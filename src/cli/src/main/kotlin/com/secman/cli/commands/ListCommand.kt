package com.secman.cli.commands

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.secman.cli.service.UserMappingCliResponse
import com.secman.cli.service.UserMappingCliService
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import picocli.CommandLine.*
import jakarta.inject.Singleton
import java.io.StringWriter

/**
 * CLI command to list user mappings (Feature 049)
 *
 * Usage:
 *   ./bin/secman manage-user-mappings list
 *   ./bin/secman manage-user-mappings list --email user@example.com
 *   ./bin/secman manage-user-mappings list --status PENDING
 *   ./bin/secman manage-user-mappings list --format JSON
 *   ./bin/secman manage-user-mappings list --format CSV
 */
@Singleton
@Command(
    name = "list",
    description = ["List existing user mappings"],
    mixinStandardHelpOptions = true
)
class ListCommand(
    private val userMappingCliService: UserMappingCliService
) : Runnable {

    @Option(
        names = ["--email"],
        description = ["Filter by user email address"]
    )
    var email: String? = null

    @Option(
        names = ["--status"],
        description = ["Filter by status (ACTIVE, PENDING, ALL)"]
    )
    var statusFilter: String? = null

    @Option(
        names = ["--format"],
        description = ["Output format (TABLE, JSON, CSV)"],
        defaultValue = "TABLE"
    )
    var format: String = "TABLE"

    @ParentCommand
    lateinit var parent: ManageUserMappingsCommand

    override fun run() {
        try {
            // Authenticate with backend
            val backendUrl = parent.getEffectiveBackendUrl()
            val username = parent.getEffectiveUsername()
            val password = parent.getEffectivePassword()
            userMappingCliService.initHttpClient(backendUrl, parent.insecure)
            val token = userMappingCliService.authenticate(username, password, backendUrl)
                ?: throw IllegalArgumentException("Authentication failed - check username/password")

            // Parse status filter
            val status = when (statusFilter?.uppercase()) {
                "ACTIVE" -> "ACTIVE"
                "PENDING" -> "PENDING"
                "ALL", null -> null
                else -> {
                    System.err.println("Error: Invalid status. Use ACTIVE, PENDING, or ALL")
                    System.exit(1)
                    return
                }
            }

            // Fetch mappings via HTTP
            val mappings = userMappingCliService.listMappings(
                email = email,
                status = status,
                backendUrl = backendUrl,
                authToken = token
            )

            // Display based on format
            when (format.uppercase()) {
                "TABLE" -> displayTable(mappings)
                "JSON" -> displayJson(mappings)
                "CSV" -> displayCsv(mappings)
                else -> {
                    System.err.println("Error: Invalid format. Use TABLE, JSON, or CSV")
                    System.exit(1)
                }
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

    private fun displayTable(mappings: List<UserMappingCliResponse>) {
        println("=" .repeat(80))
        println("User Mappings")
        println("=" .repeat(80))
        println()

        if (mappings.isEmpty()) {
            println("No mappings found")
            println()
            return
        }

        // Group by email
        val groupedByEmail = mappings.groupBy { it.email }

        groupedByEmail.forEach { (email, userMappings) ->
            val statusBadge = when {
                userMappings.all { it.status == "ACTIVE" } -> "[active]"
                userMappings.all { it.status == "PENDING" } -> "[pending]"
                else -> "[mixed]"
            }

            println("$statusBadge $email")

            // Group by type (domain vs AWS account)
            val domains = userMappings.filter { it.domain != null }
            val awsAccounts = userMappings.filter { it.awsAccountId != null }

            if (domains.isNotEmpty()) {
                println("  Domains:")
                domains.forEach { mapping ->
                    val status = if (mapping.status == "PENDING") " (pending)" else ""
                    println("    - ${mapping.domain}$status")
                }
            }

            if (awsAccounts.isNotEmpty()) {
                println("  AWS Accounts:")
                awsAccounts.forEach { mapping ->
                    val status = if (mapping.status == "PENDING") " (pending)" else ""
                    println("    - ${mapping.awsAccountId}$status")
                }
            }

            println()
        }

        // Summary statistics
        displaySummary(mappings, groupedByEmail.size)
    }

    private fun displayJson(mappings: List<UserMappingCliResponse>) {
        val objectMapper = jacksonObjectMapper()

        val groupedByEmail = mappings.groupBy { it.email }.map { (email, userMappings) ->
            mapOf(
                "email" to email,
                "domains" to userMappings.filter { it.domain != null }.map {
                    mapOf(
                        "domain" to it.domain,
                        "status" to it.status,
                        "createdAt" to it.createdAt,
                        "appliedAt" to it.appliedAt
                    )
                },
                "awsAccounts" to userMappings.filter { it.awsAccountId != null }.map {
                    mapOf(
                        "awsAccountId" to it.awsAccountId,
                        "status" to it.status,
                        "createdAt" to it.createdAt,
                        "appliedAt" to it.appliedAt
                    )
                }
            )
        }

        val output = mapOf(
            "totalUsers" to groupedByEmail.size,
            "totalMappings" to mappings.size,
            "mappings" to groupedByEmail
        )

        println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output))
    }

    @Suppress("DEPRECATION")
    private fun displayCsv(mappings: List<UserMappingCliResponse>) {
        val stringWriter = StringWriter()
        val csvPrinter = CSVPrinter(
            stringWriter,
            CSVFormat.DEFAULT.builder()
                .setHeader("Email", "Type", "Value", "Status", "Created At", "Applied At")
                .build()
        )

        mappings.forEach { mapping ->
            when {
                mapping.domain != null -> csvPrinter.printRecord(
                    mapping.email, "DOMAIN", mapping.domain, mapping.status,
                    mapping.createdAt, mapping.appliedAt ?: ""
                )
                mapping.awsAccountId != null -> csvPrinter.printRecord(
                    mapping.email, "AWS_ACCOUNT", mapping.awsAccountId, mapping.status,
                    mapping.createdAt, mapping.appliedAt ?: ""
                )
            }
        }

        csvPrinter.flush()
        println(stringWriter.toString())
    }

    private fun displaySummary(mappings: List<UserMappingCliResponse>, userCount: Int) {
        println("=" .repeat(80))
        println("Summary")
        println("=" .repeat(80))

        val activeCount = mappings.count { it.status == "ACTIVE" }
        val pendingCount = mappings.count { it.status == "PENDING" }
        val domainCount = mappings.count { it.domain != null }
        val awsAccountCount = mappings.count { it.awsAccountId != null }

        println("Total users: $userCount")
        println("Total mappings: ${mappings.size}")
        println("  - Active: $activeCount")
        println("  - Pending: $pendingCount")
        println("  - Domains: $domainCount")
        println("  - AWS Accounts: $awsAccountCount")
        println()
    }
}
