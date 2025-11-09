# Nginx Installation Guide for SecMan Web Interface

**Version:** 1.0
**Last Updated:** 2025-11-04
**Target Platform:** Linux (Ubuntu/Debian and RHEL/CentOS)

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Prerequisites](#prerequisites)
4. [Installation Steps](#installation-steps)
5. [Nginx Configuration](#nginx-configuration)
6. [SSL/TLS Setup](#ssltls-setup)
7. [Service Management](#service-management)
8. [Security Hardening](#security-hardening)
9. [Monitoring and Logs](#monitoring-and-logs)
10. [Troubleshooting](#troubleshooting)
11. [Backup and Maintenance](#backup-and-maintenance)

---

## Overview

SecMan is a full-stack security management application consisting of:

- **Backend**: Kotlin/Micronaut REST API (port 8080)
- **Frontend**: Astro/React SSR application (port 4321)
- **Database**: MariaDB 12 (port 3306)

This guide covers deploying both components behind an nginx reverse proxy for production use.

---

## Architecture

```
Internet
    ↓
[Nginx :80/:443]
    ↓
    ├─→ /api/*     → Backend (localhost:8080)
    ├─→ /oauth/*   → Backend (localhost:8080)
    └─→ /*         → Frontend (localhost:4321)
```

**Key Points:**
- Nginx handles SSL termination and serves as reverse proxy
- Frontend (Astro SSR) runs as a Node.js application
- Backend (Micronaut) runs as a Java application
- Both applications run as systemd services
- All external traffic goes through nginx on ports 80/443

---

## Prerequisites

### System Requirements

**Minimum:**
- CPU: 2 cores
- RAM: 4 GB
- Disk: 20 GB
- OS: Ubuntu 20.04+ / RHEL 8+ / Debian 11+

**Recommended:**
- CPU: 4+ cores
- RAM: 8+ GB
- Disk: 50+ GB SSD
- OS: Ubuntu 22.04 LTS / RHEL 9

### Software Requirements

1. **Nginx** (1.18+)
2. **Java** (OpenJDK 21)
3. **Node.js** (18.x or 20.x LTS)
4. **MariaDB** (12.x)
5. **Git** (for deployment)
6. **Gradle** (9.2+ for building backend)

### Network Requirements

- Ports 80 and 443 accessible from internet (for HTTPS)
- Firewall rules allowing traffic on nginx ports
- Domain name with DNS configured (e.g., secman.example.com)

---

## Installation Steps

### Step 1: Install System Dependencies

#### Ubuntu/Debian

```bash
# Update package list
sudo apt update

# Install nginx
sudo apt install -y nginx

# Install Java 21
sudo apt install -y openjdk-21-jdk

# Install Node.js 20.x LTS
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs

# Install MariaDB 12
sudo apt install -y software-properties-common
curl -LsS https://r.mariadb.com/downloads/mariadb_repo_setup | sudo bash -s -- --mariadb-server-version=12.0
sudo apt update
sudo apt install -y mariadb-server mariadb-client

# Install build tools
sudo apt install -y git build-essential
```

#### RHEL/CentOS/Rocky Linux

```bash
# Install nginx
sudo dnf install -y nginx

# Install Java 21
sudo dnf install -y java-21-openjdk java-21-openjdk-devel

# Install Node.js 20.x LTS
curl -fsSL https://rpm.nodesource.com/setup_20.x | sudo bash -
sudo dnf install -y nodejs

# Install MariaDB 12
sudo dnf install -y mariadb-server

# Install build tools
sudo dnf install -y git gcc-c++ make
```

### Step 2: Verify Installations

```bash
# Check nginx
nginx -v
# Expected: nginx version: nginx/1.18.0 or higher

# Check Java
java -version
# Expected: openjdk version "21.0.x"

# Check Node.js
node -v
npm -v
# Expected: v20.x.x and 10.x.x

# Check MariaDB
mysql --version
# Expected: mysql  Ver 15.1 Distrib 12.0.x-MariaDB

# Check Gradle (if installed globally)
./gradlew -v
# Expected: Gradle 9.2 or higher
```

### Step 3: Create Application User

```bash
# Create a dedicated user for running secman
sudo useradd -r -m -s /bin/bash -d /opt/secman secman

# Add to necessary groups
sudo usermod -aG sudo secman  # Optional: for administrative tasks
```

### Step 4: Setup Database

```bash
# Start MariaDB service
sudo systemctl start mariadb
sudo systemctl enable mariadb

# Secure MariaDB installation
sudo mysql_secure_installation

# Create database and user
sudo mysql -u root -p <<EOF
CREATE DATABASE IF NOT EXISTS secman CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'secman'@'localhost' IDENTIFIED BY 'CHANGE_THIS_PASSWORD';
GRANT ALL PRIVILEGES ON secman.* TO 'secman'@'localhost';
FLUSH PRIVILEGES;
EOF
```

### Step 5: Clone and Build Application

```bash
# Switch to secman user
sudo su - secman

# Clone repository (adjust URL to your repo)
git clone https://github.com/yourusername/secman.git /opt/secman/app
cd /opt/secman/app

# Checkout production branch
git checkout main  # or your production branch

# Build backend (IMPORTANT: Use shadowJar task to create executable JAR)
cd src/backendng
./gradlew clean shadowJar -x test
# JAR will be created at: build/libs/backendng-0.1-all.jar

# Build frontend
cd ../frontend
npm ci --production
npm run build
# Build output will be in: dist/ directory

# Exit secman user
exit
```

### Step 6: Configure Application

#### Backend Configuration

Create environment file for backend:

```bash
sudo nano /opt/secman/backend.env
```

Add the following configuration:

```bash
# Database Configuration
DB_USERNAME=secman
DB_PASSWORD=CHANGE_THIS_PASSWORD
DATASOURCE_URL=jdbc:mariadb://localhost:3306/secman

# JWT Secret (Generate with: openssl rand -base64 32)
JWT_SECRET=CHANGE_THIS_TO_RANDOM_256_BIT_SECRET

# Email Configuration (Feature 035)
SMTP_HOST=smtp.example.com
SMTP_PORT=587
SMTP_USERNAME=noreply@secman.example.com
SMTP_PASSWORD=SMTP_PASSWORD_HERE
SMTP_FROM_ADDRESS=noreply@secman.example.com
SMTP_FROM_NAME=Security Management System
SMTP_ENABLE_TLS=true

# Encryption for sensitive data (Feature 035)
SECMAN_ENCRYPTION_PASSWORD=CHANGE_THIS_ENCRYPTION_PASSWORD
SECMAN_ENCRYPTION_SALT=CHANGE_THIS_SALT_16_CHARS

# Frontend URL
FRONTEND_URL=https://secman.example.com

# Server Port
MICRONAUT_SERVER_PORT=8080
```

Secure the environment file:

```bash
sudo chown secman:secman /opt/secman/backend.env
sudo chmod 600 /opt/secman/backend.env
```

#### Frontend Configuration

Create environment file for frontend:

```bash
sudo nano /opt/secman/frontend.env
```

Add the following:

```bash
# Backend API URL (internal connection)
API_URL=http://localhost:8080

# Node.js configuration
NODE_ENV=production
HOST=127.0.0.1
PORT=4321
```

Secure the environment file:

```bash
sudo chown secman:secman /opt/secman/frontend.env
sudo chmod 600 /opt/secman/frontend.env
```

### Step 7: Create Systemd Services

#### Backend Service

**Important:** If using SDKMAN for Java installation, you need to configure the service differently than system-wide Java installations.

##### Option A: System-wide Java (apt/dnf installed)

```bash
sudo nano /etc/systemd/system/secman-backend.service
```

Add the following:

```ini
[Unit]
Description=SecMan Backend API
After=network.target mariadb.service
Wants=mariadb.service

[Service]
Type=simple
User=secman
Group=secman
WorkingDirectory=/opt/secman/app/src/backendng
EnvironmentFile=/opt/secman/backend.env

# Java options
Environment="JAVA_OPTS=-Xmx2g -Xms512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# Execute JAR
ExecStart=/usr/bin/java $JAVA_OPTS -jar /opt/secman/app/src/backendng/build/libs/backendng-0.1-all.jar

# Restart policy
Restart=on-failure
RestartSec=10s

# Security settings
NoNewPrivileges=true
PrivateTmp=true

# Logging
StandardOutput=journal
StandardError=journal
SyslogIdentifier=secman-backend

[Install]
WantedBy=multi-user.target
```

##### Option B: SDKMAN Java (Amazon Linux / User-installed Java)

First, find your Java path as the `secman` user:

```bash
sudo su - secman
which java
# Example output: /home/secman/.sdkman/candidates/java/current/bin/java
exit
```

Then create the service file:

```bash
sudo nano /etc/systemd/system/secman-backend.service
```

Add the following (replace `JAVA_PATH` with the actual path from above):

```ini
[Unit]
Description=SecMan Backend API
After=network.target mariadb.service
Wants=mariadb.service

[Service]
Type=simple
User=secman
Group=secman
WorkingDirectory=/opt/secman/app/src/backendng
EnvironmentFile=/opt/secman/backend.env

# SDKMAN environment variables
Environment="SDKMAN_DIR=/home/secman/.sdkman"
Environment="PATH=/home/secman/.sdkman/candidates/java/current/bin:/usr/local/bin:/usr/bin:/bin"
Environment="JAVA_HOME=/home/secman/.sdkman/candidates/java/current"

# Java options
Environment="JAVA_OPTS=-Xmx2g -Xms512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# Execute JAR (use explicit path to java)
ExecStart=/home/secman/.sdkman/candidates/java/current/bin/java $JAVA_OPTS -jar /opt/secman/app/src/backendng/build/libs/backendng-0.1-all.jar

# Restart policy
Restart=on-failure
RestartSec=10s

# Security settings
NoNewPrivileges=true
PrivateTmp=true

# Logging
StandardOutput=journal
StandardError=journal
SyslogIdentifier=secman-backend

[Install]
WantedBy=multi-user.target
```

**Note for SDKMAN users:**
- Replace `/home/secman` with the actual home directory of your `secman` user (could be `/opt/secman` if you used `-d /opt/secman` when creating the user)
- If using a specific Java version instead of `current`, replace `current` with the version (e.g., `21.0.1-tem`)
- Verify the paths exist before starting the service: `sudo ls -la /home/secman/.sdkman/candidates/java/current/bin/java`

#### Frontend Service

**Important:** If using Homebrew for Node.js installation, you need to configure the service differently than system-wide Node.js installations.

##### Option A: System-wide Node.js (apt/dnf installed)

```bash
sudo nano /etc/systemd/system/secman-frontend.service
```

Add the following:

```ini
[Unit]
Description=SecMan Frontend (Astro SSR)
After=network.target secman-backend.service
Wants=secman-backend.service

[Service]
Type=simple
User=secman
Group=secman
WorkingDirectory=/opt/secman/app/src/frontend
EnvironmentFile=/opt/secman/frontend.env

# Node.js options
Environment="NODE_OPTIONS=--max-old-space-size=1024"

# Execute Astro in production mode
ExecStart=/usr/bin/node /opt/secman/app/src/frontend/dist/server/entry.mjs

# Restart policy
Restart=on-failure
RestartSec=10s

# Security settings
NoNewPrivileges=true
PrivateTmp=true

# Logging
StandardOutput=journal
StandardError=journal
SyslogIdentifier=secman-frontend

[Install]
WantedBy=multi-user.target
```

##### Option B: Homebrew Node.js (Amazon Linux / Homebrew-installed Node.js)

First, find your Node.js path as the `secman` user:

```bash
sudo su - secman
which node
# Example output: /home/linuxbrew/.linuxbrew/bin/node
# OR: /home/secman/.linuxbrew/bin/node
exit
```

Then create the service file:

```bash
sudo nano /etc/systemd/system/secman-frontend.service
```

Add the following (replace paths with actual paths from above):

```ini
[Unit]
Description=SecMan Frontend (Astro SSR)
After=network.target secman-backend.service
Wants=secman-backend.service

[Service]
Type=simple
User=secman
Group=secman
WorkingDirectory=/opt/secman/app/src/frontend
EnvironmentFile=/opt/secman/frontend.env

# Homebrew environment variables
Environment="HOMEBREW_PREFIX=/home/linuxbrew/.linuxbrew"
Environment="PATH=/home/linuxbrew/.linuxbrew/bin:/home/linuxbrew/.linuxbrew/sbin:/usr/local/bin:/usr/bin:/bin"

# Node.js options
Environment="NODE_OPTIONS=--max-old-space-size=1024"

# Execute Astro in production mode (use explicit path to node)
ExecStart=/home/linuxbrew/.linuxbrew/bin/node /opt/secman/app/src/frontend/dist/server/entry.mjs

# Restart policy
Restart=on-failure
RestartSec=10s

# Security settings
NoNewPrivileges=true
PrivateTmp=true

# Logging
StandardOutput=journal
StandardError=journal
SyslogIdentifier=secman-frontend

[Install]
WantedBy=multi-user.target
```

**Note for Homebrew users:**
- The typical Homebrew prefix on Linux is `/home/linuxbrew/.linuxbrew`
- If you installed Homebrew in the user's home directory, it might be `/home/secman/.linuxbrew`
- Verify the paths exist before starting the service: `sudo ls -la /home/linuxbrew/.linuxbrew/bin/node`
- Also verify npm is in the same location: `sudo ls -la /home/linuxbrew/.linuxbrew/bin/npm`

#### Enable and Start Services

```bash
# Reload systemd
sudo systemctl daemon-reload

# Enable services to start on boot
sudo systemctl enable secman-backend.service
sudo systemctl enable secman-frontend.service

# Start services
sudo systemctl start secman-backend.service
sudo systemctl start secman-frontend.service

# Check status
sudo systemctl status secman-backend.service
sudo systemctl status secman-frontend.service
```

### Step 8: Verify Application Health

```bash
# Test backend health endpoint
curl http://localhost:8080/health
# Expected: {"status":"UP"}

# Test frontend
curl http://localhost:4321/
# Expected: HTML response

# Check logs if issues
sudo journalctl -u secman-backend.service -f
sudo journalctl -u secman-frontend.service -f
```

---

## Nginx Configuration

### Basic Reverse Proxy Configuration

Create nginx configuration:

```bash
sudo nano /etc/nginx/sites-available/secman
```

Add the following configuration:

```nginx
# Upstream definitions
upstream secman_backend {
    server 127.0.0.1:8080 fail_timeout=0;
}

upstream secman_frontend {
    server 127.0.0.1:4321 fail_timeout=0;
}

# HTTP server - redirect to HTTPS
server {
    listen 80;
    listen [::]:80;
    server_name secman.example.com;

    # Redirect all HTTP to HTTPS
    return 301 https://$server_name$request_uri;
}

# HTTPS server
server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name secman.example.com;

    # SSL certificates (will be configured in SSL section)
    ssl_certificate /etc/nginx/ssl/secman.crt;
    ssl_certificate_key /etc/nginx/ssl/secman.key;

    # SSL configuration
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers 'ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384';
    ssl_prefer_server_ciphers on;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 10m;

    # Security headers
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-XSS-Protection "1; mode=block" always;
    add_header Referrer-Policy "strict-origin-when-cross-origin" always;

    # Logging
    access_log /var/log/nginx/secman-access.log;
    error_log /var/log/nginx/secman-error.log;

    # Max upload size (for file imports)
    client_max_body_size 20M;

    # API endpoints - proxy to backend
    location /api/ {
        proxy_pass http://secman_backend;
        proxy_http_version 1.1;

        # Headers
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-Port $server_port;

        # WebSocket support (for SSE - Server-Sent Events)
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";

        # Timeouts
        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;

        # Buffering
        proxy_buffering off;
        proxy_request_buffering off;
    }

    # OAuth endpoints - proxy to backend
    location /oauth/ {
        proxy_pass http://secman_backend;
        proxy_http_version 1.1;

        # Headers - CRITICAL: X-Forwarded-Host is required for OAuth callback URLs
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-Port $server_port;

        proxy_connect_timeout 30s;
        proxy_send_timeout 30s;
        proxy_read_timeout 30s;
    }

    # Health check endpoint - proxy to backend
    location /health {
        proxy_pass http://secman_backend;
        proxy_http_version 1.1;

        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;

        access_log off;  # Don't log health checks
    }

    # All other requests - proxy to frontend
    location / {
        proxy_pass http://secman_frontend;
        proxy_http_version 1.1;

        # Headers
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # Timeouts
        proxy_connect_timeout 30s;
        proxy_send_timeout 30s;
        proxy_read_timeout 30s;

        # Handle WebSocket connections
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }

    # Static assets caching (if served directly)
    location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {
        proxy_pass http://secman_frontend;
        proxy_http_version 1.1;

        proxy_set_header Host $host;

        # Cache static assets
        expires 1y;
        add_header Cache-Control "public, immutable";
        access_log off;
    }
}
```

### Enable Nginx Site

```bash
# Test nginx configuration
sudo nginx -t

# Create symbolic link to enable site
sudo ln -s /etc/nginx/sites-available/secman /etc/nginx/sites-enabled/

# Remove default site (optional)
sudo rm /etc/nginx/sites-enabled/default

# Reload nginx
sudo systemctl reload nginx
```

---

## SSL/TLS Setup

### Option 1: Let's Encrypt (Recommended for Production)

```bash
# Install certbot
sudo apt install -y certbot python3-certbot-nginx  # Ubuntu/Debian
# OR
sudo dnf install -y certbot python3-certbot-nginx  # RHEL/CentOS

# Obtain certificate
sudo certbot --nginx -d secman.example.com

# Test auto-renewal
sudo certbot renew --dry-run

# Certbot will automatically modify your nginx config
```

### Option 2: Self-Signed Certificate (Development/Testing)

```bash
# Create SSL directory
sudo mkdir -p /etc/nginx/ssl

# Generate self-signed certificate
sudo openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
    -keyout /etc/nginx/ssl/secman.key \
    -out /etc/nginx/ssl/secman.crt \
    -subj "/C=US/ST=State/L=City/O=Organization/CN=secman.example.com"

# Set permissions
sudo chmod 600 /etc/nginx/ssl/secman.key
sudo chmod 644 /etc/nginx/ssl/secman.crt

# Reload nginx
sudo systemctl reload nginx
```

### Option 3: Commercial Certificate

```bash
# Create SSL directory
sudo mkdir -p /etc/nginx/ssl

# Copy your certificate files
sudo cp /path/to/certificate.crt /etc/nginx/ssl/secman.crt
sudo cp /path/to/private.key /etc/nginx/ssl/secman.key
sudo cp /path/to/ca-bundle.crt /etc/nginx/ssl/secman-ca-bundle.crt

# Update nginx config to include CA bundle
# In /etc/nginx/sites-available/secman, add:
# ssl_trusted_certificate /etc/nginx/ssl/secman-ca-bundle.crt;

# Set permissions
sudo chmod 600 /etc/nginx/ssl/secman.key
sudo chmod 644 /etc/nginx/ssl/secman.crt

# Reload nginx
sudo systemctl reload nginx
```

---

## Service Management

### Common Commands

```bash
# Backend Service
sudo systemctl start secman-backend     # Start
sudo systemctl stop secman-backend      # Stop
sudo systemctl restart secman-backend   # Restart
sudo systemctl status secman-backend    # Status
sudo systemctl enable secman-backend    # Enable on boot
sudo systemctl disable secman-backend   # Disable on boot

# Frontend Service
sudo systemctl start secman-frontend    # Start
sudo systemctl stop secman-frontend     # Stop
sudo systemctl restart secman-frontend  # Restart
sudo systemctl status secman-frontend   # Status
sudo systemctl enable secman-frontend   # Enable on boot
sudo systemctl disable secman-frontend  # Disable on boot

# Nginx
sudo systemctl start nginx              # Start
sudo systemctl stop nginx               # Stop
sudo systemctl reload nginx             # Reload config
sudo systemctl restart nginx            # Restart
sudo systemctl status nginx             # Status

# View logs
sudo journalctl -u secman-backend -f    # Backend logs (follow)
sudo journalctl -u secman-frontend -f   # Frontend logs (follow)
sudo tail -f /var/log/nginx/secman-access.log  # Nginx access
sudo tail -f /var/log/nginx/secman-error.log   # Nginx errors
```

### Deployment Updates

```bash
# Update application
sudo su - secman
cd /opt/secman/app

# Pull latest code
git pull origin main

# Rebuild backend
cd src/backendng
./gradlew clean shadowJar -x test

# Rebuild frontend
cd ../frontend
npm ci --production
npm run build

# Exit secman user
exit

# Restart services
sudo systemctl restart secman-backend
sudo systemctl restart secman-frontend

# Verify health
curl https://secman.example.com/health
```

---

## Security Hardening

### 1. Firewall Configuration

```bash
# UFW (Ubuntu/Debian)
sudo ufw allow 22/tcp      # SSH
sudo ufw allow 80/tcp      # HTTP
sudo ufw allow 443/tcp     # HTTPS
sudo ufw enable

# FirewallD (RHEL/CentOS)
sudo firewall-cmd --permanent --add-service=ssh
sudo firewall-cmd --permanent --add-service=http
sudo firewall-cmd --permanent --add-service=https
sudo firewall-cmd --reload

# Ensure application ports are NOT exposed externally
# Ports 8080 and 4321 should only be accessible via localhost
```

### 2. Fail2ban for Brute Force Protection

```bash
# Install fail2ban
sudo apt install -y fail2ban  # Ubuntu/Debian
sudo dnf install -y fail2ban  # RHEL/CentOS

# Create filter for SecMan
sudo nano /etc/fail2ban/filter.d/secman.conf
```

Add the following:

```ini
[Definition]
failregex = ^<HOST> .* "POST /api/auth/login HTTP.*" 401
ignoreregex =
```

Create jail configuration:

```bash
sudo nano /etc/fail2ban/jail.d/secman.conf
```

Add:

```ini
[secman]
enabled = true
port = http,https
filter = secman
logpath = /var/log/nginx/secman-access.log
maxretry = 5
bantime = 3600
findtime = 600
```

Enable fail2ban:

```bash
sudo systemctl enable fail2ban
sudo systemctl start fail2ban
```

### 3. Secure File Permissions

```bash
# Application directory
sudo chown -R secman:secman /opt/secman/app
sudo chmod -R 750 /opt/secman/app

# Environment files
sudo chmod 600 /opt/secman/*.env

# Nginx SSL certificates
sudo chmod 600 /etc/nginx/ssl/*.key
sudo chmod 644 /etc/nginx/ssl/*.crt
```

### 4. Database Security

```bash
# Connect to MariaDB
sudo mysql -u root -p

# Create admin user with limited privileges
CREATE USER 'secman_admin'@'localhost' IDENTIFIED BY 'STRONG_PASSWORD';
GRANT SELECT, INSERT, UPDATE, DELETE ON secman.* TO 'secman_admin'@'localhost';

# Disable remote root access
DELETE FROM mysql.user WHERE User='root' AND Host NOT IN ('localhost', '127.0.0.1', '::1');
FLUSH PRIVILEGES;
```

### 5. Regular Security Updates

```bash
# Ubuntu/Debian
sudo apt update
sudo apt upgrade -y

# RHEL/CentOS
sudo dnf update -y

# Enable automatic security updates (Ubuntu)
sudo apt install -y unattended-upgrades
sudo dpkg-reconfigure -plow unattended-upgrades
```

---

## Monitoring and Logs

### Application Logs

```bash
# Backend logs (systemd journal)
sudo journalctl -u secman-backend -f --since "1 hour ago"

# Frontend logs (systemd journal)
sudo journalctl -u secman-frontend -f --since "1 hour ago"

# Nginx access logs
sudo tail -f /var/log/nginx/secman-access.log

# Nginx error logs
sudo tail -f /var/log/nginx/secman-error.log

# MariaDB logs
sudo tail -f /var/log/mysql/error.log
```

### Log Rotation

Nginx logs are rotated automatically. For application logs:

```bash
# Create logrotate config for secman
sudo nano /etc/logrotate.d/secman
```

Add:

```
/var/log/secman/*.log {
    daily
    rotate 14
    compress
    delaycompress
    notifempty
    missingok
    create 0640 secman secman
    sharedscripts
    postrotate
        systemctl reload secman-backend > /dev/null 2>&1 || true
        systemctl reload secman-frontend > /dev/null 2>&1 || true
    endscript
}
```

### Health Monitoring

Create a simple monitoring script:

```bash
sudo nano /opt/secman/monitor.sh
```

Add:

```bash
#!/bin/bash

# Health check script for SecMan
BACKEND_URL="http://localhost:8080/health"
FRONTEND_URL="http://localhost:4321/"
EXTERNAL_URL="https://secman.example.com/health"

# Check backend
if ! curl -sf "$BACKEND_URL" > /dev/null; then
    echo "Backend health check failed!" | logger -t secman-monitor
    systemctl restart secman-backend
fi

# Check frontend
if ! curl -sf "$FRONTEND_URL" > /dev/null; then
    echo "Frontend health check failed!" | logger -t secman-monitor
    systemctl restart secman-frontend
fi

# Check external access
if ! curl -sf "$EXTERNAL_URL" > /dev/null; then
    echo "External access check failed!" | logger -t secman-monitor
fi
```

Make executable and add to cron:

```bash
sudo chmod +x /opt/secman/monitor.sh

# Add to crontab (run every 5 minutes)
sudo crontab -e
# Add: */5 * * * * /opt/secman/monitor.sh
```

---

## Troubleshooting

### Backend Won't Start

**Symptoms:** Backend service fails to start or crashes

**Solutions:**

1. Check logs:
   ```bash
   sudo journalctl -u secman-backend -n 100 --no-pager
   ```

2. **SDKMAN Users:** If you see "Failed to locate executable /usr/bin/java: No such file or directory":

   This means systemd can't find Java because you're using SDKMAN. You need to use Option B configuration.

   ```bash
   # First, find the correct Java path as the secman user
   sudo su - secman
   which java
   echo $JAVA_HOME
   exit

   # Then edit the service file and use the SDKMAN configuration (Option B)
   sudo nano /etc/systemd/system/secman-backend.service

   # Update the ExecStart line with the actual path:
   # ExecStart=/home/secman/.sdkman/candidates/java/current/bin/java $JAVA_OPTS -jar ...

   # Also add the SDKMAN environment variables:
   # Environment="SDKMAN_DIR=/home/secman/.sdkman"
   # Environment="PATH=/home/secman/.sdkman/candidates/java/current/bin:/usr/local/bin:/usr/bin:/bin"
   # Environment="JAVA_HOME=/home/secman/.sdkman/candidates/java/current"

   # Reload and restart
   sudo systemctl daemon-reload
   sudo systemctl restart secman-backend
   sudo systemctl status secman-backend
   ```

3. Verify database connectivity:
   ```bash
   mysql -u secman -p -h localhost secman
   ```

4. Check Java version (as the secman user):
   ```bash
   sudo su - secman
   java -version  # Must be 21
   exit
   ```

5. Verify JAR file exists and is executable:
   ```bash
   ls -lh /opt/secman/app/src/backendng/build/libs/
   # You should see: backendng-0.1-all.jar

   # Verify it's an executable JAR (should show "Main-Class")
   unzip -p /opt/secman/app/src/backendng/build/libs/backendng-0.1-all.jar META-INF/MANIFEST.MF | grep Main-Class

   # If JAR doesn't exist or has no Main-Class, rebuild with shadowJar:
   cd /opt/secman/app/src/backendng
   ./gradlew clean shadowJar -x test
   ```

6. Check port 8080 not in use:
   ```bash
   sudo netstat -tuln | grep 8080
   ```

### Frontend Won't Start

**Symptoms:** Frontend service fails or returns errors

**Solutions:**

1. Check logs:
   ```bash
   sudo journalctl -u secman-frontend -n 100 --no-pager
   ```

2. **Homebrew Users:** If you see "Failed to locate executable /usr/bin/node: No such file or directory":

   This means systemd can't find Node.js because you're using Homebrew. You need to use Option B configuration.

   ```bash
   # First, find the correct Node.js path as the secman user
   sudo su - secman
   which node
   echo $HOMEBREW_PREFIX
   exit

   # Then edit the service file and use the Homebrew configuration (Option B)
   sudo nano /etc/systemd/system/secman-frontend.service

   # Update the ExecStart line with the actual path:
   # ExecStart=/home/linuxbrew/.linuxbrew/bin/node /opt/secman/app/src/frontend/dist/server/entry.mjs

   # Also add the Homebrew environment variables:
   # Environment="HOMEBREW_PREFIX=/home/linuxbrew/.linuxbrew"
   # Environment="PATH=/home/linuxbrew/.linuxbrew/bin:/home/linuxbrew/.linuxbrew/sbin:/usr/local/bin:/usr/bin:/bin"

   # Reload and restart
   sudo systemctl daemon-reload
   sudo systemctl restart secman-frontend
   sudo systemctl status secman-frontend
   ```

3. Verify Node.js version (as the secman user):
   ```bash
   sudo su - secman
   node -v  # Should be 18.x or 20.x
   exit
   ```

4. Check if backend is running:
   ```bash
   curl http://localhost:8080/health
   ```

5. Verify build output exists:
   ```bash
   ls -lh /opt/secman/app/src/frontend/dist/server/
   # Should show: entry.mjs

   # If dist/ directory doesn't exist, rebuild frontend:
   cd /opt/secman/app/src/frontend
   npm run build
   ```

### Nginx 502 Bad Gateway

**Symptoms:** Nginx returns 502 error

**Solutions:**

1. Check if backend/frontend services are running:
   ```bash
   sudo systemctl status secman-backend
   sudo systemctl status secman-frontend
   ```

2. Check nginx error log:
   ```bash
   sudo tail -f /var/log/nginx/secman-error.log
   ```

3. Test upstream connectivity:
   ```bash
   curl http://localhost:8080/health
   curl http://localhost:4321/
   ```

4. Verify nginx configuration:
   ```bash
   sudo nginx -t
   ```

### SSL Certificate Issues

**Symptoms:** Browser shows SSL warnings

**Solutions:**

1. Verify certificate files exist:
   ```bash
   sudo ls -lh /etc/nginx/ssl/
   ```

2. Check certificate validity:
   ```bash
   openssl x509 -in /etc/nginx/ssl/secman.crt -noout -dates
   ```

3. Test SSL configuration:
   ```bash
   sudo nginx -t
   ```

4. For Let's Encrypt, renew certificate:
   ```bash
   sudo certbot renew --force-renewal
   ```

### Database Connection Issues

**Symptoms:** Backend logs show database connection errors

**Solutions:**

1. Verify MariaDB is running:
   ```bash
   sudo systemctl status mariadb
   ```

2. Test database connection:
   ```bash
   mysql -u secman -p -h localhost secman
   ```

3. Check database credentials in environment file:
   ```bash
   sudo cat /opt/secman/backend.env | grep DB_
   ```

4. Verify user permissions:
   ```sql
   SHOW GRANTS FOR 'secman'@'localhost';
   ```

### High Memory Usage

**Symptoms:** Application consuming too much RAM

**Solutions:**

1. Adjust Java heap size in backend service:
   ```bash
   sudo nano /etc/systemd/system/secman-backend.service
   # Modify JAVA_OPTS: -Xmx1g (reduce from 2g)
   sudo systemctl daemon-reload
   sudo systemctl restart secman-backend
   ```

2. Adjust Node.js memory in frontend service:
   ```bash
   sudo nano /etc/systemd/system/secman-frontend.service
   # Modify NODE_OPTIONS: --max-old-space-size=512
   sudo systemctl daemon-reload
   sudo systemctl restart secman-frontend
   ```

### File Upload Failures

**Symptoms:** File uploads fail with 413 or timeout errors

**Solutions:**

1. Increase nginx client_max_body_size:
   ```nginx
   # In /etc/nginx/sites-available/secman
   client_max_body_size 50M;  # Increase as needed
   ```

2. Increase backend timeout:
   ```nginx
   # In location /api/ block
   proxy_read_timeout 120s;
   ```

3. Restart nginx:
   ```bash
   sudo systemctl reload nginx
   ```

---

## Backup and Maintenance

### Database Backup

```bash
# Create backup script
sudo nano /opt/secman/backup-db.sh
```

Add:

```bash
#!/bin/bash
BACKUP_DIR="/opt/secman/backups/db"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
DB_NAME="secman"
DB_USER="secman"
DB_PASS="YOUR_DB_PASSWORD"

mkdir -p "$BACKUP_DIR"
mysqldump -u "$DB_USER" -p"$DB_PASS" "$DB_NAME" | gzip > "$BACKUP_DIR/secman_${TIMESTAMP}.sql.gz"

# Keep only last 7 days of backups
find "$BACKUP_DIR" -name "secman_*.sql.gz" -mtime +7 -delete

echo "Database backup completed: secman_${TIMESTAMP}.sql.gz"
```

Make executable and schedule:

```bash
sudo chmod +x /opt/secman/backup-db.sh

# Add to crontab (daily at 2 AM)
sudo crontab -e
# Add: 0 2 * * * /opt/secman/backup-db.sh
```

### Application Backup

```bash
# Backup application files
sudo tar -czf /opt/secman/backups/app_$(date +%Y%m%d).tar.gz \
    /opt/secman/app \
    /opt/secman/*.env \
    /etc/systemd/system/secman-*.service \
    /etc/nginx/sites-available/secman
```

### Restore from Backup

```bash
# Restore database
gunzip < /opt/secman/backups/db/secman_TIMESTAMP.sql.gz | mysql -u secman -p secman

# Restore application
sudo tar -xzf /opt/secman/backups/app_YYYYMMDD.tar.gz -C /
```

### Maintenance Window

For major updates:

```bash
# 1. Put application in maintenance mode (create maintenance page in nginx)
sudo nano /etc/nginx/sites-available/secman
# Add: return 503 "Under maintenance";

# 2. Reload nginx
sudo systemctl reload nginx

# 3. Backup database
/opt/secman/backup-db.sh

# 4. Stop services
sudo systemctl stop secman-frontend
sudo systemctl stop secman-backend

# 5. Perform updates
# ... your update process ...

# 6. Start services
sudo systemctl start secman-backend
sudo systemctl start secman-frontend

# 7. Verify health
curl http://localhost:8080/health

# 8. Remove maintenance mode from nginx and reload
sudo systemctl reload nginx
```

---

## Performance Tuning

### Nginx Optimization

Add to nginx configuration:

```nginx
# In http block of /etc/nginx/nginx.conf

# Worker processes (set to number of CPU cores)
worker_processes auto;
worker_rlimit_nofile 65535;

events {
    worker_connections 4096;
    use epoll;
    multi_accept on;
}

http {
    # Compression
    gzip on;
    gzip_vary on;
    gzip_min_length 1024;
    gzip_types text/plain text/css text/xml text/javascript application/json application/javascript application/xml+rss;

    # Connection pooling
    keepalive_timeout 65;
    keepalive_requests 100;

    # Buffering
    client_body_buffer_size 128k;
    client_header_buffer_size 1k;
    large_client_header_buffers 4 16k;

    # Caching
    open_file_cache max=10000 inactive=30s;
    open_file_cache_valid 60s;
    open_file_cache_min_uses 2;
    open_file_cache_errors on;
}
```

---

## Appendix

### Quick Reference

| Component | Port | Protocol | Service Name |
|-----------|------|----------|--------------|
| Nginx HTTP | 80 | HTTP | nginx |
| Nginx HTTPS | 443 | HTTPS | nginx |
| Backend API | 8080 | HTTP (internal) | secman-backend |
| Frontend SSR | 4321 | HTTP (internal) | secman-frontend |
| MariaDB | 3306 | MySQL (internal) | mariadb |

### Useful Commands Cheat Sheet

```bash
# Status checks
sudo systemctl status nginx secman-backend secman-frontend mariadb

# Restart all services
sudo systemctl restart secman-backend secman-frontend nginx

# View all logs
sudo journalctl -u secman-backend -u secman-frontend -f

# Test nginx config
sudo nginx -t && sudo nginx -s reload

# Check listening ports
sudo netstat -tuln | grep -E ':(80|443|8080|4321|3306)'

# Database quick connect
mysql -u secman -p secman

# Check disk space
df -h /opt/secman /var/log

# Check memory usage
free -h
```

---

## Support and Resources

- **Project Repository:** https://github.com/yourusername/secman
- **Documentation:** /opt/secman/app/docs/
- **Issue Tracker:** https://github.com/yourusername/secman/issues

---

**Document Version:** 1.0
**Last Updated:** 2025-11-04
**Maintainer:** SecMan DevOps Team
