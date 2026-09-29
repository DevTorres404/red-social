<#
.SYNOPSIS
    Generate self-signed TLS certificates for local HTTPS testing

.DESCRIPTION
    Generates self-signed TLS certificates for local HTTPS testing.
    Certificates will be placed in ./tls/cert.pem and ./tls/key.pem.

.PARAMETER Domain
    Domain name for the certificate (default: localhost)

.EXAMPLE
    .\scripts\generate-tls-certs.ps1 -Domain "localhost"

.EXAMPLE
    .\scripts\generate-tls-certs.ps1 -Domain "myapp.local"
#>

param(
    [string]$Domain = "localhost"
)

$TlsDir = Join-Path (Split-Path $PSScriptRoot -Parent) "tls"

if (-not (Test-Path $TlsDir)) {
    New-Item -ItemType Directory -Path $TlsDir | Out-Null
}

Write-Host "Generating self-signed TLS certificate for $Domain..." -ForegroundColor Cyan

# Generate private key
openssl genrsa -out "$TlsDir\key.pem" 2048

# Generate certificate signing request with SAN
$SanConfig = @"
[req]
distinguished_name = req_distinguished_name
req_extensions = v3_req
prompt = no

[req_distinguished_name]
CN = $Domain

[v3_req]
subjectAltName = DNS:$Domain,DNS:localhost,IP:127.0.0.1
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
"@

$SanConfigPath = Join-Path $TlsDir "san.cnf"
$SanConfig | Set-Content -Path $SanConfigPath -NoNewline

openssl req -new -key "$TlsDir\key.pem" -out "$TlsDir\cert.csr" -config $SanConfigPath

# Generate self-signed certificate
openssl x509 -req -in "$TlsDir\cert.csr" -signkey "$TlsDir\key.pem" `
    -out "$TlsDir\cert.pem" -days 365 -extensions v3_req -extfile $SanConfigPath

# Clean up
Remove-Item "$TlsDir\cert.csr", $SanConfigPath -Force

Write-Host "Certificates generated:" -ForegroundColor Green
Write-Host "  $TlsDir\cert.pem"
Write-Host "  $TlsDir\key.pem"
Write-Host ""
Write-Host "⚠️  Self-signed certificates will show browser warnings." -ForegroundColor Yellow
Write-Host "   For production, use Let's Encrypt: certbot certonly --standalone -d yourdomain.com" -ForegroundColor Yellow
Write-Host ""
Write-Host "To use with docker-compose.prod.yml:" -ForegroundColor Cyan
Write-Host "  docker compose -f docker-compose.yml -f docker-compose.prod.yml up --build -d"