package com.secman.mcp.tools

import com.secman.domain.Asset
import com.secman.domain.Criticality
import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.AssetRepository
import com.secman.repository.UserRepository
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for creating a new asset in the inventory.
 *
 * Requires User Delegation for audit trail. Any authenticated user can create assets
 * (matching REST API behavior). The delegated user is recorded as manualCreator
 * for access control purposes.
 *
 * Duplicate detection: returns error if asset with same name already exists (case-insensitive).
 *
 * Input parameters:
 * - name (required): Asset hostname/name (max 255)
 * - type (required): Asset type (e.g., "SERVER", "WORKSTATION")
 * - owner (required): Owner username (max 255)
 * - ip (optional): IP address
 * - description (optional): Asset description
 * - criticality (optional): CRITICAL, HIGH, MEDIUM, LOW, or NA
 * - adDomain (optional): Active Directory domain
 * - cloudAccountId (optional): AWS account ID
 *
 * Output:
 * - id: ID of the created asset
 * - name: Asset name
 * - type: Asset type
 * - owner: Owner username
 * - message: Success message
 */
@Singleton
class CreateAssetTool(
    @Inject private val assetRepository: AssetRepository,
    @Inject private val userRepository: UserRepository
) : McpTool {

    override val name = "create_asset"
    override val description = "Create a new asset in the inventory (requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "name" to mapOf(
                "type" to "string",
                "description" to "Asset hostname or name (max 255 characters)",
                "maxLength" to 255
            ),
            "type" to mapOf(
                "type" to "string",
                "description" to "Asset type (e.g., SERVER, WORKSTATION, NETWORK_DEVICE)"
            ),
            "owner" to mapOf(
                "type" to "string",
                "description" to "Owner username (max 255 characters)",
                "maxLength" to 255
            ),
            "ip" to mapOf(
                "type" to "string",
                "description" to "IP address of the asset"
            ),
            "description" to mapOf(
                "type" to "string",
                "description" to "Asset description"
            ),
            "criticality" to mapOf(
                "type" to "string",
                "enum" to listOf("CRITICAL", "HIGH", "MEDIUM", "LOW", "NA"),
                "description" to "Asset criticality level"
            ),
            "adDomain" to mapOf(
                "type" to "string",
                "description" to "Active Directory domain"
            ),
            "cloudAccountId" to mapOf(
                "type" to "string",
                "description" to "AWS cloud account ID"
            )
        ),
        "required" to listOf("name", "type", "owner")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        // Require User Delegation for audit trail
        if (!context.hasDelegation()) {
            return McpToolResult.error(
                "DELEGATION_REQUIRED",
                "User Delegation must be enabled to use this tool"
            )
        }

        // Extract and validate required parameters
        val assetName = (arguments["name"] as? String)?.trim()
        val assetType = (arguments["type"] as? String)?.trim()
        val owner = (arguments["owner"] as? String)?.trim()

        if (assetName.isNullOrBlank()) {
            return McpToolResult.error("VALIDATION_ERROR", "Name is required and cannot be blank")
        }
        if (assetName.length > 255) {
            return McpToolResult.error("VALIDATION_ERROR", "Name must not exceed 255 characters")
        }

        if (assetType.isNullOrBlank()) {
            return McpToolResult.error("VALIDATION_ERROR", "Type is required and cannot be blank")
        }

        if (owner.isNullOrBlank()) {
            return McpToolResult.error("VALIDATION_ERROR", "Owner is required and cannot be blank")
        }
        if (owner.length > 255) {
            return McpToolResult.error("VALIDATION_ERROR", "Owner must not exceed 255 characters")
        }

        // Check for duplicate asset (case-insensitive)
        val existing = assetRepository.findByNameIgnoreCase(assetName)
        if (existing != null) {
            return McpToolResult.error(
                "DUPLICATE_ASSET",
                "Asset with name '${assetName}' already exists (id: ${existing.id})"
            )
        }

        // Parse optional criticality
        val criticality = (arguments["criticality"] as? String)?.trim()?.uppercase()?.let { critStr ->
            try {
                Criticality.valueOf(critStr)
            } catch (e: IllegalArgumentException) {
                return McpToolResult.error(
                    "VALIDATION_ERROR",
                    "Invalid criticality: '$critStr'. Must be one of: CRITICAL, HIGH, MEDIUM, LOW, NA"
                )
            }
        }

        // Extract optional parameters
        val ip = (arguments["ip"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val description = (arguments["description"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val adDomain = (arguments["adDomain"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val cloudAccountId = (arguments["cloudAccountId"] as? String)?.trim()?.takeIf { it.isNotBlank() }

        try {
            // Resolve delegated user as manualCreator for access control
            val manualCreator = context.delegatedUserId?.let { userId ->
                userRepository.findById(userId).orElse(null)
            }

            // Create asset entity
            val asset = Asset(
                name = assetName,
                type = assetType,
                ip = ip,
                owner = owner,
                description = description,
                criticality = criticality,
                manualCreator = manualCreator
            )
            asset.adDomain = adDomain
            asset.cloudAccountId = cloudAccountId

            val savedAsset = assetRepository.save(asset)

            val result = mapOf(
                "id" to savedAsset.id,
                "name" to savedAsset.name,
                "type" to savedAsset.type,
                "owner" to savedAsset.owner,
                "ip" to savedAsset.ip,
                "criticality" to savedAsset.criticality?.name,
                "adDomain" to savedAsset.adDomain,
                "cloudAccountId" to savedAsset.cloudAccountId,
                "message" to "Asset '${savedAsset.name}' created successfully (id: ${savedAsset.id})"
            )

            return McpToolResult.success(result)

        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to create asset: ${e.message}")
        }
    }
}
