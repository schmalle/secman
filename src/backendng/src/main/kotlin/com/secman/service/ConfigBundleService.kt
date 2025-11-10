package com.secman.service

import com.secman.domain.*
import com.secman.dto.*
import com.secman.repository.*
import io.micronaut.security.authentication.Authentication
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDateTime
import java.util.*
import javax.persistence.EntityManager

/**
 * Service for handling configuration bundle export and import operations
 */
@Singleton
open class ConfigBundleService(
    private val userRepository: UserRepository,
    private val workgroupRepository: WorkgroupRepository,
    private val userMappingRepository: UserMappingRepository,
    private val identityProviderRepository: IdentityProviderRepository,
    private val falconConfigRepository: FalconConfigRepository,
    private val mcpApiKeyRepository: McpApiKeyRepository,
    private val entityManager: EntityManager,
    private val passwordEncoder: org.springframework.security.crypto.password.PasswordEncoder,
    private val auditLogService: AuditLogService
) {
    private val logger = LoggerFactory.getLogger(ConfigBundleService::class.java)

    companion object {
        const val BUNDLE_VERSION = "1.0"
        const val CLIENT_ID_MASK = "***MASKED***"
        const val CLIENT_SECRET_MASK = "***MASKED***"
        const val TEMP_PASSWORD_LENGTH = 16
    }

    /**
     * Export all configuration data to a bundle
     */
    @Transactional(readOnly = true)
    open fun exportBundle(authentication: Authentication): ConfigBundleDto {
        logger.info("Starting configuration bundle export for user: ${authentication.name}")

        try {
            // Export all entities
            val users = exportUsers()
            val workgroups = exportWorkgroups()
            val userMappings = exportUserMappings()
            val identityProviders = exportIdentityProviders()
            val falconConfigs = exportFalconConfigs()
            val mcpApiKeys = exportMcpApiKeys()

            val bundle = ConfigBundleDto(
                version = BUNDLE_VERSION,
                exportedAt = Instant.now(),
                exportedBy = authentication.name,
                systemInfo = SystemInfoDto(
                    applicationVersion = System.getProperty("app.version", "unknown"),
                    exportHost = System.getenv("HOSTNAME") ?: "unknown"
                ),
                users = users,
                workgroups = workgroups,
                userMappings = userMappings,
                identityProviders = identityProviders,
                falconConfigs = falconConfigs,
                mcpApiKeys = mcpApiKeys
            )

            // Log the export action
            auditLogService.logAction(
                authentication = authentication,
                action = "EXPORT_CONFIG_BUNDLE",
                entityType = "ConfigBundle",
                details = "Exported ${users.size} users, ${workgroups.size} workgroups, " +
                         "${userMappings.size} mappings, ${identityProviders.size} identity providers, " +
                         "${falconConfigs.size} falcon configs, ${mcpApiKeys.size} MCP keys"
            )

            logger.info("Configuration bundle export completed successfully")
            return bundle

        } catch (e: Exception) {
            logger.error("Error during configuration bundle export", e)
            throw RuntimeException("Failed to export configuration bundle: ${e.message}", e)
        }
    }

    /**
     * Import configuration bundle with validation and conflict resolution
     */
    @Transactional
    open fun importBundle(
        request: ImportBundleRequest,
        authentication: Authentication
    ): ImportBundleResult {
        logger.info("Starting configuration bundle import for user: ${authentication.name}")

        val bundle = request.bundle
        val options = request.options

        // Validate bundle version
        if (bundle.version != BUNDLE_VERSION) {
            logger.warn("Bundle version mismatch. Expected: $BUNDLE_VERSION, Got: ${bundle.version}")
        }

        // If dry run, just validate
        if (options.dryRun) {
            val validation = validateBundle(bundle)
            return ImportBundleResult(
                success = validation.isValid,
                message = if (validation.isValid) "Dry run validation successful" else "Dry run validation failed",
                imported = ImportCounts(),
                skipped = ImportCounts(),
                errors = validation.errors,
                warnings = validation.warnings
            )
        }

        val importedCounts = ImportCounts()
        val skippedCounts = ImportCounts()
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val newMcpApiKeys = mutableListOf<NewMcpApiKeyInfo>()

        try {
            // Import in dependency order

            // 1. Import workgroups (including hierarchy)
            val workgroupResults = importWorkgroups(bundle.workgroups, options)
            importedCounts.copy(workgroups = workgroupResults.imported)
            skippedCounts.copy(workgroups = workgroupResults.skipped)
            errors.addAll(workgroupResults.errors)
            warnings.addAll(workgroupResults.warnings)

            // 2. Import users (with workgroup assignments)
            val userResults = importUsers(bundle.users, options)
            importedCounts.copy(users = userResults.imported)
            skippedCounts.copy(users = userResults.skipped)
            errors.addAll(userResults.errors)
            warnings.addAll(userResults.warnings)

            // 3. Import user mappings
            val mappingResults = importUserMappings(bundle.userMappings, options)
            importedCounts.copy(userMappings = mappingResults.imported)
            skippedCounts.copy(userMappings = mappingResults.skipped)
            errors.addAll(mappingResults.errors)
            warnings.addAll(mappingResults.warnings)

            // 4. Import identity providers
            val idpResults = importIdentityProviders(bundle.identityProviders, request.providedSecrets, options)
            importedCounts.copy(identityProviders = idpResults.imported)
            skippedCounts.copy(identityProviders = idpResults.skipped)
            errors.addAll(idpResults.errors)
            warnings.addAll(idpResults.warnings)

            // 5. Import Falcon configs
            val falconResults = importFalconConfigs(bundle.falconConfigs, request.providedSecrets, options)
            importedCounts.copy(falconConfigs = falconResults.imported)
            skippedCounts.copy(falconConfigs = falconResults.skipped)
            errors.addAll(falconResults.errors)
            warnings.addAll(falconResults.warnings)

            // 6. Import MCP API keys (generate new keys)
            val mcpResults = importMcpApiKeys(bundle.mcpApiKeys, options)
            importedCounts.copy(mcpApiKeys = mcpResults.imported)
            skippedCounts.copy(mcpApiKeys = mcpResults.skipped)
            errors.addAll(mcpResults.errors)
            warnings.addAll(mcpResults.warnings)
            newMcpApiKeys.addAll(mcpResults.newKeys)

            // Log the import action
            auditLogService.logAction(
                authentication = authentication,
                action = "IMPORT_CONFIG_BUNDLE",
                entityType = "ConfigBundle",
                details = "Imported: ${importedCounts.total()} entities, Skipped: ${skippedCounts.total()} entities"
            )

            return ImportBundleResult(
                success = errors.isEmpty(),
                message = if (errors.isEmpty()) "Import completed successfully" else "Import completed with errors",
                imported = importedCounts,
                skipped = skippedCounts,
                errors = errors,
                warnings = warnings,
                newMcpApiKeys = newMcpApiKeys
            )

        } catch (e: Exception) {
            logger.error("Error during configuration bundle import", e)
            throw RuntimeException("Failed to import configuration bundle: ${e.message}", e)
        }
    }

    /**
     * Validate bundle before import
     */
    open fun validateBundle(bundle: ConfigBundleDto): BundleValidationResult {
        val conflicts = mutableListOf<ConflictInfo>()
        val requiredSecrets = mutableListOf<RequiredSecretInfo>()
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // Check version
        if (bundle.version != BUNDLE_VERSION) {
            warnings.add("Bundle version ${bundle.version} may not be fully compatible with current version $BUNDLE_VERSION")
        }

        // Check for existing entities
        bundle.users.forEach { user ->
            if (userRepository.findByUsername(user.username).isPresent) {
                conflicts.add(ConflictInfo("User", user.username, "already_exists"))
            }
            if (userRepository.findByEmail(user.email) != null) {
                conflicts.add(ConflictInfo("User", user.email, "duplicate_email"))
            }
        }

        bundle.workgroups.forEach { workgroup ->
            if (workgroupRepository.findByName(workgroup.name).isPresent) {
                conflicts.add(ConflictInfo("Workgroup", workgroup.name, "already_exists"))
            }
        }

        bundle.identityProviders.forEach { idp ->
            if (identityProviderRepository.findByName(idp.name) != null) {
                conflicts.add(ConflictInfo("IdentityProvider", idp.name, "already_exists"))
            }
            if (idp.clientSecretMasked) {
                requiredSecrets.add(RequiredSecretInfo("IdentityProvider", idp.name, "client_secret"))
            }
        }

        bundle.falconConfigs.forEach { falcon ->
            if (falcon.clientIdMasked) {
                requiredSecrets.add(RequiredSecretInfo("FalconConfig", "default", "client_id"))
            }
            if (falcon.clientSecretMasked) {
                requiredSecrets.add(RequiredSecretInfo("FalconConfig", "default", "client_secret"))
            }
        }

        // Check if import would leave system without ADMIN
        val existingAdmins = userRepository.findAll().count { it.roles.contains(Role.ADMIN) }
        val importingAdmins = bundle.users.count { it.roles.contains("ADMIN") }
        if (existingAdmins == 0 && importingAdmins == 0) {
            errors.add("Import would leave system without any ADMIN users")
        }

        return BundleValidationResult(
            isValid = errors.isEmpty(),
            schemaVersion = bundle.version,
            conflicts = conflicts,
            requiredSecrets = requiredSecrets,
            errors = errors,
            warnings = warnings
        )
    }

    // Private helper methods for exporting each entity type

    private fun exportUsers(): List<UserExportDto> {
        return userRepository.findAll().map { user ->
            UserExportDto(
                username = user.username,
                email = user.email,
                roles = user.roles.map { it.name }.toSet(),
                workgroupNames = user.workgroups.map { it.name },
                mfaEnabled = user.mfaEnabled,
                isActive = true,
                createdAt = user.createdAt
            )
        }
    }

    private fun exportWorkgroups(): List<WorkgroupExportDto> {
        return workgroupRepository.findAll().map { workgroup ->
            WorkgroupExportDto(
                name = workgroup.name,
                description = workgroup.description,
                criticality = workgroup.criticality,
                parentName = workgroup.parent?.name,
                createdAt = workgroup.createdAt
            )
        }
    }

    private fun exportUserMappings(): List<UserMappingExportDto> {
        return userMappingRepository.findAll().map { mapping ->
            UserMappingExportDto(
                email = mapping.email,
                awsAccountId = mapping.awsAccountId,
                domain = mapping.domain,
                ipAddress = mapping.ipAddress,
                userEmail = mapping.user?.email,
                appliedAt = mapping.appliedAt
            )
        }
    }

    private fun exportIdentityProviders(): List<IdentityProviderExportDto> {
        return identityProviderRepository.findAll().map { idp ->
            IdentityProviderExportDto(
                name = idp.name,
                type = idp.type.name,
                clientId = idp.clientId,
                clientSecretMasked = true,
                clientSecret = null, // Never export client secrets
                tenantId = idp.tenantId,
                discoveryUrl = idp.discoveryUrl,
                authorizationUrl = idp.authorizationUrl,
                tokenUrl = idp.tokenUrl,
                userInfoUrl = idp.userInfoUrl,
                issuer = idp.issuer,
                jwksUri = idp.jwksUri,
                scopes = idp.scopes,
                enabled = idp.enabled,
                autoProvision = idp.autoProvision,
                buttonText = idp.buttonText,
                buttonColor = idp.buttonColor,
                roleMapping = idp.roleMapping,
                claimMappings = idp.claimMappings,
                callbackUrl = idp.callbackUrl,
                createdAt = idp.createdAt
            )
        }
    }

    private fun exportFalconConfigs(): List<FalconConfigExportDto> {
        return falconConfigRepository.findAll().map { falcon ->
            FalconConfigExportDto(
                clientIdEncrypted = null, // Will mask instead of exporting encrypted values
                clientSecretEncrypted = null,
                clientIdMasked = true,
                clientSecretMasked = true,
                cloudRegion = falcon.cloudRegion,
                isActive = falcon.isActive,
                createdAt = falcon.createdAt
            )
        }
    }

    private fun exportMcpApiKeys(): List<McpApiKeyExportDto> {
        return mcpApiKeyRepository.findAll().map { key ->
            McpApiKeyExportDto(
                name = key.name,
                permissions = key.permissions,
                userEmail = userRepository.findById(key.userId).orElse(null)?.email ?: "",
                expiresAt = key.expiresAt,
                notes = key.notes,
                isActive = key.isActive,
                createdAt = key.createdAt
            )
        }
    }

    // Private helper methods for importing each entity type

    private fun importWorkgroups(
        workgroups: List<WorkgroupExportDto>,
        options: ImportOptions
    ): ImportEntityResult {
        var imported = 0
        var skipped = 0
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // First pass: Create all workgroups without parent references
        val createdWorkgroups = mutableMapOf<String, Workgroup>()

        workgroups.forEach { dto ->
            try {
                val existing = workgroupRepository.findByName(dto.name)
                if (existing.isPresent) {
                    if (options.skipExisting) {
                        skipped++
                        warnings.add("Workgroup '${dto.name}' already exists, skipping")
                        createdWorkgroups[dto.name] = existing.get()
                    } else if (options.updateExisting) {
                        val workgroup = existing.get()
                        workgroup.description = dto.description
                        workgroup.criticality = dto.criticality
                        workgroupRepository.save(workgroup)
                        imported++
                        createdWorkgroups[dto.name] = workgroup
                    } else {
                        errors.add("Workgroup '${dto.name}' already exists")
                    }
                } else {
                    val workgroup = Workgroup(
                        name = dto.name,
                        description = dto.description,
                        criticality = dto.criticality
                    )
                    workgroupRepository.save(workgroup)
                    imported++
                    createdWorkgroups[dto.name] = workgroup
                }
            } catch (e: Exception) {
                errors.add("Failed to import workgroup '${dto.name}': ${e.message}")
            }
        }

        // Second pass: Set parent references
        workgroups.filter { it.parentName != null }.forEach { dto ->
            try {
                val workgroup = createdWorkgroups[dto.name]
                val parent = createdWorkgroups[dto.parentName]
                if (workgroup != null && parent != null) {
                    workgroup.parent = parent
                    workgroupRepository.save(workgroup)
                }
            } catch (e: Exception) {
                warnings.add("Failed to set parent for workgroup '${dto.name}': ${e.message}")
            }
        }

        return ImportEntityResult(imported, skipped, errors, warnings)
    }

    private fun importUsers(
        users: List<UserExportDto>,
        options: ImportOptions
    ): ImportEntityResult {
        var imported = 0
        var skipped = 0
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        users.forEach { dto ->
            try {
                val existingByUsername = userRepository.findByUsername(dto.username)
                val existingByEmail = userRepository.findByEmail(dto.email)

                if (existingByUsername.isPresent || existingByEmail != null) {
                    if (options.skipExisting) {
                        skipped++
                        warnings.add("User '${dto.username}' already exists, skipping")
                    } else {
                        errors.add("User '${dto.username}' or email '${dto.email}' already exists")
                    }
                } else {
                    val user = User(
                        username = dto.username,
                        email = dto.email,
                        passwordHash = if (options.generateTempPasswords) {
                            passwordEncoder.encode(generateTempPassword())
                        } else {
                            passwordEncoder.encode("ChangeMeNow123!") // Default password
                        },
                        mfaEnabled = dto.mfaEnabled
                    )

                    // Set roles
                    dto.roles.forEach { roleName ->
                        try {
                            val role = Role.valueOf(roleName)
                            user.roles.add(role)
                        } catch (e: IllegalArgumentException) {
                            warnings.add("Unknown role '$roleName' for user '${dto.username}'")
                        }
                    }

                    // Set workgroups
                    dto.workgroupNames.forEach { workgroupName ->
                        val workgroup = workgroupRepository.findByName(workgroupName)
                        if (workgroup.isPresent) {
                            user.workgroups.add(workgroup.get())
                        } else {
                            warnings.add("Workgroup '$workgroupName' not found for user '${dto.username}'")
                        }
                    }

                    userRepository.save(user)
                    imported++

                    if (options.generateTempPasswords) {
                        warnings.add("Generated temporary password for user '${dto.username}' - password reset required")
                    }
                }
            } catch (e: Exception) {
                errors.add("Failed to import user '${dto.username}': ${e.message}")
            }
        }

        return ImportEntityResult(imported, skipped, errors, warnings)
    }

    private fun importUserMappings(
        mappings: List<UserMappingExportDto>,
        options: ImportOptions
    ): ImportEntityResult {
        var imported = 0
        var skipped = 0
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        mappings.forEach { dto ->
            try {
                // Check if mapping already exists
                val existing = userMappingRepository.findByEmail(dto.email)
                if (existing != null) {
                    if (options.skipExisting) {
                        skipped++
                        warnings.add("User mapping for '${dto.email}' already exists, skipping")
                    } else {
                        errors.add("User mapping for '${dto.email}' already exists")
                    }
                } else {
                    val mapping = UserMapping(
                        email = dto.email,
                        awsAccountId = dto.awsAccountId,
                        domain = dto.domain,
                        ipAddress = dto.ipAddress,
                        appliedAt = dto.appliedAt
                    )

                    // Link to user if exists
                    if (dto.userEmail != null) {
                        val user = userRepository.findByEmail(dto.userEmail)
                        if (user != null) {
                            mapping.user = user
                        } else {
                            warnings.add("User '${dto.userEmail}' not found for mapping '${dto.email}'")
                        }
                    }

                    userMappingRepository.save(mapping)
                    imported++
                }
            } catch (e: Exception) {
                errors.add("Failed to import user mapping for '${dto.email}': ${e.message}")
            }
        }

        return ImportEntityResult(imported, skipped, errors, warnings)
    }

    private fun importIdentityProviders(
        providers: List<IdentityProviderExportDto>,
        providedSecrets: Map<String, String>,
        options: ImportOptions
    ): ImportEntityResult {
        var imported = 0
        var skipped = 0
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        providers.forEach { dto ->
            try {
                val existing = identityProviderRepository.findByName(dto.name)
                if (existing != null) {
                    if (options.skipExisting) {
                        skipped++
                        warnings.add("Identity provider '${dto.name}' already exists, skipping")
                    } else {
                        errors.add("Identity provider '${dto.name}' already exists")
                    }
                } else {
                    // Get client secret from provided secrets if masked
                    val clientSecret = if (dto.clientSecretMasked) {
                        val secretKey = "IdentityProvider:${dto.name}:client_secret"
                        providedSecrets[secretKey] ?: run {
                            errors.add("Client secret not provided for identity provider '${dto.name}'")
                            return@forEach
                        }
                    } else {
                        dto.clientSecret
                    }

                    val provider = IdentityProvider(
                        name = dto.name,
                        type = IdentityProviderType.valueOf(dto.type),
                        clientId = dto.clientId,
                        clientSecret = clientSecret ?: "",
                        tenantId = dto.tenantId,
                        discoveryUrl = dto.discoveryUrl,
                        authorizationUrl = dto.authorizationUrl,
                        tokenUrl = dto.tokenUrl,
                        userInfoUrl = dto.userInfoUrl,
                        issuer = dto.issuer,
                        jwksUri = dto.jwksUri,
                        scopes = dto.scopes,
                        enabled = dto.enabled,
                        autoProvision = dto.autoProvision,
                        buttonText = dto.buttonText,
                        buttonColor = dto.buttonColor,
                        roleMapping = dto.roleMapping,
                        claimMappings = dto.claimMappings,
                        callbackUrl = dto.callbackUrl
                    )

                    identityProviderRepository.save(provider)
                    imported++
                }
            } catch (e: Exception) {
                errors.add("Failed to import identity provider '${dto.name}': ${e.message}")
            }
        }

        return ImportEntityResult(imported, skipped, errors, warnings)
    }

    private fun importFalconConfigs(
        configs: List<FalconConfigExportDto>,
        providedSecrets: Map<String, String>,
        options: ImportOptions
    ): ImportEntityResult {
        var imported = 0
        var skipped = 0
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        configs.forEach { dto ->
            try {
                // Check if any Falcon config exists
                val existing = falconConfigRepository.findAll().firstOrNull()
                if (existing != null) {
                    if (options.skipExisting) {
                        skipped++
                        warnings.add("Falcon configuration already exists, skipping")
                    } else if (options.updateExisting) {
                        // Update existing config
                        val clientId = if (dto.clientIdMasked) {
                            providedSecrets["FalconConfig:default:client_id"] ?: run {
                                errors.add("Falcon client ID not provided")
                                return@forEach
                            }
                        } else {
                            dto.clientIdEncrypted // This would need decryption in real implementation
                        }

                        val clientSecret = if (dto.clientSecretMasked) {
                            providedSecrets["FalconConfig:default:client_secret"] ?: run {
                                errors.add("Falcon client secret not provided")
                                return@forEach
                            }
                        } else {
                            dto.clientSecretEncrypted // This would need decryption in real implementation
                        }

                        existing.clientId = clientId ?: ""
                        existing.clientSecret = clientSecret ?: ""
                        existing.cloudRegion = dto.cloudRegion
                        existing.isActive = dto.isActive
                        falconConfigRepository.save(existing)
                        imported++
                    } else {
                        errors.add("Falcon configuration already exists")
                    }
                } else {
                    // Create new config
                    val clientId = if (dto.clientIdMasked) {
                        providedSecrets["FalconConfig:default:client_id"] ?: run {
                            errors.add("Falcon client ID not provided")
                            return@forEach
                        }
                    } else {
                        dto.clientIdEncrypted
                    }

                    val clientSecret = if (dto.clientSecretMasked) {
                        providedSecrets["FalconConfig:default:client_secret"] ?: run {
                            errors.add("Falcon client secret not provided")
                            return@forEach
                        }
                    } else {
                        dto.clientSecretEncrypted
                    }

                    val config = FalconConfig(
                        clientId = clientId ?: "",
                        clientSecret = clientSecret ?: "",
                        cloudRegion = dto.cloudRegion,
                        isActive = dto.isActive
                    )

                    falconConfigRepository.save(config)
                    imported++
                }
            } catch (e: Exception) {
                errors.add("Failed to import Falcon configuration: ${e.message}")
            }
        }

        return ImportEntityResult(imported, skipped, errors, warnings)
    }

    private fun importMcpApiKeys(
        keys: List<McpApiKeyExportDto>,
        options: ImportOptions
    ): ImportMcpResult {
        var imported = 0
        var skipped = 0
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val newKeys = mutableListOf<NewMcpApiKeyInfo>()

        keys.forEach { dto ->
            try {
                // Find user by email
                val user = userRepository.findByEmail(dto.userEmail)
                if (user == null) {
                    errors.add("User '${dto.userEmail}' not found for MCP API key '${dto.name}'")
                    return@forEach
                }

                // Check if key with same name exists for this user
                val existing = mcpApiKeyRepository.findByUserIdAndName(user.id, dto.name)
                if (existing != null) {
                    if (options.skipExisting) {
                        skipped++
                        warnings.add("MCP API key '${dto.name}' for user '${dto.userEmail}' already exists, skipping")
                    } else {
                        errors.add("MCP API key '${dto.name}' for user '${dto.userEmail}' already exists")
                    }
                } else {
                    // Generate new API key
                    val keyId = UUID.randomUUID().toString().replace("-", "")
                    val keySecret = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
                    val keyHash = passwordEncoder.encode(keySecret)

                    val apiKey = McpApiKey(
                        keyId = keyId,
                        keyHash = keyHash,
                        name = dto.name,
                        userId = user.id,
                        permissions = dto.permissions,
                        expiresAt = dto.expiresAt,
                        notes = dto.notes,
                        isActive = dto.isActive
                    )

                    mcpApiKeyRepository.save(apiKey)
                    imported++

                    // Add to new keys list so admin can see the generated secret
                    newKeys.add(NewMcpApiKeyInfo(
                        name = dto.name,
                        userEmail = dto.userEmail,
                        keyId = keyId,
                        keySecret = keySecret
                    ))

                    warnings.add("Generated new MCP API key '${dto.name}' for user '${dto.userEmail}'")
                }
            } catch (e: Exception) {
                errors.add("Failed to import MCP API key '${dto.name}': ${e.message}")
            }
        }

        return ImportMcpResult(imported, skipped, errors, warnings, newKeys)
    }

    private fun generateTempPassword(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*"
        return (1..TEMP_PASSWORD_LENGTH)
            .map { chars.random() }
            .joinToString("")
    }

    // Helper data classes for import results
    private data class ImportEntityResult(
        val imported: Int,
        val skipped: Int,
        val errors: List<String>,
        val warnings: List<String>
    )

    private data class ImportMcpResult(
        val imported: Int,
        val skipped: Int,
        val errors: List<String>,
        val warnings: List<String>,
        val newKeys: List<NewMcpApiKeyInfo>
    )
}