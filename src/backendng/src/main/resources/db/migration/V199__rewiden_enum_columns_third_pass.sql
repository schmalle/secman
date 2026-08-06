-- V199__rewiden_enum_columns_third_pass.sql
-- Third re-widen pass. V198 completed (success=1, 2.0s) but Hibernate
-- re-narrowed the same 27 columns to native ENUM on the next restart because
-- the application.yml setting `hibernate.type.preferred_enum_jdbc_type:
-- VARCHAR` (string form) was not honored on this Hibernate 6.4 / MariaDB
-- dialect combination.
--
-- application.yml has now been changed to use the integer form
-- `preferred_enum_jdbc_type: 12` (java.sql.Types.VARCHAR), which Hibernate
-- always parses correctly. With that setting in place, this widening should
-- stick after subsequent restarts.
--
-- If V199 also gets reverted on restart, the global setting still isn't
-- being honored and the next step is per-field @JdbcTypeCode(SqlTypes.VARCHAR)
-- annotations on each @Enumerated(EnumType.STRING) field. That's the
-- nuclear option and is guaranteed to work, but invasive.

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
