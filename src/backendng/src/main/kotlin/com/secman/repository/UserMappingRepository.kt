package com.secman.repository

import com.secman.domain.UserMapping
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.*

/**
 * Repository for UserMapping entity
 * 
 * Provides CRUD operations and query methods for user-to-AWS-account-to-domain mappings.
 * Used by UserMappingImportService for bulk imports and by future RBAC features
 * for access control lookups.
 */
@Repository
interface UserMappingRepository : JpaRepository<UserMapping, Long> {

    /**
     * Find all mappings for a specific email address
     * 
     * Use case: Get all AWS accounts and domains a user has access to
     * 
     * @param email User's email address (case-insensitive, will be normalized)
     * @return List of mappings for the email
     */
    fun findByEmail(email: String): List<UserMapping>

    /**
     * Find all mappings for a specific AWS account
     *
     * Use case: Get all users with access to an AWS account
     *
     * @param awsAccountId AWS account identifier (12-digit string)
     * @return List of mappings for the AWS account
     */
    fun findByAwsAccountId(awsAccountId: String): List<UserMapping>

    /**
     * Return the subset of [ids] that already appear as awsAccountId on at least
     * one existing mapping. Used by bulk import to detect brand-new (DB-wide)
     * AWS accounts. Callers MUST skip this query when [ids] is empty (an empty
     * IN list is invalid/degenerate).
     */
    @Query("SELECT DISTINCT m.awsAccountId FROM UserMapping m WHERE m.awsAccountId IN :ids")
    fun findExistingAwsAccountIds(ids: Collection<String>): List<String>

    /**
     * Find all mappings for a specific domain
     * 
     * Use case: Get all users within an organizational domain
     * 
     * @param domain Organizational domain name
     * @return List of mappings for the domain
     */
    fun findByDomain(domain: String): List<UserMapping>

    /**
     * Check if a specific mapping exists (duplicate detection)
     *
     * Use case: Skip duplicate mappings during Excel/CSV/JSON/S3 import
     *
     * NULL-safe: derived queries translate `domain = ?` to bind NULL, but in SQL
     * `column = NULL` is UNKNOWN (never true), so the implicit derived query
     * silently misses every row where domain IS NULL. We use explicit JPQL with
     * `(:param IS NULL AND col IS NULL) OR col = :param` so duplicates with
     * NULL columns are detected. Without this, every reimport of an
     * AWS-account-only mapping (domain/ip = NULL) creates a fresh duplicate
     * row, since MariaDB's UNIQUE constraint also treats NULLs as distinct.
     *
     * @param email User's email address (required)
     * @param awsAccountId AWS account identifier (nullable)
     * @param domain Organizational domain name (nullable)
     * @return true if mapping exists, false otherwise
     */
    @Query("""
        SELECT CASE WHEN COUNT(m) > 0 THEN TRUE ELSE FALSE END FROM UserMapping m
        WHERE m.email = :email
          AND ((:awsAccountId IS NULL AND m.awsAccountId IS NULL) OR m.awsAccountId = :awsAccountId)
          AND ((:domain IS NULL AND m.domain IS NULL) OR m.domain = :domain)
          AND m.ipAddress IS NULL
    """)
    fun existsByEmailAndAwsAccountIdAndDomain(
        email: String,
        awsAccountId: String?,
        domain: String?
    ): Boolean

    /**
     * Find a specific mapping by composite key
     *
     * Use case: Retrieve mapping for update or verification.
     * NULL-safe (see [existsByEmailAndAwsAccountIdAndDomain]).
     *
     * @param email User's email address (required)
     * @param awsAccountId AWS account identifier (nullable)
     * @param domain Organizational domain name (nullable)
     * @return Optional containing the mapping if found
     */
    @Query("""
        SELECT m FROM UserMapping m
        WHERE m.email = :email
          AND ((:awsAccountId IS NULL AND m.awsAccountId IS NULL) OR m.awsAccountId = :awsAccountId)
          AND ((:domain IS NULL AND m.domain IS NULL) OR m.domain = :domain)
          AND m.ipAddress IS NULL
    """)
    fun findByEmailAndAwsAccountIdAndDomain(
        email: String,
        awsAccountId: String?,
        domain: String?
    ): Optional<UserMapping>

    /**
     * Find distinct AWS account IDs for a user
     *
     * Use case: Get list of AWS accounts a user can access
     *
     * @param email User's email address
     * @return List of distinct AWS account IDs (excluding null values)
     */
    @Query("SELECT DISTINCT m.awsAccountId FROM UserMapping m WHERE m.email = :email AND m.awsAccountId IS NOT NULL")
    fun findDistinctAwsAccountIdByEmail(email: String): List<String>

