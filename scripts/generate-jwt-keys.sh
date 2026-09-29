#!/usr/bin/env sh
#
# Generate the RSA-2048 JWT keypair used to sign and verify access tokens.
#
# WHY TWO COPIES
# --------------
# The keypair is needed at two different places, so it is written to both:
#
#   secrets/{private,public}Key.pem
#       Host-side copy. docker-compose.yml bind-mounts ./secrets read-only into
#       the backend at /deployments/config, and the container reads the keys
#       from there via SMALLRYE_JWT_SIGN_KEY_LOCATION /
#       MP_JWT_VERIFY_PUBLICKEY_LOCATION.
#
#   backend/dev-keys/{private,public}Key.pem
#       Dev-only mirror for `mvn quarkus:dev` (reached via the %dev.* fallbacks).
#       Keys must NEVER sit under src/main/resources/: Maven would package them
#       into the app jar and the image, letting any holder sign valid tokens.
#
# Both locations are gitignored: the keypair is generated per-developer and
# never committed. A fresh clone has no keys until this script is run.
#
# USAGE
#   ./scripts/generate-jwt-keys.sh            # generate (refuses to clobber)
#   ./scripts/generate-jwt-keys.sh --force    # regenerate, invalidating tokens
#
# Works under Git Bash (Windows) and WSL. Requires openssl on PATH.

set -eu

FORCE=0
for arg in "$@"; do
    case "$arg" in
        --force|-f) FORCE=1 ;;
        -h|--help)
            sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "ERROR: unknown argument '$arg' (expected --force)" >&2
            exit 2
            ;;
    esac
done

# ── Locate repo root ──────────────────────────────────────────────────────────
# Script lives in <repo>/scripts/, so the root is one level up. Resolving from
# $0 (not the caller's cwd) means the script works from anywhere.
# Uses ${0%/*} instead of `dirname` so the script has no external-command
# dependency before the openssl preflight below.
case "$0" in
    */*) SCRIPT_DIR=$(CDPATH= cd -- "${0%/*}" && pwd) ;;
    *)   SCRIPT_DIR=$(pwd) ;;
esac
REPO_ROOT=$(CDPATH= cd -- "${SCRIPT_DIR}/.." && pwd)

SECRETS_DIR="${REPO_ROOT}/secrets"
DEV_KEYS_DIR="${REPO_ROOT}/backend/dev-keys"

# ── Preflight ─────────────────────────────────────────────────────────────────
if ! command -v openssl >/dev/null 2>&1; then
    echo "ERROR: 'openssl' was not found on PATH." >&2
    echo "       Install it (Git for Windows, WSL, or your OS package manager)" >&2
    echo "       and re-run this script." >&2
    exit 1
fi

mkdir -p "${SECRETS_DIR}" "${DEV_KEYS_DIR}"

# ── Refuse to silently rotate a live signing key ──────────────────────────────
# Overwriting the private key invalidates every token already issued, because
# the public key half changes with it. Make that an explicit decision.
if [ "${FORCE}" -eq 0 ]; then
    EXISTING=0
    for f in "${SECRETS_DIR}/privateKey.pem" \
             "${SECRETS_DIR}/publicKey.pem" \
             "${DEV_KEYS_DIR}/privateKey.pem" \
             "${DEV_KEYS_DIR}/publicKey.pem"; do
        [ -f "${f}" ] && EXISTING=1
    done
    if [ "${EXISTING}" -eq 1 ]; then
        echo "ERROR: a keypair already exists in secrets/ or backend/dev-keys/." >&2
        echo "       Re-run with --force to regenerate." >&2
        echo "       WARNING: regenerating invalidates all currently issued tokens." >&2
        exit 1
    fi
fi

# ── Generate ──────────────────────────────────────────────────────────────────
# genpkey emits PKCS#8 ("BEGIN PRIVATE KEY"), which is what SmallRye JWT expects.
# pkey -pubout emits X.509 SubjectPublicKeyInfo ("BEGIN PUBLIC KEY").
echo "Generating RSA-2048 keypair..."
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
    -out "${SECRETS_DIR}/privateKey.pem"
openssl pkey -in "${SECRETS_DIR}/privateKey.pem" -pubout \
    -out "${SECRETS_DIR}/publicKey.pem"

# ── Mirror into backend/dev-keys for local `quarkus:dev` ──────────────────────
cp "${SECRETS_DIR}/privateKey.pem" "${DEV_KEYS_DIR}/privateKey.pem"
cp "${SECRETS_DIR}/publicKey.pem"  "${DEV_KEYS_DIR}/publicKey.pem"

# ── Lock down the private key(s) ──────────────────────────────────────────────
# No-op on Windows filesystems under Git Bash, meaningful on Linux/WSL.
chmod 600 "${SECRETS_DIR}/privateKey.pem" 2>/dev/null || true
chmod 600 "${DEV_KEYS_DIR}/privateKey.pem" 2>/dev/null || true
chmod 644 "${SECRETS_DIR}/publicKey.pem"   2>/dev/null || true
chmod 644 "${DEV_KEYS_DIR}/publicKey.pem" 2>/dev/null || true

echo
echo "JWT keypair written to:"
echo "  ${SECRETS_DIR}/privateKey.pem"
echo "  ${SECRETS_DIR}/publicKey.pem"
echo "and mirrored to:"
echo "  ${DEV_KEYS_DIR}/privateKey.pem"
echo "  ${DEV_KEYS_DIR}/publicKey.pem"
echo
echo "If the Docker stack was already running, the bind mount caches the old"
echo "files. Re-run the backend so it picks up the new keys:"
echo
echo "  docker compose up -d --force-recreate backend"
echo
