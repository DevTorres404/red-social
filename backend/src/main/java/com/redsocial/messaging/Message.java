package com.redsocial.messaging;

import java.time.Instant;

/**
 * Domain model for a direct message.
 *
 * Stored in Neo4j as: (:Mensaje {id, conversacionId, text, sentAt, read})
 *
 * Relationships:
 *   (:Usuario)-[:ENVIO]->(:Mensaje)                  — sender
 *   (:Mensaje)-[:EN_CONVERSACION]->(:Conversacion)  — thread it belongs to
 *   (:Usuario)-[:PARTICIPA]->(:Conversacion)         — the two participants
 *
 * Why :Mensaje stays a NODE and does not collapse into
 * (:Usuario)-[:ENVIA]->(:Conversacion): 'read' is per-recipient state, and a
 * relationship can only carry its own properties — it cannot express "the
 * recipient of THIS message has read it" while the sender's copy is unread.
 * A node property is the only place that state can live in a 1-a-1 thread.
 *
 * There is deliberately NO [:RECIBIO] relationship. The recipient is derived:
 * it is the other [:PARTICIPA] of the conversation that is not the sender.
 * Storing it would be redundant data that can drift out of sync.
 *
 * Note: senderId/recipientId are not persisted on the node. The graph is the
 * ownership data; they are hydrated in the repository when the row is mapped.
 * conversacionId IS persisted because it is the lookup key for every message
 * query (and for the future /ws/chat/{conversationId} endpoint).
 */
public record Message(
        String id,
        String conversacionId,
        String senderId,
        String senderUsername,
        String recipientId,
        String recipientUsername,
        String text,
        Instant sentAt,
        boolean read
) {}
