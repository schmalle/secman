package com.secman.service

import com.secman.domain.AwsAccountSharing
import com.secman.domain.User
import com.secman.dto.AwsAccountSharingResponse
import com.secman.dto.CreateAwsAccountSharingRequest
import com.secman.dto.toResponse
import com.secman.repository.AwsAccountSharingRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory

/**
 * Service for managing AWS Account Sharing rules.
 *
 * Feature: AWS Account Sharing
 *
 * Provides business logic for creating, listing, and deleting sharing rules,
 * as well as resolving shared AWS account IDs for access control.
 */
@Singleton
open class AwsAccountSharingService(
    private val awsAccountSharingRepository: AwsAccountSharingRepository,
    private val userRepository: UserRepository,
    private val userMappingRepository: UserMappingRepository,
    private val userResolutionService: UserResolutionService
) {
    private val log = LoggerFactory.getLogger(AwsAccountSharingService::class.java)

    /**
     * List all sharing rules with source/target user details and shared account counts.
     * Manually paginates the eager-fetched results to avoid N+1 and LAZY issues.
     */
    fun listSharingRules(page: Int, size: Int): Map<String, Any> {
        val allRules = awsAccountSharingRepository.findAllWithUsers()
        return paginateRules(allRules, page, size)
    }

    /**
     * List sharing rules where the given user is the source (outgoing shares only).
     * Used for non-admin users who can only manage their own sharing rules.
     */
    fun listSharingRulesForSourceUser(sourceUserId: Long, page: Int, size: Int): Map<String, Any> {
        val allRules = awsAccountSharingRepository.findAllWithUsersBySourceUserId(sourceUserId)
        return paginateRules(allRules, page, size)
    }

    /**
     * List sharing rules where the given user is either the source OR the target.
     * Used for non-privileged users (anyone who is not ADMIN/SECCHAMPION) so they
     * see every rule they are personally involved in without seeing unrelated
     * rules between other users.
     */
    fun listSharingRulesForInvolvedUser(userId: Long, page: Int, size: Int): Map<String, Any> {
        val allRules = awsAccountSharingRepository.findAllWithUsersByInvolvedUserId(userId)
        return paginateRules(allRules, page, size)
    }

    private fun paginateRules(allRules: List<AwsAccountSharing>, page: Int, size: Int): Map<String, Any> {
        val totalElements = allRules.size.toLong()
        val totalPages = if (totalElements == 0L) 0 else ((totalElements + size - 1) / size).toInt()

        val pagedRules = allRules
            .drop(page * size)
            .take(size)
            .map { sharing ->
                val count = userMappingRepository.findDistinctAwsAccountIdByEmail(sharing.sourceUser.email).size
                sharing.toResponse(sharedAwsAccountCount = count)
            }

        return mapOf(
            "content" to pagedRules,
            "totalElements" to totalElements,
            "totalPages" to totalPages,
            "page" to page,
            "size" to size
        )
    }

    /**
     * Create a new sharing rule.
     *
     * Validates:
     * - source != target (no self-sharing)
     * - Both users exist
     * - No duplicate rule
     * - Source user has at least one AWS account mapping
     */
    @Transactional
    open fun createSharingRule(request: CreateAwsAccountSharingRequest, adminUserId: Long): AwsAccountSharingResponse {
        val sourceUser = resolveUser(request.sourceUserId, request.sourceUserEmail, "source")
        val targetUser = resolveUser(request.targetUserId, request.targetUserEmail, "target")

        if (sourceUser.id == targetUser.id) {
            throw IllegalArgumentException("Source and target user cannot be the same")
        }

        val adminUser = userRepository.findById(adminUserId)
            .orElseThrow { IllegalStateException("Admin user not found: $adminUserId") }

        if (awsAccountSharingRepository.existsBySourceUserIdAndTargetUserId(
                sourceUser.id!!, targetUser.id!!)) {
            throw DuplicateSharingException("Sharing rule already exists from ${sourceUser.email} to ${targetUser.email}")
        }

        val sourceAwsAccounts = userMappingRepository.findDistinctAwsAccountIdByEmail(sourceUser.email)
        if (sourceAwsAccounts.isEmpty()) {
            throw IllegalArgumentException("Source user ${sourceUser.email} has no AWS account mappings to share")
        }

        val sharing = AwsAccountSharing(
            sourceUser = sourceUser,
            targetUser = targetUser,
            createdBy = adminUser
        )

        val saved = awsAccountSharingRepository.save(sharing)
        log.info("AUDIT: AWS account sharing created: source={}, target={}, admin={}, sharedAccounts={}",
            sourceUser.email, targetUser.email, adminUser.email, sourceAwsAccounts.size)

        return saved.toResponse(sharedAwsAccountCount = sourceAwsAccounts.size)
    }

    private fun resolveUser(userId: Long?, email: String?, side: String): User =
        userResolutionService.resolveByIdOrEmail(userId, email, side)

    /**
     * Delete a sharing rule by ID.
     */
    @Transactional
    open fun deleteSharingRule(sharingId: Long): Boolean {
        val sharing = awsAccountSharingRepository.findById(sharingId)
            .orElseThrow { NoSuchElementException("Sharing rule not found: $sharingId") }

        awsAccountSharingRepository.delete(sharing)
        log.info("AUDIT: AWS account sharing deleted: id={}, source={}, target={}",
            sharingId, sharing.sourceUser.email, sharing.targetUser.email)
        return true
    }

    /**
     * Check if a sharing rule is owned by (source user of) the given user.
     * Used for authorization: non-admin users can only delete their own rules.
     */
    fun isOwnedByUser(sharingId: Long, userId: Long): Boolean {
        val sharing = awsAccountSharingRepository.findById(sharingId).orElse(null) ?: return false
        return sharing.sourceUser.id == userId
    }

    /**
     * Get all shared AWS account IDs for a target user.
     * Used by access control services to determine asset visibility.
     *
     * Returns distinct AWS account IDs from all source users sharing with this target user.
     */
    fun getSharedAwsAccountIds(targetUserId: Long): List<String> {
        return awsAccountSharingRepository.findSharedAwsAccountIdsByTargetUserId(targetUserId)
    }

    /**
     * Get shared AWS account IDs by resolving the target user's email to their ID.
     * Convenience method for services that have the email but not the user ID.
     */
    fun getSharedAwsAccountIdsByEmail(targetUserEmail: String): List<String> {
        val user = userRepository.findByEmailIgnoreCase(targetUserEmail).orElse(null) ?: return emptyList()
        return getSharedAwsAccountIds(user.id!!)
    }
}

/**
 * Thrown when attempting to create an AwsAccountSharing rule that already exists
 * for a (sourceUser, targetUser) pair. Mapped to HTTP 409 by the controller —
 * the optimistic-path counterpart to HibernateConstraintViolationHandler's
 * backstop on uk_aws_sharing_source_target.
 */
class DuplicateSharingException(message: String) : RuntimeException(message)
