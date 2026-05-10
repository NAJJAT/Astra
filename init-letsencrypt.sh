#!/usr/bin/env bash
# Bootstrap a Let's Encrypt cert for the dashboard.
# Run once on the VM. Re-running is safe.
set -euo pipefail

DOMAIN="meshconnect.duckdns.org"
EMAIL="ooo337377@gmail.com"
DATA_PATH="./certbot"
STAGING=0  # set to 1 to test against Let's Encrypt staging (no rate limit hit)

red()  { printf '\033[0;31m%s\033[0m\n' "$*"; }
grn()  { printf '\033[0;32m%s\033[0m\n' "$*"; }
hdr()  { printf '\n\033[1;34m### %s\033[0m\n' "$*"; }
fail() { red "ERROR: $*"; exit 1; }

# ---------------------- pre-flight ----------------------
hdr "Pre-flight checks"

# Must be in the project dir.
[ -f docker-compose.yml ] || fail "Run this from the meshconnect repo root (no docker-compose.yml here)."

# .htpasswd must be a regular file. Docker silently creates a directory if the
# bind-mount source doesn't exist, which causes nginx to 500 with "Is a directory".
if [ ! -e dashboard/.htpasswd ] || [ -d dashboard/.htpasswd ]; then
    red "dashboard/.htpasswd is missing or is a directory."
    red "Fix:"
    red "    sudo rm -rf dashboard/.htpasswd"
    red "    printf 'admin:{PLAIN}<your-password>\\n' > dashboard/.htpasswd"
    red "    chmod 644 dashboard/.htpasswd"
    exit 1
fi
echo "  [OK] dashboard/.htpasswd is a file"

# server/.env must exist and have MESH_TOKEN.
[ -f server/.env ] || fail "server/.env is missing. Create it with at least MESH_TOKEN=<hex>."
grep -q '^MESH_TOKEN=' server/.env || fail "MESH_TOKEN not set in server/.env."
echo "  [OK] server/.env has MESH_TOKEN"

# Compose CLI.
if docker compose version >/dev/null 2>&1; then
    COMPOSE="docker compose"
elif [ -x "$(command -v docker-compose)" ]; then
    COMPOSE="docker-compose"
else
    fail "docker compose (v2) or docker-compose (v1) is required."
fi
echo "  [OK] using: $COMPOSE"

# ---------------------- tear down ----------------------
hdr "Stopping any running containers"
$COMPOSE down 2>/dev/null || true

# ---------------------- dummy cert ----------------------
hdr "Creating dummy certificate (so nginx can start)"
mkdir -p "$DATA_PATH/conf/live/$DOMAIN" "$DATA_PATH/www"
$COMPOSE run --rm --entrypoint "\
  openssl req -x509 -nodes -newkey rsa:2048 -days 1 \
    -keyout '/etc/letsencrypt/live/$DOMAIN/privkey.pem' \
    -out    '/etc/letsencrypt/live/$DOMAIN/fullchain.pem' \
    -subj '/CN=localhost'" certbot

# ---------------------- start nginx ----------------------
hdr "Starting nginx"
$COMPOSE up --force-recreate -d dashboard

echo "  waiting for nginx to be ready..."
for i in $(seq 1 10); do
    if curl -fsS "http://$DOMAIN/.well-known/acme-challenge/" >/dev/null 2>&1 || \
       curl -fsS -o /dev/null -w '%{http_code}' "http://$DOMAIN/" 2>/dev/null | grep -qE '^(200|301|404)$'; then
        break
    fi
    sleep 1
done

# ---------------------- ACME reachability check ----------------------
hdr "Verifying webroot reachability from outside"
PROBE_FILE="$DATA_PATH/www/.well-known/acme-challenge/probe-$$"
mkdir -p "$DATA_PATH/www/.well-known/acme-challenge"
echo "ok" > "$PROBE_FILE"
chmod 644 "$PROBE_FILE"
PROBE_NAME=$(basename "$PROBE_FILE")
PROBE_URL="http://$DOMAIN/.well-known/acme-challenge/$PROBE_NAME"
PROBE_RESULT=$(curl -fsS "$PROBE_URL" 2>/dev/null || echo "")
rm -f "$PROBE_FILE"

if [ "$PROBE_RESULT" = "ok" ]; then
    grn "  [OK] Let's Encrypt will be able to reach the webroot."
else
    red "  [WARN] Could not fetch $PROBE_URL"
    red "         Got: '$PROBE_RESULT'"
    red "         Possible causes:"
    red "           - port 80 not open in Azure NSG / Rocky firewalld"
    red "           - DNS not pointing at this VM (check: nslookup $DOMAIN)"
    red "           - SELinux blocking nginx from reading the bind mount (uses :z label)"
    red "         Continuing anyway — certbot will fail with a clearer message."
fi

# ---------------------- request real cert ----------------------
hdr "Deleting dummy certificate from disk"
$COMPOSE run --rm --entrypoint "\
  rm -rf /etc/letsencrypt/live/$DOMAIN \
         /etc/letsencrypt/archive/$DOMAIN \
         /etc/letsencrypt/renewal/$DOMAIN.conf" certbot

hdr "Requesting Let's Encrypt certificate for $DOMAIN"
STAGING_ARG=""
[ $STAGING -ne 0 ] && STAGING_ARG="--staging"
set +e
$COMPOSE run --rm --entrypoint "\
  certbot certonly --webroot -w /var/www/certbot \
    $STAGING_ARG \
    --email $EMAIL \
    -d $DOMAIN \
    --rsa-key-size 2048 \
    --agree-tos \
    --non-interactive" certbot
CERTBOT_RC=$?
set -e

if [ $CERTBOT_RC -ne 0 ]; then
    red "certbot failed (exit $CERTBOT_RC). nginx is still running with the dummy cert."
    red "Check the certbot output above for the specific reason. Common causes:"
    red "  - SELinux blocking shared volume access (we use :z, but verify with"
    red "    'getenforce' and 'docker compose logs dashboard | grep denied')"
    red "  - Port 80 unreachable from the public internet (test with"
    red "    'curl http://$DOMAIN/.well-known/acme-challenge/test' from another machine)"
    red "  - Rate limit hit. Set STAGING=1 at the top of this script and re-run."
    exit 1
fi

# ---------------------- verify ----------------------
hdr "Verifying issued certificate"
CERT_FILE="$DATA_PATH/conf/live/$DOMAIN/fullchain.pem"
[ -f "$CERT_FILE" ] || fail "certbot exited 0 but $CERT_FILE is missing."
ISSUER=$(openssl x509 -in "$CERT_FILE" -noout -issuer 2>/dev/null || true)
echo "  Issuer: $ISSUER"
if echo "$ISSUER" | grep -qi "let's encrypt"; then
    grn "  [OK] Real Let's Encrypt cert installed."
elif [ $STAGING -ne 0 ] && echo "$ISSUER" | grep -qi "fake\|staging\|stg"; then
    grn "  [OK] Staging cert installed (STAGING=1)."
else
    fail "Issuer doesn't look like Let's Encrypt: $ISSUER"
fi

# ---------------------- reload + finish ----------------------
hdr "Reloading nginx with new cert"
$COMPOSE exec dashboard nginx -s reload

hdr "Bringing up the rest of the stack"
$COMPOSE up -d

echo
grn "### Done. https://$DOMAIN/ should now be live."
echo
echo "Quick smoke tests (run on any machine):"
echo "  curl https://$DOMAIN/health         # expect: {\"status\":\"ok\"}"
echo "  curl -I https://$DOMAIN/             # expect: 401 (basic auth required)"
