-- File: V2__rollback_secchampion_to_champion.sql
-- Purpose: Rollback SECCHAMPION to CHAMPION
-- Feature: 025-role-based-access-control
-- Date: 2025-10-18
-- WARNING: Only use if migration needs to be reverted

-- Rollback SECCHAMPION to CHAMPION
UPDATE user_roles
SET role_name = 'CHAMPION'
WHERE role_name = 'SECCHAMPION';

-- Verify rollback (should return 0 rows after rollback)
-- SELECT * FROM user_roles WHERE role_name = 'SECCHAMPION';

-- Migration rolled back: SECCHAMPION → CHAMPION
