package com.secman.cli

import com.secman.cli.commands.AddRequirementCommand
import com.secman.cli.commands.AddVulnerabilityCommand
import com.secman.cli.commands.ConfigCommand
import com.secman.cli.commands.DeduplicateVulnerabilitiesCommand
import com.secman.cli.commands.DeleteAllRequirementsCommand
import com.secman.cli.commands.ExportRequirementsCommand
import com.secman.cli.commands.ManageUserMappingsCommand
import com.secman.cli.commands.ManageWorkgroupsCommand
import com.secman.cli.commands.MonitorCommand
import com.secman.cli.commands.QueryCommand
import com.secman.cli.commands.SendAdminSummaryCommand
import com.secman.cli.commands.SendNotificationsCommand
import com.secman.cli.commands.SendNotificationUsersCommand
import com.secman.cli.commands.ServersCommand
import io.micronaut.configuration.picocli.PicocliRunner
import io.micronaut.context.ApplicationContext
import org.slf4j.LoggerFactory

/**
 * Main entry point for secman CLI application
 *
 * Usage (future with Picocli):
 *   secman query [options]
 *   secman config [options]
 *   secman monitor [options]
 *   secman help
 *
 */
class SecmanCli {
    private val log = LoggerFactory.getLogger(SecmanCli::class.java)

    private fun createCliContext(): ApplicationContext {
        return ApplicationContext.builder().environments("cli").start()
    }

