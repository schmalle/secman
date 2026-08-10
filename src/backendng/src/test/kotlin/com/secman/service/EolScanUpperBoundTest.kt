package com.secman.service

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A Dependabot alert names the vulnerable *range*, never the version actually
 * resolved in the lockfile. [EolScanService] therefore treats the range's upper
 * bound as a sound over-approximation: from `< 4.17.21` it knows the dependency
 * is below 4.17.21, so if the cycle containing 4.17.21 is already end of life
 * then whatever is installed is in that cycle or an older one.
 *
 * Picking the wrong bound breaks that soundness argument and the repository
 * ranking starts reporting things that are not end of life, so the bound
 * selection is pinned here.
 *
 * ID prefix: ESU-*
 */
class EolScanUpperBoundTest {

    private val service = EolScanService(
        assetRepository = mockk(relaxed = true),
        installedProductRepository = mockk(relaxed = true),
        githubRepositoryRepository = mockk(relaxed = true),
        dependabotAlertRepository = mockk(relaxed = true),
        eolProductRepository = mockk(relaxed = true),
        eolReleaseRepository = mockk(relaxed = true),
        eolWriter = mockk(relaxed = true),
        matcher = EolVersionMatcher(),
        defaultHorizonMonths = 12,
        pageSize = 500
    )

    @Test
    @DisplayName("ESU-001: reads the exclusive and inclusive upper bound of a range")
    fun readsUpperBound() {
        assertThat(service.upperBoundOf("< 4.17.21")).isEqualTo("4.17.21")
        assertThat(service.upperBoundOf("<4.17.21")).isEqualTo("4.17.21")
        assertThat(service.upperBoundOf("<= 2.6.7")).isEqualTo("2.6.7")
        assertThat(service.upperBoundOf(">= 4.0.0, < 4.17.21")).isEqualTo("4.17.21")
    }

    @Test
    @DisplayName("ESU-002: with several upper bounds the smallest wins, numerically not lexically")
    fun picksSmallestBound() {
        // "9.5" must not beat "10.0" just because "9" > "1" as text — that would
        // pick a bound the dependency may actually exceed and break soundness.
        assertThat(service.upperBoundOf("< 10.0, < 9.5")).isEqualTo("9.5")
        assertThat(service.upperBoundOf("< 9.5, < 10.0")).isEqualTo("9.5")
        assertThat(service.upperBoundOf("< 2.0.0, <= 1.99.9")).isEqualTo("1.99.9")
    }

    @Test
    @DisplayName("ESU-003: a range with no parseable upper bound is skipped rather than guessed at")
    fun skipsUnboundedRanges() {
        listOf(
            null,
            "",
            "   ",
            ">= 4.0.0",
            "= 1.2.3",
            "all versions",
            "> 1.0"
        ).forEach { range ->
            assertThat(service.upperBoundOf(range))
                .describedAs("range %s", range ?: "null")
                .isNull()
        }
    }
}
