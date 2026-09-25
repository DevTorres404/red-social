# ACTIVIDAD PRÁCTICA: RED SOCIAL DISTRIBUIDA

## 1. Descripción general

En grupos de **3 integrantes**, desarrollar una aplicación web distribuida que implemente las funcionalidades esenciales de una **red social**.

La solución deberá integrar diferentes tecnologías y mecanismos de comunicación, persistencia y almacenamiento, de manera que cada componente tenga una responsabilidad claramente definida dentro de la arquitectura.

El objetivo de la actividad no es únicamente construir una aplicación funcional, sino **diseñar, implementar y justificar una arquitectura distribuida**, identificando cómo se comunican sus componentes y por qué se utiliza cada tecnología.

---

## 2. Stack tecnológico obligatorio

La solución deberá utilizar obligatoriamente:

| Componente | Tecnología |
|---|---|
| Frontend | React |
| Backend | Quarkus + Java |
| Base de datos de grafos | Neo4j |
| Almacenamiento de archivos | Servicio compatible con S3 |
| Comunicación en tiempo real | WebSocket |
| Notificaciones | Web Push |
| API principal | REST/HTTP |
| Contenedores | Docker / Docker Compose |

No se permitirá reemplazar Neo4j por una base de datos relacional.

La interacción con Neo4j deberá evidenciar el uso de **Cypher y relaciones propias de un modelo de grafos**. No será suficiente utilizar Neo4j como si fuera una base de datos relacional convencional.

---

# 3. Funcionalidades mínimas

## 3.1. Usuarios

El sistema deberá permitir:

- Registrar usuarios.
- Iniciar sesión.
- Consultar perfiles.
- Editar información básica del perfil.
- Visualizar seguidores y usuarios seguidos.

La autenticación deberá proteger los recursos privados del backend.

---

## 3.2. Grafo social

Neo4j deberá utilizarse para representar las relaciones sociales entre los usuarios.

Como mínimo:

```text
(:Usuario)-[:SIGUE]->(:Usuario)
```

El sistema deberá permitir:

- Seguir usuarios.
- Dejar de seguir usuarios.
- Consultar seguidores.
- Consultar usuarios seguidos.
- Obtener sugerencias de usuarios.

Las sugerencias **no podrán ser aleatorias**.

Deberán obtenerse utilizando información del grafo, por ejemplo:

```text
Usuario A
   ↓ SIGUE
Usuario B
   ↓ SIGUE
Usuario C
```

Si A no sigue a C, el sistema podría identificar a C como una posible recomendación.

El grupo deberá diseñar y justificar su propio criterio de recomendación.

---

## 3.3. Publicaciones

Los usuarios podrán crear publicaciones.

Cada publicación deberá contener como mínimo:

- Autor.
- Texto.
- Fecha y hora.
- Recurso multimedia opcional.

Las relaciones correspondientes deberán representarse dentro del modelo de datos.

Ejemplo conceptual:

```text
(:Usuario)-[:PUBLICA]->(:Post)
```

Los archivos multimedia **no deberán almacenarse directamente en Neo4j**.

---

## 3.4. Almacenamiento de archivos

Las imágenes u otros archivos asociados a las publicaciones deberán almacenarse mediante un servicio compatible con **S3**.

El sistema deberá separar:

```text
Información estructurada
        ↓
      Neo4j

Archivos
        ↓
   Object Storage
       (S3)
```

En la base de datos únicamente deberá mantenerse la información necesaria para localizar o relacionar el recurso almacenado.

---

## 3.5. Feed de publicaciones

Cada usuario deberá disponer de un feed.

El feed deberá mostrar publicaciones de los usuarios que sigue.

No será válido implementar únicamente:

> "Mostrar las últimas publicaciones de todos los usuarios".

La generación del feed deberá considerar las relaciones existentes en el grafo social.

Como mínimo deberá existir:

```text
Usuario
   ↓ SIGUE
Usuarios seguidos
   ↓ PUBLICA
Publicaciones
   ↓
Feed
```

---

## 3.6. Reacciones

Los usuarios podrán reaccionar a las publicaciones.

Como mínimo deberá implementarse una reacción:

```text
(:Usuario)-[:REACCIONA]->(:Post)
```

Opcionalmente, la relación podrá almacenar propiedades:

```text
REACCIONA {
    tipo: "LIKE",
    fecha: ...
}
```

---

# 4. Mensajería en tiempo real

La aplicación deberá incorporar un sistema de **mensajería entre usuarios**.

La comunicación deberá realizarse mediante:

**WebSocket**

y no mediante consultas REST repetitivas cada cierto número de segundos.

El sistema deberá permitir como mínimo:

- Iniciar una conversación.
- Enviar mensajes.
- Recibir mensajes en tiempo real.
- Visualizar el historial de la conversación.

El grupo deberá explicar claramente la diferencia entre:

```text
REST
Request → Response

WebSocket
Cliente ↔ Servidor
conexión persistente
```

---

# 5. Notificaciones Web Push

La aplicación deberá implementar **Web Push Notifications**.

Cuando un usuario seguido realice una nueva publicación, el sistema deberá poder generar una notificación para los usuarios correspondientes.

Ejemplo:

```text
Anthony sigue a Carlos

Carlos publica
       ↓
Backend detecta seguidores
       ↓
Generación de evento
       ↓
Web Push
       ↓
Anthony recibe notificación
```

La notificación deberá dirigir al usuario hacia el recurso correspondiente dentro de la aplicación.

No se considerará Web Push mostrar únicamente una alerta o notificación dentro de React mientras la aplicación está abierta.

