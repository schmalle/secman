package com.secman.dto

import com.secman.service.AccountFindingAgeService
import io.micronaut.serde.annotation.Serdeable

/**
 * Wire shape for one account in the longest-open-finding report.
 * `accountName` is never null — it falls back to the bare account ID.
 */
@Serdeable
data class AccountFindingAgeDto(
    val awsAccountId: String,
    val accountName: String,
    val oldestFindingFirstSeenAt: String,
    val oldestFindingDaysOpen: Long,
    val oldestFindingCve: String?,
    val oldestFindingSeverity: String?,
    val oldestFindingAssetName: String?,
    val oldestFindingAssetInstanceId: String?,
    val openFindingCount: Long,
    val affectedAssetCount: Long
) {
    companion object {
        fun from(row: AccountFindingAgeService.AccountFindingAge) = AccountFindingAgeDto(
            awsAccountId = row.awsAccountId,
            accountName = row.accountName,
            oldestFindingFirstSeenAt = row.oldestFindingFirstSeenAt.toString(),
            oldestFindingDaysOpen = row.oldestFindingDaysOpen,
            oldestFindingCve = row.oldestFindingCve,
            oldestFindingSeverity = row.oldestFindingSeverity,
            oldestFindingAssetName = row.oldestFindingAssetName,
            oldestFindingAssetInstanceId = row.oldestFindingAssetInstanceId,
            openFindingCount = row.openFindingCount,
            affectedAssetCount = row.affectedAssetCount
        )
    }
}
