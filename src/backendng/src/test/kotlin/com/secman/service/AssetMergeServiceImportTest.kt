package com.secman.service

import com.secman.domain.Asset
import com.secman.repository.AssetRepository
import com.secman.repository.AssetTagRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AssetMergeServiceImportTest {

    private lateinit var assetRepository: AssetRepository
    private lateinit var assetTagRepository: AssetTagRepository
    private lateinit var service: AssetMergeService

    @BeforeEach
    fun setUp() {
        assetRepository = mockk()
        assetTagRepository = mockk(relaxed = true)
        service = AssetMergeService(assetRepository, assetTagRepository)
    }

    @Test
    fun `import asset creates asset with cloud fields`() {
        val savedAsset = slot<Asset>()
        every { assetRepository.findByNameIgnoreCase("i-abc123") } returns null
        every { assetRepository.save(capture(savedAsset)) } answers { savedAsset.captured.apply { id = 42L } }

        val (asset, created) = service.importAsset(
            name = "i-abc123",
            type = "SERVER",
            owner = "AWS Asset Inventory",
            description = "Auto-created by asset-match-clear --check-fix from AWS snapshot",
            cloudAccountId = "123456789012",
            cloudInstanceId = "i-abc123"
        )

        assertThat(created).isTrue()
        assertThat(asset.cloudAccountId).isEqualTo("123456789012")
        assertThat(asset.cloudInstanceId).isEqualTo("i-abc123")
        assertThat(savedAsset.captured.cloudAccountId).isEqualTo("123456789012")
        assertThat(savedAsset.captured.cloudInstanceId).isEqualTo("i-abc123")
    }

    @Test
    fun `import asset updates existing cloud fields only when provided`() {
        val existing = Asset(
            id = 7L,
            name = "i-abc123",
            type = "SERVER",
            owner = "Existing Owner",
            description = "Existing description",
            cloudAccountId = null,
            cloudInstanceId = null
        )
        every { assetRepository.findByNameIgnoreCase("i-abc123") } returns existing
        every { assetRepository.update(existing) } returns existing

        val (asset, created) = service.importAsset(
            name = "i-abc123",
            type = "IGNORED",
            owner = "Ignored Owner",
            cloudAccountId = "123456789012",
            cloudInstanceId = "i-abc123"
        )

        assertThat(created).isFalse()
        assertThat(asset.owner).isEqualTo("Existing Owner")
        assertThat(asset.type).isEqualTo("SERVER")
        assertThat(asset.description).isEqualTo("Existing description")
        assertThat(asset.cloudAccountId).isEqualTo("123456789012")
        assertThat(asset.cloudInstanceId).isEqualTo("i-abc123")
        verify { assetRepository.update(existing) }
    }

    @Test
    fun `import asset creates asset with uri`() {
        val savedAsset = slot<Asset>()
        every { assetRepository.findByNameIgnoreCase("portal") } returns null
        every { assetRepository.save(capture(savedAsset)) } answers { savedAsset.captured.apply { id = 43L } }

        val (asset, created) = service.importAsset(
            name = "portal",
            type = "URI",
            owner = "App Team",
            uri = "https://portal.example.com"
        )

        assertThat(created).isTrue()
        assertThat(asset.uri).isEqualTo("https://portal.example.com")
        assertThat(savedAsset.captured.uri).isEqualTo("https://portal.example.com")
    }

    @Test
    fun `import asset updates existing uri only when provided`() {
        val existing = Asset(
            id = 8L,
            name = "portal",
            type = "URI",
            owner = "Existing Owner",
            uri = "https://old.example.com"
        )
        every { assetRepository.findByNameIgnoreCase("portal") } returns existing
        every { assetRepository.update(existing) } returns existing

        val (asset, created) = service.importAsset(
            name = "portal",
            type = "IGNORED",
            owner = "Ignored Owner",
            uri = "https://new.example.com"
        )

        assertThat(created).isFalse()
        assertThat(asset.uri).isEqualTo("https://new.example.com")
        verify { assetRepository.update(existing) }
    }

}
