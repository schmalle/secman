package com.secman.service

import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.AssetRepository
import com.secman.repository.RequirementRepository
import com.secman.repository.RiskAssessmentRepository
import com.secman.repository.UseCaseRepository
import com.secman.repository.UserRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.repository.projection.SeverityDistributionRow
import io.micronaut.data.model.Pageable
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Bounded aggregate views intended for automation and operational reporting. */
@Singleton
open class McpStatisticsService(
    private val assetFilter: AssetFilterService,
    private val assetRepository: AssetRepository,
    private val vulnerabilityRepository: VulnerabilityRepository,
    private val userRepository: UserRepository,
    private val requirementRepository: RequirementRepository,
    private val useCaseRepository: UseCaseRepository,
    private val riskAssessmentRepository: RiskAssessmentRepository
) {
    private val log = LoggerFactory.getLogger(McpStatisticsService::class.java)

    @Transactional(readOnly = true)
    open fun global(context: McpExecutionContext): Map<String, Any> {
        val activeSince = Instant.now().minus(7, ChronoUnit.DAYS)
        val result = mapOf(
            "generatedAt" to Instant.now().toString(),
            "assets" to assetRepository.count(),
            "vulnerabilities" to vulnerabilityRepository.count(),
            "users" to mapOf(
                "total" to userRepository.count(),
                "loggedInLast7Days" to userRepository.countByLastLoginGreaterThanEqual(activeSince),
                "neverLoggedIn" to userRepository.countByLastLoginIsNull()
            ),
            "requirements" to requirementRepository.count(),
            "useCases" to useCaseRepository.count(),
            "riskAssessments" to mapOf(
                "total" to riskAssessmentRepository.count(),
                "started" to riskAssessmentRepository.countByStatus("STARTED"),
                "completed" to riskAssessmentRepository.countByStatus("COMPLETED")
            )
        )
        log.info("MCP actor {} read global SecMan statistics: outcome=success", context.delegatedUserId)
        return result
    }

    @Transactional(readOnly = true)
    open fun securityPosture(context: McpExecutionContext): Map<String, Any> {
        val universal = hasUniversalAssetAccess(context)
        val assetIds = context.accessibleAssetIds.orEmpty()
        val rows = when {
            universal -> vulnerabilityRepository.findSeverityDistributionForAll()
            assetIds.isEmpty() -> emptyList()
            else -> vulnerabilityRepository.findSeverityDistributionForAssets(assetIds)
        }
        val result = mapOf(
            "generatedAt" to Instant.now().toString(),
            "scope" to if (universal) "ALL_ASSETS" else "DELEGATED_USER_ASSETS",
            "assetCount" to if (universal) assetRepository.count() else assetIds.size.toLong(),
            "vulnerabilities" to severitySummary(rows)
        )
        log.info(
            "MCP actor {} read scoped security statistics: scope={} outcome=success",
            context.delegatedUserId,
            result["scope"]
        )
        return result
    }

    @Transactional(readOnly = true)
    open fun riskAssessments(context: McpExecutionContext, useCaseName: String?): Map<String, Any> {
        val viewerId = context.delegatedUserId ?: throw SecurityException("Delegation is required")
        val useCase = useCaseName?.trim()?.takeIf(String::isNotBlank)
        require(useCase == null || useCase.length <= 255) { "useCaseName must not exceed 255 characters" }
        val privileged = context.isAdmin || context.delegatedUserRoles?.contains("SECCHAMPION") == true
        val total = countAssessments(context, viewerId, privileged, useCase, null)
        val started = countAssessments(context, viewerId, privileged, useCase, "STARTED")
        val completed = countAssessments(context, viewerId, privileged, useCase, "COMPLETED")
        val result = mapOf(
            "generatedAt" to Instant.now().toString(),
            "useCaseName" to (useCase ?: "ALL"),
            "total" to total,
            "byStatus" to mapOf(
                "STARTED" to started,
                "COMPLETED" to completed,
                "OTHER" to (total - started - completed).coerceAtLeast(0)
            )
        )
        log.info(
            "MCP actor {} read risk assessment statistics: useCaseFilter={} outcome=success",
            viewerId,
            useCase != null
        )
        return result
    }

    private fun countAssessments(
        context: McpExecutionContext,
        viewerId: Long,
        privileged: Boolean,
        useCaseName: String?,
        status: String?
    ): Long = riskAssessmentRepository.findForMcp(
        status,
        useCaseName,
        viewerId,
        privileged,
        context.accessibleAssetIds.orEmpty().ifEmpty { setOf(-1L) },
        assetFilter.getAccessibleAwsAccountIds(io.micronaut.security.authentication.Authentication.build(
            context.delegatedUsername.orEmpty(), context.delegatedUserRoles.orEmpty().toList(),
            mapOf("userId" to viewerId, "email" to context.delegatedUserEmail.orEmpty()))).ifEmpty { setOf("") },
        Pageable.from(0, 1)
    ).totalSize

    private fun hasUniversalAssetAccess(context: McpExecutionContext): Boolean =
        context.isAdmin || context.delegatedUserRoles?.contains("SECCHAMPION") == true

    private fun severitySummary(rows: List<SeverityDistributionRow>): Map<String, Any> {
        val counts = mutableMapOf(
            "CRITICAL" to 0L,
            "HIGH" to 0L,
            "MEDIUM" to 0L,
            "LOW" to 0L,
            "UNKNOWN" to 0L,
            "OTHER" to 0L
        )
        rows.forEach { row ->
            val severity = row.severity?.trim()?.uppercase()
            val bucket = if (severity in counts && severity != "OTHER") severity!! else "OTHER"
            counts[bucket] = counts.getValue(bucket) + (row.count?.toLong() ?: 0L)
        }
        return mapOf("total" to counts.values.sum(), "bySeverity" to counts)
    }
}
