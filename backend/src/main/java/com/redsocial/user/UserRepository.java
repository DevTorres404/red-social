package com.redsocial.user;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.neo4j.driver.Driver;
import org.neo4j.driver.exceptions.ClientException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * All Neo4j queries related to the Usuario node.
 *
 * Key concept: there are no tables here. We pattern-match nodes and traverse
 * relationships. MERGE creates a node only if it doesn't exist (like upsert).
 */
@ApplicationScoped
public class UserRepository {

    @Inject
    Driver driver;

    // ── Read ─────────────────────────────────────────────────────────────────

    public Optional<User> findById(String id) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run(
                        "MATCH (u:Usuario {id: $id}) RETURN u",
                        Map.of("id", id)
                );
                if (!result.hasNext()) return Optional.empty();
                return Optional.of(mapNode(result.single().get("u").asNode()));
            });
        }
    }

    public Optional<User> findByEmail(String email) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run(
                        "MATCH (u:Usuario {email: $email}) RETURN u",
                        Map.of("email", email)
                );
                if (!result.hasNext()) return Optional.empty();
                return Optional.of(mapNode(result.single().get("u").asNode()));
            });
        }
    }

    public Optional<User> findByEmailOrUsername(String identifier) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                // Prefer an exact email match when legacy data has a username
                // equal to another account's email. Both lookups use the unique
                // indexes; an OR query with result.single() would throw a 500.
                var byEmail = tx.run("MATCH (u:Usuario {email: $id}) RETURN u", Map.of("id", identifier));
                if (byEmail.hasNext()) return Optional.of(mapNode(byEmail.single().get("u").asNode()));
                var byUsername = tx.run("MATCH (u:Usuario {username: $id}) RETURN u", Map.of("id", identifier));
                if (!byUsername.hasNext()) return Optional.empty();
                return Optional.of(mapNode(byUsername.single().get("u").asNode()));
            });
        }
    }

    public Optional<User> findByUsername(String username) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run(
                        "MATCH (u:Usuario {username: $username}) RETURN u",
                        Map.of("username", username)
                );
                if (!result.hasNext()) return Optional.empty();
                return Optional.of(mapNode(result.single().get("u").asNode()));
            });
        }
    }

    public boolean existsByEmail(String email) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run(
                        "MATCH (u:Usuario {email: $email}) RETURN count(u) AS c",
                        Map.of("email", email)
                );
                return result.single().get("c").asLong() > 0;
            });
        }
    }

    public boolean existsByUsername(String username) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run(
                        "MATCH (u:Usuario {username: $username}) RETURN count(u) AS c",
                        Map.of("username", username)
                );
                return result.single().get("c").asLong() > 0;
            });
        }
    }

    // ── Write ────────────────────────────────────────────────────────────────

    /**
     * CREATE a new (:Usuario) node.
     *
     * In Neo4j we don't INSERT INTO a table — we CREATE a node with a label.
     * The RETURN clause gives us back the created node immediately.
     */
    public User create(String username, String email, String passwordHash) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();

        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                var result = tx.run(
                        """
                        CREATE (u:Usuario {
                            id:           $id,
                            username:     $username,
                            email:        $email,
                            passwordHash: $passwordHash,
                            bio:          '',
                            avatarUrl:    '',
                            createdAt:    $createdAt
                        })
                        RETURN u
                        """,
                        Map.of(
                                "id", id,
                                "username", username,
                                "email", email,
                                "passwordHash", passwordHash,
                                "createdAt", now.toString()
                        )
                );
                return mapNode(result.single().get("u").asNode());
            });
        } catch (ClientException ex) {
            // The preflight uniqueness checks are not atomic. The database
            // constraint remains authoritative when two registrations race.
            if (ex.code().contains("ConstraintValidationFailed")) {
                throw new WebApplicationException("Email or username already in use", Response.Status.CONFLICT);
            }
            throw ex;
        }
    }

    public User update(String id, String bio, String avatarUrl) {
        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                var result = tx.run(
                        """
                        MATCH (u:Usuario {id: $id})
                        SET u.bio = $bio, u.avatarUrl = $avatarUrl
                        RETURN u
                        """,
                        Map.of("id", id, "bio", bio, "avatarUrl", avatarUrl)
                );
                if (!result.hasNext()) throw new NotFoundException("User not found: " + id);
                return mapNode(result.single().get("u").asNode());
            });
        }
    }

    // ── Social graph ─────────────────────────────────────────────────────────

    /**
     * Create [:SIGUE] relationship between two users.
     *
     * MERGE ensures we don't create duplicate relationships —
     * it's idempotent: run it twice, get one edge.
     */
    public void follow(String followerId, String followedId) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                var result = tx.run(
                        """
                        MATCH (a:Usuario {id: $followerId}), (b:Usuario {id: $followedId})
                        MERGE (a)-[:SIGUE]->(b)
                        RETURN count(b) AS followed
                        """,
                        Map.of("followerId", followerId, "followedId", followedId)
                );
                if (result.single().get("followed").asLong() == 0) {
                    throw new NotFoundException("User not found: " + followedId);
                }
                return null;
            });
        }
    }

    public void unfollow(String followerId, String followedId) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                var target = tx.run("MATCH (u:Usuario {id: $id}) RETURN count(u) AS count",
                        Map.of("id", followedId));
                if (target.single().get("count").asLong() == 0) {
                    throw new NotFoundException("User not found: " + followedId);
                }
                tx.run(
                        """
                        MATCH (a:Usuario {id: $followerId})-[r:SIGUE]->(b:Usuario {id: $followedId})
                        DELETE r
                        """,
                        Map.of("followerId", followerId, "followedId", followedId)
                );
                return null;
            });
        }
    }

    public boolean isFollowing(String followerId, String followedId) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run(
                        """
                        MATCH (a:Usuario {id: $followerId})-[:SIGUE]->(b:Usuario {id: $followedId})
                        RETURN count(*) AS c
                        """,
                        Map.of("followerId", followerId, "followedId", followedId)
                );
                return result.single().get("c").asLong() > 0;
            });
        }
    }

    public List<User> findFollowers(String userId) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run(
                        "MATCH (follower:Usuario)-[:SIGUE]->(u:Usuario {id: $userId}) RETURN follower LIMIT 200",
                        Map.of("userId", userId)
                );
                return result.list(r -> mapNode(r.get("follower").asNode()));
            });
        }
    }

    public List<User> findFollowing(String userId) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run(
                        "MATCH (u:Usuario {id: $userId})-[:SIGUE]->(followed:Usuario) RETURN followed LIMIT 200",
                        Map.of("userId", userId)
                );
                return result.list(r -> mapNode(r.get("followed").asNode()));
            });
        }
    }

    public record ConnectionPage(List<User> users, long total) {}

    public ConnectionPage findConnectionsPage(String userId, boolean followers, int page, int size) {
        String pattern = followers
                ? "(person:Usuario)-[:SIGUE]->(u:Usuario {id: $userId})"
                : "(u:Usuario {id: $userId})-[:SIGUE]->(person:Usuario)";
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                long total = tx.run("MATCH " + pattern + " RETURN count(person) AS total",
                        Map.of("userId", userId)).single().get("total").asLong();
                var users = tx.run("MATCH " + pattern
                                + " RETURN person ORDER BY toLower(person.username), person.id SKIP $skip LIMIT $limit",
                        Map.of("userId", userId, "skip", page * size, "limit", size))
                        .list(r -> mapNode(r.get("person").asNode()));
                return new ConnectionPage(users, total);
            });
        }
    }

    /** Starter discovery stays separate from explainable two-hop recommendations. */
    public List<User> findDiscoverable(String userId) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                    MATCH (me:Usuario {id: $userId}), (candidate:Usuario)
                    WHERE candidate <> me AND NOT (me)-[:SIGUE]->(candidate)
                    RETURN candidate ORDER BY toLower(candidate.username), candidate.id LIMIT 20
                    """, Map.of("userId", userId))
                    .list(r -> mapNode(r.get("candidate").asNode())));
        }
    }

    public List<User> search(String query) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run(
                        "MATCH (u:Usuario) WHERE toLower(u.username) CONTAINS toLower($query) OR toLower(u.bio) CONTAINS toLower($query) RETURN u LIMIT 50",
                        Map.of("query", query)
                );
                return result.list(r -> mapNode(r.get("u").asNode()));
            });
        }
    }

    public List<User> findSuggestions(String userId) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                // Followees of followed users, ranked by shared connections.
                var result = tx.run(
                        """
                        MATCH (me:Usuario {id: $userId})-[:SIGUE]->(friend:Usuario)-[:SIGUE]->(rec:Usuario)
                        WHERE NOT (me)-[:SIGUE]->(rec) AND rec.id <> $userId
                        RETURN rec, count(*) AS mutualFriends
                        ORDER BY mutualFriends DESC, rec.username ASC
                        LIMIT 10
                        """,
                        Map.of("userId", userId)
                );

                return result.list(r -> mapNode(r.get("rec").asNode()));
            });
        }
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    /**
     * Convert a raw Neo4j Node into our domain User record.
     * node.get("field") returns a Value — .asString() extracts it safely.
     */
    private User mapNode(org.neo4j.driver.types.Node node) {
        return new User(
                node.get("id").asString(),
                node.get("username").asString(),
                node.get("email").asString(),
                node.get("passwordHash").asString(""),
                node.get("bio").asString(""),
                node.get("avatarUrl").asString(""),
                Instant.parse(node.get("createdAt").asString())
        );
    }
}
