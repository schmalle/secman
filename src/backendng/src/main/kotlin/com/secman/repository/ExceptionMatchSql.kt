package com.secman.repository

/**
 * Single source of truth for the native-SQL exception-match predicate.
 *
 * Use as the body of a NOT EXISTS / EXISTS subquery on table `vulnerability_exception e`:
 *
 *     ... AND NOT EXISTS (SELECT 1 FROM vulnerability_exception e WHERE ${ExceptionMatchSql.EXCEPTION_MATCH})
 *
 * The outer query MUST alias:
 *   - `v` for vulnerability  (columns referenced: `vulnerability_id`, `vulnerable_product_versions`)
 *   - `a` for asset          (columns referenced: `id`, `ip`, `cloud_account_id`, `os_version`)
 *
 * Kind axis     : VULNERABILITY | NO_EDR   — only VULNERABILITY ever suppresses
 * Subject axis  : ALL_VULNS | PRODUCT | CVE
 * Scope axis    : GLOBAL    | IP      | ASSET | AWS_ACCOUNT | OS
 *
 * If you add a new subject or scope value, update this constant and every site listed
 * below stays in sync automatically (compile-time interpolation):
 *
 *   - VulnerabilityRepository.kt   (~30 sites)
 *   - VulnerabilityStatisticsService.kt (4 sites)
 *   - VulnerabilityService.kt      (1 site, dynamic helper)
 *   - AccountVulnsService.kt       (1 site)
 *
 * Two in-memory siblings must be updated BY HAND for any semantic change:
 *   - com.secman.domain.VulnerabilityException.matches() (canonical entity predicate)
 *   - com.secman.service.ExceptionMatchIndex (shared bulk index; agreement-tested
 *     against the entity predicate in ExceptionMatchIndexTest)
 *
 * One DERIVED reader does not interpolate this constant and must not be forgotten:
 *   - [VulnQuerySql.NOT_EXCEPTED] is `v.excepted = 0`, i.e. it reads the MATERIALIZATION of
 *     this predicate rather than re-evaluating it. The bridge is `recomputeExcepted*` below
 *     — those UPDATEs assign `excepted` from this very constant, which is what makes the two
 *     equivalent. A semantic change here therefore silently changes the statistics families
 *     only AFTER a recompute lands. `ExceptedFlagSqlAgreementIntegrationTest` asserts the two
 *     agree; keep it passing.
 *
 * Three deliberate choices in the `kind` conjunct, all of which look like over-caution
 * and are not:
 *
 *   1. `e.kind IS NULL OR e.kind = 'VULNERABILITY'`, NOT plain equality. V247 adds the
 *      column NOT NULL with a backfill, so NULL should be impossible — but if it ever
 *      occurred (Hibernate's hbm2ddl.auto=update creating the column on an environment
 *      where V247 did not apply, a restored pre-V247 dump, baseline-on-migrate skipping
 *      history), plain equality would make EVERY legacy exception stop matching, and the
 *      03:00 recomputeAllExceptedScheduled would clear `excepted` fleet-wide, unattended.
 *      This form degrades that scenario to "legacy rows behave exactly as before".
 *   2. NOT `COALESCE(e.kind, 'VULNERABILITY') = 'VULNERABILITY'` — COALESCE is
 *      non-sargable and would defeat idx_vuln_exception_covering. `IS NULL OR col = 'x'`
 *      is a two-interval range scan MariaDB serves from the index.
 *   3. First position, matching the leading column of idx_vuln_exception_covering as
 *      rebuilt by V247.
 *
 * Spec: docs/superpowers/specs/2026-04-28-vulnerability-exceptions-holistic-design.md (§3, §5)
 */
object ExceptionMatchSql {
    // REPLACE is case-sensitive. Fold only ASCII letters, as ProductSelector does.
    private const val FOLDED_LABEL = "REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(TRIM(j.label), 'A', 'a'), 'B', 'b'), 'C', 'c'), 'D', 'd'), 'E', 'e'), 'F', 'f'), 'G', 'g'), 'H', 'h'), 'I', 'i'), 'J', 'j'), 'K', 'k'), 'L', 'l'), 'M', 'm'), 'N', 'n'), 'O', 'o'), 'P', 'p'), 'Q', 'q'), 'R', 'r'), 'S', 's'), 'T', 't'), 'U', 'u'), 'V', 'v'), 'W', 'w'), 'X', 'x'), 'Y', 'y'), 'Z', 'z')"

