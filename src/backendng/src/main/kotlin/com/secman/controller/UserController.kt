package com.secman.controller

import com.secman.domain.User
import com.secman.dto.CreateUserMappingRequest
import com.secman.dto.UpdateUserMappingRequest
import com.secman.dto.UserMappingResponse
import com.secman.repository.UserRepository
import com.secman.service.UserMappingService
import io.micronaut.data.model.Pageable
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
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
    private val userDeletionValidator: com.secman.service.UserDeletionValidator,
    private val workgroupRepository: com.secman.repository.WorkgroupRepository,
    private val userMappingService: UserMappingService
) {
    
    private val passwordEncoder = BCryptPasswordEncoder()

    @Serdeable
    data class CreateUserRequest(
        val username: String,
        val email: String,
        val password: String,
        val roles: List<String>? = null,
        val workgroupIds: List<Long>? = null
    )

    @Serdeable
    data class UpdateUserRequest(
        val username: String? = null,
        val email: String? = null,
        val password: String? = null,
        val roles: List<String>? = null,
        val workgroupIds: List<Long>? = null
    )

    @Serdeable
    data class UserResponse(
        val id: Long,
        val username: String,
        val email: String,
        val roles: List<String>,
        val createdAt: String?,
        val updatedAt: String?,
        val workgroups: List<WorkgroupSummary>? = null,
        val workgroupCount: Int? = null
    ) {
        companion object {
            fun from(user: User, includeWorkgroups: Boolean = false): UserResponse {
                return UserResponse(
                    id = user.id!!,
                    username = user.username,
                    email = user.email,
                    roles = user.roles.map { it.name },
                    createdAt = user.createdAt?.toString(),
                    updatedAt = user.updatedAt?.toString(),
                    workgroups = if (includeWorkgroups) {
                        user.workgroups.map { WorkgroupSummary(it.id!!, it.name) }
                    } else null,
                    workgroupCount = if (includeWorkgroups) user.workgroups.size else null
                )
            }
        }
    }

    @Serdeable
    data class WorkgroupSummary(
        val id: Long,
        val name: String
    )

    /**
     * List all users with optional workgroup information
     * Feature: 008-create-an-additional (Workgroup-Based Access Control)
     *
     * GET /api/users?includeWorkgroups=true
     * FR-010: Display user workgroup membership in admin views
     */
    @Get
    fun list(@QueryValue(defaultValue = "false") includeWorkgroups: Boolean): HttpResponse<List<UserResponse>> {
        val users = userRepository.findAll().map { UserResponse.from(it, includeWorkgroups) }
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

            // Assign workgroups if provided
            request.workgroupIds?.let { workgroupIds ->
                val workgroups = workgroupIds.mapNotNull { id ->
                    workgroupRepository.findById(id).orElse(null)
                }

                if (workgroups.size != workgroupIds.size) {
                    return HttpResponse.badRequest(mapOf("error" to "One or more workgroup IDs not found"))
                }

                savedUser.workgroups.clear()
                savedUser.workgroups.addAll(workgroups)
                userRepository.update(savedUser)
            }

            return HttpResponse.ok(UserResponse.from(savedUser, includeWorkgroups = true))
            
        } catch (e: Exception) {
            return HttpResponse.serverError<Any>().body(mapOf("error" to "Failed to create user: ${e.message}"))
        }
    }

    /**
     * Get user by ID with workgroup information
     * Feature: 008-create-an-additional (Workgroup-Based Access Control)
     *
     * GET /api/users/{id}
     * FR-010: Display user workgroup membership in detail views
     */
    @Get("/{id}")
    fun get(@PathVariable id: Long): HttpResponse<*> {
        val userOptional = userRepository.findById(id)

        if (userOptional.isEmpty) {
            return HttpResponse.notFound<Any>().body(mapOf("error" to "User not found"))
        }

        // Always include workgroups in detail view
        return HttpResponse.ok(UserResponse.from(userOptional.get(), includeWorkgroups = true))
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

            request.workgroupIds?.let { workgroupIds ->
                val workgroups = workgroupIds.mapNotNull { id ->
                    workgroupRepository.findById(id).orElse(null)
                }

                if (workgroups.size != workgroupIds.size) {
                    return HttpResponse.badRequest(mapOf("error" to "One or more workgroup IDs not found"))
                }

                // Update workgroup assignments
                user.workgroups.clear()
                user.workgroups.addAll(workgroups)
            }

            val savedUser = userRepository.update(user)
            return HttpResponse.ok(UserResponse.from(savedUser, includeWorkgroups = true))
            
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

    // User Mapping Management Endpoints (Feature 017)
    
    /**
     * Get all mappings for a user
     * Feature: 017-user-mapping-management
     * User Story 1: View User's Existing Mappings (P1)
     */
    @Get("/{userId}/mappings")
    fun getUserMappings(@PathVariable userId: Long): List<UserMappingResponse> {
        return userMappingService.getUserMappings(userId)
    }
    
    /**
     * Create a new mapping for a user
     * Feature: 017-user-mapping-management
     * User Story 2: Add New Mapping (P2)
     */
    @Post("/{userId}/mappings")
    @Status(HttpStatus.CREATED)
    fun createUserMapping(
        @PathVariable userId: Long,
        @Body request: CreateUserMappingRequest
    ): UserMappingResponse {
        return userMappingService.createMapping(userId, request)
    }
    
    /**
     * Update an existing mapping
     * Feature: 017-user-mapping-management
     * User Story 4: Edit Existing Mapping (P3)
     */
    @Put("/{userId}/mappings/{mappingId}")
    fun updateUserMapping(
        @PathVariable userId: Long,
        @PathVariable mappingId: Long,
        @Body request: UpdateUserMappingRequest
    ): UserMappingResponse {
        return userMappingService.updateMapping(userId, mappingId, request)
    }
    
    /**
     * Delete a mapping
     * Feature: 017-user-mapping-management
     * User Story 3: Delete Existing Mapping (P2)
     */
    @Delete("/{userId}/mappings/{mappingId}")
    @Status(HttpStatus.NO_CONTENT)
    fun deleteUserMapping(
        @PathVariable userId: Long,
        @PathVariable mappingId: Long
    ) {
        userMappingService.deleteMapping(userId, mappingId)
    }
}