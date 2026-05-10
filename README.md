# MeshConnect

Self-hosted Android device tracker and remote-admin dashboard. Phones run a foreground WebSocket service that registers with the server; the dashboard shows them as online/offline cards and can issue commands (screenshot, camera capture, file browse, vibrate, push notification, etc.).

Deployed on a single Linux VM behind nginx. HTTPS via Let's Encrypt; hostname pinned to DuckDNS.

## Stack

| Component       | What it is                                                          |
| --------------- | ------------------------------------------------------------------- |
| `server/`       | Single-file Go service (`gorilla/websocket`). Persists devices to `data/devices.json`. |
| `dashboard/`    | Single static HTML page. Polls `/api/devices` every 5s.             |
| `client-android/` | Kotlin foreground service, connects via WSS, auto-starts on boot. |
| `nginx.conf`    | TLS termination, basic-auth on `/`, proxies `/api` and `/ws`.       |
| `docker-compose.yml` | Four services: `server`, `dashboard` (nginx), `certbot`, `duckdns`. |

## Auth model

Two layers, both required to use the dashboard from a browser:

1. **Nginx HTTP Basic auth** on `location /` — gates download of the dashboard JS bundle (which contains the bearer token).
2. **Bearer token** on `/ws` and every `/api/*` route — accepted via `Authorization: Bearer <token>` or `?token=<token>` query string. The Android client sends the header; `<img>` and `<a href>` URLs in the dashboard use the query parameter (browsers can't add custom headers to those).

`/health` is the only unauthenticated route.

## Repo layout

```
.
├── server/
│   ├── main.go            Go server
│   ├── main_test.go       Tests for auth + path-traversal guards
│   ├── Dockerfile
│   ├── go.mod
│   └── .env               (gitignored) MESH_TOKEN, DUCKDNS_*, LETSENCRYPT_EMAIL
├── dashboard/
│   ├── index.html         Dashboard SPA (TOKEN const must match server/.env)
│   ├── meshconnect-client.apk   Latest APK served from the dashboard
│   └── .htpasswd          (gitignored) nginx basic-auth credentials
├── client-android/
│   └── app/src/main/java/com/meshconnect/client/
│       ├── MainActivity.kt
│       ├── WebSocketService.kt    AUTH_TOKEN const must match server/.env
│       └── BootReceiver.kt
├── certbot/                (gitignored, generated on the VM by init script)
├── data/                   (gitignored) devices.json + screenshots/files/uploads
├── docker-compose.yml
├── nginx.conf
└── init-letsencrypt.sh     One-time bootstrap for the TLS cert
```

## Secrets

These files are gitignored and **must be copied to the VM manually** the first time:

### `server/.env`

```
MESH_TOKEN=<32-byte hex>
DUCKDNS_DOMAIN=<your duckdns subdomain, no .duckdns.org>
DUCKDNS_TOKEN=<from duckdns.org account page>
LETSENCRYPT_EMAIL=<your email>
DEVICE_TTL_DAYS=30                      # 0 or unset disables auto-cleanup
```

Generate a token with:

```powershell
$bytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
-join ($bytes | ForEach-Object { $_.ToString("x2") })
```

### `dashboard/.htpasswd`

One line, format `user:{PLAIN}password`. Plaintext is fine here because the file is on a VM you control and never enters git.

```
admin:{PLAIN}<random password>
```

### Token rotation

Three places hold the bearer token. To rotate, update all of them in the same change:

1. `server/.env` → `MESH_TOKEN`
2. `client-android/.../WebSocketService.kt` → `AUTH_TOKEN`
3. `dashboard/index.html` → `TOKEN` const near the top of the `<script>` block

Then redeploy the server, restart the dashboard container, and rebuild + reinstall the APK.

## First-time deploy

1. Set up DuckDNS:
   - Sign in at https://www.duckdns.org/, create a subdomain, and point it at the VM's public IP.
   - Copy your DuckDNS token into `server/.env`.

2. Open ports on the VM:
   - Azure NSG: inbound TCP 80, 443.
   - Host firewall (if active): `sudo ufw allow 80,443/tcp`.

3. On the VM:

   ```bash
   git clone <repo> meshconnect
   cd meshconnect
   # Copy server/.env and dashboard/.htpasswd into place
   chmod +x init-letsencrypt.sh
   ./init-letsencrypt.sh
   ```

   The script writes a dummy self-signed cert so nginx can start, requests the real Let's Encrypt cert via the HTTP-01 webroot challenge, swaps it in, and brings the rest of the stack up. Re-running is safe.

4. Visit `https://<your-domain>/` — you should get the basic-auth prompt, then the dashboard.

## Subsequent deploys

```bash
git pull
docker compose up -d --build server
docker compose restart dashboard
```

`certbot` renews every 12 h; `dashboard` reloads every 6 h to pick up renewed certs. `duckdns` reasserts the IP every 5 min.

## Building the APK

```bash
cd client-android
./gradlew clean assembleDebug
adb install -r app/build-run/outputs/apk/debug/app-debug.apk
```

Output is at `app/build-run/outputs/apk/debug/app-debug.apk` (custom `buildDir = 'build-run'` in `app/build.gradle`, not the default `build/`).

After installing, on the phone:

1. Grant runtime permissions (location, camera, notifications).
2. Tap **Start Service**.
3. Grant **All files access** when redirected to settings (needed for the file browser).
4. Tap **Start Service** again.
5. Approve the screen-capture dialog.

The service auto-starts on boot via `BootReceiver`.

## Tests

```bash
cd server
go test ./...
```

Covers `isValidID`, `sanitizeFilenameHeader`, the bearer-token middleware, and the path-traversal rejection in `devicesPathHandler` / `fileServeHandler`.

## Debugging

| Symptom                                | Where to look                                                                                            |
| -------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| `nslookup` returns wrong IP            | DuckDNS update didn't run; check `docker compose logs duckdns`.                                          |
| HTTPS shows cert error                 | Cert didn't bootstrap; rerun `./init-letsencrypt.sh`. For a dry run set `STAGING=1` at the top.          |
| Phone won't connect                    | (1) APK rebuilt with current SERVER_URL/AUTH_TOKEN? (2) `MESH_TOKEN` matches in all three places?         |
| Dashboard loads but `/api/*` 401       | Token mismatch between `dashboard/index.html` and `server/.env`.                                         |
| Browser keeps reprompting basic auth   | Wrong creds; check `dashboard/.htpasswd` is mounted into the nginx container.                            |
| `docker compose up` fails on cert path | Cert volume is empty; run `init-letsencrypt.sh` first.                                                   |