    @Query("SELECT COUNT(DISTINCT m.awsAccountId) FROM UserMapping m WHERE m.email = :email AND m.awsAccountId IS NOT NULL")
    fun countDistinctAwsAccountsByEmail(email: String): Long

    /**
     * Find distinct domains for a user
     *
     * Use case: Get list of domains a user has access to
     *
     * @param email User's email address
     * @return List of distinct domains (excluding null values)
     */
    @Query("SELECT DISTINCT m.domain FROM UserMapping m WHERE m.email = :email AND m.domain IS NOT NULL")
    fun findDistinctDomainByEmail(email: String): List<String>

    @Query("SELECT COUNT(DISTINCT m.domain) FROM UserMapping m WHERE m.email = :email AND m.domain IS NOT NULL")
    fun countDistinctDomainsByEmail(email: String): Long

    @Query("SELECT DISTINCT m.domain FROM UserMapping m WHERE m.domain IS NOT NULL AND m.domain != ''")
    fun findDistinctDomains(): List<String>

    // IP Address Mapping - Feature 020

    /**
     * Check if a specific IP mapping exists (duplicate detection).
     * NULL-safe (see [existsByEmailAndAwsAccountIdAndDomain]).
     *
     * @param email User's email address (required)
     * @param ipAddress IP address string (nullable)
     * @param domain Organizational domain name (nullable)
     * @return true if mapping exists, false otherwise
     */
    @Query("""
        SELECT CASE WHEN COUNT(m) > 0 THEN TRUE ELSE FALSE END FROM UserMapping m
        WHERE m.email = :email
          AND ((:ipAddress IS NULL AND m.ipAddress IS NULL) OR m.ipAddress = :ipAddress)
          AND ((:domain IS NULL AND m.domain IS NULL) OR m.domain = :domain)
          AND m.awsAccountId IS NULL
    """)
    fun existsByEmailAndIpAddressAndDomain(
        email: String,
        ipAddress: String?,
        domain: String?
    ): Boolean

    /**
     * Find a specific IP mapping by composite key.
     * NULL-safe (see [findByEmailAndAwsAccountIdAndDomain]).
     *
     * @param email User's email address (required)
     * @param ipAddress IP address string (nullable)
     * @param domain Organizational domain name (nullable)
     * @return Optional containing the mapping if found
     */
    @Query("""
        SELECT m FROM UserMapping m
        WHERE m.email = :email
          AND ((:ipAddress IS NULL AND m.ipAddress IS NULL) OR m.ipAddress = :ipAddress)
          AND ((:domain IS NULL AND m.domain IS NULL) OR m.domain = :domain)
          AND m.awsAccountId IS NULL
    """)
    fun findByEmailAndIpAddressAndDomain(
        email: String,
        ipAddress: String?,
        domain: String?
    ): Optional<UserMapping>

    // Future User Mapping - Feature 042

    /**
     * Find mapping by email (case-insensitive)
     *
     * Use case: Lookup future user mapping during user creation for automatic application
     *
     * @param email User's email address (case-insensitive)
     * @return Optional containing the first matching mapping (if multiple exist, returns first)
     */
    fun findByEmailIgnoreCase(email: String): Optional<UserMapping>

    /**
     * Find all current mappings (future + active, excluding applied history)
     *
     * Use case: Display "Current Mappings" tab in UI (paginated)
     *
     * @param pageable Pagination parameters (page number, size, sort)
     * @return Page of current mappings (appliedAt IS NULL)
     */
    fun findByAppliedAtIsNull(pageable: io.micronaut.data.model.Pageable): io.micronaut.data.model.Page<UserMapping>

    /**
     * Find all applied historical mappings
     *
     * Use case: Display "Applied History" tab in UI (paginated)
     *
     * @param pageable Pagination parameters (page number, size, sort)
     * @return Page of applied historical mappings (appliedAt IS NOT NULL)
     */
    fun findByAppliedAtIsNotNull(pageable: io.micronaut.data.model.Pageable): io.micronaut.data.model.Page<UserMapping>

    /**
     * Count current mappings (future + active)
     *
     * Use case: Display total count for "Current Mappings" tab pagination
     *
     * @return Number of current mappings (appliedAt IS NULL)
     */
    fun countByAppliedAtIsNull(): Long

    /**
     * Count applied historical mappings
     *
     * Use case: Display total count for "Applied History" tab pagination
     *
     * @return Number of applied historical mappings (appliedAt IS NOT NULL)
     */
    fun countByAppliedAtIsNotNull(): Long

    // Feature 049: CLI User Mapping Management

