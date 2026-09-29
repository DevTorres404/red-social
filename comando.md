# Comandos para desplegar Orbit en un servidor

Esta guía usa **solo** `docker-compose.prod.yml`. No se combina con el Compose local: hacerlo volvería a publicar los puertos internos. El servidor expone únicamente HTTP 80 y HTTPS 443; Caddy obtiene y renueva certificados TLS para la app y el servicio de imágenes.

## 1. Preparar el servidor (una vez)

Requisitos: Linux, Docker Engine con `docker compose`, OpenSSL, un usuario no root con acceso a Docker y dos nombres DNS que apunten a la IP pública. Abre los puertos TCP 80 y 443 en el firewall/proveedor. No publiques 3000, 8080, 9000, 9001, 7474 ni 7687.

Los ejemplos usan `orbit.example.com` y `media.orbit.example.com`; reemplázalos por tus dominios reales. Usa dominios públicos, no una IP ni `localhost`, para que TLS automático funcione.

```bash
git clone <URL_DEL_REPOSITORIO> orbit
cd orbit
umask 077
cp .env.prod.example .env
sed -i "s/^APP_UID=.*/APP_UID=$(id -u)/; s/^APP_GID=.*/APP_GID=$(id -g)/" .env
nano .env
```

En `.env`, completa `APP_DOMAIN`, `MEDIA_DOMAIN`, `NEO4J_PASSWORD`, `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD` y `VAPID_SUBJECT=mailto:tu-correo@tu-dominio`. Usa secretos únicos y fuertes; deja las dos claves VAPID en blanco hasta ejecutar el generador. `APP_UID` y `APP_GID` deben ser los del propietario de los archivos de claves, no `0`.

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
docker compose -f docker-compose.prod.yml run --rm --no-deps \
  --entrypoint caddy proxy validate --config /etc/caddy/Caddyfile
```

Si alguna validación falla, corrige el problema antes de iniciar. No pegues la salida completa de `docker compose config` en incidencias: contiene contraseñas y claves.

## 2. Primer arranque

```bash
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs --tail=100 backend proxy
curl -fsS -o /dev/null -w 'Orbit: %{http_code}\n' https://orbit.example.com/login
curl -fsS -o /dev/null -w 'Media: %{http_code}\n' \
  https://media.orbit.example.com/minio/health/live
```

Se espera HTTP 200 en ambas comprobaciones, servicios sanos y certificados válidos. Caddy necesita que ambos DNS y los puertos 80/443 sean accesibles públicamente para emitirlos. En la primera base vacía **no** se crean cuentas de demostración: registra usuarios desde Orbit.

El proyecto Compose se llama `orbit-prod` y crea volúmenes nuevos. Si el servidor ya tenía datos de otro proyecto Compose, **no** se migran automáticamente: haz un respaldo y planifica la restauración antes de cambiar tráfico.

Comprueba también desde dos navegadores que funcionan login, una imagen, mensajes WebSocket y presencia. El proxy de media permite solo lectura pública: los avatares se sirven desde `/<bucket>/avatars/` y las imágenes de posts mediante URL firmada de corta duración. La consola de MinIO y Neo4j no se exponen a Internet.

## 3. Operación habitual

```bash
# Estado y últimos registros
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f --tail=100 backend proxy

# Actualizar código e imágenes sin borrar datos
git pull --ff-only
docker compose -f docker-compose.prod.yml config -q
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml ps
```

Si cambias `.env`, usa `up -d` para recrear los servicios afectados; `restart` no vuelve a leer variables. Mantén `caddy_data`, `neo4j_data` y `minio_data`: allí viven los certificados, el grafo y las imágenes. No ejecutes `docker compose down -v`.

Antes de una actualización importante, haz un respaldo consistente de los volúmenes:

```bash
mkdir -p backups
chmod 700 backups
docker compose -f docker-compose.prod.yml stop backend neo4j minio
docker run --rm \
  -v orbit-prod_neo4j_data:/neo4j:ro \
  -v orbit-prod_minio_data:/minio:ro \
  -v "$PWD/backups:/backup" alpine \
  sh -c 'tar -czf "/backup/orbit-$(date +%Y%m%d-%H%M%S).tar.gz" -C / neo4j minio'
docker compose -f docker-compose.prod.yml up -d
```

Guarda además `.env`, `secrets/` y el volumen `orbit-prod_caddy_data` en un respaldo **cifrado y fuera del servidor**. El archivo local de respaldo contiene datos privados: limita acceso, verifica que se puede restaurar y retíralo del servidor cuando esté protegido. La orden anterior detiene temporalmente la app para evitar un respaldo inconsistente; programa una ventana de mantenimiento.

## Decisiones de seguridad

- Solo `proxy` publica puertos; base, almacenamiento, backend y frontend están en la red privada de Compose.
- `APP_SEED_ENABLED=false` impide que una base vacía reciba las credenciales de demostración conocidas.
- La clave privada JWT está fuera de la imagen y montada en solo lectura. El backend corre con el UID/GID que puede leer el archivo `chmod 600`.
- Caddy conserva el host y la ruta del subdominio de media, necesarios para validar las URLs firmadas de S3, y gestiona TLS automáticamente.
- Mantén **una sola réplica** del backend: las conexiones de WebSocket y presencia se almacenan en memoria. Escalarlo requiere un canal compartido antes de añadir réplicas.
