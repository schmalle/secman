package com.secman.domain

/**
 * Status enum for UserMapping entity (Feature 049)
 *
 * Tracks whether a mapping is:
 * - PENDING: Created for a user that doesn't exist yet (future user mapping)
 * - ACTIVE: Applied to an existing user
 *
 * State transition: PENDING → ACTIVE (when user is created)
 * No reverse transition - mappings are deleted instead of reverting to PENDING
 */
enum class MappingStatus {
    /**
     * Mapping created for non-existent user
     * Will be auto-applied when user is created via UserCreatedEvent
     */
    PENDING,

    /**
     * Mapping applied to existing user
     * User can access assets based on this mapping
     */
    ACTIVE
}
