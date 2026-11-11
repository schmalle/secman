package com.secman.service

import com.secman.repository.AiSuggestionJobRepository
import com.secman.repository.AssessmentAssignmentRepository
import com.secman.repository.DashboardPreferenceRepository
import com.secman.repository.ExportJobRepository
import com.secman.repository.McpApiKeyRepository
import com.secman.repository.NotificationPreferenceRepository
import com.secman.repository.RelayIdentityRepository
import com.secman.repository.UserNotificationSubscriptionRepository
import com.secman.repository.UserSlackSettingsRepository
import com.secman.repository.UserTelegramSettingsRepository
import jakarta.inject.Singleton

/**
 * Tables that reference a user by a plain id column, with no foreign key to stop the
 * delete. Grouped so [UserService.deleteUser] stays readable and a new table of this
 * kind has one obvious home. Runs inside the caller's transaction.
 */
@Singleton
class UserSideDataRepositories(
    private val mcpApiKeys: McpApiKeyRepository,
    private val slackSettings: UserSlackSettingsRepository,
    private val telegramSettings: UserTelegramSettingsRepository,
    private val subscriptions: UserNotificationSubscriptionRepository,
    private val notificationPreferences: NotificationPreferenceRepository,
    private val dashboardPreferences: DashboardPreferenceRepository,
    private val relayIdentities: RelayIdentityRepository,
    private val exportJobs: ExportJobRepository,
    private val aiSuggestionJobs: AiSuggestionJobRepository,
    private val assessmentAssignments: AssessmentAssignmentRepository
) {
    fun removeFor(userId: Long) {
        mcpApiKeys.deleteByUserId(userId)
        slackSettings.deleteByUserId(userId)
        telegramSettings.deleteByUserId(userId)
        subscriptions.deleteByUserId(userId)
        notificationPreferences.deleteByUserId(userId)
        dashboardPreferences.deleteByUserId(userId)
        relayIdentities.deleteByUserId(userId)
        // History rows: keep them, detach the actor.
        exportJobs.nullifyActorForUser(userId)
        aiSuggestionJobs.nullifyTriggeredByForUser(userId)
        aiSuggestionJobs.nullifyInitiatingUserForUser(userId)
        assessmentAssignments.revokeForUser(userId)
    }
}
