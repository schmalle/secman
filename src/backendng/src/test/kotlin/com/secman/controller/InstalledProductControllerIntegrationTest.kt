package com.secman.controller

import com.secman.domain.InstalledProduct
import com.secman.domain.User
import com.secman.domain.Workgroup
import com.secman.dto.InstalledProductListResponse
import com.secman.repository.AssetRepository
import com.secman.repository.InstalledProductRepository
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupRepository
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestAuthHelper
import com.secman.testutil.TestDataFactory
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.data.model.Pageable
import jakarta.inject.Inject
import org.hibernate.Hibernate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf

@EnabledIf("com.secman.testutil.DockerAvailable#isDockerAvailable")
class InstalledProductControllerIntegrationTest : BaseIntegrationTest() {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var workgroupRepository: WorkgroupRepository

    @Inject
    lateinit var assetRepository: AssetRepository

    @Inject
    lateinit var installedProductRepository: InstalledProductRepository

    private lateinit var vulnUser: User

    @BeforeEach
    fun setUp() {
        val suffix = System.nanoTime()
        vulnUser = userRepository.save(TestDataFactory.createVulnUser("products-vuln-$suffix", "products-vuln-$suffix@test.com"))
    }

    @Test
    fun `scoped vuln user can list installed products with hostnames`() {
        val suffix = System.nanoTime()
        val workgroup = workgroupRepository.save(Workgroup(name = "Products WG $suffix"))
        vulnUser.workgroups.add(workgroup)
        userRepository.update(vulnUser)

        val asset = TestDataFactory.createAsset(name = "products-host-$suffix")
        asset.workgroups.add(workgroup)
        val savedAsset = assetRepository.save(asset)
        val savedProduct = installedProductRepository.save(
            InstalledProduct(
                asset = savedAsset,
                externalId = "product-$suffix",
                name = "Chrome",
                vendor = "Google",
                version = "126.0"
            )
        )
        val token = TestAuthHelper.getAuthToken(client, vulnUser.username)

        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/installed-products?limit=500").bearerAuth(token),
            InstalledProductListResponse::class.java
        )

        assertThat(response.status).isEqualTo(HttpStatus.OK)
        assertThat(response.body()!!.products).hasSize(1)
        val product = response.body()!!.products.single()
        assertThat(product.id).isEqualTo(savedProduct.id)
        assertThat(product.assetId).isEqualTo(savedAsset.id)
        assertThat(product.hostname).isEqualTo(savedAsset.name)
    }

    @Test
    fun `repository scoped list query fetches hostnames with products`() {
        val suffix = System.nanoTime()
        val asset = assetRepository.save(TestDataFactory.createAsset(name = "projection-host-$suffix"))
        val savedProduct = installedProductRepository.save(
            InstalledProduct(
                asset = asset,
                externalId = "projection-product-$suffix",
                name = "Firefox",
                vendor = "Mozilla",
                version = "127.0"
            )
        )

        val products = installedProductRepository.searchForAssetsWithAsset(
            search = "",
            assetIds = setOf(requireNotNull(asset.id)),
            pageable = Pageable.from(0, 500)
        )

        assertThat(products).hasSize(1)
        val product = products.single()
        assertThat(product.id).isEqualTo(savedProduct.id)
        assertThat(Hibernate.isInitialized(product.asset)).isTrue()
        assertThat(product.asset.id).isEqualTo(asset.id)
        assertThat(product.asset.name).isEqualTo(asset.name)
    }

    @Test
    fun `repository admin list query fetches hostnames with products`() {
        val suffix = System.nanoTime()
        val asset = assetRepository.save(TestDataFactory.createAsset(name = "admin-projection-host-$suffix"))
        val savedProduct = installedProductRepository.save(
            InstalledProduct(
                asset = asset,
                externalId = "admin-projection-product-$suffix",
                name = "Edge",
                vendor = "Microsoft",
                version = "126.0"
            )
        )

        val products = installedProductRepository.searchWithAsset(
            search = "admin-projection-host-$suffix",
            pageable = Pageable.from(0, 500)
        )

        assertThat(products).hasSize(1)
        val product = products.single()
        assertThat(product.id).isEqualTo(savedProduct.id)
        assertThat(Hibernate.isInitialized(product.asset)).isTrue()
        assertThat(product.asset.id).isEqualTo(asset.id)
        assertThat(product.asset.name).isEqualTo(asset.name)
    }
}
