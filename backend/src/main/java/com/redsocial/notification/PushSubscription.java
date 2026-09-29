package com.redsocial.notification;

import java.time.Instant;

/**
 * Domain model for a browser Web Push subscription (W3C Push API).
 *
 * Stored in Neo4j as:
 *   (:PushSubscription {id, endpoint, p256dh, auth, userAgent, createdAt})
 *
 * Relationship:
 *   (:Usuario)-[:HAS_SUBSCRIPTION]->(:PushSubscription)
 *
 * 'endpoint' is the browser-generated push service URL and is UNIQUE across
 * the whole graph: one browser push endpoint is one subscription, no matter
 * which user registered it. A user can have many (one per browser/device);
 * the same browser can log in as a different user later, in which case the
 * subscription is re-pointed to the new user.
 *
 * 'p256dh' and 'auth' are the two keys the browser returns from
 * PushSubscription.toJSON() together with the endpoint; VAPID signing keys
 * are NOT stored here — they live in the application configuration and
 * authenticate the server, not the subscriber.
 *
 * This is the data layer only. The REST endpoint, the service worker and the
 * VAPID sender belong to Phase 7 and are not part of this node.
 */
public record PushSubscription(
        String id,
        String endpoint,
        String p256dh,
        String auth,
        String userAgent,
        Instant createdAt
) {}
