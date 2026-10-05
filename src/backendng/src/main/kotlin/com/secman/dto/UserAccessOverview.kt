package com.secman.dto

import com.fasterxml.jackson.annotation.JsonInclude
import io.micronaut.serde.annotation.Serdeable

@Serdeable
data class AccessReason(val type: String, val sourceId: Long? = null, val sourceName: String? = null)

@Serdeable
data class AccessScopeEntry(
    val value: String,
    val displayName: String?,
    val visibleAssetCount: Long,
    val wholeScopeAccess: Boolean,
    val reasons: List<AccessReason>
)

@Serdeable
data class AccessOverviewAsset(
    val id: Long,
    val name: String,
    val awsAccountId: String?,
    val adDomain: String?,
    val reasons: List<AccessReason>
)

@Serdeable
data class AccessOverviewUser(val id: Long, val username: String, val email: String, val enabled: Boolean, val roles: List<String>)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class UserAccessOverview(
    val user: AccessOverviewUser,
    val generatedAt: String,
    val vulnerabilityAccess: Boolean,
    val globalAccess: Boolean,
    val totalAssets: Long,
    val awsAccounts: List<AccessScopeEntry>,
    val adDomains: List<AccessScopeEntry>,
    val assets: List<AccessOverviewAsset>,
    val page: Int,
    val size: Int,
    val totalPages: Int
)
