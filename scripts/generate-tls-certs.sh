#!/bin/bash
# Generate self-signed TLS certificates for local HTTPS testing
# Usage: ./scripts/generate-tls-certs.sh [domain]
# Certificates will be placed in ./tls/cert.pem and ./tls/key.pem

set -euo pipefail

DOMAIN="${1:-localhost}"
TLS_DIR="$(dirname "$0")/../tls"

mkdir -p "$TLS_DIR"

echo "Generating self-signed TLS certificate for $DOMAIN..."

# Generate private key
openssl genrsa -out "$TLS_DIR/key.pem" 2048

# Generate certificate signing request
openssl req -new -key "$TLS_DIR/key.pem" -out "$TLS_DIR/cert.csr" \
    -subj "/CN=$DOMAIN" \
    -addext "subjectAltName=DNS:$DOMAIN,DNS:localhost,IP:127.0.0.1"

# Generate self-signed certificate (valid 365 days)
openssl x509 -req -in "$TLS_DIR/cert.csr" -signkey "$TLS_DIR/key.pem" \
    -out "$TLS_DIR/cert.pem" -days 365 \
    -extensions v3_req -extfile <(cat <<EOF
[v3_req]
subjectAltName = DNS:$DOMAIN,DNS:localhost,IP:127.0.0.1
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
EOF
)

# Clean up CSR
rm "$TLS_DIR/cert.csr"

# Set permissions (nginx runs as non-root in container)
chmod 644 "$TLS_DIR/cert.pem" "$TLS_DIR/key.pem"

echo "Certificates generated:"
echo "  $TLS_DIR/cert.pem"
echo "  $TLS_DIR/key.pem"
echo ""
echo "⚠️  Self-signed certificates will show browser warnings."
echo "   For production, use Let's Encrypt: certbot certonly --standalone -d yourdomain.com"
echo ""
echo "To use with docker-compose.prod.yml:"
echo "  docker compose -f docker-compose.yml -f docker-compose.prod.yml up --build -d"