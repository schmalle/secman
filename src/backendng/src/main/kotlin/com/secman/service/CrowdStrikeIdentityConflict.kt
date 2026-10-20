package com.secman.service

/** Safe import diagnostics for an authorized actor; never contains persistence errors. */
class CrowdStrikeIdentityConflict(
    val code: String,
    message: String,
    val assetId: Long? = null,
    val formerInstanceId: String? = null,
    val instanceId: String? = null,
    val aid: String? = null,
    val assetUpdatedAt: java.time.LocalDateTime? = null
) : IllegalArgumentException(message)

/** A safe operator reason for refusing deletion after the target was rechecked. */
class CrowdStrikeRecoveryBlocked(val reason: String, message: String) : IllegalStateException(message)
