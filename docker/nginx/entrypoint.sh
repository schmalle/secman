#!/bin/sh
# Generate a self-signed TLS certificate for demo/development use.
# The certificate is created only if it does not already exist, so
# bringing your own cert is as simple as mounting it into the volume.

CERT_DIR="/etc/nginx/certs"
CERT_FILE="$CERT_DIR/selfsigned.crt"
KEY_FILE="$CERT_DIR/selfsigned.key"

mkdir -p "$CERT_DIR"

if [ ! -f "$CERT_FILE" ] || [ ! -f "$KEY_FILE" ]; then
  echo "Generating self-signed TLS certificate …"
  openssl req -x509 -nodes -days 365 \
    -newkey rsa:2048 \
    -keyout "$KEY_FILE" \
    -out "$CERT_FILE" \
    -subj "/CN=localhost/O=secman-demo"
  echo "Certificate created at $CERT_FILE"
else
  echo "TLS certificate already exists, skipping generation."
fi

# Hand off to the default nginx entrypoint
exec nginx -g "daemon off;"
