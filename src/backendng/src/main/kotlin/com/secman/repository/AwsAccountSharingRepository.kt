package com.secman.repository

import com.secman.domain.AwsAccountSharing
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import java.util.Optional

/**
 * Repository for AwsAccountSharing entity.
 *
 * Feature: AWS Account Sharing
 */
@Repository
interface AwsAccountSharingRepository : JpaRepository<AwsAccountSharing, Long> {

    fun findByTargetUserId(targetUserId: Long): List<AwsAccountSharing>

    fun countByTargetUserId(targetUserId: Long): Long

    fun findBySourceUserId(sourceUserId: Long): List<AwsAccountSharing>

    fun deleteBySourceUserId(sourceUserId: Long)

    fun deleteByTargetUserId(targetUserId: Long)

    fun deleteByCreatedBy_Id(createdById: Long)

    fun existsBySourceUserIdAndTargetUserId(sourceUserId: Long, targetUserId: Long): Boolean

    fun findBySourceUserIdAndTargetUserId(sourceUserId: Long, targetUserId: Long): Optional<AwsAccountSharing>

    fun deleteBySourceUserIdAndTargetUserId(sourceUserId: Long, targetUserId: Long)

    override fun findAll(pageable: Pageable): Page<AwsAccountSharing>

    /**
     * Find all sharing rules with eager-loaded user associations for admin listing.
     */
    @Query("""
        SELECT s FROM AwsAccountSharing s
        LEFT JOIN FETCH s.sourceUser
        LEFT JOIN FETCH s.targetUser
        LEFT JOIN FETCH s.createdBy
        ORDER BY s.createdAt DESC
    """)
    fun findAllWithUsers(): List<AwsAccountSharing>

    /**
     * Find all AWS account IDs accessible to a target user via sharing rules.
     * Joins aws_account_sharing -> users -> user_mapping to resolve shared accounts.
     *
     * This is the critical query for access control integration.
     * Non-transitive by design: only joins user_mapping (direct mappings),
     * not recursively computed visibility.
     */
    @Query(
        value = """
            SELECT DISTINCT um.aws_account_id
            FROM aws_account_sharing acs
            JOIN users u_source ON u_source.id = acs.source_user_id
            JOIN user_mapping um ON um.email = u_source.email AND um.aws_account_id IS NOT NULL
            WHERE acs.target_user_id = :targetUserId
        """,
        nativeQuery = true
    )
    fun findSharedAwsAccountIdsByTargetUserId(targetUserId: Long): List<String>
}
