package com.secman.domain

import io.micronaut.serde.annotation.Serdeable

/**
 * Domain event published after an `AwsAccountSharing` rule has been
 * successfully persisted. Consumed AFTER_COMMIT by
 * `AwsAccountSharingNotificationListener` to send an email to the
 * target user announcing the new access.
 *
 * The payload carries primitives only — no Hibernate-managed entities
 * cross the event boundary. The publishing service is responsible for
 * resolving lazy `User` associations on its transactional thread.
 */
@Serdeable
data class AwsAccountSharingCreatedEvent(
    val sharingId: Long,
    val sourceUserEmail: String,
    val targetUserId: Long,
    val targetUserEmail: String,
    val targetUsername: String,
    val createdByEmail: String,
    val createdAtIso: String,
    val sharedAwsAccountCount: Int,
)
