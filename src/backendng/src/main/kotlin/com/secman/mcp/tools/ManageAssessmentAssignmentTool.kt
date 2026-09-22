package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.AssessmentWorkflowService
import jakarta.inject.Singleton

/** Assignment automation does not expose a final acceptance operation. */
@Singleton
class ManageAssessmentAssignmentTool(private val workflow: AssessmentWorkflowService) : McpTool {
    override val name = "manage_assessment_assignment"
    override val description = "ADMIN or SECCHAMPION: list, assign, revoke assessment tasks or reopen submitted answers"
    override val operation = McpOperation.WRITE
    override val inputSchema = mapOf(
        "type" to "object", "required" to listOf("assessmentId", "action"),
        "properties" to mapOf(
            "assessmentId" to mapOf("type" to "integer", "minimum" to 1),
            "action" to mapOf("type" to "string", "enum" to listOf("LIST", "ASSIGN", "REVOKE", "REOPEN")),
            "assignmentId" to mapOf("type" to "integer", "minimum" to 1),
            "userId" to mapOf("type" to "integer", "minimum" to 1),
            "email" to mapOf("type" to "string", "maxLength" to 254),
            "role" to mapOf("type" to "string", "enum" to listOf("RESPONDENT", "ASSESSOR")),
            "requirementIds" to mapOf("type" to "array", "maxItems" to 1000, "items" to mapOf("type" to "integer", "minimum" to 1))
        )
    )
    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyUserRole(context, "ADMIN", "SECCHAMPION", message = "Assessment manager role required")?.let { return it }
        val id = (arguments["assessmentId"] as? Number)?.toLong()
            ?: return McpToolResult.error("VALIDATION_ERROR", "assessmentId required")
        return riskAssessmentTool {
            require(id > 0) { "assessmentId must be positive" }
            require(arguments["requirementIds"] == null || arguments["requirementIds"] is List<*>) {
                "requirementIds must be an array"
            }
            when (arguments["action"]) {
                "LIST" -> workflow.listAssignments(id, context.authentication())
                "ASSIGN" -> workflow.assign(id, context.authentication(), (arguments["userId"] as? Number)?.toLong(),
                    arguments["email"] as? String ?: "", arguments["role"] as? String ?: "RESPONDENT",
                    (arguments["requirementIds"] as? List<*>)?.map {
                        (it as? Number)?.toLong() ?: throw IllegalArgumentException("Invalid requirement ID")
                    }?.toSet().orEmpty())
                "REVOKE" -> { workflow.revoke(id, (arguments["assignmentId"] as? Number)?.toLong()
                    ?: throw IllegalArgumentException("assignmentId required"), context.authentication()); mapOf("revoked" to true) }
                "REOPEN" -> workflow.reopen(id, context.authentication()).let { mapOf("status" to it.status) }
                else -> throw IllegalArgumentException("Unsupported action")
            }
        }
    }
}
