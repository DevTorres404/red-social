package com.redsocial.post;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Domain model for a social-network post.
 *
 * Stored in Neo4j as: (:Post {id, content, mediaKey, mediaType, mediaSize, createdAt})
 *
 * likeCount and commentCount are computed at query time via relationship traversal,
 * not stored as properties — keeping the graph as the source of truth.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Post(
        String id,
        String authorId,
        String authorUsername,
        String authorAvatarUrl,
        String content,
        String mediaKey,
        String mediaType,
        long mediaSize,
        Instant createdAt,
        long likeCount,
        long commentCount,
        boolean likedByCurrentUser
) {

    /** Compact projection used in feed responses. */
    public record PostSummary(
            String id,
            String authorId,
            String authorUsername,
            String authorAvatarUrl,
            String content,
            String mediaKey,
            String mediaType,
            long mediaSize,
            Instant createdAt,
            long likeCount,
            long commentCount,
            boolean likedByCurrentUser
    ) {}

    public PostSummary toSummary() {
        return new PostSummary(id, authorId, authorUsername, authorAvatarUrl, content, mediaKey, mediaType, mediaSize,
                createdAt, likeCount, commentCount, likedByCurrentUser);
    }

    /** Comment nested inside a post detail response. */
    public record Comment(
            String id,
            String authorId,
            String authorUsername,
            String text,
            Instant createdAt
    ) {}
}
