package com.secman.cli.service

import com.secman.domain.Asset
import com.secman.domain.Workgroup
import com.secman.repository.AssetRepository
import com.secman.repository.WorkgroupRepository
import jakarta.inject.Singleton
import io.micronaut.transaction.annotation.Transactional
import org.slf4j.LoggerFactory

/**
 * CLI service for workgroup asset management operations
 *
 * Provides business logic for assigning and removing assets from workgroups
 * via CLI commands. Supports pattern-based asset selection with wildcards.
 *
 * Usage:
 *   ./gradlew cli:run --args='manage-workgroups <subcommand> [options]'
 */
@Singleton
open class WorkgroupCliService(
    private val workgroupRepository: WorkgroupRepository,
    private val assetRepository: AssetRepository
) {
    private val log = LoggerFactory.getLogger(WorkgroupCliService::class.java)

    /**
     * List all workgroups with optional name filter
     *
     * @param nameFilter Optional name filter (partial match, case-insensitive)
     * @return List of workgroups matching criteria
     */
    fun listWorkgroups(nameFilter: String? = null): List<Workgroup> {
        val allWorkgroups = workgroupRepository.findAll().toList()

        return if (nameFilter.isNullOrBlank()) {
            allWorkgroups.sortedBy { it.name.lowercase() }
        } else {
            val pattern = wildcardToRegex(nameFilter.lowercase())
            allWorkgroups
                .filter { pattern.matches(it.name.lowercase()) }
                .sortedBy { it.name.lowercase() }
        }
    }

    /**
     * Find workgroup by name or ID
     *
     * @param nameOrId Workgroup name or numeric ID
     * @return Workgroup if found, null otherwise
     */
    fun findWorkgroup(nameOrId: String): Workgroup? {
        // Try to parse as ID first
        val id = nameOrId.toLongOrNull()
        if (id != null) {
            return workgroupRepository.findById(id).orElse(null)
        }

        // Try exact name match (case-insensitive)
        return workgroupRepository.findByNameIgnoreCase(nameOrId).orElse(null)
    }

    /**
     * List assets in a workgroup with optional filtering
     *
     * @param workgroupId Workgroup ID
     * @return List of assets in the workgroup
     */
    fun listAssetsInWorkgroup(workgroupId: Long): List<Asset> {
        return assetRepository.findByWorkgroupsIdOrderByNameAsc(workgroupId)
    }

    /**
     * Search assets by pattern with wildcard support
     *
     * Supports wildcards:
     * - * matches any characters
     * - ? matches single character
     *
     * @param pattern Search pattern (e.g., "ip-10-*", "*prod*")
     * @param type Optional asset type filter
     * @return List of matching assets
     */
    fun searchAssets(pattern: String?, type: String? = null): List<Asset> {
        val allAssets = assetRepository.findAll().toList()

        var filtered = allAssets

        // Filter by type if specified
        if (!type.isNullOrBlank()) {
            filtered = filtered.filter { it.type.equals(type, ignoreCase = true) }
        }

        // Filter by pattern if specified
        if (!pattern.isNullOrBlank()) {
            val regex = wildcardToRegex(pattern.lowercase())
            filtered = filtered.filter { asset ->
                regex.matches(asset.name.lowercase())
            }
        }

        return filtered.sortedBy { it.name.lowercase() }
    }

    /**
     * Assign assets to a workgroup
     *
     * @param workgroupId Workgroup ID
     * @param assetIds List of asset IDs to assign
     * @param adminEmail Admin user performing the operation
     * @return Result with counts and details
     */
    @Transactional
    open fun assignAssets(
        workgroupId: Long,
        assetIds: List<Long>,
        adminEmail: String
    ): WorkgroupOperationResult {
        val workgroup = workgroupRepository.findById(workgroupId).orElse(null)
            ?: return WorkgroupOperationResult(
                success = false,
                message = "Workgroup not found with ID: $workgroupId",
                assigned = 0,
                skipped = 0,
                errors = listOf("Workgroup not found")
            )

        var assigned = 0
        var skipped = 0
        val errors = mutableListOf<String>()
        val assignedAssetNames = mutableListOf<String>()

        for (assetId in assetIds) {
            val asset = assetRepository.findById(assetId).orElse(null)
            if (asset == null) {
                errors.add("Asset not found with ID: $assetId")
                continue
            }

            // Check if already assigned
            if (asset.workgroups.any { it.id == workgroupId }) {
                skipped++
                continue
            }

            // Assign to workgroup
            asset.workgroups.add(workgroup)
            assetRepository.update(asset)
            assigned++
            assignedAssetNames.add(asset.name)

            log.info("AUDIT: operation=ASSIGN_ASSET, actor=$adminEmail, " +
                "workgroup=${workgroup.name}, asset=${asset.name}")
        }

        return WorkgroupOperationResult(
            success = errors.isEmpty(),
            message = "Assigned $assigned assets to workgroup '${workgroup.name}' " +
                "(skipped $skipped already assigned)",
            assigned = assigned,
            skipped = skipped,
            errors = errors,
            assetNames = assignedAssetNames
        )
    }

    /**
     * Assign assets to a workgroup by pattern
     *
     * @param workgroupId Workgroup ID
     * @param pattern Asset name pattern with wildcards
     * @param type Optional asset type filter
     * @param adminEmail Admin user performing the operation
     * @return Result with counts and details
     */
    @Transactional
    open fun assignAssetsByPattern(
        workgroupId: Long,
        pattern: String,
        type: String? = null,
        adminEmail: String
    ): WorkgroupOperationResult {
        val matchingAssets = searchAssets(pattern, type)

        if (matchingAssets.isEmpty()) {
            return WorkgroupOperationResult(
                success = true,
                message = "No assets found matching pattern: $pattern" +
                    (if (type != null) " (type: $type)" else ""),
                assigned = 0,
                skipped = 0,
                errors = emptyList()
            )
        }

        return assignAssets(
            workgroupId = workgroupId,
            assetIds = matchingAssets.mapNotNull { it.id },
            adminEmail = adminEmail
        )
    }

    /**
     * Remove assets from a workgroup
     *
     * @param workgroupId Workgroup ID
     * @param assetIds List of asset IDs to remove
     * @param adminEmail Admin user performing the operation
     * @return Result with counts and details
     */
    @Transactional
    open fun removeAssets(
        workgroupId: Long,
        assetIds: List<Long>,
        adminEmail: String
    ): WorkgroupOperationResult {
        val workgroup = workgroupRepository.findById(workgroupId).orElse(null)
            ?: return WorkgroupOperationResult(
                success = false,
                message = "Workgroup not found with ID: $workgroupId",
                removed = 0,
                skipped = 0,
                errors = listOf("Workgroup not found")
            )

        var removed = 0
        var skipped = 0
        val errors = mutableListOf<String>()
        val removedAssetNames = mutableListOf<String>()

        for (assetId in assetIds) {
            val asset = assetRepository.findById(assetId).orElse(null)
            if (asset == null) {
                errors.add("Asset not found with ID: $assetId")
                continue
            }

            // Check if assigned to workgroup
            if (!asset.workgroups.any { it.id == workgroupId }) {
                skipped++
                continue
            }

            // Remove from workgroup
            asset.workgroups.removeIf { it.id == workgroupId }
            assetRepository.update(asset)
            removed++
            removedAssetNames.add(asset.name)

            log.info("AUDIT: operation=REMOVE_ASSET, actor=$adminEmail, " +
                "workgroup=${workgroup.name}, asset=${asset.name}")
        }

        return WorkgroupOperationResult(
            success = errors.isEmpty(),
            message = "Removed $removed assets from workgroup '${workgroup.name}' " +
                "(skipped $skipped not assigned)",
            removed = removed,
            skipped = skipped,
            errors = errors,
            assetNames = removedAssetNames
        )
    }

    /**
     * Remove assets from a workgroup by pattern
     *
     * @param workgroupId Workgroup ID
     * @param pattern Asset name pattern with wildcards
     * @param type Optional asset type filter
     * @param adminEmail Admin user performing the operation
     * @return Result with counts and details
     */
    @Transactional
    open fun removeAssetsByPattern(
        workgroupId: Long,
        pattern: String,
        type: String? = null,
        adminEmail: String
    ): WorkgroupOperationResult {
        // Get assets currently in the workgroup
        val assetsInWorkgroup = listAssetsInWorkgroup(workgroupId)

        if (assetsInWorkgroup.isEmpty()) {
            return WorkgroupOperationResult(
                success = true,
                message = "Workgroup has no assets to remove",
                removed = 0,
                skipped = 0,
                errors = emptyList()
            )
        }

        // Apply pattern filter
        val regex = wildcardToRegex(pattern.lowercase())
        var matchingAssets = assetsInWorkgroup.filter { regex.matches(it.name.lowercase()) }

        // Apply type filter if specified
        if (!type.isNullOrBlank()) {
            matchingAssets = matchingAssets.filter { it.type.equals(type, ignoreCase = true) }
        }

        if (matchingAssets.isEmpty()) {
            return WorkgroupOperationResult(
                success = true,
                message = "No assets in workgroup matching pattern: $pattern" +
                    (if (type != null) " (type: $type)" else ""),
                removed = 0,
                skipped = 0,
                errors = emptyList()
            )
        }

        return removeAssets(
            workgroupId = workgroupId,
            assetIds = matchingAssets.mapNotNull { it.id },
            adminEmail = adminEmail
        )
    }

    /**
     * Remove all assets from a workgroup
     *
     * @param workgroupId Workgroup ID
     * @param adminEmail Admin user performing the operation
     * @return Result with counts
     */
    @Transactional
    open fun removeAllAssets(
        workgroupId: Long,
        adminEmail: String
    ): WorkgroupOperationResult {
        val assets = listAssetsInWorkgroup(workgroupId)
        if (assets.isEmpty()) {
            return WorkgroupOperationResult(
                success = true,
                message = "Workgroup has no assets to remove",
                removed = 0,
                skipped = 0,
                errors = emptyList()
            )
        }

        return removeAssets(
            workgroupId = workgroupId,
            assetIds = assets.mapNotNull { it.id },
            adminEmail = adminEmail
        )
    }

    /**
     * Convert wildcard pattern to regex
     * Supports: * (any chars), ? (single char)
     */
    private fun wildcardToRegex(pattern: String): Regex {
        val regexPattern = pattern
            .replace("\\", "\\\\")
            .replace(".", "\\.")
            .replace("+", "\\+")
            .replace("^", "\\^")
            .replace("$", "\\$")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("|", "\\|")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("*", ".*")
            .replace("?", ".")

        return Regex("^$regexPattern$")
    }
}

/**
 * Result of a workgroup operation
 */
data class WorkgroupOperationResult(
    val success: Boolean,
    val message: String,
    val assigned: Int = 0,
    val removed: Int = 0,
    val skipped: Int,
    val errors: List<String>,
    val assetNames: List<String> = emptyList()
)
