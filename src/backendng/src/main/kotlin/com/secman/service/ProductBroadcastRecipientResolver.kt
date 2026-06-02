package com.secman.service

import com.secman.domain.Asset
import com.secman.domain.User
import com.secman.domain.UserMapping
import com.secman.repository.AssetRepository
import com.secman.repository.InstalledProductRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import jakarta.inject.Singleton
import jakarta.transaction.Transactional

@Singleton
open class ProductBroadcastRecipientResolver(
    private val assetRepository: AssetRepository,
    private val installedProductRepository: InstalledProductRepository,
    private val userRepository: UserRepository,
    private val userMappingRepository: UserMappingRepository
) {
    @Transactional
    open fun resolve(productName: String): List<User> {
        val recipients = linkedMapOf<Long, User>()
        val assets = (
            assetRepository.findAssetsByProductForAllNoLimit(productName) +
                installedProductRepository.findAssetsByProductName(productName)
            )

        assets.forEach { asset ->
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
