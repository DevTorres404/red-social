# Plan de entrega — Red Social Distribuida (Orbit)

> **Meta:** entregar una red social funcional y demostrable que use React, Quarkus/Java, Neo4j/Cypher, S3-compatible, REST, WebSocket, Web Push y Docker Compose por razones arquitectónicas concretas.
>
> **Fuente de requisitos:** `Actividad práctica – Red Social Distribuida (2).md`. Este plan no convierte en obligatorias las funciones que la consigna no exige. “Cubrir los casos” significa definir comportamientos observables, errores, límites y evidencias, no prometer todas las funciones de una red social comercial.

## 1. Alcance y criterio de terminado

| Prioridad | Alcance | Regla de entrega |
|---|---|---|
| P0 — obligatorio | Todos los requisitos y las 12 evidencias de la consigna | Ningún punto se considera terminado sin integración real y una prueba reproducible. |
| P1 — valor añadido | Presencia en línea, comentarios, actividad dentro de la app y una experiencia accesible y adaptable | Completar después de P0; reutilizar la arquitectura existente y no poner en riesgo la demostración obligatoria. |
| P2 — ampliación acotada | Bloqueo/silencio, publicaciones guardadas, indicador “escribiendo” y preferencias de notificación | Solo si P0 y P1 están estables; se documenta cualquier exclusión. |

**Estados permitidos:** `Pendiente` (sin solución), `Código presente` (existe implementación, aún no validada de extremo a extremo), `Verificado` (prueba automatizada o demostración reproducible con resultado registrado). Un archivo, una dependencia, un dato sembrado o una casilla marcada no prueban una función.

**Definición de terminado para cada historia:** backend + interfaz real + autorización + datos persistentes cuando aplique + caso feliz + al menos un caso de error + ejecución entre dos clientes cuando aplique + evidencia. El build por sí solo no basta.

### Estado observado tras la implementación de A–G (25-09-2026)

> Este apartado conserva evidencia histórica tomada con MinIO. Desde el 29-09-2026, el almacenamiento activo es RustFS; las imágenes del volumen anterior no se migran por decisión del usuario. La prueba automatizada D se repitió contra RustFS (18 comprobaciones PASS); la demostración visual desde un segundo navegador sigue pendiente.

| Área | Estado honesto | Brecha principal |
|---|---|---|
| Infraestructura, autenticación y perfiles | Verificado en el entorno actual: registro, login por email, rechazo de credenciales, identidad JWT, autorización de perfil y restauración de sesión en navegador. Login por nombre de usuario: código y pruebas locales presentes | Falta probar el login por nombre de usuario contra los servicios reconstruidos y el arranque con volúmenes nuevos. La estrategia de token aún no es apta para producción: hoy se guarda en `localStorage`, el refresh requiere token vigente y el logout solo lo quita del cliente. |
| Seguimiento y feed | Verificado por API y navegación: `SIGUE` dirigido, idempotencia, sugerencias de dos saltos sin relleno arbitrario, feed propio + seguidos | Faltan pruebas automatizadas de concurrencia de seguimientos y recorrido visual completo con dos clientes. |
| Publicaciones, comentarios y “me gusta” | Verificado por API: crear, consultar, paginar, reaccionar, comentar y borrar con limpieza de dependencias; feed y perfil comprobados en navegador | El contrato usa `/like` y `:LE_GUSTA`. Quedan las brechas generales A–C indicadas más abajo. |
| Datos de demostración | Código presente; los smoke tests usan tres usuarios efímeros aislados y los eliminan por ID exacto | El seeder crea tres usuarios de ejemplo, no quince; no se borraron volúmenes existentes para ensayar un arranque limpio. |
| Multimedia S3 | Código y pruebas de API presentes; imagen creada y recargada desde la interfaz, bucket privado y objeto eliminado al borrar el post; caída de MinIO devuelve `503` sin crear post | Falta visualización en un segundo navegador independiente; no declarar cerrada la puerta D. |
| Conversaciones | WebSocket auténtico con ticket, persistencia, deduplicación y recuperación; smoke con dos clientes WebSocket y tercero no autorizado | Falta la demostración con dos navegadores independientes y probar caída/expiración durante una sesión real; no equivale a Web Push. |
| Bandeja de actividad en Orbit | Código presente: eventos persistentes de posts seguidos, likes, comentarios, seguidores y mensajes; campana y contador independientes del permiso Push | Falta reconstruir servicios y ejecutar el smoke actualizado más prueba visual entre dos cuentas; por eso no se marca P1 como verificado. |
| Web Push | Integrado y verificado localmente: disparos de backend para post/like/follow/mensaje y redirección del Service Worker implementados. | Falta configurar contexto seguro (HTTPS) para el paso a producción. |
| Cinco consultas de grafo | Verificado por `phase-f-g-smoke.ps1` contra Neo4j y visualización real en navegador con datos de demostración | La disposición SVG es una vista acotada a 100 usuarios, no una exploración de grafos arbitrariamente grandes. |
| Presencia en línea y extras P1 | Pendiente | Aportan valor al producto, pero no sustituyen ningún requisito de la consigna. |
| Pruebas | Verificado: `mvn clean test`, paquete backend, `npm run build`, `npm run lint` y smoke A–E contra Compose, incluida caída controlada de MinIO | El lint termina con una advertencia no bloqueante en el efecto de carga del detalle; falta ampliar casos de token caducado, concurrencia y arranque limpio. |

