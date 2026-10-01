package com.redsocial.auth;

import com.redsocial.auth.dto.AuthResponse;
import com.redsocial.auth.dto.LoginRequest;
import com.redsocial.auth.dto.RegisterRequest;
import com.redsocial.common.ApiError;
import com.redsocial.common.CurrentUser;
import com.redsocial.user.User;
import com.redsocial.user.UserRepository;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotAuthorizedException;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.wildfly.security.password.util.ModularCrypt;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Authentication business logic.
 *
 * JWT lifecycle (production-ready):
 *   1. User provides credentials
 *   2. We verify against Neo4j (password bcrypt check)
 *   3. We build a SHORT-LIVED signed JWT (access token, 15 min) with SmallRye JWT Build
 *   4. We create a LONG-LIVED refresh token (30 days), store its bcrypt hash + SHA-256 lookupKey in Neo4j,
 *      and send the raw token in an HttpOnly, Secure, SameSite=Strict cookie
 *   5. Client stores access token IN MEMORY ONLY (React state) and sends it as:
 *      Authorization: Bearer <access_token>
 *   6. Quarkus SmallRye JWT filter verifies signature on every request
 *   7. When access token expires, client calls POST /api/auth/refresh (with cookie)
 *      which verifies the refresh token, revokes it (rotation), issues new pair
 *   8. Logout revokes the refresh token and clears the cookie
 *
 * The private key signs; the public key verifies.
 * The backend only needs the public key at runtime — the private key
 * signs tokens during auth and can be kept separately in production.
 */
@ApplicationScoped
public class AuthService {

    // Access token: 15 minutes (short-lived, stored in memory on client)
    private static final long ACCESS_TOKEN_EXPIRY_SECONDS = 15 * 60; // 900s = 15 min

    // Hash descartable para igualar el costo de un login fallido cuando el
    // email no existe. Se genera en el class load desde un valor aleatorio:
    // siempre es un hash válido al mismo costo que los reales, nunca puede
    // coincidir con una credencial, y no deja un literal con pinta de secreto.
    private static final String DUMMY_HASH =
            io.quarkus.elytron.security.common.BcryptUtil.bcryptHash(UUID.randomUUID().toString());

    @Inject
    UserRepository userRepository;

    @Inject
    CurrentUser currentUser;

    @Inject
    RefreshTokenRepository refreshTokenRepository;

    @ConfigProperty(name = "mp.jwt.verify.issuer")
    String issuer;

    @Inject
    JsonWebToken jwt;

    public Optional<User.UserProfile> me() {
        return userRepository.findById(currentUser.id()).map(User::toProfile);
    }

    // ── Register ─────────────────────────────────────────────────────────────

