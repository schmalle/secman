# Deployment

Linux production. Backend (`:8080`) + Frontend (`:4321`) behind nginx with SSL termination, MariaDB on `:3306`. Stack: Kotlin 2.4.0/Java 21/Micronaut 4.10 + Astro 6.3/React 19/Node 20 + MariaDB 11.4. Tested on Amazon Linux 2023, Ubuntu 20.04+, RHEL 8+.

System: 2c/4G/20G minimum, 4c+/8G+/50G+ SSD recommended. Outbound 443 needed for SMTP/CrowdStrike. Inbound 80/443.

## Install dependencies

```bash
# Amazon Linux / RHEL
sudo dnf install -y java-21-amazon-corretto-devel git nginx
curl -fsSL https://rpm.nodesource.com/setup_20.x | sudo bash -
sudo dnf install -y nodejs

# Ubuntu / Debian
sudo apt install -y openjdk-21-jdk git build-essential nginx
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs

# MariaDB 11.4 (both)
curl -LsS https://r.mariadb.com/downloads/mariadb_repo_setup | sudo bash -s -- --mariadb-server-version=11.4
# then dnf/apt install MariaDB-server MariaDB-client (or mariadb-server mariadb-client)

# user
sudo useradd -r -m -d /opt/secman -s /bin/bash secman
```

Verify: `java -version` → 21, `node -v` → 20, `nginx -v` → ≥ 1.18.

## Database

```bash
sudo systemctl enable --now mariadb
sudo mysql_secure_installation
sudo mysql -u root -p
```

```sql
CREATE DATABASE secman CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'secman'@'localhost' IDENTIFIED BY 'YOUR_SECURE_PASSWORD';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,DROP,INDEX,REFERENCES ON secman.* TO 'secman'@'localhost';
FLUSH PRIVILEGES;
```

Tables auto-migrate via Flyway + Hibernate on first start.

## Build

```bash
sudo su - secman
git clone https://github.com/schmalle/secman.git /opt/secman/app && cd /opt/secman/app

./gradlew :backendng:shadowJar -x test           # → src/backendng/build/libs/backendng-0.1-all.jar
cd src/frontend && npm ci --production && npm run build  # → dist/server/entry.mjs
```

## Configure

```bash
sudo install -d -o root -g secman -m 750 /etc/secman
sudo nano /etc/secman/backend.env
sudo chown root:secman /etc/secman/backend.env && sudo chmod 640 /etc/secman/backend.env
```

`/etc/secman/backend.env` (minimum production):
```bash
DB_CONNECT=jdbc:mariadb://localhost:3306/secman
DB_USERNAME=secman
DB_PASSWORD=YOUR_SECURE_PASSWORD

# generate: openssl rand -base64 32 / -hex 32 / -hex 8
JWT_SECRET=...
SECMAN_ENCRYPTION_PASSWORD=...
SECMAN_ENCRYPTION_SALT=...

SMTP_HOST=smtp.example.com
SMTP_PORT=587
SMTP_USERNAME=noreply@example.com
SMTP_PASSWORD=...
SMTP_FROM_ADDRESS=noreply@example.com
SMTP_FROM_NAME=Security Management System
SMTP_ENABLE_TLS=true

SECMAN_BACKEND_URL=https://api.example.com
FRONTEND_URL=https://secman.example.com
SECMAN_AUTH_COOKIE_SECURE=true
```

`/etc/secman/frontend.env`:
```bash
PUBLIC_API_URL=          # empty = same-domain via nginx
NODE_ENV=production
HOST=127.0.0.1
PORT=4321
```

Full env reference: `docs/ENVIRONMENT.md`.

## systemd

`/etc/systemd/system/secman-backend.service`:
```ini
[Unit]
Description=Secman Backend API
After=network.target mariadb.service
Requires=mariadb.service

[Service]
Type=simple
User=secman
Group=secman
WorkingDirectory=/opt/secman/app
EnvironmentFile=/etc/secman/backend.env
Environment="JAVA_OPTS=-Xmx2g -Xms512m -XX:+UseG1GC"
ExecStart=/usr/bin/java $JAVA_OPTS -jar /opt/secman/app/src/backendng/build/libs/backendng-0.1-all.jar
Restart=on-failure
RestartSec=10
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/opt/secman/app/logs
StandardOutput=journal
StandardError=journal
SyslogIdentifier=secman-backend

[Install]
WantedBy=multi-user.target
```

`/etc/systemd/system/secman-frontend.service`:
```ini
[Unit]
Description=Secman Frontend
After=network.target secman-backend.service
Wants=secman-backend.service

[Service]
Type=simple
User=secman
Group=secman
WorkingDirectory=/opt/secman/app/src/frontend
EnvironmentFile=/etc/secman/frontend.env
Environment="NODE_OPTIONS=--max-old-space-size=1024"
ExecStart=/usr/bin/node /opt/secman/app/src/frontend/dist/server/entry.mjs
Restart=on-failure
RestartSec=10
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now secman-backend secman-frontend
```

## nginx