    /**
     * Find all mappings for a specific email and status
     *
     * Use case: Find pending mappings when user is created for auto-application
     *
     * @param email User's email address (case-insensitive)
     * @param status Mapping status (PENDING or ACTIVE)
     * @return List of mappings matching email and status
     */
    fun findByEmailAndStatus(email: String, status: com.secman.domain.MappingStatus): List<UserMapping>

    // Feature 064: MCP and CLI User Mapping Upload

    /**
     * Find all mappings with pagination
     *
     * Use case: List all mappings via MCP tool with pagination
     *
     * @param pageable Pagination parameters (page number, size, sort)
     * @return Page of all user mappings
     */
    override fun findAll(pageable: io.micronaut.data.model.Pageable): io.micronaut.data.model.Page<UserMapping>

    /**
     * Find mappings by email containing (partial match) with pagination
     *
     * Use case: Filter mappings by email via MCP tool with pagination
     *
     * @param email Partial email to search for (case-insensitive)
     * @param pageable Pagination parameters (page number, size, sort)
     * @return Page of mappings matching the email filter
     */
    fun findByEmailContainingIgnoreCase(email: String, pageable: io.micronaut.data.model.Pageable): io.micronaut.data.model.Page<UserMapping>

    /**
     * Find all mappings with a specific status
     *
     * Use case: Filter mappings by ACTIVE or PENDING status via REST API
     *
     * @param status Mapping status (ACTIVE or PENDING)
     * @return List of mappings with the specified status
     */
    fun findByStatus(status: com.secman.domain.MappingStatus): List<UserMapping>

    /**
     * Delete all mappings linked to a given user.
     *
     * Called from UserService.deleteUser to release the user_mapping → users
     * foreign key (FKhmx5yo60mly74d31vvi96egr4) before the parent user row is
     * deleted. Without this, MariaDB rejects the user delete with
     * "1451-23000: Cannot delete or update a parent row".
     */
    fun deleteByUser_Id(userId: Long): Long

    /**
     * Find AWS account mappings created on or after [since].
     *
     * Used by the notify-new-accounts CLI command to identify users who gained
     * access to a new AWS account via a recent import within the look-back window.
     * Status filtering (ACTIVE only) is applied in the service layer.
     */
    @Query("""
        SELECT m FROM UserMapping m
        WHERE m.awsAccountId IS NOT NULL
          AND m.createdAt >= :since
        ORDER BY m.email, m.awsAccountId
    """)
    fun findRecentAwsAccountMappings(since: java.time.Instant): List<UserMapping>

    /**
     * Distinct (awsAccountId, awsAccountName, newest updatedAt) triples for every mapping
     * that carries both an account id and a display name (V260).
     *
     * Feeds the correction path — `WorkgroupAccountLinkService.linkFromStoredMappings` —
     * which links each account to the workgroup named after its display name without the
     * operator re-supplying the source file.
     *
     * Grouped, so the row count is distinct (account, name) combinations rather than
     * mappings: one account owned by 40 people yields one row, and an account renamed
     * between imports yields one row per name with its own newest timestamp, letting the
     * caller pick the current one. Paged at the query rather than sliced in Kotlin
     * (A04: unbounded is a design bug).
     */
    @Query("""
        SELECT m.awsAccountId, m.awsAccountName, MAX(m.updatedAt)
        FROM UserMapping m
        WHERE m.awsAccountId IS NOT NULL
          AND m.awsAccountName IS NOT NULL
        GROUP BY m.awsAccountId, m.awsAccountName
        ORDER BY m.awsAccountId
    """)
    fun findAwsAccountDisplayNames(
        pageable: io.micronaut.data.model.Pageable
    ): List<Array<Any>>

    /**
     * Set the display name on every mapping of one AWS account (V260).
     *
     * One statement per account rather than a read-modify-write per row: a daily import is
     * mostly duplicate rows, and an account owned by 40 people would otherwise cost 40
     * SELECT+UPDATE pairs to write the same value. The name describes the account, so
     * updating every mapping of it — including owners absent from this particular file —
     * is the intended reach, not a side effect.
     *
     * The `<>` guard makes a re-import of an unchanged file a no-op, which keeps
     * `updatedAt` meaningful: [findAwsAccountDisplayNames] uses it to pick the current
     * name for a renamed account. `updatedAt` is set explicitly because a bulk JPQL
     * update does not fire `@PreUpdate`.
     */
    @Query("""
        UPDATE UserMapping m
        SET m.awsAccountName = :displayName, m.updatedAt = :now
        WHERE m.awsAccountId = :awsAccountId
          AND (m.awsAccountName IS NULL OR m.awsAccountName <> :displayName)
    """)
    fun updateAwsAccountName(
        awsAccountId: String,
        displayName: String,
        now: java.time.Instant
    ): Int
}