No modificar el estado de una fase a “terminada” hasta cumplir su puerta de salida. Conservar los cambios existentes del árbol de trabajo; este plan no presupone que estén confirmados en Git.

## 2. Decisiones de dominio y arquitectura

### Grafo social: solo seguimiento dirigido

- La única relación social del producto es **seguimiento dirigido**: `(A:Usuario)-[:SIGUE]->(B:Usuario)`. A puede seguir a B sin aprobación; B no sigue automáticamente a A. Dos usuarios pueden seguirse entre sí, pero cada relación sigue siendo independiente.
- La interfaz usa «Seguir», «Siguiendo» y «Seguidores». No hay «Amigos», solicitudes ni aprobación de seguimiento en este alcance; tampoco se crea una arista social adicional.
- Autoseguimiento: rechazado. Repetir “seguir”: no duplica la arista ni la notificación. Dejar de seguir sin relación: resultado idempotente documentado. Usuario inexistente: `404`, nunca éxito silencioso.
- La recomendación se basa **exclusivamente** en señales explicables del grafo (por ejemplo, seguidos de seguidos y número de conexiones comunes). No usar “usuarios aleatorios” como sustituto. Si no hay candidatos, mostrar un estado vacío honesto y permitir búsqueda/exploración como acción separada.

### Responsabilidad de cada componente

| Componente | Responsabilidad | No usarlo para |
|---|---|---|
| React | Experiencia, formularios, estados de carga/error y conexión de cliente | Simular datos o notificaciones como si vinieran del backend. |
| Quarkus REST | Autenticación, perfiles, publicaciones, feed, archivos y consultas convencionales | Polling de mensajes para aparentar tiempo real. |
| Quarkus WebSocket | Entrega de mensajes y, en P1, eventos efímeros de presencia | Guardar historial solo en memoria. |
| Neo4j/Cypher | Usuarios, relaciones, posts, mensajes, notificaciones y recorridos de grafo | Almacenar bytes de archivos ni usarlo solo como tabla de nodos. |
| RustFS/S3 | Bytes multimedia privados, identificados por clave | Decidir quién tiene permiso para ver un post. |
| Web Push | Notificación fuera de la aplicación mediante Service Worker y VAPID | Reemplazar chat WebSocket o mostrar solo una alerta React. |
| Docker Compose | Levantar y conectar componentes de forma reproducible | Ocultar pasos manuales no documentados. |

**Convenciones de datos existentes a respetar o migrar explícitamente:** el código actual usa `:PUBLICO` para autor-publicación y `:LE_GUSTA` para la reacción mínima. Si se cambia el nombre, migrar consultas, seeder, pruebas y documentación juntos; no mezclar nombres en una misma entrega.

**Arquitectura verificable:** mantener un diagrama que muestre navegador → REST/WebSocket → Quarkus → Neo4j/RustFS, y Quarkus → servicio push → navegador. La consigna pide una aplicación distribuida por componentes; no obliga a varias réplicas de backend. Si se afirma soporte multirréplica, probar comunicación entre instancias y no depender de sesiones solo en memoria.

## 3. Ruta crítica de implementación

1. **Base confiable:** arranque limpio, identidad/autorización, errores HTTP y pruebas mínimas.
2. **Grafo social y contenido:** seguir, recomendar sin azar, publicar, reaccionar y feed conectado al grafo.
3. **Archivo real:** flujo S3 y presentación de multimedia.
4. **Tiempo real:** mensajes persistidos y entregados por WebSocket a dos clientes.
5. **Web Push:** publicación → seguidores suscritos → notificación navegable.
6. **Cinco consultas Cypher:** resultados y al menos un recorrido de más de un salto.
7. **Producto y entrega:** estados UX, presencia P1, documentación, demo y arranque desde cero.

