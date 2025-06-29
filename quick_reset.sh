#!/bin/bash

# Quick Database Reset - Direct MySQL Commands
# Database: secman, User: secman, Password: CHANGEME

echo "=== Quick Database Reset ==="
echo "This will drop all tables in the secman database"
echo ""

# One-liner to reset the database
mysql -h localhost -u secman -pCHANGEME secman -e "
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS assessment_content_snapshots;
DROP TABLE IF EXISTS standard_requirement_changes;
DROP TABLE IF EXISTS requirement_standard_versions;
DROP TABLE IF EXISTS standard_usecase_versions;
DROP TABLE IF EXISTS requirement_usecase_versions;
DROP TABLE IF EXISTS requirement_norm;
DROP TABLE IF EXISTS requirement_usecase;
DROP TABLE IF EXISTS requirement_standard;
DROP TABLE IF EXISTS standard_usecase;
DROP TABLE IF EXISTS response;
DROP TABLE IF EXISTS usecases_history;
DROP TABLE IF EXISTS norms_history;
DROP TABLE IF EXISTS standards_history;
DROP TABLE IF EXISTS requirements_history;
DROP TABLE IF EXISTS risk_assessment;
DROP TABLE IF EXISTS risk;
DROP TABLE IF EXISTS asset;
DROP TABLE IF EXISTS requirement;
DROP TABLE IF EXISTS norm;
DROP TABLE IF EXISTS usecase;
DROP TABLE IF EXISTS standard;
DROP TABLE IF EXISTS releases;
DROP TABLE IF EXISTS users;
DROP TABLE IF EXISTS play_evolutions;

SET FOREIGN_KEY_CHECKS = 1;

SELECT 'All tables dropped successfully' AS Status;
"

if [ $? -eq 0 ]; then
    echo "✅ Database reset completed!"
    echo "You can now restart the Play application to recreate tables."
else
    echo "❌ Reset failed. Check your MySQL connection and credentials."
fi