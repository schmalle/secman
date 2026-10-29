package com.secman.service

import com.secman.dto.OverdueAssetEntry
import com.secman.dto.OverdueMatchReason
import com.secman.dto.OverdueMatchType
import com.secman.dto.OverdueRecipient
import com.secman.dto.OverdueWorkgroupPath
import com.secman.repository.AssetCurrentnessSql
import com.secman.repository.CrowdStrikeSnapshotSql
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import java.time.LocalDateTime

/**
 * Set-based recipient resolution for `notify-users-overdue` and `show-notified-users`.
 *
 * A user receives an asset when the asset has an overdue, non-excepted finding, is current
 * (observed in the 72-hour window), and the user is a direct member of an enabled aws- workgroup
 * that either has the asset assigned (DIRECT_ASSIGNMENT) or holds a whole-account grant for
 * the asset's AWS account (AWS_WORKGROUP). Account-owner mappings, AWS sharing, metadata
 * fields and workgroup hierarchy are deliberately not paths here; they stay with
 * [AwsAccountRecipientResolver] and `send-notification-users`.
 */
@Singleton
open class WorkgroupOverdueRecipientQuery(private val entityManager: EntityManager) {

    /** One (user, asset, path) row of the recipient SQL, before deduplication. */
    internal data class MatchRow(
        val userId: Long,
        val email: String,
        val assetId: Long,
        val assetName: String,
        val ipAddress: String?,
        val assetAccountId: String?,
        val overdueCount: Int,
        val criticalCount: Int,
        val highCount: Int,
        val mediumCount: Int,
        val lowCount: Int,
        val oldestDays: Long,
        val type: OverdueMatchType,
        val workgroupId: Long,
        val workgroupName: String,
        val grantAccountId: String?
    )

    /** Overdue cutoff and observation window, computed once per run so every query agrees. */
    data class Window(val threshold: LocalDateTime, val seenStartUtc: LocalDateTime, val seenEndUtc: LocalDateTime, val today: LocalDateTime)

    @Transactional(readOnly = true)
    open fun findRecipients(window: Window, userId: Long? = null): List<OverdueRecipient> =
        groupByRecipient(findMatchRows(window, userId))

    /** Every (user, asset, path) combination, optionally for one user; deduplication happens in [groupByRecipient]. */
    internal fun findMatchRows(window: Window, userId: Long?): List<MatchRow> {
        // Constant fragment only; the id itself is always bound.
        val query = entityManager.createNativeQuery(matchSql(if (userId != null) "AND uw.user_id = :userId" else ""))
            .setParameter("threshold", window.threshold)
            .setParameter("seenStart", window.seenStartUtc)
            .setParameter("seenEnd", window.seenEndUtc)
            .setParameter("today", window.today)
        if (userId != null) query.setParameter("userId", userId)

        @Suppress("UNCHECKED_CAST")
        return (query.resultList as List<Array<Any?>>).map(::toMatchRow)
    }

    /**
     * `overdue`: current assets with overdue, non-excepted findings and their counts. Both filters
     * use the indexed generated columns (`sla_anchor_at`, `observed_at`, V295).
     * `matches`: the two recipient paths. BINARY keeps the aws- prefix case-sensitive
     * regardless of the database collation. The final join drops disabled users.
     */
    private fun matchSql(userFilter: String): String = """
            WITH overdue AS (
                SELECT v.asset_id,
                       COUNT(*) AS overdue_count,
                       SUM(CASE WHEN UPPER(v.cvss_severity) = 'CRITICAL' THEN 1 ELSE 0 END) AS critical_count,
                       SUM(CASE WHEN UPPER(v.cvss_severity) = 'HIGH' THEN 1 ELSE 0 END) AS high_count,
                       SUM(CASE WHEN UPPER(v.cvss_severity) = 'MEDIUM' THEN 1 ELSE 0 END) AS medium_count,
                       SUM(CASE WHEN UPPER(v.cvss_severity) = 'LOW' THEN 1 ELSE 0 END) AS low_count,
                       MIN(v.sla_anchor_at) AS oldest_at
                FROM ${CrowdStrikeSnapshotSql.STORED_FINDINGS} v
                WHERE v.sla_anchor_at < :threshold
                  AND v.excepted = 0
                  AND v.asset_id IN (
                      SELECT id FROM asset
                      WHERE ${AssetCurrentnessSql.OBSERVED_AT} BETWEEN :seenStart AND :seenEnd
                  )
                GROUP BY v.asset_id
            ),
            matches AS (
                SELECT uw.user_id, aw.asset_id, 'DIRECT_ASSIGNMENT' AS match_type,
                       w.id AS workgroup_id, w.name AS workgroup_name, CAST(NULL AS CHAR(12)) AS grant_account_id
                FROM asset_workgroups aw
                JOIN overdue o ON o.asset_id = aw.asset_id
                JOIN workgroup w ON w.id = aw.workgroup_id AND w.enabled = TRUE
                    AND BINARY w.name LIKE 'aws-%'
                JOIN user_workgroups uw ON uw.workgroup_id = w.id
                WHERE 1 = 1 $userFilter
                UNION ALL
                SELECT uw.user_id, a.id, 'AWS_WORKGROUP',
                       w.id, w.name, waa.aws_account_id
                FROM workgroup_aws_account waa
                JOIN workgroup w ON w.id = waa.workgroup_id AND w.enabled = TRUE
                    AND BINARY w.name LIKE 'aws-%'
                JOIN user_workgroups uw ON uw.workgroup_id = w.id
                JOIN asset a ON a.cloud_account_id = waa.aws_account_id
                JOIN overdue o ON o.asset_id = a.id
                WHERE 1 = 1 $userFilter
            )
            SELECT u.id, u.email, a.id, a.name, a.ip, a.cloud_account_id,
                   o.overdue_count, o.critical_count, o.high_count, o.medium_count, o.low_count,
                   DATEDIFF(:today, o.oldest_at),
                   m.match_type, m.workgroup_id, m.workgroup_name, m.grant_account_id
            FROM matches m
            JOIN users u ON u.id = m.user_id AND u.enabled = TRUE
            JOIN asset a ON a.id = m.asset_id
            JOIN overdue o ON o.asset_id = m.asset_id
            ORDER BY u.email, a.name, a.id
    """.trimIndent()

