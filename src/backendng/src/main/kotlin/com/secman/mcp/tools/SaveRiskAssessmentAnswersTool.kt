package com.secman.mcp.tools

import com.secman.domain.AnswerType
import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.RiskAssessmentMcpService
import jakarta.inject.Singleton

@Singleton
class SaveRiskAssessmentAnswersTool(private val service: RiskAssessmentMcpService) : McpTool {
    override val name = "save_risk_assessment_answers"
    override val description = "Save questionnaire answers as the assessment's delegated respondent"
    override val operation = McpOperation.WRITE
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "assessmentId" to mapOf("type" to "number", "minimum" to 1),
            "answers" to mapOf(
                "type" to "array",
                "maxItems" to RiskAssessmentMcpService.MAX_ANSWERS,
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "requirementId" to mapOf("type" to "number", "minimum" to 1),
                        "answerType" to mapOf("type" to "string", "enum" to listOf("YES", "NO", "N_A")),
                        "comment" to mapOf("type" to "string", "maxLength" to RiskAssessmentMcpService.MAX_COMMENT_LENGTH)
                    ),
                    "required" to listOf("requirementId", "answerType")
                )
            )
        ),
        "required" to listOf("assessmentId", "answers")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyUserRole(
            context, "USER", "ADMIN", "RISK", "SECCHAMPION",
            message = "ADMIN, RISK or SECCHAMPION role required to answer risk assessments"
        )?.let { return it }
        val id = (arguments["assessmentId"] as? Number)?.toLong()
            ?: return McpToolResult.error("VALIDATION_ERROR", "assessmentId is required")
        val rawAnswers = arguments["answers"] as? List<*>
            ?: return McpToolResult.error("VALIDATION_ERROR", "answers is required and must be an array")
        val answers = mutableListOf<RiskAssessmentMcpService.AnswerInput>()
        rawAnswers.forEachIndexed { index, value ->
            val row = value as? Map<*, *>
                ?: return McpToolResult.error("VALIDATION_ERROR", "answers[$index] must be an object")
            val requirementId = (row["requirementId"] as? Number)?.toLong()
                ?: return McpToolResult.error("VALIDATION_ERROR", "answers[$index].requirementId is required")
            val answerType = try {
                AnswerType.valueOf((row["answerType"] as? String)?.uppercase() ?: "")
            } catch (e: IllegalArgumentException) {
                return McpToolResult.error("VALIDATION_ERROR", "answers[$index].answerType must be YES, NO or N_A")
            }
            answers += RiskAssessmentMcpService.AnswerInput(requirementId, answerType, row["comment"] as? String)
        }
        return riskAssessmentTool { service.saveAnswers(context, id, answers) }
    }
}
