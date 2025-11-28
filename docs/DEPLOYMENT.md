# Secman Deployment Guide

**Last Updated:** 2025-11-26
**Version:** 2.0
**Platforms:** Amazon Linux 2023, Ubuntu 20.04+, RHEL 8+

Complete production deployment guide for the Secman security management application.

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Prerequisites](#prerequisites)
3. [System Preparation](#system-preparation)
4. [Database Setup](#database-setup)
5. [Backend Deployment](#backend-deployment)
6. [Frontend Deployment](#frontend-deployment)
7. [Nginx Reverse Proxy](#nginx-reverse-proxy)
8. [SSL/TLS Setup](#ssltls-setup)
9. [Systemd Services](#systemd-services)
10. [Security Hardening](#security-hardening)
11. [Monitoring](#monitoring)
12. [Maintenance](#maintenance)
13. [Troubleshooting](#troubleshooting)

---

## Architecture Overview

```
                              Internet
                                  |
                          [Nginx :80/:443]
                                  |
          +-------------------+---+-------------------+
          |                   |                       |
     /api/*              /oauth/*                    /*
          |                   |                       |
          v                   v                       v
  [Backend :8080]     [Backend :8080]       [Frontend :4321]
  Kotlin/Micronaut    OAuth callbacks       Astro/React SSR
          |                                         |
          +-----------------------------------------+
                              |
                      [MariaDB :3306]
```

**Components:**
- **Backend**: Kotlin 2.2.21, Java 21, Micronaut 4.10
- **Frontend**: Astro 5.15, React 19, Node.js 20.x
- **Database**: MariaDB 12
- **Reverse Proxy**: Nginx with SSL termination

---

## Prerequisites

### System Requirements

| Resource | Minimum | Recommended |
|----------|---------|-------------|
| CPU | 2 cores | 4+ cores |
| RAM | 4 GB | 8+ GB |
| Disk | 20 GB | 50+ GB SSD |
| OS | Amazon Linux 2023 / Ubuntu 20.04+ / RHEL 8+ | Latest LTS |

### Network Requirements

- Ports 80 and 443 accessible from internet
- Firewall rules allowing nginx traffic
- Domain name with DNS configured
- Outbound HTTPS (443) for email/CrowdStrike API

---

## System Preparation

### Install Dependencies

**Amazon Linux 2023:**
```bash
sudo dnf update -y
sudo dnf groupinstall "Development Tools" -y
sudo dnf install -y java-21-amazon-corretto-devel git

# Node.js 20.x
curl -fsSL https://rpm.nodesource.com/setup_20.x | sudo bash -
sudo dnf install -y nodejs

# Nginx
sudo dnf install -y nginx
```

**Ubuntu/Debian:**
```bash
sudo apt update
sudo apt install -y openjdk-21-jdk git build-essential

# Node.js 20.x
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs

# Nginx
sudo apt install -y nginx
```

### Verify Installations

```bash
java -version      # Should show Java 21
node -v            # Should show v20.x
npm -v             # Should show v10.x
nginx -v           # Should show nginx/1.18+
```

### Create Application User

```bash
sudo useradd -r -m -d /opt/secman -s /bin/bash secman
sudo passwd secman  # Set strong password
```

---

## Database Setup

### Install MariaDB

**Amazon Linux / RHEL:**
```bash
sudo curl -sS https://downloads.mariadb.com/MariaDB/mariadb_repo_setup | sudo bash -s -- --mariadb-server-version="mariadb-10.11"
sudo dnf install -y MariaDB-server MariaDB-client
```

**Ubuntu/Debian:**
```bash
curl -LsS https://r.mariadb.com/downloads/mariadb_repo_setup | sudo bash -s -- --mariadb-server-version=12.0
sudo apt update && sudo apt install -y mariadb-server mariadb-client
```

### Configure MariaDB

```bash
sudo systemctl start mariadb
sudo systemctl enable mariadb
sudo mysql_secure_installation
```

### Create Database

```bash
sudo mysql -u root -p
```

```sql
CREATE DATABASE secman CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'secman'@'localhost' IDENTIFIED BY 'YOUR_SECURE_PASSWORD';
GRANT ALL PRIVILEGES ON secman.* TO 'secman'@'localhost';
FLUSH PRIVILEGES;
EXIT;
```

---

## Backend Deployment

### Clone Repository

```bash
sudo su - secman
git clone https://github.com/schmalle/secman.git /opt/secman/app
cd /opt/secman/app
git checkout main
```

### Configure Environment

Create `/etc/secman/backend.env`:

```bash
sudo mkdir -p /etc/secman
sudo nano /etc/secman/backend.env
```

```bash
# Database
DB_USERNAME=secman
DB_PASSWORD=YOUR_SECURE_PASSWORD

# Security - GENERATE THESE!
JWT_SECRET=REPLACE_WITH_GENERATED_SECRET
SECMAN_ENCRYPTION_PASSWORD=REPLACE_WITH_GENERATED_PASSWORD
SECMAN_ENCRYPTION_SALT=REPLACE_WITH_GENERATED_SALT

# Email
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=noreply@yourdomain.com
SMTP_PASSWORD=YOUR_SMTP_PASSWORD
SMTP_FROM_ADDRESS=noreply@yourdomain.com
SMTP_FROM_NAME=Security Management System
SMTP_ENABLE_TLS=true

# URLs
BACKEND_BASE_URL=https://api.yourdomain.com
FRONTEND_URL=https://secman.yourdomain.com
```

Generate secrets:
```bash
# JWT Secret (32 bytes)
openssl rand -base64 32

# Encryption Password
openssl rand -hex 32

# Encryption Salt (8 bytes = 16 hex chars)
openssl rand -hex 8
```

Secure the file:
```bash
sudo chown root:secman /etc/secman/backend.env
sudo chmod 640 /etc/secman/backend.env
```

### Build Backend

```bash
cd /opt/secman/app
./gradlew :src:backendng:shadowJar -x test
# Output: src/backendng/build/libs/backendng-0.1-all.jar
```

---

## Frontend Deployment

### Configure Environment

Create `/etc/secman/frontend.env`:

```bash
PUBLIC_API_URL=
NODE_ENV=production
HOST=127.0.0.1
PORT=4321
```

### Build Frontend

```bash
cd /opt/secman/app/src/frontend
npm ci --production
npm run build
# Output: dist/server/entry.mjs
```

---

## Nginx Reverse Proxy

### Configuration

Create `/etc/nginx/conf.d/secman.conf`:

```nginx
upstream secman_backend {
    server 127.0.0.1:8080 fail_timeout=0;
}

upstream secman_frontend {
    server 127.0.0.1:4321 fail_timeout=0;
}

# HTTP redirect
server {
    listen 80;
    listen [::]:80;
    server_name api.yourdomain.com secman.yourdomain.com;

    location /.well-known/acme-challenge/ {
        root /var/www/certbot;
    }

    location / {
        return 301 https://$host$request_uri;
    }
}

# API Server (HTTPS)
server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name api.yourdomain.com;

    ssl_certificate /etc/letsencrypt/live/api.yourdomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/api.yourdomain.com/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers 'ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384';
    ssl_prefer_server_ciphers on;

    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;

    access_log /var/log/nginx/secman-api-access.log;
    error_log /var/log/nginx/secman-api-error.log;

    client_max_body_size 20M;

    location / {
        proxy_pass http://secman_backend;
        proxy_http_version 1.1;

        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-Port $server_port;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";

        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
        proxy_buffering off;
    }
}

# Frontend Server (HTTPS)
server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name secman.yourdomain.com;

    ssl_certificate /etc/letsencrypt/live/secman.yourdomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/secman.yourdomain.com/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers 'ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384';
    ssl_prefer_server_ciphers on;

    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;

    access_log /var/log/nginx/secman-frontend-access.log;
    error_log /var/log/nginx/secman-frontend-error.log;

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

    location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2)$ {
        proxy_pass http://secman_frontend;
        expires 1y;
        add_header Cache-Control "public, immutable";
        access_log off;
    }
}
```

### Enable and Test

```bash
# Test configuration
sudo nginx -t

# Enable site (Ubuntu)
sudo ln -s /etc/nginx/sites-available/secman /etc/nginx/sites-enabled/

# Reload
sudo systemctl reload nginx
```

---

## SSL/TLS Setup

### Let's Encrypt (Recommended)

```bash
# Install certbot
sudo dnf install -y certbot python3-certbot-nginx  # Amazon Linux/RHEL
sudo apt install -y certbot python3-certbot-nginx  # Ubuntu

# Obtain certificates
sudo certbot --nginx -d api.yourdomain.com -d secman.yourdomain.com

# Test renewal
sudo certbot renew --dry-run
```

### Self-Signed (Development)

```bash
sudo mkdir -p /etc/nginx/ssl

sudo openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
    -keyout /etc/nginx/ssl/secman.key \
    -out /etc/nginx/ssl/secman.crt \
    -subj "/CN=secman.yourdomain.com"

sudo chmod 600 /etc/nginx/ssl/secman.key
```

---

## Systemd Services

### Backend Service

Create `/etc/systemd/system/secman-backend.service`:

```ini
[Unit]
Description=Secman Backend API
After=network.target mariadb.service
Requires=mariadb.service

[Service]
Type=simple
User=secman
Group=secman
WorkingDirectory=/opt/secman/app/src/backendng
EnvironmentFile=/etc/secman/backend.env

Environment="JAVA_OPTS=-Xmx2g -Xms512m -XX:+UseG1GC"
ExecStart=/usr/bin/java $JAVA_OPTS -jar /opt/secman/app/src/backendng/build/libs/backendng-0.1-all.jar

Restart=on-failure
RestartSec=10s

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

### Frontend Service

Create `/etc/systemd/system/secman-frontend.service`:

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
RestartSec=10s

NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true

StandardOutput=journal
StandardError=journal
SyslogIdentifier=secman-frontend

[Install]
WantedBy=multi-user.target
```

### Enable and Start

```bash
sudo systemctl daemon-reload
sudo systemctl enable secman-backend secman-frontend
sudo systemctl start secman-backend secman-frontend
sudo systemctl status secman-backend secman-frontend
```

---

## Security Hardening

### Firewall

**Amazon Linux / RHEL:**
```bash
sudo systemctl enable firewalld
sudo systemctl start firewalld
sudo firewall-cmd --permanent --add-service=http
sudo firewall-cmd --permanent --add-service=https
sudo firewall-cmd --permanent --add-service=ssh
sudo firewall-cmd --reload
```

**Ubuntu:**
```bash
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
```

### File Permissions

```bash
sudo chown -R secman:secman /opt/secman/app
sudo chmod -R 750 /opt/secman/app
sudo chmod 600 /etc/secman/*.env
sudo chmod 600 /etc/nginx/ssl/*.key
```

### Fail2ban

```bash
# Install
sudo dnf install -y fail2ban  # Amazon Linux
sudo apt install -y fail2ban  # Ubuntu

# Create jail
sudo tee /etc/fail2ban/jail.d/secman.conf << 'EOF'
[secman]
enabled = true
port = http,https
filter = secman
logpath = /var/log/nginx/secman-api-access.log
maxretry = 5
bantime = 3600
findtime = 600
EOF

# Create filter
sudo tee /etc/fail2ban/filter.d/secman.conf << 'EOF'
[Definition]
failregex = ^<HOST> .* "POST /api/auth/login HTTP.*" 401
ignoreregex =
EOF

sudo systemctl enable fail2ban
sudo systemctl start fail2ban
```

---

## Monitoring

### Health Checks

```bash
# Backend
curl http://localhost:8080/health

# Frontend
curl http://localhost:4321/

# External
curl https://secman.yourdomain.com/
curl https://api.yourdomain.com/health
```

### Logs

```bash
# Backend
sudo journalctl -u secman-backend -f

# Frontend
sudo journalctl -u secman-frontend -f

# Nginx
sudo tail -f /var/log/nginx/secman-api-access.log
sudo tail -f /var/log/nginx/secman-api-error.log
```

### Health Monitor Script

Create `/opt/secman/bin/monitor.sh`:

```bash
#!/bin/bash

BACKEND_URL="http://localhost:8080/health"
FRONTEND_URL="http://localhost:4321/"

if ! curl -sf "$BACKEND_URL" > /dev/null; then
    echo "Backend down!" | logger -t secman-monitor
    systemctl restart secman-backend
fi

if ! curl -sf "$FRONTEND_URL" > /dev/null; then
    echo "Frontend down!" | logger -t secman-monitor
    systemctl restart secman-frontend
fi
```

Add to cron:
```bash
*/5 * * * * /opt/secman/bin/monitor.sh
```

---

## Maintenance

### Updates

```bash
sudo systemctl stop secman-frontend secman-backend

sudo su - secman
cd /opt/secman/app
git pull origin main

# Rebuild backend
./gradlew :src:backendng:shadowJar -x test

# Rebuild frontend
cd src/frontend
npm ci --production && npm run build
exit

sudo systemctl start secman-backend secman-frontend
```

### Database Backup

```bash
#!/bin/bash
BACKUP_DIR="/opt/secman/backups"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

mkdir -p "$BACKUP_DIR"
mysqldump -u secman -p secman | gzip > "$BACKUP_DIR/secman_${TIMESTAMP}.sql.gz"
find "$BACKUP_DIR" -name "*.sql.gz" -mtime +7 -delete
```

Schedule daily:
```bash
0 2 * * * /opt/secman/bin/backup-db.sh
```

---

## Troubleshooting

### Backend Won't Start

1. Check logs: `sudo journalctl -u secman-backend -n 100`
2. Verify Java: `java -version` (must be 21)
3. Check database: `mysql -u secman -p secman`
4. Verify JAR: `ls -la /opt/secman/app/src/backendng/build/libs/`

### Frontend Won't Start

1. Check logs: `sudo journalctl -u secman-frontend -n 100`
2. Verify Node: `node -v` (must be 18+ or 20.x)
3. Check build: `ls -la /opt/secman/app/src/frontend/dist/server/`
4. Verify backend: `curl http://localhost:8080/health`

### Nginx 502

1. Check services running: `systemctl status secman-backend secman-frontend`
2. Check nginx logs: `sudo tail -f /var/log/nginx/secman-api-error.log`
3. Test upstream: `curl http://localhost:8080/health`

### SSL Certificate Issues

1. Verify files: `sudo ls -la /etc/letsencrypt/live/`
2. Check expiry: `openssl x509 -in /path/to/cert.pem -noout -dates`
3. Renew: `sudo certbot renew --force-renewal`

---

## Related Documentation

- [Environment Variables](./ENVIRONMENT.md) - Configuration reference
- [CLI Reference](./CLI.md) - Command-line tools
- [CrowdStrike Import](./CROWDSTRIKE_IMPORT.md) - Vulnerability import details

---

*For additional support, check GitHub Issues: https://github.com/schmalle/secman/issues*
