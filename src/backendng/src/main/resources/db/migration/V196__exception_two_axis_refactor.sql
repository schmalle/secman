-- V196__exception_two_axis_refactor.sql
-- Refactor vulnerability_exception and vulnerability_exception_request from a single
-- exception_type enum into a two-axis model: subject (ALL_VULNS|PRODUCT|CVE) × scope
-- (GLOBAL|IP|ASSET|AWS_ACCOUNT).
-- Spec: docs/superpowers/specs/2026-04-28-vulnerability-exceptions-holistic-design.md
--
-- This migration is written to be safely re-runnable after a partial failure.
-- MariaDB does not roll back DDL, so every statement is guarded with either a
-- native IF [NOT] EXISTS clause or an existence check on the source column.

-- ============================================================================
-- vulnerability_exception
-- ============================================================================

-- Step 1: Add the new nullable columns (idempotent)
ALTER TABLE vulnerability_exception ADD COLUMN IF NOT EXISTS subject     VARCHAR(20)  NULL;
ALTER TABLE vulnerability_exception ADD COLUMN IF NOT EXISTS scope       VARCHAR(20)  NULL;
ALTER TABLE vulnerability_exception ADD COLUMN IF NOT EXISTS scope_value VARCHAR(255) NULL;

-- Steps 2-4: Data migration + column rename. Wrapped in a procedure so each
-- block only runs while the legacy column it depends on still exists.
DROP PROCEDURE IF EXISTS _v196_migrate_exception;
CREATE PROCEDURE _v196_migrate_exception()
BEGIN
  -- Step 2: Populate subject + scope from exception_type
  IF (SELECT COUNT(*) FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME   = 'vulnerability_exception'
          AND COLUMN_NAME  = 'exception_type') > 0 THEN
    UPDATE vulnerability_exception SET subject = 'ALL_VULNS', scope = 'IP'          WHERE exception_type = 'IP';
    UPDATE vulnerability_exception SET subject = 'PRODUCT',   scope = 'GLOBAL'      WHERE exception_type = 'PRODUCT';
    UPDATE vulnerability_exception SET subject = 'ALL_VULNS', scope = 'ASSET'       WHERE exception_type = 'ASSET';
    UPDATE vulnerability_exception SET subject = 'CVE',       scope = 'GLOBAL'      WHERE exception_type = 'CVE'         AND asset_id IS NULL;
    UPDATE vulnerability_exception SET subject = 'CVE',       scope = 'ASSET'       WHERE exception_type = 'CVE'         AND asset_id IS NOT NULL;
    UPDATE vulnerability_exception SET subject = 'ALL_VULNS', scope = 'AWS_ACCOUNT' WHERE exception_type = 'AWS_ACCOUNT';
  END IF;

  -- Step 3 + 4: Move scope strings out of target_value, then rename target_value -> subject_value.
  -- target_value was originally declared NOT NULL; relax it before writing NULLs.
  IF (SELECT COUNT(*) FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME   = 'vulnerability_exception'
          AND COLUMN_NAME  = 'target_value') > 0 THEN
    ALTER TABLE vulnerability_exception MODIFY COLUMN target_value VARCHAR(512) NULL;
    UPDATE vulnerability_exception SET scope_value = target_value, target_value = NULL
      WHERE scope IN ('IP', 'AWS_ACCOUNT');
    UPDATE vulnerability_exception SET target_value = NULL
      WHERE scope = 'ASSET';
    ALTER TABLE vulnerability_exception CHANGE COLUMN target_value subject_value VARCHAR(512) NULL;
  END IF;
END;
CALL _v196_migrate_exception();
DROP PROCEDURE _v196_migrate_exception;

-- Step 5: Tighten constraints (MODIFY is idempotent when the column already matches)
ALTER TABLE vulnerability_exception MODIFY COLUMN subject VARCHAR(20) NOT NULL;
ALTER TABLE vulnerability_exception MODIFY COLUMN scope   VARCHAR(20) NOT NULL;

-- Step 6: Drop the legacy column (idempotent)
ALTER TABLE vulnerability_exception DROP COLUMN IF EXISTS exception_type;

-- Step 7: Drop legacy indexes (idempotent)
ALTER TABLE vulnerability_exception DROP INDEX IF EXISTS idx_vuln_exception_type;
ALTER TABLE vulnerability_exception DROP INDEX IF EXISTS idx_vuln_exception_type_target;
ALTER TABLE vulnerability_exception DROP INDEX IF EXISTS idx_vuln_exception_active;

-- Step 8: Create new indexes (idempotent)
CREATE INDEX IF NOT EXISTS idx_vuln_exception_subject_scope ON vulnerability_exception (subject, scope);
CREATE INDEX IF NOT EXISTS idx_vuln_exception_active_v2     ON vulnerability_exception (expiration_date, subject, scope);

-- ============================================================================
-- vulnerability_exception_request
-- ============================================================================
-- The request table currently has: scope (SINGLE_VULNERABILITY/CVE_PATTERN), cve_id, asset_id.
-- After this migration: subject, scope (new enum values), subject_value, scope_value.
-- cve_id and asset_id are kept (used by the audit trail and as backfill source).

-- Step 1: Add the new nullable columns (idempotent)
ALTER TABLE vulnerability_exception_request ADD COLUMN IF NOT EXISTS subject       VARCHAR(20)  NULL;
ALTER TABLE vulnerability_exception_request ADD COLUMN IF NOT EXISTS subject_value VARCHAR(512) NULL;
ALTER TABLE vulnerability_exception_request ADD COLUMN IF NOT EXISTS scope_value   VARCHAR(255) NULL;

-- Step 1a: The existing `scope` column was created by Hibernate auto-update as
-- ENUM('SINGLE_VULNERABILITY','CVE_PATTERN'). Widen it to VARCHAR(20) so the
-- new enum values (GLOBAL/IP/ASSET/AWS_ACCOUNT) can be written without
-- triggering "Data truncated for column 'scope'" (MariaDB error 1265).
-- MODIFY COLUMN is idempotent: re-running on an already-VARCHAR column is a no-op.
ALTER TABLE vulnerability_exception_request MODIFY COLUMN scope VARCHAR(20) NOT NULL;

-- Step 2: Translate legacy ExceptionScope (SINGLE_VULNERABILITY/CVE_PATTERN) to new (subject, scope).
-- Idempotent: a re-run finds no rows still carrying the legacy scope strings.
-- Legacy SINGLE_VULNERABILITY = CVE × ASSET (specific vuln on specific asset)
-- Legacy CVE_PATTERN          = CVE × GLOBAL (all instances of this CVE)
UPDATE vulnerability_exception_request
   SET subject = 'CVE', subject_value = cve_id, scope = 'ASSET'
 WHERE scope = 'SINGLE_VULNERABILITY';
UPDATE vulnerability_exception_request
   SET subject = 'CVE', subject_value = cve_id, scope = 'GLOBAL'
 WHERE scope = 'CVE_PATTERN';

-- Step 3: Tighten constraints (idempotent)
ALTER TABLE vulnerability_exception_request MODIFY COLUMN subject VARCHAR(20) NOT NULL;
ALTER TABLE vulnerability_exception_request MODIFY COLUMN scope   VARCHAR(20) NOT NULL;

-- Step 4: Add an index on the new identity tuple (idempotent)
CREATE INDEX IF NOT EXISTS idx_vuln_req_subject_scope ON vulnerability_exception_request (subject, scope);
