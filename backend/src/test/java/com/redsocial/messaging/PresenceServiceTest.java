package com.redsocial.messaging;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class PresenceServiceTest {

    @Test
    void neverConnectedUserIsOfflineAndTwoTabsAreIndependent() {
        var clock = new AtomicLong(1_000);
        var presence = new PresenceService(clock::get);
        assertFalse(presence.isOnline("alice"));

        presence.registerConnection("alice", "app:tab-one");
        presence.registerConnection("alice", "app:tab-two");
        assertTrue(presence.isOnline("alice"));
        assertEquals(2, presence.getConnectionCount("alice"));
        assertEquals(List.of("alice"), List.copyOf(presence.getOnlineUserIds()));

        presence.unregisterConnection("alice", "app:tab-one");
        assertTrue(presence.isOnline("alice"));
        presence.unregisterConnection("alice", "app:tab-two");
        presence.heartbeat("alice", "app:tab-two");
        assertFalse(presence.isOnline("alice"));
    }

    @Test
    void expiresIdleConnectionsButHeartbeatKeepsOneOnline() {
        var clock = new AtomicLong(1_000);
        var presence = new PresenceService(clock::get);
        presence.registerConnection("alice", "app:active");
        presence.registerConnection("bob", "app:idle");

        clock.addAndGet(30_000);
        presence.heartbeat("alice", "app:active");
        clock.addAndGet(20_000);
        presence.cleanupStaleConnections();

        assertTrue(presence.isOnline("alice"));
        assertFalse(presence.isOnline("bob"));
        assertEquals(0, presence.getConnectionCount("bob"));
    }

    @Test
    void limitsSessionsPerUser() {
        var presence = new PresenceService(() -> 1_000L);
        for (int index = 0; index < 32; index++) {
            presence.registerConnection("alice", "app:" + index);
        }
        assertThrows(WebApplicationException.class,
                () -> presence.registerConnection("alice", "app:overflow"));
    }

    @Test
    void rejectsInvalidSessionIdentifiers() {
        assertEquals("06be694e-eb7b-4e66-af53-097b31c657e0",
                PresenceResource.validSessionId("06be694e-eb7b-4e66-af53-097b31c657e0"));
        assertThrows(BadRequestException.class, () -> PresenceResource.validSessionId(null));
        assertThrows(BadRequestException.class, () -> PresenceResource.validSessionId("not-a-uuid"));
    }
}
