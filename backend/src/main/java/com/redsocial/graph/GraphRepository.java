package com.redsocial.graph;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.neo4j.driver.Driver;

import java.util.List;
import java.util.Map;

/** Bounded, parameterized traversals over the social graph. */
@ApplicationScoped
public class GraphRepository {
    @Inject Driver driver;

    public record Person(String id, String username) {}
    public record Reachable(String id, String username, int distance, String viaId) {}
    public record Recommendation(String id, String username, long mutualCount, List<String> viaUsernames) {}
    public record NetworkPost(String id, String authorId, String authorUsername, String content,
                              String createdAt, long likeCount) {}

    // Q1: common outgoing follows, not merely shared incoming followers.
    public List<Person> commonFollowing(String userId, String otherId) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                    MATCH (:Usuario {id: $userId})-[:SIGUE]->(common:Usuario)<-[:SIGUE]-(:Usuario {id: $otherId})
                    RETURN DISTINCT common.id AS id, common.username AS username
                    ORDER BY username, id LIMIT 50
                    """, Map.of("userId", userId, "otherId", otherId))
                    .list(r -> new Person(r.get("id").asString(), r.get("username").asString())));
        }
    }

    // Q2: shortest path in 1-2 directed hops; one deterministic intermediary per target.
    public List<Reachable> reachable(String userId) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                    MATCH path=(me:Usuario {id: $userId})-[:SIGUE*1..2]->(target:Usuario)
                    WHERE target <> me AND all(n IN nodes(path) WHERE single(x IN nodes(path) WHERE x = n))
                    WITH target, path ORDER BY length(path), [n IN nodes(path) | n.id]
                    WITH target, head(collect(path)) AS path
                    RETURN target.id AS id, target.username AS username,
                           length(path) AS distance, nodes(path)[1].id AS viaId
                    ORDER BY distance, username, id LIMIT 100
                    """, Map.of("userId", userId))
                    .list(r -> new Reachable(r.get("id").asString(), r.get("username").asString(),
                            r.get("distance").asInt(), r.get("viaId").asString())));
        }
    }

    // Q3: explainable friends-of-friends, excluding self and already followed users.
    public List<Recommendation> recommendations(String userId) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                    MATCH (me:Usuario {id: $userId})-[:SIGUE]->(via:Usuario)-[:SIGUE]->(candidate:Usuario)
                    WHERE candidate <> me AND NOT (me)-[:SIGUE]->(candidate)
                    WITH candidate, via ORDER BY via.username
                    WITH candidate, collect(DISTINCT via.username) AS mutuals,
                         count(DISTINCT via) AS mutualCount
                    RETURN candidate.id AS id, candidate.username AS username,
                           mutualCount, mutuals
                    ORDER BY mutualCount DESC, username, id LIMIT 50
                    """, Map.of("userId", userId))
                    .list(r -> new Recommendation(r.get("id").asString(), r.get("username").asString(),
                            r.get("mutualCount").asLong(), r.get("mutuals").asList(v -> v.asString()))));
        }
    }

    // Q4: only direct followed authors; deterministic newest-first order.
    public List<NetworkPost> networkPosts(String userId) {
        return posts(userId, false);
    }

    // Q5: same network, ranked by unique reactions then date and ID.
    public List<NetworkPost> trendingPosts(String userId) {
        return posts(userId, true);
    }

    private List<NetworkPost> posts(String userId, boolean trending) {
        String order = trending ? "likeCount DESC, createdAt DESC, id" : "createdAt DESC, id";
        try (var session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                    MATCH (:Usuario {id: $userId})-[:SIGUE]->(author:Usuario)-[:PUBLICO]->(p:Post)
                    OPTIONAL MATCH (liker:Usuario)-[:LE_GUSTA]->(p)
                    WITH author, p, count(DISTINCT liker) AS likeCount
                    RETURN p.id AS id, author.id AS authorId, author.username AS authorUsername,
                           p.content AS content, p.createdAt AS createdAt, likeCount
                    ORDER BY """ + " " + order + " LIMIT 50", Map.of("userId", userId))
                    .list(r -> new NetworkPost(r.get("id").asString(), r.get("authorId").asString(),
                            r.get("authorUsername").asString(), r.get("content").asString(),
                            r.get("createdAt").asString(), r.get("likeCount").asLong())));
        }
    }
}
