package com.redsocial.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.neo4j.driver.Driver;
import org.neo4j.driver.exceptions.ClientException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Neo4j persistence for refresh tokens.
 * Only the hash is stored; the token itself is opaque and never recoverable.
 */
@ApplicationScoped
public class RefreshTokenRepository {

    @Inject
    Driver driver;

    private static final int REFRESH_TOKEN_EXPIRY_DAYS = 30;
    private static final int REFRESH_TOKEN_EXPIRY_SECONDS = REFRESH_TOKEN_EXPIRY_DAYS * 24 * 60 * 60;

    /**
     * Result of creating a refresh token: the entity (with hash) and the raw token for the cookie.
     */
    public record CreateResult(RefreshToken token, String rawToken) {}

    /**
     * Create and store a new refresh token for a user.
     * Returns both the entity (with hash) and the raw token (caller must send it in a cookie).
     */
    public CreateResult create(String userId) {
        String rawToken = RefreshToken.generateToken();
        String tokenHash = hashToken(rawToken);
        String lookupKey = RefreshToken.computeLookupKey(rawToken);
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(REFRESH_TOKEN_EXPIRY_SECONDS);

        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                var result = tx.run(
                        """
                        CREATE (rt:RefreshToken {
                            id: $id,
                            userId: $userId,
                            tokenHash: $tokenHash,
                            lookupKey: $lookupKey,
                            createdAt: $createdAt,
                            expiresAt: $expiresAt
                        })
                        RETURN rt
                        """,
                        java.util.Map.of(
                                "id", id,
                                "userId", userId,
                                "tokenHash", tokenHash,
                                "lookupKey", lookupKey,
                                "createdAt", now.toString(),
                                "expiresAt", expiresAt.toString()
                        )
                );
                RefreshToken entity = mapToken(result.single().get("rt").asNode());
                return new CreateResult(entity, rawToken);
            });
        } catch (ClientException ex) {
            throw ex;
        }
    }

    /**
     * Find a valid (non-revoked, non-expired) refresh token by its lookupKey.
     * Then verifies the raw token against the stored bcrypt hash.
     */
    public Optional<RefreshToken> findValidByLookupKey(String lookupKey, String rawToken) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run(
                        """
                        MATCH (rt:RefreshToken {lookupKey: $lookupKey})
                        WHERE rt.revokedAt IS NULL AND rt.expiresAt > $now
                        RETURN rt
                        """,
                        java.util.Map.of("lookupKey", lookupKey, "now", Instant.now().toString())
                );
                if (!result.hasNext()) return Optional.empty();
                RefreshToken token = mapToken(result.single().get("rt").asNode());
                // Verify raw token matches stored bcrypt hash
                if (verifyToken(rawToken, token.tokenHash())) {
                    return Optional.of(token);
                }
                return Optional.empty();
            });
        }
    }

    /**
     * Revoke a refresh token by its lookupKey (used on logout or token rotation).
     */
    public void revokeByLookupKey(String lookupKey) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run(
                        """
                        MATCH (rt:RefreshToken {lookupKey: $lookupKey})
                        SET rt.revokedAt = $now
                        """,
                        java.util.Map.of("lookupKey", lookupKey, "now", Instant.now().toString())
                ).consume();
                return null;
            });
        }
    }

    /**
     * Revoke all refresh tokens for a user (used on password change, security event).
     */
    public void revokeAllForUser(String userId) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> tx.run(
                    """
                    MATCH (rt:RefreshToken {userId: $userId})
                    WHERE rt.revokedAt IS NULL
                    SET rt.revokedAt = $now
                    """,
                    java.util.Map.of("userId", userId, "now", Instant.now().toString())
            ));
        }
    }

    /**
     * Delete expired/revoked tokens older than a threshold (cleanup job).
     */
    public long cleanupOldTokens(Instant olderThan) {
        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                var result = tx.run(
                        """
                        MATCH (rt:RefreshToken)
                        WHERE rt.revokedAt IS NOT NULL AND rt.revokedAt < $threshold
                           OR rt.expiresAt < $threshold
                        DELETE rt
                        RETURN count(rt) AS deleted
                        """,
                        java.util.Map.of("threshold", olderThan.toString())
                );
                return result.single().get("deleted").asLong();
            });
        }
    }

    private String hashToken(String token) {
        return io.quarkus.elytron.security.common.BcryptUtil.bcryptHash(token);
    }

    private boolean verifyToken(String rawToken, String hash) {
        return io.quarkus.elytron.security.common.BcryptUtil.matches(rawToken, hash);
    }

    private RefreshToken mapToken(org.neo4j.driver.types.Node node) {
        return new RefreshToken(
                node.get("id").asString(),
                node.get("userId").asString(),
                node.get("tokenHash").asString(),
                node.get("lookupKey").asString(""),
                Instant.parse(node.get("createdAt").asString()),
                Instant.parse(node.get("expiresAt").asString()),
                node.get("revokedAt").isNull() ? null : Instant.parse(node.get("revokedAt").asString())
        );
    }
}