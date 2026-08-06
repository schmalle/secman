package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.ApplicationRegisterRequest
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.ApplicationRegisterService
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.time.LocalDate

@Singleton
class ApplicationRegisterTool(
    @Inject private val applicationRegisterService: ApplicationRegisterService
) : McpTool {

    override val name = "application_register"
    override val description = "List/get/create/update/delete application-register entries and replace related assets"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "action" to mapOf(
                "type" to "string",
                "enum" to listOf("list", "get", "create", "update", "delete", "replace_assets")
            ),
            "id" to mapOf("type" to "number"),
            "search" to mapOf("type" to "string"),
            "assetIds" to mapOf("type" to "array", "items" to mapOf("type" to "number")),
            "application" to mapOf("type" to "object")
        ),
        "required" to listOf("action")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        if (!context.hasDelegation()) return McpToolResult.error("DELEGATION_REQUIRED", "User Delegation must be enabled")

        val action = (arguments["action"] as? String)?.trim()?.lowercase()
            ?: return McpToolResult.error("VALIDATION_ERROR", "action is required")

        return try {
            when (action) {
                "list" -> McpToolResult.success(applicationRegisterService.list(arguments["search"] as? String))
                "get" -> {
                    val id = (arguments["id"] as? Number)?.toLong()
                        ?: return McpToolResult.error("VALIDATION_ERROR", "id is required for get")
                    McpToolResult.success(applicationRegisterService.get(id))
                }
                "create" -> {
                    ensureWriteRole(context)
                    val req = parseRequest(arguments["application"])
                    McpToolResult.success(applicationRegisterService.create(req, context.delegatedUsername ?: "mcp"))
                }
                "update" -> {
                    ensureWriteRole(context)
                    val id = (arguments["id"] as? Number)?.toLong()
                        ?: return McpToolResult.error("VALIDATION_ERROR", "id is required for update")
                    val req = parseRequest(arguments["application"])
                    McpToolResult.success(applicationRegisterService.update(id, req, context.delegatedUsername ?: "mcp"))
                }
                "delete" -> {
                    ensureWriteRole(context)
                    val id = (arguments["id"] as? Number)?.toLong()
                        ?: return McpToolResult.error("VALIDATION_ERROR", "id is required for delete")
                    applicationRegisterService.delete(id)
                    McpToolResult.success(mapOf("deleted" to true, "id" to id))
                }
                "replace_assets" -> {
                    ensureWriteRole(context)
                    val id = (arguments["id"] as? Number)?.toLong()
                        ?: return McpToolResult.error("VALIDATION_ERROR", "id is required for replace_assets")
                    val assetIds = (arguments["assetIds"] as? List<*>)?.mapNotNull { (it as? Number)?.toLong() }
                        ?: emptyList()
                    McpToolResult.success(applicationRegisterService.replaceAssets(id, assetIds, context.delegatedUsername ?: "mcp"))
                }
                else -> McpToolResult.error("VALIDATION_ERROR", "Unknown action '$action'")
            }
        } catch (e: NoSuchElementException) {
            McpToolResult.error("NOT_FOUND", e.message ?: "Application not found")
        } catch (e: IllegalArgumentException) {
            McpToolResult.error("VALIDATION_ERROR", e.message ?: "Invalid input")
        } catch (e: Exception) {
            McpToolResult.error("EXECUTION_ERROR", "Failed to execute application_register action '$action': ${e.message}")
        }
    }

    private fun ensureWriteRole(context: McpExecutionContext) {
        val roles = context.delegatedUserRoles ?: emptySet()
        if (!(context.isAdmin || roles.contains("SECCHAMPION"))) {
            throw IllegalArgumentException("ADMIN or SECCHAMPION role required for write actions")
        }
    }

    private fun parseRequest(raw: Any?): ApplicationRegisterRequest {
        val map = raw as? Map<*, *> ?: throw IllegalArgumentException("application object is required")
        fun str(key: String): String? = (map[key] as? String)
        fun date(key: String): LocalDate? = (map[key] as? String)?.let { LocalDate.parse(it) }
        return ApplicationRegisterRequest(
            carId = str("carId"),
            name = str("name") ?: throw IllegalArgumentException("application.name is required"),
            criticality = str("criticality"),
            operationalStatus = str("operationalStatus"),
            businessOwner = str("businessOwner") ?: throw IllegalArgumentException("application.businessOwner is required"),
            applicationManager = str("applicationManager") ?: throw IllegalArgumentException("application.applicationManager is required"),
            applicationTechnology = str("applicationTechnology"),
            applicationArchitecture = str("applicationArchitecture"),
            lastQualityCheck = date("lastQualityCheck"),
            informationClassification = str("informationClassification"),
            processingOfPersonalData = str("processingOfPersonalData"),
            icsRelevant = str("icsRelevant"),
            applicationExportControlRelevant = str("applicationExportControlRelevant"),
            operationModel = str("operationModel"),
            productionOperatingHours = str("productionOperatingHours"),
            serviceOperatingHours = str("serviceOperatingHours"),
            backupRecoveryUrl = str("backupRecoveryUrl"),
            incidentAssignmentGroup = str("incidentAssignmentGroup"),
            notes = str("notes"),
            cmdbWorkspaceUrl = str("cmdbWorkspaceUrl")
        )
    }
}