    fun execute(args: Array<String>): Int {
        return when {
            args.isEmpty() || args[0] == "--help" || args[0] == "-h" -> showHelp()
            args[0] == "help" -> {
                when {
                    args.size > 2 && args[1] == "query" && args[2] == "servers" -> showCommandHelp("query-servers")
                    args.size > 2 && args[1] == "manage-user-mappings" && args[2] == "s3" -> showCommandHelp("manage-user-mappings-s3")
                    args.size > 1 -> showCommandHelp(args[1])
                    else -> showHelp()
                }
            }
            args[0] == "query" && args.size > 1 && args[1] == "--help" -> showCommandHelp("query")
            args[0] == "query" && args.size > 1 && args[1] == "servers" && args.any { it == "--help" || it == "-h" } -> showCommandHelp("query-servers")
            args[0] == "query" && args.size > 1 && args[1] == "servers" -> {
                val serversCommand = ServersCommand()
                // Parse remaining args into properties
                var i = 2
                while (i < args.size) {
                    when {
                        args[i] == "--hostnames" && i + 1 < args.size -> {
                            serversCommand.hostnames = args[i + 1].split(",").map { it.trim() }
                            i++
                        }
                        args[i] == "--device-type" -> {
                            // Check if next arg exists and is not another flag
                            if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
                                serversCommand.deviceType = args[i + 1]
                                i++
                            } else {
                                // No value provided, default to SERVER
                                serversCommand.deviceType = "SERVER"
                                System.out.println("Info: No device type specified, defaulting to SERVER")
                            }
                        }
                        args[i] == "--severity" && i + 1 < args.size -> {
                            serversCommand.severity = args[i + 1]
                            i++
                        }
                        args[i] == "--min-days-open" && i + 1 < args.size -> {
                            serversCommand.minDaysOpen = args[i + 1].toIntOrNull() ?: 30
                            i++
                        }
                        args[i] == "--last-seen-days" && i + 1 < args.size -> {
                            serversCommand.lastSeenDays = args[i + 1].toIntOrNull() ?: 0
                            i++
                        }
                        args[i] == "--limit" && i + 1 < args.size -> {
                            serversCommand.limit = args[i + 1].toIntOrNull() ?: 800
                            i++
                        }
                        args[i] == "--client-id" && i + 1 < args.size -> {
                            serversCommand.clientId = args[i + 1]
                            i++
                        }
                        args[i] == "--client-secret" && i + 1 < args.size -> {
                            serversCommand.clientSecret = args[i + 1]
                            i++
                        }
                        args[i] == "--overdue-threshold" && i + 1 < args.size -> {
                            serversCommand.overdueThreshold = args[i + 1].toIntOrNull() ?: 30
                            i++
                        }
                        args[i] == "--backend-url" && i + 1 < args.size -> {
                            serversCommand.backendUrl = args[i + 1]
                            i++
                        }
                        args[i] == "--save" -> serversCommand.save = true
                        args[i] == "--dry-run" -> serversCommand.dryRun = true
                        args[i] == "--verbose" -> serversCommand.verbose = true
                    }
                    i++
                }
                serversCommand.execute()
            }
            args[0] == "query" && args.any { it == "--help" || it == "-h" } -> showCommandHelp("query")
            args[0] == "query" -> {
                val queryCommand = QueryCommand()
                // Parse remaining args into properties
                var i = 1
                while (i < args.size) {
                    when {
                        args[i] == "--hostname" && i + 1 < args.size -> {
                            queryCommand.hostname = args[i + 1]
                            i++
                        }
                        args[i] == "--severity" && i + 1 < args.size -> {
                            queryCommand.severity = args[i + 1]
                            i++
                        }
                        args[i] == "--product" && i + 1 < args.size -> {
                            queryCommand.product = args[i + 1]
                            i++
                        }
                        args[i] == "--limit" && i + 1 < args.size -> {
                            queryCommand.limit = args[i + 1].toIntOrNull() ?: 100
                            i++
                        }
                        args[i] == "--format" && i + 1 < args.size -> {
                            queryCommand.format = args[i + 1]
                            i++
                        }
                        args[i] == "--output" && i + 1 < args.size -> {
                            queryCommand.outputFile = args[i + 1]
                            i++
                        }
                        args[i] == "--client-id" && i + 1 < args.size -> {
                            queryCommand.clientId = args[i + 1]
                            i++
                        }
                        args[i] == "--client-secret" && i + 1 < args.size -> {
                            queryCommand.clientSecret = args[i + 1]
                            i++
                        }
                        args[i] == "--save" -> queryCommand.save = true
                        args[i] == "--verbose" -> queryCommand.verbose = true
                    }
                    i++
                }
                queryCommand.execute()
            }
            args[0] == "config" && args.any { it == "--help" || it == "-h" } -> showCommandHelp("config")
            args[0] == "config" -> {
                val configCommand = ConfigCommand()
                // Parse remaining args into properties
                for (i in 1 until args.size) {
                    when {
                        args[i] == "--client-id" && i + 1 < args.size -> configCommand.clientId = args[i + 1]
                        args[i] == "--client-secret" && i + 1 < args.size -> configCommand.clientSecret = args[i + 1]
                        args[i] == "--base-url" && i + 1 < args.size -> configCommand.baseUrl = args[i + 1]
                        args[i] == "--show" -> configCommand.show = true
                        args[i] == "--format" && i + 1 < args.size -> configCommand.format = args[i + 1]
                    }
                }
                configCommand.execute()
            }
            args[0] == "monitor" && args.any { it == "--help" || it == "-h" } -> showCommandHelp("monitor")
            args[0] == "monitor" -> {
                val monitorCommand = MonitorCommand()
                // Parse remaining args into properties
                var i = 1
                while (i < args.size) {
                    when {
                        args[i] == "--interval" && i + 1 < args.size -> {
                            monitorCommand.intervalMinutes = args[i + 1].toIntOrNull() ?: 5
                            i++
                        }
                        args[i] == "--hostnames" && i + 1 < args.size -> {
                            monitorCommand.hostnames = args[i + 1].split(",").map { it.trim() }
                            i++
                        }
                        args[i] == "--backend-url" && i + 1 < args.size -> {
                            monitorCommand.backendUrl = args[i + 1]
                            i++
                        }
                        args[i] == "--config" && i + 1 < args.size -> {
                            monitorCommand.configPath = args[i + 1]
                            i++
                        }
                        args[i] == "--dry-run" -> monitorCommand.dryRun = true
                        args[i] == "--verbose" -> monitorCommand.verbose = true
                        args[i] == "--no-storage" -> monitorCommand.noStorage = true
                    }
                    i++
                }
                monitorCommand.execute()
            }
            args[0] == "manage-user-mappings" -> {
                // Use Picocli with Micronaut DI for user mapping commands
                // Drop the first argument (command name) before passing to PicocliRunner
                val subArgs = args.drop(1).toTypedArray()
                createCliContext().use { ctx ->
                    PicocliRunner.run(ManageUserMappingsCommand::class.java, ctx, *subArgs)
                }
                0
            }
            args[0] == "send-notifications" -> {
                // Use Picocli with Micronaut DI for notification command
                // Drop the first argument (command name) before passing to PicocliRunner
                val subArgs = args.drop(1).toTypedArray()
                createCliContext().use { ctx ->
                    PicocliRunner.run(SendNotificationsCommand::class.java, ctx, *subArgs)
                }
                0
            }
            args[0] == "manage-workgroups" -> {
                // Use Picocli with Micronaut DI for workgroup commands
                // Drop the first argument (command name) before passing to PicocliRunner
                val subArgs = args.drop(1).toTypedArray()
                createCliContext().use { ctx ->
                    PicocliRunner.run(ManageWorkgroupsCommand::class.java, ctx, *subArgs)
                }
                0
            }
            args[0] == "add-vulnerability" -> {
                // Use Picocli with Micronaut DI for add-vulnerability command
                // Feature: 052-cli-add-vulnerability
                val subArgs = args.drop(1).toTypedArray()
                createCliContext().use { ctx ->
                    PicocliRunner.run(AddVulnerabilityCommand::class.java, ctx, *subArgs)
                }
                0
            }
            args[0] == "export-requirements" -> {
                // Use Picocli with Micronaut DI for export-requirements command
                // Feature: 057-cli-mcp-requirements
                val subArgs = args.drop(1).toTypedArray()
                createCliContext().use { ctx ->
                    PicocliRunner.run(ExportRequirementsCommand::class.java, ctx, *subArgs)
                }
                0
            }
            args[0] == "add-requirement" -> {
                // Use Picocli with Micronaut DI for add-requirement command
                // Feature: 057-cli-mcp-requirements
                val subArgs = args.drop(1).toTypedArray()
                createCliContext().use { ctx ->
                    PicocliRunner.run(AddRequirementCommand::class.java, ctx, *subArgs)
                }
                0
            }
            args[0] == "delete-all-requirements" -> {
                // Use Picocli with Micronaut DI for delete-all-requirements command
                // Feature: 057-cli-mcp-requirements
                val subArgs = args.drop(1).toTypedArray()
                createCliContext().use { ctx ->
                    PicocliRunner.run(DeleteAllRequirementsCommand::class.java, ctx, *subArgs)
                }
                0
            }
            args[0] == "send-admin-summary" -> {
                // Use Picocli with Micronaut DI for send-admin-summary command
                // Feature: 070-admin-summary-email
                val subArgs = args.drop(1).toTypedArray()
                createCliContext().use { ctx ->
                    PicocliRunner.run(SendAdminSummaryCommand::class.java, ctx, *subArgs)
                }
                0
            }
            args[0] == "send-notification-users" -> {
                // Use Picocli with Micronaut DI for send-notification-users command
                val subArgs = args.drop(1).toTypedArray()
                createCliContext().use { ctx ->
                    PicocliRunner.run(SendNotificationUsersCommand::class.java, ctx, *subArgs)
                }
                0
            }
            args[0] == "deduplicate-vulnerabilities" -> {
                // Remove duplicate vulnerability records from the database
                val subArgs = args.drop(1).toTypedArray()
                createCliContext().use { ctx ->
                    PicocliRunner.run(DeduplicateVulnerabilitiesCommand::class.java, ctx, *subArgs)
                }
                0
            }
            else -> {
                System.err.println("ERROR: Unknown command: '${args[0]}'")

                // Suggest parent command for known subcommands used at the wrong level
                val subcommandHints = mapOf(
                    "list-bucket" to "manage-user-mappings",
                    "import-s3" to "manage-user-mappings",
                    "add-domain" to "manage-user-mappings",
                    "add-aws" to "manage-user-mappings",
                    "assign-assets" to "manage-workgroups",
                    "remove-assets" to "manage-workgroups",
                    "servers" to "query"
                )
                val parentCommand = subcommandHints[args[0]]
                if (parentCommand != null) {
                    System.err.println()
                    System.err.println("Did you mean: secman $parentCommand ${args.joinToString(" ")}")
                    System.err.println()
                }

                showHelp()
                1
            }
        }
    }

    private fun showHelp(): Int {
        println("""
            secman - Security Requirement and Risk Assessment Management CLI

            Usage: secman <command> [options]
                   secman help <command>       Show detailed help for a command

            Commands:
              CrowdStrike:
                query                  Query CrowdStrike vulnerabilities for a single host
                query servers          Batch query and import server vulnerabilities
                monitor                Continuously monitor for HIGH/CRITICAL vulnerabilities
                config                 Configure CrowdStrike API credentials

              Notifications:
                send-notifications     Send email notifications for outdated assets
                send-admin-summary     Send system statistics summary email to ADMIN users
                send-notification-users  Send vulnerability notifications to users by AWS account

              User & Access Management:
                manage-user-mappings   Manage user mappings for domains and AWS accounts
                manage-workgroups      Manage workgroup asset assignments (list, assign, remove)

              Vulnerabilities:
                add-vulnerability      Add or update a vulnerability for an asset
                deduplicate-vulnerabilities  Remove duplicate vulnerability records (ADMIN)

              Requirements:
                export-requirements    Export all requirements to Excel or Word
                add-requirement        Add a new security requirement
                delete-all-requirements  Delete ALL requirements (ADMIN)

              General:
                help                   Show this help message
                help <command>         Show detailed help for a specific command

            Run 'secman help <command>' for detailed options and examples.
            For more information, visit: https://github.com/schmalle/secman
        """.trimIndent())
        return 0
    }

    private fun showCommandHelp(command: String): Int {
        // Resolve aliases for common variations
        val resolvedCommand = commandAliases[command] ?: command
        val helpText = commandHelpTexts[resolvedCommand]
        if (helpText == null) {
            System.err.println("ERROR: Unknown command: '$command'")
            System.err.println()
            System.err.println("Available commands:")
            commandHelpTexts.keys.sorted().forEach { cmd ->
                System.err.println("  $cmd")
            }
            System.err.println()
            System.err.println("Run 'secman help' for an overview.")
            return 1
        }
        println(helpText)
        return 0
    }

    companion object {
        private val commandAliases = mapOf(
            "servers" to "query-servers",
            "notifications" to "send-notifications",
            "admin-summary" to "send-admin-summary",
            "user-mappings" to "manage-user-mappings",
            "workgroups" to "manage-workgroups",
            "env" to "environment",
            "vars" to "environment",
            "s3" to "manage-user-mappings-s3",
        )

        private val commandHelpTexts = mapOf(
            "query" to """
                secman query - Query CrowdStrike vulnerabilities for a single host

                Usage: secman query [options]

                Options:
                  --hostname <hostname>    Hostname to query vulnerabilities for (required)
                  --severity <levels>      Filter by severity (comma-separated: CRITICAL,HIGH,MEDIUM,LOW)
                  --product <name>         Filter by product name
                  --limit <num>            Maximum results to return (default: 100)
                  --format <json|csv>      Output format (default: json)
                  --output <file>          Output file path
                  --client-id <id>         CrowdStrike API client ID (overrides config file)
                  --client-secret <secret> CrowdStrike API client secret (overrides config file)
                  --save                   Save asset and vulnerabilities to database (direct access)
                  --verbose                Enable verbose logging

                Examples:
                  secman query --hostname server01
                  secman query --hostname server01 --severity CRITICAL --verbose
                  secman query --hostname server01 --severity HIGH,CRITICAL --save
                  secman query --hostname server01 --format csv --output vulns.csv
                  secman query --hostname server01 --limit 50 --format json --output results.json

                See also: secman help query-servers
            """.trimIndent(),

            "query-servers" to """
                secman query servers - Batch query and import server vulnerabilities

                Usage: secman query servers [options]

                Options:
                  --hostnames <list>       Comma-separated list of hostnames (default: all devices)
                  --device-type <type>     Device type: SERVER, WORKSTATION, or ALL (default: SERVER)
                  --severity <levels>      Severity filter (default: HIGH,CRITICAL)
                  --min-days-open <num>    Minimum days open filter (default: 30)
                  --last-seen-days <num>   Only include devices seen within N days (default: 0 = all)
                  --limit <num>            Page size for pagination (default: 800)
                  --client-id <id>         CrowdStrike API client ID (overrides config file)
                  --client-secret <secret> CrowdStrike API client secret (overrides config file)
                  --overdue-threshold <num> Days threshold for overdue vulnerability report (default: 30)
                  --backend-url <url>      Backend API URL (default: SECMAN_BACKEND_URL env var, or http://localhost:8080)
                  --save                   Save to database via backend API
                  --dry-run                Query but don't import
                  --verbose                Enable verbose logging

                Examples:
                  secman query servers --save
                  secman query servers --backend-url https://secman.example.com --save
                  secman query servers --hostnames server01,server02 --save --verbose
                  secman query servers --severity CRITICAL --min-days-open 60 --dry-run
                  secman query servers --device-type WORKSTATION --severity CRITICAL,HIGH --save
                  secman query servers --device-type ALL --dry-run --verbose
                  secman query servers --save --overdue-threshold 60

                See also: secman help query
            """.trimIndent(),

            "monitor" to """
                secman monitor - Continuously monitor for HIGH/CRITICAL vulnerabilities

                Usage: secman monitor [options]

                Options:
                  --interval <minutes>     Polling interval in minutes (default: 5)
                  --hostnames <list>       Comma-separated list of hostnames to monitor
                  --backend-url <url>      Backend API URL (default: http://localhost:8080)
                  --config <path>          Configuration file path
                  --dry-run                Query but don't store results
                  --no-storage             Disable automatic storage
                  --verbose                Enable verbose logging

                Examples:
                  secman monitor --interval 10 --hostnames server01,server02,server03
                  secman monitor --dry-run --verbose
            """.trimIndent(),

            "config" to """
                secman config - Configure CrowdStrike API credentials

                Usage: secman config [options]

                Options:
                  --client-id <id>         CrowdStrike API client ID (required for save)
                  --client-secret <secret> CrowdStrike API client secret (required for save)
                  --base-url <url>         CrowdStrike API base URL (default: https://api.crowdstrike.com)
                  --show                   Show current CrowdStrike configuration
                  --format <yaml|conf>     Configuration file format (default: yaml)

                Examples:
                  secman config --client-id <id> --client-secret <secret>
                  secman config --show
            """.trimIndent(),

            "send-notifications" to """
                secman send-notifications - Send email notifications for outdated assets

                Usage: secman send-notifications [options]

                Options:
                  --dry-run                Report planned notifications without sending emails
                  --verbose, -v            Detailed logging (show per-asset processing)
                  --outdated-only          Process only outdated asset reminders (skip new vulnerability notifications)

                Examples:
                  secman send-notifications
                  secman send-notifications --dry-run --verbose
                  secman send-notifications --outdated-only
            """.trimIndent(),

            "send-admin-summary" to """
                secman send-admin-summary - Send system statistics summary email to ADMIN users

                Usage: secman send-admin-summary [options]

                Options:
                  --dry-run                Preview planned recipients without sending emails
                  --verbose, -v            Detailed logging (show per-recipient status)

                Examples:
                  secman send-admin-summary
                  secman send-admin-summary --dry-run
                  secman send-admin-summary --verbose
                  secman send-admin-summary --dry-run --verbose
            """.trimIndent(),

            "send-notification-users" to """
                secman send-notification-users - Send vulnerability notification emails to users with affected AWS accounts

                Usage: secman send-notification-users [options]

                Options:
                  --dry-run                Preview planned notifications without sending emails
                  --verbose, -v            Detailed logging (show per-recipient status)
                  --days <number>          Vulnerability age threshold in days (default: 30)

                Description:
                  Identifies AWS accounts with systems having vulnerabilities open longer than
                  the specified threshold. Maps accounts to users via UserMapping and sends each
                  user one consolidated email listing all their affected AWS accounts.

                Examples:
                  secman send-notification-users
                  secman send-notification-users --dry-run
                  secman send-notification-users --days 60 --verbose
                  secman send-notification-users --dry-run --days 14
            """.trimIndent(),

            "manage-user-mappings" to """
                secman manage-user-mappings - Manage user mappings for domains and AWS accounts

                Usage: secman manage-user-mappings <subcommand> [options]

                Subcommands:
                  add-domain     Add domain-to-user mappings
                  add-aws        Add AWS account-to-user mappings
                  list           List existing user mappings
                  remove         Remove user mappings
                  import         Batch import from CSV/JSON file
                  import-s3      Import user mappings from AWS S3
                  list-bucket    List S3 bucket contents

                Common Options:
                  --admin-user, -u <email>  Admin email (or set SECMAN_ADMIN_EMAIL env var)

                Examples:
                  secman manage-user-mappings add-domain --emails user@example.com --domains example.com -u admin@company.com
                  secman manage-user-mappings add-aws --emails user@example.com --accounts 123456789012 -u admin@company.com
                  secman manage-user-mappings list --format TABLE
                  secman manage-user-mappings remove --email user@example.com --all
                  secman manage-user-mappings import --file mappings.csv --dry-run
                  secman manage-user-mappings import-s3 --bucket my-bucket --key mappings.csv -u admin@company.com
                  secman manage-user-mappings list-bucket --bucket my-bucket --prefix user-mappings/

                Run 'secman manage-user-mappings <subcommand> --help' for subcommand-specific options.

                See also: secman help manage-user-mappings-s3
            """.trimIndent(),

            "manage-user-mappings-s3" to """
                secman manage-user-mappings import-s3 / list-bucket - S3 import operations

                Usage:
                  secman manage-user-mappings import-s3 [options]
                  secman manage-user-mappings list-bucket [options]

                import-s3 Options:
                  --bucket, -b <name>        S3 bucket name (required)
                  --key, -k <path>           S3 object key / path to file (required)
                  --aws-region <region>      AWS region (default: SDK auto-resolution)
                  --aws-profile <name>       AWS credential profile name
                  --aws-access-key-id        AWS access key ID (or AWS_ACCESS_KEY_ID env var)
                  --aws-secret-access-key    AWS secret access key (or AWS_SECRET_ACCESS_KEY env var)
                  --aws-session-token        AWS session token for temporary credentials
                  --format <type>            File format: CSV, JSON, or AUTO (default: AUTO)
                  --dry-run                  Validate file without creating mappings
                  --admin-user, -u           Admin email (or SECMAN_ADMIN_EMAIL env var)

                list-bucket Options:
                  --bucket, -b <name>        S3 bucket name (required)
                  --prefix, -p <prefix>      Filter objects by key prefix
                  --aws-region <region>      AWS region (default: SDK auto-resolution)
                  --aws-profile <name>       AWS credential profile name
                  --aws-access-key-id        AWS access key ID (or AWS_ACCESS_KEY_ID env var)
                  --aws-secret-access-key    AWS secret access key (or AWS_SECRET_ACCESS_KEY env var)
                  --aws-session-token        AWS session token for temporary credentials
                  --admin-user, -u           Admin email (or SECMAN_ADMIN_EMAIL env var)

                AWS Credential Resolution Priority (highest to lowest):
                  1. Explicit CLI flags (--aws-access-key-id + --aws-secret-access-key)
                  2. Environment variables (AWS_ACCESS_KEY_ID + AWS_SECRET_ACCESS_KEY)
                  3. Named profile (--aws-profile, reads from ~/.aws/credentials)
                  4. Default credential chain (IAM role, SSO, etc.)

                Exit Codes (import-s3):
                  0  All mappings imported successfully
                  1  Partial import (some mappings skipped)
                  2+ Fatal error (S3 access, parsing, or authentication failure)

                Examples:
                  export AWS_ACCESS_KEY_ID=AKIA...
                  export AWS_SECRET_ACCESS_KEY=...
                  secman manage-user-mappings import-s3 --bucket my-bucket --key mappings.csv -u admin@company.com
                  secman manage-user-mappings import-s3 --bucket my-bucket --key mappings.csv --dry-run -u admin@company.com
                  secman manage-user-mappings import-s3 --bucket my-bucket --key data/users.json --aws-profile prod -u admin@company.com
                  secman manage-user-mappings list-bucket --bucket my-bucket -u admin@company.com
                  secman manage-user-mappings list-bucket --bucket my-bucket --prefix user-mappings/ -u admin@company.com
            """.trimIndent(),

            "manage-workgroups" to """
                secman manage-workgroups - Manage workgroup asset assignments

                Usage: secman manage-workgroups <subcommand> [options]

                Subcommands:
                  list             List workgroups and their assets
                  assign-assets    Assign assets to a workgroup
                  remove-assets    Remove assets from a workgroup

                Common Options:
                  --admin-user, -u <email>  Admin email (or set SECMAN_ADMIN_EMAIL env var)

                Examples:
                  secman manage-workgroups list
                  secman manage-workgroups list --workgroup Production
                  secman manage-workgroups list --search-assets "ip-10-*"
                  secman manage-workgroups assign-assets -w Production -p "ip-10-*" -u admin@company.com
                  secman manage-workgroups assign-assets -w Production -p "*prod*" --type SERVER -u admin@company.com
                  secman manage-workgroups assign-assets -w Production --ids 1,2,3 -u admin@company.com
                  secman manage-workgroups remove-assets -w Test -p "*test*" -u admin@company.com
                  secman manage-workgroups remove-assets -w Test --all -u admin@company.com

                Run 'secman manage-workgroups <subcommand> --help' for subcommand-specific options.
            """.trimIndent(),

            "add-vulnerability" to """
                secman add-vulnerability - Add or update a vulnerability for an asset

                Usage: secman add-vulnerability [options]

                Options:
                  --hostname <hostname>    Target asset hostname (required)
                  --cve <cve-id>           CVE identifier or custom vulnerability ID (required)
                  --criticality <level>    Severity: CRITICAL, HIGH, MEDIUM, or LOW (required)
                  --days-open <num>        Days the vulnerability has been open (default: 0)
                  --verbose                Enable verbose output

                If the asset does not exist, it will be created automatically.

                Examples:
                  secman add-vulnerability --hostname webserver01 --cve CVE-2024-1234 --criticality HIGH
                  secman add-vulnerability --hostname webserver01 --cve CVE-2024-1234 --criticality HIGH --days-open 30
                  secman add-vulnerability --hostname newserver99 --cve CVE-2023-5678 --criticality CRITICAL --verbose
            """.trimIndent(),

            "deduplicate-vulnerabilities" to """
                secman deduplicate-vulnerabilities - Remove duplicate vulnerability records

                Usage: secman deduplicate-vulnerabilities [options]

                Requires: ADMIN role

                Options:
                  --backend-url <url>      Backend API URL (default: http://localhost:8080)
                  --username <user>        Backend username with ADMIN role (or SECMAN_ADMIN_NAME env var)
                  --password <pass>        Backend password (or SECMAN_ADMIN_PASS env var)
                  --verbose, -v            Show per-asset deduplication details

                Examples:
                  secman deduplicate-vulnerabilities --username admin --password secret
                  secman deduplicate-vulnerabilities --verbose
                  secman deduplicate-vulnerabilities --backend-url http://prod:8080
            """.trimIndent(),

            "export-requirements" to """
                secman export-requirements - Export all requirements to Excel or Word

                Usage: secman export-requirements [options]

                Options:
                  --format <xlsx|docx>     Export format: xlsx (Excel) or docx (Word) (required)
                  --output <path>          Output file path (default: requirements_export_YYYYMMDD.{format})
                  --backend-url <url>      Backend API URL (default: http://localhost:8080)
                  --username <user>        Backend username (or SECMAN_ADMIN_NAME env var)
                  --password <pass>        Backend password (or SECMAN_ADMIN_PASS env var)
                  --verbose                Enable verbose output

                Examples:
                  export SECMAN_ADMIN_NAME=admin
                  export SECMAN_ADMIN_PASS=secret
                  secman export-requirements --format xlsx
                  secman export-requirements --format docx --output security_requirements.docx
                  secman export-requirements --format xlsx --output /path/to/export.xlsx --verbose
                  secman export-requirements --format xlsx --username admin --password secret
            """.trimIndent(),

            "add-requirement" to """
                secman add-requirement - Add a new security requirement

                Usage: secman add-requirement [options]

                Options:
                  --shortreq <text>        Short requirement text (required)
                  --chapter <name>         Chapter/category for grouping
                  --details <text>         Detailed description
                  --motivation <text>      Why this requirement exists
                  --example <text>         Implementation example
                  --norm <name>            Regulatory norm reference (e.g., ISO 27001)
                  --usecase <name>         Use case description
                  --backend-url <url>      Backend API URL (default: http://localhost:8080)
                  --username <user>        Backend username (or SECMAN_ADMIN_NAME env var)
                  --password <pass>        Backend password (or SECMAN_ADMIN_PASS env var)
                  --verbose                Enable verbose output

                Examples:
                  secman add-requirement --shortreq "All passwords must be at least 12 characters"
                  secman add-requirement --shortreq "MFA required for admin access" --chapter "Authentication"
                  secman add-requirement --shortreq "Encrypt data at rest" --chapter "Data Protection" --norm "GDPR Article 32"
                  secman add-requirement --shortreq "Log all admin actions" --chapter "Audit" --username admin --password secret
            """.trimIndent(),

            "delete-all-requirements" to """
                secman delete-all-requirements - Delete ALL requirements from the system

                Usage: secman delete-all-requirements [options]

                WARNING: This is a destructive operation! Requires ADMIN role.

                Options:
                  --confirm                Required safety flag to confirm deletion (required)
                  --backend-url <url>      Backend API URL (default: http://localhost:8080)
                  --username <user>        Backend username with ADMIN role (or SECMAN_ADMIN_NAME env var)
                  --password <pass>        Backend password (or SECMAN_ADMIN_PASS env var)
                  --verbose                Enable verbose output

                Examples:
                  secman delete-all-requirements --confirm
                  secman delete-all-requirements --confirm --verbose
                  secman delete-all-requirements --confirm --backend-url http://test-server:8080
            """.trimIndent(),

            "environment" to """
                secman environment variables reference

                Backend Authentication:
                  SECMAN_ADMIN_NAME          Backend username for authentication
                  SECMAN_ADMIN_PASS          Backend password (recommended over --password flag)

                Admin Operations:
                  SECMAN_ADMIN_EMAIL       Admin email for user mapping and S3 import operations

                AWS S3 Operations:
                  AWS_ACCESS_KEY_ID        AWS access key ID (or use --aws-access-key-id)
                  AWS_SECRET_ACCESS_KEY    AWS secret access key (or use --aws-secret-access-key)
                  AWS_SESSION_TOKEN        AWS session token for temporary credentials
                  AWS_REGION               Default AWS region for S3 operations

                AWS Credential Resolution Priority (highest to lowest):
                  1. Explicit CLI flags (--aws-access-key-id + --aws-secret-access-key)
                  2. Environment variables (AWS_ACCESS_KEY_ID + AWS_SECRET_ACCESS_KEY)
                  3. Named profile (--aws-profile, reads from ~/.aws/credentials)
                  4. Default credential chain (IAM role, SSO, etc.)
            """.trimIndent()
        )
    }
}

/**
 * Main entry point function
 */
fun main(args: Array<String>) {
    val cli = SecmanCli()
    val exitCode = cli.execute(args)
    System.exit(exitCode)
}
