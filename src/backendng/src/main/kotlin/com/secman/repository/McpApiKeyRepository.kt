package com.secman.repository

import com.secman.domain.McpApiKey
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.time.LocalDateTime
import java.util.*

/**
 * Repository interface for McpApiKey entity operations.
 *
 * Provides data access methods for MCP API key management including
 * authentication queries, expiration handling, and usage tracking.
 */
@Repository
interface McpApiKeyRepository : JpaRepository<McpApiKey, Long> {

    // ===== AUTHENTICATION QUERIES =====

    /**
     * Find an active API key by its key ID.
     * Used for authentication during MCP requests.
     */
    @Query("SELECT ak FROM McpApiKey ak WHERE ak.keyId = :keyId AND ak.isActive = true")
    fun findByKeyIdAndActive(keyId: String): Optional<McpApiKey>

    /**
     * Find an API key by its hashed value.
     * Used for secure key validation after initial lookup.
     */
    @Query("SELECT ak FROM McpApiKey ak WHERE ak.keyHash = :keyHash AND ak.isActive = true")
    fun findByKeyHashAndActive(keyHash: String): Optional<McpApiKey>

    /**
     * Validate an API key by ID and check if it's not expired.
     * Used for comprehensive key validation during authentication.
     */
    @Query("""
        SELECT ak FROM McpApiKey ak
        WHERE ak.keyId = :keyId
        AND ak.isActive = true
        AND (ak.expiresAt IS NULL OR ak.expiresAt > :now)
    """)
    fun findValidKeyById(keyId: String, now: LocalDateTime): Optional<McpApiKey>

    // ===== USER MANAGEMENT QUERIES =====

    /**
     * Find all API keys belonging to a specific user.
     * Used for user API key management interface.
     */
    @Query("SELECT ak FROM McpApiKey ak WHERE ak.userId = :userId ORDER BY ak.createdAt DESC")
    fun findByUserId(userId: Long): List<McpApiKey>

    /**
     * Find active API keys for a specific user.
     * Used for displaying user's currently usable keys.
     */
    @Query("SELECT ak FROM McpApiKey ak WHERE ak.userId = :userId AND ak.isActive = true ORDER BY ak.createdAt DESC")
    fun findActiveByUserId(userId: Long): List<McpApiKey>

    /**
     * Count active API keys for a user.
     * Used for enforcing per-user API key limits.
     */
    @Query("SELECT COUNT(ak) FROM McpApiKey ak WHERE ak.userId = :userId AND ak.isActive = true")
    fun countActiveByUserId(userId: Long): Long

    /**
     * Check if a user has an API key with the given name.
     * Used to enforce unique key names per user.
     */
    @Query("SELECT COUNT(ak) > 0 FROM McpApiKey ak WHERE ak.userId = :userId AND ak.name = :name")
    fun existsByUserIdAndName(userId: Long, name: String): Boolean

    // ===== EXPIRATION AND CLEANUP QUERIES =====

    /**
     * Find all expired API keys.
     * Used for automated cleanup of expired keys.
     */
    @Query("SELECT ak FROM McpApiKey ak WHERE ak.expiresAt IS NOT NULL AND ak.expiresAt <= :now")
    fun findExpired(now: LocalDateTime): List<McpApiKey>

    /**
     * Find API keys expiring within a specific time window.
     * Used for sending expiration warnings to users.
     */
    @Query("""
        SELECT ak FROM McpApiKey ak
        WHERE ak.expiresAt IS NOT NULL
        AND ak.expiresAt > :now
        AND ak.expiresAt <= :warningTime
        AND ak.isActive = true
    """)
    fun findExpiringWithin(now: LocalDateTime, warningTime: LocalDateTime): List<McpApiKey>

    /**
     * Deactivate expired API keys in bulk.
     * Used for automated maintenance operations.
     */
    @Query("UPDATE McpApiKey ak SET ak.isActive = false WHERE ak.expiresAt IS NOT NULL AND ak.expiresAt <= :now")
    fun deactivateExpired(now: LocalDateTime): Int

    // ===== USAGE TRACKING QUERIES =====

    /**
     * Update the last used timestamp for an API key.
     * Called after successful authentication/usage.
     */
    @Query("UPDATE McpApiKey ak SET ak.lastUsedAt = :lastUsedAt WHERE ak.id = :id")
    fun updateLastUsedAt(id: Long, lastUsedAt: LocalDateTime): Int

    /**
     * Find API keys that haven't been used for a specified period.
     * Used for identifying unused keys for cleanup.
     */
    @Query("""
        SELECT ak FROM McpApiKey ak
        WHERE ak.lastUsedAt IS NULL
        OR ak.lastUsedAt < :cutoffTime
        ORDER BY ak.lastUsedAt ASC NULLS FIRST
    """)
    fun findUnusedSince(cutoffTime: LocalDateTime): List<McpApiKey>

