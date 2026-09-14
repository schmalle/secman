package com.secman.mcp.tools

import com.secman.domain.Criticality
import com.secman.domain.Asset
import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.AssetRepository
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.net.URI

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

    private val log = LoggerFactory.getLogger(UpdateAssetTool::class.java)

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
            "resetNameToCrowdStrike" to mapOf(
                "type" to "boolean",
                "description" to "Clear the user name override and restore the latest CrowdStrike hostname"
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
            "uri" to mapOf(
                "type" to "string",
                "description" to "New asset URI (http, https, or urn)",
                "maxLength" to 2048
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

    private fun normalizeUri(value: String?): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (trimmed.length > 2048) {
            throw IllegalArgumentException("URI must not exceed 2048 characters")
        }
        val parsed = URI.create(trimmed)
        val scheme = parsed.scheme?.lowercase()
        if (scheme.isNullOrBlank()) {
            throw IllegalArgumentException("URI must include a scheme, such as https:// or urn:")
        }
        if (scheme !in setOf("http", "https", "urn")) {
            throw IllegalArgumentException("URI scheme must be http, https, or urn")
        }
        if (scheme in setOf("http", "https") && parsed.host.isNullOrBlank()) {
            throw IllegalArgumentException("HTTP(S) URI must include a host")
        }
        return trimmed
    }

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }

        val assetId = (arguments["assetId"] as? Number)?.toLong()
            ?: return McpToolResult.error("VALIDATION_ERROR", "assetId is required and must be a valid number")
        if (arguments.keys == setOf("assetId")) {
            return McpToolResult.error("VALIDATION_ERROR", "At least one field to update must be provided")
        }

        if (!context.canAccessAsset(assetId)) {
            return McpToolResult.error("NOT_FOUND", "Asset with ID $assetId not found or access denied")
        }

        try {
            val asset = assetRepository.findById(assetId).orElse(null)
                ?: return McpToolResult.error("NOT_FOUND", "Asset with ID $assetId not found")

            val updatedFields = mutableListOf<String>()
            applyNameUpdate(arguments, context, asset, updatedFields)?.let { return it }
            applyOtherUpdates(arguments, asset, updatedFields)?.let { return it }

            if (updatedFields.isEmpty()) {
                return McpToolResult.error("VALIDATION_ERROR", "No valid fields to update were provided")
            }
            return saveUpdate(asset, updatedFields, context)

        } catch (e: Exception) {
            log.error(
                "MCP asset update failed: actor={} assetId={} outcome=failed",
                context.delegatedUserEmail, assetId, e
            )
            return McpToolResult.error("EXECUTION_ERROR", "Failed to update asset")
        }
    }

    private fun saveUpdate(
        asset: Asset,
        updatedFields: List<String>,
        context: McpExecutionContext
    ): McpToolResult {
        val savedAsset = assetRepository.save(asset)
        val result = mapOf(
            "id" to savedAsset.id,
            "name" to savedAsset.name,
            "crowdStrikeHostname" to savedAsset.crowdStrikeHostname,
            "nameOverridden" to (savedAsset.nameOverriddenAt != null),
            "type" to savedAsset.type,
            "owner" to savedAsset.owner,
            "ip" to savedAsset.ip,
            "uri" to savedAsset.uri,
            "criticality" to savedAsset.criticality?.name,
            "adDomain" to savedAsset.adDomain,
            "updatedFields" to updatedFields,
            "message" to "Asset '${savedAsset.name}' (id: ${savedAsset.id}) updated: ${updatedFields.joinToString(", ")}"
        )
        log.info(
            "MCP asset update completed: actor={} assetId={} nameOverride={} outcome=updated",
            context.delegatedUserEmail, savedAsset.id, savedAsset.nameOverriddenAt != null
        )
        return McpToolResult.success(result)
    }

    private fun applyNameUpdate(
        arguments: Map<String, Any>,
        context: McpExecutionContext,
        asset: Asset,
        updatedFields: MutableList<String>
    ): McpToolResult.Error? {
        val resetName = arguments["resetNameToCrowdStrike"] as? Boolean ?: false
        if (resetName && arguments["name"] != null) {
            return McpToolResult.error(
                "VALIDATION_ERROR",
                "name and resetNameToCrowdStrike cannot be used together"
            )
        }
        if (resetName) {
            if (!asset.resetNameOverride()) {
                return McpToolResult.error(
                    "VALIDATION_ERROR",
                    "Asset has no CrowdStrike hostname to restore"
                )
            }
            updatedFields.add("nameOverride")
        }

        (arguments["name"] as? String)?.let { newName ->
            val trimmed = newName.trim()
            if (trimmed.isBlank()) {
                return McpToolResult.error("VALIDATION_ERROR", "Name cannot be empty")
            }
            if (trimmed.length > 255) {
                return McpToolResult.error(
                    "VALIDATION_ERROR",
                    "Name must not exceed 255 characters"
                )
            }
            if (trimmed != asset.name) {
                val actor = context.delegatedUsername ?: context.delegatedUserEmail ?: "mcp-user"
                asset.overrideName(trimmed, actor)
                updatedFields.add("name")
            }
        }
        return null
    }

    private fun applyOtherUpdates(
        arguments: Map<String, Any>,
        asset: Asset,
        updatedFields: MutableList<String>
    ): McpToolResult.Error? {
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
                return McpToolResult.error(
                    "VALIDATION_ERROR",
                    "Owner must not exceed 255 characters"
                )
            }
            asset.owner = trimmed
            updatedFields.add("owner")
        }
        applyOptionalTextUpdates(arguments, asset, updatedFields)

        (arguments["uri"] as? String)?.let { newUri ->
            asset.uri = try {
                normalizeUri(newUri)
            } catch (e: IllegalArgumentException) {
                return McpToolResult.error("VALIDATION_ERROR", e.message ?: "Invalid URI")
            }
            updatedFields.add("uri")
        }
        return applyCriticalityUpdate(arguments, asset, updatedFields)
    }

    private fun applyCriticalityUpdate(
        arguments: Map<String, Any>,
        asset: Asset,
        updatedFields: MutableList<String>
    ): McpToolResult.Error? {
        (arguments["criticality"] as? String)?.let { criticality ->
            val normalized = criticality.trim().uppercase()
            asset.criticality = try {
                Criticality.valueOf(normalized)
            } catch (e: IllegalArgumentException) {
                return McpToolResult.error(
                    "VALIDATION_ERROR",
                    "Invalid criticality: '$normalized'. Must be one of: CRITICAL, HIGH, MEDIUM, LOW, NA"
                )
            }
            updatedFields.add("criticality")
        }
        return null
    }

    private fun applyOptionalTextUpdates(
        arguments: Map<String, Any>,
        asset: Asset,
        updatedFields: MutableList<String>
    ) {
        (arguments["ip"] as? String)?.let {
            asset.ip = it.trim().takeIf(String::isNotBlank)
            updatedFields.add("ip")
        }
        (arguments["description"] as? String)?.let {
            asset.description = it.trim().takeIf(String::isNotBlank)
            updatedFields.add("description")
        }
        (arguments["adDomain"] as? String)?.let {
            asset.adDomain = it.trim().takeIf(String::isNotBlank)
            updatedFields.add("adDomain")
        }
    }
}
