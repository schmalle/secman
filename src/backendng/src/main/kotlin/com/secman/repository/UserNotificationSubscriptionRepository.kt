package com.secman.repository

import com.secman.domain.NotificationChannel
import com.secman.domain.NotificationEventType
import com.secman.domain.UserNotificationSubscription
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

/**
 * Repository for per-user chat notification subscriptions.
 */
@Repository
interface UserNotificationSubscriptionRepository : JpaRepository<UserNotificationSubscription, Long> {
    fun findByUserIdAndChannel(userId: Long, channel: NotificationChannel): List<UserNotificationSubscription>

    /** Dispatch path: everyone who asked to be told about this event over this channel. */
    fun findByChannelAndEventType(
        channel: NotificationChannel,
        eventType: NotificationEventType
    ): List<UserNotificationSubscription>

    fun deleteByUserId(userId: Long)
}
