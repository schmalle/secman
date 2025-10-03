package com.secman.controller

import com.secman.domain.User
import com.secman.repository.UserRepository
import io.micronaut.data.model.Pageable
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.*
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import jakarta.transaction.Transactional
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

@Controller("/api/users")
@Secured("ADMIN")
open class UserController(
    private val userRepository: UserRepository,
    private val userDeletionValidator: com.secman.service.UserDeletionValidator
) {
    
    private val passwordEncoder = BCryptPasswordEncoder()

    @Serdeable
    data class CreateUserRequest(
        val username: String,
        val email: String,
        val password: String,
        val roles: List<String>? = null
    )

    @Serdeable
    data class UpdateUserRequest(
        val username: String? = null,
        val email: String? = null,
        val password: String? = null,
        val roles: List<String>? = null
    )

    @Serdeable
    data class UserResponse(
        val id: Long,
        val username: String,
        val email: String,
        val roles: List<String>,
        val createdAt: String?,
        val updatedAt: String?
    ) {
        companion object {
            fun from(user: User): UserResponse {
                return UserResponse(
                    id = user.id!!,
                    username = user.username,
                    email = user.email,
                    roles = user.roles.map { it.name },
                    createdAt = user.createdAt?.toString(),
                    updatedAt = user.updatedAt?.toString()
                )
            }
        }
    }

    @Get
    fun list(): HttpResponse<List<UserResponse>> {
        val users = userRepository.findAll().map { UserResponse.from(it) }
        return HttpResponse.ok(users)
    }

    @Post
    @Transactional
    open fun create(@Body request: CreateUserRequest): HttpResponse<*> {
        // Validation
        if (request.username.isBlank() || request.email.isBlank() || request.password.isBlank()) {
            return HttpResponse.badRequest(mapOf("error" to "Username, email, and password are required"))
        }

        // Check for duplicates
        if (userRepository.existsByUsername(request.username)) {
            return HttpResponse.badRequest(mapOf("error" to "Username already exists"))
        }

        if (userRepository.existsByEmail(request.email)) {
            return HttpResponse.badRequest(mapOf("error" to "Email already exists"))
        }

        try {
            // Parse roles
            val roles = mutableSetOf<User.Role>()
            request.roles?.forEach { roleString ->
                try {
                    roles.add(User.Role.valueOf(roleString.uppercase()))
                } catch (e: IllegalArgumentException) {
                    return HttpResponse.badRequest(mapOf("error" to "Invalid role: $roleString"))
                }
            }
            
            // Default to USER role if none provided
            if (roles.isEmpty()) {
                roles.add(User.Role.USER)
            }

            val user = User(
                username = request.username,
                email = request.email,
                passwordHash = passwordEncoder.encode(request.password),
                roles = roles
            )

            val savedUser = userRepository.save(user)
            return HttpResponse.ok(UserResponse.from(savedUser))
            
        } catch (e: Exception) {
            return HttpResponse.serverError<Any>().body(mapOf("error" to "Failed to create user: ${e.message}"))
        }
    }

    @Get("/{id}")
    fun get(@PathVariable id: Long): HttpResponse<*> {
        val userOptional = userRepository.findById(id)
        
        if (userOptional.isEmpty) {
            return HttpResponse.notFound<Any>().body(mapOf("error" to "User not found"))
        }

        return HttpResponse.ok(UserResponse.from(userOptional.get()))
    }

    @Put("/{id}")
    @Transactional
    open fun update(@PathVariable id: Long, @Body request: UpdateUserRequest): HttpResponse<*> {
        val userOptional = userRepository.findById(id)
        
        if (userOptional.isEmpty) {
            return HttpResponse.notFound<Any>().body(mapOf("error" to "User not found"))
        }

        val user = userOptional.get()

        try {
            // Update fields if provided
            request.username?.let { 
                if (it.isNotBlank() && it != user.username) {
                    if (userRepository.existsByUsername(it)) {
                        return HttpResponse.badRequest(mapOf("error" to "Username already exists"))
                    }
                    user.username = it
                }
            }

            request.email?.let { 
                if (it.isNotBlank() && it != user.email) {
                    if (userRepository.existsByEmail(it)) {
                        return HttpResponse.badRequest(mapOf("error" to "Email already exists"))
                    }
                    user.email = it
                }
            }

            request.password?.let { 
                if (it.isNotBlank()) {
                    user.passwordHash = passwordEncoder.encode(it)
                }
            }

            request.roles?.let { roleStrings ->
                val roles = mutableSetOf<User.Role>()
                roleStrings.forEach { roleString ->
                    try {
                        roles.add(User.Role.valueOf(roleString.uppercase()))
                    } catch (e: IllegalArgumentException) {
                        return HttpResponse.badRequest(mapOf("error" to "Invalid role: $roleString"))
                    }
                }
                // Properly manage the collection instead of replacing it
                user.roles.clear()
                user.roles.addAll(roles)
            }

            val savedUser = userRepository.update(user)
            return HttpResponse.ok(UserResponse.from(savedUser))
            
        } catch (e: Exception) {
            return HttpResponse.serverError<Any>().body(mapOf("error" to "Failed to update user: ${e.message}"))
        }
    }

    @Delete("/{id}")
    @Transactional
    open fun delete(@PathVariable id: Long): HttpResponse<*> {
        val userOptional = userRepository.findById(id)

        if (userOptional.isEmpty) {
            return HttpResponse.notFound<Any>().body(mapOf("error" to "User not found"))
        }

        // Validate user deletion
        val validationResult = userDeletionValidator.validateUserDeletion(id)

        if (!validationResult.canDelete) {
            // Return detailed error with blocking references
            val response = mapOf(
                "error" to "Cannot delete user",
                "message" to validationResult.message,
                "blockingReferences" to validationResult.blockingReferences.map { ref ->
                    mapOf(
                        "entityType" to ref.entityType,
                        "count" to ref.count,
                        "role" to ref.role,
                        "details" to ref.details
                    )
                }
            )
            return HttpResponse.badRequest<Any>().body(response)
        }

        try {
            userRepository.deleteById(id)
            return HttpResponse.ok(mapOf("message" to "User deleted successfully"))
        } catch (e: Exception) {
            return HttpResponse.serverError<Any>().body(mapOf("error" to "Failed to delete user: ${e.message}"))
        }
    }
}