package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.AccountOnboardingService
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * List the configured onboarding questions and the rules that turn an owner's answers into
 * use cases.
 *
 * Read-only. Together with [PreviewAccountOnboardingRulesTool] this is how an agent — or an
 * E2E driver — checks a rule set without mailing anyone: list what exists, then ask what a
 * given set of answers would resolve to.
 */
@Singleton
class ListAccountOnboardingRulesTool(
    @Inject private val onboardingService: AccountOnboardingService
) : McpTool {

    private val log = LoggerFactory.getLogger(ListAccountOnboardingRulesTool::class.java)

    override val name = "list_account_onboarding_rules"
    override val description =
        "List AWS account onboarding questions and the rules mapping answer combinations to use cases " +
            "(ADMIN or SECCHAMPION, requires User Delegation)"
    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "activeOnly" to mapOf(
                "type" to "boolean",
                "description" to "Only rules that would currently fire. Default true.",
                "default" to true
            )
        ),
        "required" to emptyList<String>()
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyRole(
            context, "ADMIN", "SECCHAMPION",
            code = "FORBIDDEN",
            message = "ADMIN or SECCHAMPION role required to read account onboarding rules"
        )?.let { return it }

        return try {
            val matrix = onboardingService.describeRules()
            log.debug("MCP list_account_onboarding_rules: {} rule(s)", matrix.activeRuleCount)

            McpToolResult.success(
                mapOf(
                    "questionCount" to matrix.questionCount,
                    "choiceCount" to matrix.choiceCount,
                    "activeRuleCount" to matrix.activeRuleCount,
                    // Called out explicitly: without a default rule, an owner whose answers match
                    // nothing gets told to wait for a human. That is a configuration fact worth
                    // surfacing at the top rather than leaving to be inferred from the list.
                    "hasDefaultRule" to matrix.hasDefaultRule,
                    "reachableUseCases" to matrix.reachableUseCases,
                    "reachableRequirementCount" to matrix.reachableRequirementCount,
                    "releaseVersion" to matrix.releaseVersion,
                    "rules" to matrix.rules.map { rule ->
                        mapOf(
                            "id" to rule.id,
                            "name" to rule.name,
                            "description" to rule.description,
                            "isDefault" to rule.isDefault,
                            "active" to rule.active,
                            "combination" to rule.combination,
                            "useCases" to rule.useCases
                        )
                    }
                )
            )
        } catch (e: Exception) {
            log.error("MCP list_account_onboarding_rules failed", e)
            McpToolResult.error("EXECUTION_ERROR", "Failed to list onboarding rules: ${e.message}")
        }
    }
}
