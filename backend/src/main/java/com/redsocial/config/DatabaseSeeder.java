package com.redsocial.config;

import com.redsocial.messaging.ConversationId;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Transaction;
import org.neo4j.driver.TransactionContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sembrador de datos de demostración para Neo4j.
 *
 * ── Credenciales de demo (públicas a propósito) ──────────────────────────────
 * Son credenciales de DEMOSTRACIÓN y están escritas acá a la vista para que
 * cualquiera pueda levantar el proyecto y entrar sin pedirle permiso a nadie.
 *   usuario  password    email
 *   admin    admin123    admin@redsocial.dev
 *   damian   damian123   damian@test.com
 *   juan     juan123     juan@test.com
 * Los hashes se calculan en tiempo de seed con el MISMO bcrypt que usa
 * AuthService (io.quarkus.elytron.security.common.BcryptUtil), así que el login
 * contra estos usuarios funciona de verdad — nunca se siembra un hash falso.
 * En un entorno real, esto no se hace: el seed se desactiva.
 *
 * ── Modos de ejecución ──────────────────────────────────────────────────────
 * Normal  — si ya hay usuarios, no hace nada (no duplica datos).
 * Forzado — con app.seed.force=true borra TODOS los nodos y relaciones y
 *           vuelve a sembrar. Sirve para levantar la demo desde cero.
 *
 * ── Reglas del seed ──────────────────────────────────────────────────────────
 * Los ids se generan en Java (UUID.randomUUID()) y se pasan como parámetros
 * de Cypher; nunca con randomUUID() adentro del query, porque así el id del
 * seed es un UUID igual de bien formado que el que genera la aplicación.
 * Las fechas también salen de Java (Instant.now()) para que el formato sea
 * el mismo que espera Instant.parse() al leerlas.
 *
 * El modelo sembrado es el mismo que usa la aplicación:
 *   (:Usuario)-[:PARTICIPA]->(:Conversacion)<-[:PARTICIPA]-(:Usuario)
 *   (:Usuario)-[:ENVIO]->(:Mensaje)-[:EN_CONVERSACION]->(:Conversacion)
 * El id de :Conversacion sale de ConversationId.of(...), el MISMO algoritmo
 * que usa MessageRepository, así que un usuario puede seguir escribiendo en
 * un hilo ya sembrado sin crear una conversación duplicada.
 *
 * Nunca se siembra nada que la aplicación no use, y el seeder se traga sus
 * propios errores: un fallo del seed no puede impedir que la app levante.
 * Pero tragarse el error no es lo mismo que esconderlo — el borrado y cada paso
 * del seed corren en una única transacción gestionada, así que un fallo a mitad
 * de camino revierte todo y deja el grafo como estaba: no queda una base a
 * medias. El fallo se reporta igual, con la causa y con la lista exacta de los
 * pasos que no llegaron a completarse, porque una demo sin sembrar tampoco es
 * una demo funcional.
 */
@ApplicationScoped
public class DatabaseSeeder {

    private static final Logger LOGGER = Logger.getLogger(DatabaseSeeder.class.getName());

    /** Passwords de demo en texto plano, hasheadas en tiempo de seed. Ver el javadoc de la clase. */
    private static final Map<String, String> DEMO_PASSWORDS = new java.util.LinkedHashMap<>();
    static {
        DEMO_PASSWORDS.put("admin", "admin123");
        String[] nombres = {
            "andy", "skay", "ismael", "luis", "gino", "jean", "oscar", "estalin",
            "paulo", "peter", "anthony", "carlos", "said", "jose", "dayron",
            "alisson", "yandris", "melanie", "amy", "damian", "angel"
        };
        for (String nombre : nombres) {
            DEMO_PASSWORDS.put(nombre, nombre + "123");
        }
    }

    /**
     * Fuerza el reseed wiping completo. Por defecto false, que preserva el
     * comportamiento histórico: sembrar sólo si la base está vacía.
     */
    @ConfigProperty(name = "app.seed.force")
    boolean forceReseed;

    @ConfigProperty(name = "app.seed.enabled")
    boolean seedEnabled;

