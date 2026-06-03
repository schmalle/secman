package com.secman.service

import com.secman.domain.Asset
import com.secman.domain.InstalledProduct
import com.secman.repository.InstalledProductRepository
import io.micronaut.data.model.Pageable
import io.micronaut.security.authentication.Authentication
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InstalledProductListServiceTest {
    private lateinit var installedProductRepository: InstalledProductRepository
    private lateinit var accessibleAssetIdsCache: AccessibleAssetIdsCache
    private lateinit var service: InstalledProductListService

    @BeforeEach
    fun setUp() {
        installedProductRepository = mockk()
        accessibleAssetIdsCache = mockk()
        service = InstalledProductListService(installedProductRepository, accessibleAssetIdsCache)
    }

    @Test
    fun `listForServer returns products for matching server names for admins`() {
        val authentication = Authentication.build("admin", listOf("ADMIN"))
        val asset = asset(42L, "server01", "10.0.0.15")
        val chrome = product(100L, asset, "Chrome", "120.0")
        every { installedProductRepository.searchByServerWithAsset("server01", any<Pageable>()) } returns listOf(chrome)
        every { installedProductRepository.countDistinctAssetsByServer("server01") } returns 1L

        val result = service.listForServer(authentication, " server01 ", 50)

        assertThat(result.products).hasSize(1)
        assertThat(result.products[0].hostname).isEqualTo("server01")
        assertThat(result.products[0].name).isEqualTo("Chrome")
        assertThat(result.products[0].version).isEqualTo("120.0")
        assertThat(result.totalSystems).isEqualTo(1L)
        verify(exactly = 0) { accessibleAssetIdsCache.get(any()) }
    }

    @Test
    fun `listForServer scopes server results to accessible assets for non-admins`() {
        val authentication = Authentication.build("champion", listOf("SECCHAMPION"))
        val asset = asset(7L, "app-server", "10.0.0.7")
        val product = product(101L, asset, "OpenJDK", "21")
        every { accessibleAssetIdsCache.get(authentication) } returns setOf(7L)
        every { installedProductRepository.searchByServerForAssetsWithAsset("app", setOf(7L), any<Pageable>()) } returns listOf(product)
        every { installedProductRepository.countDistinctAssetsByServerForAssets("app", setOf(7L)) } returns 1L

        val result = service.listForServer(authentication, "app", 25)

        assertThat(result.products).extracting<String> { it.name }.containsExactly("OpenJDK")
        assertThat(result.totalSystems).isEqualTo(1L)
    }

    @Test
    fun `listForServer rejects blank server search terms`() {
        val authentication = Authentication.build("admin", listOf("ADMIN"))

        assertThatThrownBy { service.listForServer(authentication, "   ", 25) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Server search term is required")
    }

    private fun asset(id: Long, name: String, ip: String): Asset = Asset(
        id = id,
        name = name,
        type = "SERVER",
        ip = ip,
        owner = "owner"
    )

    private fun product(id: Long, asset: Asset, name: String, version: String): InstalledProduct = InstalledProduct(
        id = id,
        asset = asset,
        name = name,
        version = version
    )
}
