# Red Social Distribuida

Aplicación de curso con React, Quarkus, Neo4j y RustFS. El modelo social usa **seguimiento dirigido**: seguir a alguien no crea una amistad ni un seguimiento recíproco.

## Arquitectura

```text
Navegador React ── REST / WebSocket ── Quarkus ── Cypher ── Neo4j
                                          └──── S3 ───── RustFS privado
                                          └──── Web Push ───── servicio del navegador
```

- Neo4j conserva usuarios, relaciones, publicaciones, mensajes y referencias a imágenes; nunca almacena archivos binarios.
- RustFS conserva los bytes de las imágenes. El backend autoriza la lectura antes de emitir una URL firmada de 60 segundos.
- El historial de chat se consulta por REST. WebSocket entrega mensajes nuevos sin polling.
- Las publicaciones crean trabajos Web Push durables en Neo4j; un proceso separado los entrega sin bloquear la publicación.
- La vista «Red» muestra recorridos y publicaciones calculados por cinco consultas Cypher reales, no un dibujo precargado.
- Esta instalación ejecuta **una instancia de backend**. Los tickets y conexiones WebSocket están en memoria; no hay coordinación entre réplicas.

## Arranque local

Requisitos: Docker Compose v2, Git Bash/WSL y OpenSSL para generar las claves JWT. Para desarrollo fuera de Docker también se necesitan Java 21 y Node.

```bash
cp .env.example .env
# Antes de continuar, define RUSTFS_ACCESS_KEY y RUSTFS_SECRET_KEY únicos en .env.
sh scripts/generate-jwt-keys.sh
node scripts/generate-vapid-keys.mjs
docker compose up --build
```

No usar `APP_SEED_FORCE=true` sobre datos que se quieran conservar. Los volúmenes de Neo4j y RustFS persisten entre reinicios; `docker compose down -v` los elimina.

Al sustituir una instalación MinIO existente, Orbit crea un volumen RustFS nuevo: **las imágenes antiguas no se copian** y sus referencias anteriores dejarán de mostrarse. El volumen antiguo no se borra automáticamente.

| Servicio | Dirección local |
|---|---|
| Aplicación | http://localhost:3000 |
| API / Swagger | http://localhost:8080 / http://localhost:8080/q/swagger-ui |
| Neo4j Browser | http://localhost:7474 |
| Consola RustFS | http://localhost:9001 |

En `.env` deben configurarse secretos reales, no los valores de ejemplo. `RUSTFS_PUBLIC_ENDPOINT` debe ser accesible desde los navegadores que abrirán imágenes; `WEBSOCKET_ALLOWED_ORIGIN` debe coincidir exactamente con el origen del frontend. En un despliegue HTTPS, ambos deben usar direcciones seguras y coherentes con el túnel (ej. Cloudflare Tunnel). Las claves privadas JWT se generan en `secrets/` (ignorado por Git) y se montan de solo lectura.

## Despliegue en servidor

Usa el Compose **autónomo** `docker-compose.prod.yml`, no lo combines con el de desarrollo. Con `cloudflared` instalado en el host, 3000 (app), 8080 (API de diagnóstico) y 9000 (S3) se vinculan **solo a `127.0.0.1`**. El túnel publica `https://orbit.labtorres.me` → `http://127.0.0.1:3000` y `https://media.orbit.labtorres.me` → `http://127.0.0.1:9000`; `/api` y `/ws` pasan por Nginx del frontend, sin tercer subdominio. Neo4j y la consola RustFS permanecen internos. La base nueva arranca sin cuentas de demostración.

Los requisitos, la plantilla `.env.prod.example`, el primer arranque, las verificaciones, actualizaciones y respaldos están en [comando.md](comando.md).

## Modelo de datos esencial

El inicio de sesión acepta **email o nombre de usuario** en el mismo campo. `POST /api/auth/login` recibe `{ "identifier": "usuario_o_email", "password": "..." }`; por compatibilidad también acepta el campo anterior `email`. Las credenciales incorrectas reciben la misma respuesta sin indicar si existe la cuenta.

```cypher
(:Usuario)-[:SIGUE]->(:Usuario)
(:Usuario)-[:PUBLICO]->(:Post {id, content, mediaKey, mediaType, mediaSize, createdAt})
(:Usuario)-[:LE_GUSTA]->(:Post)
(:Usuario)-[:ENVIO]->(:Mensaje)-[:EN_CONVERSACION]->(:Conversacion)
(:Usuario)-[:PARTICIPA]->(:Conversacion)
```

