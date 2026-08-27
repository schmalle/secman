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
 *
 * Each recipient also carries **the asset ids that made them a recipient**, so
 * the broadcast body can be scoped to those rows instead of disclosing every
 * affected system to everyone. That linkage is a strict subset of the
 * recipient's own access: owner, manual creator, scan uploader, AWS-account
 * mapping and AD-domain mapping are criteria 3, 4, 5, 6 and 8 of §Unified Asset
 * Access. Deriving it here rather than re-running the access filter per
 * recipient costs no extra query and cannot drift from the rule that produced
 * the recipient in the first place.
 */
@Singleton
open class EolBroadcastRecipientResolver(
    private val eolFindingRepository: EolFindingRepository,
    private val assetRepository: AssetRepository,
    private val userRepository: UserRepository,
    private val userMappingRepository: UserMappingRepository,
    private val assetFilterService: AssetFilterService
) {
    /**
     * Collects the recipients for one product, deduplicated by user.
     *
     * Two things the signature cannot show. An empty list is the ordinary answer in
     * both cases that produce it — the caller reaches no assets at all, or none of
     * the ones it reaches are affected — and neither is an error. And a user who has
     * never logged in is never a recipient: every path funnels through [addUser],
     * which drops them, so an account provisioned but never used stays silent.
     */
    @Transactional
    open fun resolve(product: String, authentication: Authentication): List<EolBroadcastRecipient> {
        val accessibleAssetIds = assetFilterService.getAccessibleAssetIds(authentication)
        if (accessibleAssetIds.isEmpty()) return emptyList()

        val affectedAssetIds = eolFindingRepository.findAssetIdsByComponentNameForAssets(product, accessibleAssetIds)
        if (affectedAssetIds.isEmpty()) return emptyList()

        val recipients = linkedMapOf<Long, MutableRecipient>()
        assetRepository.findByIdIn(affectedAssetIds).forEach { asset ->
            val assetId = asset.id ?: return@forEach
            addUserByUsername(asset.owner, assetId, recipients)
            addUser(asset.manualCreator, assetId, recipients)
            addUser(asset.scanUploader, assetId, recipients)
            addMappedUsers(asset, assetId, recipients)
        }
        return recipients.values.map { EolBroadcastRecipient(it.user, it.assetIds.toSet()) }
    }

    private fun addMappedUsers(asset: Asset, assetId: Long, recipients: MutableMap<Long, MutableRecipient>) {
        asset.cloudAccountId?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { userMappingRepository.findByAwsAccountId(it) }
            ?.forEach { addMappedUser(it, assetId, recipients) }

        asset.adDomain?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
            ?.let { userMappingRepository.findByDomain(it) }
            ?.forEach { addMappedUser(it, assetId, recipients) }
    }

    private fun addMappedUser(mapping: UserMapping, assetId: Long, recipients: MutableMap<Long, MutableRecipient>) {
        mapping.user?.let {
            addUser(it, assetId, recipients)
            return
        }

        userRepository.findByEmailIgnoreCase(mapping.email).ifPresent { addUser(it, assetId, recipients) }
    }

    private fun addUserByUsername(username: String?, assetId: Long, recipients: MutableMap<Long, MutableRecipient>) {
        username?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { userRepository.findByUsername(it) }
            ?.ifPresent { addUser(it, assetId, recipients) }
    }

    private fun addUser(user: User?, assetId: Long, recipients: MutableMap<Long, MutableRecipient>) {
        val userId = user?.id ?: return
        if (user.lastLogin == null) return
        recipients.getOrPut(userId) { MutableRecipient(user) }.assetIds += assetId
    }

    /**
     * Accumulator for [EolBroadcastRecipient]. One user is commonly reached through
     * several assets and several criteria, so the ids gather here first and the
     * immutable copy is built once, at the end of [resolve].
     */
    private class MutableRecipient(val user: User, val assetIds: MutableSet<Long> = linkedSetOf())
}

/**
 * One broadcast recipient plus the affected systems that linked them to the
 * product. [assetIds] is never empty and is always a subset of what the
 * recipient may access, so it is safe to render into their copy of the mail.
 */
data class EolBroadcastRecipient(
    val user: User,
    val assetIds: Set<Long>
)
