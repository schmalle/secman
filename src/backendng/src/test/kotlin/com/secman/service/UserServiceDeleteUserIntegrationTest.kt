package com.secman.service

import com.secman.domain.AiSuggestionJob
import com.secman.domain.AiSuggestionScope
import com.secman.domain.AssessmentAssignment
import com.secman.domain.AssessmentBasisType
import com.secman.domain.DashboardPreference
import com.secman.domain.ExportJob
import com.secman.domain.McpApiKey
import com.secman.domain.NotificationChannel
import com.secman.domain.NotificationEventType
import com.secman.domain.NotificationPreference
import com.secman.domain.RelayIdentity
import com.secman.domain.RiskAssessment
import com.secman.domain.User
import com.secman.domain.UserNotificationSubscription
import com.secman.domain.UserSlackSettings
import com.secman.domain.UserTelegramSettings
import com.secman.repository.AiSuggestionJobRepository
import com.secman.repository.AssessmentAssignmentRepository
import com.secman.repository.AssetRepository
import com.secman.repository.DashboardPreferenceRepository
import com.secman.repository.ExportJobRepository
import com.secman.repository.McpApiKeyRepository
import com.secman.repository.NotificationPreferenceRepository
import com.secman.repository.RelayIdentityRepository
import com.secman.repository.RiskAssessmentRepository
import com.secman.repository.UserNotificationSubscriptionRepository
import com.secman.repository.UserRepository
import com.secman.repository.UserSlackSettingsRepository
import com.secman.repository.UserTelegramSettingsRepository
import com.secman.testutil.TestDataFactory
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.LocalDate
import java.util.UUID

/**
 * [UserService.deleteUser] must leave nothing behind that still points at the deleted id —
 * above all an MCP API key that could keep authenticating — and must detach (not refuse)
 * nullable references the validator no longer blocks on.
 *
 * Not transactional: deleteUser runs its own transaction and the assertions read committed state.
 */
@MicronautTest(environments = ["test"], transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserServiceDeleteUserIntegrationTest {

    @Inject lateinit var userService: UserService
    @Inject lateinit var userRepository: UserRepository
    @Inject lateinit var assetRepository: AssetRepository
    @Inject lateinit var riskAssessmentRepository: RiskAssessmentRepository
    @Inject lateinit var mcpApiKeyRepository: McpApiKeyRepository
    @Inject lateinit var slackRepository: UserSlackSettingsRepository
    @Inject lateinit var telegramRepository: UserTelegramSettingsRepository
    @Inject lateinit var subscriptionRepository: UserNotificationSubscriptionRepository
    @Inject lateinit var notificationPreferenceRepository: NotificationPreferenceRepository
    @Inject lateinit var dashboardPreferenceRepository: DashboardPreferenceRepository
    @Inject lateinit var relayIdentityRepository: RelayIdentityRepository
    @Inject lateinit var exportJobRepository: ExportJobRepository
    @Inject lateinit var aiSuggestionJobRepository: AiSuggestionJobRepository
    @Inject lateinit var assessmentAssignmentRepository: AssessmentAssignmentRepository

    private var tag = ""
    private val createdUserIds = mutableListOf<Long>()

    @BeforeEach
    fun newTag() {
        tag = UUID.randomUUID().toString().take(8)
    }

    @AfterEach
    fun cleanUp() {
        aiSuggestionJobRepository.deleteAll()
        assessmentAssignmentRepository.deleteAll()
        exportJobRepository.deleteAll()
        riskAssessmentRepository.deleteAll()
        assetRepository.deleteAll()
        createdUserIds.filter { userRepository.existsById(it) }.forEach { userService.deleteUser(it) }
        createdUserIds.clear()
    }

    @Test
    fun `deleteUser removes side rows`() {
        val target = saveUser("target")
        val userId = target.id!!
        val assessment = assessmentFor(respondent = target)

        mcpApiKeyRepository.save(McpApiKey(keyId = "test-key-id-$tag", keyHash = "h", name = "key", userId = userId, permissions = "ASSETS_READ"))
        slackRepository.save(UserSlackSettings(userId = userId))
        telegramRepository.save(UserTelegramSettings(userId = userId))
        subscriptionRepository.save(
            UserNotificationSubscription(userId = userId, channel = NotificationChannel.SLACK,
                eventType = NotificationEventType.CROWDSTRIKE_REPORT_COMPLETED)
        )
        notificationPreferenceRepository.save(NotificationPreference(userId = userId))
        dashboardPreferenceRepository.save(DashboardPreference(userId = userId))
        relayIdentityRepository.save(RelayIdentity(userId = userId, provider = "apple", providerSubject = "s-$tag"))
        val exportJob = exportJobRepository.save(ExportJob(id = "e-$tag", username = target.username, actorUserId = userId))
        val aiJob = aiSuggestionJobRepository.save(
            AiSuggestionJob(riskAssessmentId = assessment.id!!, triggeredByUserId = userId, initiatingUserId = userId,
                model = "m", scope = AiSuggestionScope.WHOLE_ASSESSMENT)
        )
        val assignment = assessmentAssignmentRepository.save(
            AssessmentAssignment(assessmentId = assessment.id!!, userId = userId, email = target.email, role = "RESPONDENT")
        )

        assertThat(userService.deleteUser(userId)).isTrue()

        assertThat(userRepository.existsById(userId)).isFalse()
        assertThat(mcpApiKeyRepository.findByUserId(userId)).isEmpty()
        assertThat(slackRepository.findByUserId(userId)).isEmpty
        assertThat(telegramRepository.findByUserId(userId)).isEmpty
        assertThat(subscriptionRepository.findByUserIdAndChannel(userId, NotificationChannel.SLACK)).isEmpty()
        assertThat(notificationPreferenceRepository.findByUserId(userId)).isEmpty
        assertThat(dashboardPreferenceRepository.findByUserId(userId)).isEmpty
        assertThat(relayIdentityRepository.findByUserId(userId)).isEmpty()
        assertThat(exportJobRepository.findById(exportJob.id).get().actorUserId).isNull()
        aiSuggestionJobRepository.findById(aiJob.id!!).get().let {
            assertThat(it.triggeredByUserId).isNull()
            assertThat(it.initiatingUserId).isNull()
        }
        assertThat(assessmentAssignmentRepository.findById(assignment.id!!).get().revoked).isTrue()
    }

    @Test
    fun `deleteUser detaches respondent instead of refusing`() {
        val target = saveUser("resp")
        val assessment = assessmentFor(respondent = target)

        assertThat(userService.deleteUser(target.id!!)).isTrue()

        assertThat(riskAssessmentRepository.findById(assessment.id!!).get().respondent).isNull()
    }

    private fun saveUser(prefix: String): User =
        userRepository.save(TestDataFactory.createRegularUser("$prefix-$tag", "$prefix-$tag@secman.test"))
            .also { createdUserIds += it.id!! }

    private fun assessmentFor(respondent: User): RiskAssessment {
        val owner = saveUser("owner")
        val asset = assetRepository.save(TestDataFactory.createAsset(name = "asset-$tag"))
        return riskAssessmentRepository.save(
            RiskAssessment(
                startDate = LocalDate.now(), endDate = LocalDate.now().plusDays(30),
                assessmentBasisType = AssessmentBasisType.ASSET, assessmentBasisId = asset.id!!,
                asset = asset, assessor = owner, requestor = owner, respondent = respondent
            )
        )
    }
}
