package com.secman.controller

import com.secman.domain.Asset
import com.secman.domain.User
import com.secman.domain.Workgroup
import com.secman.repository.AssetRepository
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupRepository
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestAuthHelper
import com.secman.testutil.TestDataFactory
import io.micronaut.http.HttpRequest
import io.micronaut.core.type.Argument
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.json.JsonMapper
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@MicronautTest(environments = ["test"], transactional = false)
class AssetOverviewControllerTest : BaseIntegrationTest() {
    @Inject @field:Client("/") lateinit var client: HttpClient
    @Inject lateinit var assetRepository: AssetRepository
    @Inject lateinit var userRepository: UserRepository
    @Inject lateinit var workgroupRepository: WorkgroupRepository
    @Inject lateinit var jsonMapper: JsonMapper

    private lateinit var admin: User
    private lateinit var viewer: User
    private lateinit var token: String
    private lateinit var asset: Asset
    private var workgroup: Workgroup? = null

    @BeforeEach
    fun setup() {
        val suffix = System.nanoTime()
        admin = userRepository.save(TestDataFactory.createAdminUser("ip-admin-$suffix", "ip-admin-$suffix@test.com"))
        viewer = userRepository.save(TestDataFactory.createRegularUser("ip-viewer-$suffix", "ip-viewer-$suffix@test.com"))
        token = TestAuthHelper.getAuthToken(client, admin.username)
        asset = assetRepository.save(TestDataFactory.createAsset(name = "multi-ip-$suffix", ip = "10.4.0.10").apply {
            ipAddresses = mutableSetOf("10.4.0.10", "10.77.32.9", "10.8.0.10", "2001:db8::9")
        })
    }

    @AfterEach
    fun cleanup() {
        if (workgroup != null) {
            asset.workgroups = mutableSetOf()
            assetRepository.update(asset)
        }
        assetRepository.delete(asset)
        workgroup?.let(workgroupRepository::delete)
        workgroup = null
        userRepository.delete(admin)
        userRepository.delete(viewer)
    }

    private fun search(ip: String? = null, page: Int = 0, auth: String = token): Map<*, *> {
        val query = "/api/assets/search?page=$page&pageSize=25&name=${asset.name}" + (ip?.let { "&ip=$it" } ?: "")
        val body = client.toBlocking().retrieve(HttpRequest.GET<Any>(query).bearerAuth(auth))
        return requireNotNull(jsonMapper.readValue(body, Map::class.java))
    }

    @Test
    fun `overview returns every address and secondary search counts each asset once`() {
        for (ip in listOf(null, "10.77.32.9", "10.", "2001:db8::9")) {
            val response = search(ip)
            assertThat((response["matchingCount"] as Number).toLong()).isEqualTo(1L)
            val row = (response["items"] as List<*>).single() as Map<*, *>
            assertThat(row["ipAddress"]).isEqualTo("10.4.0.10")
            assertThat(row["ipAddresses"] as List<*>).containsExactly("10.4.0.10", "10.77.32.9", "10.8.0.10", "2001:db8::9")
        }
    }

    @Test
    fun `empty and out of range pages always serialize an items array`() {
        val missing = search("192.0.2.254")
        assertThat(missing["items"] as List<*>).isEmpty()
        assertThat((missing["matchingCount"] as Number).toLong()).isZero()
        val pastEnd = search(page = 1)
        assertThat(pastEnd["items"] as List<*>).isEmpty()
        assertThat((pastEnd["matchingCount"] as Number).toLong()).isEqualTo(1L)
    }

    @Test
    fun `primary IP edits preserve other reported addresses`() {
        for (ip in listOf("10.4.0.10", "10.4.0.11", "")) {
            val body = client.toBlocking().retrieve(HttpRequest.PUT("/api/assets/${asset.id}", mapOf("ip" to ip)).bearerAuth(token))
            val updated = requireNotNull(jsonMapper.readValue(body, Map::class.java))
            val addresses = updated["ipAddresses"] as List<*>
            assertThat(addresses).contains("10.4.0.10", "10.77.32.9", "10.8.0.10", "2001:db8::9")
            if (ip != "10.4.0.10") assertThat(addresses).contains("10.4.0.11")
        }
    }

    @Test
    fun `secondary IP matching cannot reveal an inaccessible asset`() {
        val response = search("10.77.32.9", auth = TestAuthHelper.getAuthToken(client, viewer.username))
        assertThat((response["matchingCount"] as Number).toLong()).isZero()
        assertThat(response["items"] as List<*>).isEmpty()
    }

    @Test
    fun `workgroup asset details include every reported address`() {
        val group = workgroupRepository.save(Workgroup(name = "ip-group-${System.nanoTime()}"))
        workgroup = group
        asset.workgroups.add(group)
        assetRepository.update(asset)
        val body = client.toBlocking().retrieve(HttpRequest.GET<Any>("/api/workgroups/${group.id}/assets").bearerAuth(token))
        val rows = requireNotNull(jsonMapper.readValue(body, Argument.listOf(AssignedAssetDto::class.java)))
        assertThat(rows.single().ipAddresses).containsExactly("10.4.0.10", "10.77.32.9", "10.8.0.10", "2001:db8::9")
    }
}