`mediaKey` es una clave generada por el servidor, no una URL suministrada por el cliente. Las imágenes se guardan en el bucket privado `red-social` (o `RUSTFS_BUCKET`).

## Publicaciones con imagen

Hay **un solo flujo de escritura de imágenes**: `POST /api/posts/with-image` recibe multipart con `content` y exactamente una parte `image`. Se aceptan PNG/JPEG reales de hasta 5 MiB y 16 megapíxeles, con extensión y tipo declarados concordantes. El nombre original no se utiliza como clave S3. `POST /api/posts` solo crea publicaciones de texto y rechaza `mediaUrl` arbitrarios.

`GET /api/posts/{id}/media-url` exige JWT y devuelve una URL de lectura firmada de 60 segundos. El bucket rechaza lecturas anónimas. Si falla la creación del post tras subir la imagen, se intenta borrarla; una eliminación fallida se registra para reintento. Al borrar un post, la clave se encola en Neo4j dentro de la misma transacción y luego se borra el objeto. Las limpiezas pendientes se reintentan al iniciar el backend.

La interfaz permite seleccionar, previsualizar y quitar una imagen, muestra progreso y permite reintentar tras un error. El feed vuelve a solicitar una URL de lectura al cargarse.

## Conversaciones en tiempo real

`GET /api/messages/conversations` lista interlocutores; `GET /api/messages/{userId}?skip=0&limit=50` devuelve historial paginado. La conversación se crea en Neo4j al enviar el primer mensaje. `POST /api/messages/ws-ticket` con `{ "otherUserId": "..." }` exige JWT y devuelve un ticket de un solo uso, válido 30 segundos y vinculado a esa pareja y al vencimiento del JWT. El navegador lo ofrece en `Sec-WebSocket-Protocol` al conectar a `/ws/chat/{conversationId}`; **no se incluye el JWT en la URL**. El origen permitido se configura con `WEBSOCKET_ALLOWED_ORIGIN`.

Cada mensaje WebSocket envía `{ "clientMessageId": "UUID", "text": "..." }`. El servidor valida el tamaño (máximo 1000 caracteres y marco de 2048 bytes), limita la frecuencia (10 mensajes por 10 segundos), persiste antes de difundir y deduplica reintentos por ID de cliente. Al reconectar, la interfaz recupera historial REST y fusiona por ID sin duplicados. Un ticket de otra conversación no concede acceso. La etiqueta «Guardado en servidor» no significa entregado al otro navegador; «Leído» refleja el estado persistido cuando se vuelve a consultar.

## Notificaciones dentro de Orbit

La **campana de la barra superior** es la bandeja interna y no requiere permiso del navegador. Las publicaciones nuevas de personas seguidas, likes y comentarios en tus posts, nuevos seguidores y mensajes directos generan una notificación persistente en Neo4j. Mientras Orbit está abierto, consulta el contador cada cinco segundos y muestra un **toast dentro de Orbit** al detectar un aviso nuevo; al recuperar el foco también consulta de inmediato. Al abrir la campana se carga la lista, se marcan como leídas y cada aviso enlaza al post, perfil o conversación. Este canal no es instantáneo como el WebSocket del chat. Las acciones repetidas de seguir o dar like no duplican el aviso. Si se borra un post, se eliminan sus avisos relacionados.

Los avisos de escritorio **Web Push son adicionales**: se activan por dispositivo en Configuración → Notificaciones y pueden aparecer incluso con la pestaña cerrada. Desactivar o denegar Push no desactiva la bandeja de Orbit. La persistencia del aviso interno ocurre después de guardar la acción; si ese paso falla, la acción no se revierte y el fallo queda registrado para diagnóstico.

## Web Push

Las claves VAPID se generan una vez en `.env` ignorado por Git; el generador rechaza reemplazar claves existentes para no invalidar suscripciones. Nunca publiques `VAPID_PRIVATE_KEY`. La pantalla **Notificaciones** solicita permiso solo al pulsar «Activar en este navegador». Cada navegador guarda su propia suscripción. Al desactivar o salir se intenta eliminar la suscripción del servidor y del navegador; un endpoint revocado se elimina al recibir 404/410 del servicio push. El backend solo acepta endpoints HTTPS de servicios push conocidos, para evitar que una suscripción falsa se convierta en una solicitud a una dirección interna.

