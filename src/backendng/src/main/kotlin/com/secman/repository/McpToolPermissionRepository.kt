package com.secman.repository

import com.secman.domain.McpToolPermission
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.time.LocalDateTime
import java.util.*

/**
 * Repository interface for McpToolPermission entity operations.
 *
 * Provides data access methods for fine-grained MCP tool permission management
 * including authorization checks, permission auditing, and access control enforcement.
 */
@Repository
interface McpToolPermissionRepository : JpaRepository<McpToolPermission, Long> {

    // ===== PERMISSION VALIDATION QUERIES =====

    /**
     * Find active tool permission for a specific API key and tool.
     * Used for real-time authorization checks during tool calls.
     */
    @Query("""
        SELECT tp FROM McpToolPermission tp
        WHERE tp.apiKeyId = :apiKeyId
        AND tp.toolName = :toolName
        AND tp.isActive = true
    """)
    fun findActivePermission(apiKeyId: Long, toolName: String): Optional<McpToolPermission>

    /**
     * Check if an API key has permission for a specific tool.
     * Used for quick authorization checks without loading full entity.
     */
    @Query("""
        SELECT COUNT(tp) > 0 FROM McpToolPermission tp
        WHERE tp.apiKeyId = :apiKeyId
        AND tp.toolName = :toolName
        AND tp.isActive = true
    """)
    fun hasPermission(apiKeyId: Long, toolName: String): Boolean

    /**
     * Find all active tool permissions for an API key.
     * Used for determining complete tool access scope.
     */
    @Query("""
        SELECT tp FROM McpToolPermission tp
        WHERE tp.apiKeyId = :apiKeyId
        AND tp.isActive = true
        ORDER BY tp.toolName ASC
    """)
    fun findActiveByApiKey(apiKeyId: Long): List<McpToolPermission>

    /**
     * Get tool names that an API key has access to.
     * Used for generating tool capability lists.
     */
    @Query("""
        SELECT tp.toolName FROM McpToolPermission tp
        WHERE tp.apiKeyId = :apiKeyId
        AND tp.isActive = true
        ORDER BY tp.toolName ASC
    """)
    fun findAuthorizedToolNames(apiKeyId: Long): List<String>

    // ===== RATE LIMITING QUERIES =====

    /**
     * Find tool permissions with rate limiting enabled.
     * Used for implementing per-tool rate limiting.
     */
    @Query("""
        SELECT tp FROM McpToolPermission tp
        WHERE tp.apiKeyId = :apiKeyId
        AND tp.toolName = :toolName
        AND tp.maxCallsPerHour IS NOT NULL
        AND tp.isActive = true
    """)
    fun findWithRateLimit(apiKeyId: Long, toolName: String): Optional<McpToolPermission>

    /**
     * Find all rate-limited tools for an API key.
     * Used for implementing comprehensive rate limiting.
     */
    @Query("""
        SELECT tp FROM McpToolPermission tp
        WHERE tp.apiKeyId = :apiKeyId
        AND tp.maxCallsPerHour IS NOT NULL
        AND tp.isActive = true
        ORDER BY tp.toolName ASC
    """)
    fun findRateLimitedByApiKey(apiKeyId: Long): List<McpToolPermission>

    // ===== TOOL MANAGEMENT QUERIES =====

    /**
     * Find all permissions for a specific tool.
     * Used for tool-specific access analysis.
     */
    @Query("""
        SELECT tp FROM McpToolPermission tp
        WHERE tp.toolName = :toolName
        ORDER BY tp.priority DESC, tp.createdAt DESC
    """)
    fun findByToolName(toolName: String): List<McpToolPermission>

    /**
     * Find active permissions for a specific tool.
     * Used for analyzing current tool access.
     */
    @Query("""
        SELECT tp FROM McpToolPermission tp
        WHERE tp.toolName = :toolName
        AND tp.isActive = true
        ORDER BY tp.priority DESC, tp.createdAt DESC
    """)
    fun findActiveByToolName(toolName: String): List<McpToolPermission>

    /**
     * Count API keys with access to a specific tool.
     * Used for tool usage statistics.
     */
    @Query("""
        SELECT COUNT(DISTINCT tp.apiKeyId) FROM McpToolPermission tp
        WHERE tp.toolName = :toolName
        AND tp.isActive = true
    """)
    fun countApiKeysWithAccess(toolName: String): Long

    // ===== PERMISSION MANAGEMENT QUERIES =====

    /**
     * Find duplicate permissions (same API key and tool).
     * Used for data integrity checks and cleanup.
     */
    @Query("""
        SELECT tp FROM McpToolPermission tp
        WHERE tp.apiKeyId = :apiKeyId
        AND tp.toolName = :toolName
        ORDER BY tp.priority DESC, tp.createdAt DESC
    """)
    fun findDuplicates(apiKeyId: Long, toolName: String): List<McpToolPermission>