    public AuthResult register(RegisterRequest req) {
        // Guard: uniqueness checks hit Neo4j with a simple MATCH + count
        if (userRepository.existsByEmail(req.email())) {
            throw new jakarta.ws.rs.WebApplicationException("Email already in use", jakarta.ws.rs.core.Response.Status.CONFLICT);
        }
        if (userRepository.existsByUsername(req.username())) {
            throw new jakarta.ws.rs.WebApplicationException("Username already taken", jakarta.ws.rs.core.Response.Status.CONFLICT);
        }

        // Hash the password — NEVER store plaintext
        String hash = hashPassword(req.password());

        // CREATE (:Usuario) node in Neo4j
        User user = userRepository.create(req.username(), req.email(), hash);

        // Build and sign the short-lived access token
        String accessToken = buildAccessToken(user);

        // Create refresh token (stored in Neo4j, raw token returned for cookie)
        var rtResult = refreshTokenRepository.create(user.id());

        return new AuthResult(
                new AuthResponse(accessToken, ACCESS_TOKEN_EXPIRY_SECONDS, user.toProfile()),
                rtResult.rawToken()
        );
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    public AuthResult login(LoginRequest req) {
        // MATCH (:Usuario) by email or username in Neo4j
        Optional<User> found = userRepository.findByEmailOrUsername(req.identifier().trim());

        // Both paths run exactly one bcrypt verification — against the real hash
        // when the user exists, against the throwaway hash when it does not.
        // Never short-circuit: answering "no such identifier" faster than "wrong
        // password" lets the response time alone enumerate registered accounts.
        String hash = found.map(User::passwordHash).orElse(DUMMY_HASH);
        boolean passwordOk = verifyPassword(req.password(), hash);

        if (found.isEmpty() || !passwordOk) {
            throw new NotAuthorizedException("Invalid credentials");
        }

        User user = found.orElseThrow();
        String accessToken = buildAccessToken(user);

        // Create refresh token
        var rtResult = refreshTokenRepository.create(user.id());

        return new AuthResult(
                new AuthResponse(accessToken, ACCESS_TOKEN_EXPIRY_SECONDS, user.toProfile()),
                rtResult.rawToken()
        );
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    /**
     * Refresh the access token using the refresh token from the HttpOnly cookie.
     * Implements token rotation: the used refresh token is revoked and a new one is issued.
     */
    public AuthResult refresh(String refreshTokenRaw) {
        if (refreshTokenRaw == null || refreshTokenRaw.isBlank()) {
            throw new NotAuthorizedException("Refresh token missing");
        }

        String lookupKey = RefreshToken.computeLookupKey(refreshTokenRaw);
        var stored = refreshTokenRepository.findValidByLookupKey(lookupKey, refreshTokenRaw);

        if (stored.isEmpty()) {
            throw new NotAuthorizedException("Invalid or expired refresh token");
        }

        RefreshToken oldToken = stored.get();

        // Revoke the used token (rotation)
        refreshTokenRepository.revokeByLookupKey(lookupKey);

        // Load user and issue new token pair
        User user = userRepository.findById(oldToken.userId())
                .orElseThrow(() -> new NotAuthorizedException("User not found"));

        String newAccessToken = buildAccessToken(user);
        var newRtResult = refreshTokenRepository.create(user.id());

        return new AuthResult(
                new AuthResponse(newAccessToken, ACCESS_TOKEN_EXPIRY_SECONDS, user.toProfile()),
                newRtResult.rawToken()
        );
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    /**
     * Revoke the refresh token from the cookie (if present).
     * The resource will clear the cookie.
     */
    public void logout(String refreshTokenRaw) {
        if (refreshTokenRaw != null && !refreshTokenRaw.isBlank()) {
            String lookupKey = RefreshToken.computeLookupKey(refreshTokenRaw);
            refreshTokenRepository.revokeByLookupKey(lookupKey);
        }
    }

    // ── Token ─────────────────────────────────────────────────────────────────

    /**
     * Build a signed JWT (access token) using SmallRye JWT Build.
     *
     * Claims included:
     *   sub   — user ID (standard: "who this token is about")
     *   upn   — username (MicroProfile JWT standard claim for principal name)
     *   groups— roles for @RolesAllowed
     *   iss   — issuer (must match mp.jwt.verify.issuer)
     *   exp   — short expiry (15 minutes)
     *
     * Signing uses the private key at: smallrye.jwt.sign.key.location
     */
    private String buildAccessToken(User user) {
        return Jwt.issuer(issuer)
                .subject(user.id())
                .upn(user.username())
                .groups(Set.of("user"))
                .claim("email", user.email())
                .claim("username", user.username())
                .expiresIn(Duration.ofSeconds(ACCESS_TOKEN_EXPIRY_SECONDS))
                .sign();
    }

    // ── Password helpers ──────────────────────────────────────────────────────

    private String hashPassword(String plaintext) {
        return io.quarkus.elytron.security.common.BcryptUtil.bcryptHash(plaintext);
    }

    private boolean verifyPassword(String plaintext, String hash) {
        return io.quarkus.elytron.security.common.BcryptUtil.matches(plaintext, hash);
    }

    private String hashToken(String token) {
        return io.quarkus.elytron.security.common.BcryptUtil.bcryptHash(token);
    }
}
