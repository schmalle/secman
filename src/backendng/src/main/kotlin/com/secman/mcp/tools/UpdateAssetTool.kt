package com.secman.mcp.tools

import com.secman.domain.Criticality
import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.AssetRepository
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for updating an existing asset's properties.
 *
 * Requires User Delegation for audit trail. Uses row-level access control:
 * users can only update assets they have access to (via workgroup, ownership,
 * manual creator, scan uploader, cloud account, or AD domain mappings).
 *
 * Supports partial updates — only provided fields are modified.
 * Workgroup reassignment is deliberately excluded (use assign_assets_to_workgroup tool).
 *
 * Input parameters:
 * - assetId (required): ID of the asset to update
 * - name (optional): New asset name
 * - type (optional): New asset type
 * - owner (optional): New owner username
 * - ip (optional): New IP address
 * - description (optional): New description
 * - criticality (optional): New criticality (CRITICAL, HIGH, MEDIUM, LOW, NA)
 * - adDomain (optional): New Active Directory domain
 *
 * Output:
 * - id, name, type, owner, ip, criticality, adDomain: Updated asset fields
 * - updatedFields: List of fields that were changed
 * - message: Success message
 */
@Singleton
class UpdateAssetTool(
    @Inject private val assetRepository: AssetRepository
) : McpTool {

    override val name = "update_asset"
    override val description = "Update an existing asset's properties such as owner, name, type, or criticality (requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "assetId" to mapOf(
                "type" to "number",
                "description" to "The ID of the asset to update"
            ),
            "name" to mapOf(
                "type" to "string",
                "description" to "New asset name (max 255 characters)",
                "maxLength" to 255
            ),
            "type" to mapOf(
                "type" to "string",
                "description" to "New asset type (e.g., SERVER, WORKSTATION)"
            ),
            "owner" to mapOf(
                "type" to "string",
                "description" to "New owner username (max 255 characters)",
                "maxLength" to 255
            ),
            "ip" to mapOf(
                "type" to "string",
                "description" to "New IP address"
            ),
            "description" to mapOf(
                "type" to "string",
                "description" to "New asset description"
            ),
            "criticality" to mapOf(
                "type" to "string",
                "enum" to listOf("CRITICAL", "HIGH", "MEDIUM", "LOW", "NA"),
                "description" to "New criticality level"
            ),
            "adDomain" to mapOf(
                "type" to "string",
                "description" to "New Active Directory domain"
            )
        ),
        "required" to listOf("assetId")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        // Require User Delegation for audit trail
        if (!context.hasDelegation()) {
            return McpToolResult.error(
                "DELEGATION_REQUIRED",
                "User Delegation must be enabled to use this tool"
            )
        }

        // Extract and validate asset ID
        val assetId = (arguments["assetId"] as? Number)?.toLong()
        if (assetId == null) {
            return McpToolResult.error("VALIDATION_ERROR", "assetId is required and must be a valid number")
        }

        // Collect update fields (exclude assetId)
        val updateFields = arguments.keys - "assetId"
        if (updateFields.isEmpty()) {
            return McpToolResult.error("VALIDATION_ERROR", "At least one field to update must be provided")
        }

        // Row-level access control
        if (!context.canAccessAsset(assetId)) {
            return McpToolResult.error("NOT_FOUND", "Asset with ID $assetId not found or access denied")
        }

        try {
            val asset = assetRepository.findById(assetId).orElse(null)
                ?: return McpToolResult.error("NOT_FOUND", "Asset with ID $assetId not found")

            val updatedFields = mutableListOf<String>()

            // Apply partial updates matching AssetController.update() pattern
            (arguments["name"] as? String)?.let { newName ->
                val trimmed = newName.trim()
                if (trimmed.isBlank()) {
                    return McpToolResult.error("VALIDATION_ERROR", "Name cannot be empty")
                }
                if (trimmed.length > 255) {
                    return McpToolResult.error("VALIDATION_ERROR", "Name must not exceed 255 characters")
                }
                asset.name = trimmed
                updatedFields.add("name")
            }

            (arguments["type"] as? String)?.let { newType ->
                val trimmed = newType.trim()
                if (trimmed.isBlank()) {
                    return McpToolResult.error("VALIDATION_ERROR", "Type cannot be empty")
                }
                asset.type = trimmed
                updatedFields.add("type")
            }

            (arguments["owner"] as? String)?.let { newOwner ->
                val trimmed = newOwner.trim()
                if (trimmed.isBlank()) {
                    return McpToolResult.error("VALIDATION_ERROR", "Owner cannot be empty")
                }
                if (trimmed.length > 255) {
                    return McpToolResult.error("VALIDATION_ERROR", "Owner must not exceed 255 characters")
                }
                asset.owner = trimmed
                updatedFields.add("owner")
            }

            (arguments["ip"] as? String)?.let { newIp ->
                asset.ip = newIp.trim().takeIf { it.isNotBlank() }
                updatedFields.add("ip")
            }

            (arguments["description"] as? String)?.let { newDescription ->
                asset.description = newDescription.trim().takeIf { it.isNotBlank() }
                updatedFields.add("description")
            }

            (arguments["criticality"] as? String)?.let { critStr ->
                val trimmed = critStr.trim().uppercase()
                try {
                    asset.criticality = Criticality.valueOf(trimmed)
                    updatedFields.add("criticality")
                } catch (e: IllegalArgumentException) {
                    return McpToolResult.error(
                        "VALIDATION_ERROR",
                        "Invalid criticality: '$trimmed'. Must be one of: CRITICAL, HIGH, MEDIUM, LOW, NA"
                    )
                }
            }

            (arguments["adDomain"] as? String)?.let { newAdDomain ->
                asset.adDomain = newAdDomain.trim().takeIf { it.isNotBlank() }
                updatedFields.add("adDomain")
            }

            if (updatedFields.isEmpty()) {
                return McpToolResult.error("VALIDATION_ERROR", "No valid fields to update were provided")
            }

            val savedAsset = assetRepository.save(asset)

            val result = mapOf(
                "id" to savedAsset.id,
                "name" to savedAsset.name,
                "type" to savedAsset.type,
                "owner" to savedAsset.owner,
                "ip" to savedAsset.ip,
                "criticality" to savedAsset.criticality?.name,
                "adDomain" to savedAsset.adDomain,
                "updatedFields" to updatedFields,
                "message" to "Asset '${savedAsset.name}' (id: ${savedAsset.id}) updated: ${updatedFields.joinToString(", ")}"
            )

            return McpToolResult.success(result)

        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to update asset: ${e.message}")
        }
    }
}
