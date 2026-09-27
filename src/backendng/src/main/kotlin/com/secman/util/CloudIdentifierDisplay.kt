package com.secman.util

/** Canonical cloud identifiers exposed by inventory list views. */
object CloudIdentifierDisplay {
    private val awsAccountId = Regex("^[0-9]{12}$")
    private val ec2InstanceId = Regex("^i-(?:[0-9a-f]{8}|[0-9a-f]{17})$", RegexOption.IGNORE_CASE)

    fun accountId(value: String?): String? = value?.trim()?.takeIf(awsAccountId::matches)

    fun instanceId(value: String?): String? = value?.trim()?.takeIf(ec2InstanceId::matches)?.lowercase()
}
