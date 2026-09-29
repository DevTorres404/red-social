package com.redsocial.messaging;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.neo4j.driver.Driver;
import org.neo4j.driver.types.Node;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * All Neo4j queries for (:Conversacion) and (:Mensaje) nodes.
 *
 * The conversation between two users is a first-class node:
 *   (a:Usuario)-[:PARTICIPA]->(c:Conversacion)<-[:PARTICIPA]-(b:Usuario)
 *   (sender:Usuario)-[:ENVIO]->(m:Mensaje)-[:EN_CONVERSACION]->(c)
 *
 * A conversation is created lazily on the first message, with MERGE, so there
 * is never a half-built thread and two concurrent first-sends converge on the
 * same node instead of racing to create two.
 *
 * The conversation id is DERIVED from the two user ids (see {@link ConversationId}):
 * no lookup is needed to find the thread, and the id is stable enough to be
 * used as the path parameter of the future /ws/chat/{conversationId} endpoint.
 *
 * There is no [:RECIBIO]. The recipient is the other participant of the
 * conversation, resolved at read time.
 */
@ApplicationScoped
public class MessageRepository {

    @Inject
    Driver driver;

    /** Send a message from senderId to recipientId, creating the thread if needed. */
    public Message send(String senderId, String recipientId, String text) {
        return send(senderId, recipientId, text, null).message();
    }

    public record SendResult(Message message, boolean created) {}

    /** A client-generated UUID makes WebSocket retries idempotent. */
    public SendResult send(String senderId, String recipientId, String text, String clientMessageId) {
        if (senderId.equals(recipientId)) {
            throw new BadRequestException("Cannot send a message to yourself");
        }
        String conversationId = conversationId(senderId, recipientId);
        String messageId = clientMessageId == null ? UUID.randomUUID().toString()
                : UUID.nameUUIDFromBytes((senderId + ":" + conversationId + ":" + clientMessageId)
                        .getBytes(StandardCharsets.UTF_8)).toString();
        Instant now = Instant.now();

        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                var result = tx.run("""
                        MATCH (sender:Usuario {id: $senderId}),
                              (recipient:Usuario {id: $recipientId})
                        MERGE (c:Conversacion {id: $conversationId})
                          ON CREATE SET c.createdAt = $now
                        MERGE (sender)-[:PARTICIPA]->(c)
                        MERGE (recipient)-[:PARTICIPA]->(c)
                        MERGE (m:Mensaje {id: $messageId})
                          ON CREATE SET m.conversacionId = $conversationId,
                                        m.text = $text,
                                        m.sentAt = $now,
                                        m.read = false
                        MERGE (sender)-[:ENVIO]->(m)
                        MERGE (m)-[:EN_CONVERSACION]->(c)
                        SET c.updatedAt = CASE WHEN m.sentAt = $now THEN $now ELSE c.updatedAt END
                        RETURN m,
                               m.sentAt = $now AS created,
                               sender.id            AS senderId,
                               sender.username      AS senderUsername,
                               recipient.id         AS recipientId,
                               recipient.username   AS recipientUsername
                        """,
                        Map.of(
                                "senderId", senderId,
                                "recipientId", recipientId,
                                "conversationId", conversationId,
                                "messageId", messageId,
                                "text", text,
                                "now", now.toString()
                        )
                );
                if (!result.hasNext()) throw new NotFoundException("Recipient not found: " + recipientId);
                var row = result.single();
                return new SendResult(mapRow(row.get("m").asNode(),
                        row.get("senderId").asString(),
                        row.get("senderUsername").asString(),
                        row.get("recipientId").asString(),
                        row.get("recipientUsername").asString()), row.get("created").asBoolean());
            });
        }
    }

    /**
     * Fetch the full conversation thread between two users, oldest first.
     * The thread is resolved by the derived conversation id, so no lookup is
     * needed; if the users have never talked the result is simply empty.
     */
    public List<Message> getConversation(String userAId, String userBId, int skip, int limit) {
        String conversationId = conversationId(userAId, userBId);

        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (m:Mensaje {conversacionId: $conversationId})-[:EN_CONVERSACION]->(c:Conversacion)
                        MATCH (s:Usuario)-[:ENVIO]->(m)
                        MATCH (c)<-[:PARTICIPA]-(u:Usuario)
                        WHERE s.id <> u.id
                          AND u.id IN [$userAId, $userBId]
                        RETURN m,
                               s.id AS senderId, s.username AS senderUsername,
                               u.id AS recipientId, u.username AS recipientUsername
                        ORDER BY m.sentAt DESC, m.id DESC
                        SKIP $skip
                        LIMIT $limit
                        """,
                        Map.of(
                                "conversationId", conversationId,
                                "userAId", userAId,
                                "userBId", userBId,
                                "skip", skip,
                                "limit", limit
                        )
                );
                var messages = result.list(row -> mapRow(
                        row.get("m").asNode(),
                        row.get("senderId").asString(),
                        row.get("senderUsername").asString(),
                        row.get("recipientId").asString(),
                        row.get("recipientUsername").asString()
                ));
                java.util.Collections.reverse(messages);
                return messages;
            });
        }
    }

    /** List of unique users that share a conversation with the given userId. */
    public List<String> getConversationPartners(String userId) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (me:Usuario {id: $userId})-[:PARTICIPA]->(:Conversacion)<-[:PARTICIPA]-(other:Usuario)
                        WHERE other.id <> $userId
                        RETURN DISTINCT other.id AS partnerId
                        LIMIT 200
                        """,
                        Map.of("userId", userId)
                );
                return result.list(row -> row.get("partnerId").asString());
            });
        }
    }

    /**
     * Mark as read every unread message the given sender sent to userId.
     * Scoped to the derived conversation so we never touch another thread.
     */
    public void markAsRead(String userId, String senderId) {
        String conversationId = conversationId(userId, senderId);

        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                        MATCH (c:Conversacion {id: $conversationId})<-[:PARTICIPA]-(me:Usuario {id: $userId})
                        MATCH (sender:Usuario {id: $senderId})-[:ENVIO]->(m:Mensaje {conversacionId: $conversationId, read: false})
                        MATCH (m)-[:EN_CONVERSACION]->(c)
                        SET m.read = true
                        """,
                        Map.of("userId", userId, "senderId", senderId, "conversationId", conversationId)
                );
                return null;
            });
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Id determinista de la conversación entre dos usuarios.
     *
     * El orden de los argumentos NO importa: ConversationId ordena los ids
     * alfabéticamente antes de derivar el UUID, así que el remitente y el
     * receptor calculan exactamente el mismo id sin necesidad de consultarlo.
     * Delega en ConversationId porque el seeder también necesita sembrar
     * conversaciones con el mismo algoritmo y no puede duplicar la fórmula.
     */
    private String conversationId(String userAId, String userBId) {
        return ConversationId.of(userAId, userBId);
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private Message mapRow(Node node, String senderId, String senderUsername,
                           String recipientId, String recipientUsername) {
        return new Message(
                node.get("id").asString(),
                node.get("conversacionId").asString(null),
                senderId,
                senderUsername,
                recipientId,
                recipientUsername,
                node.get("text").asString(),
                Instant.parse(node.get("sentAt").asString()),
                node.get("read").asBoolean(false)
        );
    }
}
