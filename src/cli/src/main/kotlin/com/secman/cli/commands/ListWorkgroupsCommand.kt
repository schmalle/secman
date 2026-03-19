package com.secman.cli.commands

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.secman.cli.service.CliHttpClient
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import picocli.CommandLine.*
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.io.StringWriter

/**
 * CLI command to list workgroups and their assets via backend HTTP API.
 */
@Singleton
@Command(
    name = "list",
    description = ["List workgroups or assets in a workgroup"],
    mixinStandardHelpOptions = true
)
class ListWorkgroupsCommand : Runnable {

    @Inject
    lateinit var cliHttpClient: CliHttpClient

    @Option(names = ["--workgroup", "-w"], description = ["Workgroup name or ID to list assets for"])
    var workgroup: String? = null

    @Option(names = ["--name", "-n"], description = ["Filter workgroups by name pattern (supports * and ? wildcards)"])
    var nameFilter: String? = null

    @Option(names = ["--search-assets", "-s"], description = ["Search all assets by name pattern"])
    var searchAssets: String? = null

    @Option(names = ["--type", "-t"], description = ["Filter assets by type (e.g., SERVER, WORKSTATION)"])
    var assetType: String? = null

    @Option(names = ["--format", "-f"], description = ["Output format: TABLE, JSON, CSV (default: TABLE)"], defaultValue = "TABLE")
    var format: String = "TABLE"

    @ParentCommand
    lateinit var parent: ManageWorkgroupsCommand

    override fun run() {
        try {
            val effectiveUrl = parent.getEffectiveBackendUrl()
            val authToken = cliHttpClient.authenticate(
                parent.getEffectiveUsername(), parent.getEffectivePassword(), effectiveUrl
            ) ?: throw RuntimeException("Authentication failed. Check credentials.")

            when {
                !searchAssets.isNullOrBlank() -> {
                    val typeParam = if (assetType != null) "&type=$assetType" else ""
                    val assets = cliHttpClient.getList(
                        "$effectiveUrl/api/workgroups/cli/search-assets?pattern=$searchAssets$typeParam",
                        authToken
                    ) ?: emptyList()
                    displayAssets(assets, "Assets matching pattern: $searchAssets" +
                        (if (assetType != null) " (type: $assetType)" else ""))
                }

                !workgroup.isNullOrBlank() -> {
                    // Find workgroup by name or ID
                    val workgroups = cliHttpClient.getList("$effectiveUrl/api/workgroups", authToken) ?: emptyList()
                    val wg = findWorkgroupInList(workgroups, workgroup!!)
                    if (wg == null) {
                        System.err.println("Workgroup not found: $workgroup")
                        System.exit(1)
                        return
                    }
                    val wgId = (wg["id"] as? Number)?.toLong() ?: 0
                    val wgName = wg["name"]?.toString() ?: workgroup!!
                    val assets = cliHttpClient.getList(
                        "$effectiveUrl/api/workgroups/$wgId/cli/assets", authToken
                    ) ?: emptyList()
                    displayAssets(assets, "Assets in workgroup: $wgName")
                }

                else -> {
                    val workgroups = cliHttpClient.getList("$effectiveUrl/api/workgroups", authToken) ?: emptyList()
                    val filtered = if (nameFilter.isNullOrBlank()) {
                        workgroups
                    } else {
                        val regex = wildcardToRegex(nameFilter!!.lowercase())
                        workgroups.filter { wg ->
                            regex.matches((wg["name"]?.toString() ?: "").lowercase())
                        }
                    }
                    displayWorkgroups(filtered)
                }
            }

        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            e.printStackTrace()
            System.exit(1)
        }
    }

    private fun findWorkgroupInList(workgroups: List<Map<String, Any?>>, nameOrId: String): Map<String, Any?>? {
        val id = nameOrId.toLongOrNull()
        if (id != null) {
            return workgroups.find { (it["id"] as? Number)?.toLong() == id }
        }
        return workgroups.find { (it["name"]?.toString() ?: "").equals(nameOrId, ignoreCase = true) }
    }

