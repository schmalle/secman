package com.secman.repository

import com.secman.domain.UserMapping
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.*

/**
 * Repository for UserMapping entity
 * 
 * Feature: 013-user-mapping-upload
 * 
 * Provides CRUD operations and query methods for user-to-AWS-account-to-domain mappings.
 * Used by UserMappingImportService for bulk imports and by future RBAC features
 * for access control lookups.
 * 
 * Related to: Feature 013 (User Mapping Upload)
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
     * Use case: Skip duplicate mappings during Excel import
     *
     * @param email User's email address (required)
     * @param awsAccountId AWS account identifier (nullable)
     * @param domain Organizational domain name (nullable)
     * @return true if mapping exists, false otherwise
     */
    fun existsByEmailAndAwsAccountIdAndDomain(
        email: String,
        awsAccountId: String?,
        domain: String?
    ): Boolean

    /**
     * Find a specific mapping by composite key
     *
     * Use case: Retrieve mapping for update or verification
     *
     * @param email User's email address (required)
     * @param awsAccountId AWS account identifier (nullable)
     * @param domain Organizational domain name (nullable)
     * @return Optional containing the mapping if found
     */
    fun findByEmailAndAwsAccountIdAndDomain(
        email: String,
        awsAccountId: String?,
        domain: String?
    ): Optional<UserMapping>

    /**
     * Count total mappings for a user
     * 
     * Use case: Display user's total access scope
     * 
     * @param email User's email address
     * @return Number of mappings for the user
     */
    fun countByEmail(email: String): Long

    /**
     * Count total mappings for an AWS account
     * 
     * Use case: Display how many users have access to an account
     * 
     * @param awsAccountId AWS account identifier
     * @return Number of mappings for the account
     */
    fun countByAwsAccountId(awsAccountId: String): Long

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

    // IP Address Mapping - Feature 020

    /**
     * Find all IP address mappings for a specific email
     *
     * Use case: Get all IP ranges a user has access to
     *
     * Related to: Feature 020 (IP Address Mapping)
     *
     * @param email User's email address
     * @return List of mappings with IP addresses (excluding null IP addresses)
     */
    @Query("SELECT m FROM UserMapping m WHERE m.email = :email AND m.ipAddress IS NOT NULL")
    fun findIpMappingsByEmail(email: String): List<UserMapping>

    /**
     * Check if a specific IP mapping exists (duplicate detection)
     *
     * Use case: Skip duplicate IP mappings during CSV/Excel import
     *
     * Related to: Feature 020 (IP Address Mapping)
     *
     * @param email User's email address (required)
     * @param ipAddress IP address string (nullable)
     * @param domain Organizational domain name (nullable)
     * @return true if mapping exists, false otherwise
     */
    fun existsByEmailAndIpAddressAndDomain(
        email: String,
        ipAddress: String?,
        domain: String?
    ): Boolean

    /**
     * Find a specific IP mapping by composite key
     *
     * Use case: Retrieve IP mapping for update or verification
     *
     * Related to: Feature 020 (IP Address Mapping)
     *
     * @param email User's email address (required)
     * @param ipAddress IP address string (nullable)
     * @param domain Organizational domain name (nullable)
     * @return Optional containing the mapping if found
     */
    fun findByEmailAndIpAddressAndDomain(
        email: String,
        ipAddress: String?,
        domain: String?
    ): Optional<UserMapping>

    /**
     * Count total IP mappings for a user
     *
     * Use case: Display user's IP-based access scope
     *
     * Related to: Feature 020 (IP Address Mapping)
     *
     * @param email User's email address
     * @return Number of IP mappings for the user
     */
    @Query("SELECT COUNT(m) FROM UserMapping m WHERE m.email = :email AND m.ipAddress IS NOT NULL")
    fun countIpMappingsByEmail(email: String): Long

    // Future User Mapping - Feature 042

    /**
     * Find mapping by email (case-insensitive)
     *
     * Use case: Lookup future user mapping during user creation for automatic application
     *
     * Related to: Feature 042 (Future User Mappings)
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
     * Related to: Feature 042 (Future User Mappings)
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
     * Related to: Feature 042 (Future User Mappings)
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
     * Related to: Feature 042 (Future User Mappings)
     *
     * @return Number of current mappings (appliedAt IS NULL)
     */
    fun countByAppliedAtIsNull(): Long

    /**
     * Count applied historical mappings
     *
     * Use case: Display total count for "Applied History" tab pagination
     *
     * Related to: Feature 042 (Future User Mappings)
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
     * Related to: Feature 049 (CLI User Mapping Management)
     *
     * @param email User's email address (case-insensitive)
     * @param status Mapping status (PENDING or ACTIVE)
     * @return List of mappings matching email and status
     */
    fun findByEmailAndStatus(email: String, status: com.secman.domain.MappingStatus): List<UserMapping>
}
