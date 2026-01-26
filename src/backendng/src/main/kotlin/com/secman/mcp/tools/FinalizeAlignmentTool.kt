package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.AlignmentService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for finalizing or cancelling an alignment session.
 * Feature: 068-requirements-alignment-process
 *
 * Completes the alignment process and optionally activates the release,
 * or cancels the alignment returning the release to DRAFT.
 *
 * Accessible by: ADMIN, RELEASE_MANAGER roles (via User Delegation)
 */
@Singleton
class FinalizeAlignmentTool(
    @Inject private val alignmentService: AlignmentService
) : McpTool {

    override val name = "finalize_alignment"
    override val description = "Finalize a requirements alignment session. Can complete and activate the release, complete without activating, or cancel the alignment."
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "session_id" to mapOf(
                "type" to "number",
                "description" to "ID of the alignment session to finalize"
            ),
            "action" to mapOf(
                "type" to "string",
                "description" to "Action to take: 'complete_and_activate' (complete and set release to ACTIVE), 'complete' (complete but keep release as DRAFT), or 'cancel' (cancel alignment, return to DRAFT)",
                "enum" to listOf("complete_and_activate", "complete", "cancel")
            ),
            "notes" to mapOf(
                "type" to "string",
                "description" to "Optional notes about the finalization decision"
            )
        ),
        "required" to listOf("session_id", "action")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        // Check authorization - require User Delegation with ADMIN or RELEASE_MANAGER role
        if (!context.hasDelegation()) {
            return McpToolResult.error(
                "DELEGATION_REQUIRED",
                "User Delegation must be enabled to use this tool"
            )
        }

        val userRoles = context.delegatedUserRoles?.map { it.uppercase() } ?: emptyList()
        if (!userRoles.contains("ADMIN") && !userRoles.contains("RELEASE_MANAGER")) {
            return McpToolResult.error(
                "AUTHORIZATION_ERROR",
                "ADMIN or RELEASE_MANAGER role required to finalize alignment"
            )
        }

        // Extract parameters
        val sessionId = (arguments["session_id"] as? Number)?.toLong()
        val action = arguments["action"] as? String
        val notes = arguments["notes"] as? String

        if (sessionId == null) {
            return McpToolResult.error("VALIDATION_ERROR", "session_id is required")
        }
        if (action.isNullOrBlank()) {
            return McpToolResult.error("VALIDATION_ERROR", "action is required")
        }

        try {
            val session = when (action.lowercase()) {
                "complete_and_activate" -> {
                    alignmentService.finalizeAlignment(sessionId, activateRelease = true, notes = notes)
                }
                "complete" -> {
                    alignmentService.finalizeAlignment(sessionId, activateRelease = false, notes = notes)
                }
                "cancel" -> {
                    alignmentService.cancelAlignment(sessionId, notes = notes)
                }
                else -> {
                    return McpToolResult.error(
                        "VALIDATION_ERROR",
                        "Invalid action. Must be one of: complete_and_activate, complete, cancel"
                    )
                }
            }

            val actionMessage = when (action.lowercase()) {
                "complete_and_activate" -> "Alignment completed and release ${session.release.version} activated"
                "complete" -> "Alignment completed, release ${session.release.version} returned to DRAFT"
                "cancel" -> "Alignment cancelled, release ${session.release.version} returned to DRAFT"
                else -> "Action completed"
            }

            val response = mapOf(
                "success" to true,
                "action" to action,
                "session" to mapOf(
                    "id" to session.id,
                    "releaseId" to session.release.id,
                    "releaseVersion" to session.release.version,
                    "releaseName" to session.release.name,
                    "status" to session.status.name,
                    "releaseStatus" to session.release.status.name,
                    "completedAt" to session.completedAt?.toString(),
                    "completionNotes" to session.completionNotes
                ),
                "message" to actionMessage
            )

            return McpToolResult.success(response)

        } catch (e: IllegalStateException) {
            return McpToolResult.error("CONFLICT_ERROR", e.message ?: "Cannot finalize alignment")
        } catch (e: NoSuchElementException) {
            return McpToolResult.error("NOT_FOUND", e.message ?: "Session not found")
        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to finalize alignment: ${e.message}")
        }
    }
}
