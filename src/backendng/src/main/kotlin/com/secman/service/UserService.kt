package com.secman.service

import com.secman.domain.User
import com.secman.event.UserCreatedEvent
import com.secman.repository.AlignmentReviewerRepository
import com.secman.repository.AlignmentSessionRepository
import com.secman.repository.AwsAccountSharingRepository
import com.secman.repository.PasskeyCredentialRepository
import com.secman.repository.RequirementReviewRepository
import com.secman.repository.ReviewDecisionRepository
import com.secman.repository.UserRepository
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
    @Inject private val reviewDecisionRepository: ReviewDecisionRepository
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

        // 6. Delete user
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