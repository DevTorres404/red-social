package com.redsocial.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redsocial.notification.InAppNotificationService;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.UUID;

@WebSocket(path = "/ws/chat/{conversationId}")
public class ChatWebSocket {
    private static final CloseReason POLICY_VIOLATION = new CloseReason(1008, "Not authorized for conversation");

    public record Incoming(String clientMessageId, String text) {}

    @Inject WebSocketConnection connection;
    @Inject ChatTickets tickets;
    @Inject ChatDelivery delivery;
    @Inject MessageRepository messages;
    @Inject InAppNotificationService inAppNotifications;
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
        ChatTickets.Ticket ticket = value == null ? null
                : tickets.consume(value, connection.pathParam("conversationId"));
        if (ticket == null) {
            connection.closeAndAwait(POLICY_VIOLATION);
            return;
        }
        tickets.connected(connection.id(), ticket);
        delivery.send(connection, new ChatDelivery.Event("ready", null, null, null));
    }

    @OnTextMessage
    public void onMessage(String raw) {
        ChatTickets.Peer peer = tickets.peer(connection.id());
        if (peer == null || !peer.authValid()) {
            connection.closeAndAwait(POLICY_VIOLATION);
            return;
        }
        if (!peer.allowSend()) {
            delivery.send(connection, new ChatDelivery.Event("error", null, null, "Too many messages"));
            return;
        }
        Incoming input;
        try {
            input = json.readValue(raw, Incoming.class);
            if (input.clientMessageId() == null) throw new IllegalArgumentException();
            UUID.fromString(input.clientMessageId());
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            delivery.send(connection, new ChatDelivery.Event("error", null, null, "Invalid message ID or JSON"));
            return;
        }
        if (input.text() == null || input.text().isBlank() || input.text().length() > 1000) {
            delivery.send(connection, new ChatDelivery.Event("error", input.clientMessageId(), null,
                    "Message must contain 1 to 1000 characters"));
            return;
        }
        try {
            MessageRepository.SendResult result = messages.send(peer.userId, peer.otherUserId,
                    input.text().trim(), input.clientMessageId());
            if (result.created()) {
                inAppNotifications.onMessageSent(peer.userId, peer.otherUserId, result.message().id());
                delivery.publish(result.message(), input.clientMessageId());
            }
            else delivery.send(connection, new ChatDelivery.Event("message", input.clientMessageId(), result.message(), null));
        } catch (RuntimeException ex) {
            delivery.send(connection, new ChatDelivery.Event("error", input.clientMessageId(), null,
                    "Message could not be saved"));
        }
    }

    @OnClose
    public void onClose() {
        tickets.disconnected(connection.id());
    }
}
