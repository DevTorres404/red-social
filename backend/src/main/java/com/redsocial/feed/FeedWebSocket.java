package com.redsocial.feed;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Set;

/**
 * Broadcast-only real-time channel for feed updates.
 *
 * Auth mirrors the chat channel: Origin exact-match, a single-use
 * "ticket." token carried in Sec-WebSocket-Protocol, and a per-frame
 * authExpiresAt re-check. There is no inbound mutation — clients only
 * receive like-changed / comment-created events, and may optionally
 * narrow interest with a subscribe frame.
 */
@WebSocket(path = "/ws/feed")
public class FeedWebSocket {
    private static final CloseReason POLICY_VIOLATION = new CloseReason(1008, "Not authorized for feed");
    /** Upper bound for a client-supplied subscribe filter. */
    private static final int MAX_POST_IDS = 500;

    public record Incoming(String type, String scope, List<String> postIds) {}

    @Inject WebSocketConnection connection;
    @Inject FeedTickets tickets;
    @Inject FeedDelivery delivery;
    @Inject ObjectMapper json;
    @ConfigProperty(name = "app.websocket.allowed-origin") String allowedOrigin;

    @OnOpen
    public void onOpen() {
        if (!allowedOrigin.equals(connection.handshakeRequest().header("Origin"))) {
            connection.closeAndAwait(POLICY_VIOLATION);
            return;
        }
        String offered = connection.handshakeRequest().header("Sec-WebSocket-Protocol");
        String value = null;
        if (offered != null) {
            for (String protocol : offered.split(",")) {
                String candidate = protocol.trim();
                if (candidate.startsWith("ticket.")) value = candidate.substring("ticket.".length());
            }
        }
        FeedTickets.FeedTicket ticket = value == null ? null : tickets.consume(value);
        if (ticket == null) {
            connection.closeAndAwait(POLICY_VIOLATION);
            return;
        }
        tickets.connected(connection.id(), ticket);
        delivery.send(connection, new FeedDelivery.StatusEvent("ready", null));
    }

    @OnTextMessage
    public void onMessage(String raw) {
        FeedTickets.Viewer viewer = tickets.viewer(connection.id());
        if (viewer == null || !viewer.authValid()) {
            connection.closeAndAwait(POLICY_VIOLATION);
            return;
        }
        Incoming input;
        try {
            input = json.readValue(raw, Incoming.class);
        } catch (Exception ex) {
            delivery.send(connection, new FeedDelivery.StatusEvent("error", "Invalid subscribe message"));
            return;
        }
        if (input == null || !"subscribe".equals(input.type())) return;
        if ("home".equals(input.scope()) || input.postIds() == null || input.postIds().isEmpty()) {
            viewer.subscribeTo(null); // all post events for the user
            return;
        }
        // A malformed frame must not NPE out of Set.copyOf and kill the callback.
        List<String> ids = input.postIds().stream()
                .filter(id -> id != null && !id.isBlank())
                .limit(MAX_POST_IDS)
                .toList();
        if (ids.isEmpty()) {
            delivery.send(connection, new FeedDelivery.StatusEvent("error", "subscribe requires postIds"));
            return;
        }
        viewer.subscribeTo(Set.copyOf(ids));
    }

    @OnClose
    public void onClose() {
        tickets.disconnected(connection.id());
    }
}
