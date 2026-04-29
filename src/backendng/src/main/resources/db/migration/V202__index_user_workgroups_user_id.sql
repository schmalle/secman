-- V202__index_user_workgroups_user_id.sql
-- Defensive index on user_workgroups.user_id used by AssetRepository.findAccessibleAssets
-- subqueries (rules #1 and #9). MariaDB normally creates this as part of the FK
-- constraint, but Hibernate-managed @JoinTable behavior can vary; this CREATE INDEX
-- IF NOT EXISTS is a no-op when the index is already present.

CREATE INDEX IF NOT EXISTS idx_user_workgroups_user_id ON user_workgroups (user_id);
