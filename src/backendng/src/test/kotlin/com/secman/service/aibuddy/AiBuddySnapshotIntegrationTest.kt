package com.secman.service.aibuddy

import com.secman.domain.*
import com.secman.repository.*
import com.secman.security.BetaFeatureAccess
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestDataFactory
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import io.micronaut.http.exceptions.HttpStatusException
import java.time.LocalDateTime

@MicronautTest(environments = ["test"], transactional = false, startApplication = false)
class AiBuddySnapshotIntegrationTest : BaseIntegrationTest() {
    @Inject lateinit var assets: AssetRepository
    @Inject lateinit var users: UserRepository
    @Inject lateinit var groups: WorkgroupRepository
    @Inject lateinit var vulnerabilities: VulnerabilityRepository
    @Inject lateinit var snapshots: AiBuddySnapshotService
    @Inject lateinit var guard: AiBuddyAccessGuard
    @Inject lateinit var configs: TranslationConfigRepository
    @Inject lateinit var settings: AiBuddySettingsService
    @Inject lateinit var dataSource: javax.sql.DataSource
    @Inject lateinit var beta: BetaFeatureAccess

    @Test fun `shared config persists beta settings encrypts secrets and masks every response`() {
        val key = java.util.UUID.randomUUID().toString()
        val saved = configs.save(TranslationConfig(apiKey = key, modelName = "vendor/model", isActive = true,
            aiBuddyEnabled = true, aiBuddySingleInstance = true, aiBuddyProviders = "vendor"))
        val loaded = configs.findById(saved.id!!).get()
        assertThat(loaded.apiKey).isEqualTo(key)
        assertThat(loaded.toSafeResponse().apiKey).isEqualTo(TranslationConfig.API_KEY_MASK)
        assertThat(settings.current().config.model).isEqualTo("vendor/model")
        dataSource.unwrap(com.zaxxer.hikari.HikariDataSource::class.java).connection.use { connection ->
            connection.prepareStatement("SELECT api_key FROM translation_config WHERE id = ?").use { query ->
                query.setLong(1, saved.id!!)
                query.executeQuery().use { row -> row.next(); assertThat(row.getString(1)).isNotEqualTo(key) }
            }
            connection.prepareStatement("UPDATE translation_config SET api_key = ? WHERE id = ?").use { query ->
                query.setString(1, key); query.setLong(2, saved.id!!); query.executeUpdate()
            }
        }
        val legacy = configs.findById(saved.id!!).get()
        assertThat(legacy.apiKey).isEqualTo(key)
        val before = settings.current().fingerprint
        legacy.modelName = "vendor/other-model"; configs.update(legacy)
        assertThat(settings.current().fingerprint).isNotEqualTo(before)
        assertThat(settings.current().config.model).isEqualTo("vendor/other-model")
        configs.deleteById(saved.id!!)
    }

    @Test fun `BETA persistence uses existing varchar roles and cannot widen workgroup access`() {
        val suffix = System.nanoTime()
        val group = groups.save(Workgroup(name = "buddy-$suffix"))
        val accessible = assets.save(TestDataFactory.createAsset("buddy-shared-$suffix").apply { workgroups.add(group) })
        val privateAsset = assets.save(TestDataFactory.createAsset("buddy-private-$suffix"))
        val user = users.save(TestDataFactory.createRegularUser("buddy-$suffix", "buddy-$suffix@test.invalid").apply {
            roles.add(User.Role.BETA); workgroups.add(group)
        })
        val staleAuth = Authentication.build(user.username, listOf("USER"), mapOf("userId" to user.id.toString()))
        assertThat(beta.current(staleAuth).roles).contains(User.Role.BETA)
        assertThat(guard.check(accessible.id!!, staleAuth).id).isEqualTo(user.id)
        assertThrows<HttpStatusException> { guard.check(privateAsset.id!!, staleAuth) }
        group.enabled = false; groups.update(group)
        assertThrows<HttpStatusException> { guard.check(accessible.id!!, staleAuth) }
    }

    @Test fun `snapshot retains HIGH and CRITICAL across sources and detects replacement`() {
        val asset = assets.save(TestDataFactory.createAsset("buddy-snapshot-${System.nanoTime()}"))
        val rows = listOf(
            vulnerabilities.save(Vulnerability(asset = asset, vulnerabilityId = "CVE-2025-1111", vulnerableProductVersions = "Windows Server 2022", cvssSeverity = "High", scanTimestamp = LocalDateTime.now().minusDays(20), source = "CROWDSTRIKE")),
            vulnerabilities.save(Vulnerability(asset = asset, vulnerabilityId = "CVE-2025-1111", vulnerableProductVersions = "Microsoft Office", cvssSeverity = "Critical", scanTimestamp = LocalDateTime.now(), source = "MANUAL")),
            vulnerabilities.save(Vulnerability(asset = asset, vulnerabilityId = "configuration-finding", cvssSeverity = "High", scanTimestamp = LocalDateTime.now(), source = "NMAP")),
            vulnerabilities.save(Vulnerability(asset = asset, vulnerabilityId = "low-finding", cvssSeverity = "Low", scanTimestamp = LocalDateTime.now(), source = "MANUAL")),
            vulnerabilities.save(Vulnerability(asset = asset, vulnerabilityId = "medium-finding", cvssSeverity = "Medium", scanTimestamp = LocalDateTime.now(), source = "NMAP")))
        val first = snapshots.capture(asset.id!!)
        assertThat(first.findings.map { it.id }).containsExactlyElementsOf(rows.take(3).map { it.id!! })
        assertThat(first.findings.map { it.source }).containsExactly("CROWDSTRIKE", "MANUAL", "NMAP")
        assertThat(first.findings.mapNotNull { it.cve }.distinct()).hasSize(1)
        rows[0].vulnerableProductVersions = "Windows Server 2019"; vulnerabilities.update(rows[0])
        val next = snapshots.capture(asset.id!!)
        assertThat(next.fingerprint).isNotEqualTo(first.fingerprint)
        assertThat(first.findings.first().product).isEqualTo("Windows Server 2022")
        assertThat(next.findings.first().product).isEqualTo("Windows Server 2019")
    }
}
