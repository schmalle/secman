-- Bug fix follow-up to V194: a class of legacy rows uses literal sentinel
-- strings ("-none-", "none", "null", etc.) in the `domain` column to mean
-- "no domain". Those rows pass the existing domain regex
-- (^[a-zA-Z0-9.-]+$) so they were stored verbatim instead of as SQL NULL,
-- producing logical duplicates next to the real-NULL row for the same
-- (email, aws_account_id) pair.
--
-- The application code now canonicalizes these sentinels to NULL via the
-- UserMapping @PrePersist callback, but rows already in the table still
-- carry the literal sentinel. This migration:
--   1. Coerces sentinel domain values to NULL.
--   2. Re-runs the V194 dedup logic so the freshly-canonicalized rows
--      collapse against any pre-existing real-NULL row for the same key.

-- Step 1: collapse domain sentinels to NULL. Lowercased + trimmed comparison
-- mirrors the @PrePersist normalization the application uses going forward.
UPDATE user_mapping
SET domain = NULL
WHERE LOWER(TRIM(domain)) IN ('', '-', '--', '-none-', 'none', 'null', 'nil', 'n/a', 'na');

-- Step 2: dedupe. Same selection rules as V194 (prefer rows linked to a
-- user, then rows already applied, then oldest by created_at/id) so we do
-- not silently revoke active access while collapsing the new duplicates.
DELETE FROM user_mapping
WHERE id IN (
    SELECT id FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY
                       email,
                       COALESCE(aws_account_id, ''),
                       COALESCE(domain, ''),
                       COALESCE(ip_address, '')
                   ORDER BY
                       CASE WHEN user_id IS NOT NULL THEN 0 ELSE 1 END,
                       CASE WHEN applied_at IS NOT NULL THEN 0 ELSE 1 END,
                       created_at,
                       id
               ) AS rn
        FROM user_mapping
    ) ranked
    WHERE rn > 1
);