    /**
     * Find permissions created by a specific user.
     * Used for tracking permission creation and auditing.
     */
    @Query("""
        SELECT tp FROM McpToolPermission tp
        WHERE tp.createdBy = :createdBy
        ORDER BY tp.createdAt DESC
    """)
    fun findByCreatedBy(createdBy: Long): List<McpToolPermission>

    /**
     * Find recently modified permissions.
     * Used for change tracking and auditing.
     */
    @Query("""
        SELECT tp FROM McpToolPermission tp
        WHERE tp.updatedAt >= :since
        ORDER BY tp.updatedAt DESC
    """)
    fun findRecentlyModified(since: LocalDateTime): List<McpToolPermission>

    // ===== PARAMETER RESTRICTION QUERIES =====

    /**
     * Find permissions with parameter restrictions.
     * Used for enforcing parameter-level security.
     */
    @Query("""
        SELECT tp FROM McpToolPermission tp
        WHERE tp.parameterRestrictions IS NOT NULL
        AND tp.parameterRestrictions != ''
        AND tp.isActive = true
        ORDER BY tp.toolName ASC
    """)
    fun findWithParameterRestrictions(): List<McpToolPermission>

    /**
     * Find permissions with parameter restrictions for specific API key.
     * Used for parameter validation during tool calls.
     */
    @Query("""
        SELECT tp FROM McpToolPermission tp
        WHERE tp.apiKeyId = :apiKeyId
        AND tp.parameterRestrictions IS NOT NULL
        AND tp.parameterRestrictions != ''
        AND tp.isActive = true
        ORDER BY tp.toolName ASC
    """)
    fun findWithParameterRestrictionsByApiKey(apiKeyId: Long): List<McpToolPermission>

    // ===== CACHING CONTROL QUERIES =====

    /**
     * Find permissions that allow caching for an API key and tool.
     * Used for determining if tool results can be cached.
     */
    @Query("""
        SELECT tp FROM McpToolPermission tp
        WHERE tp.apiKeyId = :apiKeyId
        AND tp.toolName = :toolName
        AND tp.allowCaching = true
        AND tp.isActive = true
    """)
    fun findCachingAllowedPermission(apiKeyId: Long, toolName: String): Optional<McpToolPermission>

    /**
     * Find all caching-disabled tools for an API key.
     * Used for cache policy enforcement.
     */
    @Query("""
        SELECT tp.toolName FROM McpToolPermission tp
        WHERE tp.apiKeyId = :apiKeyId
        AND tp.allowCaching = false
        AND tp.isActive = true
    """)
    fun findCachingDisabledTools(apiKeyId: Long): List<String>

    // ===== STATISTICS AND MONITORING QUERIES =====

    /**
     * Get tool permission statistics by tool.
     * Returns tool name, permission count, active count.
     */
    @Query("""
        SELECT
            tp.toolName,
            COUNT(tp) as totalPermissions,
            COUNT(CASE WHEN tp.isActive = true THEN 1 END) as activePermissions
        FROM McpToolPermission tp
        GROUP BY tp.toolName
        ORDER BY activePermissions DESC
    """)
    fun getToolPermissionStatistics(): List<Array<Any>>

    /**
     * Get API key permission statistics.
     * Returns API key ID, tool count, restricted tool count.
     */
    @Query("""
        SELECT
            tp.apiKeyId,
            COUNT(tp) as totalTools,
            COUNT(CASE WHEN tp.parameterRestrictions IS NOT NULL THEN 1 END) as restrictedTools,
            COUNT(CASE WHEN tp.maxCallsPerHour IS NOT NULL THEN 1 END) as rateLimitedTools
        FROM McpToolPermission tp
        WHERE tp.isActive = true
        GROUP BY tp.apiKeyId
        ORDER BY totalTools DESC
    """)
    fun getApiKeyPermissionStatistics(): List<Array<Any>>

    /**
     * Get creation statistics by time period.
     * Returns creation date and permission count.
     */
    @Query("""
        SELECT
            DATE(tp.createdAt) as creationDate,
            COUNT(tp) as permissionCount
        FROM McpToolPermission tp
        WHERE tp.createdAt >= :since
        GROUP BY DATE(tp.createdAt)
        ORDER BY creationDate DESC
    """)
    fun getCreationStatistics(since: LocalDateTime): List<Array<Any>>

    // ===== PRIORITY AND CONFLICT RESOLUTION =====

    /**
     * Find permissions with highest priority for API key and tool.
     * Used for conflict resolution when multiple permissions exist.
     */
    @Query("""
        SELECT tp FROM McpToolPermission tp
        WHERE tp.apiKeyId = :apiKeyId
        AND tp.toolName = :toolName
        AND tp.isActive = true
        ORDER BY tp.priority DESC, tp.createdAt DESC
        LIMIT 1
    """)
    fun findHighestPriority(apiKeyId: Long, toolName: String): Optional<McpToolPermission>