El Service Worker (`/sw.js`) recibe un payload mínimo con ID de post. Al hacer clic abre `/posts/{id}`; si el post ya no existe, la pantalla ofrece volver al inicio. La cola `PushDelivery` se crea en la misma transacción que el post para los seguidores suscritos. El worker revisa de nuevo la relación `SIGUE`, la propiedad de la suscripción y la existencia del post antes de enviar; reintenta hasta cinco veces, con espera creciente y llamada limitada a diez segundos. Un error push **no cancela** una publicación ya persistida. El envío entre Neo4j y un servicio externo es de tipo *al menos una vez*: si el proceso cae después de enviar y antes de cerrar el trabajo, puede reintentar. La etiqueta del aviso usa el ID de post para evitar duplicados visibles en el mismo navegador; no se promete entrega exactamente una vez.

Web Push requiere **HTTPS** al desplegarlo fuera de la máquina local; `http://localhost:3000` funciona para desarrollo por ser un contexto seguro reconocido por el navegador. No funciona en cualquier `http://IP`. Si VAPID no está configurado, la publicación sigue disponible pero `/api/push/public-key` devuelve 503. Una pestaña cerrada no impide que el Service Worker muestre el aviso, siempre que el navegador/sistema mantenga habilitadas las notificaciones.

## Consultas y visualización del grafo

`/graph` consume las cinco rutas autenticadas `/api/graph/common/{otherId}`, `/reachable`, `/recommendations`, `/network-posts` y `/trending-posts`. La cuenta actual siempre procede del JWT; el cliente no puede consultar la red privada de otra identidad cambiando un parámetro. Las consultas usan `$userId`/`$otherId`, límites 50–100, orden estable y devuelven listas vacías cuando no hay resultados. [Detalle de Cypher, semántica y fixture esperado](docs/CYPHER_QUERIES.md).

## Verificación

```powershell
cd backend; mvn -B clean test; mvn -B -DskipTests package
cd ../frontend; npm run lint; npm run build
cd ..; ./scripts/phase-a-c-smoke.ps1
./scripts/phase-d-smoke.ps1
./scripts/phase-e-smoke.ps1
./scripts/phase-f-g-smoke.ps1
```

Para comprobar el fallo de almacenamiento por separado, ejecutar `./scripts/phase-d-outage-smoke.ps1`: detiene RustFS temporalmente, verifica `503` sin post huérfano y lo reinicia en `finally`. No ejecutarlo mientras otra persona esté usando ese servicio local.

Los smoke usan usuarios temporales con nombres únicos y eliminan únicamente los datos que generan. Requieren los servicios levantados y `.env` local para la limpieza de prueba. La puerta de salida y los casos todavía no verificados se mantienen en [PLAN.md](PLAN.md).

**Estado actual (27-09-2026):**
- ✅ Arranque limpio (`docker compose down -v && docker compose up --build`) verificado
- ✅ Smokes A–G: todos PASS (53 + 18 + 19 + 26 checks)
- ✅ Tests unitarios backend: 9 PASS
- ✅ Build + lint frontend: OK
- ✅ Fix duplicados PushDelivery: query `DISTINCT` + constraint única `(postId, subscriptionId)`

## Alcance pendiente

La entrega Web Push con una pestaña cerrada y permiso concedido todavía requiere una prueba manual en un navegador/dispositivo que acepte notificaciones. El smoke automatizado verifica suscripciones, cola, autorización, ausencia de envío al dejar de seguir y las cinco consultas; el test Java prepara una solicitud cifrada y firmada sin enviarla. Ninguno prueba la entrega por un proveedor externo, y una prueba de egress real hacia FCM requiere aprobación explícita. Presencia online y verificación completa desde volúmenes nuevos siguen pendientes.

**Estrategia de tokens (implementada 27-09-2026):**
- Access token: 15 min, almacenado **solo en memoria** (React state), nunca en localStorage
- Refresh token: 30 días, cookie `HttpOnly; Secure; SameSite=Strict; Path=/api/auth/refresh`
- `/api/auth/refresh`: rota el refresh token (revoca el usado, emite nuevo par)
- `/api/auth/logout`: revoca refresh token en Neo4j y limpia la cookie
- Frontend: intercepta 401 → llama refresh automáticamente → reintenta request original
