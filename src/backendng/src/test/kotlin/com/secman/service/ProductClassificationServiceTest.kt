package com.secman.service

import com.secman.domain.ProductClass
import com.secman.domain.ProductClassificationRule
import com.secman.domain.RuleMatchField
import com.secman.repository.EolFindingRepository
import com.secman.repository.InstalledProductRepository
import com.secman.repository.ProductClassificationRuleRepository
import com.secman.repository.VulnerabilityRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ProductClassificationServiceTest {

    private lateinit var ruleRepository: ProductClassificationRuleRepository
    private lateinit var vulnerabilityRepository: VulnerabilityRepository
    private lateinit var installedProductRepository: InstalledProductRepository
    private lateinit var eolFindingRepository: EolFindingRepository
    private lateinit var service: ProductClassificationService

    private val seedRules = listOf(
        ProductClassificationRule(
            id = 1, matchField = RuleMatchField.PRODUCT_NAME, pattern = "app installer*",
            classification = ProductClass.INSTALLED, priority = 0
        ),
        ProductClassificationRule(
            id = 2, matchField = RuleMatchField.PRODUCT_NAME, pattern = "* installer*",
            classification = ProductClass.INSTALLER_ARTIFACT, priority = 100
        )
    )

    @BeforeEach
    fun setUp() {
        ruleRepository = mockk()
        vulnerabilityRepository = mockk(relaxed = true)
        installedProductRepository = mockk(relaxed = true)
        eolFindingRepository = mockk(relaxed = true)
        service = ProductClassificationService(
            ruleRepository, vulnerabilityRepository, installedProductRepository, eolFindingRepository
        )
        every { ruleRepository.findEnabledOrdered() } returns seedRules
    }

    @Test
    fun `recomputeForAsset resets to visible then marks only the artifact products`() {
        every { vulnerabilityRepository.findDistinctProductsByAssetId(7L) } returns listOf(
            "Chrome Installer 1.0",
            "Google Chrome 120.0",
            "App Installer 1.21.3"
        )

        service.recomputeForAsset(7L)

        // The reset always runs first, so a product that stops matching becomes visible again.
        verify(exactly = 1) { vulnerabilityRepository.resetProductClassForAsset(7L) }
        verify(exactly = 1) {
            vulnerabilityRepository.markProductClassArtifactForAsset(7L, listOf("Chrome Installer 1.0"))
        }
    }

    @Test
    fun `recomputeForAsset still resets when no product is an artifact`() {
        every { vulnerabilityRepository.findDistinctProductsByAssetId(9L) } returns listOf("Google Chrome 120.0")

        service.recomputeForAsset(9L)

        verify(exactly = 1) { vulnerabilityRepository.resetProductClassForAsset(9L) }
        verify(exactly = 0) { vulnerabilityRepository.markProductClassArtifactForAsset(any(), any()) }
    }

    @Test
    fun `with no rules every row is reset to visible and nothing is marked`() {
        every { ruleRepository.findEnabledOrdered() } returns emptyList()

        service.recomputeForAsset(3L)

        verify(exactly = 1) { vulnerabilityRepository.resetProductClassForAsset(3L) }
        verify(exactly = 0) { vulnerabilityRepository.findDistinctProductsByAssetId(any()) }
        verify(exactly = 0) { vulnerabilityRepository.markProductClassArtifactForAsset(any(), any()) }
    }

    @Test
    fun `rules are compiled once and cached until invalidated`() {
        service.rules()
        service.rules()
        verify(exactly = 1) { ruleRepository.findEnabledOrdered() }

        service.invalidateRules()
        service.rules()
        verify(exactly = 2) { ruleRepository.findEnabledOrdered() }
    }

    @Test
    fun `classifyProductName drives the admin test box`() {
        assertThat(service.classifyProductName("Chrome Installer")).isEqualTo(ProductClass.INSTALLER_ARTIFACT)
        assertThat(service.classifyProductName("App Installer")).isEqualTo(ProductClass.INSTALLED)
        assertThat(service.classifyProductName("Google Chrome")).isEqualTo(ProductClass.INSTALLED)
    }
}
