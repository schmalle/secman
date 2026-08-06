package com.secman.cli.commands

import com.secman.cli.config.ConfigLoader
import com.secman.crowdstrike.dto.FalconConfigDto
import org.slf4j.LoggerFactory

/**
 * Config command for CrowdStrike credentials
 *
 * Usage (future with Picocli):
 *   secman config [options]
 *
 * For now, this is a simple class for testing the CLI module structure.
 *
 * Related to: Feature 023-create-in-the
 * Task: T051
 */
class ConfigCommand {
    private val log = LoggerFactory.getLogger(ConfigCommand::class.java)
    private val configLoader = ConfigLoader()

    var clientId: String? = null
    var clientSecret: String? = null
    var baseUrl: String = "https://api.crowdstrike.com"
    var show: Boolean = false
    var format: String = "yaml"

    fun execute(): Int {
        return try {
            when {
                show -> showConfig()
                clientId != null && clientSecret != null -> saveConfig()
                else -> {
                    System.err.println("Error: Either provide --client-id and --client-secret to save, or use --show to view")
                    1
                }
            }
        } catch (e: IllegalArgumentException) {
            System.err.println("Error: ${e.message}")
            1
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            e.printStackTrace()
            1
        }
    }

    private fun showConfig(): Int {
        return try {
            val config = configLoader.loadConfig()
            System.out.println("CrowdStrike Configuration:")
            System.out.println("  Client ID: ${config.clientId}")
            System.out.println("  Client Secret: ${maskSecret(config.clientSecret)}")
            System.out.println("  Base URL: ${config.baseUrl}")
            0
        } catch (e: Exception) {
            System.err.println("Error: No configuration found")
            1
        }
    }

    private fun saveConfig(): Int {
        try {
            val config = FalconConfigDto(
                clientId = clientId!!,
                clientSecret = clientSecret!!,
                baseUrl = baseUrl
            )

            val filename = if (format == "conf") "crowdstrike.conf" else "crowdstrike.yaml"
            configLoader.saveConfig(config, filename)

            System.out.println("Configuration saved successfully")
            return 0
        } catch (e: Exception) {
            System.err.println("Error: Failed to save configuration: ${e.message}")
            return 1
        }
    }

    fun maskSecret(secret: String): String {
        return if (secret.length <= 4) {
            "*".repeat(secret.length)
        } else {
            secret.take(2) + "*".repeat(secret.length - 4) + secret.takeLast(2)
        }
    }
}