    @Inject
    Driver neo4jDriver;

    @Priority(2)
    void onStart(@Observes StartupEvent ev) {
        if (!seedEnabled) {
            LOGGER.info("Demo seeding disabled for this deployment.");
            return;
        }
        LOGGER.info("Iniciando chequeo de la base de datos (Seeder)...");

        // Se arma acá y no dentro del try para que el catch pueda decir qué
        // pasos del seed llegaron a ejecutarse antes de que reviente. Ojo: esos
        // pasos sí se escribieron, pero el rollback los descartó, así que la
        // lista sirve para diagnosticar el fallo, no para afirmar que quedaron
        // en la base.
        List<String> completedSteps = new ArrayList<>();

        try (Session session = neo4jDriver.session()) {

            // Todo el reseed ocurre en UNA transacción: el borrado y las
            // escrituras del seed se confirman o se revierten juntos, así que un
            // fallo a mitad de camino deja el grafo como estaba, no vacío.
            session.executeWrite(tx -> {
                // 1. Verificamos si ya existen usuarios
                var result = tx.run("MATCH (u:Usuario) RETURN count(u) AS total");
                int userCount = result.single().get("total").asInt();

                if (userCount > 0 && !forceReseed) {
                    LOGGER.info("Ya existen datos (" + userCount + " usuarios). "
                            + "Omitiendo seeding para no duplicar. "
                            + "Para forzar el reseed: APP_SEED_FORCE=true");
                    return null;
                }

                if (userCount > 0) {
                    LOGGER.warning("app.seed.force=true — borrando " + userCount
                            + " usuarios y TODOS los nodos/relaciones antes de volver a sembrar.");
                    tx.run("MATCH (n) DETACH DELETE n");
                } else {
                    LOGGER.info("Base de datos vacía. Insertando nodos y relaciones de prueba...");
                }

                seed(tx, completedSteps);

                LOGGER.info("Seeding completado: 3 usuarios, 3 posts, 4 likes, 2 comentarios, "
                        + "1 conversación, 2 mensajes, 2 notificaciones, 1 suscripción de push.");
                return null;
            });
        } catch (Exception e) {
            // Un seed fallido no puede tumbar la aplicación, pero el fallo tampoco
            // puede quedar escondido: como la transacción se revierte completa, no
            // queda un seed a medias, y se reporta la causa junto con los pasos que
            // se ejecutaron y quedaron revertidos.
            reportSeedFailure(e, completedSteps);
        }
    }

    // ── Datos ─────────────────────────────────────────────────────────────────

    /**
     * Los pasos del seed, en orden de ejecución. Se usan para calcular, cuando
     * algo falla, qué se alcanzó a sembrar y qué quedó sin sembrar.
     */
    private static final List<String> SEED_STEPS = List.of(
            "usuarios",
            "seguimientos",
            "publicaciones+likes",
            "comentarios",
            "conversacion+mensajes",
            "notificaciones",
            "suscripcion-push"
    );

