package com.redsocial.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Refresh token stored in Neo4j for revocation support.
 * The actual token sent to the client is a random opaque string.
 * We store:
 *   - tokenHash: bcrypt hash (for secure verification via BcryptUtil.matches)
 *   - lookupKey: SHA-256 of raw token (deterministic, for exact DB lookup)
 * The token itself is never recoverable from the database.
 */
public record RefreshToken(
        String id,
        String userId,
        String tokenHash,   // bcrypt hash
        String lookupKey,   // SHA-256 of raw token (for exact lookup)
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt
) {

    public boolean isActive() {
        return revokedAt == null && Instant.now().isBefore(expiresAt);
    }

    public static String generateToken() {
        return UUID.randomUUID().toString() + "." + UUID.randomUUID().toString();
    }

    public static String computeLookupKey(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}