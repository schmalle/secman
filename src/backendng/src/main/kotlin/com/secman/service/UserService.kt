package com.secman.service

import com.secman.domain.User
import com.secman.event.UserCreatedEvent
import com.secman.repository.AlignmentReviewerRepository
import com.secman.repository.AlignmentSessionRepository
import com.secman.repository.AssetRepository
import com.secman.repository.AwsAccountSharingRepository
import com.secman.repository.DemandClassificationResultRepository
import com.secman.repository.DemandClassificationRuleRepository
import com.secman.repository.DemandRepository
import com.secman.repository.ExceptionRequestAuditLogRepository
import com.secman.repository.MaintenanceBannerRepository
import com.secman.repository.PasskeyCredentialRepository
import com.secman.repository.ReleaseRepository
import com.secman.repository.RequirementReviewRepository
import com.secman.repository.ReviewDecisionRepository
import com.secman.repository.RiskAssessmentRepository
import com.secman.repository.RiskRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import com.secman.repository.VulnerabilityExceptionRequestRepository
import com.secman.repository.WorkgroupAwsAccountRepository
import io.micronaut.context.event.ApplicationEventPublisher
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import java.util.*

@Singleton
open class UserService(
    @Inject private val userRepository: UserRepository,
    @Inject private val eventPublisher: ApplicationEventPublisher<UserCreatedEvent>,
    @Inject private val alignmentReviewerRepository: AlignmentReviewerRepository,
    @Inject private val alignmentSessionRepository: AlignmentSessionRepository,
    @Inject private val awsAccountSharingRepository: AwsAccountSharingRepository,
    @Inject private val passkeyCredentialRepository: PasskeyCredentialRepository,
    @Inject private val requirementReviewRepository: RequirementReviewRepository,
    @Inject private val reviewDecisionRepository: ReviewDecisionRepository,
    @Inject private val userMappingRepository: UserMappingRepository,
    @Inject private val assetRepository: AssetRepository,
    @Inject private val riskRepository: RiskRepository,
    @Inject private val riskAssessmentRepository: RiskAssessmentRepository,
    @Inject private val vulnerabilityExceptionRequestRepository: VulnerabilityExceptionRequestRepository,
    @Inject private val exceptionRequestAuditLogRepository: ExceptionRequestAuditLogRepository,
    @Inject private val releaseRepository: ReleaseRepository,
    @Inject private val demandRepository: DemandRepository,
    @Inject private val demandClassificationRuleRepository: DemandClassificationRuleRepository,
    @Inject private val demandClassificationResultRepository: DemandClassificationResultRepository,
    @Inject private val maintenanceBannerRepository: MaintenanceBannerRepository,
    @Inject private val workgroupAwsAccountRepository: WorkgroupAwsAccountRepository
) {

    fun getAllUsers(): List<User> {
        return userRepository.findAll()
    }

    fun getUserById(id: Long): User? {
        return userRepository.findById(id).orElse(null)
    }

    fun getUserByUsername(username: String): User? {
        return userRepository.findByUsername(username).orElse(null)
    }

    fun getUserByEmail(email: String): User? {
        return userRepository.findByEmail(email).orElse(null)
    }

    fun getUserByUsernameOrEmail(username: String, email: String): User? {
        return userRepository.findByUsernameOrEmail(username, email).orElse(null)
    }

    fun createUser(user: User): User {
        val savedUser = userRepository.save(user)
        // Feature 042: Publish event to trigger automatic application of future user mappings
        eventPublisher.publishEvent(UserCreatedEvent(user = savedUser, source = "MANUAL"))
        return savedUser
    }

    fun updateUser(id: Long, updatedUser: User): User? {
        return getUserById(id)?.let { existing ->
            val updated = existing.copy(
                username = updatedUser.username,
                email = updatedUser.email,
                roles = updatedUser.roles
            )
            userRepository.update(updated)
            updated
        }
    }

    @Transactional
    open fun deleteUser(id: Long): Boolean {
        if (!userRepository.existsById(id)) return false

        // 1. AWS account sharing cleanup
        awsAccountSharingRepository.deleteBySourceUserId(id)
        awsAccountSharingRepository.deleteByTargetUserId(id)
        awsAccountSharingRepository.deleteByCreatedBy_Id(id)

        // 2. Passkey credentials
        passkeyCredentialRepository.deleteByUserId(id)

        // 3. Review decisions where this user was the decision-maker
        reviewDecisionRepository.deleteByDecidedBy_Id(id)

        // 4. Alignment chain (FK order: decisions → reviews → reviewers)
        val reviewers = alignmentReviewerRepository.findByUser_Id(id)
        for (reviewer in reviewers) {
            reviewDecisionRepository.deleteByReviewReviewerId(reviewer.id!!)
            requirementReviewRepository.deleteByReviewer_Id(reviewer.id!!)
        }
        alignmentReviewerRepository.deleteByUser_Id(id)

        // 5. Nullify alignment sessions initiated by this user
        alignmentSessionRepository.nullifyInitiatedByForUser(id)

        // 6. User mappings (FK user_mapping.user_id → users.id has no cascade;
        //    leaving rows behind triggers MariaDB error 1451 on user delete).
        //    We hard-delete here rather than demoting to PENDING future mappings
        //    to avoid composite-unique-key collisions with pre-existing
        //    future-user mappings for the same (email, aws_account_id, domain,
        //    ip_address) tuple. Mapping data can be reimported via CSV.
        userMappingRepository.deleteByUser_Id(id)

        // 7. Nullify all nullable User FKs across audit/history-style records.
        //    None of these FKs cascade, so each one will trigger MariaDB error
        //    1451 ("Cannot delete or update a parent row") on user delete if
        //    rows reference the user. We NULL them out instead of deleting the
        //    parent row because the entity-level intent is "preserve history,
        //    detach the actor" (e.g., ExceptionRequestAuditLog explicitly
        //    documents "Preserved even if user account deleted" and
        //    MaintenanceBanner.createdBy is annotated "Nullable to preserve
        //    history on user deletion").
        exceptionRequestAuditLogRepository.nullifyActorUserForUser(id)
        assetRepository.nullifyManualCreatorForUser(id)
        assetRepository.nullifyScanUploaderForUser(id)
        riskRepository.nullifyOwnerForUser(id)
        riskAssessmentRepository.nullifyRespondentForUser(id)
        vulnerabilityExceptionRequestRepository.nullifyRequestedByUserForUser(id)
        vulnerabilityExceptionRequestRepository.nullifyReviewedByUserForUser(id)
        releaseRepository.nullifyCreatedByForUser(id)
        demandRepository.nullifyApproverForUser(id)
        demandClassificationRuleRepository.nullifyCreatedByForUser(id)
        demandClassificationResultRepository.nullifyOverriddenByForUser(id)
        maintenanceBannerRepository.nullifyCreatedByForUser(id)
        workgroupAwsAccountRepository.nullifyCreatedByForUser(id)

        // 8. Delete user.
        //    NOTE: NOT-NULL User FKs that are NOT cleaned up here will still
        //    block deletion if the user owns rows there:
        //      - risk_assessment.assessor_id, requestor_id
        //      - demand.requestor_id
        //      - requirement_file.uploaded_by
        //    These need a schema migration to make them nullable before they
        //    can be nullified the same way. Tracked as a follow-up.
        userRepository.deleteById(id)
        return true
    }

    fun existsByUsername(username: String): Boolean {
        return userRepository.existsByUsername(username)
    }

    fun existsByEmail(email: String): Boolean {
        return userRepository.existsByEmail(email)
    }

    fun searchUsers(query: String, limit: Int? = null): List<User> {
        val allUsers = userRepository.findAll()
        val matches = allUsers.filter { user ->
            user.username.contains(query, ignoreCase = true) ||
            user.email.contains(query, ignoreCase = true)
        }

        return if (limit != null) matches.take(limit) else matches
    }

    fun getActiveUsers(): List<User> {
        return userRepository.findAll() // All users are considered "active" in this implementation
    }

    fun getUsersByRole(role: String): List<User> {
        return userRepository.findAll().filter { user ->
            user.roles.any { it.name.equals(role, ignoreCase = true) }
        }
    }

    /**
     * Count the number of users with ADMIN role
     * Feature: 037-last-admin-protection
     * Used to determine if a user is the last administrator
     *
     * @return count of users with ADMIN role
     */
    fun countAdminUsers(): Int {
        return userRepository.findAll().count { user ->
            user.roles.contains(User.Role.ADMIN)
        }
    }
}