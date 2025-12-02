package com.secman.cli

import com.secman.cli.commands.ConfigCommand
import com.secman.cli.commands.ManageUserMappingsCommand
import com.secman.cli.commands.ManageWorkgroupsCommand
import com.secman.cli.commands.MonitorCommand
import com.secman.cli.commands.QueryCommand
import com.secman.cli.commands.SendNotificationsCommand
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
 * For now, this is a simple CLI router for testing the CLI module structure.
 *
 * Related to: Feature 023-create-in-the, 026-crowdstrike-polling-monitor
 * Task: T052
 */
class SecmanCli {
    private val log = LoggerFactory.getLogger(SecmanCli::class.java)

    fun execute(args: Array<String>): Int {
        return when {
            args.isEmpty() || args[0] == "help" || args[0] == "--help" || args[0] == "-h" -> showHelp()
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
                        args[i] == "--device-type" && i + 1 < args.size -> {
                            serversCommand.deviceType = args[i + 1]
                            i++
                        }
                        args[i] == "--severity" && i + 1 < args.size -> {
                            serversCommand.severity = args[i + 1]
                            i++
                        }
                        args[i] == "--min-days-open" && i + 1 < args.size -> {
                            serversCommand.minDaysOpen = args[i + 1].toIntOrNull() ?: 30
                            i++
                        }
                        args[i] == "--limit" && i + 1 < args.size -> {
                            serversCommand.limit = args[i + 1].toIntOrNull() ?: 800
                            i++
                        }
                        args[i] == "--backend-url" && i + 1 < args.size -> {
                            serversCommand.backendUrl = args[i + 1]
                            i++
                        }
                        args[i] == "--username" && i + 1 < args.size -> {
                            serversCommand.username = args[i + 1]
                            i++
                        }
                        args[i] == "--password" && i + 1 < args.size -> {
                            serversCommand.password = args[i + 1]
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
                        args[i] == "--backend-url" && i + 1 < args.size -> {
                            queryCommand.backendUrl = args[i + 1]
                            i++
                        }
                        args[i] == "--username" && i + 1 < args.size -> {
                            queryCommand.username = args[i + 1]
                            i++
                        }
                        args[i] == "--password" && i + 1 < args.size -> {
                            queryCommand.password = args[i + 1]
                            i++
                        }
                        args[i] == "--save" -> queryCommand.save = true
                        args[i] == "--verbose" -> queryCommand.verbose = true
                    }
                    i++
                }
                queryCommand.execute()
            }
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
                ApplicationContext.run().use { ctx ->
                    PicocliRunner.run(ManageUserMappingsCommand::class.java, ctx, *args)
                }
                0
            }
            args[0] == "send-notifications" -> {
                // Use Picocli with Micronaut DI for notification command
                ApplicationContext.run().use { ctx ->
                    PicocliRunner.run(SendNotificationsCommand::class.java, ctx, *args)
                }
                0
            }
            args[0] == "manage-workgroups" -> {
                // Use Picocli with Micronaut DI for workgroup commands
                // Drop the first argument (command name) before passing to PicocliRunner
                val subArgs = args.drop(1).toTypedArray()
                ApplicationContext.run().use { ctx ->
                    PicocliRunner.run(ManageWorkgroupsCommand::class.java, ctx, *subArgs)
                }
                0
            }
            else -> {
                System.err.println("Unknown command: ${args[0]}")
                showHelp()
                1
            }
        }
    }

    private fun showHelp(): Int {
        println("""
            secman - CrowdStrike Vulnerability Management CLI

            Usage: secman [command] [options]

            Commands:
              query servers          Query and import server vulnerabilities (Feature 032)
              query                  Query CrowdStrike vulnerabilities
              monitor                Continuously monitor for HIGH/CRITICAL vulnerabilities
              config                 Configure CrowdStrike API credentials
              send-notifications     Send email notifications for outdated assets (Feature 035)
              manage-user-mappings   Manage user mappings for domains and AWS accounts (Feature 049)
              manage-workgroups      Manage workgroup asset assignments (list, assign, remove)
              help                   Show this help message

            Query Servers Options (Feature 032):
              --hostnames <list>       Comma-separated list of hostnames (optional, default: all servers)
              --device-type <type>     Device type filter (default: SERVER)
              --severity <levels>      Severity filter (default: HIGH,CRITICAL)
              --min-days-open <num>    Minimum days open filter (default: 30)
              --limit <num>            Page size for pagination (default: 800)
              --backend-url <url>      Backend API URL (default: http://localhost:8080)
              --save                   Save to database (requires --username and --password)
              --username <user>        Backend username for authentication (required with --save)
              --password <pass>        Backend password for authentication (required with --save)
              --dry-run                Query but don't import to backend
              --verbose                Enable verbose logging

            Query Options:
              --hostname <hostname>    Hostname to query vulnerabilities for (required)
              --severity <levels>      Filter by severity (comma-separated: CRITICAL,HIGH,MEDIUM,LOW)
              --product <name>         Filter by product name
              --limit <num>            Maximum results to return (default: 100)
              --format <json|csv>      Output format (default: json)
              --output <file>          Output file path
              --save                   Save asset and vulnerabilities to database
              --backend-url <url>      Backend API URL (default: http://localhost:8080)
              --username <user>        Backend username for authentication (required with --save)
              --password <pass>        Backend password for authentication (required with --save)
              --verbose                Enable verbose logging

            Monitor Options:
              --interval <minutes>     Polling interval in minutes (default: 5)
              --hostnames <list>       Comma-separated list of hostnames to monitor
              --backend-url <url>      Backend API URL (default: http://localhost:8080)
              --config <path>          Configuration file path
              --dry-run                Query but don't store results
              --no-storage             Disable automatic storage
              --verbose                Enable verbose logging

            Examples:
              # Configure CrowdStrike credentials
              secman config --client-id <id> --client-secret <secret>

              # Query and display vulnerabilities (no save)
              secman query --hostname server01
              secman query --hostname server01 --verbose

              # Query and save to database (with authentication)
              secman query --hostname server01 --save --username admin --password secret
              secman query --hostname server01 --save --username admin --password secret --backend-url http://api.example.com:8080

              # Query with filtering (single severity)
              secman query --hostname server01 --severity CRITICAL

              # Query with multiple severities
              secman query --hostname server01 --severity HIGH,CRITICAL
              secman query --hostname server01 --severity CRITICAL,HIGH,MEDIUM --verbose

              # Query with filtering and save
              secman query --hostname server01 --severity HIGH,CRITICAL --save --username admin --password secret

              # Query and export to file
              secman query --hostname server01 --format csv --output vulns.csv
              secman query --hostname server01 --limit 50 --format json --output results.json

              # Batch import servers (query only, no save)
              secman query servers --hostnames server01,server02 --verbose
              secman query servers --severity CRITICAL --min-days-open 60 --dry-run

              # Query all servers and save to database (with authentication)
              secman query servers --save --username admin --password secret
              secman query servers --save --username admin --password secret --verbose

              # Query specific servers and save to database
              secman query servers --hostnames server01,server02 --save --username admin --password secret

              # Monitor continuously
              secman monitor --interval 10 --hostnames server01,server02,server03
              secman monitor --dry-run --verbose

              # Send email notifications (Feature 035)
              secman send-notifications
              secman send-notifications --dry-run --verbose
              secman send-notifications --outdated-only

              # Manage user mappings (Feature 049)
              secman manage-user-mappings add-domain --emails user@example.com --domains example.com --admin-user admin@company.com
              secman manage-user-mappings add-aws --emails user@example.com --accounts 123456789012 --admin-user admin@company.com
              secman manage-user-mappings --help

              # Manage workgroup assets
              secman manage-workgroups list                                    # List all workgroups
              secman manage-workgroups list --workgroup Production             # List assets in workgroup
              secman manage-workgroups list --search-assets "ip-10-*"          # Search assets by pattern
              secman manage-workgroups assign-assets -w Production -p "ip-10-*" -u admin@company.com  # Assign by pattern
              secman manage-workgroups assign-assets -w Production -p "*prod*" --type SERVER -u admin@company.com
              secman manage-workgroups assign-assets -w Production --ids 1,2,3 -u admin@company.com   # Assign by ID
              secman manage-workgroups remove-assets -w Test -p "*test*" -u admin@company.com        # Remove by pattern
              secman manage-workgroups remove-assets -w Test --all -u admin@company.com              # Remove all
              secman manage-workgroups --help

            For more information, visit: https://github.com/schmalle/secman
        """.trimIndent())
        return 0
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
