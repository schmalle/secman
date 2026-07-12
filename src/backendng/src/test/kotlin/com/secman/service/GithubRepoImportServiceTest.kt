package com.secman.service

import com.secman.domain.GithubAppConfig
import com.secman.domain.GithubRepoDependabotAlert
import com.secman.domain.GithubRepoFindingSnapshot
import com.secman.domain.GithubRepository
import com.secman.repository.GithubAppConfigRepository
import com.secman.repository.GithubRepoDependabotAlertRepository
import com.secman.repository.GithubRepoFindingSnapshotRepository
import com.secman.repository.GithubRepositoryRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional

class GithubRepoImportServiceTest {

    private lateinit var configRepository: GithubAppConfigRepository
    private lateinit var repoRepository: GithubRepositoryRepository
    private lateinit var snapshotRepository: GithubRepoFindingSnapshotRepository
    private lateinit var alertRepository: GithubRepoDependabotAlertRepository
    private lateinit var client: GithubAppClientService
    private lateinit var service: GithubRepoImportService

    private val config = GithubAppConfig(id = 1, appId = "12345", privateKeyPem = "pem")

    @BeforeEach
    fun setUp() {
        configRepository = mockk()
        repoRepository = mockk()
        snapshotRepository = mockk()
        alertRepository = mockk()
        client = mockk()
        service = GithubRepoImportService(configRepository, repoRepository, snapshotRepository, alertRepository, client)

        every { configRepository.findActiveConfig() } returns Optional.of(config)
        every { client.getInstallationToken(config) } returns "token"
        every { snapshotRepository.save(any()) } answers { firstArg() }
        every { repoRepository.findByGithubRepoId(any()) } returns Optional.empty()
        every { repoRepository.findByFullName(any()) } returns Optional.empty()
        every { repoRepository.save(any()) } answers {
            firstArg<GithubRepository>().also { it.id = it.id ?: it.githubRepoId }
        }
        every { repoRepository.update(any<GithubRepository>()) } answers { firstArg() }
        every { alertRepository.deleteByGithubRepositoryId(any()) } returns 0
    }

    private fun repoDto(id: Long, fullName: String = "org/repo$id") =
        GithubAppClientService.GithubRepoDto(
            repoId = id,
            name = fullName.substringAfter('/'),
            owner = fullName.substringBefore('/'),
            fullName = fullName,
            htmlUrl = "https://github.com/$fullName",
            archived = false
        )