    private fun displayWorkgroups(workgroups: List<Map<String, Any?>>) {
        when (format.uppercase()) {
            "TABLE" -> displayWorkgroupsTable(workgroups)
            "JSON" -> {
                val objectMapper = jacksonObjectMapper()
                println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(workgroups))
            }
            "CSV" -> displayWorkgroupsCsv(workgroups)
            else -> {
                System.err.println("Invalid format. Use TABLE, JSON, or CSV")
                System.exit(1)
            }
        }
    }

    private fun displayWorkgroupsTable(workgroups: List<Map<String, Any?>>) {
        println("=" .repeat(80))
        println("Workgroups" + (if (nameFilter != null) " (filter: $nameFilter)" else ""))
        println("=" .repeat(80))
        println()

        if (workgroups.isEmpty()) {
            println("No workgroups found")
            return
        }

        println(String.format("%-6s  %-30s  %-10s  %s", "ID", "Name", "Assets", "Description"))
        println("-".repeat(80))

        workgroups.forEach { wg ->
            val id = (wg["id"] as? Number)?.toLong() ?: 0
            val name = (wg["name"]?.toString() ?: "").take(30)
            val assetCount = (wg["assetCount"] as? Number)?.toInt() ?: 0
            val desc = ((wg["description"]?.toString() ?: "-")).take(30) +
                if ((wg["description"]?.toString()?.length ?: 0) > 30) "..." else ""
            println(String.format("%-6d  %-30s  %-10d  %s", id, name, assetCount, desc))
        }

        println()
        println("Total: ${workgroups.size} workgroup(s)")
    }

    @Suppress("DEPRECATION")
    private fun displayWorkgroupsCsv(workgroups: List<Map<String, Any?>>) {
        val stringWriter = StringWriter()
        val csvPrinter = CSVPrinter(
            stringWriter,
            CSVFormat.DEFAULT.builder()
                .setHeader("ID", "Name", "Description", "Criticality", "Asset Count", "Parent ID", "Created At")
                .build()
        )

        workgroups.forEach { wg ->
            csvPrinter.printRecord(
                (wg["id"] as? Number)?.toLong() ?: 0,
                wg["name"]?.toString() ?: "",
                wg["description"]?.toString() ?: "",
                wg["criticality"]?.toString() ?: "",
                (wg["assetCount"] as? Number)?.toInt() ?: 0,
                wg["parentId"]?.toString() ?: "",
                wg["createdAt"]?.toString() ?: ""
            )
        }

        csvPrinter.flush()
        println(stringWriter.toString())
    }

    private fun displayAssets(assets: List<Map<String, Any?>>, title: String) {
        when (format.uppercase()) {
            "TABLE" -> displayAssetsTable(assets, title)
            "JSON" -> {
                val objectMapper = jacksonObjectMapper()
                println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(assets))
            }
            "CSV" -> displayAssetsCsv(assets)
            else -> {
                System.err.println("Invalid format. Use TABLE, JSON, or CSV")
                System.exit(1)
            }
        }
    }

    private fun displayAssetsTable(assets: List<Map<String, Any?>>, title: String) {
        println("=" .repeat(90))
        println(title)
        println("=" .repeat(90))
        println()

        if (assets.isEmpty()) {
            println("No assets found")
            return
        }

        println(String.format("%-6s  %-40s  %-12s  %s", "ID", "Name", "Type", "IP"))
        println("-".repeat(90))

        assets.forEach { asset ->
            println(String.format("%-6d  %-40s  %-12s  %s",
                (asset["id"] as? Number)?.toLong() ?: 0,
                (asset["name"]?.toString() ?: "").take(40),
                (asset["type"]?.toString() ?: "").take(12),
                asset["ip"]?.toString() ?: "-"
            ))
        }

        println()
        println("Total: ${assets.size} asset(s)")
    }

    @Suppress("DEPRECATION")
    private fun displayAssetsCsv(assets: List<Map<String, Any?>>) {
        val stringWriter = StringWriter()
        val csvPrinter = CSVPrinter(
            stringWriter,
            CSVFormat.DEFAULT.builder()
                .setHeader("ID", "Name", "Type", "IP", "Owner")
                .build()
        )

        assets.forEach { asset ->
            csvPrinter.printRecord(
                (asset["id"] as? Number)?.toLong() ?: 0,
                asset["name"]?.toString() ?: "",
                asset["type"]?.toString() ?: "",
                asset["ip"]?.toString() ?: "",
                asset["owner"]?.toString() ?: ""
            )
        }

        csvPrinter.flush()
        println(stringWriter.toString())
    }

    private fun wildcardToRegex(pattern: String): Regex {
        val regexPattern = pattern
            .replace("\\", "\\\\").replace(".", "\\.").replace("+", "\\+")
            .replace("^", "\\^").replace("$", "\\$").replace("{", "\\{")
            .replace("}", "\\}").replace("(", "\\(").replace(")", "\\)")
            .replace("|", "\\|").replace("[", "\\[").replace("]", "\\]")
            .replace("*", ".*").replace("?", ".")
        return Regex("^$regexPattern$")
    }
}
