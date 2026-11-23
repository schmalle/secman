# Install

## Prereqs

| | Version |
|---|---|
| JDK | 25 (Amazon Corretto recommended) |
| Node | 22.23.4+ |
| MariaDB | 11.4+ |
| Git | 2.x |
| Docker | optional, deployment only; tests use an external MariaDB |

System: 2 cores / 4 GB RAM / 20 GB disk minimum (4 cores / 8 GB / 50 GB SSD recommended).

### Install dependencies

```bash
# Ubuntu / Debian
sudo apt install -y openjdk-25-jdk git build-essential
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt install -y nodejs
curl -LsS https://r.mariadb.com/downloads/mariadb_repo_setup | sudo bash -s -- --mariadb-server-version=11.4
sudo apt install -y mariadb-server mariadb-client

# Amazon Linux 2023 / RHEL
sudo dnf install -y java-25-amazon-corretto-devel git
curl -fsSL https://rpm.nodesource.com/setup_22.x | sudo bash -
sudo dnf install -y nodejs
curl -LsS https://r.mariadb.com/downloads/mariadb_repo_setup | sudo bash -s -- --mariadb-server-version=11.4
sudo dnf install -y MariaDB-server MariaDB-client

# macOS
brew install openjdk@25 node@22 mariadb@11.4 git
```

## Quick start (dev)

Authenticate [Proton Pass CLI](docs/PASS_CLI.md) and configure the vault references used by the start scripts. The MariaDB service command below is for systemd-based Linux.

```bash
git clone https://github.com/schmalle/secman.git && cd secman
sudo systemctl enable --now mariadb
cd scripts/install/db && ./installdb.sh && cd -    # DB 'secman', user 'secman'/'CHANGEME'
./scripts/startbackenddev.sh                       # canonical dev start
cd src/frontend && npm install && cd - && ./scripts/startfrontenddev.sh   # canonical dev start, http://localhost:4321
```

When the users table is empty, startup creates a local admin and prints a 20-character generated password to stdout:
```
==========================================================
  DEFAULT ADMIN USER CREATED
  Username: admin
  Password: <…> (CHANGE IMMEDIATELY!)
==========================================================
```
If you miss the banner, check the startup stdout captured by your service runner. Bootstrap runs only when the users table is empty. For an existing installation, use your established administrator recovery procedure.

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
cd src/frontend && npm ci --include=dev && npm run build
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
Environment="JAVA_OPTS=-Xmx2g -Xms512m -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/log/secman/oom.hprof -XX:+ExitOnOutOfMemoryError"
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
./scripts/secman help                              # always go through the wrapper
./scripts/secman query servers --dry-run
./scripts/secman send-notifications --dry-run
./scripts/secman manage-user-mappings list --send-email --dry-run
./scripts/secman add-vulnerability --hostname host --cve CVE-2024-1234 --criticality HIGH
```

`./scripts/secman` resolves secrets via `pass-cli` (Proton Pass). See `docs/PASS_CLI.md`.

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
| Missed admin password | Check first-start stdout or follow the deployment’s administrator recovery procedure. |
| Port 8080 / 4321 in use | `lsof -i :8080`; kill or set `MICRONAUT_SERVER_PORT=8081` / edit `astro.config.mjs` |
| Gradle build fails | `java -version` (must be 25); `./scripts/test/run-isolated-e2e.sh --database-only -- ./gradlew build` |
| Frontend dependency install fails | Check the Node version and npm error, then retry `npm ci` from `src/frontend` with the checked-in lockfile. |
| Flyway checksum mismatch | Identify the shipped migration from the startup error and compare it with the database history before applying a recovery procedure. |
| Too many failed logins | Wait 15 minutes before trying that username again. |

More: `docs/TROUBLESHOOTING.md`.
