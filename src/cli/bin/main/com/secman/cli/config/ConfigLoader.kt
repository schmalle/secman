package com.secman.cli.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.secman.crowdstrike.dto.FalconConfigDto
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Configuration loader for CrowdStrike CLI
 *
 * Supports loading config from:
 * - YAML files: ~/.secman/crowdstrike.conf, ~/.secman/crowdstrike.yaml
 * - Environment variables: CROWDSTRIKE_CLIENT_ID, CROWDSTRIKE_CLIENT_SECRET
 * - System properties: crowdstrike.clientId, crowdstrike.clientSecret
 *
 * Related to: Feature 023-create-in-the
 * Task: T045
 */
class ConfigLoader {
    private val log = LoggerFactory.getLogger(ConfigLoader::class.java)
    private val yamlMapper = ObjectMapper(YAMLFactory())
    private val jsonMapper = ObjectMapper()

    companion object {
        const val CONFIG_HOME = ".secman"
        const val CONFIG_FILE_YAML = "crowdstrike.yaml"
        const val CONFIG_FILE_CONF = "crowdstrike.conf"
        const val ENV_CLIENT_ID = "CROWDSTRIKE_CLIENT_ID"
        const val ENV_CLIENT_SECRET = "CROWDSTRIKE_CLIENT_SECRET"
        const val ENV_BASE_URL = "CROWDSTRIKE_BASE_URL"
        // Falcon naming (for compatibility with Python helper tool)
        const val ENV_FALCON_CLIENT_ID = "FALCON_CLIENT_ID"
        const val ENV_FALCON_CLIENT_SECRET = "FALCON_CLIENT_SECRET"
        const val ENV_FALCON_CLOUD_REGION = "FALCON_CLOUD_REGION"
        const val SYS_CLIENT_ID = "crowdstrike.clientId"
        const val SYS_CLIENT_SECRET = "crowdstrike.clientSecret"
        const val SYS_BASE_URL = "crowdstrike.baseUrl"
    }

    /**
     * Load configuration with fallback chain
     *
     * Priority order:
     * 1. System properties (highest priority)
     * 2. Environment variables
     * 3. Config files (~/.secman/)
     * 4. Defaults
     *
     * @return FalconConfigDto with loaded credentials
     * @throws IllegalArgumentException if required credentials not found
     */
    fun loadConfig(): FalconConfigDto {
        log.info("Loading CrowdStrike configuration")

        // Try system properties first
        val sysPropConfig = loadFromSystemProperties()
        if (sysPropConfig != null) {
            log.info("Loaded configuration from system properties")
            return sysPropConfig
        }

        // Try environment variables
        val envConfig = loadFromEnvironment()
        if (envConfig != null) {
            log.info("Loaded configuration from environment variables")
            return envConfig
        }

        // Try config files
        val fileConfig = loadFromConfigFiles()
        if (fileConfig != null) {
            log.info("Loaded configuration from config files")
            return fileConfig
        }

        // No config found
        log.error("No CrowdStrike configuration found")
        throw IllegalArgumentException(
            """
            No CrowdStrike configuration found. Please provide one of:
            1. System properties: -Dcrowdstrike.clientId=... -Dcrowdstrike.clientSecret=...
            2. Environment variables: CROWDSTRIKE_CLIENT_ID, CROWDSTRIKE_CLIENT_SECRET (or FALCON_CLIENT_ID, FALCON_CLIENT_SECRET)
            3. Config file: ~/.secman/crowdstrike.yaml or ~/.secman/crowdstrike.conf
            """.trimIndent()
        )
    }

    /**
     * Load configuration from system properties
     */
    private fun loadFromSystemProperties(): FalconConfigDto? {
        val clientId = System.getProperty(SYS_CLIENT_ID)?.takeIf { it.isNotBlank() }
        val clientSecret = System.getProperty(SYS_CLIENT_SECRET)?.takeIf { it.isNotBlank() }
        val baseUrl = System.getProperty(SYS_BASE_URL)?.takeIf { it.isNotBlank() }
            ?: "https://api.crowdstrike.com"

        return if (clientId != null && clientSecret != null) {
            FalconConfigDto(
                clientId = clientId,
                clientSecret = clientSecret,
                baseUrl = baseUrl
            )
        } else {
            null
        }
    }

