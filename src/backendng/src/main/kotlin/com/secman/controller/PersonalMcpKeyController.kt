package com.secman.controller

import com.secman.domain.McpApiKey
import com.secman.domain.McpPermission
import com.secman.repository.McpApiKeyRepository
import com.secman.repository.UserRepository
import com.secman.service.McpAuthenticationService
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.*
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import jakarta.transaction.Transactional
import java.time.LocalDateTime
import org.slf4j.LoggerFactory

@Controller("/api/profile/mcp-keys")
@Secured(SecurityRule.IS_AUTHENTICATED)
open class PersonalMcpKeyController(
    private val users: UserRepository,
    private val keys: McpApiKeyRepository,
    private val auth: McpAuthenticationService
) {
    @Serdeable data class KeyInfo(val keyId: String, val expiresAt: LocalDateTime?)
    private val permission = McpPermission.PERSONAL_INVENTORY_READ.name
    private val log = LoggerFactory.getLogger(javaClass)

    @Get
    fun list(authentication: Authentication): List<KeyInfo> {
        val owner = users.findByUsername(authentication.name).orElseThrow()
        return keys.findActiveByUserId(requireNotNull(owner.id))
            .filter { it.permissions == permission && it.isValid() }
            .map { KeyInfo(it.keyId, it.expiresAt) }
    }

    @Post
    @Transactional
    open fun create(authentication: Authentication): HttpResponse<Map<String, String>> {
        val owner = users.findByUsernameForUpdate(authentication.name).orElse(null)
            ?: return HttpResponse.unauthorized()
        if (!owner.enabled) return HttpResponse.unauthorized()
        if (keys.findActiveByUserId(requireNotNull(owner.id)).count { it.permissions == permission && it.isValid() } >= 5) {
            return HttpResponse.badRequest(mapOf("message" to "Revoke an existing personal key before creating another."))
        }
        val pair = auth.generateApiKeyPair()
        val key = McpApiKey(keyId = pair.keyId, keyHash = pair.keyHash, name = "Personal inventory " + pair.keyId,
            userId = requireNotNull(owner.id), permissions = permission, expiresAt = LocalDateTime.now().plusDays(90),
            delegationEnabled = true, allowedDelegationDomains = "@" + owner.email.substringAfterLast('@'))
        keys.save(key)
        log.info("Personal inventory key created: ownerId={}, keyId={}", owner.id, key.keyId)
        return HttpResponse.created(mapOf("apiKey" to pair.keySecret, "keyId" to pair.keyId))
            .header("Cache-Control", "no-store")
    }

    @Delete("/{keyId}")
    fun revoke(keyId: String, authentication: Authentication): HttpResponse<Void> {
        val owner = users.findByUsername(authentication.name).orElseThrow()
        val key = keys.findByKeyIdAndActive(keyId).orElse(null) ?: return HttpResponse.notFound()
        if (key.userId != owner.id || key.permissions != permission) return HttpResponse.notFound()
        keys.deactivateByKeyId(keyId)
        log.info("Personal inventory key revoked: ownerId={}, keyId={}", owner.id, keyId)
        return HttpResponse.noContent()
    }
}
