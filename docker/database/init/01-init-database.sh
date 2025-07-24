#!/bin/bash
set -e

echo "Starting database initialization..."

# Create the secman database if it doesn't exist
mysql -u root -p"$MYSQL_ROOT_PASSWORD" <<-EOSQL
    CREATE DATABASE IF NOT EXISTS secman CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
EOSQL

echo "Database 'secman' created successfully."

# Create the secman user and grant privileges
mysql -u root -p"$MYSQL_ROOT_PASSWORD" <<-EOSQL
    CREATE USER IF NOT EXISTS 'secman'@'%' IDENTIFIED BY '$MYSQL_PASSWORD';
    GRANT ALL PRIVILEGES ON secman.* TO 'secman'@'%';
    FLUSH PRIVILEGES;
EOSQL

echo "User 'secman' created and privileges granted."

echo "Database initialization completed."