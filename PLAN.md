# Development Plan — Red Social Distribuida

> **Goal**: Implement a fully distributed social network that demonstrates REST, WebSocket, Web Push, Neo4j graph traversal, and S3 object storage — each mechanism justified by the problem it solves.

---

## Phases overview

```
Phase 1 ─ Infrastructure ready       [DONE ✅]
Phase 2 ─ Auth & security
Phase 3 ─ Users & social graph
Phase 4 ─ Posts, feed & reactions
Phase 5 ─ File storage (MinIO/S3)
Phase 6 ─ Real-time chat (WebSocket)
Phase 7 ─ Web Push notifications
Phase 8 ─ Graph queries (Cypher)
Phase 9 ─ Frontend integration
Phase 10─ Docker & final validation
```

---

## Phase 1 — Infrastructure & Project scaffold ✅

| Task | Status |
|---|---|
| Git repository + `.gitignore` / `.gitattributes` | ✅ |
| `docker-compose.yml` (Neo4j + MinIO + backend + frontend) | ✅ |
| MinIO bucket auto-init on startup | ✅ |
| Quarkus `pom.xml` with all required extensions | ✅ |
| React + Vite scaffold + Dockerfile + nginx proxy | ✅ |
| `application.properties` with env-var wiring | ✅ |
| `.env.example` with all variables documented | ✅ |
| CodeGraph initialized | ✅ |

---

## Phase 2 — Authentication & Security

**Why**: Every resource must be protected. JWT is stateless, which fits a distributed architecture where the backend doesn't keep session state.

### Backend

- [ ] Generate RSA key pair for JWT signing (`privateKey.pem` / `publicKey.pem`)
- [ ] `POST /api/auth/register` — create user node in Neo4j, hash password (bcrypt)
- [ ] `POST /api/auth/login` — validate credentials, return signed JWT
- [ ] `POST /api/auth/refresh` — refresh token logic
- [ ] `@RolesAllowed` guard on all private endpoints
- [ ] `AuthService` — JWT build/verify via SmallRye JWT
- [ ] `PasswordService` — bcrypt via Quarkus Security

### Frontend

- [ ] `/register` page (form + validation)
- [ ] `/login` page
- [ ] `AuthContext` — store JWT in memory (not localStorage for security)
- [ ] Axios/fetch interceptor — attach `Authorization: Bearer` header on every request
- [ ] Protected route wrapper component

### Neo4j schema (run once on startup)

```cypher
CREATE CONSTRAINT user_id IF NOT EXISTS FOR (u:Usuario) REQUIRE u.id IS UNIQUE;
CREATE CONSTRAINT user_email IF NOT EXISTS FOR (u:Usuario) REQUIRE u.email IS UNIQUE;
CREATE CONSTRAINT user_username IF NOT EXISTS FOR (u:Usuario) REQUIRE u.username IS UNIQUE;
CREATE CONSTRAINT post_id IF NOT EXISTS FOR (p:Post) REQUIRE p.id IS UNIQUE;
```

---

## Phase 3 — Users & Social Graph

**Why**: Neo4j models `:SIGUE` as a first-class relationship — traversals like "who do my followings follow?" are native O(depth) operations, not expensive JOINs.

### Backend

- [ ] `GET  /api/users/{id}` — fetch user profile
- [ ] `PUT  /api/users/{id}` — update bio, avatarUrl
- [ ] `GET  /api/users/{id}/followers` — list followers (Cypher)
- [ ] `GET  /api/users/{id}/following` — list followed users (Cypher)
- [ ] `POST /api/users/{id}/follow` — create `[:SIGUE]` edge
- [ ] `DELETE /api/users/{id}/follow` — remove `[:SIGUE]` edge
- [ ] `GET  /api/users/{id}/suggestions` — recommended users (see Phase 8)
- [ ] `GET  /api/users/search?q=` — search by username

### Cypher — core social queries

