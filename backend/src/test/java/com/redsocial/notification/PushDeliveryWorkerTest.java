package com.redsocial.notification;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PushDeliveryWorkerTest {
    private static final PushSubscription SUBSCRIPTION = new PushSubscription(
            "subscription", "https://fcm.googleapis.com/fcm/send/test", "key", "auth", "test", Instant.now());

    @Test
    void providerFailureIsRetriedWithoutThrowingFromWorker() {
        FakeDeliveries deliveries = new FakeDeliveries(Optional.of(SUBSCRIPTION));
        PushDeliveryWorker worker = new PushDeliveryWorker();
        worker.deliveries = deliveries;
        worker.sender = new WebPushSender() {
            @Override public boolean configured() { return true; }
            @Override public int send(PushSubscription subscription, String postId) throws Exception {
                throw new IOException("simulated outage");
            }
        };

        assertDoesNotThrow(worker::dispatch);
        assertEquals(1, deliveries.retriedAttempts);
        assertFalse(deliveries.completed);
    }

    @Test
    void unfollowedRecipientIsDiscardedBeforeAnySend() {
        FakeDeliveries deliveries = new FakeDeliveries(Optional.empty());
        PushDeliveryWorker worker = new PushDeliveryWorker();
        worker.deliveries = deliveries;
        worker.sender = new WebPushSender() {
            @Override public boolean configured() { return true; }
            @Override public int send(PushSubscription subscription, String postId) {
                fail("No push should be sent to an unauthorized recipient");
                return 0;
            }
        };

        worker.dispatch();
        assertTrue(deliveries.completed);
        assertEquals(0, deliveries.retriedAttempts);
    }

    private static final class FakeDeliveries extends PushDeliveryRepository {
        final Optional<PushSubscription> subscription;
        boolean completed;
        int retriedAttempts;

        FakeDeliveries(Optional<PushSubscription> subscription) { this.subscription = subscription; }
        @Override public List<Delivery> due() { return List.of(new Delivery("job", "post", "post", "{\"type\":\"POST_CREATED\",\"refId\":\"post\",\"url\":\"/posts/post\"}", 0)); }
        @Override public boolean claim(String id) { return true; }
        @Override public Optional<PushSubscription> authorizedSubscription(String id) { return subscription; }
        @Override public void complete(String id) { completed = true; }
        @Override public void retry(String id, int attempts) { retriedAttempts = attempts; }
    }
}
