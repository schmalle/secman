-- V200__rewiden_enum_columns_final.sql
-- Final re-widen pass. V199 (and V198 / V197 before it) all completed
-- successfully but Hibernate's dialect-level default for MariaDB
-- (NAMED_ENUM) kept re-narrowing the same 27 columns to native ENUM on
-- subsequent restarts.
--
-- This migration is paired with per-field @JdbcTypeCode(SqlTypes.VARCHAR)
-- annotations applied to all 43 @Enumerated(EnumType.STRING) fields in the
-- domain layer (commit alongside this file). Field-level annotations take
-- precedence over both global config and dialect defaults in Hibernate's
-- type resolution order, so the columns stay VARCHAR after this restart.
--
-- The global setting `hibernate.type.preferred_enum_jdbc_type: 12` in
-- application.yml is kept as a defense-in-depth backup — it doesn't hurt
-- and would protect any future @Enumerated field that forgets the
-- @JdbcTypeCode annotation.
--
-- Idempotent: MODIFY COLUMN to the same VARCHAR(50) target is a no-op.

ALTER TABLE admin_summary_log               MODIFY COLUMN status              VARCHAR(50) NOT NULL;
ALTER TABLE alignment_reviewer              MODIFY COLUMN status              VARCHAR(50) NOT NULL;
ALTER TABLE alignment_session               MODIFY COLUMN review_scope        VARCHAR(50) NOT NULL;
ALTER TABLE alignment_session               MODIFY COLUMN status              VARCHAR(50) NOT NULL;
ALTER TABLE alignment_snapshot              MODIFY COLUMN change_type         VARCHAR(50) NOT NULL;
ALTER TABLE asset                           MODIFY COLUMN criticality         VARCHAR(50) NULL;
ALTER TABLE asset                           MODIFY COLUMN network_zone        VARCHAR(50) NULL;
ALTER TABLE asset_deletion_audit_log        MODIFY COLUMN operation_type      VARCHAR(50) NOT NULL;
ALTER TABLE email_configs                   MODIFY COLUMN provider            VARCHAR(50) NOT NULL;
ALTER TABLE email_notification_logs         MODIFY COLUMN status              VARCHAR(50) NOT NULL;
ALTER TABLE exception_request_audit         MODIFY COLUMN event_type          VARCHAR(50) NOT NULL;
ALTER TABLE exception_request_audit         MODIFY COLUMN severity            VARCHAR(50) NOT NULL;
ALTER TABLE export_jobs                     MODIFY COLUMN export_type         VARCHAR(50) NOT NULL;
ALTER TABLE export_jobs                     MODIFY COLUMN status              VARCHAR(50) NOT NULL;
ALTER TABLE materialized_view_refresh_job   MODIFY COLUMN status              VARCHAR(50) NOT NULL;
ALTER TABLE mcp_audit_logs                  MODIFY COLUMN event_type          VARCHAR(50) NOT NULL;
ALTER TABLE mcp_audit_logs                  MODIFY COLUMN operation           VARCHAR(50) NULL;
ALTER TABLE mcp_audit_logs                  MODIFY COLUMN severity            VARCHAR(50) NOT NULL;
ALTER TABLE mcp_sessions                    MODIFY COLUMN connection_type     VARCHAR(50) NOT NULL;
ALTER TABLE response                        MODIFY COLUMN answer_type         VARCHAR(50) NULL;
ALTER TABLE test_email_accounts             MODIFY COLUMN provider            VARCHAR(50) NOT NULL;
ALTER TABLE test_email_accounts             MODIFY COLUMN status              VARCHAR(50) NOT NULL;
ALTER TABLE users                           MODIFY COLUMN auth_source         VARCHAR(50) NOT NULL;
ALTER TABLE user_mapping                    MODIFY COLUMN ip_range_type       VARCHAR(50) NULL;
ALTER TABLE user_mapping                    MODIFY COLUMN status              VARCHAR(50) NOT NULL;
ALTER TABLE vulnerability_exception_request MODIFY COLUMN status              VARCHAR(50) NOT NULL;
ALTER TABLE workgroup                       MODIFY COLUMN criticality         VARCHAR(50) NOT NULL;