---

# 6. API REST

Las operaciones convencionales del sistema deberán exponerse mediante una API REST desarrollada en Quarkus.

Ejemplos:

```text
POST   /usuarios
GET    /usuarios/{id}

POST   /usuarios/{id}/seguir
DELETE /usuarios/{id}/seguir

POST   /posts
GET    /feed

GET    /usuarios/{id}/seguidores
GET    /usuarios/{id}/seguidos
GET    /usuarios/{id}/sugerencias
```

Los endpoints definitivos deberán ser diseñados por cada grupo.

---

# 7. Consultas de grafos

Cada grupo deberá implementar **mínimo 5 consultas Cypher no triviales**.

No cuentan consultas simples equivalentes únicamente a:

```cypher
MATCH (n)
RETURN n
```

Las consultas deberán aprovechar relaciones del grafo.

Entre ellas deberán existir consultas capaces de resolver problemas como:

- Amigos/usuarios en común.
- Seguidores de determinado usuario.
- Usuarios alcanzables mediante relaciones de seguimiento.
- Usuarios recomendados.
- Publicaciones provenientes de la red de un usuario.

Al menos **una consulta deberá recorrer relaciones de más de un nivel**.

---

# 8. Arquitectura

El grupo deberá diseñar un diagrama de arquitectura antes de implementar la solución.

Como referencia:

```text
                    ┌─────────────┐
                    │    React    │
                    └──────┬──────┘
                           │
              REST / WebSocket
                           │
                    ┌──────▼──────┐
                    │   Quarkus   │
                    │   Backend   │
                    └───┬────┬────┘
                        │    │
                  Cypher│    │S3 API
                        │    │
                 ┌──────▼─┐ ┌▼─────────────┐
                 │ Neo4j  │ │Object Storage│
                 └────────┘ └──────────────┘
                        │
                   Web Push
                        │
                 ┌──────▼──────┐
                 │   Usuario   │
                 └─────────────┘
```

El diagrama definitivo deberá reflejar la arquitectura realmente implementada.

---

# 9. Dockerización

La solución deberá poder ejecutarse mediante contenedores.

Como mínimo deberán estar dockerizados los componentes que correspondan al despliegue del proyecto.

Se deberá entregar un archivo:

```text
docker-compose.yml
```

que permita levantar el entorno requerido con la menor cantidad posible de configuración manual.

---

# 10. Requisitos de arquitectura

La aplicación deberá demostrar claramente el uso de diferentes mecanismos propios de aplicaciones distribuidas:

| Necesidad | Mecanismo esperado |
|---|---|
| Operaciones CRUD y consultas | REST |
| Comunicación bidireccional | WebSocket |
| Notificaciones fuera de la aplicación | Web Push |
| Relaciones sociales | Base de datos de grafos |
| Archivos | Object Storage |
| Despliegue reproducible | Contenedores |

Cada grupo deberá poder explicar **por qué se utilizó cada mecanismo y qué problema resuelve**.

---

# 11. Restricciones

No se permitirá:

- Sustituir Neo4j por MySQL, PostgreSQL u otra base relacional.
- Implementar el chat mediante polling.
- Guardar imágenes directamente dentro de Neo4j.
- Simular Web Push mediante alertas de React.
- Utilizar datos completamente estáticos para demostrar las funcionalidades.
- Entregar componentes que no estén realmente integrados.
- Incorporar tecnologías obligatorias únicamente de forma decorativa.

Una funcionalidad que aparezca en la interfaz pero que no esté integrada correctamente con la arquitectura correspondiente se considerará incompleta.

---

# 12. Evidencias obligatorias

El grupo deberá demostrar durante la presentación:

1. Registro e inicio de sesión.
2. Dos o más usuarios interactuando.
3. Seguimiento entre usuarios.
4. Visualización del grafo generado.
5. Creación de publicaciones.
6. Carga de un archivo al almacenamiento S3.
7. Feed personalizado.
8. Consulta de recomendación basada en el grafo.
9. Chat funcionando en tiempo real entre dos clientes.
10. Recepción de una notificación Web Push.
11. Ejecución de consultas Cypher.
12. Levantamiento de la infraestructura mediante contenedores.

---

# 13. Entregables

Cada grupo deberá entregar:

### Código fuente

Repositorio Git con:

```text
/frontend
/backend
/docker-compose.yml
README.md
```

### README técnico

Deberá contener como mínimo:

- Integrantes.
- Descripción del proyecto.
- Arquitectura.
- Tecnologías utilizadas.
- Instrucciones de ejecución.
- Variables de entorno necesarias.
- Modelo del grafo.
- Endpoints principales.
- Explicación del uso de REST.
- Explicación del uso de WebSocket.
- Explicación del uso de Web Push.
- Consultas Cypher desarrolladas.
- Decisiones técnicas relevantes.

### Diagrama de arquitectura

Debe representar los componentes y mecanismos de comunicación utilizados.

### Demostración

Todos los integrantes deberán participar y ser capaces de explicar tanto el funcionamiento como las decisiones técnicas de la solución.

---

# 14. Condición fundamental

El proyecto **no se evaluará únicamente por funcionar**.

El grupo deberá demostrar que comprende:

**qué componente se comunica con cuál, mediante qué mecanismo, dónde se almacena cada tipo de información y por qué se tomó cada decisión tecnológica.**

La finalidad de la actividad es construir una aplicación que permita experimentar en la práctica diferentes conceptos de **Sistemas Distribuidos**, no simplemente desarrollar una red social.