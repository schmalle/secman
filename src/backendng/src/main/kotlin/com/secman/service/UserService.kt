package com.secman.service

import com.secman.domain.User
import com.secman.event.UserCreatedEvent
import com.secman.repository.UserRepository
import io.micronaut.context.event.ApplicationEventPublisher
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.util.*

@Singleton
class UserService(
    @Inject private val userRepository: UserRepository,
    @Inject private val eventPublisher: ApplicationEventPublisher<UserCreatedEvent>
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

    fun deleteUser(id: Long): Boolean {
        return if (userRepository.existsById(id)) {
            userRepository.deleteById(id)
            true
        } else {
            false
        }
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