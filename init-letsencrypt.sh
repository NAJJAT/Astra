#!/usr/bin/env bash
# Bootstrap a Let's Encrypt cert for the dashboard.
# Run once on the VM. Re-running is safe but unnecessary; renewal is automatic
# afterwards via the certbot service in docker-compose.
set -euo pipefail

DOMAIN="meshconnect.duckdns.org"
EMAIL="ooo337377@gmail.com"
DATA_PATH="./certbot"
STAGING=0  # set to 1 to test against Let's Encrypt staging

if ! [ -x "$(command -v docker-compose)" ] && ! docker compose version >/dev/null 2>&1; then
    echo "ERROR: docker-compose (or 'docker compose') is required." >&2
    exit 1
fi

# Pick the available compose CLI.
if docker compose version >/dev/null 2>&1; then
    COMPOSE="docker compose"
else
    COMPOSE="docker-compose"
fi

echo "### Creating dummy certificate for $DOMAIN ..."
mkdir -p "$DATA_PATH/conf/live/$DOMAIN"
mkdir -p "$DATA_PATH/www"
$COMPOSE run --rm --entrypoint "\
  openssl req -x509 -nodes -newkey rsa:2048 -days 1 \
    -keyout '/etc/letsencrypt/live/$DOMAIN/privkey.pem' \
    -out    '/etc/letsencrypt/live/$DOMAIN/fullchain.pem' \
    -subj '/CN=localhost'" certbot

echo "### Starting nginx ..."
$COMPOSE up --force-recreate -d dashboard

echo "### Deleting dummy certificate ..."
$COMPOSE run --rm --entrypoint "\
  rm -rf /etc/letsencrypt/live/$DOMAIN && \
  rm -rf /etc/letsencrypt/archive/$DOMAIN && \
  rm -rf /etc/letsencrypt/renewal/$DOMAIN.conf" certbot

echo "### Requesting Let's Encrypt certificate for $DOMAIN ..."
STAGING_ARG=""
if [ $STAGING -ne 0 ]; then STAGING_ARG="--staging"; fi
$COMPOSE run --rm --entrypoint "\
  certbot certonly --webroot -w /var/www/certbot \
    $STAGING_ARG \
    --email $EMAIL \
    -d $DOMAIN \
    --rsa-key-size 2048 \
    --agree-tos \
    --non-interactive \
    --force-renewal" certbot

echo "### Reloading nginx ..."
$COMPOSE exec dashboard nginx -s reload

echo "### Bringing up the rest of the stack ..."
$COMPOSE up -d

echo "### Done. https://$DOMAIN/ should now be live."
