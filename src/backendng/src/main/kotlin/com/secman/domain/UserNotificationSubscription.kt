package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

/**
 * One row per (user, channel, event type) the user wants reported.
 *
 * Row-per-subscription rather than a boolean column per event: adding a new
 * [NotificationEventType] or a new [NotificationChannel] then costs no migration, and
 * "who wants X over Slack" is a single indexed lookup on the dispatch path.
 *
 * Including the channel in the key is what lets one user route CrowdStrike imports to
 * Slack and AWS imports to Telegram rather than forcing the same set on both.
 *
 * Presence of a row means subscribed; unsubscribing deletes it.
 */
@Entity
@Table(
    name = "user_notification_subscription",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_user_notification_subscription",
            columnNames = ["user_id", "channel", "event_type"]
        )
    ],
    indexes = [
        Index(name = "idx_user_notification_subscription_lookup", columnList = "channel,event_type")
    ]
)
@Serdeable
data class UserNotificationSubscription(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 32)
    var channel: NotificationChannel,

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64)
    var eventType: NotificationEventType,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null
)