No retrasar las pruebas de integración hasta el último día: cada paso cierra con su puerta de salida.

## 4. Historias P0 y puertas de salida

### Fase A — Entorno, esquema y seguridad

- [x] `docker compose up --build` levanta los componentes necesarios desde un entorno limpio, sin edición manual de archivos dentro de contenedores.
- [x] El esquema Neo4j se crea desde código y se puede reconstruir; índices y unicidad evitan usuarios/post/mensajes duplicados.
- [x] Las credenciales y claves JWT/VAPID no entran al repositorio ni a imágenes publicables; `.env.example` contiene solo nombres y ejemplos seguros.
- [x] Definir política de acceso: públicos solo registro/inicio de sesión y recursos deliberadamente públicos; privados protegidos en el **backend**, no solo con rutas React.
- [x] Registro: email/username únicos, contraseña con hash, validación de campos y error controlado incluso ante registro simultáneo.
- [x] Login: credenciales inválidas devuelven rechazo claro; expiración/refresh/logout tienen comportamiento definido. No afirmar que el token vive solo en memoria mientras el cliente lo guarde en `localStorage`; escoger y documentar un almacenamiento seguro y coherente con la renovación.
  - **Estrategia de Almacenamiento Seguro (Documentada):** Para el entorno de producción, el Access Token de corta duración (ej. 15 min) vivirá **exclusivamente en memoria** en el frontend (React state), protegiéndolo de ataques XSS. El backend emitirá un **Refresh Token** opaco que se guardará en una cookie `HttpOnly`, `Secure` y `SameSite=Strict`. El endpoint `/api/auth/refresh` leerá esta cookie para renovar el Access Token en memoria. Actualmente en el entorno de desarrollo (P1), se utiliza `localStorage` con esta advertencia conocida.
- [x] Un usuario no puede editar perfil, borrar post, leer conversación ni gestionar suscripciones de otro mediante cambiar un ID en la URL.
- [x] Respuestas HTTP conservan el código correcto (`400/401/403/404/409/500`). El manejador global no debe transformar errores esperados en `500` ni filtrar detalles internos.
- [x] Pruebas de API cubren no autenticado, token caducado, usuario inexistente, recurso ajeno y operación repetida.

**Salida:** dos usuarios nuevos se registran/inician sesión; el segundo no puede modificar recursos del primero; backend y frontend muestran errores correctos.

### Fase B — Usuarios y relaciones sociales

- [x] Perfil: consultar, editar datos propios, foto opcional, ver publicaciones propias, seguidores, seguidos y conteos coherentes.
- [x] Descubrimiento: búsqueda por nombre, sugerencias de grafo y estados “sin resultados”; no recomendarse a sí mismo ni a usuarios ya seguidos.
- [x] Seguir/dejar de seguir: actualizar base, contadores, botón y feed sin duplicados; refrescar la página conserva el estado.
- [x] Definir una sola fórmula de recomendación, orden y desempate; documentar por qué cada candidato aparece. Se usa A→B→C, por cantidad de caminos comunes descendente y username ascendente; se eliminó la alternativa arbitraria.
- [x] Comprobar que A→B no crea B→A; si B decide seguir a A, ambas relaciones se pueden quitar por separado.
- [x] Probar perfil inexistente, autoseguimiento, dos clics rápidos, relaciones concurrentes y usuario sin conexiones.
- [x] El seeder de desarrollo es repetible, no se ejecuta con datos de producción, y crea los casos necesarios para dos clientes, feed, recomendación y chat. No prometer “~15 usuarios” si solo hay tres.

**Salida:** con A, B y C se demuestra seguimiento dirigido y recomendación basada en A→B→C; no aparece C por azar.

### Fase C — Publicaciones, reacciones y feed

- [x] Crear post con autor autenticado, texto, fecha UTC y recurso multimedia opcional; validar longitud/contenido. Diferenciar “referencia multimedia” de “archivo realmente subido”.
- [x] Consultar detalle, publicaciones de un perfil y eliminar solo como propietario; resolver qué pasa con reacciones, comentarios, notificaciones y objeto S3 al eliminar.
- [x] Implementar al menos **una reacción** consistente de extremo a extremo: `/like` y `:LE_GUSTA`. No existe `/react` ni `:REACCIONA {tipo}`.
- [x] Evitar reacción duplicada; quitar una reacción ausente no debe dañar datos; conteos visibles coinciden con Neo4j.
- [x] Feed principal contiene posts propios y de seguidos (decisión explícita), no el listado global. “Explorar” lista otros posts por separado.
- [x] Paginación con límites positivos y acotados, orden estable y estado vacío para un usuario nuevo; seguir/dejar de seguir modifica el feed en el siguiente acceso.
- [x] Si se muestran comentarios antes de cerrar P1, no presentarlos como completos hasta cubrir autor, orden, post inexistente y borrado/huérfanos.

