-- V197__widen_native_enum_columns_to_varchar.sql
-- Convert every native MariaDB ENUM column to VARCHAR(50) so adding a value to
-- the corresponding Kotlin @Enumerated enum never requires a follow-up
-- migration. Hibernate auto-update is conservative and will not widen ENUM
-- value lists on subsequent restarts; once a column is VARCHAR, that risk is
-- gone for good.
--
-- The acute trigger: user_roles.role was enum('ADMIN','USER') while
-- User.Role declares 9 values — any attempt to write VULN/RELEASE_MANAGER/
-- REQ/REQADMIN/RISK/SECCHAMPION/REPORT failed with MariaDB error 1265.
--
-- VARCHAR(50) covers every current value (longest is 'CAPABILITIES_REQUEST'
-- and 'VERIFICATION_PENDING' at 20 chars) with comfortable headroom.
--
-- Idempotency: MODIFY COLUMN with the same target type is a no-op.
-- If V197 was partially applied previously, re-running is safe.

-- ============================================================================
-- One ALTER per ENUM column found via:
--   SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE() AND DATA_TYPE = 'enum';
-- ============================================================================

ALTER TABLE admin_summary_log               MODIFY COLUMN status                VARCHAR(50) NOT NULL;
ALTER TABLE alignment_reviewer              MODIFY COLUMN status                VARCHAR(50) NOT NULL;
ALTER TABLE alignment_session               MODIFY COLUMN review_scope          VARCHAR(50) NOT NULL;
ALTER TABLE alignment_session               MODIFY COLUMN status                VARCHAR(50) NOT NULL;
ALTER TABLE alignment_snapshot              MODIFY COLUMN change_type           VARCHAR(50) NOT NULL;
ALTER TABLE asset                           MODIFY COLUMN criticality           VARCHAR(50) NULL;
ALTER TABLE asset                           MODIFY COLUMN network_zone          VARCHAR(50) NULL;
ALTER TABLE asset_deletion_audit_log        MODIFY COLUMN operation_type        VARCHAR(50) NOT NULL;
ALTER TABLE auth_audit_log                  MODIFY COLUMN event_type            VARCHAR(50) NOT NULL;
ALTER TABLE classification_session          MODIFY COLUMN final_classification  VARCHAR(50) NULL;
ALTER TABLE classification_session          MODIFY COLUMN session_status        VARCHAR(50) NOT NULL;
ALTER TABLE decision_tree_node              MODIFY COLUMN classification_result VARCHAR(50) NULL;
ALTER TABLE decision_tree_node              MODIFY COLUMN node_type             VARCHAR(50) NOT NULL;
ALTER TABLE demand                          MODIFY COLUMN demand_type           VARCHAR(50) NOT NULL;
ALTER TABLE demand                          MODIFY COLUMN priority              VARCHAR(50) NOT NULL;
ALTER TABLE demand                          MODIFY COLUMN status                VARCHAR(50) NOT NULL;
ALTER TABLE demand_classification_result    MODIFY COLUMN classification        VARCHAR(50) NOT NULL;
ALTER TABLE email_configs                   MODIFY COLUMN provider              VARCHAR(50) NOT NULL;
ALTER TABLE email_notification_logs         MODIFY COLUMN status                VARCHAR(50) NOT NULL;
ALTER TABLE exception_request_audit         MODIFY COLUMN event_type            VARCHAR(50) NOT NULL;
ALTER TABLE exception_request_audit         MODIFY COLUMN severity              VARCHAR(50) NOT NULL;
ALTER TABLE export_jobs                     MODIFY COLUMN export_type           VARCHAR(50) NOT NULL;
ALTER TABLE export_jobs                     MODIFY COLUMN status                VARCHAR(50) NOT NULL;
ALTER TABLE identity_providers              MODIFY COLUMN type                  VARCHAR(50) NOT NULL;
ALTER TABLE materialized_view_refresh_job   MODIFY COLUMN status                VARCHAR(50) NOT NULL;
ALTER TABLE mcp_audit_logs                  MODIFY COLUMN event_type            VARCHAR(50) NOT NULL;
ALTER TABLE mcp_audit_logs                  MODIFY COLUMN operation             VARCHAR(50) NULL;
ALTER TABLE mcp_audit_logs                  MODIFY COLUMN severity              VARCHAR(50) NOT NULL;
ALTER TABLE mcp_sessions                    MODIFY COLUMN connection_type       VARCHAR(50) NOT NULL;
ALTER TABLE migration_log                   MODIFY COLUMN status                VARCHAR(50) NOT NULL;
ALTER TABLE notification_log                MODIFY COLUMN notification_type     VARCHAR(50) NOT NULL;
ALTER TABLE response                        MODIFY COLUMN answer                VARCHAR(50) NOT NULL;
ALTER TABLE response                        MODIFY COLUMN answer_type           VARCHAR(50) NULL;
ALTER TABLE risk_assessment                 MODIFY COLUMN assessment_basis_type VARCHAR(50) NOT NULL;
ALTER TABLE test_email_accounts             MODIFY COLUMN provider              VARCHAR(50) NOT NULL;
ALTER TABLE test_email_accounts             MODIFY COLUMN status                VARCHAR(50) NOT NULL;
ALTER TABLE users                           MODIFY COLUMN auth_source           VARCHAR(50) NOT NULL;
ALTER TABLE user_mapping                    MODIFY COLUMN ip_range_type         VARCHAR(50) NULL;
ALTER TABLE user_mapping                    MODIFY COLUMN status                VARCHAR(50) NOT NULL;
ALTER TABLE user_roles                      MODIFY COLUMN role                  VARCHAR(50) NULL;
ALTER TABLE vulnerability_exception_request MODIFY COLUMN status                VARCHAR(50) NOT NULL;
ALTER TABLE workgroup                       MODIFY COLUMN criticality           VARCHAR(50) NOT NULL;
