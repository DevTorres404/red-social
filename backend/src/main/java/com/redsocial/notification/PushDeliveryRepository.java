package com.redsocial.notification;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.neo4j.driver.Driver;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class PushDeliveryRepository {
    @Inject Driver driver;

    public record Delivery(String id, String postId, String refId, String payload, int attempts) {}

    public List<Delivery> due() {
        String now = Instant.now().toString();
        try (var session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                    MATCH (d:PushDelivery)
                    WHERE (d.status = 'PENDING' AND d.nextAt <= $now)
                       OR (d.status = 'SENDING' AND d.leaseUntil <= $now)
                    RETURN d.id AS id, d.postId AS postId, d.refId AS refId,
                           d.payload AS payload, d.attempts AS attempts
                    ORDER BY d.nextAt, d.id LIMIT 50
                    """, Map.of("now", now)).list(r -> new Delivery(
                    r.get("id").asString(),
                    r.get("postId").asString(""),
                    r.get("refId").asString(""),
                    r.get("payload").asString(null),
                    r.get("attempts").asInt())));
        }
    }

    public boolean claim(String id) {
        String now = Instant.now().toString();
        String leaseUntil = Instant.now().plusSeconds(120).toString();
        try (var session = driver.session()) {
            return session.executeWrite(tx -> tx.run("""
                    MATCH (d:PushDelivery {id: $id})
                    WHERE (d.status = 'PENDING' AND d.nextAt <= $now)
                       OR (d.status = 'SENDING' AND d.leaseUntil <= $now)
                    SET d.status = 'SENDING', d.leaseUntil = $leaseUntil,
                        d.attempts = d.attempts + 1
                    RETURN count(d) AS claimed
                    """, Map.of("id", id, "now", now, "leaseUntil", leaseUntil))
                    .single().get("claimed").asLong() == 1);
        }
    }

    /** Rechecks current graph ownership/follow state immediately before sending. */
    public Optional<PushSubscription> authorizedSubscription(String id) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (d:PushDelivery {id: $id})
                        MATCH (recipient:Usuario {id: d.recipientId})-[:HAS_SUBSCRIPTION]->(s:PushSubscription {id: d.subscriptionId})
                        RETURN s.id AS id, s.endpoint AS endpoint, s.p256dh AS p256dh,
                               s.auth AS auth, s.userAgent AS userAgent, s.createdAt AS createdAt
                        """, Map.of("id", id));
                if (!result.hasNext()) return Optional.empty();
                var r = result.single();
                return Optional.of(new PushSubscription(r.get("id").asString(), r.get("endpoint").asString(),
                        r.get("p256dh").asString(), r.get("auth").asString(),
                        r.get("userAgent").asString(""), Instant.parse(r.get("createdAt").asString())));
            });
        }
    }

    public void complete(String id) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> { tx.run("MATCH (d:PushDelivery {id: $id}) DETACH DELETE d", Map.of("id", id)); return null; });
        }
    }

    public void retry(String id, int attempts) {
        long delay = Math.min(300, 5L * (1L << Math.min(attempts, 5)));
        String next = Instant.now().plusSeconds(delay).toString();
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                        MATCH (d:PushDelivery {id: $id})
                        SET d.status = $status, d.nextAt = $next
                        """, Map.of("id", id, "status", attempts >= 5 ? "FAILED" : "PENDING", "next", next));
                return null;
            });
        }
    }

    public void removeSubscription(String subscriptionId) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (d:PushDelivery {subscriptionId: $id}) DETACH DELETE d", Map.of("id", subscriptionId));
                tx.run("MATCH (s:PushSubscription {id: $id}) DETACH DELETE s", Map.of("id", subscriptionId));
                return null;
            });
        }
    }
}
