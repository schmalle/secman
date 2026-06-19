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
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Inject
import org.hibernate.Hibernate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection

@MicronautTest(environments = ["test"], transactional = false)
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

    @Inject
    lateinit var transactionOperations: TransactionOperations<Connection>

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

        val asset = TestDataFactory.createAsset(name = "products-host-$suffix")
        val savedAsset = assetRepository.save(asset)
        assignUserToWorkgroup(vulnUser.id!!, workgroup.id!!)
        assignAssetToWorkgroup(savedAsset.id!!, workgroup.id!!)
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
    fun `re-import with new run id replaces a server's products with a clean snapshot`() {
        val suffix = System.nanoTime()
        val asset = assetRepository.save(TestDataFactory.createAsset(name = "replace-host-$suffix"))
        val hostname = asset.name
        val token = TestAuthHelper.getAuthToken(client, vulnUser.username)

        // First run: import two products for the server.
        val first = importProducts(
            token,
            importRunId = "run-A-$suffix",
            products = listOf(
                mapOf("hostname" to hostname, "name" to "Chrome", "vendor" to "Google"),
                mapOf("hostname" to hostname, "name" to "Firefox", "vendor" to "Mozilla")
            )
        )
        assertThat((first["productsImported"] as Number).toInt()).isEqualTo(2)

        // Second run: import only one product; the other must be removed for a clean snapshot.
        val second = importProducts(
            token,
            importRunId = "run-B-$suffix",
            products = listOf(
                mapOf("hostname" to hostname, "name" to "Chrome", "vendor" to "Google")
            )
        )
        assertThat((second["productsDeleted"] as Number).toInt()).isGreaterThanOrEqualTo(1)

        val remaining = installedProductRepository.searchByServerForAssetsWithAsset(
            server = hostname,
            assetIds = setOf(requireNotNull(asset.id)),
            pageable = Pageable.from(0, 500)
        )
        assertThat(remaining).hasSize(1)
        assertThat(remaining.single().name).isEqualTo("Chrome")
    }

    @Suppress("UNCHECKED_CAST")
    private fun importProducts(token: String, importRunId: String, products: List<Map<String, Any?>>): Map<String, Any> {
        val body = mapOf("products" to products, "dryRun" to false, "importRunId" to importRunId)
        val response = client.toBlocking().exchange(
            HttpRequest.POST("/api/installed-products/import", body).bearerAuth(token),
            Map::class.java
        )
        assertThat(response.status).isEqualTo(HttpStatus.OK)
        return response.body() as Map<String, Any>
    }

    private fun assignUserToWorkgroup(userId: Long, workgroupId: Long) {
        transactionOperations.executeWrite<Unit> { status ->
            status.connection.prepareStatement("INSERT INTO user_workgroups (user_id, workgroup_id) VALUES (?, ?)").use { ps ->
                ps.setLong(1, userId)
                ps.setLong(2, workgroupId)
                ps.executeUpdate()
            }
        }
    }

    private fun assignAssetToWorkgroup(assetId: Long, workgroupId: Long) {
        transactionOperations.executeWrite<Unit> { status ->
            status.connection.prepareStatement("INSERT INTO asset_workgroups (asset_id, workgroup_id) VALUES (?, ?)").use { ps ->
                ps.setLong(1, assetId)
                ps.setLong(2, workgroupId)
                ps.executeUpdate()
            }
        }
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
