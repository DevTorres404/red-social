package com.redsocial.user;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;

/**
 * Domain model for a social-network user.
 *
 * Stored in Neo4j as: (:Usuario {id, username, email, passwordHash, bio, avatarUrl, createdAt})
 *
 * passwordHash is excluded from all JSON serialization via @JsonIgnore.
 */
public record User(
        String id,
        String username,
        String email,
        @JsonIgnore
        String passwordHash,
        String bio,
        String avatarUrl,
        Instant createdAt
) {
    /**
     * Projection used in API responses — no sensitive fields.
     */
    public UserProfile toProfile() {
        return new UserProfile(id, username, bio, avatarUrl, createdAt);
    }

    public record UserProfile(
            String id,
            String username,
            String bio,
            String avatarUrl,
            Instant createdAt
    ) {}
}