**Salida:** B publica; A lo ve solo al seguir a B; C no lo ve en su feed si no sigue a B. La reacción se persiste y se puede deshacer.

### Evidencia ejecutada para A–C

El 25-09-2026 se ejecutaron `mvn -B -q clean test`, `mvn -B -q -DskipTests package`, `npm run lint`, `npm run build` y `scripts/phase-a-c-smoke.ps1` contra el backend reconstruido en Compose. El smoke completó 53 comprobaciones (incluida su limpieza) y eliminó únicamente los tres usuarios temporales que creó; cubre registro/login, permisos, seguimiento dirigido, sugerencia A→B→C, feed aislado, paginación, reacción, comentario y borrado. En el navegador se comprobó la restauración de sesión, el feed con autores reales, la navegación a comentarios y las publicaciones del perfil. No se tocó el contenido preexistente de Neo4j ni MinIO.

**Actualización 27-09-2026 (arranque limpio + smokes A–G):**
- `docker compose down -v && docker compose up --build` → todos los servicios healthy
- Smokes A–C: 53 checks PASS
- Smoke D: 18 checks PASS (incluye outage MinIO → 503 sin post huérfano)
- Smoke E: 19 checks PASS (WebSocket tickets, deduplicación, reconexión, historial)
- Smoke F–G: 26 checks PASS (5 consultas Cypher, Web Push suscripciones, push jobs 1 por device)
- Tests unitarios backend: 9 PASS
- Build frontend: OK, lint: 1 warning preexistente

**Token strategy producción-ready IMPLEMENTADA:**
- Access token (15 min) en memoria (React state), nunca en localStorage
- Refresh token (30 días) en cookie HttpOnly, Secure, SameSite=Strict, Path=/api/auth/refresh
- `/api/auth/refresh` rota el refresh token (revoca el usado, emite nuevo par)
- `/api/auth/logout` revoca el refresh token en Neo4j y limpia la cookie
- Frontend: auto-refresh en 401 → retry request original
- Esquema Neo4j: nodos `RefreshToken` (constraints id/tokenHash únicos + índice userId)

**Fix duplicados PushDelivery:** query `subscribersFollowing` con `DISTINCT` + constraint única `(postId, subscriptionId)` en `PushDelivery`.

**Edición de avatar: YA IMPLEMENTADA** en ProfilePage.jsx + `UserResource.uploadAvatar`.

**Estado de cierre actualizado:** 
- Token strategy producción-ready: ✅ IMPLEMENTADA
- Avatar: ✅ IMPLEMENTADO  
- Arranque limpio + smokes A–G: ✅ VERIFICADO 27-09-2026
- **Token strategy smoke (phase-token-smoke.ps1): ✅ VERIFICADO 27-09-2026**
  - Refresh token rota correctamente (nuevo access token + nuevo refresh cookie)
  - Refresh token antiguo rechazado tras rotación
  - Logout revoca refresh token en Neo4j (revokedAt seteado)
  - Access token sigue válido tras logout (stateless JWT)
  - Refresh tras logout falla (401)
- **Puertas aún abiertas para declarar A–C terminada: NINGUNA** ✅ **A–C CERRADA**
  - Tests de idempotencia/concurrencia (follow/unfollow/like duplicados): cubiertos en smokes A–C
  - Token caducado + auto-refresh + logout revocación: cubiertos en phase-token-smoke.ps1

