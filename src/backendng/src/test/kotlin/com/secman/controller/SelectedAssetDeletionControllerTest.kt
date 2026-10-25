package com.secman.controller

import com.secman.domain.Asset
import com.secman.domain.Demand
import com.secman.domain.DemandType
import com.secman.domain.Vulnerability
import com.secman.repository.AssetDeletionAuditLogRepository
import com.secman.repository.AssetRepository
import com.secman.repository.DemandRepository
import com.secman.repository.UserRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestAuthHelper
import com.secman.testutil.TestDataFactory
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime

@MicronautTest(environments = ["test"], transactional = false)
class SelectedAssetDeletionControllerTest : BaseIntegrationTest() {
    @Inject @field:Client("/") lateinit var client: HttpClient
    @Inject lateinit var assets: AssetRepository
    @Inject lateinit var users: UserRepository
    @Inject lateinit var vulnerabilities: VulnerabilityRepository
    @Inject lateinit var demands: DemandRepository
    @Inject lateinit var audits: AssetDeletionAuditLogRepository

    private fun asset(): Asset = assets.save(TestDataFactory.createAsset(name = "selected-delete-${System.nanoTime()}"))

    private fun token(role: String = "ADMIN"): String {
        val name = "selection-admin-${System.nanoTime()}"
        val user = when (role) {
            "ADMIN" -> TestDataFactory.createAdminUser(name, "$name@test.com")
            "SECCHAMPION" -> TestDataFactory.createSecChampionUser(name, "$name@test.com")
            else -> TestDataFactory.createRegularUser(name, "$name@test.com")
        }
        users.save(user)
        return TestAuthHelper.getAuthToken(client, name)
    }

    private fun delete(ids: List<Long>, token: String? = token()): AssetController.DeleteSelectedAssetsResponse {
        val request = HttpRequest.POST("/api/assets/delete-selected", AssetController.DeleteSelectedAssetsRequest(ids))
        token?.let(request::bearerAuth)
        return client.toBlocking().retrieve(request, AssetController.DeleteSelectedAssetsResponse::class.java)
    }

    @Test
    fun `admin deletes only selected IDs with cascade and audit and deduplicates IDs`() {
        val selected = asset()
        val second = asset()
        val retained = asset()
        vulnerabilities.save(Vulnerability(asset = selected, vulnerabilityId = "SELECTED-LOW", cvssSeverity = "LOW",
            source = "MANUAL", scanTimestamp = LocalDateTime.now()))
        val selectedId = requireNotNull(selected.id)
        val secondId = requireNotNull(second.id)

        val response = delete(listOf(selectedId, secondId, selectedId))

        assertThat(response.results.map { it.assetId }).containsExactly(selectedId, secondId)
        assertThat(response.results.all { it.deleted }).isTrue()
        assertThat(assets.existsById(selectedId)).isFalse()
        assertThat(assets.existsById(secondId)).isFalse()
        assertThat(assets.existsById(requireNotNull(retained.id))).isTrue()
        assertThat(vulnerabilities.countAllStoredByAssetId(selectedId)).isZero()
        assertThat(audits.countByAssetId(selectedId)).isEqualTo(1)
    }

    @Test
    fun `anonymous users regular users and security champions cannot delete selections`() {
        val id = requireNotNull(asset().id)
        for (auth in listOf(null, token("USER"), token("SECCHAMPION"))) {
            val failure = assertThrows<HttpClientResponseException> { delete(listOf(id), auth) }
            assertThat(failure.status).isEqualTo(if (auth == null) HttpStatus.UNAUTHORIZED else HttpStatus.FORBIDDEN)
            assertThat(assets.existsById(id)).isTrue()
        }
    }

    @Test
    fun `invalid selections reject the whole request without deleting any asset`() {
        val id = requireNotNull(asset().id)
        val auth = token()
        for (ids in listOf(emptyList(), listOf(id, 0L), listOf(id, -1L), List(251) { id })) {
            val failure = assertThrows<HttpClientResponseException> { delete(ids, auth) }
            assertThat(failure.status).isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(assets.existsById(id)).isTrue()
        }
    }

    @Test
    fun `blocked and missing assets are reported while other selections commit`() {
        val blocked = asset()
        val selected = asset()
        val requestorName = "selected-demand-${System.nanoTime()}"
        val requestor = users.save(TestDataFactory.createAdminUser(requestorName, "$requestorName@test.com"))
        demands.save(Demand(title = "Retain referenced asset", demandType = DemandType.CHANGE,
            existingAsset = blocked, requestor = requestor))
        val blockedId = requireNotNull(blocked.id)
        val selectedId = requireNotNull(selected.id)

        val response = delete(listOf(blockedId, Long.MAX_VALUE, selectedId))

        assertThat(response.results.map { it.deleted }).containsExactly(false, false, true)
        assertThat(response.results[0].message).contains("demand")
        assertThat(assets.existsById(blockedId)).isTrue()
        assertThat(assets.existsById(selectedId)).isFalse()
    }
    private fun preview(hostnames: String, auth: String? = token()): AssetController.HostnameDeletionPreviewResponse {
        val request = HttpRequest.POST("/api/assets/deletion-preview", AssetController.HostnameDeletionPreviewRequest(hostnames))
        auth?.let(request::bearerAuth)
        return client.toBlocking().retrieve(request, AssetController.HostnameDeletionPreviewResponse::class.java)
    }

