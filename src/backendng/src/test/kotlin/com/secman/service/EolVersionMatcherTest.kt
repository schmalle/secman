package com.secman.service

import com.secman.domain.EolStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * [EolVersionMatcher] decides whether a production system is reported as running
 * end-of-life software. Both failure directions are expensive: a false positive
 * puts a healthy server on an owner's remediation list and erodes trust in the
 * report, a false negative hides an unsupported OS. The matcher is pure, so all
 * of that is testable without a database.
 *
 * `junit-jupiter-params` is not on this classpath (CLAUDE.md §Test
 * Infrastructure), so table-driven cases loop inside a plain `@Test`.
 *
 * ID prefix: EVM-*
 */
class EolVersionMatcherTest {

    private val matcher = EolVersionMatcher()
    private val today: LocalDate = LocalDate.of(2026, 8, 10)

    // ------------------------------------------------------------------ fixture

    private fun release(
        id: Long,
        cycle: String,
        eol: String?,
        alreadyEol: Boolean = false,
        unknown: Boolean = false
    ) = EolVersionMatcher.CatalogRelease(
        releaseId = id,
        cycle = cycle,
        label = null,
        eolDate = eol?.let { LocalDate.parse(it) },
        alreadyEol = alreadyEol,
        eolUnknown = unknown,
        lts = false
    )

    private fun product(
        id: Long,
        key: String,
        label: String,
        aliases: Set<String> = emptySet(),
        releases: List<EolVersionMatcher.CatalogRelease>
    ) = EolVersionMatcher.CatalogProduct(
        productId = id,
        productKey = key,
        label = label,
        category = null,
        aliases = aliases,
        releases = releases
    )

    private val ubuntu = product(
        1, "ubuntu", "Ubuntu",
        releases = listOf(
            release(11, "20.04", "2025-05-29"),
            release(12, "22.04", "2027-06-01"),
            release(13, "24.04", "2029-06-01")
        )
    )

    private val windowsServer = product(
        2, "windows-server", "Windows Server",
        releases = listOf(
            release(21, "2012", "2023-10-10"),
            release(22, "2019", "2029-01-09"),
            release(23, "2022", "2031-10-14")
        )
    )

    private val rhel = product(
        3, "rhel", "Red Hat Enterprise Linux",
        releases = listOf(
            release(31, "7", "2024-06-30"),
            release(32, "8", "2029-05-31"),
            release(33, "9", "2032-05-31")
        )
    )

    private val chrome = product(
        4, "googlechrome", "Google Chrome",
        releases = listOf(
            release(41, "119", "2023-11-29"),
            release(42, "120", "2023-12-20")
        )
    )

    private val index = EolVersionMatcher.Index.build(listOf(ubuntu, windowsServer, rhel, chrome))

    // ------------------------------------------------------------------- OS path

    @Test
    @DisplayName("EVM-001: resolves a distro name plus trailing version to the right cycle")
    fun resolvesOsNameAndVersion() {
        val cases = listOf(
            "Ubuntu 20.04.6 LTS" to "20.04",
            "ubuntu 22.04" to "22.04",
            "Ubuntu Linux 24.04.1" to "24.04",
            "Red Hat Enterprise Linux 8.6" to "8",
            "Windows Server 2019" to "2019",
            "Microsoft Windows Server 2012 R2" to "2012"
        )
        cases.forEach { (osVersion, expectedCycle) ->
            val match = matcher.matchOs(index, osVersion, today, 12)
            assertThat(match).describedAs("os %s", osVersion).isNotNull
            assertThat(match!!.release.cycle).describedAs("os %s", osVersion).isEqualTo(expectedCycle)
        }
    }

