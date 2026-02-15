# Secman Database Installation

This directory contains scripts for setting up the Secman database on a fresh MariaDB installation.

## Scripts

### install.sql
SQL script that creates the Secman database and application database user.

**What it does:**
- Creates database `secman` with `utf8mb4` charset and `utf8mb4_unicode_ci` collation
- Creates user `secman` with password `CHANGEME` (localhost-only access)
- Grants required privileges (SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES) on the `secman` database
- Flushes privileges to apply changes

**Requirements:**
- MariaDB 10.5+ (for `CREATE USER IF NOT EXISTS` support)
- MariaDB root access to create the database and user

### installdb.sh
Interactive wrapper script for executing install.sql with error checking.

**Usage:**
```bash
cd scripts/install/db
./installdb.sh
```

The script will:
- Check for MySQL/MariaDB client availability
- Prompt for confirmation before proceeding
- Execute the SQL script as MariaDB root user
- Provide status feedback and next steps

## Database Configuration

The scripts create a database setup matching the application defaults:

- **Database:** secman (utf8mb4 / utf8mb4_unicode_ci)
- **Username:** secman
- **Password:** CHANGEME
- **Host:** localhost
- **Port:** 3306 (default)

This configuration is used by:
- `src/backendng/src/main/resources/application.yml` - Backend database configuration
- `docker-compose.yml` - Docker Compose database service
- All database management scripts in this directory

Override via environment variables: `DB_USERNAME`, `DB_PASSWORD`, `DB_CONNECT`

## Installation Process

1. Ensure MariaDB server is running
2. Run the install script: `./installdb.sh`
3. Enter MariaDB root password when prompted
4. Start the Secman backend application
5. Flyway migrations and Hibernate auto-update will create all tables automatically
6. On first startup (when no users exist), a default admin user is created:
   - **Username:** admin
   - **Password:** password
   - **Roles:** ADMIN, USER
7. Log in and change the default admin password immediately

## Default Admin User

The application automatically creates a default admin user on first startup if the `users` table is empty. This is handled by the `DefaultAdminBootstrapper` component in the backend.

| Field    | Value           |
|----------|-----------------|
| Username | admin           |
| Email    | admin@localhost |
| Password | password        |
| Roles    | ADMIN, USER     |

**IMPORTANT:** Change the default admin password immediately after first login.

## Security Notes

- **Database password:** Change `CHANGEME` to a strong password in production. Set via `DB_PASSWORD` environment variable.
- **Admin password:** The default admin password (`password`) must be changed immediately after first login.
- **Privileges:** The `secman` database user is granted only the privileges needed for application operation (no SUPER, GRANT, or global privileges).
- **Localhost only:** The database user is restricted to localhost connections.
- See `docs/ENVIRONMENT.md` for the full production security checklist.
