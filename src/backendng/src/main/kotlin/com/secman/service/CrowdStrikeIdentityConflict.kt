package com.secman.service

/** Safe import diagnostics for an authorized actor; never contains persistence errors. */
class CrowdStrikeIdentityConflict(
    val code: String,
    message: String,
    val assetId: Long? = null,
    val formerInstanceId: String? = null,
    val instanceId: String? = null,
    val aid: String? = null
) : IllegalArgumentException(message)
