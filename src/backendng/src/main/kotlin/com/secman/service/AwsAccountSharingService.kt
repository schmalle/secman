package com.secman.service

import com.secman.domain.AwsAccountSharing
import com.secman.domain.AwsAccountSharingCreatedEvent
import com.secman.domain.User
import com.secman.dto.AwsAccountSharingResponse
import com.secman.dto.CreateAwsAccountSharingRequest
import com.secman.dto.UpdateAwsAccountSharingRequest
import com.secman.dto.toResponse
import com.secman.repository.AwsAccountSharingRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import io.micronaut.context.event.ApplicationEventPublisher
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory

/**
 * Service for managing AWS Account Sharing rules.
 *
 * Feature: AWS Account Sharing (per-account scoping in V207)
 *
 * Provides business logic for creating, listing, updating, and deleting
 * sharing rules, as well as resolving shared AWS account IDs for access
 * control.
 *
 * Selection semantics:
 * - selectedAwsAccountIds empty  → share ALL of the source user's accounts
 *   (legacy default; future mappings auto-propagate)
 * - selectedAwsAccountIds set    → share ONLY the listed account IDs
 *   (mapping changes do not auto-extend the rule)
 */
@Singleton
open class AwsAccountSharingService(
    private val awsAccountSharingRepository: AwsAccountSharingRepository,
    private val userRepository: UserRepository,
    private val userMappingRepository: UserMappingRepository,
    private val userResolutionService: UserResolutionService,
    private val sharingCreatedEventPublisher: ApplicationEventPublisher<AwsAccountSharingCreatedEvent>,
    private val mcpAccessCacheInvalidator: McpAccessibleAssetsCacheInvalidator,
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

        val responses = pagedRules.map { sharing ->
            // selectedAwsAccountIds is JOIN FETCHed by the repository queries
            // (see findAllWithUsers* in AwsAccountSharingRepository), so reading
            // it here does not trigger a lazy load even though we're outside
            // the original transaction.
            val selected = sharing.selectedAwsAccountIds.toList().sorted()
            val effectiveCount = if (selected.isNotEmpty()) {
                selected.size
            } else {
                userMappingRepository.findDistinctAwsAccountIdByEmail(sharing.sourceUser.email).size
            }
            sharing.toResponse(
                sharedAwsAccountCount = effectiveCount,
                selectedAwsAccountIds = selected,
            )
        }

        return mapOf(
            "content" to responses,
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
     * - If awsAccountIds is provided and non-empty, at least one must match
     *   one of the source user's actual mappings (unknown IDs are silently
     *   dropped — defensive against stale UI state)
     */
    @Transactional
    open fun createSharingRule(request: CreateAwsAccountSharingRequest, adminUserId: Long): AwsAccountSharingResponse {
        val sourceUser = resolveUser(request.sourceUserId, request.sourceUserEmail, "source")

        // Capture before resolution: was the target email a new account
        // at the moment the request entered the service? Used to gate the
        // "your account was just created" block in the notification email.
        val targetWasNewBeforeResolve = request.inviteByEmail &&
            !request.targetUserEmail.isNullOrBlank() &&
            userRepository.findByEmailIgnoreCase(request.targetUserEmail).isEmpty

        val targetUser = if (request.inviteByEmail) {
            // Invite path: explicit USER+VULN role set; controller has
            // already validated domain + non-collision.
            userResolutionService.resolveByIdOrEmail(
                userId = null,
                email = request.targetUserEmail,
                context = "target",
                roles = setOf(User.Role.USER, User.Role.VULN),
            )
        } else {
            resolveUser(request.targetUserId, request.targetUserEmail, "target")
        }

        if (request.inviteByEmail && targetWasNewBeforeResolve) {
            log.info(
                "AUDIT: AWS sharing invited user created: email={}, inviter={}, roles={}",
                targetUser.email, sourceUser.email, "USER,VULN",
            )
        }

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

        // Resolve effective scope: empty/null → share-all (legacy); otherwise
        // intersect requested IDs with source's actual mappings.
        val sourceSet = sourceAwsAccounts.toSet()
        val resolvedScope: Set<String> = when {
            request.awsAccountIds.isNullOrEmpty() -> emptySet() // share-all
            else -> {
                val requested = request.awsAccountIds.toSet()
                val matched = requested.intersect(sourceSet)
                val unknown = requested - sourceSet
                if (unknown.isNotEmpty()) {
                    log.warn(
                        "AWS account sharing create: dropping {} unknown account ids for source {}: {}",
                        unknown.size, sourceUser.email, unknown
                    )
                }
                if (matched.isEmpty()) {
                    throw IllegalArgumentException(
                        "None of the requested account IDs match the source user's AWS account mappings"
                    )
                }
                matched
            }
        }

        val sharing = AwsAccountSharing(
            sourceUser = sourceUser,
            targetUser = targetUser,
            createdBy = adminUser,
            selectedAwsAccountIds = resolvedScope.toMutableSet(),
        )

        val saved = awsAccountSharingRepository.save(sharing)
        val effectiveCount = if (resolvedScope.isNotEmpty()) resolvedScope.size else sourceAwsAccounts.size
        log.info(
            "AUDIT: AWS account sharing created: source={}, target={}, admin={}, scope={}, sharedAccounts={}, inviteCreatedUser={}",
            sourceUser.email, targetUser.email, adminUser.email,
            if (resolvedScope.isEmpty()) "ALL" else "SELECTED(${resolvedScope.size})",
            effectiveCount,
            targetWasNewBeforeResolve,
        )

        sharingCreatedEventPublisher.publishEvent(
            AwsAccountSharingCreatedEvent(
                sharingId = saved.id!!,
                sourceUserEmail = sourceUser.email,
                targetUserId = targetUser.id!!,
                targetUserEmail = targetUser.email,
                targetUsername = targetUser.username,
                createdByEmail = adminUser.email,
                createdAtIso = saved.createdAt!!.toString(),
                sharedAwsAccountCount = effectiveCount,
                targetUserWasJustCreated = targetWasNewBeforeResolve,
            )
        )

        // The target user just gained visibility into the source's AWS accounts —
        // clear the per-user MCP access cache so the change is reflected before
        // the 5-minute TTL would expire it.
        mcpAccessCacheInvalidator.invalidate()

        return saved.toResponse(
            sharedAwsAccountCount = effectiveCount,
            selectedAwsAccountIds = resolvedScope.sorted(),
        )
    }

    /**
     * Update the per-account selection on an existing sharing rule.
     *
     * Source and target are immutable on a sharing row — to change them,
     * delete and recreate. A null/empty `awsAccountIds` resets the rule
     * back to share-all.
     */
    @Transactional
    open fun updateSharingRule(sharingId: Long, request: UpdateAwsAccountSharingRequest): AwsAccountSharingResponse {
        val sharing = awsAccountSharingRepository.findById(sharingId)
            .orElseThrow { NoSuchElementException("Sharing rule not found: $sharingId") }

        val sourceAwsAccounts = userMappingRepository.findDistinctAwsAccountIdByEmail(sharing.sourceUser.email)
        val sourceSet = sourceAwsAccounts.toSet()

        val resolvedScope: Set<String> = when {
            request.awsAccountIds.isNullOrEmpty() -> emptySet()
            else -> {
                val requested = request.awsAccountIds.toSet()
                val matched = requested.intersect(sourceSet)
                val unknown = requested - sourceSet
                if (unknown.isNotEmpty()) {
                    log.warn(
                        "AWS account sharing update: dropping {} unknown account ids for source {}: {}",
                        unknown.size, sharing.sourceUser.email, unknown
                    )
                }
                if (matched.isEmpty()) {
                    throw IllegalArgumentException(
                        "None of the requested account IDs match the source user's AWS account mappings"
                    )
                }
                matched
            }
        }

        // Replace the collection contents in place so JPA cascades the diff
        // (delete-then-insert) on the join table within this transaction.
        sharing.selectedAwsAccountIds.clear()
        sharing.selectedAwsAccountIds.addAll(resolvedScope)
        val saved = awsAccountSharingRepository.update(sharing)

        val effectiveCount = if (resolvedScope.isNotEmpty()) resolvedScope.size else sourceAwsAccounts.size
        log.info(
            "AUDIT: AWS account sharing updated: id={}, source={}, target={}, scope={}, sharedAccounts={}",
            sharingId, sharing.sourceUser.email, sharing.targetUser.email,
            if (resolvedScope.isEmpty()) "ALL" else "SELECTED(${resolvedScope.size})",
            effectiveCount,
        )

        // Updating the per-account scope changes which assets the target can see
        // (either narrowing or widening). Invalidate so the change takes effect
        // immediately rather than after the cache TTL.
        mcpAccessCacheInvalidator.invalidate()

        return saved.toResponse(
            sharedAwsAccountCount = effectiveCount,
            selectedAwsAccountIds = resolvedScope.sorted(),
        )
    }

    private fun resolveUser(userId: Long?, email: String?, side: String): User =
        userResolutionService.resolveByIdOrEmail(userId, email, side)

    /**
     * Delete a sharing rule by ID. The aws_account_sharing_account child
     * rows are removed via the FK's ON DELETE CASCADE.
     */
    @Transactional
    open fun deleteSharingRule(sharingId: Long): Boolean {
        val sharing = awsAccountSharingRepository.findById(sharingId)
            .orElseThrow { NoSuchElementException("Sharing rule not found: $sharingId") }

        awsAccountSharingRepository.delete(sharing)
        log.info("AUDIT: AWS account sharing deleted: id={}, source={}, target={}",
            sharingId, sharing.sourceUser.email, sharing.targetUser.email)
        // Revoking a sharing rule must take effect immediately — the target user
        // should lose visibility into the source's accounts on the next request.
        mcpAccessCacheInvalidator.invalidate()
        return true
    }

    /**
     * Check if a sharing rule is owned by (source user of) the given user.
     * Used for authorization: non-admin users can only delete/update their own rules.
     */
    fun isOwnedByUser(sharingId: Long, userId: Long): Boolean {
        val sharing = awsAccountSharingRepository.findById(sharingId).orElse(null) ?: return false
        return sharing.sourceUser.id == userId
    }

    /**
     * List the AWS account IDs available for sharing on behalf of a given
     * source user — used to populate the per-account picker in the UI.
     *
     * Returned in lexical order so the UI display is stable across calls.
     */
    fun listSourceAccountIds(sourceUserId: Long): List<String> {
        val user = userRepository.findById(sourceUserId).orElse(null) ?: return emptyList()
        return userMappingRepository.findDistinctAwsAccountIdByEmail(user.email).sorted()
    }

    /**
     * Same as listSourceAccountIds but addressed by email. Convenience for
     * pending users (no User row yet — UserMapping hits by email).
     */
    fun listSourceAccountIdsByEmail(sourceUserEmail: String): List<String> {
        return userMappingRepository.findDistinctAwsAccountIdByEmail(sourceUserEmail).sorted()
    }

    /**
     * Get all shared AWS account IDs for a target user.
     * Used by access control services to determine asset visibility.
     *
     * Returns distinct AWS account IDs from all source users sharing with this target user,
     * honoring per-rule account selection. The native query treats an empty
     * selection as "all of the source user's accounts" so legacy rules still
     * resolve to the full set.
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