    /**
     * Find permissions with conflicting priorities.
     * Used for identifying configuration issues.
     */
    @Query("""
        SELECT tp FROM McpToolPermission tp
        WHERE tp.apiKeyId = :apiKeyId
        AND tp.toolName = :toolName
        AND tp.isActive = true
        AND tp.priority = (
            SELECT MAX(p.priority)
            FROM McpToolPermission p
            WHERE p.apiKeyId = :apiKeyId
            AND p.toolName = :toolName
            AND p.isActive = true
        )
    """)
    fun findConflictingPermissions(apiKeyId: Long, toolName: String): List<McpToolPermission>

    // ===== BULK OPERATIONS =====

    /**
     * Deactivate all permissions for a specific API key.
     * Used when an API key is revoked or suspended.
     */
    @Query("UPDATE McpToolPermission tp SET tp.isActive = false WHERE tp.apiKeyId = :apiKeyId")
    fun deactivateAllForApiKey(apiKeyId: Long): Int

    /**
     * Deactivate all permissions for a specific tool.
     * Used when a tool is deprecated or removed.
     */
    @Query("UPDATE McpToolPermission tp SET tp.isActive = false WHERE tp.toolName = :toolName")
    fun deactivateAllForTool(toolName: String): Int

    /**
     * Delete inactive permissions older than specified date.
     * Used for cleanup and storage management.
     */
    @Query("DELETE FROM McpToolPermission tp WHERE tp.isActive = false AND tp.createdAt < :cutoffDate")
    fun deleteInactiveOlderThan(cutoffDate: LocalDateTime): Int

    // ===== VALIDATION QUERIES =====

    /**
     * Check if a specific permission combination exists.
     * Used for preventing duplicate permissions.
     */
    fun existsByApiKeyIdAndToolName(apiKeyId: Long, toolName: String): Boolean

    /**
     * Count permissions for an API key.
     * Used for enforcing permission limits.
     */
    fun countByApiKeyIdAndIsActive(apiKeyId: Long, isActive: Boolean): Long

    /**
     * Find permissions that would conflict with a new permission.
     * Used for validation during permission creation.
     */
    @Query("""
        SELECT tp FROM McpToolPermission tp
        WHERE tp.apiKeyId = :apiKeyId
        AND tp.toolName = :toolName
        AND tp.id != :excludeId
    """)
    fun findConflictingPermissions(apiKeyId: Long, toolName: String, excludeId: Long): List<McpToolPermission>

    // ===== SECURITY MONITORING QUERIES =====

    /**
     * Find permissions with unusually high rate limits.
     * Used for security monitoring and anomaly detection.
     */
    @Query("""
        SELECT tp FROM McpToolPermission tp
        WHERE tp.maxCallsPerHour > :threshold
        AND tp.isActive = true
        ORDER BY tp.maxCallsPerHour DESC
    """)
    fun findHighRateLimitPermissions(threshold: Int): List<McpToolPermission>

    /**
     * Find admin or sensitive tool permissions.
     * Used for privileged access monitoring.
     */
    @Query("""
        SELECT tp FROM McpToolPermission tp
        WHERE tp.toolName IN ('get_system_info', 'get_user_activity', 'delete_requirement', 'delete_assessment')
        AND tp.isActive = true
        ORDER BY tp.createdAt DESC
    """)
    fun findPrivilegedPermissions(): List<McpToolPermission>

    /**
     * Find permissions created recently.
     * Used for monitoring new permission grants.
     */
    @Query("""
        SELECT tp FROM McpToolPermission tp
        WHERE tp.createdAt >= :since
        ORDER BY tp.createdAt DESC
    """)
    fun findCreatedSince(since: LocalDateTime): List<McpToolPermission>

    // ===== CLEANUP AND MAINTENANCE =====

    /**
     * Find orphaned permissions (API key no longer exists).
     * Used for data integrity maintenance.
     */
    @Query("""
        SELECT tp FROM McpToolPermission tp
        WHERE tp.apiKeyId NOT IN (
            SELECT ak.id FROM McpApiKey ak WHERE ak.id = tp.apiKeyId
        )
    """)
    fun findOrphanedPermissions(): List<McpToolPermission>

    /**
     * Find permissions for invalid tool names.
     * Used for configuration validation.
     */
    @Query("""
        SELECT tp FROM McpToolPermission tp
        WHERE tp.toolName NOT IN (
            'get_requirements', 'search_requirements', 'create_requirement',
            'update_requirement', 'delete_requirement', 'get_assessments',
            'search_assessments', 'create_assessment', 'update_assessment',
            'delete_assessment', 'get_tags', 'search_all', 'get_system_info',
            'get_user_activity'
        )
    """)
    fun findInvalidToolPermissions(): List<McpToolPermission>

    // ===== CUSTOM FINDER METHODS =====

    /**
     * Find permissions by notes containing specific text.
     * Used for finding permissions by purpose or description.
     */
    fun findByNotesContainingIgnoreCase(notesPattern: String): List<McpToolPermission>

    /**
     * Find permissions created between specific dates.
     * Used for audit and compliance reporting.
     */
    fun findByCreatedAtBetween(startDate: LocalDateTime, endDate: LocalDateTime): List<McpToolPermission>
}