    /**
     * Load configuration from environment variables
     * Supports both CROWDSTRIKE_* and FALCON_* naming conventions
     */
    private fun loadFromEnvironment(): FalconConfigDto? {
        // Try CROWDSTRIKE_* variables first (preferred naming)
        var clientId = System.getenv(ENV_CLIENT_ID)?.takeIf { it.isNotBlank() }
        var clientSecret = System.getenv(ENV_CLIENT_SECRET)?.takeIf { it.isNotBlank() }
        var baseUrl = System.getenv(ENV_BASE_URL)?.takeIf { it.isNotBlank() }

        // Fallback to FALCON_* variables (for compatibility with Python helper tool)
        if (clientId == null) {
            clientId = System.getenv(ENV_FALCON_CLIENT_ID)?.takeIf { it.isNotBlank() }
        }
        if (clientSecret == null) {
            clientSecret = System.getenv(ENV_FALCON_CLIENT_SECRET)?.takeIf { it.isNotBlank() }
        }

        // Default base URL if not specified
        if (baseUrl == null) {
            baseUrl = "https://api.crowdstrike.com"
        }

        return if (clientId != null && clientSecret != null) {
            log.debug("Loaded credentials from environment")
            FalconConfigDto(
                clientId = clientId,
                clientSecret = clientSecret,
                baseUrl = baseUrl
            )
        } else {
            null
        }
    }

    /**
     * Load configuration from config files
     *
     * Tries:
     * 1. ~/.secman/crowdstrike.yaml
     * 2. ~/.secman/crowdstrike.conf
     */
    private fun loadFromConfigFiles(): FalconConfigDto? {
        val homeDir = File(System.getProperty("user.home"))
        val configDir = File(homeDir, CONFIG_HOME)

        if (!configDir.exists()) {
            log.debug("Config directory not found: {}", configDir.absolutePath)
            return null
        }

        // Try YAML file
        val yamlFile = File(configDir, CONFIG_FILE_YAML)
        if (yamlFile.exists()) {
            try {
                log.debug("Loading config from: {}", yamlFile.absolutePath)
                return yamlMapper.readValue(yamlFile, FalconConfigDto::class.java)
            } catch (e: Exception) {
                log.warn("Failed to load YAML config: {}", e.message)
            }
        }

        // Try CONF file (treated as YAML)
        val confFile = File(configDir, CONFIG_FILE_CONF)
        if (confFile.exists()) {
            try {
                log.debug("Loading config from: {}", confFile.absolutePath)
                return yamlMapper.readValue(confFile, FalconConfigDto::class.java)
            } catch (e: Exception) {
                log.warn("Failed to load CONF config: {}", e.message)
            }
        }

        return null
    }

    /**
     * Save configuration to file
     *
     * @param config Configuration to save
     * @param filename Target filename (crowdstrike.yaml or crowdstrike.conf)
     */
    fun saveConfig(config: FalconConfigDto, filename: String = CONFIG_FILE_YAML) {
        val homeDir = File(System.getProperty("user.home"))
        val configDir = File(homeDir, CONFIG_HOME)

        if (!configDir.exists()) {
            configDir.mkdirs()
            log.info("Created config directory: {}", configDir.absolutePath)
        }

        val configFile = File(configDir, filename)

        try {
            yamlMapper.writeValue(configFile, config)
            configFile.setReadable(false, false)  // Remove read for others
            configFile.setWritable(false, false)  // Remove write for others
            configFile.setReadable(true, true)    // Add read for owner only
            configFile.setWritable(true, true)    // Add write for owner only

            log.info("Saved configuration to: {}", configFile.absolutePath)
        } catch (e: Exception) {
            log.error("Failed to save configuration: {}", e.message, e)
            throw RuntimeException("Failed to save configuration: ${e.message}", e)
        }
    }
}
