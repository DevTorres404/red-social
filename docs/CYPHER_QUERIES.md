# Cinco consultas Cypher del grafo social

Las implementaciones ejecutables están en `backend/src/main/java/com/redsocial/graph/GraphRepository.java`. Todos los recorridos parten del `userId` extraído del JWT; solo Q1 recibe además `otherId`. Los límites son intencionales para impedir respuestas ilimitadas.

## Q1 — Seguidos en común

```cypher
MATCH (:Usuario {id: $userId})-[:SIGUE]->(common:Usuario)<-[:SIGUE]-(:Usuario {id: $otherId})
RETURN DISTINCT common.id AS id, common.username AS username
ORDER BY username, id LIMIT 50
```

Compara **salidas** de seguimiento de dos personas. Si no comparten ninguna, devuelve `[]`. `otherId` debe existir; el endpoint devuelve 404 si no.

## Q2 — Alcance de hasta dos pasos

```cypher
MATCH path=(me:Usuario {id: $userId})-[:SIGUE*1..2]->(target:Usuario)
WHERE target <> me AND all(n IN nodes(path) WHERE single(x IN nodes(path) WHERE x = n))
WITH target, path ORDER BY length(path), [n IN nodes(path) | n.id]
WITH target, head(collect(path)) AS path
RETURN target.id AS id, target.username AS username,
       length(path) AS distance, nodes(path)[1].id AS viaId
ORDER BY distance, username, id LIMIT 100
```

Elige el camino más corto y, en empates, uno determinista. `viaId` permite dibujar la arista real del segundo salto. Excluye ciclos al origen y duplicados.

## Q3 — Recomendaciones explicables

```cypher
MATCH (me:Usuario {id: $userId})-[:SIGUE]->(via:Usuario)-[:SIGUE]->(candidate:Usuario)
WHERE candidate <> me AND NOT (me)-[:SIGUE]->(candidate)
WITH candidate, via ORDER BY via.username
WITH candidate, collect(DISTINCT via.username) AS mutuals,
     count(DISTINCT via) AS mutualCount
RETURN candidate.id AS id, candidate.username AS username, mutualCount, mutuals
ORDER BY mutualCount DESC, username, id LIMIT 50
```

Recomienda seguidos de seguidos, indica las conexiones comunes y excluye a quien ya se sigue. El orden no depende del azar.

## Q4 — Publicaciones de la red seguida

```cypher
MATCH (:Usuario {id: $userId})-[:SIGUE]->(author:Usuario)-[:PUBLICO]->(p:Post)
OPTIONAL MATCH (liker:Usuario)-[:LE_GUSTA]->(p)
WITH author, p, count(DISTINCT liker) AS likeCount
RETURN p.id AS id, author.id AS authorId, author.username AS authorUsername,
       p.content AS content, p.createdAt AS createdAt, likeCount
ORDER BY createdAt DESC, id LIMIT 50
```

El recorrido `SIGUE` + `PUBLICO` excluye posts de autores ajenos; el conteo opcional no duplica la fila del post.

## Q5 — Publicaciones destacadas por reacciones

```cypher
MATCH (:Usuario {id: $userId})-[:SIGUE]->(author:Usuario)-[:PUBLICO]->(p:Post)
OPTIONAL MATCH (liker:Usuario)-[:LE_GUSTA]->(p)
WITH author, p, count(DISTINCT liker) AS likeCount
RETURN p.id AS id, author.id AS authorId, author.username AS authorUsername,
       p.content AS content, p.createdAt AS createdAt, likeCount
ORDER BY likeCount DESC, createdAt DESC, id LIMIT 50
```

Un post sin likes conserva conteo cero. `DISTINCT` impide que relaciones repetidas inflen el rango.

## Fixture reproducible

`scripts/phase-f-g-smoke.ps1` crea usuarios temporales A, B, C, D, E; aristas A→B, A→C, B→C, B→D, C→D; dos posts de B, uno de C y uno de E. Espera:

| Consulta | Resultado clave |
|---|---|
| Q1(A,B) | C; Q1(A,E) vacío |
| Q2(A) | B y C a un paso, D a dos; nunca E |
| Q3(A) | D, con dos conexiones comunes (B y C) |
| Q4(A) | Tres posts de B/C, ninguno de E |
| Q5(A) | El post de B con dos likes primero; tres IDs únicos |

El smoke verifica esos resultados por REST y elimina únicamente sus usuarios y posts temporales. Esto demuestra recorridos observables que serían joins recursivos/manuales en un esquema relacional, con dirección, profundidad y autoría expresadas directamente en los patrones de Neo4j.
