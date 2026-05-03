# Install

## Prereqs

| | Version |
|---|---|
| JDK | 21 (Amazon Corretto recommended) |
| Node | 20+ |
| MariaDB | 11.4+ |
| Git | 2.x |
| Docker | optional, integration tests |

System: 2 cores / 4 GB RAM / 20 GB disk minimum (4 cores / 8 GB / 50 GB SSD recommended).

### Install dependencies

```bash
# Ubuntu / Debian
sudo apt install -y openjdk-21-jdk git build-essential
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs
curl -LsS https://r.mariadb.com/downloads/mariadb_repo_setup | sudo bash -s -- --mariadb-server-version=11.4
sudo apt install -y mariadb-server mariadb-client

# Amazon Linux 2023 / RHEL
sudo dnf install -y java-21-amazon-corretto-devel git
curl -fsSL https://rpm.nodesource.com/setup_20.x | sudo bash -
sudo dnf install -y nodejs
curl -LsS https://r.mariadb.com/downloads/mariadb_repo_setup | sudo bash -s -- --mariadb-server-version=11.4
sudo dnf install -y MariaDB-server MariaDB-client

# macOS
brew install openjdk@21 node@20 mariadb@11.4 git
```

## Quick start (dev)

```bash
git clone https://github.com/schmalle/secman.git && cd secman
sudo systemctl enable --now mariadb
cd scriptpp/install/db && ./installdb.sh && cd -    # DB 'secman', user 'secman'/'CHANGEME'
./scriptpp/startbackenddev.sh                       # canonical dev start
cd src/frontend && npm install && npm run dev       # http://localhost:4321
```

On first startup the backend logs a 20-char random admin password — **copy it immediately**:
```
==========================================================
  DEFAULT ADMIN USER CREATED
  Username: admin
  Password: <…> (CHANGE IMMEDIATELY!)
==========================================================
```
If you miss it, reset: `./scriptpp/reset_database.sh` (or `DELETE FROM user_roles; DELETE FROM users;` then restart) — a fresh password will be generated.

## Manual DB setup (alternative to `installdb.sh`)

```sql
CREATE DATABASE secman CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'secman'@'localhost' IDENTIFIED BY 'YOUR_SECURE_PASSWORD';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES
  ON secman.* TO 'secman'@'localhost';
FLUSH PRIVILEGES;
```
Then export `DB_PASSWORD=YOUR_SECURE_PASSWORD` before starting the backend.

## Configuration (env)

Required in production:

```bash
DB_CONNECT=jdbc:mariadb://localhost:3306/secman
DB_USERNAME=secman
DB_PASSWORD=...
JWT_SECRET=$(openssl rand -base64 32)              # ≥ 256 bits
SECMAN_ENCRYPTION_PASSWORD=$(openssl rand -hex 32)
SECMAN_ENCRYPTION_SALT=$(openssl rand -hex 8)      # exactly 16 hex chars
SECMAN_BACKEND_URL=https://api.example.com
FRONTEND_URL=https://secman.example.com
SECMAN_AUTH_COOKIE_SECURE=true                     # default; HTTPS only
```

Optional (SMTP, OAuth retry, memory tuning, vuln settings, debug): see `docs/ENVIRONMENT.md`. Convenience template: `cp .env.example .env`.

> The encryption password and salt MUST remain constant for the lifetime of encrypted data — rotating them orphans existing OAuth secrets and API keys.

## Production build

```bash
# Backend → fat JAR
./gradlew :backendng:shadowJar -x test
# → src/backendng/build/libs/backendng-0.1-all.jar

# Frontend → SSR bundle
cd src/frontend && npm ci --production && npm run build
# → dist/server/entry.mjs
```

## Production deploy (skeleton)

```bash
sudo useradd -r -m -d /opt/secman -s /bin/bash secman
sudo mkdir -p /etc/secman
sudo nano /etc/secman/backend.env             # paste production env
sudo chown root:secman /etc/secman/backend.env && sudo chmod 640 /etc/secman/backend.env
```

systemd unit `/etc/systemd/system/secman-backend.service`:
```ini
[Unit]
Description=Secman Backend
After=network.target mariadb.service
Requires=mariadb.service

[Service]
Type=simple
User=secman
EnvironmentFile=/etc/secman/backend.env
WorkingDirectory=/opt/secman/app
Environment="JAVA_OPTS=-Xmx2g -Xms512m -XX:+UseG1GC"
ExecStart=/usr/bin/java $JAVA_OPTS -jar src/backendng/build/libs/backendng-0.1-all.jar
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Frontend unit is analogous (`User=secman`, `ExecStart=/usr/bin/node dist/server/entry.mjs`). Enable both: `sudo systemctl enable --now secman-backend secman-frontend`.

Hardening checklist:
- [ ] Default admin password changed
- [ ] Unique `JWT_SECRET`, encryption password, salt
- [ ] HTTPS via Let's Encrypt (`sudo certbot --nginx -d ...`)
- [ ] `SECMAN_AUTH_COOKIE_SECURE=true`
- [ ] DB user limited to `localhost`
- [ ] Firewall: 80/443 only
- [ ] Daily DB backups
- [ ] SMTP configured (notifications)

Full nginx + SSL + fail2ban guide: `docs/DEPLOYMENT.md`.

## CLI build

```bash
./gradlew :cli:shadowJar
./scriptpp/secman help                              # always go through the wrapper
./scriptpp/secman query servers --dry-run
./scriptpp/secman send-notifications --dry-run
./scriptpp/secman manage-user-mappings list --send-email --dry-run
./scriptpp/secman add-vulnerability --hostname host --cve CVE-2024-1234 --criticality HIGH
```

`./scriptpp/secman` resolves secrets via `pass-cli` (Proton Pass). See `docs/PASS_CLI.md`.

## Verify

```bash
curl http://localhost:8080/health   # {"status":"UP","service":"secman-backend-ng",…}
curl http://localhost:4321/         # Login page HTML
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<paste>"}'
```

## Common issues

| Symptom | Fix |
|---|---|
| "Cannot connect to database" | `systemctl status mariadb`; verify `DB_*` env vars; `mysql -u secman -p secman` |
| Missed admin password | `./scriptpp/reset_database.sh` then restart |
| Port 8080 / 4321 in use | `lsof -i :8080`; kill or set `MICRONAUT_SERVER_PORT=8081` / edit `astro.config.mjs` |
| Gradle build fails | `java -version` (must be 21); `./gradlew clean build` |
| `npm install` fails | `npm cache clean --force`; `rm -rf src/frontend/node_modules src/frontend/package-lock.json && npm install` |
| Flyway checksum mismatch | `./gradlew flywayInfo` then `flywayRepair` |
| 5 failed logins → locked 15min | wait or restart backend |

More: `docs/TROUBLESHOOTING.md`.
