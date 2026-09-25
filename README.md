# Red Social Distribuida

Aplicación web distribuida que implementa las funcionalidades esenciales de una red social, diseñada para demostrar conceptos de **Sistemas Distribuidos**.

## Integrantes

<!-- TODO: completar con los integrantes del grupo -->
- Integrante 1
- Integrante 2
- Integrante 3

## Arquitectura

```
┌─────────────┐      REST / WebSocket      ┌─────────────────┐
│    React     │ ◄────────────────────────► │  Quarkus Backend│
│  (Vite SPA) │                             │   (Java 21)     │
└─────────────┘                             └────────┬────────┘
                                                     │
                              ┌──────────────────────┼──────────────────────┐
                              │                      │                      │
                       Cypher (Bolt)           S3 API                  Web Push
                              │                      │                      │
                    ┌─────────▼──────┐    ┌──────────▼──────┐    ┌─────────▼──────┐
                    │     Neo4j 5    │    │   MinIO (S3)    │    │   Browser Push  │
                    │  (Graph DB)    │    │ (Object Storage)│    │     Service     │
                    └────────────────┘    └─────────────────┘    └────────────────┘
```

## Stack tecnológico

| Componente | Tecnología | Puerto |
|---|---|---|
| Frontend | React 18 + Vite | 3000 |
| Backend | Quarkus 3.15 + Java 21 | 8080 |
| Base de datos de grafos | Neo4j 5 | 7474 / 7687 |
| Almacenamiento de archivos | MinIO (S3-compatible) | 9000 / 9001 |
| Comunicación en tiempo real | WebSocket (Quarkus Next) | — |
| Notificaciones | Web Push (VAPID) | — |
| Contenedores | Docker / Docker Compose | — |

## Inicio rápido

### Requisitos
- Docker 24+
- Docker Compose v2
- Java 21+ (para desarrollo local del backend)
- Node 22+ (para desarrollo local del frontend)

### Con Docker Compose (recomendado)

```bash
# 1. Copiar variables de entorno
cp .env.example .env
# 2. Editar .env con los valores reales (especialmente JWT y VAPID)
# 3. Levantar todo
docker compose up --build
```

Servicios disponibles:
- Frontend: http://localhost:3000
- Backend API: http://localhost:8080
- Swagger UI: http://localhost:8080/q/swagger-ui
- Neo4j Browser: http://localhost:7474
- MinIO Console: http://localhost:9001

### Desarrollo local

**Backend:**
```bash
cd backend
mvn quarkus:dev
```

**Frontend:**
```bash
cd frontend
npm install
npm run dev
```

## Variables de entorno necesarias

Ver [.env.example](.env.example) para la lista completa.

Las variables críticas que **deben** generarse manualmente:
- `JWT_SECRET`: mínimo 256 bits
- `VAPID_PUBLIC_KEY` / `VAPID_PRIVATE_KEY`: generar con `npx web-push generate-vapid-keys`

## Modelo del grafo (Neo4j)

```cypher
// Nodos
(:Usuario {id, username, email, bio, avatarUrl, createdAt})
(:Post    {id, content, mediaUrl, createdAt})

// Relaciones
(:Usuario)-[:SIGUE]    ->(:Usuario)
(:Usuario)-[:PUBLICA]  ->(:Post)
(:Usuario)-[:REACCIONA {tipo, fecha}]->(:Post)
(:Usuario)-[:COMENTA]  ->(:Post)
```

## Endpoints principales

```
POST   /api/auth/register
POST   /api/auth/login

GET    /api/users/{id}
PUT    /api/users/{id}
GET    /api/users/{id}/followers
GET    /api/users/{id}/following
GET    /api/users/{id}/suggestions

POST   /api/users/{id}/follow
DELETE /api/users/{id}/follow

POST   /api/posts
GET    /api/posts/{id}
DELETE /api/posts/{id}

GET    /api/feed

GET    /api/graph/users/{id}/common-connections
GET    /api/graph/users/{id}/reachable
GET    /api/graph/users/{id}/recommended

WS     /ws/chat/{conversationId}
```

## Mecanismos distribuidos

### REST
Operaciones CRUD convencionales. Stateless, cacheable, estandarizado.

### WebSocket
Chat en tiempo real. Conexión persistente bidireccional entre cliente y servidor.  
Diferencia clave con REST: no hay request/response — el servidor puede enviar mensajes sin que el cliente los solicite.

### Web Push
Notificaciones fuera de la aplicación mediante el protocolo VAPID.  
El navegador mantiene una suscripción push activa incluso cuando la app está cerrada.

### Neo4j
Base de datos de grafos para modelar relaciones sociales.  
Las consultas Cypher aprovechan traversals de grafos que serían costosos en SQL.

### MinIO (S3-compatible)
Almacenamiento de objetos para archivos multimedia.  
Separación clara: Neo4j guarda metadata, MinIO guarda los bytes.

## Consultas Cypher implementadas

<!-- TODO: documentar las 5+ consultas Cypher no triviales -->

1. Feed personalizado (publicaciones de usuarios seguidos)
2. Amigos en común
3. Usuarios recomendados (seguidos de seguidos)
4. Usuarios alcanzables a N niveles
5. Ranking de publicaciones por reacciones en la red

## Decisiones técnicas relevantes

- **Quarkus** sobre Spring Boot: arranque más rápido, menor footprint en contenedores.
- **MinIO** sobre almacenamiento local: API S3-compatible, fácil migración a cloud.
- **Neo4j** modelado como grafo real: relaciones SIGUE, PUBLICA, REACCIONA como aristas tipadas.
- **WebSocket Next** (Quarkus): API moderna, no blocking, basada en Vert.x.
