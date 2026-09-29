# Comandos para desplegar Orbit en un servidor

Esta guía usa **solo** `docker-compose.prod.yml`, con `cloudflared` instalado como servicio del **host**. No lo combines con el Compose local. Los puertos 3000, 8080 y 9000 se publican únicamente en `127.0.0.1`; los navegadores acceden por los subdominios HTTPS del túnel. No abras esos puertos en el firewall público ni apuntes registros DNS directamente al servidor.

## 1. Preparar el servidor (una vez)

Requisitos: Linux, Docker Engine con `docker compose`, OpenSSL, `cloudflared` conectado al túnel existente y un usuario no root con acceso a Docker. Cloudflare Tunnel necesita salida hacia su red; no necesita abrir entrada para la app. Mantén 3000, 8080, 9000, 9001, 7474 y 7687 cerrados desde Internet.

En el túnel existente, comprueba estas **dos rutas de aplicación publicada**:

| Hostname público | Servicio local en el host |
|---|---|
| `orbit.labtorres.me` | `http://127.0.0.1:3000` |
| `media.orbit.labtorres.me` | `http://127.0.0.1:9000` |

El frontend reenvía `/api/*` y `/ws/*` al backend dentro de Compose: no publiques un tercer hostname para 8080. En la ruta de medios no sobreescribas `HTTP Host Header`, ni reescribas la ruta o los parámetros de consulta; la firma S3 depende de ellos. Activa WebSockets en Cloudflare y crea una regla de caché **Bypass cache** para `media.orbit.labtorres.me` para que una respuesta de una URL firmada no sobreviva a su caducidad. El backend también marca las imágenes privadas `Cache-Control: private, no-store`.

```bash
git clone <URL_DEL_REPOSITORIO> orbit
cd orbit
umask 077
cp .env.prod.example .env
sed -i "s/^APP_UID=.*/APP_UID=$(id -u)/; s/^APP_GID=.*/APP_GID=$(id -g)/" .env
nano .env
```

En `.env`, deja `APP_ORIGIN=https://orbit.labtorres.me` y `RUSTFS_PUBLIC_ENDPOINT=https://media.orbit.labtorres.me`, **sin barra final**. Completa `NEO4J_PASSWORD`, `RUSTFS_ACCESS_KEY`, `RUSTFS_SECRET_KEY` y `VAPID_SUBJECT=mailto:tu-correo@tu-dominio`. Usa secretos únicos y fuertes; deja las dos claves VAPID en blanco hasta ejecutar el generador. `APP_UID` y `APP_GID` deben ser los del propietario de los archivos de claves, no `0`.

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
curl -fsS -o /dev/null -w 'Orbit HTTPS: %{http_code}\n' https://orbit.labtorres.me/login
curl -fsS -o /dev/null -w 'Media HTTPS: %{http_code}\n' https://media.orbit.labtorres.me/health
```

Se espera HTTP 200 en las cuatro comprobaciones y servicios sanos. Verifica además que `docker compose -f docker-compose.prod.yml ps` muestra `127.0.0.1:3000`, `127.0.0.1:8080` y `127.0.0.1:9000`, nunca `0.0.0.0` para esos puertos. En la primera base vacía **no** se crean cuentas de demostración: registra usuarios desde Orbit.

El proyecto Compose se llama `orbit-prod` y crea volúmenes nuevos. Las imágenes previas de MinIO **no** se migran, por decisión de producto; referencias antiguas pueden quedar sin imagen. No se borra automáticamente su volumen.

Comprueba también desde dos navegadores en `https://orbit.labtorres.me` que funcionan login, recarga de sesión, una imagen y mensajes WebSocket. La URL firmada de un post debe empezar con `https://media.orbit.labtorres.me/`, abrirse antes de 60 segundos y rechazarse después; en la respuesta, comprueba `Cache-Control: private, no-store` y que Cloudflare no la sirva desde caché. Los avatares se sirven desde `/<bucket>/avatars/`. La consola RustFS y Neo4j no se publican.

### Si las rutas públicas fallan

Si `orbit.labtorres.me` responde **502**, primero confirma que el frontend responde en el servidor y que `cloudflared` apunta exactamente a `http://127.0.0.1:3000`. Si `media.orbit.labtorres.me` falla durante el **handshake TLS** (sin respuesta HTTP), comprueba en Cloudflare que haya un certificado de borde **activo que incluya exactamente `media.orbit.labtorres.me`**. Mantén este hostname; no lo sustituyas por otro ni desactives la validación TLS como solución. Al ser un subdominio de varios niveles, no des por hecho que el certificado Universal SSL de la zona lo cubre. Después verifica que la ruta del túnel apunte a `http://127.0.0.1:9000`.

```bash
docker compose -f docker-compose.prod.yml ps
curl -fsS http://127.0.0.1:3000/login -o /dev/null -w 'Frontend local: %{http_code}\n'
curl -fsS http://127.0.0.1:9000/health -o /dev/null -w 'RustFS local: %{http_code}\n'
sudo systemctl status cloudflared --no-pager
sudo journalctl -u cloudflared -n 100 --no-pager
```

Si ambas pruebas locales dan 200 pero las públicas no, revisa las rutas publicadas, el estado del túnel y el certificado en Cloudflare; reiniciar los contenedores no corrige por sí solo un error de TLS en el borde.

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

- 3000/8080/9000 solo escuchan en loopback para `cloudflared`; el firewall no debe permitir acceso directo a ellos.
- `APP_SEED_ENABLED=false` impide que una base vacía reciba las credenciales de demostración conocidas.
- La clave privada JWT está fuera de la imagen y montada en solo lectura. El backend corre con el UID/GID que puede leer el archivo `chmod 600`.
- Cloudflare termina HTTPS; la ruta de medios debe conservar host, ruta y query firmados y omitir caché de imágenes privadas.
- Mantén **una sola réplica** del backend: las conexiones de WebSocket y presencia se almacenan en memoria. Escalarlo requiere un canal compartido antes de añadir réplicas.
