#!/bin/bash

# Install Database Script for Secman
# This script will create the database and user for Secman application

# Database connection parameters
DB_HOST="localhost"
DB_PORT="3306"
DB_NAME="secman"
DB_USER="secman"
DB_PASS="CHANGEME"

echo "=== Secman database Install Script ==="
echo "This script will create:"
echo "  Database: $DB_NAME"
echo "  User: $DB_USER"
echo "  Host: $DB_HOST:$DB_PORT"
echo ""

# Check if mysql client is available
if ! command -v mysql &> /dev/null; then
    echo "ERROR: MySQL client is not installed or not in PATH"
    echo "Please install MySQL client and try again"
    exit 1
fi

# Check if SQL file exists
SQL_FILE="install.sql"
if [ ! -f "$SQL_FILE" ]; then
    echo "ERROR: SQL file '$SQL_FILE' not found"
    echo "Make sure you're running this script from the directory containing the SQL file"
    exit 1
fi

echo "This will create the database and user for Secman if they don't already exist."
echo ""
read -p "Do you want to proceed? (y/N): " confirmation

if [[ ! "$confirmation" =~ ^[Yy]$ ]]; then
    echo "Operation cancelled."
    exit 0
fi

echo ""
echo "Connecting to MySQL server and executing install script..."
echo "Note: You may be prompted for the MySQL root password"

# Execute the SQL script as root user
mysql -h "$DB_HOST" -P "$DB_PORT" -u root -p < "$SQL_FILE"

# Check if the command was successful
if [ $? -eq 0 ]; then
    echo ""
    echo "✅ Database installation completed successfully!"
    echo ""
    echo "Database setup:"
    echo "  Database: $DB_NAME"
    echo "  Username: $DB_USER"
    echo "  Password: $DB_PASS"
    echo "  Host: $DB_HOST"
    echo ""
    echo "Next steps:"
    echo "1. Update your application configuration if needed"
    echo "2. Start your Play Framework application"
    echo "3. The application will automatically run database evolutions"
    echo "4. This will create all tables with the latest schema"
    echo ""
else
    echo ""
    echo "❌ Database installation failed!"
    echo "Please check the error messages above and try again"
    echo ""
    echo "Common issues:"
    echo "- MySQL server not running"
    echo "- Incorrect root password"
    echo "- Insufficient privileges"
    echo "- User or database already exists (this is usually OK)"
    exit 1
fi