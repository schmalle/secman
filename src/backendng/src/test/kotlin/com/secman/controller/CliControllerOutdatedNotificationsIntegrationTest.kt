package com.secman.controller

import com.secman.domain.Criticality
import com.secman.domain.MaterializedViewRefreshJob
import com.secman.domain.UserMapping
import com.secman.domain.User
import com.secman.domain.Workgroup
import com.secman.repository.AssetRepository
import com.secman.repository.MaterializedViewRefreshJobRepository
import com.secman.repository.OutdatedAssetMaterializedViewRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.repository.WorkgroupRepository
import com.secman.service.MaterializedViewRefreshService
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestAuthHelper
import com.secman.testutil.TestDataFactory
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Regression test for the LazyInitializationException that occurred when
 * CliController.queryOutdatedAssets() called Asset.getEffectiveCriticality()
 * on an asset fetched via plain AssetRepository.findById() — workgroups is
 * FetchType.LAZY, so accessing it outside a session threw. Fixed by fetching
 * via AssetRepository.findByIdWithWorkgroups() instead.
 *
 * `transactional = false` is required: BaseIntegrationTest's default wraps each
 * test in one ambient, never-committed transaction, so writes here would be
 * invisible to the separate connection the embedded HTTP server uses to serve
 * the request under test (see AssetRepositoryLegacyStaleTest for precedent).
 */
@MicronautTest(environments = ["test"], transactional = false)
@DisplayName("CliController Outdated Notifications Tests")
open class CliControllerOutdatedNotificationsIntegrationTest : BaseIntegrationTest() {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var assetRepository: AssetRepository

    @Inject
    lateinit var workgroupRepository: WorkgroupRepository

    @Inject
    lateinit var vulnerabilityRepository: VulnerabilityRepository

    @Inject
    lateinit var userMappingRepository: UserMappingRepository

    @Inject
    lateinit var materializedViewRefreshJobRepository: MaterializedViewRefreshJobRepository

    @Inject
    lateinit var materializedViewRefreshService: MaterializedViewRefreshService

    @Inject
    lateinit var outdatedAssetRepository: OutdatedAssetMaterializedViewRepository

    private var adminUser: User? = null
    private var workgroup: Workgroup? = null
    private var asset: com.secman.domain.Asset? = null
    private var userMapping: UserMapping? = null
    private var refreshJob: MaterializedViewRefreshJob? = null

    @AfterEach
    fun cleanup() {
        outdatedAssetRepository.deleteAll()
        refreshJob?.let { materializedViewRefreshJobRepository.delete(it) }
        asset?.id?.let { vulnerabilityRepository.deleteByAssetId(it) }
        asset?.let { assetRepository.delete(it) }
        userMapping?.let { userMappingRepository.delete(it) }
        workgroup?.let { workgroupRepository.delete(it) }
        adminUser?.let { userRepository.delete(it) }
    }

    @Test
    fun `send-outdated dry run succeeds for asset whose criticality derives from a workgroup`() {
        val suffix = System.nanoTime()
        val awsAccountId = (100000000000L + (suffix % 900000000000L)).toString()

        adminUser = userRepository.save(
            TestDataFactory.createAdminUser(
                username = "cli-outdated-admin-$suffix",
                email = "cli-outdated-admin-$suffix@test.com"
            )
        )

        // Workgroup carries the criticality; the asset has none set explicitly, so
        // Asset.getEffectiveCriticality() must fall back to reading asset.workgroups.
        workgroup = workgroupRepository.save(
            Workgroup(name = "wg-$suffix", criticality = Criticality.HIGH)
        )

        val newAsset = TestDataFactory.createAsset(
            name = "outdated-asset-$suffix",
            owner = awsAccountId
        )
        newAsset.cloudAccountId = awsAccountId
        newAsset.workgroups = mutableSetOf(workgroup!!)
        asset = assetRepository.save(newAsset)

        vulnerabilityRepository.save(
            TestDataFactory.createVulnerability(asset!!, daysOpen = 60)
        )

        userMapping = userMappingRepository.save(
            UserMapping(
                email = "owner-$suffix@test.com",
                awsAccountId = awsAccountId,
                domain = null
            )
        )

        // Populate the outdated-asset materialized view synchronously (executeRefresh is
        // the non-@Async core used by the HTTP trigger's background job).
        refreshJob = materializedViewRefreshJobRepository.save(
            MaterializedViewRefreshJob(triggeredBy = "test-$suffix", totalAssets = 0)
        )
        materializedViewRefreshService.executeRefresh(refreshJob!!)

        val token = TestAuthHelper.getAuthToken(client, adminUser!!.username)

        val response = client.toBlocking().exchange(
            HttpRequest.POST(
                "/api/cli/notifications/send-outdated",
                CliController.SendNotificationsRequest(dryRun = true, verbose = true)
            ).bearerAuth(token),
            CliController.NotificationResultDto::class.java
        )

        assertThat(response.status).isEqualTo(HttpStatus.OK)
        assertThat(response.body()!!.assetsProcessed).isEqualTo(1)
    }
}
