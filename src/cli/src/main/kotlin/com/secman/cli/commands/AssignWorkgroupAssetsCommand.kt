package com.secman.cli.commands

import com.secman.cli.service.WorkgroupCliService
import picocli.CommandLine.*
import jakarta.inject.Singleton

/**
 * CLI command to assign assets to a workgroup
 *
 * Supports assigning assets by:
 * - Pattern matching (wildcards)
 * - Specific asset IDs
 * - Asset type filtering
 *
 * Usage:
 *   # Assign assets by pattern
 *   ./gradlew cli:run --args='manage-workgroups assign-assets --workgroup Production --pattern "ip-10-*"'
 *
 *   # Assign specific assets by ID
 *   ./gradlew cli:run --args='manage-workgroups assign-assets --workgroup Production --ids 1,2,3'
 *
 *   # Assign assets by pattern and type
 *   ./gradlew cli:run --args='manage-workgroups assign-assets --workgroup Production --pattern "*prod*" --type SERVER'
 *
 *   # Dry run (preview without changes)
 *   ./gradlew cli:run --args='manage-workgroups assign-assets --workgroup Production --pattern "*" --dry-run'
 */
@Singleton
@Command(
    name = "assign-assets",
    description = ["Assign assets to a workgroup"],
    mixinStandardHelpOptions = true
)
class AssignWorkgroupAssetsCommand(
    private val workgroupCliService: WorkgroupCliService
) : Runnable {

    @Option(
        names = ["--workgroup", "-w"],
        description = ["Target workgroup name or ID"],
        required = true
    )
    lateinit var workgroup: String

    @Option(
        names = ["--pattern", "-p"],
        description = ["Asset name pattern with wildcards (* = any chars, ? = single char)"]
    )
    var pattern: String? = null

    @Option(
        names = ["--ids", "-i"],
        description = ["Comma-separated list of asset IDs to assign"]
    )
    var assetIds: String? = null

    @Option(
        names = ["--type", "-t"],
        description = ["Filter assets by type (e.g., SERVER, WORKSTATION)"]
    )
    var assetType: String? = null

    @Option(
        names = ["--dry-run", "-d"],
        description = ["Preview matching assets without making changes"]
    )
    var dryRun: Boolean = false

    @Option(
        names = ["--verbose", "-v"],
        description = ["Show detailed output including asset names"]
    )
    var verbose: Boolean = false

    @ParentCommand
    lateinit var parent: ManageWorkgroupsCommand

    override fun run() {
        try {
            // Validate that at least one selection method is provided
            if (pattern.isNullOrBlank() && assetIds.isNullOrBlank()) {
                System.err.println("Error: Must specify either --pattern or --ids")
                System.exit(1)
                return
            }

            // Find workgroup
            val wg = workgroupCliService.findWorkgroup(workgroup)
            if (wg == null) {
                System.err.println("Error: Workgroup not found: $workgroup")
                System.exit(1)
                return
            }

            // Dry run mode - just show what would be assigned
            if (dryRun) {
                executeDryRun(wg.id!!)
                return
            }

            // Get admin user
            val adminEmail = parent.getAdminUserOrThrow()

            // Execute assignment
            val result = if (!pattern.isNullOrBlank()) {
                workgroupCliService.assignAssetsByPattern(
                    workgroupId = wg.id!!,
                    pattern = pattern!!,
                    type = assetType,
                    adminEmail = adminEmail
                )
            } else {
                val ids = assetIds!!.split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .mapNotNull { it.toLongOrNull() }

                if (ids.isEmpty()) {
                    System.err.println("Error: No valid asset IDs provided")
                    System.exit(1)
                    return
                }

                workgroupCliService.assignAssets(
                    workgroupId = wg.id!!,
                    assetIds = ids,
                    adminEmail = adminEmail
                )
            }

            // Display result
            println()
            if (result.success) {
                println("SUCCESS: ${result.message}")
            } else {
                println("COMPLETED WITH ERRORS: ${result.message}")
            }

            println()
            println("Summary:")
            println("  - Assigned: ${result.assigned}")
            println("  - Skipped (already assigned): ${result.skipped}")

            if (result.errors.isNotEmpty()) {
                println("  - Errors: ${result.errors.size}")
                result.errors.forEach { println("    - $it") }
            }

            if (verbose && result.assetNames.isNotEmpty()) {
                println()
                println("Assigned assets:")
                result.assetNames.forEach { println("  - $it") }
            }

        } catch (e: IllegalArgumentException) {
            System.err.println("Error: ${e.message}")
            System.exit(1)
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            e.printStackTrace()
            System.exit(1)
        }
    }

    private fun executeDryRun(workgroupId: Long) {
        println("DRY RUN - No changes will be made")
        println()

        val matchingAssets = if (!pattern.isNullOrBlank()) {
            workgroupCliService.searchAssets(pattern, assetType)
        } else {
            val ids = assetIds!!.split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .mapNotNull { it.toLongOrNull() }

            // Get all assets and filter by IDs
            workgroupCliService.searchAssets(null, null)
                .filter { ids.contains(it.id) }
        }

        if (matchingAssets.isEmpty()) {
            println("No assets match the specified criteria")
            return
        }

        // Get current workgroup assets
        val currentAssets = workgroupCliService.listAssetsInWorkgroup(workgroupId)
        val currentAssetIds = currentAssets.mapNotNull { it.id }.toSet()

        val newAssets = matchingAssets.filter { !currentAssetIds.contains(it.id) }
        val alreadyAssigned = matchingAssets.filter { currentAssetIds.contains(it.id) }

        println("Assets that would be assigned:")
        if (newAssets.isEmpty()) {
            println("  (none - all matching assets already assigned)")
        } else {
            newAssets.forEach { asset ->
                println("  - ${asset.name} (${asset.type})")
            }
        }

        if (alreadyAssigned.isNotEmpty()) {
            println()
            println("Assets already assigned (would be skipped):")
            alreadyAssigned.forEach { asset ->
                println("  - ${asset.name} (${asset.type})")
            }
        }

        println()
        println("Summary: ${newAssets.size} would be assigned, ${alreadyAssigned.size} already assigned")
    }
}
