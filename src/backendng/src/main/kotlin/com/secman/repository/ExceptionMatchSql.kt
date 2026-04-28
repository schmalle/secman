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
 *   - `a` for asset          (columns referenced: `id`, `ip`, `cloud_account_id`)
 *
 * Subject axis  : ALL_VULNS | PRODUCT | CVE
 * Scope axis    : GLOBAL    | IP      | ASSET | AWS_ACCOUNT
 *
 * If you add a new subject or scope value, update this constant and every site listed
 * below stays in sync automatically (compile-time interpolation):
 *
 *   - VulnerabilityRepository.kt   (~30 sites)
 *   - VulnerabilityStatisticsService.kt (4 sites)
 *   - VulnerabilityService.kt      (1 site, dynamic helper)
 *   - AccountVulnsService.kt       (1 site)
 *
 * Spec: docs/superpowers/specs/2026-04-28-vulnerability-exceptions-holistic-design.md (§3, §5)
 */
object ExceptionMatchSql {
    const val EXCEPTION_MATCH: String = """
        (e.expiration_date IS NULL OR e.expiration_date > NOW())
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
            )
        )
    """
}
