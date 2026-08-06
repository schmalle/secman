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
    const val EXCEPTION_MATCH: String = """
        (e.kind IS NULL OR e.kind = 'VULNERABILITY')
        AND (e.expiration_date IS NULL OR e.expiration_date > NOW())
        AND (
            (
                (e.subject = 'ALL_VULNS')
                OR (e.subject = 'PRODUCT' AND (e.subject_value = v.vulnerability_id OR LOCATE(e.subject_value, v.vulnerable_product_versions) > 0))
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
}
