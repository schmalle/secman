#!/bin/bash

# Quick Database Reset - Direct MySQL Commands
# Database: secman, User: secman, Password: CHANGEME

echo "=== Quick Database Reset ==="
echo "This will drop all tables in the secman database"
echo ""

# Get all table names and drop them dynamically
TABLES=$(mysql -h localhost -u secman -pCHANGEME secman -e "SHOW TABLES;" -s | tail -n +1)

if [ -z "$TABLES" ]; then
    echo "No tables found in the secman database."
    exit 0
fi

echo "Found tables to drop:"
echo "$TABLES"
echo ""

# Generate DROP statements for all tables
DROP_STATEMENTS=""
for table in $TABLES; do
    DROP_STATEMENTS="${DROP_STATEMENTS}DROP TABLE IF EXISTS \`$table\`;"$'\n'
done

# Execute the drop statements
mysql -h localhost -u secman -pCHANGEME secman -e "
SET FOREIGN_KEY_CHECKS = 0;

$DROP_STATEMENTS

SET FOREIGN_KEY_CHECKS = 1;

SELECT 'All tables dropped successfully' AS Status;
"

if [ $? -eq 0 ]; then
    echo "✅ Database reset completed!"
    echo "You can now restart the Play application to recreate tables."
else
    echo "❌ Reset failed. Check your MySQL connection and credentials."
fi