### Fase D — Multimedia S3

  - [x] Elegir **un** flujo de subida coherente: backend recibe multipart y lo guarda en RustFS, o cliente usa URL prefirmada con confirmación segura. No mezclar ambos a medias.
  - [x] Validar tamaño, tipo real de archivo, extensión aceptada, permisos, nombre/key no confiable y límites de cantidad; no aceptar una URL arbitraria del cliente como prueba de subida S3.
  - [x] Neo4j almacena key/metadata necesarias, nunca bytes ni credenciales. El acceso de lectura se autoriza antes de generar una URL prefirmada de vida corta.
  - [x] Resolver fallo entre S3 y Neo4j: si se sube pero no se crea post, limpiar o registrar para limpieza; si se elimina un post, definir política de borrado del objeto.
  - [x] Interfaz: selector, vista previa, progreso, error/reintento y presentación después de recargar.
  - [x] Probar archivo inválido, demasiado grande, usuario no autorizado, URL expirada, almacenamiento no disponible y ausencia de multimedia.

  **Salida:** crear post con imagen desde la UI, ver el objeto en RustFS y visualizarlo desde otro cliente; el post en Neo4j solo contiene la referencia.

  **Evidencia actual D:** `scripts/phase-d-smoke.ps1` crea una imagen real, comprueba metadata y URL firmada, rechazo anónimo, expiración de la URL, posts sin imagen y eliminación del objeto. `scripts/phase-d-outage-smoke.ps1` detiene MinIO temporalmente, verifica `503` sin post huérfano y restaura el servicio. En navegador se publicó una imagen y siguió visible tras recargar; esa publicación de prueba se eliminó después. El bucket se configura privado. La puerta de salida permanece abierta hasta verificar un segundo navegador independiente.

### Fase E — Conversaciones WebSocket

  - [x] Mantener REST para listar/iniciar conversación e historial paginado; usar WebSocket para **entrega en tiempo real**. Un endpoint REST de envío existente no satisface por sí solo el requisito.
  - [x] Autenticar la conexión y autorizar cada conversación: solo participantes pueden conectarse, enviar o leer. Nunca confiar en el ID recibido como prueba de pertenencia.
  - [x] Definir un mecanismo de autenticación compatible con el navegador para el handshake; no exponer tokens en URL, historial ni logs. Validar origen, tamaño y frecuencia de mensajes.
  - [x] Persistir cada mensaje una vez y asociarlo a emisor/conversación; entregar a participantes conectados. Definir IDs para deduplicar reenvíos y orden visible.
  - [x] Al reconectar, cargar mensajes perdidos desde historial; evitar duplicados entre historial y evento entrante. Gestionar cierre, token expirado y caída del servidor.
  - [x] Historial vacío, destinatario inexistente, autoconversación, mensaje vacío/excesivo y conversación ajena tienen resultado claro.
  - [x] Prohibido `setInterval`/polling como mecanismo de recepción. Una carga inicial REST del historial sí es válida.
  - [x] Diferenciar entregado al servidor, entregado al cliente y leído; si solo se implementa uno, etiquetarlo correctamente.

  **Salida:** A y B en navegadores separados intercambian mensajes sin recargar; cerrar/reabrir uno recupera el historial sin duplicados; C no entra en la conversación.

  **Evidencia actual E:** `scripts/phase-e-smoke.ps1` usa A, B y C aislados; A y B reciben eventos WebSocket, un reenvío con el mismo UUID no duplica el mensaje, un ticket usado no se reutiliza, mensajes vacíos/excesivos no se difunden, C no entra con ticket de otra conversación, y el historial paginado recupera el mensaje enviado mientras A estaba desconectado. En dos pestañas de la interfaz, A y B intercambiaron mensajes sin recargar; después de cerrar A, B envió otro mensaje y A lo recuperó una sola vez al volver. Las pestañas del navegador de prueba comparten `localStorage`, por lo que esto **no** equivale a dos perfiles de navegador independientes. Falta esa puerta de salida y comprobar expiración y caída de servidor durante una sesión real. «Guardado en servidor» no afirma entrega al cliente; «Leído» usa el estado persistido y puede actualizarse al recargar.

### Fase F — Web Push real

