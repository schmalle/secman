package com.secman.event

import com.secman.domain.User
import io.micronaut.serde.annotation.Serdeable
import java.time.Instant

/**
 * Event published when a new user is created
 *
 * Feature: 042-future-user-mappings
 *
 * Purpose: Trigger automatic application of future user mappings when a user is created
 * (either manually or via OAuth auto-provisioning)
 *
 * Related to: Feature 042 (Future User Mappings)
 */
@Serdeable
data class UserCreatedEvent(
    /**
     * The newly created user
     */
    val user: User,

    /**
     * Timestamp when the event was created
     */
    val timestamp: Instant = Instant.now(),

    /**
     * Source of user creation (MANUAL, OAUTH, IMPORT, etc.)
     */
    val source: String = "MANUAL"
)
