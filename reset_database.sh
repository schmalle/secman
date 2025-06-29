#!/bin/bash

# Reset Database Script for Secman
# This script will execute the SQL commands to completely reset the database

# Database connection parameters
DB_HOST="localhost"
DB_PORT="3306"
DB_NAME="secman"
DB_USER="secman"
DB_PASS="CHANGEME"

echo "=== Secman Database Reset Script ==="
echo "Database: $DB_NAME"
echo "User: $DB_USER"
echo "Host: $DB_HOST:$DB_PORT"
echo ""

# Check if mysql client is available
if ! command -v mysql &> /dev/null; then
    echo "ERROR: MySQL client is not installed or not in PATH"
    echo "Please install MySQL client and try again"
    exit 1
fi

# Check if SQL file exists
SQL_FILE="reset_database.sql"
if [ ! -f "$SQL_FILE" ]; then
    echo "ERROR: SQL file '$SQL_FILE' not found"
    echo "Make sure you're running this script from the directory containing the SQL file"
    exit 1
fi

echo "⚠️  WARNING: This will DELETE ALL DATA in the '$DB_NAME' database!"
echo "This action cannot be undone."
echo ""
read -p "Are you sure you want to proceed? (type 'YES' to confirm): " confirmation

if [ "$confirmation" != "YES" ]; then
    echo "Operation cancelled."
    exit 0
fi

echo ""
echo "Connecting to database and executing reset script..."

# Execute the SQL script
mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p"$DB_PASS" "$DB_NAME" < "$SQL_FILE"

# Check if the command was successful
if [ $? -eq 0 ]; then
    echo ""
    echo "✅ Database reset completed successfully!"
    echo ""
    echo "Next steps:"
    echo "1. Restart your Play Framework application"
    echo "2. The application will automatically run database evolutions"
    echo "3. This will recreate all tables with the latest schema"
    echo ""
else
    echo ""
    echo "❌ Database reset failed!"
    echo "Please check the error messages above and try again"
    echo ""
    echo "Common issues:"
    echo "- Wrong database credentials"
    echo "- Database server not running"
    echo "- Insufficient privileges"
    exit 1
fi