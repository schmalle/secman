package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import java.time.Instant
import java.time.LocalDateTime

/**
 * Main configuration bundle DTO containing all exported configuration data
 */
@Serdeable
data class ConfigBundleDto(
    val version: String = "1.0",
    val exportedAt: Instant = Instant.now(),
    val exportedBy: String,
    val systemInfo: SystemInfoDto? = null,
    val users: List<UserExportDto> = emptyList(),
    val workgroups: List<WorkgroupExportDto> = emptyList(),
    val userMappings: List<UserMappingExportDto> = emptyList(),
    val identityProviders: List<IdentityProviderExportDto> = emptyList(),
    val falconConfigs: List<FalconConfigExportDto> = emptyList(),
    val mcpApiKeys: List<McpApiKeyExportDto> = emptyList()
)

/**
 * System information for compatibility checking
 */
@Serdeable
data class SystemInfoDto(
    val applicationVersion: String? = null,
    val exportHost: String? = null
)

/**
 * User export data - excludes passwords for security
 */
@Serdeable
data class UserExportDto(
    val username: String,
    val email: String,
    val roles: Set<String>,
    val workgroupNames: List<String> = emptyList(), // Reference by name for portability
    val mfaEnabled: Boolean = false,
    val isActive: Boolean = true,
    val createdAt: Instant? = null
)

/**
 * Workgroup export data with hierarchy support
 */
@Serdeable
data class WorkgroupExportDto(
    val name: String,
    val description: String? = null,
    val criticality: String? = null,
    val parentName: String? = null, // Reference parent by name for hierarchy
    val createdAt: Instant? = null
)

/**
 * User mapping export data
 */
@Serdeable
data class UserMappingExportDto(
    val email: String,
    val awsAccountId: String? = null,
    val domain: String? = null,
    val ipAddress: String? = null,
    val userEmail: String? = null, // Reference to user by email
    val appliedAt: Instant? = null
)

/**
 * Identity provider export data with masked secrets
 */
@Serdeable
data class IdentityProviderExportDto(
    val name: String,
    val type: String, // OIDC or SAML
    val clientId: String,
    val clientSecretMasked: Boolean = true, // Indicates if secret needs re-entry
    val clientSecret: String? = null, // Will be null if masked
    val tenantId: String? = null,
    val discoveryUrl: String? = null,
    val authorizationUrl: String? = null,
    val tokenUrl: String? = null,
    val userInfoUrl: String? = null,
    val issuer: String? = null,
    val jwksUri: String? = null,
    val scopes: String? = null,
    val enabled: Boolean = true,
    val autoProvision: Boolean = false,
    val buttonText: String? = null,
    val buttonColor: String? = null,
    val roleMapping: String? = null, // JSON string
    val claimMappings: String? = null, // JSON string
    val callbackUrl: String? = null,
    val createdAt: Instant? = null
)

/**
 * Falcon/CrowdStrike configuration export data with encrypted credentials
 */
@Serdeable
data class FalconConfigExportDto(
    val clientIdEncrypted: String? = null, // Base64 encoded encrypted value
    val clientSecretEncrypted: String? = null, // Base64 encoded encrypted value
    val clientIdMasked: Boolean = false, // If true, needs re-entry
    val clientSecretMasked: Boolean = false, // If true, needs re-entry
    val cloudRegion: String, // us-1, us-2, eu-1, us-gov-1, us-gov-2
    val isActive: Boolean = false,
    val createdAt: Instant? = null
)

/**
 * MCP API key export data - cannot export actual secrets
 */
@Serdeable
data class McpApiKeyExportDto(
    val name: String,
    val permissions: String, // Comma-separated permissions
    val userEmail: String, // Reference user by email
    val expiresAt: LocalDateTime? = null,
    val notes: String? = null,
    val isActive: Boolean = true,
    val createdAt: Instant? = null
)

/**
 * Import result for bundle import operations
 */
@Serdeable
data class ImportBundleResult(
    val success: Boolean,
    val message: String,
    val imported: ImportCounts,
    val skipped: ImportCounts,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val newMcpApiKeys: List<NewMcpApiKeyInfo> = emptyList() // New API keys generated during import
)

/**
 * Counts of imported/skipped entities
 */
@Serdeable
data class ImportCounts(
    val users: Int = 0,
    val workgroups: Int = 0,
    val userMappings: Int = 0,
    val identityProviders: Int = 0,
    val falconConfigs: Int = 0,
    val mcpApiKeys: Int = 0
) {
    fun total(): Int = users + workgroups + userMappings + identityProviders + falconConfigs + mcpApiKeys
}

/**
 * Information about newly generated MCP API keys during import
 */
@Serdeable
data class NewMcpApiKeyInfo(
    val name: String,
    val userEmail: String,
    val keyId: String,
    val keySecret: String // Only time the secret is available
)

/**
 * Validation result for bundle validation
 */
@Serdeable
data class BundleValidationResult(
    val isValid: Boolean,
    val schemaVersion: String? = null,
    val conflicts: List<ConflictInfo> = emptyList(),
    val requiredSecrets: List<RequiredSecretInfo> = emptyList(),
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)

/**
 * Information about conflicts with existing entities
 */
@Serdeable
data class ConflictInfo(
    val entityType: String,
    val identifier: String,
    val conflictType: String // e.g., "already_exists", "duplicate_email"
)

/**
 * Information about secrets that need to be provided during import
 */
@Serdeable
data class RequiredSecretInfo(
    val entityType: String,
    val identifier: String,
    val secretType: String // e.g., "client_secret", "api_credentials"
)

/**
 * Request DTO for importing with provided secrets
 */
@Serdeable
data class ImportBundleRequest(
    val bundle: ConfigBundleDto,
    val providedSecrets: Map<String, String> = emptyMap(), // Key: entityType:identifier:secretType, Value: secret
    val options: ImportOptions = ImportOptions()
)

/**
 * Options for import behavior
 */
@Serdeable
data class ImportOptions(
    val skipExisting: Boolean = true, // Skip entities that already exist
    val updateExisting: Boolean = false, // Update existing entities (if false and not skip, will error)
    val generateTempPasswords: Boolean = true, // Generate temporary passwords for users
    val dryRun: Boolean = false // Validate only, don't actually import
)