    @Test
    fun `fails without active configuration`() {
        every { configRepository.findActiveConfig() } returns Optional.empty()

        assertThatThrownBy { service.importRepositories() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No active GitHub App configuration")
    }

    @Test
    fun `creates new repo with counts and snapshot`() {
        every { client.listInstallationRepositories("token") } returns listOf(repoDto(7))
        every { client.countOpenDependabotAlerts("token", "org", "repo7") } returns
            GithubAppClientService.SeverityCounts(critical = 2, high = 5)

        val result = service.importRepositories()

        assertThat(result.reposDiscovered).isEqualTo(1)
        assertThat(result.reposNew).isEqualTo(1)
        assertThat(result.reposUpdated).isEqualTo(0)
        assertThat(result.totalCritical).isEqualTo(2)
        assertThat(result.totalHigh).isEqualTo(5)

        val savedRepo = slot<GithubRepository>()
        verify { repoRepository.save(capture(savedRepo)) }
        assertThat(savedRepo.captured.criticalCount).isEqualTo(2)
        assertThat(savedRepo.captured.highCount).isEqualTo(5)
        assertThat(savedRepo.captured.lastImportAt).isNotNull()
        assertThat(savedRepo.captured.lastHighCriticalFindingAt).isNotNull()

        val savedSnapshot = slot<GithubRepoFindingSnapshot>()
        verify { snapshotRepository.save(capture(savedSnapshot)) }
        assertThat(savedSnapshot.captured.criticalCount).isEqualTo(2)
        assertThat(savedSnapshot.captured.highCount).isEqualTo(5)
    }

    @Test
    fun `update preserves ownerEmail and does not stamp finding time for clean repos`() {
        val existing = GithubRepository(
            id = 42, githubRepoId = 7, name = "repo7", owner = "org", fullName = "org/repo7",
            ownerEmail = "keeper@example.com", criticalCount = 9, highCount = 9,
            lastHighCriticalFindingAt = null
        )
        every { repoRepository.findByGithubRepoId(7) } returns Optional.of(existing)
        every { client.listInstallationRepositories("token") } returns listOf(repoDto(7))
        every { client.countOpenDependabotAlerts("token", "org", "repo7") } returns
            GithubAppClientService.SeverityCounts(critical = 0, high = 0)

        val result = service.importRepositories()

        assertThat(result.reposNew).isEqualTo(0)
        assertThat(result.reposUpdated).isEqualTo(1)

        val updated = slot<GithubRepository>()
        verify { repoRepository.update(capture(updated)) }
        assertThat(updated.captured.ownerEmail).isEqualTo("keeper@example.com")
        assertThat(updated.captured.criticalCount).isEqualTo(0)
        assertThat(updated.captured.highCount).isEqualTo(0)
        assertThat(updated.captured.lastHighCriticalFindingAt).isNull()
    }

    @Test
    fun `repo with alerts disabled is recorded with zero counts and listed`() {
        every { client.listInstallationRepositories("token") } returns listOf(repoDto(7))
        every { client.countOpenDependabotAlerts("token", "org", "repo7") } returns
            GithubAppClientService.SeverityCounts(disabled = true)

        val result = service.importRepositories()

        assertThat(result.reposWithAlertsDisabled).containsExactly("org/repo7")
        assertThat(result.errors).isEmpty()
        val savedRepo = slot<GithubRepository>()
        verify { repoRepository.save(capture(savedRepo)) }
        assertThat(savedRepo.captured.criticalCount).isEqualTo(0)
        assertThat(savedRepo.captured.highCount).isEqualTo(0)
    }

    @Test
    fun `per-repo error does not abort the run`() {
        every { client.listInstallationRepositories("token") } returns listOf(repoDto(1, "org/bad"), repoDto(2, "org/good"))
        every { client.countOpenDependabotAlerts("token", "org", "bad") } throws
            GithubAppClientService.GithubApiException("boom", 500)
        every { client.countOpenDependabotAlerts("token", "org", "good") } returns
            GithubAppClientService.SeverityCounts(critical = 1, high = 0)

        val result = service.importRepositories()

        assertThat(result.reposDiscovered).isEqualTo(2)
        assertThat(result.reposNew).isEqualTo(1)
        assertThat(result.errors).hasSize(1)
        assertThat(result.errors[0]).contains("org/bad")
    }

    @Test
    fun `replaces alert rows on each import`() {
        val alertDto = GithubAppClientService.AlertDto(
            alertNumber = 3,
            packageName = "lodash",
            ecosystem = "npm",
            manifestPath = "package.json",
            severity = "critical",
            ghsaId = "GHSA-xxxx",
            cveId = "CVE-2024-1",
            summary = "desc",
            vulnerableVersionRange = "< 4.17.21",
            firstPatchedVersion = "4.17.21",
            htmlUrl = "https://github.com/org/repo7/security/dependabot/3",
            alertCreatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            alertUpdatedAt = Instant.parse("2026-01-02T00:00:00Z")
        )
        val savedAlerts = slot<List<GithubRepoDependabotAlert>>()
        every { alertRepository.saveAll(capture(savedAlerts)) } answers { firstArg() }
        every { client.listInstallationRepositories("token") } returns listOf(repoDto(7))
        every { client.countOpenDependabotAlerts("token", "org", "repo7") } returns
            GithubAppClientService.SeverityCounts(critical = 1, high = 0, alerts = listOf(alertDto))

        service.importRepositories()

        verify { alertRepository.deleteByGithubRepositoryId(7) }
        assertThat(savedAlerts.captured).hasSize(1)
        assertThat(savedAlerts.captured[0].githubRepositoryId).isEqualTo(7)
        assertThat(savedAlerts.captured[0].packageName).isEqualTo("lodash")
        assertThat(savedAlerts.captured[0].cveId).isEqualTo("CVE-2024-1")
    }

    @Test
    fun `deletes stale alert rows even when the repo now has none`() {
        every { client.listInstallationRepositories("token") } returns listOf(repoDto(7))
        every { client.countOpenDependabotAlerts("token", "org", "repo7") } returns
            GithubAppClientService.SeverityCounts(critical = 0, high = 0, alerts = emptyList())

        service.importRepositories()

        verify { alertRepository.deleteByGithubRepositoryId(7) }
        verify(exactly = 0) { alertRepository.saveAll(any<Iterable<GithubRepoDependabotAlert>>()) }
    }
}
