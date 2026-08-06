package com.secman.service

import com.secman.domain.Asset
import com.secman.domain.ExceptionKind
import com.secman.domain.ExceptionMatchable
import com.secman.domain.Vulnerability
import com.secman.domain.VulnerabilityException

/**
 * Pre-parsed, bucketed index of active exceptions for cheap per-row matching against
 * any [ExceptionMatchable] projection (status-filtered page rows) or a
 * (Vulnerability, Asset) entity pair. This is the single in-memory bulk-match
 * implementation; the entity-level [VulnerabilityException.matches] stays the canonical
 * per-pair semantic reference and the native-SQL predicate lives in
 * ExceptionMatchSql.EXCEPTION_MATCH — keep all three in sync (see ExceptionMatchSql.kt's
 * cross-reference list).
 *
 * Why this exists instead of calling `VulnerabilityException.matches()` per row: the
 * entity method reparses comma-separated CVE lists on every call. This index parses once
 * and buckets by Subject, which matters at page-render scale (50 exceptions × 50 rows).
 *
 * **Page scale only.** This index is for bounded row sets, where the caller needs to know
 * WHICH exception matched (a boolean column cannot answer that). It is deliberately NOT
 * used for aggregate/statistics scale any more: it was, over the entire vulnerability
 * table inside VulnerabilityStatisticsCacheService, and that full-table fetch ran a 1 GB
 * container out of heap during the 2026-07-30 CrowdStrike import. Aggregates now filter in
 * SQL on the materialized `excepted` flag — see VulnQuerySql.NOT_EXCEPTED.
 *
 * Match priority mirrors entity logic:
 *   1. ALL_VULNS exceptions (subject always matches; fall through to scope check)
 *   2. PRODUCT exceptions (CVE-equality OR substring of vulnerableProductVersions)
 *   3. CVE exceptions (membership in pre-parsed CVE set)
 *
 * Contract: callers pass already-ACTIVE exceptions (not expired); this index does not
 * re-check `isActive()`. It DOES filter by kind itself, so callers need not pre-filter —
 * a NO_EDR exception never enters a bucket and can never match.
 */
class ExceptionMatchIndex(activeExceptions: List<VulnerabilityException>) {
    private data class CveBucket(val ex: VulnerabilityException, val cves: Set<String>)

    private val allVulns: List<VulnerabilityException>
    private val productExceptions: List<VulnerabilityException>
    private val cveBuckets: List<CveBucket>

    init {
        val a = mutableListOf<VulnerabilityException>()
        val p = mutableListOf<VulnerabilityException>()
        val c = mutableListOf<CveBucket>()
        for (ex in activeExceptions) {
            // Only VULNERABILITY exceptions suppress findings. Dropping NO_EDR rows here
            // rather than in firstMatch() means they can never reach a bucket at all, so
            // no future match path can accidentally consider them. Mirrors the entity
            // guard in VulnerabilityException.matches() and the SQL guard in
            // ExceptionMatchSql.EXCEPTION_MATCH — all three must agree.
            if (ex.kind != ExceptionKind.VULNERABILITY) continue
            when (ex.subject) {
                VulnerabilityException.Subject.ALL_VULNS -> a += ex
                VulnerabilityException.Subject.PRODUCT -> p += ex
                VulnerabilityException.Subject.CVE -> {
                    val sv = ex.subjectValue
                    if (!sv.isNullOrBlank()) {
                        c += CveBucket(ex, sv.split(",").map { it.trim() }.toSet())
                    }
                }
            }
        }
        allVulns = a
        productExceptions = p
        cveBuckets = c
    }

    /** Return the first active exception matching this row, or null. */
    fun firstMatch(row: ExceptionMatchable): VulnerabilityException? {
        allVulns.firstOrNull { scopeMatches(it, row) }?.let { return it }
        productExceptions.firstOrNull { productSubjectMatches(it, row) && scopeMatches(it, row) }?.let { return it }
        cveBuckets.firstOrNull { row.vulnerabilityId in it.cves && scopeMatches(it.ex, row) }?.let { return it.ex }
        return null
    }

    /**
     * Entity-pair overload mirroring `VulnerabilityException.matches` semantics but
     * without re-parsing CVE lists on every call.
     */
    fun firstMatch(v: Vulnerability, a: Asset): VulnerabilityException? =
        firstMatch(VulnAssetRow(v, a))

    fun isExcepted(row: ExceptionMatchable): Boolean = firstMatch(row) != null

    /** Adapter presenting a (Vulnerability, Asset) entity pair as an [ExceptionMatchable]. */
    private class VulnAssetRow(v: Vulnerability, a: Asset) : ExceptionMatchable {
        override val assetId: Long? = a.id
        override val assetIp: String? = a.ip
        override val cloudAccountId: String? = a.cloudAccountId
        override val osVersion: String? = a.osVersion
        override val vulnerabilityId: String? = v.vulnerabilityId
        override val vulnerableProductVersions: String? = v.vulnerableProductVersions
    }

    private fun productSubjectMatches(ex: VulnerabilityException, row: ExceptionMatchable): Boolean {
        val sv = ex.subjectValue ?: return false
        return row.vulnerabilityId == sv ||
            row.vulnerableProductVersions?.contains(sv, ignoreCase = true) == true
    }

    private fun scopeMatches(ex: VulnerabilityException, row: ExceptionMatchable): Boolean = when (ex.scope) {
        VulnerabilityException.Scope.GLOBAL -> true
        VulnerabilityException.Scope.IP -> row.assetIp == ex.scopeValue
        VulnerabilityException.Scope.ASSET -> ex.assetId != null && ex.assetId == row.assetId
        VulnerabilityException.Scope.AWS_ACCOUNT -> row.cloudAccountId != null && row.cloudAccountId == ex.scopeValue
        VulnerabilityException.Scope.OS -> osMatches(row.osVersion, ex.scopeValue)
    }

    // OS scope: case-insensitive substring of the asset's osVersion. Mirrors the
    // native-SQL predicate in ExceptionMatchSql (LOCATE(LOWER(scope_value), LOWER(os_version))).
    private fun osMatches(osVersion: String?, scopeValue: String?): Boolean =
        !scopeValue.isNullOrBlank() && osVersion?.contains(scopeValue, ignoreCase = true) == true
}