    private fun toMatchRow(row: Array<Any?>) = MatchRow(
        userId = (row[0] as Number).toLong(),
        email = row[1] as String,
        assetId = (row[2] as Number).toLong(),
        assetName = row[3] as String,
        ipAddress = row[4] as String?,
        assetAccountId = row[5] as String?,
        overdueCount = (row[6] as Number).toInt(),
        criticalCount = (row[7] as Number).toInt(),
        highCount = (row[8] as Number).toInt(),
        mediumCount = (row[9] as Number).toInt(),
        lowCount = (row[10] as Number).toInt(),
        oldestDays = (row[11] as Number?)?.toLong() ?: 0L,
        type = OverdueMatchType.valueOf(row[12].toString()),
        workgroupId = (row[13] as Number).toLong(),
        workgroupName = row[14] as String,
        grantAccountId = row[15] as String?
    )

    /** Direct memberships with what each workgroup contributes; disabled groups are listed but grant nothing. */
    @Transactional(readOnly = true)
    open fun findWorkgroupPaths(userId: Long): List<OverdueWorkgroupPath> {
        val rows = entityManager.createNativeQuery("""
            SELECT w.id, w.name, w.enabled,
                   (SELECT COUNT(*) FROM asset_workgroups aw WHERE aw.workgroup_id = w.id) AS assigned_assets,
                   waa.aws_account_id
            FROM user_workgroups uw
            JOIN workgroup w ON w.id = uw.workgroup_id
            LEFT JOIN workgroup_aws_account waa ON waa.workgroup_id = w.id
            WHERE uw.user_id = :userId
            ORDER BY w.name, w.id, waa.aws_account_id
        """.trimIndent())
            .setParameter("userId", userId)
            .resultList

        @Suppress("UNCHECKED_CAST")
        return (rows as List<Array<Any?>>)
            .groupBy { (it[0] as Number).toLong() }
            .map { (id, group) ->
                val first = group.first()
                OverdueWorkgroupPath(
                    workgroupId = id,
                    name = first[1] as String,
                    enabled = toBoolean(first[2]),
                    assignedAssetCount = (first[3] as Number).toLong(),
                    awsAccountIds = group.mapNotNull { it[4] as String? }.distinct()
                )
            }
    }

    companion object {
        fun normalizeEmail(email: String): String = email.trim().lowercase()

        /** One recipient per normalized email, one entry per asset, every distinct reason kept. */
        internal fun groupByRecipient(rows: List<MatchRow>): List<OverdueRecipient> =
            rows.groupBy { normalizeEmail(it.email) }
                .map { (email, userRows) ->
                    OverdueRecipient(
                        userId = userRows.minOf { it.userId },
                        email = email,
                        assets = userRows.groupBy { it.assetId }.map { (_, assetRows) -> toAssetEntry(assetRows) }
                            .sortedWith(compareByDescending<OverdueAssetEntry> { it.oldestDays }.thenBy { it.assetName }.thenBy { it.assetId })
                    )
                }
                .sortedBy { it.email }

        private fun toAssetEntry(rows: List<MatchRow>): OverdueAssetEntry {
            val first = rows.first()
            val reasons = rows.map { OverdueMatchReason(it.type, it.workgroupId, it.workgroupName, it.grantAccountId) }
                .distinct()
                .sortedWith(compareBy<OverdueMatchReason> { it.type }.thenBy { it.workgroupName }.thenBy { it.awsAccountId ?: "" })
            return OverdueAssetEntry(
                assetId = first.assetId,
                assetName = first.assetName,
                ipAddress = first.ipAddress,
                awsAccountId = first.assetAccountId,
                overdueCount = first.overdueCount,
                criticalCount = first.criticalCount,
                highCount = first.highCount,
                mediumCount = first.mediumCount,
                lowCount = first.lowCount,
                oldestDays = first.oldestDays,
                reasons = reasons
            )
        }

        private fun toBoolean(value: Any?): Boolean = when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            else -> false
        }
    }
}
