-- Bug fix: deduplicate user_mapping rows that were inserted because the
-- application-level duplicate check (`existsByEmailAndAwsAccountIdAndDomain`)
-- and the table's UNIQUE constraint both treat NULL as distinct in equality
-- comparisons. The application code has been fixed to use NULL-safe JPQL,
-- but rows already written by the broken code remain in place. This migration
-- collapses each duplicate group to one row.
--
-- Dedup key matches the existing UNIQUE constraint:
--   (email, aws_account_id, domain, ip_address)
-- with NULLs treated as equal (via COALESCE to a literal that cannot occur
-- in real data — empty string for the string columns).
--
-- Survivor selection (per group), from most-preferred to least-preferred:
--   1. Rows already linked to a user (user_id IS NOT NULL) — these are the
--      "active" mappings that grant access; losing them would silently revoke
--      permissions.
--   2. Rows already applied (applied_at IS NOT NULL) — preserves applied
--      history even when user_id is null (legacy data).
--   3. Oldest row by created_at, then by id — deterministic tiebreaker.

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
