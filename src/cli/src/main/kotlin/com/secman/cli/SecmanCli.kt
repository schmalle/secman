package com.secman.cli

import com.secman.cli.commands.ConfigCommand
import com.secman.cli.commands.MonitorCommand
import com.secman.cli.commands.QueryCommand
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
            args[0] == "query" -> {
                val queryCommand = QueryCommand()
                // Parse remaining args into properties
                for (i in 1 until args.size) {
                    when {
                        args[i] == "--hostname" && i + 1 < args.size -> queryCommand.hostname = args[i + 1]
                        args[i] == "--severity" && i + 1 < args.size -> queryCommand.severity = args[i + 1]
                        args[i] == "--product" && i + 1 < args.size -> queryCommand.product = args[i + 1]
                        args[i] == "--limit" && i + 1 < args.size -> queryCommand.limit = args[i + 1].toIntOrNull() ?: 100
                        args[i] == "--format" && i + 1 < args.size -> queryCommand.format = args[i + 1]
                        args[i] == "--output" && i + 1 < args.size -> queryCommand.outputFile = args[i + 1]
                        args[i] == "--verbose" -> queryCommand.verbose = true
                    }
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
              query      Query CrowdStrike vulnerabilities
              monitor    Continuously monitor for HIGH/CRITICAL vulnerabilities
              config     Configure CrowdStrike API credentials
              help       Show this help message

            Query Options:
              --hostname <hostname>    Hostname to query vulnerabilities for (required)
              --severity <level>       Filter by severity level
              --product <name>         Filter by product name
              --limit <num>            Maximum results to return (default: 100)
              --format <json|csv>      Output format (default: json)
              --output <file>          Output file path
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
              secman config --client-id <id> --client-secret <secret>
              secman query --hostname EC2AMAZ-6167U5R --severity critical --format csv --output vulns.csv
              secman query --hostname server01 --limit 50 --format json --output results.json
              secman monitor --interval 10 --hostnames server01,server02,server03
              secman monitor --dry-run --verbose
              secman config --show

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
