package com.secman.service

import com.secman.domain.MappingStatus
import com.secman.domain.User
import com.secman.repository.UserMappingRepository
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.runtime.event.annotation.EventListener
import io.micronaut.scheduling.annotation.Async
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Event listener for automatic application of pending user mappings (Feature 049)
 *
 * When a new user is created, this service automatically finds and applies
 * any pending mappings that were created for that user's email address.
 *
 * Flow:
 * 1. User created via web UI, API, or CLI
 * 2. UserCreatedEvent published
 * 3. This listener receives the event
 * 4. Find all PENDING mappings for the user's email
 * 5. Update mappings: status = ACTIVE, user = newUser, appliedAt = NOW()
 *
 * Related to: Feature 042 (Future User Mappings), Feature 049 (CLI User Mapping)
 */
@Singleton
open class UserMappingApplicationService(
    private val userMappingRepository: UserMappingRepository
) {
    private val log = LoggerFactory.getLogger(UserMappingApplicationService::class.java)

    /**
     * Automatically apply pending user mappings when a user is created
     *
     * @param event UserCreatedEvent containing the newly created user
     */
    @EventListener
    @Async
    open fun onUserCreated(event: UserCreatedEvent) {
        val email = event.user.email.lowercase()
        log.info("User created event received for email: $email (source: ${event.source})")

        try {
            // Find all pending mappings for this email
            val pendingMappings = userMappingRepository.findByEmailAndStatus(email, MappingStatus.PENDING)

            if (pendingMappings.isEmpty()) {
                log.debug("No pending mappings found for user: $email")
                return
            }

            log.info("Found ${pendingMappings.size} pending mapping(s) for user: $email")

            // Apply each pending mapping
            pendingMappings.forEach { mapping ->
                mapping.user = event.user
                mapping.status = MappingStatus.ACTIVE
                mapping.appliedAt = Instant.now()
                userMappingRepository.update(mapping)

                log.info(
                    "Applied pending mapping: email=$email, " +
                    "domain=${mapping.domain}, awsAccountId=${mapping.awsAccountId}, " +
                    "ipAddress=${mapping.ipAddress}"
                )
            }

            log.info("Successfully applied ${pendingMappings.size} pending mapping(s) for user: $email")
        } catch (e: Exception) {
            log.error("Failed to apply pending mappings for user: $email", e)
            // Don't rethrow - event processing should continue even if mapping application fails
        }
    }
}

/**
 * Event published when a new user is created
 *
 * @param user The newly created user
 * @param source Source of the user creation (e.g., "WEB_UI", "API", "CLI", "OAUTH")
 */
data class UserCreatedEvent(
    val user: User,
    val source: String
)