`/etc/nginx/conf.d/secman.conf`:
```nginx
upstream secman_backend  { server 127.0.0.1:8080 fail_timeout=0; }
upstream secman_frontend { server 127.0.0.1:4321 fail_timeout=0; }

server {                                     # HTTP → HTTPS
    listen 80; listen [::]:80;
    server_name api.example.com secman.example.com;
    location /.well-known/acme-challenge/ { root /var/www/certbot; }
    location / { return 301 https://$host$request_uri; }
}

server {                                     # API
    listen 443 ssl http2; listen [::]:443 ssl http2;
    server_name api.example.com;
    ssl_certificate     /etc/letsencrypt/live/api.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/api.example.com/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers 'ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384';
    ssl_prefer_server_ciphers on;
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;
    client_max_body_size 20M;
    location / {
        proxy_pass http://secman_backend;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host  $host;
        proxy_set_header X-Forwarded-Port  $server_port;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_buffering off;
    }
}

server {                                     # Frontend
    listen 443 ssl http2; listen [::]:443 ssl http2;
    server_name secman.example.com;
    ssl_certificate     /etc/letsencrypt/live/secman.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/secman.example.com/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    location / {
        proxy_pass http://secman_frontend;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
    location ~* \.(js|css|png|jpe?g|gif|ico|svg|woff2?)$ {
        proxy_pass http://secman_frontend;
        expires 1y; add_header Cache-Control "public, immutable"; access_log off;
    }
}
```

`sudo nginx -t && sudo systemctl reload nginx`.

## SSL

```bash
sudo dnf install -y certbot python3-certbot-nginx        # or apt
sudo certbot --nginx -d api.example.com -d secman.example.com
sudo certbot renew --dry-run
```

Self-signed for dev:
```bash
sudo openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout /etc/nginx/ssl/secman.key -out /etc/nginx/ssl/secman.crt -subj "/CN=secman.local"
```

## Hardening

```bash
# firewall
sudo firewall-cmd --permanent --add-service={http,https,ssh} && sudo firewall-cmd --reload    # RHEL
sudo ufw allow 22,80,443/tcp && sudo ufw enable                                                # Ubuntu

# perms
sudo chown -R secman:secman /opt/secman/app && sudo chmod -R 750 /opt/secman/app
sudo chmod 600 /etc/secman/*.env /etc/nginx/ssl/*.key

# fail2ban (basic)
sudo dnf install -y fail2ban   # or apt
sudo tee /etc/fail2ban/filter.d/secman.conf >/dev/null <<'EOF'
[Definition]
failregex = ^<HOST> .* "POST /api/auth/login HTTP.*" 401
EOF
sudo tee /etc/fail2ban/jail.d/secman.conf >/dev/null <<'EOF'
[secman]
enabled  = true
port     = http,https
filter   = secman
logpath  = /var/log/nginx/secman-api-access.log
maxretry = 5
bantime  = 3600
findtime = 600
EOF
sudo systemctl enable --now fail2ban
```

Required: change default admin password; unique `JWT_SECRET`/`SECMAN_ENCRYPTION_*`; `SECMAN_AUTH_COOKIE_SECURE=true`; DB user limited to `localhost`; DB backup; SMTP configured.

## Monitor

```bash
curl http://localhost:8080/health      # {"status":"UP",...}
curl http://localhost:4321/
sudo journalctl -u secman-backend  -f
sudo journalctl -u secman-frontend -f
sudo tail -f /var/log/nginx/secman-api-{access,error}.log
```

Watchdog `/opt/secman/bin/monitor.sh`:
```bash
#!/usr/bin/env bash
curl -sf http://localhost:8080/health >/dev/null || { logger -t secman-monitor "backend down";  systemctl restart secman-backend;  }
curl -sf http://localhost:4321/        >/dev/null || { logger -t secman-monitor "frontend down"; systemctl restart secman-frontend; }
```
`*/5 * * * * /opt/secman/bin/monitor.sh`

CrowdStrike checkin freshness alert (Telegram, stdlib-only Python):
```cron
*/10 * * * * TELEGRAM_BOT_TOKEN=… TELEGRAM_CHAT_ID=… \
  /opt/secman/src/clinotify/check_crowdstrike_checkin.py \
  --url https://secman.example.com --max-age-minutes 120 \
  >> /var/log/secman-checkin.log 2>&1
```
Public endpoint: `GET /api/crowdstrike/last-checkin` (text/plain, ISO-8601 or `never`).

## Updates & backups

```bash
# update
sudo systemctl stop secman-frontend secman-backend
sudo -u secman bash -lc 'cd /opt/secman/app && git pull origin main \
  && ./gradlew :backendng:shadowJar -x test \
  && cd src/frontend && npm ci --production && npm run build'
sudo systemctl start secman-backend secman-frontend

# backup (daily)
mkdir -p /opt/secman/backups
mysqldump -u secman -p secman | gzip > "/opt/secman/backups/secman_$(date +%Y%m%d_%H%M%S).sql.gz"
find /opt/secman/backups -name '*.sql.gz' -mtime +7 -delete
```

## Quick troubleshoot

| Symptom | Action |
|---|---|
| Backend won't start | `journalctl -u secman-backend -n 100`; `java -version`; `mysql -u secman -p secman`; check JAR present |
| Frontend won't start | `journalctl -u secman-frontend -n 100`; `node -v`; check `dist/server/entry.mjs`; backend reachable |
| 502 Bad Gateway | services running? `curl http://localhost:8080/health` and `:4321/`; check nginx error log |
| SSL issues | `ls /etc/letsencrypt/live/`; `openssl x509 -in <cert> -noout -dates`; `certbot renew --force-renewal` |

Deeper guide: `docs/TROUBLESHOOTING.md`.
