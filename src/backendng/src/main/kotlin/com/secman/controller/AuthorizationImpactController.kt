package com.secman.controller

import com.secman.repository.AssetRepository
import com.secman.repository.UserRepository
import io.micronaut.http.annotation.*
import io.micronaut.security.annotation.Secured
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.transaction.annotation.Transactional

/** Read-only, bounded preview. Applying replacement grants is a separate reviewed operation. */
@Controller("/api/admin/authorization-impact")
@Secured("ADMIN")
@ExecuteOn(TaskExecutors.BLOCKING)
open class AuthorizationImpactController(private val users: UserRepository, private val assets: AssetRepository) {
    @Get("/{userId}{?afterId,limit}")
    @Transactional(readOnly = true)
    open fun preview(userId: Long, @QueryValue(defaultValue = "0") afterId: Long,
                     @QueryValue(defaultValue = "200") limit: Int): Map<String, Any> {
        require(afterId >= 0 && limit in 1..500)
        val user = users.findById(userId).orElseThrow { IllegalArgumentException("User not found") }
        val legacy = assets.findLegacyMetadataGrantIds(userId, user.username, afterId, limit)
        val global = user.roles.any { it.name == "ADMIN" || it.name == "SECCHAMPION" }
        val retained = if (global) legacy.toSet() else assets.findAccessibleAssetIds(userId, user.email).toSet()
        return mapOf("userId" to userId, "candidateAssetIds" to legacy,
            "removedMetadataGrantAssetIds" to legacy.filter { it !in retained },
            "nextAfterId" to (legacy.lastOrNull() ?: afterId), "hasMore" to (legacy.size == limit),
            "scope" to "OWNER_CREATOR_UPLOADER", "appliedChanges" to 0)
    }
}
