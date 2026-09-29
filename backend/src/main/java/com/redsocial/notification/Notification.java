package com.redsocial.notification;

import java.time.Instant;

/**
 * Domain model for in-app notifications.
 *
 * Stored in Neo4j as: (:Notificacion {id, type, read, createdAt})
 *
 * Relationships:
 *   (:Usuario)-[:TIENE]->(:Notificacion)          — owner
 *   (:Notificacion)-[:SOBRE]->(:Post)             — optional: what post triggered it
 *   (:Notificacion)-[:GENERADA_POR]->(:Usuario)   — optional: who triggered it
 *
 * Type values (kept as plain strings for flexibility):
 *   "LIKE"      — someone liked your post
 *   "COMMENT"   — someone commented on your post
 *   "FOLLOW"    — someone started following you
 *   "MENTION"   — someone mentioned you
 */
public record Notification(
        String id,
        String type,
        boolean read,
        Instant createdAt,
        String triggeredByUserId,
        String triggeredByUsername,
        String postId            // nullable — only for LIKE, COMMENT
) {}
