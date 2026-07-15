package com.secman.service

import com.secman.domain.VulnerabilityException
import com.secman.repository.projection.VulnerabilityStatsRawRow

/**
 * Pre-parsed, bucketed index of active exceptions for cheap per-row matching against
 * [VulnerabilityStatsRawRow]. Mirrors the match semantics of the native-SQL
 * ExceptionMatchSql.EXCEPTION_MATCH predicate and the page-render-scale ExceptionMatchIndex
 * private class in VulnerabilityService — kept as a separate, purpose-built copy here
 * (rather than shared) because this one runs over the *entire* vulnerability table
 * (~358k+ rows) inside VulnerabilityStatisticsCacheService, replacing 4 correlated
 * NOT EXISTS SQL scans that could hold a pooled connection for minutes (HikariCP
 * leak-detection incident, 2026-07-15). Keep all match-predicate copies in sync — see
 * ExceptionMatchSql.kt's own cross-reference list.
 */
class StatisticsExceptionMatchIndex(activeExceptions: List<VulnerabilityException>) {
    private data class CveBucket(val ex: VulnerabilityException, val cves: Set<String>)

    private val allVulns: List<VulnerabilityException>
    private val productExceptions: List<VulnerabilityException>
    private val cveBuckets: List<CveBucket>

    init {
        val a = mutableListOf<VulnerabilityException>()
        val p = mutableListOf<VulnerabilityException>()
        val c = mutableListOf<CveBucket>()
        for (ex in activeExceptions) {
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

    fun isExcepted(row: VulnerabilityStatsRawRow): Boolean {
        if (allVulns.any { scopeMatches(it, row) }) return true
        if (productExceptions.any { productSubjectMatches(it, row) && scopeMatches(it, row) }) return true
        return cveBuckets.any { row.vulnerabilityId in it.cves && scopeMatches(it.ex, row) }
    }

    private fun productSubjectMatches(ex: VulnerabilityException, row: VulnerabilityStatsRawRow): Boolean {
        val sv = ex.subjectValue ?: return false
        return row.vulnerabilityId == sv ||
            row.vulnerableProductVersions?.contains(sv, ignoreCase = true) == true
    }

    private fun scopeMatches(ex: VulnerabilityException, row: VulnerabilityStatsRawRow): Boolean = when (ex.scope) {
        VulnerabilityException.Scope.GLOBAL -> true
        VulnerabilityException.Scope.IP -> row.assetIp == ex.scopeValue
        VulnerabilityException.Scope.ASSET -> ex.assetId != null && ex.assetId == row.assetId
        VulnerabilityException.Scope.AWS_ACCOUNT -> row.cloudAccountId != null && row.cloudAccountId == ex.scopeValue
        VulnerabilityException.Scope.OS -> osMatches(row.osVersion, ex.scopeValue)
    }

    private fun osMatches(osVersion: String?, scopeValue: String?): Boolean =
        !scopeValue.isNullOrBlank() && osVersion?.contains(scopeValue, ignoreCase = true) == true
}
