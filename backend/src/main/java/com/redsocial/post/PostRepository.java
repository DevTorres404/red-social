package com.redsocial.post;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import org.neo4j.driver.Driver;
import org.neo4j.driver.types.Node;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * All Neo4j queries related to (:Post) nodes and their relationships.
 *
 * Key relationships managed here:
 *   (:Usuario)-[:PUBLICO]->(:Post)        — authorship
 *   (:Usuario)-[:LE_GUSTA]->(:Post)       — likes (idempotent via MERGE)
 *   (:Post)-[:TIENE_COMENTARIO]->(:Comentario)
 *   (:Usuario)-[:COMENTO]->(:Comentario)
 */
@ApplicationScoped
public class PostRepository {

    @Inject
    Driver driver;

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Creates a (:Post) node and links it to its author with [:PUBLICO].
     */
    public Post create(String authorId, String content, MediaAsset media) {
        String postId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String mediaKey = media != null ? media.key() : "";
        String mediaType = media != null ? media.contentType() : "";
        long mediaSize = media != null ? media.size() : 0;

        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                var result = tx.run("""
                        MATCH (author:Usuario {id: $authorId})
                        CREATE (p:Post {
                            id:        $postId,
                            content:   $content,
                            mediaKey:  $mediaKey,
                            mediaType: $mediaType,
                            mediaSize: $mediaSize,
                            createdAt: $createdAt
                        })
                        CREATE (author)-[:PUBLICO]->(p)
                        RETURN p,
                               author.id       AS authorId,
                               author.username AS authorUsername,
                               author.avatarUrl AS authorAvatarUrl
                        """,
                        Map.of(
                                "authorId", authorId,
                                "postId", postId,
                                "content", content,
                                "mediaKey", mediaKey,
                                "mediaType", mediaType,
                                "mediaSize", mediaSize,
                                "createdAt", now.toString()
                        )
                );
                var row = result.single();
                tx.run("""
                        MATCH (author:Usuario {id: $authorId})
                        MATCH (follower:Usuario)-[:SIGUE]->(author)
                        MATCH (follower)-[:HAS_SUBSCRIPTION]->(s:PushSubscription)
                        MERGE (d:PushDelivery {id: $postId + '|' + s.id})
                        ON CREATE SET d.postId = $postId, d.recipientId = follower.id,
                                      d.subscriptionId = s.id, d.status = 'PENDING',
                                      d.attempts = 0, d.nextAt = $createdAt
                        """, Map.of("authorId", authorId, "postId", postId, "createdAt", now.toString())).consume();
                return mapRow(row.get("p").asNode(),
                        row.get("authorId").asString(),
                        row.get("authorUsername").asString(),
                        row.get("authorAvatarUrl").asString(""),
                        0, 0, false);
            });
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    public Optional<Post> findById(String postId, String currentUserId) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (author:Usuario)-[:PUBLICO]->(p:Post {id: $postId})
                        // Hay que contar la VARIABLE, no count(*): un OPTIONAL MATCH
                        // sin coincidencia emite una fila con la variable en NULL, y
                        // count(*) la cuenta igual. Un post sin likes reportaría 1.
                        OPTIONAL MATCH (liker:Usuario)-[:LE_GUSTA]->(p)
                        WITH p, author, count(liker) AS likeCount
                        OPTIONAL MATCH (p)-[:TIENE_COMENTARIO]->(c:Comentario)
                        WITH p, author, likeCount, count(c) AS commentCount
                        OPTIONAL MATCH (me:Usuario {id: $currentUserId})-[like:LE_GUSTA]->(p)
                        RETURN p,
                               author.id       AS authorId,
                               author.username AS authorUsername,
                               author.avatarUrl AS authorAvatarUrl,
                               likeCount,
                               commentCount,
                               like IS NOT NULL AS likedByMe
                        """,
                        Map.of("postId", postId, "currentUserId", currentUserId)
                );
                if (!result.hasNext()) return Optional.empty();
                var row = result.single();
                return Optional.of(mapRow(
                        row.get("p").asNode(),
                        row.get("authorId").asString(),
                        row.get("authorUsername").asString(),
                        row.get("authorAvatarUrl").asString(""),
                        row.get("likeCount").asLong(),
                        row.get("commentCount").asLong(),
                        row.get("likedByMe").asBoolean()
                ));
            });
        }
    }

    /** Posts published by a specific user, newest first. */
    public List<Post> findByAuthor(String authorId, String currentUserId) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (author:Usuario {id: $authorId})-[:PUBLICO]->(p:Post)
                        // Hay que contar la VARIABLE, no count(*): un OPTIONAL MATCH
                        // sin coincidencia emite una fila con la variable en NULL, y
                        // count(*) la cuenta igual. Un post sin likes reportaría 1.
                        OPTIONAL MATCH (liker:Usuario)-[:LE_GUSTA]->(p)
                        WITH p, author, count(liker) AS likeCount
                        OPTIONAL MATCH (p)-[:TIENE_COMENTARIO]->(c:Comentario)
                        WITH p, author, likeCount, count(c) AS commentCount
                        OPTIONAL MATCH (me:Usuario {id: $currentUserId})-[like:LE_GUSTA]->(p)
                        RETURN p,
                               author.id       AS authorId,
                               author.username AS authorUsername,
                               author.avatarUrl AS authorAvatarUrl,
                               likeCount,
                               commentCount,
                               like IS NOT NULL AS likedByMe
                        ORDER BY p.createdAt DESC, p.id ASC
                        LIMIT 200
                        """,
                        Map.of("authorId", authorId, "currentUserId", currentUserId)
                );
                return result.list(row -> mapRow(
                        row.get("p").asNode(),
                        row.get("authorId").asString(),
                        row.get("authorUsername").asString(),
                        row.get("authorAvatarUrl").asString(""),
                        row.get("likeCount").asLong(),
                        row.get("commentCount").asLong(),
                        row.get("likedByMe").asBoolean()
                ));
            });
        }
    }

    /** Comments for a post, oldest first. */
    public List<Post.Comment> findComments(String postId) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (p:Post {id: $postId})-[:TIENE_COMENTARIO]->(c:Comentario)
                        MATCH (author:Usuario)-[:COMENTO]->(c)
                        RETURN c,
                               author.id       AS authorId,
                               author.username AS authorUsername
                        ORDER BY c.createdAt ASC
                        LIMIT 200
                        """,
                        Map.of("postId", postId)
                );
                return result.list(row -> mapComment(
                        row.get("c").asNode(),
                        row.get("authorId").asString(),
                        row.get("authorUsername").asString()
                ));
            });
        }
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    public String delete(String postId, String requestingUserId) {
        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                // Only the author can delete their post
                var result = tx.run("""
                        MATCH (author:Usuario {id: $userId})-[:PUBLICO]->(p:Post {id: $postId})
                        OPTIONAL MATCH (p)-[:TIENE_COMENTARIO]->(c:Comentario)
                        WITH p, collect(DISTINCT c) AS comments
                        OPTIONAL MATCH (n:Notificacion)-[:SOBRE]->(p)
                        WITH p, comments, collect(DISTINCT n) AS notifications
                        WITH p, comments, notifications, coalesce(p.mediaKey, '') AS mediaKey
                        FOREACH (key IN CASE WHEN mediaKey = '' THEN [] ELSE [mediaKey] END |
                            MERGE (cleanup:MediaCleanup {key: key})
                            ON CREATE SET cleanup.createdAt = $now)
                        FOREACH (comment IN comments | DETACH DELETE comment)
                        FOREACH (notification IN notifications | DETACH DELETE notification)
                        DETACH DELETE p
                        RETURN mediaKey
                        """,
                        Map.of("postId", postId, "userId", requestingUserId, "now", Instant.now().toString())
                );
                if (!result.hasNext()) {
                    throw new NotFoundException("Post not found or not owned by user");
                }
                return result.single().get("mediaKey").asString();
            });
        }
    }

    public void queueMediaCleanup(String key) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MERGE (cleanup:MediaCleanup {key: $key}) ON CREATE SET cleanup.createdAt = $now",
                        Map.of("key", key, "now", Instant.now().toString()));
                return null;
            });
        }
    }

    public List<String> pendingMediaCleanup() {
        try (var session = driver.session()) {
            return session.executeRead(tx -> tx.run(
                    "MATCH (cleanup:MediaCleanup) RETURN cleanup.key AS key ORDER BY cleanup.createdAt LIMIT 100")
                    .list(row -> row.get("key").asString()));
        }
    }

    public void completeMediaCleanup(String key) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (cleanup:MediaCleanup {key: $key}) DELETE cleanup", Map.of("key", key));
                return null;
            });
        }
    }

    // ── Likes ─────────────────────────────────────────────────────────────────

    /**
     * Like a post once (idempotent via MERGE).
     *
     * @return true when the like relationship was actually created, false on a duplicate
     */
    public boolean like(String userId, String postId) {
        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                var result = tx.run("""
                        MATCH (u:Usuario {id: $userId}), (p:Post {id: $postId})
                        OPTIONAL MATCH (u)-[r:LE_GUSTA]->(p)
                        WITH u, p, collect(r) AS likes
                        WITH u, p, likes, size(likes) = 0 AS needsLike
                        FOREACH (ignore IN CASE WHEN needsLike THEN [1] ELSE [] END |
                            MERGE (u)-[:LE_GUSTA]->(p))
                        RETURN count(p) AS existing, needsLike AS created
                        """,
                        Map.of("userId", userId, "postId", postId)
                );
                if (!result.hasNext()) {
                    throw new NotFoundException("Post not found: " + postId);
                }
                var row = result.single();
                if (row.get("existing").asLong() == 0) {
                    throw new NotFoundException("Post not found: " + postId);
                }
                return row.get("created").asBoolean();
            });
        }
    }

    /**
     * Unlike a post. Deleting a like that does not exist is a no-op.
     *
     * @return true when a like relationship was actually deleted, false otherwise
     */
    public boolean unlike(String userId, String postId) {
        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                var result = tx.run("""
                        MATCH (p:Post {id: $postId})
                        OPTIONAL MATCH (u:Usuario {id: $userId})-[r:LE_GUSTA]->(p)
                        WITH p, collect(r) AS likes
                        WITH p, likes, size(likes) > 0 AS hadLike
                        FOREACH (like IN likes | DELETE like)
                        RETURN count(p) AS existing, hadLike AS deleted
                        """,
                        Map.of("userId", userId, "postId", postId)
                );
                if (!result.hasNext()) {
                    throw new NotFoundException("Post not found: " + postId);
                }
                var row = result.single();
                if (row.get("existing").asLong() == 0) {
                    throw new NotFoundException("Post not found: " + postId);
                }
                return row.get("deleted").asBoolean();
            });
        }
    }

    // ── Comments ──────────────────────────────────────────────────────────────

    public Post.Comment addComment(String postId, String authorId, String text) {
        String commentId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                var result = tx.run("""
                        MATCH (author:Usuario {id: $authorId}), (p:Post {id: $postId})
                        CREATE (c:Comentario {
                            id:        $commentId,
                            text:      $text,
                            createdAt: $createdAt
                        })
                        CREATE (p)-[:TIENE_COMENTARIO]->(c)
                        CREATE (author)-[:COMENTO]->(c)
                        RETURN c,
                               author.id       AS authorId,
                               author.username AS authorUsername
                        """,
                        Map.of(
                                "postId", postId,
                                "authorId", authorId,
                                "commentId", commentId,
                                "text", text,
                                "createdAt", now.toString()
                        )
                );
                if (!result.hasNext()) {
                    throw new NotFoundException("Post not found: " + postId);
                }
                var row = result.single();
                return mapComment(row.get("c").asNode(),
                        row.get("authorId").asString(),
                        row.get("authorUsername").asString());
            });
        }
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private Post mapRow(Node node, String authorId, String authorUsername, String authorAvatarUrl,
                        long likeCount, long commentCount, boolean likedByCurrentUser) {
        return new Post(
                node.get("id").asString(),
                authorId,
                authorUsername,
                authorAvatarUrl,
                node.get("content").asString(),
                node.get("mediaKey").asString(""),
                node.get("mediaType").asString(""),
                node.get("mediaSize").asLong(0),
                Instant.parse(node.get("createdAt").asString()),
                likeCount,
                commentCount,
                likedByCurrentUser
        );
    }

    private Post.Comment mapComment(Node node, String authorId, String authorUsername) {
        return new Post.Comment(
                node.get("id").asString(),
                authorId,
                authorUsername,
                node.get("text").asString(),
                Instant.parse(node.get("createdAt").asString())
        );
    }
}
