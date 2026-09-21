package com.secman.service

import com.secman.domain.Asset
import com.secman.domain.User
import com.secman.domain.Workgroup
import com.secman.repository.AssetRepository
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupRepository
import com.secman.security.GrantAuthority
import jakarta.inject.Singleton
import jakarta.transaction.Transactional

/** Creates the asset and its initial visibility grant in one transaction. */
@Singleton
open class AssetCreationService(
    private val assets: AssetRepository,
    private val users: UserRepository,
    private val workgroups: WorkgroupRepository
) {
    /** Validate every initial grant before persisting the asset. */
    @Transactional
    open fun create(asset: Asset, actorId: Long, workgroupIds: List<Long>): Asset {
        val actor = users.findById(actorId).orElseThrow { IllegalArgumentException("Unknown actor") }
        require(workgroupIds.size <= 100) { "Select at most 100 workgroups" }
        val requested = workgroupIds.toSet()
        val groups = if (requested.isEmpty()) emptyList() else workgroups.findForPlacement(requested).toList()
        require(groups.size == requested.size) { "Workgroup unavailable" }
        validatePlacement(asset, actor, groups)
        asset.manualCreator = actor
        asset.workgroups.clear()
        asset.workgroups.addAll(groups)
        return assets.save(asset)
    }

    /** Apply identical placement rules to individual creations and spreadsheet rows. */
    fun validatePlacement(asset: Asset, actor: User, groups: Collection<Workgroup>) {
        require(actor.enabled) { "Actor is disabled" }
        val managesGrants = GrantAuthority.canManage(actor.roles.map { it.name })
        require(managesGrants || groups.isNotEmpty()) { "Select an enabled workgroup you directly belong to" }
        require(managesGrants || (asset.adDomain == null && asset.cloudAccountId == null)) {
            "Account and domain associations require ADMIN or SECCHAMPION"
        }
        require(groups.size <= 100) { "Select at most 100 workgroups" }
        groups.forEach { group ->
            require(managesGrants || (group.enabled && group.users.any { it.id == actor.id })) {
                "Workgroup unavailable"
            }
        }
    }

}
