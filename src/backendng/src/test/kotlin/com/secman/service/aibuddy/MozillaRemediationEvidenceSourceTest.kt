package com.secman.service.aibuddy

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.config.AiBuddyConfig
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MozillaRemediationEvidenceSourceTest {
    private val source = MozillaRemediationEvidenceSource(AiBuddyConfig(), mockk(), AiBuddyJson(ObjectMapper()))

    @Test
    fun `observed Falcon names are recognized without inventing a full Windows build`() {
        assertThat(AiBuddyProducts.canonical("FireFox")).isEqualTo("mozilla firefox")
        assertThat(AiBuddyProducts.canonical("Mozilla Firefox ESR 128.7esr")).isEqualTo("mozilla firefox")
        assertThat(AiBuddyProducts.canonical("Windows Server 2019 1809")).isEqualTo("Windows Server 2019")
        assertThat(AiBuddyProducts.canonical("Firefox private-host")).isNull()
    }

    @Test
    fun `only explicit single product fixed-version statements map CVEs`() {
        val html = """<dl><dt>Products</dt><dd>Firefox ESR</dd><dt>Fixed in</dt><dd><ul><li>Firefox ESR 128.7</li></ul></dd></dl><section class="cve"><h4 id="CVE-2025-1009">Issue</h4></section>"""
        val advisory = source.parseAdvisories("/en-US/security/advisories/mfsa2025-09/", html).single()
        assertThat(advisory.channel).isEqualTo("ESR")
        assertThat(advisory.version).isEqualTo("128.7")
        assertThat(advisory.cves).containsExactly("CVE-2025-1009")
        assertThat(source.parseAdvisories("unused", html.replace("Firefox ESR</dd>", "Firefox, Thunderbird</dd>"))).isEmpty()
        assertThat(source.parseAdvisories("unused", html.replace("<li>Firefox ESR 128.7</li>", "<li>Firefox ESR 128.7</li><li>Firefox 135</li>"))).isEmpty()
        assertThat(source.parseAdvisories("unused", "<h2>Update Firefox</h2>")).isEmpty()
    }

    @Test
    fun `version ordering is numeric and handles equal trailing zero components`() {
        assertThat(source.compareVersions("128.10", "128.9")).isPositive()
        assertThat(source.compareVersions("128.7", "128.7.0")).isZero()
        assertThat(source.compareVersions("128.6", "128.7")).isNegative()
    }
}
