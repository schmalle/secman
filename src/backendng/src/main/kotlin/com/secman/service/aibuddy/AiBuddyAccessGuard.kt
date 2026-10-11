package com.secman.service.aibuddy

import com.secman.domain.User
import com.secman.repository.AssetRepository
import com.secman.security.BetaFeatureAccess
import com.secman.service.AssetFilterService
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

@Singleton
open class AiBuddyAccessGuard(
    private val beta: BetaFeatureAccess,
    private val assets: AssetRepository,
    private val filter: AssetFilterService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    open fun check(assetId: Long, authentication: Authentication): User {
        val user = try { beta.requireEligible(authentication) } catch (e: HttpStatusException) {
            log.warn("AI buddy actor={} asset={} outcome=denied", authentication.attributes["userId"]?.toString()?.toLongOrNull(), assetId)
            throw e
        }
        if (assetId <= 0 || !filter.canAccessAsset(assetId, beta.authentication(user)) || !assets.existsById(assetId)) {
            log.warn("AI buddy actor={} asset={} outcome=denied", user.id, assetId)
            throw HttpStatusException(HttpStatus.NOT_FOUND, "ASSET_OR_JOB_NOT_FOUND")
        }
        return user
    }
}
