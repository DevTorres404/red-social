# Entregable de Seguimiento - Red Social Distribuida

**Grupo:** Orbit
**Integrantes:** Damian Torres, Melanie Tomala, Jean Cedeño
**Enlace al repositorio:** [DevTorres404/red-social](https://github.com/DevTorres404/red-social)
**Enlace al video:** [Insertar enlace aquí]

---

## Guion y Estructura del Video (Duración estimada: 4 minutos)

La idea de este guion es que cada uno comparta pantalla y hable sobre las partes en las que más ha trabajado. Pueden usar el diagrama de Archify que generamos recientemente como apoyo visual para explicar la arquitectura.

### 1. Damián Torres (Full Stack / DevOps) - ⏱️ ~1.5 a 2 minutos
**Enfoque:** Arquitectura, Infraestructura y Backend.

*   **Introducción:** "Hola a todos, somos el equipo encargado de desarrollar la red social 'Orbit'. Nuestro equipo está conformado por Jean, Melanie y yo, Damián."
*   **Arquitectura General (Mostrar Diagrama de Archify):** "Para cumplir con la consigna, implementamos una arquitectura fuertemente desacoplada. Tenemos nuestro Frontend SPA en React servido por Nginx, el cual además actúa como Reverse Proxy enrutando las peticiones de forma transparente hacia nuestro Backend."
*   **El Backend en Quarkus:** "La API REST está en **Quarkus** con Java 21. Aunque fue un requisito, entendemos perfectamente por qué se utiliza en este escenario: su naturaleza Cloud Native nos da tiempos de arranque rapidísimos y bajo consumo de memoria, ideal para meterlo en Docker."
*   **Persistencia Distribuida (Mostrar Neo4j y RustFS Console):** "El punto central de esta práctica es cómo separamos los datos. Por un lado, tenemos **Neo4j**, que nos exige el proyecto para modelar eficientemente el grafo social (`SIGUE`, `PUBLICA`) sin las limitaciones de SQL. Por otro lado, no guardamos imágenes en el grafo; usamos **RustFS** (un Object Storage tipo S3) para aislar los binarios. Así, el backend solo une las piezas, guardando la URL del S3 en el nodo de Neo4j."
*   **Demostración Rápida de Infraestructura:** "Y todo esto se orquesta fácilmente con Docker Compose (mostrar terminal). Con un comando levantamos la base de grafos, el storage y nuestra app, garantizando un despliegue confiable."

### 2. Jean Cedeño (Frontend Developer) - ⏱️ ~1.5 minutos
**Enfoque:** Interfaz de Usuario (UI) y Comunicación en Tiempo Real.

*   **Pase de Damián a Jean:** "Ahora Jean les mostrará cómo se ve y cómo interactúa el cliente con esta infraestructura."
*   **Recorrido por el Frontend (Mostrar la aplicación en el navegador):** "En el lado del cliente, trabajamos con una SPA (Single Page Application) en React + Vite. Implementamos un patrón de componentes presentacionales alimentados por Custom Hooks para aislar la lógica de negocio."
*   **Funcionalidad de Posts y Feed:** "Como pueden ver, ya podemos registrarnos, loguearnos y ver un feed de publicaciones. Recientemente, integramos mejoras en la UX, como enlaces rápidos al perfil haciendo clic en el avatar del creador del post."
*   **Tiempo Real (Mostrar Chat / Notificaciones):** "Uno de los hitos principales que logramos es la integración de WebSockets bidireccionales. Gracias a esto, el sistema de Chat y el Live Feed se actualizan al instante sin tener que recargar la página. Además, ya integramos la base para notificaciones Web Push vía VAPID conectadas con nuestro Service Worker."

### 3. Melanie Tomala (QA y QC) - ⏱️ ~1.5 minutos
**Enfoque:** Calidad, Pruebas y Validación Funcional.

*   **Pase de Jean a Melanie:** "Para asegurar que toda esta conexión de micro-servicios y datos funcione, Melanie detallará nuestro flujo de calidad."
*   **Estrategia de Testing (Mostrar la carpeta de scripts de pruebas):** "Para asegurar la estabilidad del proyecto, implementamos una batería de 'Smoke Tests' automatizados usando scripts en PowerShell (Phase A hasta G) que nos permiten verificar los flujos críticos sin intervención manual."
*   **Prueba Funcional en Vivo:** "Para cerrar, haremos una demostración rápida desde el Frontend. Vamos a crear un post con una imagen. Al darle publicar, aunque lo vemos reflejado aquí al instante, por debajo están ocurriendo varias cosas: el archivo pesado se está subiendo directamente a nuestro almacenamiento S3 (RustFS), mientras que en nuestra base de datos Neo4j se crea la relación del grafo indicando que este usuario acaba de publicar este post."
*   **Cierre:** "Hasta el momento hemos cubierto los pilares principales de la arquitectura distribuida solicitada: bases de datos de grafos, object storage, websockets, y comunicación backend-frontend. ¡Gracias por su atención!"

---

> **💡 Tips para grabar el video:**
> *   Pueden usar **Zoom** o **Google Meet** para grabar la reunión donde los tres estén conectados, e ir rotando quién comparte pantalla.
> *   Otra opción es que alguien comparta la pantalla todo el tiempo y vaya haciendo clics mientras los demás explican su parte.
> *   Asegúrense de probar el audio antes de arrancar y no olviden subir el archivo a YouTube o Google Drive con permisos públicos.