    @Test
    @DisplayName("EVM-002: a product name whose version is embedded is matched from the name, not the version field")
    fun embeddedVersionWinsOverVersionField() {
        // Windows Server reports a build number ("10.0.17763") as its version.
        // Preferring that over the "2019" in the name would match no cycle at all.
        val match = matcher.matchComponent(index, "Windows Server 2019", "Microsoft", "10.0.17763", today, 12)

        assertThat(match).isNotNull
        assertThat(match!!.product.productKey).isEqualTo("windows-server")
        assertThat(match.release.cycle).isEqualTo("2019")
    }

    @Test
    @DisplayName("EVM-003: an unknown or unparseable OS string produces no finding rather than a guess")
    fun refusesToGuess() {
        listOf(
            null,
            "",
            "   ",
            "SomeAppliance OS",          // no catalogue product
            "Ubuntu",                    // product but no version
            "Ubuntu unknown-release",    // product but no parseable version
            "Ubuntu 19.10"               // version with no matching cycle
        ).forEach { osVersion ->
            assertThat(matcher.matchOs(index, osVersion, today, 12))
                .describedAs("os %s", osVersion)
                .isNull()
        }
    }

    // ------------------------------------------------------------- component path

    @Test
    @DisplayName("EVM-004: vendor widens the candidate name but is never required")
    fun vendorWidensCandidates() {
        val withVendor = matcher.matchComponent(index, "Chrome", "Google", "120.0.6099.109", today, 12)
        val withoutVendor = matcher.matchComponent(index, "Google Chrome", null, "120.0.6099.109", today, 12)

        assertThat(withVendor?.product?.productKey).isEqualTo("googlechrome")
        assertThat(withoutVendor?.product?.productKey).isEqualTo("googlechrome")
    }

    @Test
    @DisplayName("EVM-005: architecture and edition noise in a product name does not block the match")
    fun ignoresPackagingNoise() {
        listOf(
            "Google Chrome (64-bit)",
            "Google Chrome x64",
            "Google Chrome 64-bit Edition"
        ).forEach { name ->
            assertThat(matcher.matchComponent(index, name, null, "120.1", today, 12))
                .describedAs("name %s", name)
                .isNotNull
        }
    }

    // ------------------------------------------------------------- cycle resolution

    @Test
    @DisplayName("EVM-006: the longest dot-segment prefix wins and a partial segment never matches")
    fun longestPrefixWins() {
        val product = product(
            9, "demo", "Demo",
            releases = listOf(
                release(91, "4", "2020-01-01"),
                release(92, "4.1", "2021-01-01"),
                release(93, "4.17", "2022-01-01")
            )
        )
        assertThat(matcher.resolveRelease(product, "4.17.21")?.cycle).isEqualTo("4.17")
        assertThat(matcher.resolveRelease(product, "4.1.9")?.cycle).isEqualTo("4.1")
        assertThat(matcher.resolveRelease(product, "4.2")?.cycle).isEqualTo("4")
        // "4.1" must NOT match the observed "4.10" — segments compare whole, not
        // as strings, or every 4.10.x install is misreported as cycle 4.1.
        assertThat(matcher.resolveRelease(product, "4.10")?.cycle).isEqualTo("4")
    }

    // -------------------------------------------------------------- classification

    @Test
    @DisplayName("EVM-007: classifies against the horizon boundary inclusively")
    fun classifiesAgainstHorizon() {
        val past = matcher.classify(ubuntu, release(1, "x", "2025-05-29"), today, 12)
        val boundary = matcher.classify(ubuntu, release(2, "x", "2027-08-10"), today, 12)
        val justInside = matcher.classify(ubuntu, release(3, "x", "2027-08-09"), today, 12)
        val outside = matcher.classify(ubuntu, release(4, "x", "2027-08-11"), today, 12)

        assertThat(past.status).isEqualTo(EolStatus.EOL)
        assertThat(boundary.status).isEqualTo(EolStatus.APPROACHING_EOL)
        assertThat(justInside.status).isEqualTo(EolStatus.APPROACHING_EOL)
        assertThat(outside.status).isEqualTo(EolStatus.SUPPORTED)
    }