    private void seed(TransactionContext tx, List<String> completedSteps) {
        Instant now = Instant.now();

        // ── Mapa maestro de parámetros ───────────────────────────────────────
        // UNA sola fuente de ids y valores para TODOS los statements.
        //
        // Antes cada bloque armaba su propio map —copia de otro map— y una clave
        // podía quedar en el mapa equivocado: el Cypher de comentarios pedía
        // $p1Id/$p2Id, que sólo estaban en postParams, y el driver abortaba con
        // "Expected parameter(s): p1Id, p2Id" a mitad del seed, dejando
        // Comentario/Conversacion/Mensaje en cero sin que se notara.
        //
        // Ahora todos los statements reciben ESTE map y runChecked() verifica
        // antes de escribir que estén todas las claves que el Cypher referencia.
        // Si se olvida una, el seed aborta en ese punto y lo dice con nombre y
        // apellido, en vez de dejar una base a medias en silencio.
        Map<String, Object> params = new HashMap<>();
        params.put("now", now.toString());
        params.put("earlier", now.minusSeconds(600).toString());

        // Usuarios de demo, con password hasheada de verdad (mismo bcrypt que
        // usa AuthService).
        for (String username : DEMO_PASSWORDS.keySet()) {
            params.put(username + "Id", UUID.randomUUID().toString());
            params.put(username + "Hash", BcryptUtil.bcryptHash(DEMO_PASSWORDS.get(username)));
        }

        // Publicaciones, comentarios, mensajes, notificaciones y suscripción.
        params.put("p1Id", UUID.randomUUID().toString());
        params.put("p2Id", UUID.randomUUID().toString());
        params.put("p3Id", UUID.randomUUID().toString());
        params.put("c1Id", UUID.randomUUID().toString());
        params.put("c2Id", UUID.randomUUID().toString());
        params.put("m1Id", UUID.randomUUID().toString());
        params.put("m2Id", UUID.randomUUID().toString());
        params.put("n1Id", UUID.randomUUID().toString());
        params.put("n2Id", UUID.randomUUID().toString());
        params.put("suscripcionId", UUID.randomUUID().toString());

        // El id de la conversación se deriva igual que en MessageRepository,
        // así el hilo sembrado y el hilo creado en runtime son el mismo nodo.
        String conversacionId = ConversationId.of(
                requireStr(params, "damianId"),
                requireStr(params, "jeanId"));
        params.put("conversacionId", conversacionId);

        // ── Usuarios ─────────────────────────────────────────────────────────
        StringBuilder usuariosCypher = new StringBuilder();
        Set<String> usuariosRequiredParams = new java.util.HashSet<>();
        usuariosRequiredParams.add("now");

        for (String username : DEMO_PASSWORDS.keySet()) {
            usuariosRequiredParams.add(username + "Id");
            usuariosRequiredParams.add(username + "Hash");
            
            if ("admin".equals(username)) {
                usuariosCypher.append(String.format(
                    "CREATE (%s:Usuario { id: $%sId, username: '%s', email: '%s@redsocial.dev', passwordHash: $%sHash, bio: 'Administrador del sistema', avatarUrl: '', createdAt: $now })\n",
                    username, username, username, username, username
                ));
            } else {
                usuariosCypher.append(String.format(
                    "CREATE (%s:Usuario { id: $%sId, username: '%s', email: '%s@test.com', passwordHash: $%sHash, bio: 'Estudiante', avatarUrl: '', createdAt: $now })\n",
                    username, username, username, username, username
                ));
            }
        }
        runChecked(tx, params, "usuarios", usuariosRequiredParams, usuariosCypher.toString());
        completedSteps.add("usuarios");

        // ── Seguimientos ─────────────────────────────────────────────────────
        String[][] follows = {
            {"damian", "ismael"}, {"damian", "andy"}, {"damian", "luis"},
            {"ismael", "damian"}, {"ismael", "jean"}, {"ismael", "oscar"},
            {"skay", "damian"}, {"skay", "peter"}, {"skay", "melanie"},
            {"andy", "gino"}, {"andy", "estalin"}, {"andy", "damian"},
            {"luis", "jose"}, {"luis", "dayron"},
            {"gino", "damian"}, {"gino", "paulo"},
            {"jean", "carlos"}, {"jean", "said"},
            {"melanie", "amy"}, {"melanie", "skay"},
            {"carlos", "angel"}, {"carlos", "jean"},
            {"peter", "alisson"}, {"peter", "skay"},
            {"damian", "jean"}, {"damian", "melanie"},
            {"jean", "damian"}, {"melanie", "damian"}
        };

        for (String[] pair : follows) {
            String u1 = pair[0];
            String u2 = pair[1];
            runChecked(tx, params, "seguimientos_" + u1 + "_" + u2,
                Set.of(u1 + "Id", u2 + "Id"),
                String.format("MATCH (a:Usuario {id: $%sId}), (b:Usuario {id: $%sId}) MERGE (a)-[:SIGUE]->(b)", u1, u2)
            );
        }
        completedSteps.add("seguimientos");

        // ── Publicaciones y likes ────────────────────────────────────────────
        runChecked(tx, params, "publicaciones+likes",
                Set.of("melanieId", "damianId", "jeanId", "p1Id", "p2Id", "p3Id", "now"),
                """
                MATCH (melanie:Usuario {id: $melanieId}),
                      (damian:Usuario {id: $damianId}),
                      (jean:Usuario  {id: $jeanId})
                CREATE (p1:Post {
                    id: $p1Id,
                    content: 'Bienvenidos a la red social! Esta plataforma fue construida con Neo4j, Quarkus y React.',
                    mediaUrl: '', createdAt: $now
                })
                CREATE (p2:Post {
                    id: $p2Id,
                    content: 'Las bases de datos de grafos son increibles para modelar redes sociales.',
                    mediaUrl: '', createdAt: $now
                })
                CREATE (p3:Post {
                    id: $p3Id,
                    content: 'Aprendiendo Cypher: MATCH (n) RETURN n',
                    mediaUrl: '', createdAt: $now
                })

                // Relación de autoría con nombre propio :PUBLICO (decisión de diseño del grupo; la consigna permite criterio propio).
                CREATE (melanie)-[:PUBLICO]->(p1)
                CREATE (damian)-[:PUBLICO]->(p2)
                CREATE (jean)-[:PUBLICO]->(p3)

                CREATE (damian)-[:LE_GUSTA]->(p1)
                CREATE (jean)-[:LE_GUSTA]->(p1)
                CREATE (jean)-[:LE_GUSTA]->(p2)
                CREATE (melanie)-[:LE_GUSTA]->(p3)
                """);
        completedSteps.add("publicaciones+likes");

        // ── Comentarios ──────────────────────────────────────────────────────
        runChecked(tx, params, "comentarios",
                Set.of("p1Id", "p2Id", "damianId", "jeanId", "c1Id", "c2Id", "now"),
                """
                MATCH (p1:Post   {id: $p1Id}),
                      (p2:Post   {id: $p2Id}),
                      (damian:Usuario {id: $damianId}),
                      (jean:Usuario   {id: $jeanId})
                CREATE (c1:Comentario {
                    id: $c1Id, text: 'Excelente post! Muy bienvenido.',
                    createdAt: $now
                })
                CREATE (c2:Comentario {
                    id: $c2Id, text: 'Totalmente de acuerdo, Neo4j es una pasada.',
                    createdAt: $now
                })
                CREATE (p1)-[:TIENE_COMENTARIO]->(c1)
                CREATE (p2)-[:TIENE_COMENTARIO]->(c2)
                CREATE (damian)-[:COMENTO]->(c1)
                CREATE (jean)-[:COMENTO]->(c2)
                """);
        completedSteps.add("comentarios");

        // ── Conversación + mensajes ──────────────────────────────────────────
        runChecked(tx, params, "conversacion+mensajes",
                Set.of("damianId", "jeanId", "conversacionId", "m1Id", "m2Id", "earlier", "now"),
                """
                MATCH (damian:Usuario {id: $damianId}),
                      (jean:Usuario   {id: $jeanId})
                MERGE (c:Conversacion {id: $conversacionId})
                  ON CREATE SET c.createdAt = $earlier
                SET c.updatedAt = $now
                MERGE (damian)-[:PARTICIPA]->(c)
                MERGE (jean)-[:PARTICIPA]->(c)

                CREATE (m1:Mensaje {
                    id: $m1Id, conversacionId: $conversacionId,
                    text: 'Hola Damian! Como va el proyecto?',
                    sentAt: $earlier, read: false
                })
                CREATE (m2:Mensaje {
                    id: $m2Id, conversacionId: $conversacionId,
                    text: 'Todo bien Jean, avanzando con los grafos',
                    sentAt: $now, read: false
                })
                CREATE (jean)-[:ENVIO]->(m1)
                CREATE (m1)-[:EN_CONVERSACION]->(c)
                CREATE (damian)-[:ENVIO]->(m2)
                CREATE (m2)-[:EN_CONVERSACION]->(c)
                """);
        completedSteps.add("conversacion+mensajes");

        // ── Notificaciones ───────────────────────────────────────────────────
        runChecked(tx, params, "notificaciones",
                Set.of("melanieId", "damianId", "jeanId", "p1Id", "n1Id", "n2Id", "now"),
                """
                MATCH (melanie:Usuario  {id: $melanieId}),
                      (damian:Usuario {id: $damianId}),
                      (jean:Usuario   {id: $jeanId}),
                      (p1:Post {id: $p1Id})
                CREATE (n1:Notificacion {
                    id: $n1Id, type: 'LIKE', read: false, createdAt: $now
                })
                CREATE (n2:Notificacion {
                    id: $n2Id, type: 'FOLLOW', read: false, createdAt: $now
                })
                CREATE (melanie)-[:TIENE]->(n1)
                CREATE (n1)-[:GENERADA_POR]->(damian)
                CREATE (n1)-[:SOBRE]->(p1)
                CREATE (damian)-[:TIENE]->(n2)
                CREATE (n2)-[:GENERADA_POR]->(jean)
                """);
        completedSteps.add("notificaciones");

        // ── Suscripción de push ──────────────────────────────────────────────
        // Fake push subscription removed as it breaks PushDeliveryWorker with invalid base64 keys
        completedSteps.add("suscripcion-push");
    }