    @Test
    fun `hostname preview includes old duplicates and exact Falcon or legacy names without deleting`() {
        val hostname = "preview-${System.nanoTime()}"
        val first = assets.save(TestDataFactory.createAsset(name = "display-$hostname").apply {
            crowdStrikeHostname = hostname
            cloudAccountId = "012345678901"
            cloudInstanceId = "i-0123456789abcdef0"
            lastSeen = LocalDateTime.now().minusDays(30)
        })
        val second = assets.save(TestDataFactory.createAsset(name = hostname))
        val suffix = assets.save(TestDataFactory.createAsset(name = "$hostname.example.test"))
        val response = preview("  ${hostname.uppercase()}, $hostname, missing-$hostname, , display-$hostname")
        assertThat(response.assets.map { it.id }).containsExactly(first.id, second.id)
        assertThat(response.assets.first().cloudAccountId).isEqualTo("012345678901")
        assertThat(response.assets.first().cloudInstanceId).isEqualTo("i-0123456789abcdef0")
        assertThat(response.unmatchedHostnames).containsExactly("missing-$hostname", "display-$hostname")
        for (row in listOf(first, second, suffix)) {
            assertThat(assets.existsById(requireNotNull(row.id))).isTrue()
            assertThat(audits.countByAssetId(requireNotNull(row.id))).isZero()
        }
        val deleted = delete(response.assets.map { it.id })
        assertThat(deleted.results.all { it.deleted }).isTrue()
        assertThat(assets.existsById(requireNotNull(suffix.id))).isTrue()
    }

    @Test
    fun `hostname preview raw JSON includes both arrays when either is empty`() {
        val hostname = "serialized-${System.nanoTime()}"
        assets.save(TestDataFactory.createAsset(name = hostname))
        val auth = token()

        val allMatchedRequest = HttpRequest.POST(
            "/api/assets/deletion-preview",
            AssetController.HostnameDeletionPreviewRequest(hostname)
        ).bearerAuth(auth)
        val allMatchedJson = client.toBlocking().exchange(allMatchedRequest, String::class.java).body()
        assertThat(allMatchedJson).contains("\"assets\":[").contains("\"unmatchedHostnames\":[]")

        val missingHostname = "missing-$hostname"
        val noMatchRequest = HttpRequest.POST(
            "/api/assets/deletion-preview",
            AssetController.HostnameDeletionPreviewRequest(missingHostname)
        ).bearerAuth(auth)
        val noMatchJson = client.toBlocking().exchange(noMatchRequest, String::class.java).body()
        assertThat(noMatchJson).contains("\"assets\":[]")
            .contains("\"unmatchedHostnames\":[\"$missingHostname\"]")
    }

    @Test
    fun `hostname preview rejects non administrators and invalid lists`() {
        for (auth in listOf(null, token("USER"), token("SECCHAMPION"))) {
            val failure = assertThrows<HttpClientResponseException> { preview("host", auth) }
            assertThat(failure.status).isEqualTo(if (auth == null) HttpStatus.UNAUTHORIZED else HttpStatus.FORBIDDEN)
        }
        val auth = token()
        for (input in listOf("", " , , ", "a".repeat(256), "two hosts", "host\nother",
                (1..251).joinToString(",") { "host-$it" }, "a".repeat(64001))) {
            assertThat(assertThrows<HttpClientResponseException> { preview(input, auth) }.status)
                .isEqualTo(HttpStatus.BAD_REQUEST)
        }
    }

    @Test
    fun `hostname preview rejects oversized results instead of offering a partial deletion`() {
        val hostname = "many-${System.nanoTime()}"
        val rows = assets.saveAll((1..251).map { TestDataFactory.createAsset(name = hostname) }).toList()
        assertThat(assertThrows<HttpClientResponseException> { preview(hostname) }.status)
            .isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(rows.all { assets.existsById(requireNotNull(it.id)) }).isTrue()
    }

    @Test
    fun `wildcards in hostname preview are literals`() {
        val row = asset()
        assertThat(preview("%, _").assets).isEmpty()
        assertThat(assets.existsById(requireNotNull(row.id))).isTrue()
    }

}
