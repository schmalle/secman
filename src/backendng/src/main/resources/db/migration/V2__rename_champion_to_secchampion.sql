-- File: V2__rename_champion_to_secchampion.sql
-- Purpose: Rename CHAMPION role to SECCHAMPION for existing users
-- Feature: 025-role-based-access-control
-- Date: 2025-10-18
-- Constitutional Compliance: Principle VI (Schema Evolution)

-- Update existing CHAMPION roles to SECCHAMPION
UPDATE user_roles
SET role_name = 'SECCHAMPION'
WHERE role_name = 'CHAMPION';

-- Verify migration (should return 0 rows after migration)
-- SELECT * FROM user_roles WHERE role_name = 'CHAMPION';

-- Add comment for audit trail
-- Migration completed: CHAMPION → SECCHAMPION
-- Feature: Role-Based Access Control - RISK, REQ, and SECCHAMPION Roles
