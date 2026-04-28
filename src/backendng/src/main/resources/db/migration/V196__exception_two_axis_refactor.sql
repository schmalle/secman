-- V196__exception_two_axis_refactor.sql
-- Refactor vulnerability_exception and vulnerability_exception_request from a single
-- exception_type enum into a two-axis model: subject (ALL_VULNS|PRODUCT|CVE) × scope
-- (GLOBAL|IP|ASSET|AWS_ACCOUNT).
-- Spec: docs/superpowers/specs/2026-04-28-vulnerability-exceptions-holistic-design.md

-- ============================================================================
-- vulnerability_exception
-- ============================================================================

-- Step 1: Add the new nullable columns
ALTER TABLE vulnerability_exception ADD COLUMN subject     VARCHAR(20)  NULL;
ALTER TABLE vulnerability_exception ADD COLUMN scope       VARCHAR(20)  NULL;
ALTER TABLE vulnerability_exception ADD COLUMN scope_value VARCHAR(255) NULL;

-- Step 2: Populate subject + scope from exception_type (and asset_id for CVE)
UPDATE vulnerability_exception SET subject = 'ALL_VULNS', scope = 'IP'          WHERE exception_type = 'IP';
UPDATE vulnerability_exception SET subject = 'PRODUCT',   scope = 'GLOBAL'      WHERE exception_type = 'PRODUCT';
UPDATE vulnerability_exception SET subject = 'ALL_VULNS', scope = 'ASSET'       WHERE exception_type = 'ASSET';
UPDATE vulnerability_exception SET subject = 'CVE',       scope = 'GLOBAL'      WHERE exception_type = 'CVE'         AND asset_id IS NULL;
UPDATE vulnerability_exception SET subject = 'CVE',       scope = 'ASSET'       WHERE exception_type = 'CVE'         AND asset_id IS NOT NULL;
UPDATE vulnerability_exception SET subject = 'ALL_VULNS', scope = 'AWS_ACCOUNT' WHERE exception_type = 'AWS_ACCOUNT';

-- Step 3: Move scope strings out of target_value
UPDATE vulnerability_exception SET scope_value = target_value, target_value = NULL
  WHERE scope IN ('IP', 'AWS_ACCOUNT');
UPDATE vulnerability_exception SET target_value = NULL
  WHERE scope = 'ASSET';

-- Step 4: Rename target_value -> subject_value (column now legitimately holds only subject values or NULL)
ALTER TABLE vulnerability_exception CHANGE COLUMN target_value subject_value VARCHAR(512) NULL;

-- Step 5: Tighten constraints
ALTER TABLE vulnerability_exception MODIFY COLUMN subject VARCHAR(20) NOT NULL;
ALTER TABLE vulnerability_exception MODIFY COLUMN scope   VARCHAR(20) NOT NULL;

-- Step 6: Drop the legacy column
ALTER TABLE vulnerability_exception DROP COLUMN exception_type;

-- Step 7: Drop legacy indexes
ALTER TABLE vulnerability_exception DROP INDEX idx_vuln_exception_type;
ALTER TABLE vulnerability_exception DROP INDEX idx_vuln_exception_type_target;
ALTER TABLE vulnerability_exception DROP INDEX idx_vuln_exception_active;

-- Step 8: Create new indexes
CREATE INDEX idx_vuln_exception_subject_scope ON vulnerability_exception (subject, scope);
CREATE INDEX idx_vuln_exception_active_v2     ON vulnerability_exception (expiration_date, subject, scope);

-- ============================================================================
-- vulnerability_exception_request
-- ============================================================================
-- The request table currently has: scope (SINGLE_VULNERABILITY/CVE_PATTERN), cve_id, asset_id.
-- After this migration: subject, scope (new enum values), subject_value, scope_value.
-- cve_id and asset_id are kept (used by the audit trail and as backfill source).

-- Step 1: Add the new nullable columns
ALTER TABLE vulnerability_exception_request ADD COLUMN subject       VARCHAR(20)  NULL;
ALTER TABLE vulnerability_exception_request ADD COLUMN subject_value VARCHAR(512) NULL;
ALTER TABLE vulnerability_exception_request ADD COLUMN scope_value   VARCHAR(255) NULL;
-- Note: a `scope` column already exists with VARCHAR holding the legacy enum.
-- We will overwrite its values in step 2 and rely on Hibernate validation to
-- accept the new enum values in the same column (the column type stays VARCHAR(20)).

-- Step 2: Translate legacy ExceptionScope (SINGLE_VULNERABILITY/CVE_PATTERN) to new (subject, scope)
-- Legacy SINGLE_VULNERABILITY = CVE × ASSET (specific vuln on specific asset)
-- Legacy CVE_PATTERN          = CVE × GLOBAL (all instances of this CVE)
UPDATE vulnerability_exception_request
   SET subject = 'CVE', subject_value = cve_id, scope = 'ASSET'
 WHERE scope = 'SINGLE_VULNERABILITY';
UPDATE vulnerability_exception_request
   SET subject = 'CVE', subject_value = cve_id, scope = 'GLOBAL'
 WHERE scope = 'CVE_PATTERN';

-- Step 3: Tighten constraints
ALTER TABLE vulnerability_exception_request MODIFY COLUMN subject VARCHAR(20) NOT NULL;
ALTER TABLE vulnerability_exception_request MODIFY COLUMN scope   VARCHAR(20) NOT NULL;

-- Step 4: Add an index on the new identity tuple
CREATE INDEX idx_vuln_req_subject_scope ON vulnerability_exception_request (subject, scope);
