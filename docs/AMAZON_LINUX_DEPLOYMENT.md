# Secman Deployment Guide for Amazon Linux 2023

This guide provides step-by-step instructions for deploying the Secman security management application on Amazon Linux 2023, including automatic startup configuration.

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [System Preparation](#system-preparation)
3. [Database Setup](#database-setup)
4. [Backend Deployment](#backend-deployment)
5. [Frontend Deployment](#frontend-deployment)
6. [Systemd Services](#systemd-services)
7. [Nginx Reverse Proxy](#nginx-reverse-proxy)
8. [Environment Variables](#environment-variables)
9. [Security Hardening](#security-hardening)
10. [Verification](#verification)
11. [Troubleshooting](#troubleshooting)

---

## Prerequisites

### Software Requirements

- Amazon Linux 2023 instance
- Root or sudo access
- At least 2GB RAM (4GB recommended)
- 20GB disk space
- Public IP or domain name (for production)

---

## System Preparation

### 1. Update System Packages

```bash
sudo dnf update -y
```

### 2. Install Required Dependencies

```bash
# Install development tools
sudo dnf groupinstall "Development Tools" -y

# Install Java 21 (required for Kotlin/Micronaut backend)
sudo dnf install java-21-amazon-corretto-devel -y

# Verify Java installation
java -version  # Should show Java 21

# Install Node.js 20 (required for Astro/React frontend)
curl -fsSL https://rpm.nodesource.com/setup_20.x | sudo bash -
sudo dnf install nodejs -y

# Verify Node.js and npm
node --version  # Should show v20.x
npm --version   # Should show v10.x

# Install Git (if not already installed)
sudo dnf install git -y
```

### 3. Create Application User

Create a dedicated user for running the application:

```bash
sudo useradd -r -m -d /opt/secman -s /bin/bash secman
sudo passwd secman  # Set a strong password
```

---

## Database Setup

### 1. Install MariaDB 12

```bash
# Add MariaDB repository
sudo curl -sS https://downloads.mariadb.com/MariaDB/mariadb_repo_setup | sudo bash -s -- --mariadb-server-version="mariadb-10.11"

# Install MariaDB server
sudo dnf install MariaDB-server MariaDB-client -y

# Start and enable MariaDB
sudo systemctl start mariadb
sudo systemctl enable mariadb
```

### 2. Secure MariaDB Installation

```bash
sudo mysql_secure_installation
```

Follow the prompts:
- Set root password: **YES** (use a strong password)
- Remove anonymous users: **YES**
- Disallow root login remotely: **YES**
- Remove test database: **YES**
- Reload privilege tables: **YES**

### 3. Create Database and User

```bash
sudo mysql -u root -p
```

Execute the following SQL commands:

```sql
CREATE DATABASE secman CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER 'secman'@'localhost' IDENTIFIED BY 'YOUR_SECURE_PASSWORD';

GRANT ALL PRIVILEGES ON secman.* TO 'secman'@'localhost';

FLUSH PRIVILEGES;

EXIT;
```

**Important:** Replace `YOUR_SECURE_PASSWORD` with a strong, randomly generated password.

---

## Backend Deployment

### 1. Clone Repository

```bash
# Switch to secman user
sudo su - secman

# Clone the repository
cd /opt/secman
git clone https://github.com/schmalle/secman.git app
cd app

# Checkout the desired branch/tag
git checkout main  # or your production branch
```

### 2. Configure Environment Variables

Create environment file for backend:

```bash
sudo mkdir -p /etc/secman
sudo nano /etc/secman/backend.env
```

Add the following configuration:

```bash
# Database Configuration
DB_USERNAME=secman
DB_PASSWORD=YOUR_SECURE_PASSWORD

# JWT Secret (MUST be 256 bits / 32 bytes)
# Generate with: openssl rand -base64 32
JWT_SECRET=REPLACE_WITH_GENERATED_SECRET

# Backend Base URL (your server's public URL)
BACKEND_BASE_URL=https://api.yourdomain.com

# Frontend URL (your frontend's public URL)
FRONTEND_URL=https://secman.yourdomain.com

# Email Configuration (SMTP)
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=noreply@yourdomain.com
SMTP_PASSWORD=your_smtp_password
SMTP_FROM_ADDRESS=noreply@yourdomain.com
SMTP_FROM_NAME=Security Management System
SMTP_ENABLE_TLS=true

# Encryption Configuration (for sensitive data)
# Generate with: openssl rand -hex 32
SECMAN_ENCRYPTION_PASSWORD=REPLACE_WITH_GENERATED_PASSWORD
# Generate with: openssl rand -hex 8
SECMAN_ENCRYPTION_SALT=REPLACE_WITH_GENERATED_SALT

# Optional: Vulnerability Configuration
VULN_USE_PATCH_PUBLICATION_DATE=false
VULN_REQUIRE_PATCH_PUBLICATION_DATE=false

# Optional: OAuth Configuration
# GITHUB_CLIENT_ID=your_github_client_id
# GITHUB_CLIENT_SECRET=your_github_client_secret
```

**Important Security Notes:**
- Generate JWT_SECRET: `openssl rand -base64 32`
- Generate ENCRYPTION_PASSWORD: `openssl rand -hex 32`
- Generate ENCRYPTION_SALT: `openssl rand -hex 8`
- Never commit these values to version control
- Use strong, unique passwords

Secure the environment file:

```bash
sudo chown root:secman /etc/secman/backend.env
sudo chmod 640 /etc/secman/backend.env
```

### 3. Build Backend

```bash
# As secman user
cd /opt/secman/app

# Build the application
./gradlew :src:backendng:build -x test

# The built JAR will be at:
# src/backendng/build/libs/backendng-0.1-all.jar
```

---

## Frontend Deployment

### 1. Configure Frontend Environment

Create environment file for frontend:

```bash
sudo nano /etc/secman/frontend.env
```

Add the following:

```bash
# Backend API URL
PUBLIC_API_URL=https://api.yourdomain.com

# Frontend public URL
PUBLIC_FRONTEND_URL=https://secman.yourdomain.com
```

Secure the file:

```bash
sudo chown root:secman /etc/secman/frontend.env
sudo chmod 640 /etc/secman/frontend.env
```

### 2. Create Astro Environment Configuration

```bash
# As secman user
cd /opt/secman/app/src/frontend

# Create .env file from the system environment
cat > .env << 'EOF'
PUBLIC_API_URL=$PUBLIC_API_URL
PUBLIC_FRONTEND_URL=$PUBLIC_FRONTEND_URL
EOF
```

### 3. Update Astro Configuration

Edit the Astro config to set the correct base URL:

```bash
nano /opt/secman/app/src/frontend/astro.config.mjs
```

Ensure it includes:

```javascript
import { defineConfig } from 'astro/config';
import react from '@astrojs/react';
import node from '@astrojs/node';

export default defineConfig({
  integrations: [react()],
  output: 'server',
  adapter: node({
    mode: 'standalone'
  }),
  server: {
    port: 4321,
    host: '127.0.0.1'
  }
});
```

### 4. Build Frontend

```bash
# As secman user
cd /opt/secman/app/src/frontend

# Install dependencies
npm ci --production

# Build the frontend
npm run build
```

---

## Systemd Services

### 1. Create Backend Service

```bash
sudo nano /etc/systemd/system/secman-backend.service
```

Add the following configuration:

```ini
[Unit]
Description=Secman Backend Service (Micronaut/Kotlin)
After=network.target mariadb.service
Requires=mariadb.service
Documentation=https://github.com/schmalle/secman

[Service]
Type=simple
User=secman
Group=secman
WorkingDirectory=/opt/secman/app

# Load environment variables
EnvironmentFile=/etc/secman/backend.env

# Java options
Environment="JAVA_OPTS=-Xmx1024m -Xms512m -XX:+UseG1GC"

# Execute the JAR
ExecStart=/usr/bin/java $JAVA_OPTS \
    -Dmicronaut.config.files=/etc/secman/backend.env \
    -jar /opt/secman/app/src/backendng/build/libs/backendng-0.1-all.jar

# Restart policy
Restart=on-failure
RestartSec=10
StartLimitInterval=5min
StartLimitBurst=5

# Security hardening
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/opt/secman/app/logs

# Logging
StandardOutput=journal
StandardError=journal
SyslogIdentifier=secman-backend

[Install]
WantedBy=multi-user.target
```

### 2. Create Frontend Service

```bash
sudo nano /etc/systemd/system/secman-frontend.service
```

Add the following:

```ini
[Unit]
Description=Secman Frontend Service (Astro/React)
After=network.target secman-backend.service
Wants=secman-backend.service
Documentation=https://github.com/schmalle/secman

[Service]
Type=simple
User=secman
Group=secman
WorkingDirectory=/opt/secman/app/src/frontend

# Load environment variables
EnvironmentFile=/etc/secman/frontend.env

# Node.js options
Environment="NODE_ENV=production"
Environment="HOST=127.0.0.1"
Environment="PORT=4321"

# Execute Astro in production mode
ExecStart=/usr/bin/node ./dist/server/entry.mjs

# Restart policy
Restart=on-failure
RestartSec=10
StartLimitInterval=5min
StartLimitBurst=5

# Security hardening
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true

# Logging
StandardOutput=journal
StandardError=journal
SyslogIdentifier=secman-frontend

[Install]
WantedBy=multi-user.target
```

### 3. Enable and Start Services

```bash
# Reload systemd daemon
sudo systemctl daemon-reload

# Enable services to start on boot
sudo systemctl enable secman-backend
sudo systemctl enable secman-frontend

# Start services
sudo systemctl start secman-backend
sudo systemctl start secman-frontend

# Check status
sudo systemctl status secman-backend
sudo systemctl status secman-frontend
```

### 4. View Logs

```bash
# Backend logs
sudo journalctl -u secman-backend -f

# Frontend logs
sudo journalctl -u secman-frontend -f

# View last 100 lines
sudo journalctl -u secman-backend -n 100
```

---

## Nginx Reverse Proxy

### 1. Install Nginx

```bash
sudo dnf install nginx -y
```

### 2. Configure Nginx

Create configuration for Secman:

```bash
sudo nano /etc/nginx/conf.d/secman.conf
```

Add the following configuration:

```nginx
# Backend API server
upstream secman_backend {
    server 127.0.0.1:8080;
    keepalive 32;
}

# Frontend server
upstream secman_frontend {
    server 127.0.0.1:4321;
    keepalive 32;
}

# HTTP redirect to HTTPS
server {
    listen 80;
    server_name api.yourdomain.com secman.yourdomain.com;

    location /.well-known/acme-challenge/ {
        root /var/www/certbot;
    }

    location / {
        return 301 https://$host$request_uri;
    }
}

# Backend API (HTTPS)
server {
    listen 443 ssl http2;
    server_name api.yourdomain.com;

    # SSL Certificate paths (use certbot to generate)
    ssl_certificate /etc/letsencrypt/live/api.yourdomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/api.yourdomain.com/privkey.pem;

    # SSL Configuration
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers ECDHE-RSA-AES128-GCM-SHA256:ECDHE-RSA-AES256-GCM-SHA384;
    ssl_prefer_server_ciphers off;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 10m;

    # Security headers
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-XSS-Protection "1; mode=block" always;

    # Logs
    access_log /var/log/nginx/secman-backend-access.log;
    error_log /var/log/nginx/secman-backend-error.log;

    # File upload size (matches backend config)
    client_max_body_size 20M;

    # Proxy to backend
    location / {
        proxy_pass http://secman_backend;
        proxy_http_version 1.1;

        # Headers
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-Port $server_port;

        # WebSocket support (for SSE)
        proxy_set_header Connection "upgrade";
        proxy_set_header Upgrade $http_upgrade;

        # Timeouts
        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;

        # Buffering
        proxy_buffering off;
        proxy_request_buffering off;
    }

    # Health check endpoint
    location /health {
        proxy_pass http://secman_backend/health;
        access_log off;
    }
}

# Frontend (HTTPS)
server {
    listen 443 ssl http2;
    server_name secman.yourdomain.com;

    # SSL Certificate paths
    ssl_certificate /etc/letsencrypt/live/secman.yourdomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/secman.yourdomain.com/privkey.pem;

    # SSL Configuration
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers ECDHE-RSA-AES128-GCM-SHA256:ECDHE-RSA-AES256-GCM-SHA384;
    ssl_prefer_server_ciphers off;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 10m;

    # Security headers
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-XSS-Protection "1; mode=block" always;

    # Logs
    access_log /var/log/nginx/secman-frontend-access.log;
    error_log /var/log/nginx/secman-frontend-error.log;

    # Proxy to frontend
    location / {
        proxy_pass http://secman_frontend;
        proxy_http_version 1.1;

        # Headers
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # Timeouts
        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
    }
}
```

### 3. Obtain SSL Certificates

```bash
# Install certbot
sudo dnf install certbot python3-certbot-nginx -y

# Obtain certificates for both domains
sudo certbot --nginx -d api.yourdomain.com -d secman.yourdomain.com

# Test auto-renewal
sudo certbot renew --dry-run
```

### 4. Enable and Start Nginx

```bash
# Test nginx configuration
sudo nginx -t

# Enable nginx on boot
sudo systemctl enable nginx

# Start nginx
sudo systemctl start nginx

# Check status
sudo systemctl status nginx
```

---

## Environment Variables

### Critical Environment Variables

The following environment variables **MUST** be configured:

#### Backend (`/etc/secman/backend.env`)

| Variable | Description | Example | Required |
|----------|-------------|---------|----------|
| `BACKEND_BASE_URL` | Public URL of backend API | `https://api.yourdomain.com` | ✅ Yes |
| `FRONTEND_URL` | Public URL of frontend | `https://secman.yourdomain.com` | ✅ Yes |
| `DB_USERNAME` | Database username | `secman` | ✅ Yes |
| `DB_PASSWORD` | Database password | `[secure-password]` | ✅ Yes |
| `JWT_SECRET` | JWT signing secret (256 bits) | `[base64-string]` | ✅ Yes |
| `SMTP_HOST` | SMTP server hostname | `smtp.gmail.com` | ✅ Yes |
| `SMTP_PORT` | SMTP server port | `587` | ✅ Yes |
| `SMTP_USERNAME` | SMTP username | `noreply@domain.com` | ✅ Yes |
| `SMTP_PASSWORD` | SMTP password | `[smtp-password]` | ✅ Yes |
| `SECMAN_ENCRYPTION_PASSWORD` | Encryption password | `[hex-string-32-bytes]` | ✅ Yes |
| `SECMAN_ENCRYPTION_SALT` | Encryption salt | `[hex-string-8-bytes]` | ✅ Yes |

#### Frontend (`/etc/secman/frontend.env`)

| Variable | Description | Example | Required |
|----------|-------------|---------|----------|
| `PUBLIC_API_URL` | Backend API URL | `https://api.yourdomain.com` | ✅ Yes |
| `PUBLIC_FRONTEND_URL` | Frontend public URL | `https://secman.yourdomain.com` | ✅ Yes |

### Updating CORS Configuration

After setting `BACKEND_BASE_URL` and `FRONTEND_URL`, update the CORS configuration in the backend:

Edit `/opt/secman/app/src/backendng/src/main/resources/application.yml`:

```yaml
micronaut:
  server:
    cors:
      enabled: true
      configurations:
        web:
          allowed-origins:
            - "${FRONTEND_URL}"  # Uses environment variable
            - "http://localhost:4321"  # Development only
```

Rebuild and restart:

```bash
cd /opt/secman/app
./gradlew :src:backendng:build -x test
sudo systemctl restart secman-backend
```

---

## Security Hardening

### 1. Firewall Configuration

```bash
# Enable firewalld
sudo systemctl enable firewalld
sudo systemctl start firewalld

# Allow HTTP and HTTPS
sudo firewall-cmd --permanent --add-service=http
sudo firewall-cmd --permanent --add-service=https

# Allow SSH (if not already allowed)
sudo firewall-cmd --permanent --add-service=ssh

# Reload firewall
sudo firewall-cmd --reload

# Verify rules
sudo firewall-cmd --list-all
```

### 2. SELinux Configuration

If SELinux is enabled:

```bash
# Check SELinux status
getenforce

# Allow nginx to connect to backend services
sudo setsebool -P httpd_can_network_connect 1

# Allow nginx to read/write files
sudo chcon -R -t httpd_sys_content_t /opt/secman/app
```

### 3. File Permissions

```bash
# Application directory
sudo chown -R secman:secman /opt/secman/app

# Environment files (read-only for secman group)
sudo chown root:secman /etc/secman/*.env
sudo chmod 640 /etc/secman/*.env

# Log directory
sudo mkdir -p /opt/secman/app/logs
sudo chown -R secman:secman /opt/secman/app/logs
sudo chmod 755 /opt/secman/app/logs
```

### 4. Database Security

```bash
# Bind MariaDB to localhost only
sudo nano /etc/my.cnf.d/server.cnf
```

Add under `[mysqld]`:

```ini
[mysqld]
bind-address = 127.0.0.1
skip-networking = 0
```

Restart MariaDB:

```bash
sudo systemctl restart mariadb
```

### 5. Automatic Security Updates

```bash
# Enable automatic security updates
sudo dnf install dnf-automatic -y

# Configure for security updates only
sudo nano /etc/dnf/automatic.conf
```

Set:

```ini
[commands]
upgrade_type = security
apply_updates = yes
```

Enable and start:

```bash
sudo systemctl enable dnf-automatic.timer
sudo systemctl start dnf-automatic.timer
```

---

## Verification

### 1. Check Service Status

```bash
# Check all services
sudo systemctl status secman-backend secman-frontend nginx mariadb

# Check if services are enabled
sudo systemctl is-enabled secman-backend secman-frontend nginx mariadb
```

### 2. Test Backend API

```bash
# Health check
curl http://localhost:8080/health

# Should return: {"status":"UP"}

# Test via nginx (HTTPS)
curl https://api.yourdomain.com/health
```

### 3. Test Frontend

```bash
# Test local frontend
curl http://localhost:4321

# Test via nginx (HTTPS)
curl https://secman.yourdomain.com
```

### 4. Test Database Connection

```bash
sudo mysql -u secman -p secman -e "SHOW TABLES;"
```

### 5. Test Email Configuration

Use the notification CLI command:

```bash
cd /opt/secman/app
./gradlew cli:run --args='send-notifications --dry-run --verbose'
```

### 6. Check Logs

```bash
# Recent backend logs
sudo journalctl -u secman-backend -n 50 --no-pager

# Recent frontend logs
sudo journalctl -u secman-frontend -n 50 --no-pager

# Nginx access logs
sudo tail -f /var/log/nginx/secman-backend-access.log
sudo tail -f /var/log/nginx/secman-frontend-access.log

# Nginx error logs
sudo tail -f /var/log/nginx/secman-backend-error.log
sudo tail -f /var/log/nginx/secman-frontend-error.log
```

---

## Troubleshooting

### Backend Not Starting

**Check logs:**

```bash
sudo journalctl -u secman-backend -n 100 --no-pager
```

**Common issues:**

1. **Database connection failed**
   - Verify MariaDB is running: `sudo systemctl status mariadb`
   - Check credentials in `/etc/secman/backend.env`
   - Test connection: `mysql -u secman -p -h localhost secman`

2. **Port 8080 already in use**
   - Check what's using the port: `sudo lsof -i :8080`
   - Kill the process or change port in application.yml

3. **Missing environment variables**
   - Verify `/etc/secman/backend.env` exists and is readable
   - Check permissions: `ls -la /etc/secman/backend.env`

4. **Java version mismatch**
   - Verify Java version: `java -version`
   - Should be Java 21

### Frontend Not Starting

**Check logs:**

```bash
sudo journalctl -u secman-frontend -n 100 --no-pager
```

**Common issues:**

1. **Build directory missing**
   - Ensure build was successful: `ls -la /opt/secman/app/src/frontend/dist`
   - Rebuild: `cd /opt/secman/app/src/frontend && npm run build`

2. **Port 4321 already in use**
   - Check: `sudo lsof -i :4321`
   - Kill the process or change port

3. **Cannot connect to backend**
   - Verify `PUBLIC_API_URL` in `/etc/secman/frontend.env`
   - Test backend: `curl http://localhost:8080/health`

### Nginx Issues

**Test configuration:**

```bash
sudo nginx -t
```

**Check logs:**

```bash
sudo tail -f /var/log/nginx/error.log
```

**Common issues:**

1. **Port 80/443 already in use**
   - Check: `sudo lsof -i :80` or `sudo lsof -i :443`
   - Stop conflicting service

2. **SSL certificate errors**
   - Verify certificates exist: `ls -la /etc/letsencrypt/live/`
   - Regenerate: `sudo certbot --nginx -d yourdomain.com`

3. **Proxy connection refused**
   - Ensure backend/frontend services are running
   - Check firewall rules

### Database Issues

**Check MariaDB status:**

```bash
sudo systemctl status mariadb
```

**View MariaDB logs:**

```bash
sudo journalctl -u mariadb -n 100 --no-pager
```

**Common issues:**

1. **Cannot connect to database**
   - Verify MariaDB is running
   - Check bind address in `/etc/my.cnf.d/server.cnf`
   - Test connection: `mysql -u root -p`

2. **Tables not created**
   - Check Hibernate auto-update in application.yml
   - Manually run migrations if needed

### Email Notification Issues

**Test SMTP configuration:**

```bash
cd /opt/secman/app
./gradlew cli:run --args='send-notifications --dry-run --verbose'
```

**Common issues:**

1. **SMTP authentication failed**
   - Verify credentials in `/etc/secman/backend.env`
   - Check if SMTP provider requires app-specific passwords (e.g., Gmail)

2. **Connection timeout**
   - Verify firewall allows outbound SMTP (port 587/465)
   - Check `SMTP_HOST` and `SMTP_PORT` values

### Performance Issues

**Check system resources:**

```bash
# CPU and memory usage
top

# Disk usage
df -h

# Check service memory usage
sudo systemctl status secman-backend
sudo systemctl status secman-frontend
```

**Increase Java heap size:**

Edit `/etc/systemd/system/secman-backend.service`:

```ini
Environment="JAVA_OPTS=-Xmx2048m -Xms1024m -XX:+UseG1GC"
```

Reload and restart:

```bash
sudo systemctl daemon-reload
sudo systemctl restart secman-backend
```

---

## Maintenance

### Updating the Application

```bash
# Stop services
sudo systemctl stop secman-frontend secman-backend

# Switch to secman user
sudo su - secman
cd /opt/secman/app

# Pull latest changes
git fetch origin
git checkout main  # or your target branch
git pull

# Rebuild backend
./gradlew :src:backendng:build -x test

# Rebuild frontend
cd src/frontend
npm ci --production
npm run build

# Exit secman user
exit

# Start services
sudo systemctl start secman-backend secman-frontend

# Check status
sudo systemctl status secman-backend secman-frontend
```

### Database Backup

```bash
# Create backup directory
sudo mkdir -p /opt/secman/backups

# Backup database
sudo mysqldump -u secman -p secman > /opt/secman/backups/secman_$(date +%Y%m%d_%H%M%S).sql

# Compress backup
gzip /opt/secman/backups/secman_*.sql
```

**Automated daily backup (crontab):**

```bash
sudo crontab -e
```

Add:

```cron
# Daily database backup at 2 AM
0 2 * * * mysqldump -u secman -p'YOUR_PASSWORD' secman | gzip > /opt/secman/backups/secman_$(date +\%Y\%m\%d_\%H\%M\%S).sql.gz

# Delete backups older than 30 days
0 3 * * * find /opt/secman/backups -name "secman_*.sql.gz" -mtime +30 -delete
```

### Log Rotation

Create log rotation configuration:

```bash
sudo nano /etc/logrotate.d/secman
```

Add:

```
/var/log/nginx/secman-*.log {
    daily
    rotate 14
    compress
    delaycompress
    notifempty
    create 0640 nginx nginx
    sharedscripts
    postrotate
        systemctl reload nginx > /dev/null 2>&1
    endscript
}
```

---

## Additional Resources

- **Project Repository:** https://github.com/schmalle/secman
- **Micronaut Documentation:** https://docs.micronaut.io/
- **Astro Documentation:** https://docs.astro.build/
- **Amazon Linux 2023 User Guide:** https://docs.aws.amazon.com/linux/al2023/

---

## Support

For issues or questions:
1. Check logs using `journalctl` commands above
2. Review troubleshooting section
3. Open an issue on GitHub: https://github.com/schmalle/secman/issues

---

**Last Updated:** 2025-11-08
**Version:** 1.0
**Tested On:** Amazon Linux 2023
