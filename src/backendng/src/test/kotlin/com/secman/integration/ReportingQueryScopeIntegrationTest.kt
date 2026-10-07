package com.secman.integration

import com.secman.domain.Asset
import com.secman.domain.OutdatedAssetMaterializedView
import com.secman.domain.UserMapping
import com.secman.domain.Workgroup
import com.secman.repository.AssetRepository
import com.secman.repository.OutdatedAssetMaterializedViewRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupRepository
import com.secman.service.AssetFilterService
import com.secman.service.OutdatedAssetService
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestDataFactory
import io.micronaut.data.model.Pageable
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReportingQueryScopeIntegrationTest : BaseIntegrationTest() {
    @Inject lateinit var entityManager: EntityManager
    @Inject lateinit var assets: AssetRepository
    @Inject lateinit var users: UserRepository
    @Inject lateinit var mappings: UserMappingRepository
    @Inject lateinit var groups: WorkgroupRepository
    @Inject lateinit var view: OutdatedAssetMaterializedViewRepository
    @Inject lateinit var outdated: OutdatedAssetService
    @Inject lateinit var filter: AssetFilterService

    private fun asset(name: String, domain: String, account: String? = null): Asset =
        assets.save(TestDataFactory.createAsset(name = name).apply {
            adDomain = domain
            cloudAccountId = account
        })

    private fun row(asset: Asset, critical: Int = 1) {
        view.save(OutdatedAssetMaterializedView(
            assetId = asset.id!!, assetName = asset.name, assetType = asset.type,
            totalOverdueCount = 1, criticalCount = critical, highCount = 1 - critical,
            oldestVulnDays = 45, adDomain = asset.adDomain,
            // Deliberately stale/unrelated: visibility must come from live grants.
            workgroupIds = "999999"
        ))
    }

    @Test
    fun `personal mapping applies before pagination and totals and revocation is immediate`() {
        val suffix = System.nanoTime()
        val user = users.save(TestDataFactory.createRegularUser("report-$suffix", "report-$suffix@example.test"))
        val auth = Authentication.build(user.username, listOf("USER", "VULN"),
            mapOf("userId" to user.id!!, "email" to user.email, "workgroupIds" to listOf(123L)))
        val domain = "report-$suffix.example"
        val grant = mappings.save(UserMapping(email = user.email, awsAccountId = null, domain = domain))
        val hidden = asset("hidden-$suffix", "hidden-$suffix.example")
        row(hidden)
        val visible = (1..3).map { asset("visible-$it-$suffix", domain) }
        visible.forEach { row(it) }
        entityManager.flush() // Native scope queries must see the fixture writes.

        val first = outdated.getOutdatedAssets(auth, pageable = Pageable.from(0, 2))
        val second = outdated.getOutdatedAssets(auth, pageable = Pageable.from(1, 2))
        assertThat(first.content.map { it.assetId }).containsExactly(visible[0].id, visible[1].id)
        assertThat(second.content.map { it.assetId }).containsExactly(visible[2].id)
        assertThat(first.totalSize).isEqualTo(3)
        assertThat(second.totalSize).isEqualTo(3)
        assertThat(outdated.countOutdatedAssets(auth)).isEqualTo(3)
        assertThat(outdated.getOutdatedAssets(auth, searchTerm = "visible-2-", minSeverity = "CRITICAL",
            adDomain = domain.uppercase(), pageable = Pageable.from(0, 1)).totalSize).isEqualTo(1)

        mappings.delete(grant)
        entityManager.flush()
        assertThat(outdated.countOutdatedAssets(auth)).isZero()
        assertThat(outdated.getOutdatedAssets(auth, pageable = Pageable.from(0, 2)).content).isEmpty()
    }

    @Test
    fun `disabled workgroup revokes outdated visibility despite stale view and authentication metadata`() {
        val suffix = System.nanoTime()
        val group = groups.save(Workgroup(name = "report-group-$suffix"))
        val user = users.save(TestDataFactory.createRegularUser("member-$suffix", "member-$suffix@example.test").apply {
            workgroups.add(group)
        })
        val granted = assets.save(TestDataFactory.createAsset(name = "group-asset-$suffix").apply { workgroups.add(group) })
        row(granted)
        entityManager.flush()
        val auth = Authentication.build(user.username, listOf("USER"),
            mapOf("userId" to user.id!!, "email" to user.email, "workgroupIds" to listOf(group.id!!)))
        assertThat(outdated.countOutdatedAssets(auth)).isEqualTo(1)
        group.enabled = false
        groups.update(group)
        entityManager.flush()
        assertThat(outdated.countOutdatedAssets(auth)).isZero()
        assertThat(outdated.getOutdatedAssets(auth, pageable = Pageable.from(0, 10)).totalSize).isZero()
    }

    @Test
    fun `statistics filters intersect grants and exclude blank AWS accounts`() {
        val suffix = System.nanoTime()
        val user = users.save(TestDataFactory.createRegularUser("stats-$suffix", "stats-$suffix@example.test"))
        val domain = "stats-$suffix.example"
        val grant = mappings.save(UserMapping(email = user.email, awsAccountId = null, domain = domain))
        val cloud = asset("cloud-$suffix", domain, "111111111111")
        asset("spaces-$suffix", domain, "   ")
        asset("null-$suffix", domain)
        val hidden = asset("other-$suffix", "other-$suffix.example", "222222222222")
        val auth = Authentication.build(user.username, listOf("USER"),
            mapOf("userId" to user.id!!, "email" to user.email))

        entityManager.flush()
        assertThat(filter.getFilteredAccessibleAssetIds(auth, domain.uppercase(), true)).containsExactly(cloud.id)
        assertThat(filter.getFilteredAccessibleAssetIds(auth, hidden.adDomain, true)).isEmpty()
        assertThat(filter.getFilteredAccessibleAssetIds(auth, null, false)).hasSize(3)
        for (role in listOf("ADMIN", "SECCHAMPION")) {
            val global = Authentication.build("global", listOf(role))
            assertThat(filter.getFilteredAccessibleAssetIds(global, domain.uppercase(), true)).containsExactly(cloud.id)
        }
        mappings.delete(grant)
        entityManager.flush()
        assertThat(filter.getFilteredAccessibleAssetIds(auth, domain, true)).isEmpty()
    }

    @Test
    fun `repository applies severity filters to content and count consistently`() {
        val suffix = System.nanoTime()
        val domain = "severity-$suffix.example"
        val critical = asset("critical-$suffix", domain)
        val high = asset("high-$suffix", domain)
        row(critical)
        row(high, critical = 0)
        val ids = setOf(critical.id!!, high.id!!)
        val page = view.findOutdatedAssetsForAssets(ids, null, "CRITICAL", domain, Pageable.from(0, 1))
        assertThat(page.content.map { it.assetId }).containsExactly(critical.id)
        assertThat(page.totalSize).isEqualTo(1)
        assertThat(view.countByAssetIdIn(ids)).isEqualTo(2)
    }
}
