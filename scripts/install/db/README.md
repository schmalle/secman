# Secman Database Installation

This directory contains scripts for setting up and managing the Secman database.

## Scripts

### install.sql
SQL script that creates the Secman database and user if they don't exist.

**What it does:**
- Creates database `secman` if not exists
- Creates user `secman` with password `CHANGEME` if not exists  
- Grants full privileges to `secman` user on `secman` database via localhost
- Flushes privileges to ensure changes take effect

**Requirements:**
- MySQL 5.7+ or MariaDB 10.1+ (for `CREATE USER IF NOT EXISTS` support)
- MySQL root access to create database and user

### install.sh
Wrapper script for easy execution of install.sql with error checking.

**Usage:**
```bash
cd scripts
./installdb.sh
```

The script will:
- Check for MySQL client availability
- Prompt for confirmation before proceeding
- Execute the SQL script as MySQL root user
- Provide status feedback and next steps


## Database Configuration

The scripts create a database setup that matches the application configuration:

- **Database:** secman
- **Username:** secman  
- **Password:** CHANGEME
- **Host:** localhost
- **Port:** 3306 (default)

This configuration is used by:
- `src/backend/conf/application.conf` - Main application database config
- All database management scripts in this directory

## Installation Process

1. Ensure MySQL/MariaDB server is running
2. Run the install script: `./install.sh`
3. Enter MySQL root password when prompted
4. Start the Secman application
5. Play Framework will automatically run database evolutions to create tables

## Security Note

Remember to change the default password `CHANGEME` in production environments.