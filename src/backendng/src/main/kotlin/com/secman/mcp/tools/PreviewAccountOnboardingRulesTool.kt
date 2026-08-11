package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.AccountOnboardingQuestionRepository
import com.secman.service.AccountOnboardingRuleMatcher
import com.secman.util.EmailAddressValidator
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Ask what a given set of answers would resolve to, without submitting anything.
 *
 * The MCP twin of the admin UI's test panel and of a GUIDED dry run: it writes nothing, mails
 * nothing and consumes no invite, so an agent can verify a rule set — including that a
 * combination unions across several rules the way the operator intended — before any account
 * owner is ever mailed a link.
 */
@Singleton
class PreviewAccountOnboardingRulesTool(
    @Inject private val questionRepository: AccountOnboardingQuestionRepository,
    @Inject private val ruleMatcher: AccountOnboardingRuleMatcher
) : McpTool {

    private val log = LoggerFactory.getLogger(PreviewAccountOnboardingRulesTool::class.java)

    override val name = "preview_account_onboarding_rules"
    override val description =
        "Resolve a set of onboarding answers to the use cases they would scope a risk assessment to, " +
            "without creating anything (ADMIN or SECCHAMPION, requires User Delegation)"
    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "answers" to mapOf(
                "type" to "array",
                "description" to "The answers to resolve, one entry per question",
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "questionKey" to mapOf(
                            "type" to "string",
                            "description" to "Key of the question being answered (required)"
                        ),
                        "choiceKeys" to mapOf(
                            "type" to "array",
                            "description" to "Keys of the chosen answers (required)",
                            "items" to mapOf("type" to "string")
                        )
                    ),
                    "required" to listOf("questionKey", "choiceKeys")
                ),
                "maxItems" to MAX_ANSWERS
            )
        ),
        "required" to listOf("answers")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyRole(
            context, "ADMIN", "SECCHAMPION",
            code = "FORBIDDEN",
            message = "ADMIN or SECCHAMPION role required to preview account onboarding rules"
        )?.let { return it }

        @Suppress("UNCHECKED_CAST")
        val answers = arguments["answers"] as? List<Map<String, Any?>>
            ?: return McpToolResult.error("VALIDATION_ERROR", "The 'answers' parameter is required and must be an array")
        if (answers.size > MAX_ANSWERS) {
            return McpToolResult.error("VALIDATION_ERROR", "At most $MAX_ANSWERS answers may be previewed at once")
        }

        return try {
            val questions = questionRepository.findActiveWithChoices()
            val byKey = questions.associateBy { it.questionKey.lowercase() }
            val choiceIds = mutableSetOf<Long>()

            for (answer in answers) {
                val questionKey = (answer["questionKey"] as? String)?.trim().orEmpty()
                val question = byKey[questionKey.lowercase()]
                    // Refused rather than ignored: silently dropping an unknown key would
                    // resolve against a different combination than the caller asked about, and
                    // report the wrong answer confidently.
                    ?: return McpToolResult.error(
                        "VALIDATION_ERROR",
                        "Unknown question '${EmailAddressValidator.sanitizeForEcho(questionKey, 64)}'"
                    )
                @Suppress("UNCHECKED_CAST")
                val choiceKeys = (answer["choiceKeys"] as? List<String>) ?: emptyList()
                for (choiceKey in choiceKeys) {
                    val choice = question.choices
                        .firstOrNull { it.active && it.choiceKey.equals(choiceKey.trim(), ignoreCase = true) }
                        ?: return McpToolResult.error(
                            "VALIDATION_ERROR",
                            "Unknown choice '${EmailAddressValidator.sanitizeForEcho(choiceKey, 64)}' " +
                                "for question '${question.questionKey}'"
                        )
                    choice.id?.let { choiceIds.add(it) }
                }
            }

            val resolution = ruleMatcher.resolve(choiceIds)
            log.debug(
                "MCP preview_account_onboarding_rules: {} choice(s) -> {} rule(s)",
                choiceIds.size, resolution.matchedRuleNames.size
            )

            McpToolResult.success(
                mapOf(
                    "matchedRules" to resolution.matchedRuleNames,
                    "useCases" to resolution.useCases.map { it.name }.sorted(),
                    "requirementCount" to resolution.requirementCount,
                    // True when nothing matched and the fallback carried the result — worth
                    // distinguishing, because "it resolved" and "it fell back" are different
                    // configuration outcomes.
                    "usedDefault" to resolution.usedDefault,
                    "releaseVersion" to resolution.releaseVersion,
                    "failure" to resolution.failure?.name
                )
            )
        } catch (e: Exception) {
            log.error("MCP preview_account_onboarding_rules failed", e)
            McpToolResult.error("EXECUTION_ERROR", "Failed to preview onboarding rules: ${e.message}")
        }
    }

    companion object {
        private const val MAX_ANSWERS = 50
    }
}
