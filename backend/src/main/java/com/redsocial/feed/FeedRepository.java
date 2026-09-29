package com.redsocial.feed;

import com.redsocial.post.Post;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.neo4j.driver.Driver;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Feed queries: fetches posts from users that the current user follows.
 *
 * The graph traversal is the key insight here:
 *   - In SQL you'd JOIN users → follows → posts (expensive at scale)
 *   - Here Neo4j walks zero or one [:SIGUE] edge from the user node,
 *     then [:PUBLICO] edges to posts. Zero hops includes the user's own posts.
 */
@ApplicationScoped
public class FeedRepository {

    @Inject
    Driver driver;

    /**
     * Home feed: posts from all users that currentUserId follows.
     * Paginated by skip/limit (cursor pagination can be added later).
     *
     * Cypher pattern read:
     *   Includes the viewer's own posts and posts by users they follow.
     */
    public List<Post> getHomeFeed(String currentUserId, int skip, int limit) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (me:Usuario {id: $userId})
                        MATCH (me)-[:SIGUE*0..1]->(author:Usuario)-[:PUBLICO]->(p:Post)
                        WITH DISTINCT author, p
                        // Hay que contar la VARIABLE, no count(*): un OPTIONAL MATCH
                        // sin coincidencia emite una fila con la variable en NULL, y
                        // count(*) la cuenta igual. Un post sin likes reportaría 1.
                        OPTIONAL MATCH (liker:Usuario)-[:LE_GUSTA]->(p)
                        WITH p, author, count(liker) AS likeCount
                        OPTIONAL MATCH (p)-[:TIENE_COMENTARIO]->(c:Comentario)
                        WITH p, author, likeCount, count(c) AS commentCount
                        OPTIONAL MATCH (me2:Usuario {id: $userId})-[like:LE_GUSTA]->(p)
                        RETURN p,
                               author.id       AS authorId,
                               author.username AS authorUsername,
                               author.avatarUrl AS authorAvatarUrl,
                               likeCount,
                               commentCount,
                               like IS NOT NULL  AS likedByMe
                        ORDER BY p.createdAt DESC, p.id ASC
                        SKIP $skip
                        LIMIT $limit
                        """,
                        Map.of("userId", currentUserId, "skip", skip, "limit", limit)
                );
                return result.list(row -> new Post(
                        row.get("p").asNode().get("id").asString(),
                        row.get("authorId").asString(),
                        row.get("authorUsername").asString(),
                        row.get("authorAvatarUrl").asString(""),
                        row.get("p").asNode().get("content").asString(),
                        row.get("p").asNode().get("mediaKey").asString(""),
                        row.get("p").asNode().get("mediaType").asString(""),
                        row.get("p").asNode().get("mediaSize").asLong(0),
                        java.time.Instant.parse(row.get("p").asNode().get("createdAt").asString()),
                        row.get("likeCount").asLong(),
                        row.get("commentCount").asLong(),
                        row.get("likedByMe").asBoolean()
                ));
            });
        }
    }

    /**
     * Explore feed: recent posts from users the current user does NOT follow.
     * Useful for content discovery.
     */
    public List<Post> getExploreFeed(String currentUserId, int skip, int limit) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (author:Usuario)-[:PUBLICO]->(p:Post)
                        WHERE author.id <> $userId
                          AND NOT ((:Usuario {id: $userId})-[:SIGUE]->(author))
                        // Hay que contar la VARIABLE, no count(*): un OPTIONAL MATCH
                        // sin coincidencia emite una fila con la variable en NULL, y
                        // count(*) la cuenta igual. Un post sin likes reportaría 1.
                        OPTIONAL MATCH (liker:Usuario)-[:LE_GUSTA]->(p)
                        WITH p, author, count(liker) AS likeCount
                        OPTIONAL MATCH (p)-[:TIENE_COMENTARIO]->(c:Comentario)
                        WITH p, author, likeCount, count(c) AS commentCount
                        OPTIONAL MATCH (me:Usuario {id: $userId})-[like:LE_GUSTA]->(p)
                        RETURN p,
                               author.id       AS authorId,
                               author.username AS authorUsername,
                               author.avatarUrl AS authorAvatarUrl,
                               likeCount,
                               commentCount,
                               like IS NOT NULL AS likedByMe
                        ORDER BY p.createdAt DESC, p.id ASC
                        SKIP $skip
                        LIMIT $limit
                        """,
                        Map.of("userId", currentUserId, "skip", skip, "limit", limit)
                );
                return result.list(row -> new Post(
                        row.get("p").asNode().get("id").asString(),
                        row.get("authorId").asString(),
                        row.get("authorUsername").asString(),
                        row.get("authorAvatarUrl").asString(""),
                        row.get("p").asNode().get("content").asString(),
                        row.get("p").asNode().get("mediaKey").asString(""),
                        row.get("p").asNode().get("mediaType").asString(""),
                        row.get("p").asNode().get("mediaSize").asLong(0),
                        java.time.Instant.parse(row.get("p").asNode().get("createdAt").asString()),
                        row.get("likeCount").asLong(),
                        row.get("commentCount").asLong(),
                        row.get("likedByMe").asBoolean()
                ));
            });
        }
    }

    /**
     * Visibility rule for real-time broadcasts: a viewer may see a post when
     * they are the author or they follow the author (mirrors getHomeFeed's
     * (me)-[:SIGUE*0..1]->(author) pattern for a single post).
     */
    public boolean canSeePost(String viewerId, String postId) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (author:Usuario)-[:PUBLICO]->(post:Post {id: $postId})
                        OPTIONAL MATCH (viewer:Usuario {id: $viewerId})-[f:SIGUE]->(author)
                        RETURN (viewer.id = author.id OR f IS NOT NULL) AS visible
                        """,
                        Map.of("postId", postId, "viewerId", viewerId)
                );
                if (!result.hasNext()) return false;
                return result.single().get("visible").asBoolean();
            });
        }
    }

    /**
     * All user IDs allowed to see a post (author + followers), computed with a
     * single query so a broadcast prune is O(1) queries — never N×M.
     */
    public Set<String> visibleViewerIds(String postId) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (author:Usuario)-[:PUBLICO]->(post:Post {id: $postId})
                        OPTIONAL MATCH (viewer:Usuario)-[:SIGUE]->(author)
                        WITH collect(DISTINCT author.id) + collect(DISTINCT viewer.id) AS ids
                        UNWIND ids AS viewerId
                        RETURN collect(DISTINCT viewerId) AS viewerIds
                        """,
                        Map.of("postId", postId)
                );
                if (!result.hasNext()) return new HashSet<>();
                var row = result.single();
                var list = row.get("viewerIds").asList(v -> v.asString());
                return new HashSet<>(list);
            });
        }
    }
}