    /**
     * Find API keys used within a specific time range.
     * Used for activity reporting and analytics.
     */
    @Query("""
        SELECT ak FROM McpApiKey ak
        WHERE ak.lastUsedAt IS NOT NULL
        AND ak.lastUsedAt BETWEEN :startTime AND :endTime
        ORDER BY ak.lastUsedAt DESC
    """)
    fun findUsedBetween(startTime: LocalDateTime, endTime: LocalDateTime): List<McpApiKey>

    // ===== SECURITY AND MONITORING QUERIES =====

    /**
     * Find API keys with specific permissions.
     * Used for security auditing and permission analysis.
     */
    @Query("SELECT ak FROM McpApiKey ak WHERE ak.permissions LIKE :permissionPattern")
    fun findByPermissionPattern(permissionPattern: String): List<McpApiKey>

    /**
     * Find API keys that require admin privileges.
     * Used for security monitoring of elevated access keys.
     */
    @Query("""
        SELECT ak FROM McpApiKey ak
        WHERE ak.permissions LIKE '%ADMIN%'
        OR ak.permissions LIKE '%SYSTEM%'
        ORDER BY ak.createdAt DESC
    """)
    fun findAdminKeys(): List<McpApiKey>

    /**
     * Find recently created API keys.
     * Used for monitoring new key creation activity.
     */
    @Query("SELECT ak FROM McpApiKey ak WHERE ak.createdAt >= :since ORDER BY ak.createdAt DESC")
    fun findCreatedSince(since: LocalDateTime): List<McpApiKey>

    // ===== STATISTICS AND REPORTING QUERIES =====

    /**
     * Get usage statistics for API keys by user.
     * Returns user ID and count of active keys.
     */
    @Query("""
        SELECT ak.userId, COUNT(ak)
        FROM McpApiKey ak
        WHERE ak.isActive = true
        GROUP BY ak.userId
        ORDER BY COUNT(ak) DESC
    """)
    fun getUsageStatisticsByUser(): List<Array<Any>>

    /**
     * Get API key creation statistics by time period.
     * Returns creation date and count for trend analysis.
     */
    @Query("""
        SELECT DATE(ak.createdAt) as creationDate, COUNT(ak)
        FROM McpApiKey ak
        WHERE ak.createdAt >= :since
        GROUP BY DATE(ak.createdAt)
        ORDER BY creationDate DESC
    """)
    fun getCreationStatistics(since: LocalDateTime): List<Array<Any>>

    /**
     * Get permission distribution statistics.
     * Returns permission type and count for analysis.
     */
    @Query("""
        SELECT ak.permissions, COUNT(ak)
        FROM McpApiKey ak
        WHERE ak.isActive = true
        GROUP BY ak.permissions
        ORDER BY COUNT(ak) DESC
    """)
    fun getPermissionStatistics(): List<Array<Any>>

    // ===== BATCH OPERATIONS =====

    /**
     * Deactivate all API keys for a specific user.
     * Used when a user account is disabled or suspended.
     */
    @Query("UPDATE McpApiKey ak SET ak.isActive = false WHERE ak.userId = :userId")
    fun deactivateAllForUser(userId: Long): Int

    /**
     * Delete inactive API keys older than specified date.
     * Used for database cleanup of old, unused keys.
     */
    @Query("DELETE FROM McpApiKey ak WHERE ak.isActive = false AND ak.createdAt < :cutoffDate")
    fun deleteInactiveOlderThan(cutoffDate: LocalDateTime): Int

    // ===== CUSTOM FINDER METHODS =====

    /**
     * Find API keys by name pattern (case-insensitive).
     * Used for administrative search functionality.
     */
    fun findByNameContainingIgnoreCase(namePattern: String): List<McpApiKey>

    /**
     * Find API keys created between specific dates.
     * Used for audit and compliance reporting.
     */
    fun findByCreatedAtBetween(startDate: LocalDateTime, endDate: LocalDateTime): List<McpApiKey>

    /**
     * Check if any API key exists with the given key ID.
     * Used for ensuring global key ID uniqueness.
     */
    fun existsByKeyId(keyId: String): Boolean

    // ===== VALIDATION METHODS =====

    /**
     * Count API keys with a specific key ID (should be 0 or 1).
     * Used for validation during key creation.
     */
    fun countByKeyId(keyId: String): Long

    /**
     * Find API keys that would conflict with a new key name for a user.
     * Used for preventing duplicate key names per user.
     */
    @Query("SELECT ak FROM McpApiKey ak WHERE ak.userId = :userId AND ak.name = :name AND ak.id != :excludeId")
    fun findConflictingName(userId: Long, name: String, excludeId: Long): List<McpApiKey>
}