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
     * @param email User's email address
     * @param awsAccountId AWS account identifier
     * @param domain Organizational domain name
     * @return true if mapping exists, false otherwise
     */
    fun existsByEmailAndAwsAccountIdAndDomain(
        email: String,
        awsAccountId: String,
        domain: String
    ): Boolean

    /**
     * Find a specific mapping by composite key
     * 
     * Use case: Retrieve mapping for update or verification
     * 
     * @param email User's email address
     * @param awsAccountId AWS account identifier
     * @param domain Organizational domain name
     * @return Optional containing the mapping if found
     */
    fun findByEmailAndAwsAccountIdAndDomain(
        email: String,
        awsAccountId: String,
        domain: String
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
     * @return List of distinct AWS account IDs
     */
    @Query("SELECT DISTINCT m.awsAccountId FROM UserMapping m WHERE m.email = :email")
    fun findDistinctAwsAccountIdByEmail(email: String): List<String>

    /**
     * Find distinct domains for a user
     * 
     * Use case: Get list of domains a user has access to
     * 
     * @param email User's email address
     * @return List of distinct domains
     */
    @Query("SELECT DISTINCT m.domain FROM UserMapping m WHERE m.email = :email")
    fun findDistinctDomainByEmail(email: String): List<String>
}
