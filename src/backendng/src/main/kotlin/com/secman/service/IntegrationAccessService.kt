package com.secman.service

import com.secman.domain.IntegrationScanner
import com.secman.repository.UserRepository
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton

@Singleton
class IntegrationAccessService(private val assets: AssetFilterService, private val users: UserRepository) {
    private val log = org.slf4j.LoggerFactory.getLogger(IntegrationAccessService::class.java)
    fun requireAdmin(auth: Authentication) {
        if ("ADMIN" !in auth.roles) throw HttpStatusException(HttpStatus.FORBIDDEN, "Administrator required")
    }
    fun requireAsset(assetId: Long, auth: Authentication) {
        if (!assets.canAccessAsset(assetId, auth)) notFound()
    }
    fun assetIds(auth: Authentication): Set<Long> = assets.getAccessibleAssetIds(auth)
    fun requireWriter(scanner: IntegrationScanner, auth: Authentication) {
        val user = users.findByUsername(auth.name).orElse(null)
        if (!scanner.enabled || user?.id != scanner.serviceUserId) {
            log.warn("Integration submission denied: actorId={} scannerId={} outcome=denied", user?.id, scanner.id)
            throw HttpStatusException(HttpStatus.FORBIDDEN, "Scanner submission is not permitted")
        }
    }
    fun notFound(): Nothing = throw HttpStatusException(HttpStatus.NOT_FOUND, "Integration resource not found")
}
