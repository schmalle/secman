# Secman Installation Guide

**Last Updated:** 2026-04-03

Complete installation instructions for new users of the Secman security management application.

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Quick Start (Development)](#quick-start-development)
3. [Step-by-Step Installation](#step-by-step-installation)
4. [Default Credentials](#default-credentials)
5. [Configuration Reference](#configuration-reference)
6. [Production Deployment](#production-deployment)
7. [CLI Tool Setup](#cli-tool-setup)
8. [Verification](#verification)
9. [Troubleshooting](#troubleshooting)

---

## Prerequisites

### Required Software

| Software   | Version   | Purpose                |
|------------|-----------|------------------------|
| Java (JDK) | 21+      | Backend runtime        |
| Node.js    | 20+      | Frontend runtime       |
| npm        | 10+      | Frontend dependencies  |
| MariaDB    | 11.4+    | Database               |
| Git        | 2.x+     | Source code management |

### System Requirements

| Resource | Minimum | Recommended |
|----------|---------|-------------|
| CPU      | 2 cores | 4+ cores    |
| RAM      | 4 GB    | 8+ GB       |
| Disk     | 20 GB   | 50+ GB SSD  |

### Install Prerequisites

**Ubuntu/Debian:**

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk git build-essential

# Node.js 20.x
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs

# MariaDB 11.4
curl -LsS https://r.mariadb.com/downloads/mariadb_repo_setup | sudo bash -s -- --mariadb-server-version=11.4
sudo apt update && sudo apt install -y mariadb-server mariadb-client
```

**Amazon Linux 2023 / RHEL:**

```bash
sudo dnf update -y
sudo dnf groupinstall "Development Tools" -y
sudo dnf install -y java-21-amazon-corretto-devel git

# Node.js 20.x
curl -fsSL https://rpm.nodesource.com/setup_20.x | sudo bash -
sudo dnf install -y nodejs

# MariaDB 11.4
sudo curl -sS https://downloads.mariadb.com/MariaDB/mariadb_repo_setup | sudo bash -s -- --mariadb-server-version="mariadb-11.4"
sudo dnf install -y MariaDB-server MariaDB-client
```

**macOS (Homebrew):**

```bash
brew install openjdk@21 node@20 mariadb@11.4 git
```

### Verify Installations

```bash
java -version      # Should show Java 21+
node -v            # Should show v20.x+
npm -v             # Should show v10.x+
mysql --version    # Should show MariaDB 11.4+
git --version      # Should show 2.x+
```

---

## Quick Start (Development)

For those who want to get up and running fast:

```bash
# 1. Clone the repository
git clone https://github.com/schmalle/secman.git
cd secman

# 2. Start and configure MariaDB
sudo systemctl start mariadb
sudo systemctl enable mariadb
cd scripts/install/db && ./installdb.sh && cd ../../..

# 3. Start the backend (Terminal 1)
cd src/backendng
./gradlew run

# 4. Start the frontend (Terminal 2)
cd src/frontend
npm install
npm run dev

# 5. Open http://localhost:4321 in your browser
# 6. Check the backend terminal output for the admin password (see below)
```

> **Important:** On first startup, the backend logs the auto-generated admin password to the console. See [Default Credentials](#default-credentials) for details.

---

## Step-by-Step Installation

### Step 1: Clone the Repository

```bash
git clone https://github.com/schmalle/secman.git
cd secman
```

### Step 2: Set Up MariaDB

Start the MariaDB service:

```bash
sudo systemctl start mariadb
sudo systemctl enable mariadb

# Secure the installation (set root password, remove test DB, etc.)
sudo mysql_secure_installation
```

Run the database installation script:

```bash
cd scripts/install/db
./installdb.sh
cd ../../..
```

This script creates:
- **Database:** `secman` (UTF-8 with full Unicode support)
- **Database User:** `secman@localhost`
- **Database Password:** `CHANGEME` (change this for production!)

The script will prompt for your MariaDB root password and ask for confirmation before proceeding.

**Alternatively**, you can set up the database manually:

```bash
sudo mysql -u root -p
```

```sql
CREATE DATABASE secman CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'secman'@'localhost' IDENTIFIED BY 'YOUR_SECURE_PASSWORD';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES
    ON secman.* TO 'secman'@'localhost';
FLUSH PRIVILEGES;
EXIT;
```

> **Note:** If you use a custom database password, set the `DB_PASSWORD` environment variable before starting the backend (see [Configuration Reference](#configuration-reference)).

### Step 3: Build and Start the Backend

```bash
cd src/backendng

# Option A: Run in development mode (with hot-reload)
./gradlew run

# Option B: Build a production JAR
./gradlew shadowJar -x test
java -jar build/libs/backendng-0.1-all.jar
```

The backend starts on **http://localhost:8080**.

On first startup, Flyway migrations and Hibernate auto-update will create all required database tables automatically. No manual schema setup is needed.

### Step 4: Install and Start the Frontend

```bash
cd src/frontend
npm install
npm run dev
```

The frontend starts on **http://localhost:4321**.

### Step 5: Log In

Open **http://localhost:4321** in your browser and log in with the default admin credentials (see next section).

---

## Default Credentials

### Default Admin User

On **first startup**, when no users exist in the database, Secman automatically creates a default admin account:

| Field    | Value              |
|----------|--------------------|
| Username | `admin`            |
| Email    | `admin@localhost`  |
| Password | *randomly generated (20 characters)* |
| Roles    | ADMIN, USER        |

### Finding the Admin Password

The auto-generated password is printed to the **backend console output** on first startup. Look for this log message:

```
==========================================================
  DEFAULT ADMIN USER CREATED
  Username: admin
  Password: <20-character random password> (CHANGE IMMEDIATELY!)
==========================================================
```

> **Important:** Copy this password immediately. It is only displayed once during the initial startup. If you miss it, you will need to reset the database (see [Troubleshooting](#troubleshooting)).

### After First Login

1. Log in at **http://localhost:4321** using `admin` and the generated password
2. Navigate to your user profile
3. **Change the default password immediately**
4. Optionally set up MFA (Multi-Factor Authentication) via WebAuthn/Passkeys

### Additional Users

After logging in as admin, you can:
- Create additional users via the admin panel
- Configure OAuth2/OIDC identity providers for SSO (GitHub, Microsoft Azure AD, etc.)
- Import users via CSV/Excel upload

---

## Configuration Reference

Secman is configured through environment variables. Set these before starting the backend.

### Essential Variables

```bash
# Database (required)
export DB_CONNECT=jdbc:mariadb://localhost:3306/secman
export DB_USERNAME=secman
export DB_PASSWORD=CHANGEME        # Change this!

# Security (required for production)
export JWT_SECRET=$(openssl rand -base64 32)
export SECMAN_ENCRYPTION_PASSWORD=$(openssl rand -hex 32)
export SECMAN_ENCRYPTION_SALT=$(openssl rand -hex 8)
```

### Optional Variables

```bash
# Email / SMTP
export SMTP_HOST=smtp.example.com
export SMTP_PORT=587
export SMTP_USERNAME=noreply@yourdomain.com
export SMTP_PASSWORD=your_smtp_password
export SMTP_FROM_ADDRESS=noreply@yourdomain.com
export SMTP_FROM_NAME="Security Management System"
export SMTP_ENABLE_TLS=true

# URLs (for production behind a reverse proxy)
export SECMAN_BACKEND_URL=https://api.yourdomain.com
export FRONTEND_URL=https://secman.yourdomain.com

# OAuth (optional - for SSO)
export GITHUB_CLIENT_ID=your_client_id
export GITHUB_CLIENT_SECRET=your_client_secret

# Cookie security (enable in production with HTTPS)
export SECMAN_AUTH_COOKIE_SECURE=true
```

### Using an Environment File

You can also use the provided `.env.example` template:

```bash
cp .env.example .env
# Edit .env with your values
```

For a complete list of all environment variables, see [docs/ENVIRONMENT.md](docs/ENVIRONMENT.md).

---

## Production Deployment

For production environments, additional steps are recommended:

### 1. Generate Secure Secrets

```bash
# JWT signing secret (32 bytes, base64 encoded)
openssl rand -base64 32

# Encryption password (64 hex characters)
openssl rand -hex 32

# Encryption salt (16 hex characters)
openssl rand -hex 8
```

### 2. Create a System User

```bash
sudo useradd -r -m -d /opt/secman -s /bin/bash secman
```

### 3. Create Environment File

```bash
sudo mkdir -p /etc/secman
sudo nano /etc/secman/backend.env
```

Add your production configuration (see [Configuration Reference](#configuration-reference)), then secure the file:

```bash
sudo chown root:secman /etc/secman/backend.env
sudo chmod 640 /etc/secman/backend.env
```

### 4. Build for Production

```bash
# Backend
cd src/backendng
./gradlew shadowJar -x test
# Output: build/libs/backendng-0.1-all.jar

# Frontend
cd src/frontend
npm ci --production
npm run build
# Output: dist/server/entry.mjs
```

### 5. Set Up Systemd Services

Create `/etc/systemd/system/secman-backend.service`:

```ini
[Unit]
Description=Secman Backend
After=network.target mariadb.service
Requires=mariadb.service

[Service]
Type=simple
User=secman
Group=secman
EnvironmentFile=/etc/secman/backend.env
WorkingDirectory=/opt/secman/app
ExecStart=/usr/bin/java -jar src/backendng/build/libs/backendng-0.1-all.jar
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Create `/etc/systemd/system/secman-frontend.service`:

```ini
[Unit]
Description=Secman Frontend
After=network.target

[Service]
Type=simple
User=secman
Group=secman
EnvironmentFile=/etc/secman/frontend.env
WorkingDirectory=/opt/secman/app/src/frontend
ExecStart=/usr/bin/node dist/server/entry.mjs
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Enable and start:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now secman-backend secman-frontend
```

### 6. Set Up Nginx Reverse Proxy

Install Nginx and configure it as a reverse proxy with SSL termination. A complete Nginx configuration example is available in [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).

### 7. Security Hardening Checklist

- [ ] Change all default passwords (database, admin user)
- [ ] Generate unique JWT_SECRET, encryption password, and salt
- [ ] Enable HTTPS with valid SSL/TLS certificates
- [ ] Set `SECMAN_AUTH_COOKIE_SECURE=true`
- [ ] Restrict database user to localhost access only
- [ ] Configure firewall rules (only expose ports 80/443)
- [ ] Set up regular database backups
- [ ] Configure SMTP for email notifications

For the complete production deployment guide, see [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).

---

## CLI Tool Setup

Secman includes a command-line tool for CrowdStrike integration, notifications, and data management.

### Build the CLI

```bash
./gradlew :cli:shadowJar
```

### Usage

```bash
# Show all commands
./bin/secman help

# Query CrowdStrike servers (dry run)
./bin/secman query servers --dry-run

# Send vulnerability notifications (dry run)
./bin/secman send-notifications --dry-run

# Manage user mappings
./bin/secman manage-user-mappings --help

# Export requirements
./bin/secman export-requirements --format xlsx

# Add a requirement
./bin/secman add-requirement --shortreq "New security requirement"

# Add a vulnerability
./bin/secman add-vulnerability --hostname server01 --cve CVE-2024-1234 --criticality HIGH
```

---

## Verification

After installation, verify everything is working:

### 1. Check Backend Health

```bash
curl http://localhost:8080/health
# Expected: {"status":"UP"}
```

### 2. Check Frontend

Open **http://localhost:4321** in your browser. You should see the login page.

### 3. Test Login

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"YOUR_GENERATED_PASSWORD"}'
# Expected: JSON with user details and roles
```

### 4. Check Memory/JVM Status

```bash
curl http://localhost:8080/memory
# Returns JVM heap metrics (used, max, free, total in MB)
```

---

## Troubleshooting

### "Cannot connect to database"

- Verify MariaDB is running: `sudo systemctl status mariadb`
- Check credentials: `mysql -u secman -p secman`
- Verify the `DB_CONNECT`, `DB_USERNAME`, and `DB_PASSWORD` environment variables

### "I missed the admin password"

If you missed the auto-generated admin password from the console output, reset the database:

```bash
cd scripts
./reset_database.sh
```

Then restart the backend. A new admin password will be generated and displayed.

Alternatively, you can manually reset via SQL:

```bash
mysql -u root -p secman
```

```sql
-- Delete all users to trigger admin re-creation on next startup
DELETE FROM user_roles;
DELETE FROM users;
```

Then restart the backend.

### "Port 8080/4321 already in use"

```bash
# Find what's using the port
lsof -i :8080
lsof -i :4321

# Kill the process or change the port
# Backend: set MICRONAUT_SERVER_PORT=8081
# Frontend: edit src/frontend/astro.config.mjs
```

### "Gradle build fails"

```bash
# Ensure Java 21 is active
java -version

# Clean and rebuild
./gradlew clean build

# Skip tests if needed (development only)
./gradlew build -x test
```

### "npm install fails"

```bash
# Clear npm cache
npm cache clean --force

# Delete node_modules and reinstall
cd src/frontend
rm -rf node_modules package-lock.json
npm install
```

### "Flyway migration error"

If Flyway reports migration conflicts after an upgrade:

```bash
# Check migration status
./gradlew flywayInfo

# Repair checksum mismatches
./gradlew flywayRepair
```

### Rate Limiting / Account Lockout

After 5 failed login attempts, the account is locked for 15 minutes. Wait or restart the backend to clear the lockout.

---

## Further Documentation

- [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) - Complete production deployment guide
- [docs/ENVIRONMENT.md](docs/ENVIRONMENT.md) - Full environment variables reference
- [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) - Extended troubleshooting guide
- [CLAUDE.md](CLAUDE.md) - Developer reference and architecture overview
