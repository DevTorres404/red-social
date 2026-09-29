package com.redsocial.config;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.neo4j.driver.Driver;

/**
 * Runs Neo4j schema setup on application startup.
 *
 * In a relational DB you'd use Flyway/Liquibase SQL migrations.
 * In Neo4j, "schema" means uniqueness constraints and indexes.
 *
 * IF NOT EXISTS makes these idempotent — safe to re-run on every boot.
 *
 * This class is the SINGLE SOURCE OF TRUTH for the graph schema: every
 * constraint and index the application relies on must be declared here. A
 * constraint that only exists in someone's local database (hand-made with
 * cypher-shell) is a bug — the app boots against a clean Neo4j volume in CI
 * and in Docker, and a missing constraint there means duplicated nodes and
 * silent corruption later.
 *
 * Only node types the code actually creates get a constraint here.
 */
@ApplicationScoped
public class SchemaInitializer {

    private static final Logger LOG = Logger.getLogger(SchemaInitializer.class);

    @Inject
    Driver driver;

    // Runs before DatabaseSeeder (@Priority(2)) so the seeder always writes
    // against a schema that is already in place.
    @Priority(1)
    void onStart(@Observes StartupEvent event) {
        LOG.info("Initializing Neo4j schema constraints...");
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                // ── Uniqueness constraints ────────────────────────────────────
                // These also create implicit indexes on the constrained property.

                tx.run("CREATE CONSTRAINT user_id IF NOT EXISTS " +
                       "FOR (u:Usuario) REQUIRE u.id IS UNIQUE");

                tx.run("CREATE CONSTRAINT user_email IF NOT EXISTS " +
                       "FOR (u:Usuario) REQUIRE u.email IS UNIQUE");

                tx.run("CREATE CONSTRAINT user_username IF NOT EXISTS " +
                       "FOR (u:Usuario) REQUIRE u.username IS UNIQUE");

                tx.run("CREATE CONSTRAINT post_id IF NOT EXISTS " +
                       "FOR (p:Post) REQUIRE p.id IS UNIQUE");

                tx.run("CREATE CONSTRAINT media_cleanup_key IF NOT EXISTS " +
                       "FOR (m:MediaCleanup) REQUIRE m.key IS UNIQUE");

                tx.run("CREATE CONSTRAINT comentario_id IF NOT EXISTS " +
                       "FOR (c:Comentario) REQUIRE c.id IS UNIQUE");

                tx.run("CREATE CONSTRAINT comment_reaction_id IF NOT EXISTS " +
                       "FOR (r:CommentReaction) REQUIRE r.id IS UNIQUE");

                tx.run("CREATE CONSTRAINT mensaje_id IF NOT EXISTS " +
                       "FOR (m:Mensaje) REQUIRE m.id IS UNIQUE");

                // La conversación es un nodo de primer nivel: su id es el
                // parámetro de /ws/chat/{conversationId}, tiene que ser único.
                tx.run("CREATE CONSTRAINT conversation_id IF NOT EXISTS " +
                       "FOR (c:Conversacion) REQUIRE c.id IS UNIQUE");

                tx.run("CREATE CONSTRAINT notificacion_id IF NOT EXISTS " +
                       "FOR (n:Notificacion) REQUIRE n.id IS UNIQUE");

                tx.run("CREATE CONSTRAINT suscripcion_id IF NOT EXISTS " +
                       "FOR (s:PushSubscription) REQUIRE s.id IS UNIQUE");

                // Un endpoint de push del navegador identifica UNA suscripción.
                // Es la clave de MERGE: sin esto, cada refresh del navegador
                // dejaría un nodo muerto y el usuario recibiría duplicados.
                tx.run("CREATE CONSTRAINT suscripcion_endpoint IF NOT EXISTS " +
                       "FOR (s:PushSubscription) REQUIRE s.endpoint IS UNIQUE");

                tx.run("CREATE CONSTRAINT push_delivery_id IF NOT EXISTS " +
                       "FOR (d:PushDelivery) REQUIRE d.id IS UNIQUE");

                tx.run("CREATE CONSTRAINT push_delivery_post_subscription IF NOT EXISTS " +
                       "FOR (d:PushDelivery) REQUIRE (d.postId, d.subscriptionId) IS UNIQUE");

                tx.run("CREATE CONSTRAINT refresh_token_id IF NOT EXISTS " +
                       "FOR (rt:RefreshToken) REQUIRE rt.id IS UNIQUE");

                tx.run("CREATE CONSTRAINT refresh_token_lookup_key IF NOT EXISTS " +
                       "FOR (rt:RefreshToken) REQUIRE rt.lookupKey IS UNIQUE");

                tx.run("CREATE CONSTRAINT refresh_token_hash IF NOT EXISTS " +
                       "FOR (rt:RefreshToken) REQUIRE rt.tokenHash IS UNIQUE");

                // ── Additional indexes (not unique, but fast lookup) ──────────
                tx.run("CREATE INDEX usuario_created IF NOT EXISTS " +
                       "FOR (u:Usuario) ON (u.createdAt)");

                tx.run("CREATE INDEX post_created IF NOT EXISTS " +
                       "FOR (p:Post) ON (p.createdAt)");

                tx.run("CREATE INDEX mensaje_sent IF NOT EXISTS " +
                       "FOR (m:Mensaje) ON (m.sentAt)");

                // getConversation y markAsRead buscan por (:Mensaje).conversacionId.
                // Sin este índice, ambos hacen un escaneo completo de la etiqueta.
                tx.run("CREATE INDEX mensaje_conversacion IF NOT EXISTS " +
                       "FOR (m:Mensaje) ON (m.conversacionId)");

                // Ordena el listado de conversaciones (la más reciente arriba).
                tx.run("CREATE INDEX conversacion_updated IF NOT EXISTS " +
                       "FOR (c:Conversacion) ON (c.updatedAt)");

                tx.run("CREATE INDEX notificacion_created IF NOT EXISTS " +
                       "FOR (n:Notificacion) ON (n.createdAt)");

                tx.run("CREATE INDEX suscripcion_created IF NOT EXISTS " +
                       "FOR (s:PushSubscription) ON (s.createdAt)");

                tx.run("CREATE INDEX push_delivery_due IF NOT EXISTS " +
                       "FOR (d:PushDelivery) ON (d.status, d.nextAt)");

                tx.run("CREATE INDEX refresh_token_user IF NOT EXISTS " +
                       "FOR (rt:RefreshToken) ON (rt.userId)");

                return null;
            });
        }
        LOG.info("Neo4j schema ready.");
    }
}
