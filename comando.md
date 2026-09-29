# Comandos para desplegar Orbit en un servidor

Esta guía usa **solo** `docker-compose.prod.yml`. No se combina con el Compose local. Esta variante publica los puertos 3000 (app), 8080 (API) y 9000 (S3), sin Caddy ni certificados TLS. **No envíes credenciales reales por HTTP público**: antes de abrirlo a usuarios, configura HTTPS en un proxy externo o restringe acceso con firewall/VPN. Web Push fuera de localhost requiere HTTPS.

## 1. Preparar el servidor (una vez)

Requisitos: Linux, Docker Engine con `docker compose`, OpenSSL y un usuario no root con acceso a Docker. Decide primero qué puertos abrirá el firewall; nunca publiques 9001, 7474 ni 7687. Protege con TLS externo o VPN los puertos publicados por Compose.

Configura `APP_ORIGIN` como origen exacto que abre el navegador (sin barra final) y `RUSTFS_PUBLIC_ENDPOINT` como URL del API S3 accesible desde ese navegador. En acceso directo serían `http://tu-servidor:3000` y `http://tu-servidor:9000`; con un proxy TLS externo usa sus URL `https://` y conserva el host/ruta S3 para validar firmas.

```bash
git clone <URL_DEL_REPOSITORIO> orbit
cd orbit
umask 077
cp .env.prod.example .env
sed -i "s/^APP_UID=.*/APP_UID=$(id -u)/; s/^APP_GID=.*/APP_GID=$(id -g)/" .env
nano .env
```

En `.env`, completa `APP_ORIGIN`, `RUSTFS_PUBLIC_ENDPOINT`, `NEO4J_PASSWORD`, `RUSTFS_ACCESS_KEY`, `RUSTFS_SECRET_KEY` y `VAPID_SUBJECT=mailto:tu-correo@tu-dominio`. Usa secretos únicos y fuertes; deja las dos claves VAPID en blanco hasta ejecutar el generador. `APP_UID` y `APP_GID` deben ser los del propietario de los archivos de claves, no `0`.

Puedes generar valores aptos para `.env` con `openssl rand -hex 32` (una ejecución distinta para cada contraseña). No reutilices los valores de desarrollo ni publiques este archivo.

```bash
# Genera una sola vez la pareja JWT. No uses --force en un sistema en uso:
# rotarla invalidaría las sesiones existentes.
sh scripts/generate-jwt-keys.sh

# Node se ejecuta en un contenedor; escribe las claves VAPID en el .env local.
docker run --rm --user "$(id -u):$(id -g)" -v "$PWD:/app" -w /app \
  node:22-alpine node scripts/generate-vapid-keys.mjs

chmod 600 .env secrets/privateKey.pem
test -s secrets/privateKey.pem && test -s secrets/publicKey.pem
docker compose -f docker-compose.prod.yml config -q
```

Si alguna validación falla, corrige el problema antes de iniciar. No pegues la salida completa de `docker compose config` en incidencias: contiene contraseñas y claves.

## 2. Primer arranque

```bash
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs --tail=100 backend rustfs
curl -fsS -o /dev/null -w 'Orbit: %{http_code}\n' http://localhost:3000/login
curl -fsS -o /dev/null -w 'Media: %{http_code}\n' \
  http://localhost:9000/health
```

Se espera HTTP 200 en ambas comprobaciones y servicios sanos. Estas pruebas locales no demuestran TLS ni seguridad para acceso público. En la primera base vacía **no** se crean cuentas de demostración: registra usuarios desde Orbit.

El proyecto Compose se llama `orbit-prod` y crea volúmenes nuevos. Las imágenes previas de MinIO **no** se migran, por decisión de producto; referencias antiguas pueden quedar sin imagen. No se borra automáticamente su volumen.

Comprueba también desde dos navegadores que funcionan login, una imagen, mensajes WebSocket y presencia. Los avatares se sirven desde `/<bucket>/avatars/` y las imágenes de posts mediante URL firmada de corta duración; la API S3 en 9000 está publicada, aunque las escrituras requieren credenciales. La consola RustFS y Neo4j no se publican.

## 3. Operación habitual

```bash
# Estado y últimos registros
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f --tail=100 backend rustfs

# Actualizar código e imágenes sin borrar datos
git pull --ff-only
docker compose -f docker-compose.prod.yml config -q
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml ps
```

Si cambias `.env`, usa `up -d` para recrear los servicios afectados; `restart` no vuelve a leer variables. Mantén `neo4j_data` y `rustfs_data`: allí viven el grafo y las imágenes. No ejecutes `docker compose down -v`.

Antes de una actualización importante, haz un respaldo consistente de los volúmenes:

```bash
mkdir -p backups
chmod 700 backups
docker compose -f docker-compose.prod.yml stop backend neo4j rustfs
docker run --rm \
  -v orbit-prod_neo4j_data:/neo4j:ro \
  -v orbit-prod_rustfs_data:/rustfs:ro \
  -v "$PWD/backups:/backup" alpine \
  sh -c 'tar -czf "/backup/orbit-$(date +%Y%m%d-%H%M%S).tar.gz" -C / neo4j rustfs'
docker compose -f docker-compose.prod.yml up -d
```

Guarda además `.env` y `secrets/` en un respaldo **cifrado y fuera del servidor**. El archivo local de respaldo contiene datos privados: limita acceso, verifica que se puede restaurar y retíralo del servidor cuando esté protegido. La orden anterior detiene temporalmente la app para evitar un respaldo inconsistente; programa una ventana de mantenimiento.

## Decisiones de seguridad

- Esta variante publica 3000/8080/9000. Restringe esos puertos hasta contar con TLS externo o VPN.
- `APP_SEED_ENABLED=false` impide que una base vacía reciba las credenciales de demostración conocidas.
- La clave privada JWT está fuera de la imagen y montada en solo lectura. El backend corre con el UID/GID que puede leer el archivo `chmod 600`.
- Si agregas un proxy TLS externo para medios, debe conservar el host y la ruta firmados de S3.
- Mantén **una sola réplica** del backend: las conexiones de WebSocket y presencia se almacenan en memoria. Escalarlo requiere un canal compartido antes de añadir réplicas.