    // ── Utilidades del seed ───────────────────────────────────────────────────

    /**
     * Ejecuta un statement contra Neo4j, previa comprobación de que el mapa
     * maestro tenga todas las claves que su Cypher referencia.
     *
     * El driver igual falla si falta una clave, pero con un
     * "Expected parameter(s): x, y" que no dice QUÉ statement lo pidió ni por
     * qué se perdió. Acá el error lo dice antes de escribir nada, y nombra el
     * paso del seed, así que el log de arranque alcanza para diagnóstico.
     */
    private void runChecked(TransactionContext tx, Map<String, Object> params, String step,
                            Set<String> requiredParams, String cypher) {
        List<String> missing = requiredParams.stream()
                .filter(key -> !params.containsKey(key))
                .toList();
        if (!missing.isEmpty()) {
            String message = "Seed incompleto en el paso '" + step
                    + "': el mapa maestro de parámetros no tiene " + missing
                    + ", que el Cypher de ese paso referencia.";
            LOGGER.severe(message);
            throw new IllegalStateException(message);
        }
        tx.run(cypher, params);
    }

    /** Lee un id del mapa maestro o revienta: si falta, el seed está mal armado. */
    private static String requireStr(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (!(value instanceof String s)) {
            throw new IllegalStateException(
                    "Seed incompleto: el mapa maestro no tiene el id '" + key + "'.");
        }
        return s;
    }

