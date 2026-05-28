package com.secman.service

import com.secman.crowdstrike.dto.InstalledProductDto
import com.secman.domain.Asset
import com.secman.domain.InstalledProduct
import com.secman.repository.AssetRepository
import com.secman.repository.InstalledProductRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InstalledProductImportServiceTest {
    private lateinit var assetRepository: AssetRepository
    private lateinit var installedProductRepository: InstalledProductRepository
    private lateinit var service: InstalledProductImportService

    @BeforeEach
    fun setUp() {
        assetRepository = mockk()
        installedProductRepository = mockk()
        service = InstalledProductImportService(assetRepository, installedProductRepository)
    }

    @Test
    fun `imports product for known asset`() {
        val asset = asset(1L, "server01")
        every { assetRepository.findByNameIgnoreCase("server01.example.com") } returns null
        every { assetRepository.findByNameIgnoreCase("server01") } returns asset
        every { installedProductRepository.findByExternalId("app-1") } returns null
        every { installedProductRepository.findLogicalDuplicate(1L, "Chrome", "Google", "1.2.3") } returns null
        val saved = slot<InstalledProduct>()
        every { installedProductRepository.save(capture(saved)) } answers { saved.captured.apply { id = 10L } }

        val result = service.importProducts(
            listOf(InstalledProductDto(externalId = "app-1", hostname = "server01.example.com", name = "Chrome", vendor = "Google", version = "1.2.3")),
            dryRun = false
        )

        assertThat(result.productsProcessed).isEqualTo(1)
        assertThat(result.productsImported).isEqualTo(1)
        assertThat(result.productsUpdated).isEqualTo(0)
        assertThat(saved.captured.asset.name).isEqualTo("server01")
        assertThat(saved.captured.name).isEqualTo("Chrome")
    }

    @Test
    fun `dry run counts known products without saving`() {
        val asset = asset(2L, "workstation01")
        every { assetRepository.findByNameIgnoreCase("workstation01") } returns asset

        val result = service.importProducts(
            listOf(InstalledProductDto(hostname = "workstation01", name = "Firefox")),
            dryRun = true
        )

        assertThat(result.dryRun).isTrue()
        assertThat(result.productsImported).isEqualTo(1)
        verify(exactly = 0) { installedProductRepository.save(any()) }
        verify(exactly = 0) { installedProductRepository.update(any<InstalledProduct>()) }
    }

    @Test
    fun `skips products for unknown systems`() {
        every { assetRepository.findByNameIgnoreCase("unknown") } returns null

        val result = service.importProducts(
            listOf(InstalledProductDto(hostname = "unknown", name = "Chrome")),
            dryRun = false
        )

        assertThat(result.productsSkipped).isEqualTo(1)
        assertThat(result.unknownSystems).isEqualTo(1)
    }

    private fun asset(id: Long, name: String): Asset = Asset(
        id = id,
        name = name,
        type = "SERVER",
        owner = "owner"
    )
}
