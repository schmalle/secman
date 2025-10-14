#!/bin/bash
# Script to fix user_mapping table schema
# Makes aws_account_id and domain columns nullable
# Feature: 017-user-mapping-management
# Date: 2025-10-13

echo "Fixing user_mapping table schema..."

# Method 1: If using Docker
docker exec -i $(docker ps -qf "name=secman.*database") mysql -uroot -proot secman_dev <<EOF
ALTER TABLE user_mapping MODIFY COLUMN aws_account_id VARCHAR(12) NULL;
ALTER TABLE user_mapping MODIFY COLUMN domain VARCHAR(255) NULL;
DESCRIBE user_mapping;
EOF

# Method 2: If using local MySQL
# mysql -u root -proot secman_dev <<EOF
# ALTER TABLE user_mapping MODIFY COLUMN aws_account_id VARCHAR(12) NULL;
# ALTER TABLE user_mapping MODIFY COLUMN domain VARCHAR(255) NULL;
# DESCRIBE user_mapping;
# EOF

echo "Migration complete!"
