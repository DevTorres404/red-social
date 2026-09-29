package com.redsocial.messaging;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Generación determinista del id de una conversación directa (1-a-1).
 *
 * En una conversación directa (:Usuario)-[:PARTICIPA]->(:Conversacion)<-[:PARTICIPA]-(:Usuario)
 * no hay un "id de conversación" que venga de afuera: hay que derivarlo.
 *
 * Se deriva con un UUID de nombre (type 3, MD5) sobre los dos ids de usuario
 * ordenados alfabéticamente y unidos por ':'. Es determinista: los mismos dos
 * usuarios SIEMPRE producen el mismo id, sin importar quién envíe el primer
 * mensaje. Eso elimina la carrera de "buscar la conversación y crearla si no
 * existe" (dos requests simultáneos del mismo par podrían crear dos nodos) y
 * le da un id estable al futuro endpoint WebSocket /ws/chat/{conversationId}.
 */
public final class ConversationId {

    private ConversationId() {
    }

    /**
     * Devuelve el id de conversación para el par de usuarios dado.
     * El orden de los argumentos es irrelevante.
     */
    public static String of(String userAId, String userBId) {
        // Ordenar importa: si dependiera del orden de los argumentos, "A -> B" y
        // "B -> A" producirían ids distintos y los dos usuarios no verían el mismo
        // hilo. El orden alfabético es conmutativo, así que ambos lados coinciden.
        String first = userAId.compareTo(userBId) <= 0 ? userAId : userBId;
        String second = first.equals(userAId) ? userBId : userAId;

        return UUID.nameUUIDFromBytes(
                (first + ":" + second).getBytes(StandardCharsets.UTF_8)
        ).toString();
    }
}
