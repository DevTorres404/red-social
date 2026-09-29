package com.redsocial.notification;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.neo4j.driver.Driver;
import org.neo4j.driver.types.Node;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * All Neo4j queries for (:Notificacion) nodes.
 *
 * Notifications are created internally (by services, not by the user directly).
 * Each notification is owned by a user via [:TIENE], optionally linked to a post
 * via [:SOBRE] and to the actor via [:GENERADA_POR].
 */
@ApplicationScoped
public class NotificationRepository {

    @Inject
    Driver driver;

    // ── Create (called internally by other services) ──────────────────────────

    /**
     * Create a notification for a target user.
     *
     * @param notifId       — stable event ID; retries do not duplicate notifications
     * @param targetUserId  — the user who will receive the notification
     * @param type          — event type (LIKE, COMMENT, FOLLOW, MENTION)
     * @param triggeredById — the user who caused the notification
     * @param postId        — optional: the related post ID (null for FOLLOW)
     */
    public Notification createOnce(String notifId, String targetUserId, String type,
                                   String triggeredById, String postId) {
        Instant now = Instant.now();

        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                String query;
                Map<String, Object> params;

                if (postId != null) {
                    query = """
                            MATCH (target:Usuario {id: $targetId}),
                                  (actor:Usuario {id: $actorId}),
                                  (p:Post {id: $postId})
                            MERGE (n:Notificacion {id: $notifId})
                              ON CREATE SET n.type = $type, n.read = false, n.createdAt = $createdAt
                            MERGE (target)-[:TIENE]->(n)
                            MERGE (n)-[:GENERADA_POR]->(actor)
                            MERGE (n)-[:SOBRE]->(p)
                            RETURN n,
                                   actor.id       AS triggeredById,
                                   actor.username AS triggeredByUsername,
                                   p.id           AS postId
                            """;
                    params = Map.of(
                            "targetId", targetUserId,
                            "actorId", triggeredById,
                            "postId", postId,
                            "notifId", notifId,
                            "type", type,
                            "createdAt", now.toString()
                    );
                } else {
                    query = """
                            MATCH (target:Usuario {id: $targetId}),
                                  (actor:Usuario {id: $actorId})
                            MERGE (n:Notificacion {id: $notifId})
                              ON CREATE SET n.type = $type, n.read = false, n.createdAt = $createdAt
                            MERGE (target)-[:TIENE]->(n)
                            MERGE (n)-[:GENERADA_POR]->(actor)
                            RETURN n,
                                   actor.id       AS triggeredById,
                                   actor.username AS triggeredByUsername,
                                   null           AS postId
                            """;
                    params = Map.of(
                            "targetId", targetUserId,
                            "actorId", triggeredById,
                            "notifId", notifId,
                            "type", type,
                            "createdAt", now.toString()
                    );
                }

                var result = tx.run(query, params);
                var row = result.single();
                return mapRow(row.get("n").asNode(),
                        row.get("triggeredById").asString(),
                        row.get("triggeredByUsername").asString(),
                        row.get("postId").isNull() ? null : row.get("postId").asString());
            });
        }
    }

    /** A post is visible in every current follower's in-app inbox, even without Web Push permission. */
    public void createForFollowers(String authorId, String postId) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                        MATCH (author:Usuario {id: $authorId})-[:PUBLICO]->(p:Post {id: $postId})
                        MATCH (recipient:Usuario)-[:SIGUE]->(author)
                        WHERE recipient <> author
                        WITH author, p, recipient,
                             'post:' + $postId + ':' + recipient.id AS notificationId
                        MERGE (n:Notificacion {id: notificationId})
                          ON CREATE SET n.type = 'POST', n.read = false, n.createdAt = $createdAt
                        MERGE (recipient)-[:TIENE]->(n)
                        MERGE (n)-[:GENERADA_POR]->(author)
                        MERGE (n)-[:SOBRE]->(p)
                        RETURN count(n) AS created
                        """, Map.of("authorId", authorId, "postId", postId,
                                "createdAt", Instant.now().toString())).consume();
                return null;
            });
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /** All notifications for a user, newest first. */
    public List<Notification> findByUser(String userId) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (u:Usuario {id: $userId})-[:TIENE]->(n:Notificacion)
                        MATCH (n)-[:GENERADA_POR]->(actor:Usuario)
                        OPTIONAL MATCH (n)-[:SOBRE]->(p:Post)
                        RETURN n,
                               actor.id       AS triggeredById,
                               actor.username AS triggeredByUsername,
                               p.id           AS postId
                        ORDER BY n.createdAt DESC, n.id ASC
                        LIMIT 200
                        """,
                        Map.of("userId", userId)
                );
                return result.list(row -> mapRow(
                        row.get("n").asNode(),
                        row.get("triggeredById").asString(),
                        row.get("triggeredByUsername").asString(),
                        row.get("postId").isNull() ? null : row.get("postId").asString()
                ));
            });
        }
    }

    public long countUnread(String userId) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (u:Usuario {id: $userId})-[:TIENE]->(n:Notificacion {read: false})
                        RETURN count(n) AS total
                        """,
                        Map.of("userId", userId)
                );
                return result.single().get("total").asLong();
            });
        }
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /** Mark all unread notifications for a user as read. */
    public void markAllAsRead(String userId) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                        MATCH (u:Usuario {id: $userId})-[:TIENE]->(n:Notificacion {read: false})
                        SET n.read = true
                        """,
                        Map.of("userId", userId)
                );
                return null;
            });
        }
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private Notification mapRow(Node node, String triggeredById,
                                String triggeredByUsername, String postId) {
        return new Notification(
                node.get("id").asString(),
                node.get("type").asString(),
                node.get("read").asBoolean(false),
                Instant.parse(node.get("createdAt").asString()),
                triggeredById,
                triggeredByUsername,
                postId
        );
    }
}
