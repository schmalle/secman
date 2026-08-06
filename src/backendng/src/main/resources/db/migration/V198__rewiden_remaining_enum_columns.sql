-- V198__rewiden_remaining_enum_columns.sql
-- V197 widened all 42 native MariaDB ENUM columns to VARCHAR(50), but
-- Hibernate 6.4+ on MariaDB defaults to generating native ENUM types for
-- @Enumerated(EnumType.STRING) fields. With hbm2ddl.auto=update, Hibernate
-- re-narrowed 27 of those columns back to ENUM on the next restart, which is
-- exactly the behavior that creates the value-list-lag trap (e.g.
-- user_roles.role losing the 7 non-{USER,ADMIN} role values).
--
-- This migration is paired with a one-line application.yml change:
--   jpa.default.properties.hibernate.type.preferred_enum_jdbc_type: VARCHAR
-- which tells Hibernate to map @Enumerated to VARCHAR, so the widened columns
-- stick after subsequent restarts.
--
-- The 27 columns listed below are the ones still showing DATA_TYPE='enum' in
-- information_schema.COLUMNS at the time V198 was authored. MODIFY COLUMN to
-- the same VARCHAR(50) target is a no-op if a column has already been
-- widened, so this script is safely re-runnable.

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