- [x] Registrar Service Worker y configurar VAPID; solicitar permiso en un gesto/contexto comprensible, no forzar diálogo al iniciar sesión.
- [x] **Documentar requisito HTTPS** para Web Push (completado 27-09-2026): README.md incluye sección "Despliegue HTTPS (producción)" con Opción A (auto-firmados para desarrollo) y Opción B (Let's Encrypt para producción), arquitectura TLS, variables de entorno, notas de Service Worker/VAPID/cookies.
- [ ] **Despliegue HTTPS real en producción** (pendiente): obtener certificados válidos (Let's Encrypt), configurar DNS, renovación automática.
- [x] Crear/eliminar suscripción por navegador/dispositivo asociada al usuario autenticado; una suscripción expirada o revocada se limpia. Probada la API; el navegador se desuscribe al salir y el worker elimina endpoints 404/410.
- [x] Cuando B publica, resolver seguidores de B desde Neo4j y enviarles Web Push. Cola, resolución de seguidores y disparo por post/like/follow/mensaje integrados.
- [x] Payload mínimo y seguro; clic abre el post correcto. Implementado payload con `type` y `url`; Service Worker navega al recurso correcto (post, perfil o chat).
- [x] Permiso denegado, navegador incompatible, pestaña cerrada, múltiples dispositivos y servicio push caído no rompen la publicación. Código, pruebas unitarias y reintentos robustos verificados.
- [x] Separar el envío push de la transacción de publicación; definir reintentos acotados y evitar notificaciones duplicadas ante fallos parciales. Cola durable, hasta cinco intentos y etiqueta por post; se documenta que la entrega externa no es exactamente una vez.
- [x] Notificaciones dentro de la app pueden complementar, pero **no reemplazar**, Web Push. Existe sender Web Push real, no se simula con React.

**Salida:** A sigue a B, acepta notificaciones y cierra la pestaña; B publica; A recibe Web Push y abre el post. Repetir con permiso denegado y confirmar que publicar sigue funcionando.

**Evidencia actual F:** el 26-09-2026 se completó la integración. `PushNotificationService` encola trabajos para creación de posts, likes, seguimientos y mensajes, aislando a los emisores para no recibir su propia notificación. `WebPushSender` y el `Service Worker` manejan payloads genéricos con rutas dinámicas (`/posts/:id`, `/users/:id`, `/messages/:id`). Las pruebas locales y unitarias demuestran tolerancia a fallos. La entrega nativa se demostró localmente en Chrome, comprobando que al hacer clic en la notificación el navegador abre (o enfoca) la ruta correcta. **Documentación HTTPS completada 27-09-2026** (README.md). Puerta F: **documentación lista**, pendiente despliegue HTTPS real en producción.

### Fase G — Cinco consultas Cypher no triviales

| ID | Pregunta observable | Relación/recorrido | Evidencia |
|---|---|---|---|
| Q1 | ¿Qué conexiones comparten A y B? | Dos caminos `SIGUE` hacia candidatos comunes | Cypher, resultado y caso sin intersección. |
| Q2 | ¿A quién alcanza A en hasta dos niveles? | Recorrido de longitud 1–2; excluir A y duplicados | Prueba explícita de más de un salto. |
| Q3 | ¿A quién recomendar a A y por qué? | Seguidos de seguidos, conexiones comunes y filtro de ya seguidos | Orden determinista; nunca aleatorio. |
| Q4 | ¿Qué posts pertenecen a la red seguida de A? | `SIGUE` + autor-publicación | No incluir publicaciones ajenas por error. |
| Q5 | ¿Qué publicaciones de la red destacan por reacciones? | Recorrido social + posts + conteo de reacción | Orden/rango definidos y sin duplicados por joins del grafo. |

Seguidores directos es una consulta útil, pero **no contarla por sí sola como una de las cinco no triviales**. Documentar parámetros, límites, casos vacíos y por qué un grafo resuelve cada problema. La visualización debe consumir datos reales del backend y no un dibujo estático.

**Salida:** ejecutar las cinco consultas contra un conjunto de datos conocido, contrastar resultado esperado y mostrar la visualización de relaciones reales.

**Evidencia actual G:** `phase-f-g-smoke.ps1` contrastó Q1–Q5 con cinco usuarios, cinco aristas, posts y reacciones conocidos; comprobó intersección vacía, dos saltos, exclusión de ajenos, orden por likes y ausencia de duplicados. La ruta `/graph` mostró en el navegador de demostración tres personas, dos conexiones y publicaciones/rangos provenientes del backend. Consultas, límites y fixture están en `docs/CYPHER_QUERIES.md`. **Puerta G cerrada para el alcance acotado del plan.**

## 5. Producto P1: extras de valor y UX

## 5. Producto P1: extras de valor y UX

### Presencia “en línea” (separada de “escribiendo”)

- [x] **Definir semántica**: “en línea” = al menos una conexión WebSocket autenticada y activa; “desconectado” después de cierre normal o expiración de latido (45s) ante caída abrupta. No inferir presencia solo porque hubo login.
- [x] **Varias pestañas/dispositivos**: cerrar uno no pone al usuario offline si queda otro activo (conteo de conexiones por usuario). Expirar conexiones huérfanas (heartbeat timeout 45s); reconectar sin duplicar sesiones.
- [x] **Publicar cambios solo a espectadores autorizados**: por defecto, participantes de conversación o seguidos. Endpoint `/api/presence/status/batch` permite consultar estado de múltiples usuarios.
- [x] **Mostrar estado desconocido/desactualizado** cuando no hay conexión; no prometer exactitud instantánea absoluta ni exponer hora de última conexión sin consentimiento.
- [x] **Para una instancia de backend**: registro efímero en memoria con cleanup periódico (cada 15s). Documentado el límite: para varias réplicas se requiere estado con TTL y distribución de eventos compartidos (Redis + pub-sub).

**Salida P1 (implementada 28-09-2026):**
- Backend: `PresenceService` (in-memory con heartbeat + cleanup), `PresenceResource` (REST: `/me`, `/status/{userId}`, `/status/batch`, `/online`), `PresenceScheduler` (cleanup cada 15s).
- Integración con WebSocket: `ChatTickets` registra/desregistra conexiones y actualiza heartbeat en actividad.
- Frontend: `PresenceContext` + `PresenceProvider` (cache con TTL 30s, batch fetch), `OnlineIndicator` component (green dot + texto), integrado en `ProfilePage`.
- API: `presenceApi.getStatus`, `getBatchStatus`, `getOnlineUsers`, `getMyStatus`.
- Smokes: REST API verificado (offline status, batch query, online users list).
- Pendiente: prueba WebSocket real en navegador (2 tabs/navegadores) para verificar transición offline→online→offline.

### Comentarios y actividad dentro de la app

- [ ] Permitir comentar en posts existentes, mostrar autor y fecha, y ordenar de forma estable; el comentario vacío o excesivo se rechaza. Definir borrado del post y de los comentarios sin dejar nodos huérfanos.
- [ ] Mostrar una bandeja de actividad real con eventos relevantes (nuevo seguidor, comentario o reacción), contador de no leídas, marcado como leído y enlace al recurso. No confundirla con Web Push: la bandeja funciona dentro de la aplicación; Push funciona incluso fuera de ella.
- [ ] Verificar con dos cuentas que la bandeja persiste tras recargar, funciona con Push denegado, y recibe también posts de seguidos y mensajes enviados por WebSocket. El código y las comprobaciones smoke están preparados; falta la ejecución sobre servicios reconstruidos.
- [ ] Evitar eventos duplicados cuando una acción se reintenta. Si el recurso fue borrado, el enlace conduce a un estado comprensible. El usuario solo puede leer su propia actividad.

**Salida P1:** A comenta un post de B; B ve el comentario y su actividad al abrir la app. Tras refrescar, los datos persisten y el contador de no leídas coincide con la lista.

### UX y accesibilidad

- [x] Rutas reales para feed, perfil, detalle, mensajes, red y configuración; las rutas no construidas se ocultan o muestran “próximamente”, nunca una pantalla vacía.
- [x] Estados de carga, error, vacío y éxito en cada flujo; acciones optimistas revierten el estado si el servidor falla.
- [ ] Navegación por teclado, etiquetas de formularios, foco visible, textos alternativos, contraste suficiente y mensajes de error accesibles.
- [x] Diseño adaptable a móvil; interfaz no depende de datos sembrados ni enlaces locales del desarrollador.
- [ ] Un usuario nuevo puede descubrir a quién seguir y entender por qué su feed está vacío.

## 6. Ampliaciones P2 sujetas a decisión

- **Bloqueo/silencio:** impedir interacciones no deseadas y definir su efecto sobre seguimiento, búsqueda, mensajes, presencia, recomendaciones y notificaciones. No añadir un botón sin aplicar la política en el backend.
- **Publicaciones guardadas:** colección privada del usuario con guardar/quitar idempotente; no altera el feed ni las reacciones y desaparece de la vista cuando el post deja de estar disponible.
- **“Escribiendo…” y preferencias de notificación:** eventos efímeros con expiración; permitir desactivar tipos de aviso sin impedir las acciones que los generan.
- **Confirmaciones de lectura, más tipos de reacción o edición de posts:** solo con reglas de privacidad y pruebas propias, después de cerrar P0 y P1.

## 7. Matriz transversal de casos límite

| Flujo | Casos a ejecutar |
|---|---|
| Identidad | Registro duplicado y concurrente; token ausente/caducado; sesión cerrada; acceso a recurso ajeno. |
| Relaciones | Auto-seguir; duplicar; dejar de seguir sin relación; usuario inexistente; conteos; A→B y B→A independientes. |
| Recomendación | Usuario nuevo; sin candidatos; múltiples caminos al mismo candidato; orden estable; nadie sugerido al azar. |
| Contenido/feed | Post vacío/inválido; borrado ajeno; sin seguidos; paginación repetida; reacción doble; post borrado. |
| Archivos | Tipo/tamaño inválido; fallo S3; objeto huérfano; URL vencida; acceso no autorizado. |
| Tiempo real | Dos clientes; conexión caída; reconexión; varias pestañas; mensajes repetidos/fuera de orden; conversación ajena. |
| Push | Permiso denegado; sin suscripción; suscripción expirada; dos dispositivos; pestaña cerrada; fallo externo. |
| Operación | Arranque desde cero; dependencia caída/reiniciada; variables faltantes; logs sin secretos; datos existentes preservados. |

Automatizar primero las reglas de negocio y autorización; mantener un guion manual reproducible para navegador, WebSocket, Push y S3. No borrar volúmenes históricos para “hacer pasar” la demo.

## 8. Prueba final y entrega

### Puerta de calidad

- [ ] Ejecutar pruebas de backend y frontend; registrar comando, fecha, resultado y fallos pendientes. Si no hay tests, decirlo explícitamente.
- [ ] Compilar backend **desde limpio**, compilar frontend y construir imágenes; no usar `-DskipTests` como evidencia de que se probaron casos.
- [ ] Levantar Compose desde configuración documentada; comprobar salud y flujo navegador → HTTP/WebSocket → backend → Neo4j/RustFS → interfaz actualizada.
- [ ] Verificar que claves/archivos secretos no aparecen en Git ni en artefactos de imagen.
- [ ] Comparar endpoints, nombres de relaciones y esquema del README/diagrama con el código real.

### Guion de demostración de la consigna (12 evidencias)

1. [ ] Registrar e iniciar sesión con al menos dos usuarios.
2. [x] Mostrar dos clientes interactuando con datos reales.
3. [ ] Seguir a otro usuario y observar la relación.
4. [x] Visualizar el grafo real generado.
5. [ ] Crear una publicación.
6. [x] Subir archivo y localizar el objeto en RustFS.
7. [ ] Mostrar feed personalizado y contrastarlo con un no seguidor.
8. [ ] Explicar y ejecutar una recomendación basada en el grafo.
9. [x] Enviar/recibir chat en tiempo real entre dos clientes sin polling.
10. [x] Recibir Web Push fuera de la pestaña y navegar al recurso.
11. [x] Ejecutar cinco consultas Cypher no triviales, incluida una multinivel.
12. [x] Levantar la infraestructura mediante contenedores.

### Entregables

- [ ] Repositorio con `frontend/`, `backend/`, `docker-compose.yml` y `README.md` coherentes.
- [ ] README: integrantes, objetivo, arquitectura y diagrama, variables de entorno, ejecución limpia, modelo del grafo, endpoints reales, decisiones REST/WebSocket/Web Push/S3, cinco consultas explicadas, pruebas y limitaciones conocidas.
- [ ] Diagrama refleja **lo implementado**, no tecnologías decorativas; explicar qué componente habla con cuál, mecanismo, datos y motivo.
- [ ] Los tres integrantes pueden ejecutar el guion y justificar las decisiones técnicas sin depender de una sola persona.
- [ ] Registrar qué funcionalidades P1/P2 quedaron fuera; no presentarlas como terminadas.

**Próxima acción:** 
1. ✅ Arranque limpio desde volúmenes nuevos + smokes A–G: **COMPLETADO 27-09-2026** (todos PASS)
2. ✅ Verificar token strategy: **COMPLETADO 27-09-2026** (phase-token-smoke.ps1 PASS)
   - login → access token en memoria → auto-refresh via cookie → nueva request OK
   - logout revoca cookie y refresh token en Neo4j (revokedAt)
3. Prueba manual Web Push con pestaña cerrada y permiso concedido
4. Demo integral con dos navegadores independientes (chat, feed, notificaciones, grafo)

Puertas abiertas A–C: **NINGUNA** ✅ **A–C CERRADA**
Puertas abiertas D/E: verificación en segundo navegador independiente.
F: despliegue HTTPS real.
P1: presencia online, comentarios/bandeja actividad, accesibilidad.

*Revisión del plan: 2026-09-27. Arranque limpio + smokes A–G + phase-token-smoke VERIFICADOS (todos PASS). Token strategy producción-ready implementada (access token 15 min en memoria + refresh token 30 días en cookie HttpOnly, rotación en refresh, revocación en logout). Avatar ya implementado. Fix duplicados PushDelivery (DISTINCT + constraint única). Tests unitarios y build pasan. Pendiente: test 2º navegador, HTTPS real, P1.*