```cypher
// Follow
MATCH (a:Usuario {id: $followerId}), (b:Usuario {id: $followedId})
MERGE (a)-[:SIGUE]->(b)

// Unfollow
MATCH (a:Usuario {id: $followerId})-[r:SIGUE]->(b:Usuario {id: $followedId})
DELETE r

// Followers list
MATCH (follower:Usuario)-[:SIGUE]->(u:Usuario {id: $userId})
RETURN follower
```

### Frontend

- [ ] `/users/:id` profile page (avatar, bio, followers/following counts)
- [ ] Follow/unfollow button with optimistic UI
- [ ] Followers / following modal list
- [ ] User search bar in navbar

---

## Phase 4 — Posts, Feed & Reactions

**Why**: Feed is graph-aware — it queries posts from followed users specifically, not "all recent posts". That's the graph advantage.

### Backend

- [ ] `POST /api/posts` — create post (text + optional media upload)
- [ ] `GET  /api/posts/{id}` — single post
- [ ] `DELETE /api/posts/{id}` — owner-only delete
- [ ] `GET  /api/feed` — personalized feed (posts from followed users, sorted by date)
- [ ] `POST /api/posts/{id}/react` — add `[:REACCIONA {tipo, fecha}]`
- [ ] `DELETE /api/posts/{id}/react` — remove reaction
- [ ] `GET  /api/posts/{id}/reactions` — list reactions

### Cypher — feed and reactions

```cypher
// Personalized feed
MATCH (me:Usuario {id: $userId})-[:SIGUE]->(followed:Usuario)-[:PUBLICA]->(p:Post)
RETURN p, followed
ORDER BY p.createdAt DESC
LIMIT $limit

// Add reaction
MATCH (u:Usuario {id: $userId}), (p:Post {id: $postId})
MERGE (u)-[r:REACCIONA]->(p)
SET r.tipo = $tipo, r.fecha = datetime()
```

### Frontend

- [ ] Feed page (`/`) — paginated post list
- [ ] Post card component (content, media, reactions, author)
- [ ] Create post form (text + file picker)
- [ ] Reaction button (like)
- [ ] Post detail page `/posts/:id`

---

## Phase 5 — File Storage with MinIO (S3)

**Why**: Storing binary files in Neo4j is forbidden by the spec and architecturally wrong. MinIO provides an S3-compatible API: the graph stores only the object key, the bytes live in object storage.

### Backend

- [ ] `StorageService` — inject `S3AsyncClient` pointing to MinIO
- [ ] `POST /api/storage/upload` — multipart upload → MinIO, return object key
- [ ] `GET  /api/storage/presigned/{key}` — generate pre-signed URL (time-limited)
- [ ] Wire upload into `POST /api/posts` — upload file first, store key in Neo4j Post node
- [ ] `PUT /api/users/{id}/avatar` — upload avatar → MinIO, store URL in Usuario node

### Configuration (already in `application.properties`)

```properties
quarkus.s3.endpoint-override=http://localhost:9000
quarkus.s3.path-style-access=true
quarkus.s3.aws.credentials.type=static
```

### Frontend

- [ ] File picker component with preview
- [ ] Upload progress indicator
- [ ] Display media from presigned URLs in post cards and profile avatar

---

## Phase 6 — Real-time Chat (WebSocket)

**Why**: REST is request/response — the client must poll to receive new messages. WebSocket maintains a persistent bidirectional connection: the server PUSHES messages the instant they arrive. Polling is explicitly forbidden by the spec.

### Backend

- [ ] `@WebSocket("/ws/chat/{conversationId}")` endpoint (Quarkus WebSockets Next)
- [ ] `ChatService` — manage active sessions per conversation
- [ ] `ConversationService` — start conversation, list conversations, message history
- [ ] Persist messages in Neo4j:

```cypher
// Conversation node
(:Usuario)-[:PARTICIPA]->(:Conversacion)<-[:PARTICIPA]-(:Usuario)

// Message
(:Usuario)-[:ENVIA {contenido, timestamp}]->(:Conversacion)
```

