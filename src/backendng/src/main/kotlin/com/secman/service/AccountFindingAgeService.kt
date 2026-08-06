package com.secman.service

import com.secman.repository.AwsAccountRepository
import com.secman.repository.VulnerabilityRepository
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Ranks AWS accounts by the age of their oldest still-open (non-excepted) finding.
 *
 * ADMIN-only data — deliberately unscoped by the unified asset-access rules, the same
 * way AdminSummaryService.getTopServersAdmin() is. Every caller must enforce ADMIN at
 * its own boundary before invoking this service.
 *
 * Spec: docs/superpowers/specs/2026-07-26-account-finding-age-design.md
 */
@Singleton
open class AccountFindingAgeService(
    private val vulnerabilityRepository: VulnerabilityRepository,
    private val awsAccountRepository: AwsAccountRepository
) {
    private val logger = LoggerFactory.getLogger(AccountFindingAgeService::class.java)

    companion object {
        const val DEFAULT_LIMIT = 10
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 50
    }

    /**
     * One reported account. [accountName] is never null — it falls back to the bare
     * 12-digit account ID when no name is on record.
     */
    data class AccountFindingAge(
        val awsAccountId: String,
        val accountName: String,
        val oldestFindingFirstSeenAt: LocalDateTime,
        val oldestFindingDaysOpen: Long,
        val oldestFindingCve: String?,
        val oldestFindingSeverity: String?,
        val oldestFindingAssetName: String?,
        val oldestFindingAssetInstanceId: String?,
        val openFindingCount: Long,
        val affectedAssetCount: Long
    )

    /**
     * @throws IllegalArgumentException if [limit] is outside [MIN_LIMIT]..[MAX_LIMIT]
     */
    open fun getTopAccountsByOldestFinding(limit: Int = DEFAULT_LIMIT): List<AccountFindingAge> {
        require(limit in MIN_LIMIT..MAX_LIMIT) {
            "limit must be between $MIN_LIMIT and $MAX_LIMIT, was $limit"
        }

        val ranked = vulnerabilityRepository.findAccountsByOldestOpenFinding(limit)
            .filter { !it.awsAccountId.isNullOrBlank() && it.oldestFirstSeen != null }

        if (ranked.isEmpty()) {
            logger.debug("Account finding-age report: no accounts with open findings")
            return emptyList()
        }

        val accountIds = ranked.mapNotNull { it.awsAccountId }
        val namesById = awsAccountRepository.findByAwsAccountIdIn(accountIds)
            .associate { it.awsAccountId to it.name }

        // Single batched query for all accounts (design §2 step 2) instead of one call
        // per account inside the map below.
        val detailsByAccount = vulnerabilityRepository.findOldestFindingDetail(accountIds)
            .associateBy { it.awsAccountId }

        val now = LocalDateTime.now()

        return ranked.map { row ->
            val accountId = row.awsAccountId!!
            val firstSeen = row.oldestFirstSeen!!
            val detail = detailsByAccount[accountId]

            AccountFindingAge(
                awsAccountId = accountId,
                accountName = resolveName(accountId, namesById[accountId]),
                oldestFindingFirstSeenAt = firstSeen,
                oldestFindingDaysOpen = ChronoUnit.DAYS.between(firstSeen, now).coerceAtLeast(0),
                oldestFindingCve = detail?.cve,
                oldestFindingSeverity = detail?.severity,
                oldestFindingAssetName = detail?.assetName,
                oldestFindingAssetInstanceId = detail?.assetInstanceId,
                openFindingCount = row.openFindingCount?.toLong() ?: 0L,
                affectedAssetCount = row.affectedAssetCount?.toLong() ?: 0L
            )
        }
    }

    /** The single place the "name always present" guarantee is implemented. */
    private fun resolveName(accountId: String, storedName: String?): String =
        storedName?.trim()?.takeIf { it.isNotEmpty() } ?: accountId
}
