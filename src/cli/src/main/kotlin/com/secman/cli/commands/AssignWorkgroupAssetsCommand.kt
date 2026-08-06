package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import picocli.CommandLine.*
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * CLI command to assign assets to a workgroup via backend HTTP API.
 */
@Singleton
@Command(
    name = "assign-assets",
    description = ["Assign assets to a workgroup"],
    mixinStandardHelpOptions = true
)
class AssignWorkgroupAssetsCommand : Runnable {

    @Inject
    lateinit var cliHttpClient: CliHttpClient

    @Option(names = ["--workgroup", "-w"], description = ["Target workgroup name or ID"], required = true)
    lateinit var workgroup: String

    @Option(names = ["--pattern", "-p"], description = ["Asset name pattern with wildcards (* = any chars, ? = single char)"])
    var pattern: String? = null

    @Option(names = ["--ids", "-i"], description = ["Comma-separated list of asset IDs to assign"])
    var assetIds: String? = null

    @Option(names = ["--type", "-t"], description = ["Filter assets by type (e.g., SERVER, WORKSTATION)"])
    var assetType: String? = null

    @Option(names = ["--dry-run", "-d"], description = ["Preview matching assets without making changes"])
    var dryRun: Boolean = false

    @Option(names = ["--verbose", "-v"], description = ["Show detailed output including asset names"])
    var verbose: Boolean = false

    @ParentCommand
    lateinit var parent: ManageWorkgroupsCommand

    override fun run() {
        try {
            if (pattern.isNullOrBlank() && assetIds.isNullOrBlank()) {
                System.err.println("Error: Must specify either --pattern or --ids")
                System.exit(1)
                return
            }

            val effectiveUrl = parent.getEffectiveBackendUrl()
            val authToken = cliHttpClient.authenticate(
                parent.getEffectiveUsername(), parent.getEffectivePassword(), effectiveUrl
            ) ?: throw RuntimeException("Authentication failed. Check credentials.")

            // Find workgroup
            val workgroups = cliHttpClient.getList("$effectiveUrl/api/workgroups", authToken) ?: emptyList()
            val wg = findWorkgroup(workgroups, workgroup)
            if (wg == null) {
                System.err.println("Error: Workgroup not found: $workgroup")
                System.exit(1)
                return
            }
            val wgId = (wg["id"] as? Number)?.toLong() ?: 0

            // Build request
            val requestBody = mutableMapOf<String, Any?>(
                "dryRun" to dryRun
            )

            if (!pattern.isNullOrBlank()) {
                requestBody["pattern"] = pattern
                if (assetType != null) requestBody["type"] = assetType
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
                requestBody["assetIds"] = ids
            }

            val result = cliHttpClient.postMap(
                "$effectiveUrl/api/workgroups/$wgId/cli/assign-assets",
                requestBody,
                authToken
            ) ?: throw RuntimeException("Failed to assign assets - no response from server")

            val success = result["success"] as? Boolean ?: false
            val message = result["message"]?.toString() ?: ""
            val assigned = (result["assigned"] as? Number)?.toInt() ?: 0
            val skipped = (result["skipped"] as? Number)?.toInt() ?: 0
            @Suppress("UNCHECKED_CAST")
            val errors = (result["errors"] as? List<String>) ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val assetNames = (result["assetNames"] as? List<String>) ?: emptyList()

            if (dryRun) {
                println("DRY RUN - No changes will be made")
                println()
            }

            println()
            if (success) {
                println("SUCCESS: $message")
            } else {
                println("COMPLETED WITH ERRORS: $message")
            }

            println()
            println("Summary:")
            println("  - Assigned: $assigned")
            println("  - Skipped (already assigned): $skipped")

            if (errors.isNotEmpty()) {
                println("  - Errors: ${errors.size}")
                errors.forEach { println("    - $it") }
            }

            if (verbose && assetNames.isNotEmpty()) {
                println()
                println("Assigned assets:")
                assetNames.forEach { println("  - $it") }
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

    private fun findWorkgroup(workgroups: List<Map<String, Any?>>, nameOrId: String): Map<String, Any?>? {
        val id = nameOrId.toLongOrNull()
        if (id != null) {
            return workgroups.find { (it["id"] as? Number)?.toLong() == id }
        }
        return workgroups.find { (it["name"]?.toString() ?: "").equals(nameOrId, ignoreCase = true) }
    }
}
