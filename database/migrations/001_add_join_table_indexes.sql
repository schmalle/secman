-- Migration: Add indexes to join tables for performance optimization
-- Feature: Database Structure Optimization
-- Date: 2025-11-19
-- Description: Adds missing indexes to many-to-many join tables to improve query performance

-- ============================================================================
-- JOIN TABLE INDEXES
-- ============================================================================

-- asset_workgroups join table
-- These indexes significantly improve workgroup-based asset filtering
CREATE INDEX IF NOT EXISTS idx_asset_workgroups_asset
ON asset_workgroups(asset_id);

CREATE INDEX IF NOT EXISTS idx_asset_workgroups_workgroup
ON asset_workgroups(workgroup_id);

-- Composite index for bidirectional lookups
CREATE INDEX IF NOT EXISTS idx_asset_workgroups_composite
ON asset_workgroups(workgroup_id, asset_id);

-- user_workgroups join table
-- These indexes improve user access control queries
CREATE INDEX IF NOT EXISTS idx_user_workgroups_user
ON user_workgroups(user_id);

CREATE INDEX IF NOT EXISTS idx_user_workgroups_workgroup
ON user_workgroups(workgroup_id);

-- Composite index for bidirectional lookups
CREATE INDEX IF NOT EXISTS idx_user_workgroups_composite
ON user_workgroups(workgroup_id, user_id);

-- user_roles ElementCollection table
-- These indexes improve role-based authorization queries
CREATE INDEX IF NOT EXISTS idx_user_roles_user
ON user_roles(user_id);

CREATE INDEX IF NOT EXISTS idx_user_roles_role
ON user_roles(role_name);

-- Composite index for role checks
CREATE INDEX IF NOT EXISTS idx_user_roles_composite
ON user_roles(user_id, role_name);

-- ============================================================================
-- VERIFICATION QUERIES (optional - for testing)
-- ============================================================================

-- To verify indexes were created, run:
-- SHOW INDEX FROM asset_workgroups;
-- SHOW INDEX FROM user_workgroups;
-- SHOW INDEX FROM user_roles;

-- ============================================================================
-- ROLLBACK (if needed)
-- ============================================================================

-- To remove these indexes:
-- DROP INDEX IF EXISTS idx_asset_workgroups_asset ON asset_workgroups;
-- DROP INDEX IF EXISTS idx_asset_workgroups_workgroup ON asset_workgroups;
-- DROP INDEX IF EXISTS idx_asset_workgroups_composite ON asset_workgroups;
-- DROP INDEX IF EXISTS idx_user_workgroups_user ON user_workgroups;
-- DROP INDEX IF EXISTS idx_user_workgroups_workgroup ON user_workgroups;
-- DROP INDEX IF EXISTS idx_user_workgroups_composite ON user_workgroups;
-- DROP INDEX IF EXISTS idx_user_roles_user ON user_roles;
-- DROP INDEX IF EXISTS idx_user_roles_role ON user_roles;
-- DROP INDEX IF EXISTS idx_user_roles_composite ON user_roles;
