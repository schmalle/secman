package com.secman.repository.projection

import io.micronaut.core.annotation.Introspected
import io.micronaut.serde.annotation.Serdeable

/**
 * Step-2 projection: identifying detail of the single oldest non-excepted finding
 * in one AWS account.
 */
@Serdeable
@Introspected
data class OldestFindingDetailRow(
    val awsAccountId: String?,
    val cve: String?,
    val severity: String?,
    val assetName: String?,
    val assetInstanceId: String?
)
