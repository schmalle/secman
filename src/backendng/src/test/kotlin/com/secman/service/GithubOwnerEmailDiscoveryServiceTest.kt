package com.secman.service

import com.secman.domain.GithubAppConfig
import com.secman.domain.GithubOwnerEmailMapping
import com.secman.domain.GithubRepository
import com.secman.repository.GithubAppConfigRepository
import com.secman.repository.GithubOwnerEmailMappingRepository
import com.secman.repository.GithubRepositoryRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional

class GithubOwnerEmailDiscoveryServiceTest {

    private lateinit var configRepository: GithubAppConfigRepository
    private lateinit var repoRepository: GithubRepositoryRepository
    private lateinit var mappingRepository: GithubOwnerEmailMappingRepository
    private lateinit var mappingService: GithubOwnerEmailMappingService
    private lateinit var client: GithubAppClientService
    private lateinit var service: GithubOwnerEmailDiscoveryService

    private val config = GithubAppConfig(id = 1, appId = "12345", privateKeyPem = "pem")

    private fun repo(owner: String, id: Long) =
        GithubRepository(id = id, githubRepoId = id, name = "repo$id", owner = owner, fullName = "$owner/repo$id")

    @BeforeEach
    fun setUp() {
        configRepository = mockk()
        repoRepository = mockk()
        mappingRepository = mockk()
        mappingService = mockk()
        client = mockk()
        service = GithubOwnerEmailDiscoveryService(configRepository, repoRepository, mappingRepository, mappingService, client)

        every { configRepository.findActiveConfig() } returns Optional.of(config)
        every { client.getInstallationToken(config) } returns "token"
        every { mappingRepository.findByOwnerIgnoreCase(any()) } returns Optional.empty()
    }

    @Test
    fun `fails without active configuration`() {
        every { configRepository.findActiveConfig() } returns Optional.empty()

        assertThatThrownBy { service.discover(dryRun = false, actor = "admin") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No active GitHub App configuration")
    }

    @Test
    fun `creates a mapping when the owner has a public email`() {
        every { repoRepository.findByOwnerEmailIsNull() } returns listOf(repo("acme-corp", 1), repo("acme-corp", 2))
        every { client.fetchPublicEmail("token", "acme-corp", "https://api.github.com") } returns "security@acme-corp.example.com"
        every { mappingService.create("acme-corp", "security@acme-corp.example.com", "admin") } returns
            GithubOwnerEmailMapping(id = 9, owner = "acme-corp", email = "security@acme-corp.example.com", createdBy = "admin")

        val result = service.discover(dryRun = false, actor = "admin")

        assertThat(result.status).isEqualTo("SUCCESS")
        assertThat(result.ownersEvaluated).isEqualTo(1)
        assertThat(result.ownersDiscovered).isEqualTo(1)
        assertThat(result.discoveredMappings).containsExactly(
            GithubOwnerEmailDiscoveryService.DiscoveredMapping("acme-corp", "security@acme-corp.example.com", 2)
        )
        assertThat(result.ownersSkippedNoPublicEmail).isEmpty()
        assertThat(result.errors).isEmpty()
        verify { mappingService.create("acme-corp", "security@acme-corp.example.com", "admin") }
    }

    @Test
    fun `dry run previews a discovery without creating a mapping`() {
        every { repoRepository.findByOwnerEmailIsNull() } returns listOf(repo("acme-corp", 1))
        every { client.fetchPublicEmail("token", "acme-corp", "https://api.github.com") } returns "security@acme-corp.example.com"

        val result = service.discover(dryRun = true, actor = "admin")

        assertThat(result.status).isEqualTo("DRY_RUN")
        assertThat(result.discoveredMappings).hasSize(1)
        verify(exactly = 0) { mappingService.create(any(), any(), any()) }
    }

    @Test
    fun `owner with no public email is reported as skipped, not an error`() {
        every { repoRepository.findByOwnerEmailIsNull() } returns listOf(repo("some-user", 1))
        every { client.fetchPublicEmail("token", "some-user", "https://api.github.com") } returns null

        val result = service.discover(dryRun = false, actor = "admin")

        assertThat(result.status).isEqualTo("SUCCESS")
        assertThat(result.discoveredMappings).isEmpty()
        assertThat(result.ownersSkippedNoPublicEmail).containsExactly("some-user")
        assertThat(result.errors).isEmpty()
        verify(exactly = 0) { mappingService.create(any(), any(), any()) }
    }

    @Test
    fun `owner that already has a mapping despite a blank repo ownerEmail is not queried`() {
        every { repoRepository.findByOwnerEmailIsNull() } returns listOf(repo("acme-corp", 1))
        every { mappingRepository.findByOwnerIgnoreCase("acme-corp") } returns
            Optional.of(GithubOwnerEmailMapping(id = 1, owner = "acme-corp", email = "x@example.com", createdBy = "admin"))

        val result = service.discover(dryRun = false, actor = "admin")

        assertThat(result.ownersEvaluated).isEqualTo(0)
        assertThat(result.discoveredMappings).isEmpty()
        verify(exactly = 0) { client.fetchPublicEmail(any(), any(), any()) }
    }

    @Test
    fun `per-owner API failure is reported without aborting the run`() {
        every { repoRepository.findByOwnerEmailIsNull() } returns listOf(repo("bad-owner", 1), repo("good-owner", 2))
        every { client.fetchPublicEmail("token", "bad-owner", "https://api.github.com") } throws
            GithubAppClientService.GithubApiException("boom", 500)
        every { client.fetchPublicEmail("token", "good-owner", "https://api.github.com") } returns "good@example.com"
        every { mappingService.create("good-owner", "good@example.com", "admin") } returns
            GithubOwnerEmailMapping(id = 2, owner = "good-owner", email = "good@example.com", createdBy = "admin")

        val result = service.discover(dryRun = false, actor = "admin")

        assertThat(result.status).isEqualTo("PARTIAL_FAILURE")
        assertThat(result.ownersDiscovered).isEqualTo(1)
        assertThat(result.errors).hasSize(1)
        assertThat(result.errors[0]).contains("bad-owner")
    }

    @Test
    fun `uses the configured GitHub Enterprise base URL`() {
        val gheConfig = config.copy(apiBaseUrl = "https://ghe.corp.example.com/api/v3")
        every { configRepository.findActiveConfig() } returns Optional.of(gheConfig)
        every { client.getInstallationToken(gheConfig) } returns "token"
        every { repoRepository.findByOwnerEmailIsNull() } returns listOf(repo("acme-corp", 1))
        every { client.fetchPublicEmail("token", "acme-corp", "https://ghe.corp.example.com/api/v3") } returns null

        service.discover(dryRun = false, actor = "admin")

        verify { client.fetchPublicEmail("token", "acme-corp", "https://ghe.corp.example.com/api/v3") }
    }
}
