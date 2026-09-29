package com.redsocial.notification;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.neo4j.driver.Driver;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Enqueues Web Push delivery jobs for every relevant event in the app.
 *
 * Events supported:
 *   POST_CREATED  — notifies all followers of the author
 *   POST_LIKED    — notifies the post author
 *   USER_FOLLOWED — notifies the followed user
 *   MESSAGE_SENT  — notifies the message recipient
 *
 * Push jobs are fire-and-forget from the caller's perspective: they are
 * persisted in Neo4j as (:PushDelivery) nodes and dispatched asynchronously
 * by PushDeliveryWorker every 10 seconds.
 *
 * Every job carries a payload field so the browser's Service Worker can
 * build a meaningful notification card without an extra round-trip.
 */
@ApplicationScoped
public class PushNotificationService {

    private static final Logger LOG = Logger.getLogger(PushNotificationService.class);

    @Inject
    Driver driver;

    @Inject
    WebPushSender sender;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * A user published a new post → notify all their followers.
     */
    public void onPostCreated(String authorId, String postId) {
        if (!sender.configured()) return;
        List<String> subscriptionIds = subscribersFollowing(authorId, authorId);
        for (var entry : subscriptionIds) {
            enqueue(entry, authorId, postId, "POST_CREATED", "/posts/" + postId);
        }
        if (!subscriptionIds.isEmpty()) {
            LOG.debugf("Queued %d push deliveries for new post %s", subscriptionIds.size(), postId);
        }
    }

    /**
     * A user liked a post → notify the post author (not the liker).
     */
    public void onPostLiked(String likerId, String postId, String authorId) {
        if (!sender.configured() || likerId.equals(authorId)) return;
        List<String> subscriptionIds = subscriptionsOf(authorId);
        for (var subId : subscriptionIds) {
            enqueue(subId, authorId, postId, "POST_LIKED", "/posts/" + postId);
        }
    }

    /**
     * A user followed another → notify the followed user.
     */
    public void onUserFollowed(String followerId, String followedId) {
        if (!sender.configured() || followerId.equals(followedId)) return;
        List<String> subscriptionIds = subscriptionsOf(followedId);
        for (var subId : subscriptionIds) {
            enqueue(subId, followedId, followerId, "USER_FOLLOWED", "/users/" + followerId);
        }
    }

    /**
     * A direct message was sent → notify the recipient.
     */
    public void onMessageSent(String senderId, String recipientId, String messageId, String conversationId) {
        if (!sender.configured() || senderId.equals(recipientId)) return;
        List<String> subscriptionIds = subscriptionsOf(recipientId);
        for (var subId : subscriptionIds) {
            enqueue(subId, recipientId, messageId, "MESSAGE_SENT", "/messages/" + senderId);
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Returns subscription IDs of all followers of `targetId`, excluding `excludeId`.
     * Used for post-created events where we don't notify the author themselves.
     * DISTINCT ensures one row per subscription even if multiple paths exist.
     */
    private List<String> subscribersFollowing(String targetId, String excludeId) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                    MATCH (follower:Usuario)-[:SIGUE]->(target:Usuario {id: $targetId})
                    WHERE follower.id <> $excludeId
                    MATCH (follower)-[:HAS_SUBSCRIPTION]->(s:PushSubscription)
                    RETURN DISTINCT s.id AS subscriptionId, follower.id AS recipientId
                    """, Map.of("targetId", targetId, "excludeId", excludeId))
                    .list(r -> r.get("subscriptionId").asString() + "|" + r.get("recipientId").asString()));
        }
    }

    /**
     * Returns all subscription IDs for a specific user.
     */
    private List<String> subscriptionsOf(String userId) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                    MATCH (:Usuario {id: $userId})-[:HAS_SUBSCRIPTION]->(s:PushSubscription)
                    RETURN s.id AS subscriptionId
                    """, Map.of("userId", userId))
                    .list(r -> r.get("subscriptionId").asString() + "|" + userId));
        }
    }

    /**
     * Persists a PushDelivery node in Neo4j for the worker to pick up.
     * The `refId` is the "subject" of the notification (postId, followerId, messageId).
     * The `url` is the frontend route the notification click should open.
     */
    private void enqueue(String subscriptionAndRecipient, String recipientId, String refId, String type, String url) {
        // Format is "subscriptionId|recipientId" when coming from subscribersFollowing
        String subscriptionId;
        String actualRecipientId;
        if (subscriptionAndRecipient.contains("|")) {
            String[] parts = subscriptionAndRecipient.split("\\|", 2);
            subscriptionId = parts[0];
            actualRecipientId = parts[1];
        } else {
            subscriptionId = subscriptionAndRecipient;
            actualRecipientId = recipientId;
        }

        String deliveryId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        String payload = buildPayload(type, refId, url);

        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                        MERGE (s:PushSubscription {id: $subscriptionId})
                        CREATE (d:PushDelivery {
                            id:             $deliveryId,
                            postId:         $refId,
                            refId:          $refId,
                            type:           $type,
                            url:            $url,
                            payload:        $payload,
                            recipientId:    $recipientId,
                            subscriptionId: $subscriptionId,
                            status:         'PENDING',
                            attempts:       0,
                            nextAt:         $now
                        })
                        CREATE (d)-[:FOR_SUBSCRIPTION]->(s)
                        """,
                        Map.of(
                                "deliveryId", deliveryId,
                                "subscriptionId", subscriptionId,
                                "recipientId", actualRecipientId,
                                "refId", refId,
                                "type", type,
                                "url", url,
                                "payload", payload,
                                "now", now
                        ));
                return null;
            });
        } catch (Exception ex) {
            LOG.warnf("Failed to enqueue push delivery for subscription %s: %s", subscriptionId, ex.getMessage());
        }
    }

    private String buildPayload(String type, String refId, String url) {
        return String.format(
                "{\"type\":\"%s\",\"refId\":\"%s\",\"url\":\"%s\"}",
                type, refId, url);
    }
}
