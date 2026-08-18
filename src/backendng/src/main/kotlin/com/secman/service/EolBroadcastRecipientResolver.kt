package com.secman.service

import com.secman.domain.Asset
import com.secman.domain.User
import com.secman.domain.UserMapping
import com.secman.repository.AssetRepository
import com.secman.repository.EolFindingRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton
import jakarta.transaction.Transactional

/**
 * Resolves who to email about one EOL product: the owner, creator, uploader and
 * mapped users of every accessible asset that product is EOL or approaching EOL
 * on. Mirrors [ProductBroadcastRecipientResolver]'s recipient-collection shape —
 * the only difference is the affected-asset set, which here comes from
 * [EolFindingRepository] instead of installed-product/vulnerability data.
 *
 * The asset id set is scoped through [AssetFilterService.getAccessibleAssetIds]
 * before it ever reaches the repository query, so the resolver can only ever
 * mail owners of systems the caller can already see (CLAUDE.md §A01) — callers
 * are ADMIN/SECCHAMPION, both universal-access roles for this check.
 */
@Singleton
open class EolBroadcastRecipientResolver(
    private val eolFindingRepository: EolFindingRepository,
    private val assetRepository: AssetRepository,
    private val userRepository: UserRepository,
    private val userMappingRepository: UserMappingRepository,
    private val assetFilterService: AssetFilterService
) {
    @Transactional
    open fun resolve(product: String, authentication: Authentication): List<User> {
        val accessibleAssetIds = assetFilterService.getAccessibleAssetIds(authentication)
        if (accessibleAssetIds.isEmpty()) return emptyList()

        val affectedAssetIds = eolFindingRepository.findAssetIdsByComponentNameForAssets(product, accessibleAssetIds)
        if (affectedAssetIds.isEmpty()) return emptyList()

        val recipients = linkedMapOf<Long, User>()
        assetRepository.findByIdIn(affectedAssetIds).forEach { asset ->
            addUserByUsername(asset.owner, recipients)
            addUser(asset.manualCreator, recipients)
            addUser(asset.scanUploader, recipients)
            addMappedUsers(asset, recipients)
        }
        return recipients.values.toList()
    }

    private fun addMappedUsers(asset: Asset, recipients: MutableMap<Long, User>) {
        asset.cloudAccountId?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { userMappingRepository.findByAwsAccountId(it) }
            ?.forEach { addMappedUser(it, recipients) }

        asset.adDomain?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
            ?.let { userMappingRepository.findByDomain(it) }
            ?.forEach { addMappedUser(it, recipients) }
    }

    private fun addMappedUser(mapping: UserMapping, recipients: MutableMap<Long, User>) {
        mapping.user?.let {
            addUser(it, recipients)
            return
        }

        userRepository.findByEmailIgnoreCase(mapping.email).ifPresent { addUser(it, recipients) }
    }

    private fun addUserByUsername(username: String?, recipients: MutableMap<Long, User>) {
        username?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { userRepository.findByUsername(it) }
            ?.ifPresent { addUser(it, recipients) }
    }

    private fun addUser(user: User?, recipients: MutableMap<Long, User>) {
        val userId = user?.id ?: return
        if (user.lastLogin == null) return
        recipients.putIfAbsent(userId, user)
    }
}