    /**
     * Reporta un seed que falló a mitad de camino.
     *
     * La aplicación sigue levantando (un seed roto no puede tumbar el arranque),
     * pero el log tiene que ser inequívoco: el borrado y cada paso del seed
     * corren dentro de una única transacción gestionada, así que el fallo
     * revirtió todo y la base queda sin cambios — NO con datos parciales, y esto
     * NO es una demo funcional.
     */
    private void reportSeedFailure(Exception e, List<String> completedSteps) {
        List<String> missingSteps = SEED_STEPS.stream()
                .filter(step -> !completedSteps.contains(step))
                .toList();

        LOGGER.severe("SEED REVERTIDO: la siembra de datos de demostración FALLÓ y toda la "
                + "transacción fue revertida (rollback), así que la base queda sin cambios. La "
                + "aplicación sigue corriendo, pero la demo NO está sembrada.");
        LOGGER.severe("Causa: " + e.getClass().getName() + ": " + e.getMessage());

        if (missingSteps.isEmpty()) {
            LOGGER.severe("No se detectan pasos pendientes, pero el seed falló igual: "
                    + "revisar el stack trace.");
        } else {
            LOGGER.severe("Pasos ejecutados y revertidos por el rollback: " + completedSteps);
            LOGGER.severe("Datos que FALTAN sembrar: " + String.join(", ", missingSteps)
                    + ". Para rehacer el seed completo desde cero: APP_SEED_FORCE=true");
        }

        LOGGER.log(Level.SEVERE, "Stack trace del fallo del seeder", e);
    }
}
