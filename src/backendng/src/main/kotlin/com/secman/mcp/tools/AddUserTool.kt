package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.domain.User
import com.secman.dto.mcp.McpExecutionContext
import com.secman.event.UserCreatedEvent
import com.secman.repository.UserRepository
import io.micronaut.context.event.ApplicationEventPublisher
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

/**
 * MCP tool for adding a new user with defined roles.
 * Feature: add-mcp-user-roles
 *
 * ADMIN role is required via User Delegation.
 * Creates a new user with the specified username, email, password, and roles.
 *
 * Input parameters:
 * - username (required): Unique username for the new user
 * - email (required): Unique email address for the new user
 * - password (required): Password for the new user (will be hashed)
 * - roles (optional): List of roles to assign (defaults to ["USER", "VULN", "REQ"])
 *
 * Available roles: USER, ADMIN, VULN, RELEASE_MANAGER, REQ, RISK, SECCHAMPION
 */
@Singleton
class AddUserTool(
    @Inject private val userRepository: UserRepository,
    @Inject private val eventPublisher: ApplicationEventPublisher<UserCreatedEvent>
) : McpTool {

    private val passwordEncoder = BCryptPasswordEncoder()

    override val name = "add_user"
    override val description = "Add a new user with defined roles (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "username" to mapOf(
                "type" to "string",
                "description" to "Unique username for the new user"
            ),
            "email" to mapOf(
                "type" to "string",
                "description" to "Unique email address for the new user"
            ),
            "password" to mapOf(
                "type" to "string",
                "description" to "Password for the new user (will be securely hashed)"
            ),
            "roles" to mapOf(
                "type" to "array",
                "items" to mapOf("type" to "string"),
                "description" to "List of roles to assign. Available: USER, ADMIN, VULN, RELEASE_MANAGER, REQ, RISK, SECCHAMPION. Defaults to [USER, VULN, REQ]"
            ),
            "mfaEnabled" to mapOf(
                "type" to "boolean",
                "description" to "Whether to enable MFA for the user. Defaults to false"
            )
        ),
        "required" to listOf("username", "email", "password")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        // Require User Delegation - cannot verify admin role without knowing the user
        if (!context.hasDelegation()) {
            return McpToolResult.error(
                "DELEGATION_REQUIRED",
                "User Delegation must be enabled to use this tool"
            )
        }

        // Require ADMIN role
        if (!context.isAdmin) {
            return McpToolResult.error(
                "ADMIN_REQUIRED",
                "ADMIN role required to add users"
            )
        }

        // Extract and validate required parameters
        val username = (arguments["username"] as? String)?.trim()
        val email = (arguments["email"] as? String)?.trim()
        val password = arguments["password"] as? String

        if (username.isNullOrBlank()) {
            return McpToolResult.error("VALIDATION_ERROR", "Username is required and cannot be blank")
        }

        if (email.isNullOrBlank()) {
            return McpToolResult.error("VALIDATION_ERROR", "Email is required and cannot be blank")
        }

        if (password.isNullOrBlank()) {
            return McpToolResult.error("VALIDATION_ERROR", "Password is required and cannot be blank")
        }

        // Basic email format validation
        if (!email.contains("@") || !email.contains(".")) {
            return McpToolResult.error("VALIDATION_ERROR", "Invalid email format")
        }

        // Check for duplicate username
        if (userRepository.existsByUsername(username)) {
            return McpToolResult.error("DUPLICATE_ERROR", "Username '$username' already exists")
        }

        // Check for duplicate email
        if (userRepository.existsByEmail(email)) {
            return McpToolResult.error("DUPLICATE_ERROR", "Email '$email' already exists")
        }

        // Parse roles
        val roles = mutableSetOf<User.Role>()
        @Suppress("UNCHECKED_CAST")
        val roleStrings = arguments["roles"] as? List<String>

        if (roleStrings.isNullOrEmpty()) {
            // Default to USER, VULN, REQ roles if none provided
            roles.addAll(setOf(User.Role.USER, User.Role.VULN, User.Role.REQ))
        } else {
            for (roleString in roleStrings) {
                try {
                    roles.add(User.Role.valueOf(roleString.uppercase().trim()))
                } catch (e: IllegalArgumentException) {
                    val validRoles = User.Role.entries.joinToString(", ") { it.name }
                    return McpToolResult.error(
                        "INVALID_ROLE",
                        "Invalid role: '$roleString'. Valid roles are: $validRoles"
                    )
                }
            }
        }

        // Parse MFA setting
        val mfaEnabled = arguments["mfaEnabled"] as? Boolean ?: false

        try {
            // Create the user
            val user = User(
                username = username,
                email = email,
                passwordHash = passwordEncoder.encode(password)!!,
                roles = roles,
                mfaEnabled = mfaEnabled
            )

            val savedUser = userRepository.save(user)

            // Publish event to trigger automatic application of future user mappings
            eventPublisher.publishEvent(UserCreatedEvent(user = savedUser, source = "MCP"))

            // Return success with user info (excluding password)
            val result = mapOf(
                "user" to mapOf(
                    "id" to savedUser.id,
                    "username" to savedUser.username,
                    "email" to savedUser.email,
                    "roles" to savedUser.roles.map { it.name },
                    "authSource" to savedUser.authSource.name,
                    "mfaEnabled" to savedUser.mfaEnabled,
                    "createdAt" to savedUser.createdAt?.toString()
                ),
                "message" to "User '${savedUser.username}' created successfully with roles: ${roles.joinToString(", ") { it.name }}"
            )

            return McpToolResult.success(result)

        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to create user: ${e.message}")
        }
    }
}
