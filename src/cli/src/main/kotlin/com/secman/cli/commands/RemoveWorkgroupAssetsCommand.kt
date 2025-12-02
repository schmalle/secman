package com.secman.cli.commands

import com.secman.cli.service.WorkgroupCliService
import picocli.CommandLine.*
import jakarta.inject.Singleton

/**
 * CLI command to remove assets from a workgroup
 *
 * Supports removing assets by:
 * - Pattern matching (wildcards)
 * - Specific asset IDs
 * - All assets at once
 *
 * Usage:
 *   # Remove assets by pattern
 *   ./gradlew cli:run --args='manage-workgroups remove-assets --workgroup Test --pattern "*test*"'
 *
 *   # Remove specific assets by ID
 *   ./gradlew cli:run --args='manage-workgroups remove-assets --workgroup Test --ids 1,2,3'
 *
 *   # Remove all assets from workgroup
 *   ./gradlew cli:run --args='manage-workgroups remove-assets --workgroup Test --all'
 *
 *   # Dry run (preview without changes)
 *   ./gradlew cli:run --args='manage-workgroups remove-assets --workgroup Test --pattern "*" --dry-run'
 */
@Singleton
@Command(
    name = "remove-assets",
    description = ["Remove assets from a workgroup"],
    mixinStandardHelpOptions = true
)
class RemoveWorkgroupAssetsCommand(
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
        description = ["Comma-separated list of asset IDs to remove"]
    )
    var assetIds: String? = null

    @Option(
        names = ["--type", "-t"],
        description = ["Filter assets by type (e.g., SERVER, WORKSTATION)"]
    )
    var assetType: String? = null

    @Option(
        names = ["--all", "-a"],
        description = ["Remove all assets from the workgroup"]
    )
    var removeAll: Boolean = false

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

    @Option(
        names = ["--force", "-f"],
        description = ["Skip confirmation prompt for --all"]
    )
    var force: Boolean = false

    @ParentCommand
    lateinit var parent: ManageWorkgroupsCommand

    override fun run() {
        try {
            // Validate that at least one selection method is provided
            if (pattern.isNullOrBlank() && assetIds.isNullOrBlank() && !removeAll) {
                System.err.println("Error: Must specify either --pattern, --ids, or --all")
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

            // Dry run mode - just show what would be removed
            if (dryRun) {
                executeDryRun(wg.id!!)
                return
            }

            // Confirm removal of all assets
            if (removeAll && !force) {
                val assetCount = workgroupCliService.listAssetsInWorkgroup(wg.id!!).size
                print("Are you sure you want to remove all $assetCount assets from '${wg.name}'? [y/N]: ")
                val answer = readLine()?.trim()?.lowercase()
                if (answer != "y" && answer != "yes") {
                    println("Cancelled")
                    return
                }
            }

            // Get admin user
            val adminEmail = parent.getAdminUserOrThrow()

            // Execute removal
            val result = when {
                removeAll -> {
                    workgroupCliService.removeAllAssets(
                        workgroupId = wg.id!!,
                        adminEmail = adminEmail
                    )
                }
                !pattern.isNullOrBlank() -> {
                    workgroupCliService.removeAssetsByPattern(
                        workgroupId = wg.id!!,
                        pattern = pattern!!,
                        type = assetType,
                        adminEmail = adminEmail
                    )
                }
                else -> {
                    val ids = assetIds!!.split(",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .mapNotNull { it.toLongOrNull() }

                    if (ids.isEmpty()) {
                        System.err.println("Error: No valid asset IDs provided")
                        System.exit(1)
                        return
                    }

                    workgroupCliService.removeAssets(
                        workgroupId = wg.id!!,
                        assetIds = ids,
                        adminEmail = adminEmail
                    )
                }
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
            println("  - Removed: ${result.removed}")
            println("  - Skipped (not in workgroup): ${result.skipped}")

            if (result.errors.isNotEmpty()) {
                println("  - Errors: ${result.errors.size}")
                result.errors.forEach { println("    - $it") }
            }

            if (verbose && result.assetNames.isNotEmpty()) {
                println()
                println("Removed assets:")
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

        // Get current workgroup assets
        val currentAssets = workgroupCliService.listAssetsInWorkgroup(workgroupId)

        if (currentAssets.isEmpty()) {
            println("Workgroup has no assets")
            return
        }

        val assetsToRemove = when {
            removeAll -> currentAssets
            !pattern.isNullOrBlank() -> {
                // Build regex from pattern
                val regexPattern = pattern!!.lowercase()
                    .replace("\\", "\\\\")
                    .replace(".", "\\.")
                    .replace("*", ".*")
                    .replace("?", ".")
                val regex = Regex("^$regexPattern$")

                var filtered = currentAssets.filter { regex.matches(it.name.lowercase()) }
                if (!assetType.isNullOrBlank()) {
                    filtered = filtered.filter { it.type.equals(assetType, ignoreCase = true) }
                }
                filtered
            }
            else -> {
                val ids = assetIds!!.split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .mapNotNull { it.toLongOrNull() }
                    .toSet()

                currentAssets.filter { ids.contains(it.id) }
            }
        }

        println("Assets that would be removed:")
        if (assetsToRemove.isEmpty()) {
            println("  (none match the criteria)")
        } else {
            assetsToRemove.forEach { asset ->
                println("  - ${asset.name} (${asset.type})")
            }
        }

        val remaining = currentAssets.size - assetsToRemove.size
        println()
        println("Summary: ${assetsToRemove.size} would be removed, $remaining would remain")
    }
}
