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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.util.UUID

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
    private val userMappingService: UserMappingService
) {
    private val log = LoggerFactory.getLogger(AwsAccountSharingService::class.java)
    private val passwordEncoder = BCryptPasswordEncoder()

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

    /**
     * Resolve a User from either an id or an email.
     *
     * - If `userId` is provided, look it up directly.
     * - If only `email` is provided, look up by email (case-insensitive). If no
     *   matching User exists (the email belongs to a "pending" mapping for a
     *   user who has never logged in), create a User record on the fly so that
     *   the sharing rule's FK is satisfied. The new User follows the OAuth
     *   auto-provisioning shape: no usable password, OAUTH auth source,
     *   default roles. Any PENDING UserMappings for the email are applied
     *   synchronously inside the current transaction — see the inline
     *   comment below for why we don't publish UserCreatedEvent here.
     *
     * @param side "source" or "target" — used only in error messages.
     */
    private fun resolveUser(userId: Long?, email: String?, side: String): User {
        if (userId != null && userId > 0) {
            return userRepository.findById(userId)
                .orElseThrow { NoSuchElementException("$side user not found: $userId") }
        }
        val normalizedEmail = email?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("$side user identifier required (id or email)")

        val existing = userRepository.findByEmailIgnoreCase(normalizedEmail).orElse(null)
        if (existing != null) return existing

        log.info("Creating pending User record on demand for {} email: {}", side, normalizedEmail)
        val username = resolveUniqueUsername(normalizedEmail.substringBefore("@"))
        val newUser = User(
            username = username,
            email = normalizedEmail,
            passwordHash = passwordEncoder.encode(UUID.randomUUID().toString())!!,
            roles = mutableSetOf(User.Role.USER, User.Role.VULN, User.Role.REQ),
            authSource = User.AuthSource.OAUTH
        )
        val saved = userRepository.save(newUser)

        // Apply PENDING UserMappings synchronously in the current transaction.
        //
        // We deliberately do NOT publish UserCreatedEvent here: the default
        // listener (UserMappingService.onUserCreated) is @Async @Transactional,
        // which opens a SEPARATE Hibernate session while ours still owns the
        // new User's `workgroups` PersistentSet. That cross-session collection
        // ownership trips Hibernate's "Found shared references to a collection"
        // check during auto-flush of the listener's first query. Performing the
        // mapping application in this thread and session avoids the hazard —
        // everything the async listener would have done, done synchronously.
        try {
            val applied = userMappingService.applyFutureUserMapping(saved)
            if (applied > 0) {
                log.info("Applied {} pending user mapping(s) to newly-created user: {}", applied, saved.email)
            }
        } catch (e: Exception) {
            // Don't fail sharing-rule creation if mapping application fails.
            log.warn("Failed to apply pending user mappings for {}: {}", saved.email, e.message)
        }
        return saved
    }

    /**
     * Append a numeric suffix to keep usernames unique when the email prefix
     * collides with an existing user (e.g. two "john" emails on different domains).
     */
    private fun resolveUniqueUsername(baseUsername: String): String {
        val sanitized = baseUsername.ifBlank { "user" }
        if (!userRepository.existsByUsername(sanitized)) return sanitized
        for (suffix in 2..999) {
            val candidate = "$sanitized$suffix"
            if (!userRepository.existsByUsername(candidate)) return candidate
        }
        throw IllegalStateException("Unable to derive unique username from: $baseUsername")
    }

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