- [ ] `GET  /api/conversations` — list user conversations
- [ ] `GET  /api/conversations/{id}/messages` — message history (REST, paginated)
- [ ] `POST /api/conversations` — start new conversation

### Frontend

- [ ] Chat list sidebar
- [ ] Chat window with real-time message stream
- [ ] `useChatSocket` hook — manages `WebSocket` lifecycle and reconnection
- [ ] Message input + send
- [ ] Online/typing indicator (optional but good demo)

### Key concept to explain in presentation

```
REST:          Client ──request──► Server ──response──► Client
               (client initiates every exchange)

WebSocket:     Client ◄──────────────────────────────► Server
               (persistent connection, server can push anytime)
```

---

## Phase 7 — Web Push Notifications

**Why**: Web Push sends notifications to the browser even when the tab is closed. It uses the W3C Push API + VAPID keys — NOT a React alert (that's explicitly banned by the spec).

### Backend

- [ ] Add `nl.martijndwars:web-push` Java library to `pom.xml`
- [ ] `POST /api/push/subscribe` — save push subscription (endpoint + keys) in Neo4j
- [ ] `DELETE /api/push/subscribe` — unsubscribe
- [ ] `PushNotificationService` — send Web Push message via VAPID
- [ ] Trigger notification on:
  - New post by a followed user → notify all followers
  - New follower → notify the followed user

```cypher
// Find all followers to notify when user $publisherId publishes
MATCH (follower:Usuario)-[:SIGUE]->(publisher:Usuario {id: $publisherId})
MATCH (follower)-[:HAS_SUBSCRIPTION]->(sub:PushSubscription)
RETURN sub
```

### Frontend

- [ ] Register Service Worker (`sw.js`) — handle `push` event, show notification
- [ ] Request push permission on login
- [ ] `POST /api/push/subscribe` with `PushSubscription` from browser API
- [ ] Notification click → navigate to relevant post/user

### VAPID keys generation

```bash
npx web-push generate-vapid-keys
# Copy output to .env: VAPID_PUBLIC_KEY, VAPID_PRIVATE_KEY
```

---

## Phase 8 — Graph Queries (Cypher — minimum 5 non-trivial)

**Why**: These queries demonstrate that Neo4j is being used as a graph database, not a relational one disguised. Traversals across multiple hops are native to Neo4j.

| # | Query | Description |
|---|---|---|
| Q1 | Common connections | Users followed by both `A` and `B` |
| Q2 | Followers of a user | Direct `[:SIGUE]` traversal |
| Q3 | Reachable users (2 levels) | Who can I reach through my followings' followings |
| Q4 | Recommended users | Followed by people I follow, but not by me |
| Q5 | Network feed ranking | Posts from my network ranked by reaction count |

```cypher
-- Q1: Common connections between user A and B
MATCH (a:Usuario {id: $userA})-[:SIGUE]->(common)<-[:SIGUE]-(b:Usuario {id: $userB})
RETURN common

-- Q3: Reachable at 2 hops (not already followed)
MATCH (me:Usuario {id: $userId})-[:SIGUE*1..2]->(reachable)
WHERE NOT (me)-[:SIGUE]->(reachable) AND reachable.id <> $userId
RETURN DISTINCT reachable

-- Q4: Recommended (friends of friends)
MATCH (me:Usuario {id: $userId})-[:SIGUE]->(friend)-[:SIGUE]->(rec)
WHERE NOT (me)-[:SIGUE]->(rec) AND rec.id <> $userId
RETURN rec, count(*) AS mutualFriends
ORDER BY mutualFriends DESC
LIMIT 10

-- Q5: Top posts in my network by reactions
MATCH (me:Usuario {id: $userId})-[:SIGUE*1..2]->(u)-[:PUBLICA]->(p:Post)
OPTIONAL MATCH (p)<-[r:REACCIONA]-()
RETURN p, count(r) AS reactions
ORDER BY reactions DESC
LIMIT 20
```

### Backend endpoints

- [ ] `GET /api/graph/users/{id}/common-connections?with={otherId}`
- [ ] `GET /api/graph/users/{id}/reachable?depth=2`
- [ ] `GET /api/graph/users/{id}/recommended`
- [ ] `GET /api/graph/network-feed`
- [ ] `GET /api/graph/visualize/{id}` — return graph data for visualization

### Frontend

- [ ] Graph visualization component (e.g., `react-force-graph` or `vis-network`)
- [ ] "Explore your network" page — visual graph of social connections

---

## Phase 9 — Frontend Integration & UX

### Pages / Routes

| Route | Component | Protected |
|---|---|---|
| `/` | Feed | ✅ |
| `/login` | LoginPage | ❌ |
| `/register` | RegisterPage | ❌ |
| `/users/:id` | ProfilePage | ✅ |
| `/posts/:id` | PostDetail | ✅ |
| `/messages` | ConversationList | ✅ |
| `/messages/:id` | ChatWindow | ✅ |
| `/network` | GraphVisualization | ✅ |
| `/settings` | UserSettings | ✅ |

### Shared components

- [ ] `Navbar` — search, notifications bell, user menu
- [ ] `PostCard` — reusable across feed and profile
- [ ] `UserCard` — for suggestions and search results
- [ ] `ProtectedRoute` — redirect to `/login` if no JWT
- [ ] `Toast` notifications for actions

### State management

- [ ] `AuthContext` — user session, JWT
- [ ] `useWebSocket` hook — WebSocket lifecycle per conversation
- [ ] React Query (or SWR) for server state + caching

---

## Phase 10 — Dockerization & Final Validation

### Build verification

- [ ] `mvn package -DskipTests` — Quarkus JVM build succeeds
- [ ] `docker build -f backend/src/main/docker/Dockerfile.jvm backend/` — image builds
- [ ] `npm run build` — Vite production build succeeds
- [ ] `docker build frontend/` — nginx image builds
- [ ] `docker compose up --build` — all 5 services healthy

### Integration checklist (mandatory demo items)

- [ ] ☐ Register + login (two different users)
- [ ] ☐ Follow between users
- [ ] ☐ View social graph visualization
- [ ] ☐ Create a post with a file upload
- [ ] ☐ MinIO Console shows the uploaded file
- [ ] ☐ Feed shows only posts from followed users
- [ ] ☐ Recommendation query returns results
- [ ] ☐ Chat between two users in real time (two browser tabs)
- [ ] ☐ Web Push notification received on new post
- [ ] ☐ Run 5 Cypher queries in Neo4j Browser
- [ ] ☐ `docker compose up` brings everything up from scratch

### README final review

- [ ] All team members listed
- [ ] Architecture diagram updated to match real implementation
- [ ] All endpoints documented
- [ ] All 5 Cypher queries documented with explanation
- [ ] Env vars table complete
- [ ] Instructions tested on a clean machine

---

## Dependency graph (what blocks what)

```
Phase 1 (infra) ──► Phase 2 (auth) ──► Phase 3 (users/graph)
                                               │
                              ┌────────────────┼─────────────────┐
                              ▼                ▼                  ▼
                         Phase 4           Phase 5            Phase 8
                       (posts/feed)       (MinIO)          (graph queries)
                              │
                    ┌─────────┴─────────┐
                    ▼                   ▼
               Phase 6              Phase 7
                (WS chat)          (Web Push)
                    │                   │
                    └─────────┬─────────┘
                              ▼
                         Phase 9
                         (frontend)
                              │
                              ▼
                         Phase 10
                       (docker + demo)
```

---

## Tech debt to avoid

> These are the most common mistakes that make a project fail the evaluation even if it "works".

- ❌ Using Neo4j like a relational DB (only `MATCH (n) RETURN n` queries)
- ❌ Implementing chat with `setInterval` polling instead of WebSocket
- ❌ Showing a React alert and calling it "Web Push"
- ❌ Storing file bytes in Neo4j properties
- ❌ Components that render static data not connected to the real backend
- ❌ A docker-compose that requires 10 manual steps before working

---

*Last updated: 2026-09-25*
