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
     * Find all sharing rules with eager-loaded user associations and
     * per-rule account selection (Set<String> @ElementCollection) for
     * admin listing. Eager-fetching the selection avoids LazyInitException
     * when the service maps to DTOs outside the persistence context.
     */
    @Query("""
        SELECT DISTINCT s FROM AwsAccountSharing s
        LEFT JOIN FETCH s.sourceUser
        LEFT JOIN FETCH s.targetUser
        LEFT JOIN FETCH s.createdBy
        LEFT JOIN FETCH s.selectedAwsAccountIds
        ORDER BY s.createdAt DESC
    """)
    fun findAllWithUsers(): List<AwsAccountSharing>

    /**
     * Find sharing rules where the given user is the source, with eager-loaded associations.
     * Used for non-admin users who can only see their own outgoing sharing rules.
     */
    @Query("""
        SELECT DISTINCT s FROM AwsAccountSharing s
        LEFT JOIN FETCH s.sourceUser
        LEFT JOIN FETCH s.targetUser
        LEFT JOIN FETCH s.createdBy
        LEFT JOIN FETCH s.selectedAwsAccountIds
        WHERE s.sourceUser.id = :sourceUserId
        ORDER BY s.createdAt DESC
    """)
    fun findAllWithUsersBySourceUserId(sourceUserId: Long): List<AwsAccountSharing>

    /**
     * Find sharing rules where the given user is either source OR target, with
     * eager-loaded associations. Used for non-privileged users who should see
     * only the sharing rules they are personally involved in — as the account
     * owner sharing out, or as the recipient receiving visibility.
     */
    @Query("""
        SELECT DISTINCT s FROM AwsAccountSharing s
        LEFT JOIN FETCH s.sourceUser
        LEFT JOIN FETCH s.targetUser
        LEFT JOIN FETCH s.createdBy
        LEFT JOIN FETCH s.selectedAwsAccountIds
        WHERE s.sourceUser.id = :userId OR s.targetUser.id = :userId
        ORDER BY s.createdAt DESC
    """)
    fun findAllWithUsersByInvolvedUserId(userId: Long): List<AwsAccountSharing>

    /**
     * Find all AWS account IDs accessible to a target user via sharing rules.
     * Joins aws_account_sharing -> users -> user_mapping to resolve shared accounts.
     *
     * This is the critical query for access control integration.
     * Non-transitive by design: only joins user_mapping (direct mappings),
     * not recursively computed visibility.
     *
     * Per-account scoping (V207): a sharing row with NO entries in
     * aws_account_sharing_account shares ALL of the source's accounts
     * (legacy behavior). A row WITH entries shares only the listed ones.
     */
    @Query(
        value = """
            SELECT DISTINCT um.aws_account_id
            FROM aws_account_sharing acs
            JOIN users u_source ON u_source.id = acs.source_user_id
            JOIN user_mapping um ON um.email = u_source.email AND um.aws_account_id IS NOT NULL
            WHERE acs.target_user_id = :targetUserId
              AND (
                NOT EXISTS (
                    SELECT 1 FROM aws_account_sharing_account a
                    WHERE a.sharing_id = acs.id
                )
                OR EXISTS (
                    SELECT 1 FROM aws_account_sharing_account a
                    WHERE a.sharing_id = acs.id
                      AND a.aws_account_id = um.aws_account_id
                )
              )
        """,
        nativeQuery = true
    )
    fun findSharedAwsAccountIdsByTargetUserId(targetUserId: Long): List<String>
}
