package com.redsocial.auth;

import com.redsocial.auth.dto.AuthResponse;
import com.redsocial.auth.dto.LoginRequest;
import com.redsocial.auth.dto.RegisterRequest;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.core.Context;
import io.vertx.core.http.HttpServerRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Locale;

/**
 * Authentication REST endpoints.
 *
 * Registration and login are PUBLIC.
 * Refresh requires a valid refresh token from the HttpOnly cookie.
 * Logout clears the refresh token cookie.
 *
 * Cookie settings for refresh token:
 *   - HttpOnly: prevents XSS theft
 *   - Secure: only sent over HTTPS (required for production)
 *   - SameSite=Strict: CSRF protection
 *   - Path=/api/auth: sent to refresh and logout, not to unrelated API routes
 *   - Max-Age: 30 days (matches refresh token TTL)
 */
@Path("/api/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Authentication")
public class AuthResource {

    @Inject
    AuthService authService;

    @Inject
    LoginRateLimiter loginRateLimiter;

    @Context
    HttpServerRequest httpRequest;

    private static final String REFRESH_COOKIE_NAME = "rt";
    private static final int REFRESH_COOKIE_MAX_AGE = 30 * 24 * 60 * 60; // 30 days
    private static final String REFRESH_COOKIE_PATH = "/api/auth";
    private static final String LEGACY_REFRESH_COOKIE_PATH = "/api/auth/refresh";

    private NewCookie buildRefreshCookie(String token, boolean isLogout) {
        return new NewCookie.Builder(REFRESH_COOKIE_NAME)
                .value(isLogout ? "" : token)
                .path(REFRESH_COOKIE_PATH)
                .httpOnly(true)
                .secure(true) // In dev, localhost is treated as secure context
                .sameSite(NewCookie.SameSite.STRICT)
                .maxAge(isLogout ? 0 : REFRESH_COOKIE_MAX_AGE)
                .build();
    }

    // Clear cookies issued before logout was included in the cookie path.
    private NewCookie clearLegacyRefreshCookie() {
        return new NewCookie.Builder(REFRESH_COOKIE_NAME)
                .value("")
                .path(LEGACY_REFRESH_COOKIE_PATH)
                .httpOnly(true)
                .secure(true)
                .sameSite(NewCookie.SameSite.STRICT)
                .maxAge(0)
                .build();
    }

    /**
     * POST /api/auth/register
     *
     * Creates a new Usuario node in Neo4j and returns a signed JWT.
     * Also sets the refresh token in an HttpOnly cookie.
     * @Valid triggers Jakarta Bean Validation on the request body.
     */
    @POST
    @Path("/register")
    @Operation(summary = "Register a new user")
    public Response register(@Valid RegisterRequest request) {
        AuthResult result = authService.register(request);
        return Response.status(Response.Status.CREATED)
                .entity(result.response())
                .cookie(buildRefreshCookie(result.rawRefreshToken(), false), clearLegacyRefreshCookie())
                .build();
    }

    /**
     * POST /api/auth/login
     *
     * Validates credentials against Neo4j (bcrypt comparison) and returns a JWT.
     * Also sets the refresh token in an HttpOnly cookie.
     * Public endpoint: protegido contra fuerza bruta combinando ventana fija por
     * identificador (usuario/email) y por IP del cliente, mitigando tanto fuerza 
     * bruta directa como Password Spraying (ataque horizontal).
     */
    @POST
    @Path("/login")
    @Operation(summary = "Login with email or username and password")
    public Response login(@Valid LoginRequest request) {
        String identifierKey = loginKey(request.identifier());
        String ipKey = "ip:" + getClientIp();

        if (!loginRateLimiter.allow(identifierKey) || !loginRateLimiter.allow(ipKey)) {
            throw new WebApplicationException(
                    "Demasiados intentos de inicio de sesión. Intente de nuevo en unos minutos.",
                    Response.Status.TOO_MANY_REQUESTS);
        }
        try {
            AuthResult result = authService.login(request);
            loginRateLimiter.onSuccess(identifierKey);
            loginRateLimiter.onSuccess(ipKey);
            return Response.ok(result.response())
                    .cookie(buildRefreshCookie(result.rawRefreshToken(), false), clearLegacyRefreshCookie())
                    .build();
        } catch (NotAuthorizedException ex) {
            loginRateLimiter.registerFailure(identifierKey);
            loginRateLimiter.registerFailure(ipKey);
            throw ex;
        }
    }

    private String getClientIp() {
        if (httpRequest == null) return "unknown";
        // En producción detrás de Cloudflare, este es inyectado de forma segura.
        String cf = httpRequest.getHeader("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) return cf.trim();
        // Fallback estándar a proxys genéricos.
        String xff = httpRequest.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return httpRequest.remoteAddress() != null ? httpRequest.remoteAddress().hostAddress() : "unknown";
    }

    /**
     * Usa la misma clave para variantes triviales del mismo identificador.
     * No incluye cabeceras controladas por el cliente.
     */
    static String loginKey(String identifier) {
        return "account:" + identifier.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * POST /api/auth/refresh
     *
     * Reads the refresh token from the HttpOnly cookie, verifies it,
     * rotates it (revokes old, issues new), and returns a new access token.
     * Sets the new refresh token in the cookie.
     */
    @POST
    @Path("/refresh")
    @Operation(summary = "Refresh JWT token using HttpOnly cookie")
    public Response refresh(@jakarta.ws.rs.CookieParam("rt") String refreshToken) {
        AuthResult result = authService.refresh(refreshToken);
        return Response.ok(result.response())
                .cookie(buildRefreshCookie(result.rawRefreshToken(), false), clearLegacyRefreshCookie())
                .build();
    }

    /**
     * POST /api/auth/logout
     *
     * Revokes the refresh token and clears the cookie.
     * Does not require a valid access token (works even with expired access token).
     */
    @POST
    @Path("/logout")
    @Operation(summary = "Logout and revoke refresh token")
    public Response logout(@jakarta.ws.rs.CookieParam("rt") String refreshToken) {
        authService.logout(refreshToken);
        return Response.ok()
                .cookie(buildRefreshCookie("", true), clearLegacyRefreshCookie())
                .build();
    }

    @GET
    @Path("/me")
    @RolesAllowed("user")
    @Operation(summary = "Get current authenticated user")
    public Response me() {
        return authService.me()
                .map(profile -> Response.ok(profile).build())
                .orElse(Response.status(Response.Status.UNAUTHORIZED).build());
    }
}
