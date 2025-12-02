package com.secman.cli.commands

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.secman.cli.service.WorkgroupCliService
import com.secman.domain.Asset
import com.secman.domain.Workgroup
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import picocli.CommandLine.*
import jakarta.inject.Singleton
import java.io.StringWriter

/**
 * CLI command to list workgroups and their assets
 *
 * Usage:
 *   # List all workgroups
 *   ./gradlew cli:run --args='manage-workgroups list'
 *
 *   # List workgroups matching pattern
 *   ./gradlew cli:run --args='manage-workgroups list --name "*prod*"'
 *
 *   # List assets in a specific workgroup
 *   ./gradlew cli:run --args='manage-workgroups list --workgroup Production'
 *
 *   # Search all assets by pattern (for preview before assigning)
 *   ./gradlew cli:run --args='manage-workgroups list --search-assets "ip-10-*"'
 *
 *   # Output in JSON or CSV format
 *   ./gradlew cli:run --args='manage-workgroups list --format JSON'
 */
@Singleton
@Command(
    name = "list",
    description = ["List workgroups or assets in a workgroup"],
    mixinStandardHelpOptions = true
)
class ListWorkgroupsCommand(
    private val workgroupCliService: WorkgroupCliService
) : Runnable {

    @Option(
        names = ["--workgroup", "-w"],
        description = ["Workgroup name or ID to list assets for"]
    )
    var workgroup: String? = null

    @Option(
        names = ["--name", "-n"],
        description = ["Filter workgroups by name pattern (supports * and ? wildcards)"]
    )
    var nameFilter: String? = null

    @Option(
        names = ["--search-assets", "-s"],
        description = ["Search all assets by name pattern (for preview before assigning)"]
    )
    var searchAssets: String? = null

    @Option(
        names = ["--type", "-t"],
        description = ["Filter assets by type (e.g., SERVER, WORKSTATION)"]
    )
    var assetType: String? = null

    @Option(
        names = ["--format", "-f"],
        description = ["Output format: TABLE, JSON, CSV (default: TABLE)"],
        defaultValue = "TABLE"
    )
    var format: String = "TABLE"

    @ParentCommand
    lateinit var parent: ManageWorkgroupsCommand

    override fun run() {
        try {
            when {
                // Search all assets by pattern
                !searchAssets.isNullOrBlank() -> {
                    val assets = workgroupCliService.searchAssets(searchAssets, assetType)
                    displayAssets(assets, "Assets matching pattern: $searchAssets" +
                        (if (assetType != null) " (type: $assetType)" else ""))
                }

                // List assets in a specific workgroup
                !workgroup.isNullOrBlank() -> {
                    val wg = workgroupCliService.findWorkgroup(workgroup!!)
                    if (wg == null) {
                        System.err.println("Workgroup not found: $workgroup")
                        System.exit(1)
                        return
                    }
                    val assets = workgroupCliService.listAssetsInWorkgroup(wg.id!!)
                    displayAssets(assets, "Assets in workgroup: ${wg.name}")
                }

                // List workgroups
                else -> {
                    val workgroups = workgroupCliService.listWorkgroups(nameFilter)
                    displayWorkgroups(workgroups)
                }
            }

        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            e.printStackTrace()
            System.exit(1)
        }
    }

    private fun displayWorkgroups(workgroups: List<Workgroup>) {
        when (format.uppercase()) {
            "TABLE" -> displayWorkgroupsTable(workgroups)
            "JSON" -> displayWorkgroupsJson(workgroups)
            "CSV" -> displayWorkgroupsCsv(workgroups)
            else -> {
                System.err.println("Invalid format. Use TABLE, JSON, or CSV")
                System.exit(1)
            }
        }
    }

    private fun displayWorkgroupsTable(workgroups: List<Workgroup>) {
        println("=" .repeat(80))
        println("Workgroups" + (if (nameFilter != null) " (filter: $nameFilter)" else ""))
        println("=" .repeat(80))
        println()

        if (workgroups.isEmpty()) {
            println("No workgroups found")
            return
        }

        // Table header
        println(String.format("%-6s  %-30s  %-10s  %s", "ID", "Name", "Assets", "Description"))
        println("-".repeat(80))

        workgroups.forEach { wg ->
            val assetCount = workgroupCliService.listAssetsInWorkgroup(wg.id!!).size
            val desc = (wg.description ?: "-").take(30) + if ((wg.description?.length ?: 0) > 30) "..." else ""
            println(String.format("%-6d  %-30s  %-10d  %s", wg.id, wg.name.take(30), assetCount, desc))
        }

        println()
        println("Total: ${workgroups.size} workgroup(s)")
    }

    private fun displayWorkgroupsJson(workgroups: List<Workgroup>) {
        val objectMapper = jacksonObjectMapper()
        val data = workgroups.map { wg ->
            mapOf(
                "id" to wg.id,
                "name" to wg.name,
                "description" to wg.description,
                "criticality" to wg.criticality.name,
                "assetCount" to workgroupCliService.listAssetsInWorkgroup(wg.id!!).size,
                "parentId" to wg.parent?.id,
                "createdAt" to wg.createdAt?.toString()
            )
        }
        println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data))
    }

    private fun displayWorkgroupsCsv(workgroups: List<Workgroup>) {
        val stringWriter = StringWriter()
        val csvPrinter = CSVPrinter(
            stringWriter,
            CSVFormat.DEFAULT.builder()
                .setHeader("ID", "Name", "Description", "Criticality", "Asset Count", "Parent ID", "Created At")
                .build()
        )

        workgroups.forEach { wg ->
            csvPrinter.printRecord(
                wg.id,
                wg.name,
                wg.description ?: "",
                wg.criticality.name,
                workgroupCliService.listAssetsInWorkgroup(wg.id!!).size,
                wg.parent?.id ?: "",
                wg.createdAt?.toString() ?: ""
            )
        }

        csvPrinter.flush()
        println(stringWriter.toString())
    }

    private fun displayAssets(assets: List<Asset>, title: String) {
        when (format.uppercase()) {
            "TABLE" -> displayAssetsTable(assets, title)
            "JSON" -> displayAssetsJson(assets)
            "CSV" -> displayAssetsCsv(assets)
            else -> {
                System.err.println("Invalid format. Use TABLE, JSON, or CSV")
                System.exit(1)
            }
        }
    }

    private fun displayAssetsTable(assets: List<Asset>, title: String) {
        println("=" .repeat(90))
        println(title)
        println("=" .repeat(90))
        println()

        if (assets.isEmpty()) {
            println("No assets found")
            return
        }

        // Table header
        println(String.format("%-6s  %-40s  %-12s  %s", "ID", "Name", "Type", "IP"))
        println("-".repeat(90))

        assets.forEach { asset ->
            println(String.format("%-6d  %-40s  %-12s  %s",
                asset.id,
                asset.name.take(40),
                asset.type.take(12),
                asset.ip ?: "-"
            ))
        }

        println()
        println("Total: ${assets.size} asset(s)")
    }

    private fun displayAssetsJson(assets: List<Asset>) {
        val objectMapper = jacksonObjectMapper()
        val data = assets.map { asset ->
            mapOf(
                "id" to asset.id,
                "name" to asset.name,
                "type" to asset.type,
                "ip" to asset.ip,
                "owner" to asset.owner,
                "description" to asset.description,
                "cloudAccountId" to asset.cloudAccountId,
                "adDomain" to asset.adDomain
            )
        }
        println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data))
    }

    private fun displayAssetsCsv(assets: List<Asset>) {
        val stringWriter = StringWriter()
        val csvPrinter = CSVPrinter(
            stringWriter,
            CSVFormat.DEFAULT.builder()
                .setHeader("ID", "Name", "Type", "IP", "Owner", "Cloud Account ID", "AD Domain")
                .build()
        )

        assets.forEach { asset ->
            csvPrinter.printRecord(
                asset.id,
                asset.name,
                asset.type,
                asset.ip ?: "",
                asset.owner,
                asset.cloudAccountId ?: "",
                asset.adDomain ?: ""
            )
        }

        csvPrinter.flush()
        println(stringWriter.toString())
    }
}
