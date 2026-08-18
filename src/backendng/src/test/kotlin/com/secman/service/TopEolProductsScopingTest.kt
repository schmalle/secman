package com.secman.service

import com.secman.domain.Asset
import com.secman.repository.EolFindingRepository
import io.micronaut.data.model.Pageable
import io.micronaut.security.authentication.Authentication
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Access scoping for the "Most Often EOL Products" card.
 *
 * The ranking reads `eol_finding`, a table with no owner column of its own — the
 * boundary is entirely the asset id set the caller resolves first. That makes the
 * three-way branch in [VulnerabilityStatisticsService.getTopEolProducts] the whole
 * access control story, and its middle case the dangerous one: `null` means ADMIN
 * (no restriction) while an *empty* set means "this user can see nothing". Collapsing
 * the two — the natural `if (ids.isNullOrEmpty()) queryEverything()` — would hand the
 * full estate to precisely the user entitled to none of it, and would still pass every
 * test whose fixture happens to give the user an asset.
 *
 * ID prefix: TEP-*
 */
class TopEolProductsScopingTest {

    private val eolFindingRepository = mockk<EolFindingRepository>(relaxed = true)
    private val assetFilterService = mockk<AssetFilterService>()

    private val service = VulnerabilityStatisticsService(
        vulnerabilityRepository = mockk(relaxed = true),
        eolFindingRepository = eolFindingRepository,
        assetFilterService = assetFilterService,
        entityManager = mockk(relaxed = true),
        statisticsCacheService = mockk(relaxed = true),
        objectMapper = mockk(relaxed = true)
    )

    private fun auth(vararg roles: String): Authentication =
        mockk<Authentication>().also { every { it.roles } returns roles.toList() }

    private fun asset(id: Long, domain: String? = null, cloudAccountId: String? = null) =
        Asset(
            name = "asset-$id",
            type = "SERVER",
            owner = "owner-$id",
            adDomain = domain,
            cloudAccountId = cloudAccountId
        ).also { it.id = id }

    /** `[componentName, eolAssets, approachingAssets, eolCycles]` as the query returns it. */
    private fun row(name: String, eol: Long, approaching: Long, cycles: Long): Array<Any> =
        arrayOf(name, eol, approaching, cycles)

    @Test
    @DisplayName("TEP-001: ADMIN gets the unscoped query, never a bounded id list")
    fun adminUsesGlobalQuery() {
        every { eolFindingRepository.topEolProductsForAll(any()) } returns
            listOf(row("Internet Explorer", 706L, 0L, 1L))

        val result = service.getTopEolProducts(auth("ADMIN"))

        assertThat(result).hasSize(1)
        verify(exactly = 1) { eolFindingRepository.topEolProductsForAll(any()) }
        verify(exactly = 0) { eolFindingRepository.topEolProductsForAssets(any(), any()) }
    }

    @Test
    @DisplayName("TEP-002: a regular user is restricted to their own accessible asset ids")
    fun regularUserIsScoped() {
        every { assetFilterService.getAccessibleAssets(any()) } returns listOf(asset(7), asset(9))
        every { eolFindingRepository.topEolProductsForAssets(any(), any()) } returns
            listOf(row("SQL Server", 3L, 1L, 2L))

        val result = service.getTopEolProducts(auth("USER"))

        assertThat(result).hasSize(1)
        verify(exactly = 0) { eolFindingRepository.topEolProductsForAll(any()) }
        verify(exactly = 1) {
            eolFindingRepository.topEolProductsForAssets(
                match { it.toSet() == setOf(7L, 9L) },
                any()
            )
        }
    }

    @Test
    @DisplayName("TEP-003: a user with no accessible assets gets nothing — and never reaches a query")
    fun noAccessibleAssetsReturnsEmpty() {
        // The regression this pins: an empty id set must not be treated as "unfiltered".
        every { assetFilterService.getAccessibleAssets(any()) } returns emptyList()

        val result = service.getTopEolProducts(auth("USER"))

        assertThat(result).isEmpty()
        verify(exactly = 0) { eolFindingRepository.topEolProductsForAll(any()) }
        verify(exactly = 0) { eolFindingRepository.topEolProductsForAssets(any(), any()) }
    }

    @Test
    @DisplayName("TEP-004: a domain filter narrows an ADMIN off the unscoped query too")
    fun adminWithDomainFilterIsScoped() {
        // Filters are additional constraints, never a bypass — so a filtered ADMIN
        // must leave the global path even though they have universal access.
        every { assetFilterService.getAccessibleAssets(any()) } returns
            listOf(asset(1, domain = "corp.example"), asset(2, domain = "other.example"))
        every { eolFindingRepository.topEolProductsForAssets(any(), any()) } returns emptyList()

        service.getTopEolProducts(auth("ADMIN"), domain = "CORP.EXAMPLE")

        verify(exactly = 0) { eolFindingRepository.topEolProductsForAll(any()) }
        verify(exactly = 1) {
            eolFindingRepository.topEolProductsForAssets(match { it.toSet() == setOf(1L) }, any())
        }
    }

    @Test
    @DisplayName("TEP-005: awsHosted keeps only assets carrying a cloud account id")
    fun awsHostedFilterNarrows() {
        every { assetFilterService.getAccessibleAssets(any()) } returns
            listOf(asset(1, cloudAccountId = "123456789012"), asset(2), asset(3, cloudAccountId = " "))
        every { eolFindingRepository.topEolProductsForAssets(any(), any()) } returns emptyList()

        service.getTopEolProducts(auth("USER"), awsHosted = true)

        // asset 3's blank account id is not a cloud account.
        verify(exactly = 1) {
            eolFindingRepository.topEolProductsForAssets(match { it.toSet() == setOf(1L) }, any())
        }
    }

    @Test
    @DisplayName("TEP-006: rows map onto the DTO in order, and the ranking is capped at 10")
    fun mapsRowsAndCapsAtTen() {
        every { eolFindingRepository.topEolProductsForAll(any()) } returns listOf(
            row("Universal Forwarder", 292L, 1L, 4L),
            row("Firefox", 73L, 6L, 18L)
        )

        val result = service.getTopEolProducts(auth("ADMIN"))

        assertThat(result.map { it.product }).containsExactly("Universal Forwarder", "Firefox")
        assertThat(result[0].affectedAssets).isEqualTo(292L)
        assertThat(result[0].approachingAssets).isEqualTo(1L)
        assertThat(result[0].eolVersions).isEqualTo(4L)
        assertThat(result[1].eolVersions).isEqualTo(18L)

        // The limit lives at the query, not in a Kotlin .take() after the fact.
        verify { eolFindingRepository.topEolProductsForAll(Pageable.from(0, 10)) }
    }

    @Test
    @DisplayName("TEP-007: a row with no component name is dropped rather than rendered blank")
    fun dropsNamelessRows() {
        every { eolFindingRepository.topEolProductsForAll(any()) } returns listOf(
            row("", 5L, 0L, 1L),
            row("   ", 4L, 0L, 1L),
            row("Chrome Installer", 48L, 0L, 1L)
        )

        val result = service.getTopEolProducts(auth("ADMIN"))

        assertThat(result.map { it.product }).containsExactly("Chrome Installer")
    }
}
