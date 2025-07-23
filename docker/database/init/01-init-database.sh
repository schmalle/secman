#!/bin/bash
set -e

echo "Initializing Secman database..."

# Create the secman database and user
mysql -u root -p"$MYSQL_ROOT_PASSWORD" <<-EOSQL
    CREATE DATABASE IF NOT EXISTS secman CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    CREATE USER IF NOT EXISTS 'secman'@'%' IDENTIFIED BY '$MYSQL_PASSWORD';
    GRANT ALL PRIVILEGES ON secman.* TO 'secman'@'%';
    FLUSH PRIVILEGES;
EOSQL

echo "Database initialization completed successfully."