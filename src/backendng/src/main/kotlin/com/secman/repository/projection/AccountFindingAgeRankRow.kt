package com.secman.repository.projection

import io.micronaut.core.annotation.Introspected
import io.micronaut.serde.annotation.Serdeable
import java.math.BigInteger
import java.time.LocalDateTime

/**
 * Step-1 projection: one row per AWS account, ranked by the age of its oldest
 * non-excepted finding.
 */
@Serdeable
@Introspected
data class AccountFindingAgeRankRow(
    val awsAccountId: String?,
    val oldestFirstSeen: LocalDateTime?,
    val openFindingCount: BigInteger?,
    val affectedAssetCount: BigInteger?
)