    const val EXCEPTION_MATCH: String = """
        (e.kind IS NULL OR e.kind = 'VULNERABILITY')
        AND (e.expiration_date IS NULL OR (e.product_mode IS NULL AND e.expiration_date > NOW())
             OR (e.product_mode IS NOT NULL AND e.expiration_date > UTC_TIMESTAMP()))
        AND (
            (
                (e.subject = 'ALL_VULNS')
                OR (e.subject = 'PRODUCT' AND e.product_mode IS NULL AND e.product_patterns_json IS NULL AND (e.subject_value = v.vulnerability_id OR LOCATE(e.subject_value, v.vulnerable_product_versions) > 0))
                OR (e.subject = 'PRODUCT' AND e.product_mode IN ('EXACT', 'GLOB') AND EXISTS (
                    SELECT 1 FROM JSON_TABLE(COALESCE(v.product_labels_json, '[]'), '$[*]'
                        COLUMNS (label LONGTEXT PATH '$')) AS j
                    WHERE (e.product_mode = 'EXACT' AND BINARY $FOLDED_LABEL = BINARY e.subject_value)
                       OR (e.product_mode = 'GLOB' AND BINARY $FOLDED_LABEL LIKE BINARY
                           REPLACE(REPLACE(REPLACE(REPLACE(e.subject_value, '!', '!!'), '%', '!%'), '_', '!_'), '*', '%') ESCAPE '!')
                ))
                OR (e.subject = 'PRODUCT' AND e.product_patterns_json IS NOT NULL AND EXISTS (
                    SELECT 1 FROM JSON_TABLE(COALESCE(e.product_patterns_json, '[]'), '$[*]'
                        COLUMNS (pattern VARCHAR(512) PATH '$')) AS p
                    WHERE (LOCATE('*', p.pattern) = 0 AND (p.pattern = v.vulnerability_id OR LOCATE(p.pattern, v.vulnerable_product_versions) > 0))
                       OR (LOCATE('*', p.pattern) > 0 AND EXISTS (
                           SELECT 1 FROM JSON_TABLE(COALESCE(v.product_labels_json,
                               CASE WHEN LOCATE(',', v.vulnerable_product_versions) = 0
                                    THEN JSON_ARRAY(v.vulnerable_product_versions) ELSE '[]' END), '$[*]'
                               COLUMNS (label LONGTEXT PATH '$')) AS j
                           WHERE BINARY $FOLDED_LABEL LIKE BINARY
                               REPLACE(REPLACE(REPLACE(REPLACE(p.pattern, '!', '!!'), '%', '!%'), '_', '!_'), '*', '%') ESCAPE '!'
                       ))
                ))
                OR (e.subject = 'CVE' AND FIND_IN_SET(v.vulnerability_id, REPLACE(e.subject_value, ' ', '')) > 0)
            )
            AND (
                (e.scope = 'GLOBAL')
                OR (e.scope = 'IP' AND e.scope_value = a.ip)
                OR (e.scope = 'ASSET' AND e.asset_id = a.id)
                OR (e.scope = 'AWS_ACCOUNT' AND a.cloud_account_id IS NOT NULL AND e.scope_value = a.cloud_account_id)
                OR (e.scope = 'OS' AND a.os_version IS NOT NULL AND e.scope_value IS NOT NULL AND LOCATE(LOWER(e.scope_value), LOWER(a.os_version)) > 0)
            )
        )
    """
    // The latest end date is the union of overlapping rules. A permanent match keeps the flag active.
    private const val UTC_END = """CASE WHEN e.product_mode IS NOT NULL THEN e.expiration_date
        ELSE TIMESTAMPADD(SECOND, UNIX_TIMESTAMP(e.expiration_date), CAST('1970-01-01' AS DATETIME)) END"""
    const val MATERIALIZE = """v.excepted = EXISTS (SELECT 1 FROM vulnerability_exception e WHERE $EXCEPTION_MATCH),
        v.excepted_until = (SELECT CASE WHEN COUNT(*) = COUNT($UTC_END) THEN MAX($UTC_END) END
            FROM vulnerability_exception e WHERE $EXCEPTION_MATCH)"""

    const val COUNT_RULE_MATCHES = """SELECT COUNT(*) FROM vulnerability_exception e
        CROSS JOIN vulnerability v JOIN asset a ON a.id = v.asset_id
        WHERE e.id = :exceptionId AND """ + EXCEPTION_MATCH
    const val COUNT_VISIBLE_RULE_MATCHES = "$COUNT_RULE_MATCHES AND a.id IN (:assetIds)"
}
