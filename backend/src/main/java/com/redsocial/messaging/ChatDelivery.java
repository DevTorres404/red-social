package com.redsocial.messaging;

import io.quarkus.websockets.next.OpenConnections;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ChatDelivery {
    private static final Logger LOG = Logger.getLogger(ChatDelivery.class);

    public record Event(String type, String clientMessageId, Message message, String error) {}

    @Inject OpenConnections connections;
    @Inject ChatTickets tickets;

    public void publish(Message message, String clientMessageId) {
        Event event = new Event("message", clientMessageId, message, null);
        for (WebSocketConnection connection : connections.listAll()) {
            ChatTickets.Peer peer = tickets.peer(connection.id());
            if (peer != null && peer.conversationId.equals(message.conversacionId()) && connection.isOpen()) {
                if (!peer.authValid()) {
                    try {
                        connection.closeAndAwait(new io.quarkus.websockets.next.CloseReason(1008, "Authentication expired"));
                    } catch (RuntimeException ex) {
                        LOG.warnf("Could not close expired chat connection %s: %s", connection.id(), ex.getMessage());
                    }
                    continue;
                }
                send(connection, event);
            }
        }
    }

    public void send(WebSocketConnection connection, Event event) {
        try {
            connection.sendTextAndAwait(event);
        } catch (RuntimeException ex) {
            LOG.warnf("Could not deliver chat event on connection %s: %s", connection.id(), ex.getMessage());
        }
    }
}
