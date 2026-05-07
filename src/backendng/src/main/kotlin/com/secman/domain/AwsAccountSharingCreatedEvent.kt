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
    /**
     * True when this share's target user did not exist as a SecMan User
     * before this rule was created (i.e. the sharing flow lazy-created
     * the row). The notification email uses this to decide whether to
     * include the "your account was just created" onboarding block.
     *
     * Note: under a tight race between two concurrent invites for the
     * same email, both events may carry true. That's accepted as a
     * benign inaccuracy — see design doc, "User-create races".
     */
    val targetUserWasJustCreated: Boolean = false,
)
