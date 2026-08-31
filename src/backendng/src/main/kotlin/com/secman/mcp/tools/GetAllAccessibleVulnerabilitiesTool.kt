package com.secman.mcp.tools

import com.secman.domain.Asset
import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.AssetRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.service.VulnerabilityExceptionService
import io.micronaut.data.model.Pageable
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP Tool: get_all_accessible_vulnerabilities
 *
 * Returns every current vulnerability across every asset the caller can access, in a single,
 * unpaginated response. Complements get_vulnerabilities/get_all_vulnerabilities_detail (which
 * require manual pagination) for callers that just want "everything I can see" in one call.
 */
@Singleton
class GetAllAccessibleVulnerabilitiesTool(
    @Inject private val vulnerabilityRepository: VulnerabilityRepository,
    @Inject private val vulnerabilityExceptionService: VulnerabilityExceptionService,
    @Inject private val assetRepository: AssetRepository
) : McpTool {

    override val name = "get_all_accessible_vulnerabilities"

    override val description = """
        Retrieve every current vulnerability across every asset the caller can access, in a
        single unpaginated response.

        Supports filtering by:
        - severity: Severity levels (CRITICAL, HIGH, MEDIUM, LOW)
        - includeExcepted: include vulnerabilities covered by active exceptions (default: false)

        A safety cap (limit, default=5000, max=20000) bounds the response size. The response
        reports total/returned/truncated so callers know whether the cap was hit. When
        truncated is true, total counts the rows matching access control and severity, before
        exception filtering.
    """.trimIndent()

    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "severity" to mapOf(
                "type" to "array",
                "description" to "Filter by CVSS severity levels",
                "items" to mapOf("type" to "string"),
                "enum" to listOf("CRITICAL", "HIGH", "MEDIUM", "LOW")
            ),
            "includeExcepted" to mapOf(
                "type" to "boolean",
                "description" to "Include vulnerabilities that are covered by active exceptions (default: false).",
                "default" to false
            ),
            "limit" to mapOf(
                "type" to "integer",
                "description" to "Maximum number of vulnerabilities to return (default=5000, max=20000)",
                "minimum" to 1,
                "maximum" to 20000,
                "default" to 5000
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        try {
            val severityFilter = (arguments["severity"] as? List<*>)?.mapNotNull { (it as? String)?.uppercase() }
            val includeExcepted = arguments["includeExcepted"] as? Boolean ?: false
            val limit = (arguments["limit"] as? Number)?.toInt() ?: 5000

            if (limit < 1 || limit > 20000) {
                return McpToolResult.error("INVALID_LIMIT", "limit must be between 1 and 20000")
            }
            if (severityFilter != null && severityFilter.any { it !in listOf("CRITICAL", "HIGH", "MEDIUM", "LOW") }) {
                return McpToolResult.error("INVALID_SEVERITY", "severity must be one of: CRITICAL, HIGH, MEDIUM, LOW")
            }

            // Get accessible asset IDs for access control filtering (null = ADMIN/unrestricted)
            val accessibleIds = context.getFilterableAssetIds()

            if (accessibleIds != null && accessibleIds.isEmpty()) {
                return McpToolResult.success(mapOf(
                    "vulnerabilities" to emptyList<Map<String, Any?>>(),
                    "total" to 0,
                    "returned" to 0,
                    "truncated" to false,
                    "exceptedFiltered" to !includeExcepted
                ))
            }

            // Access control, severity and the row cap all have to be applied in SQL. Reading the
            // whole `vulnerability` table via findAll() and filtering in Kotlin loaded ~1.1M
            // entities and exhausted the heap before any of these filters ran.
            val severities = severityFilter?.takeIf { it.isNotEmpty() }
            val pageable = Pageable.from(0, limit)
            val page = when {
                accessibleIds != null && severities != null ->
                    vulnerabilityRepository.findByAssetIdInAndCvssSeverityIn(accessibleIds, severities, pageable)
                accessibleIds != null ->
                    vulnerabilityRepository.findByAssetIdIn(accessibleIds, pageable)
                severities != null ->
                    vulnerabilityRepository.findByCvssSeverityIn(severities, pageable)
                else ->
                    vulnerabilityRepository.findAll(pageable)
            }
            var vulnerabilities = page.content

            // Eagerly hydrate the assets referenced by these vulnerabilities so we never touch
            // a Hibernate proxy outside of an active session (exception matching reads asset
            // fields like osVersion, and the response mapping reads name/type/ip).
            val assetIds = vulnerabilities.mapNotNull { it.asset.id }.toSet()
            val assetsById: Map<Long, Asset> = if (assetIds.isEmpty()) {
                emptyMap()
            } else {
                assetRepository.findByIdIn(assetIds).associateBy { it.id!! }
            }

            if (!includeExcepted) {
                val activeExceptions = vulnerabilityExceptionService.getActiveExceptions()
                vulnerabilities = vulnerabilities.filter { vuln ->
                    val asset = assetsById[vuln.asset.id] ?: return@filter false
                    activeExceptions.none { ex -> ex.matches(vuln, asset) }
                }
            }

            // The cap is enforced by the SQL page above, so `truncated` means "more rows matched
            // than we fetched". When nothing was truncated we know the exact post-exception count;
            // when it was, the best available total is the SQL match count (before exception
            // filtering), since counting the rest would mean reading them.
            val truncated = page.totalSize > page.content.size
            val total = if (truncated) page.totalSize.toInt() else vulnerabilities.size
            val limited = vulnerabilities

            val response = mapOf(
                "vulnerabilities" to limited.map { vuln ->
                    val asset = assetsById[vuln.asset.id]
                    mapOf(
                        "id" to vuln.id,
                        "vulnerabilityId" to vuln.vulnerabilityId,
                        "cvssSeverity" to vuln.cvssSeverity,
                        "vulnerableProductVersions" to vuln.vulnerableProductVersions,
                        "daysOpen" to vuln.daysOpen,
                        "scanTimestamp" to vuln.scanTimestamp.toString(),
                        "asset" to mapOf(
                            "id" to asset?.id,
                            "name" to asset?.name,
                            "type" to asset?.type,
                            "ip" to asset?.ip
                        ),
                        "createdAt" to vuln.createdAt?.toString()
                    )
                },
                "total" to total,
                "returned" to limited.size,
                "truncated" to truncated,
                "exceptedFiltered" to !includeExcepted
            )

            return McpToolResult.success(response)

        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to retrieve vulnerabilities: ${e.message}")
        }
    }
}