    @Test
    @DisplayName("EVM-008: an EOL date of today counts as already end of life, not as approaching")
    fun todayIsAlreadyEol() {
        val match = matcher.classify(ubuntu, release(1, "x", today.toString()), today, 12)

        assertThat(match.status).isEqualTo(EolStatus.EOL)
        assertThat(match.daysUntilEol).isZero()
    }

    @Test
    @DisplayName("EVM-009: a dateless EOL flag is EOL with a null horizon, never a zero-day countdown")
    fun datelessEolCarriesNoHorizon() {
        val flagged = matcher.classify(ubuntu, release(1, "x", null, alreadyEol = true), today, 12)

        assertThat(flagged.status).isEqualTo(EolStatus.EOL)
        // Null, not 0: the owner mail promises "EOL within N months" and must not
        // be able to count a cycle with no published date into that window.
        assertThat(flagged.daysUntilEol).isNull()
    }

    @Test
    @DisplayName("EVM-010: an unknown lifecycle state is treated as supported, so nothing is reported")
    fun unknownIsSupported() {
        val unknown = matcher.classify(ubuntu, release(1, "x", null, unknown = true), today, 12)

        assertThat(unknown.status).isEqualTo(EolStatus.SUPPORTED)
    }

    @Test
    @DisplayName("EVM-011: days until EOL is signed — negative once the date has passed")
    fun daysUntilEolIsSigned() {
        val past = matcher.classify(ubuntu, release(1, "x", "2026-08-01"), today, 12)
        val future = matcher.classify(ubuntu, release(2, "x", "2026-08-20"), today, 12)

        assertThat(past.daysUntilEol).isEqualTo(-9L)
        assertThat(future.daysUntilEol).isEqualTo(10L)
    }

    // ------------------------------------------------------------------ tokenizing

    @Test
    @DisplayName("EVM-012: alias normalization is stable across punctuation and case")
    fun aliasNormalization() {
        assertThat(EolVersionMatcher.normalizeAlias("Red Hat Enterprise Linux")).isEqualTo("red hat enterprise linux")
        assertThat(EolVersionMatcher.normalizeAlias("Node.js")).isEqualTo("node.js")
        assertThat(EolVersionMatcher.normalizeAlias("  WINDOWS_SERVER  ")).isEqualTo("windows server")
        assertThat(EolVersionMatcher.normalizeAlias("")).isEmpty()
    }

    @Test
    @DisplayName("EVM-013: version extraction pulls the first version run out of a free-form string")
    fun versionExtraction() {
        assertThat(EolVersionMatcher.extractVersion("120.0.6099.109")).isEqualTo("120.0.6099.109")
        assertThat(EolVersionMatcher.extractVersion("v18.20.4")).isEqualTo("18.20.4")
        assertThat(EolVersionMatcher.extractVersion("release 8.6 (Ootpa)")).isEqualTo("8.6")
        assertThat(EolVersionMatcher.extractVersion("no digits here")).isNull()
        assertThat(EolVersionMatcher.extractVersion(null)).isNull()
        assertThat(EolVersionMatcher.extractVersion("  ")).isNull()
    }

    @Test
    @DisplayName("EVM-014: an alias is claimed by the first product that declares it, deterministically")
    fun aliasCollisionIsDeterministic() {
        val first = product(101, "first", "Shared Label", releases = listOf(release(1, "1", "2020-01-01")))
        val second = product(102, "second", "Shared Label", releases = listOf(release(2, "1", "2020-01-01")))

        val forward = EolVersionMatcher.Index.build(listOf(first, second))
        val reverse = EolVersionMatcher.Index.build(listOf(second, first))

        assertThat(forward.lookup("shared label")?.productKey).isEqualTo("first")
        assertThat(reverse.lookup("shared label")?.productKey).isEqualTo("second")
    }
}
