package com.secman.service

import com.secman.domain.EolFinding
import com.secman.domain.EolStatus
import com.secman.domain.EolSubjectType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The renderer is the single HTML/text sink for every EOL mail body, so its
 * escaping is a security control rather than formatting (CLAUDE.md §A03):
 * component names, hostnames and cloud instance ids all come from imported
 * inventory and are attacker-influenceable via a scan upload.
 */
class EolFindingTableRendererTest {

    private val renderer = EolFindingTableRenderer()
    private val today: LocalDate = LocalDate.of(2026, 1, 1)

    private fun finding(
        assetName: String? = "web-01",
        cloudAccountId: String? = "123456789012",
        cloudInstanceId: String? = "i-0abc123",
        adDomain: String? = "corp.example.com",
        componentName: String = "Amazon Linux",
        componentVersion: String? = "2",
        cycle: String = "2",
        eolDate: LocalDate? = LocalDate.of(2025, 6, 30),
        status: EolStatus = EolStatus.EOL,
        subjectType: EolSubjectType = EolSubjectType.ASSET_OS
    ) = EolFinding(
        id = 1L,
        subjectType = subjectType,
        assetId = 1L,
        assetName = assetName,
        cloudAccountId = cloudAccountId,
        cloudInstanceId = cloudInstanceId,
        adDomain = adDomain,
        assetOwner = "owner",
        componentName = componentName,
        componentVersion = componentVersion,
        eolCycle = cycle,
        eolDate = eolDate,
        status = status
    )

    @Test
    fun `html table carries every column the product page shows`() {
        val html = renderer.renderHtml(listOf(finding()), today)

        listOf(
            "System", "Type", "Cloud account", "Cloud instance",
            "AD domain", "Version", "Release cycle", "End of support", "Status"
        ).forEach { header ->
            assertThat(html).describedAs("header $header").contains(">$header</th>")
        }
        assertThat(html).contains("web-01")
        assertThat(html).contains("123456789012")
        assertThat(html).contains("i-0abc123")
        assertThat(html).contains("corp.example.com")
        assertThat(html).contains("2025-06-30")
    }

    @Test
    fun `html escapes inventory-supplied values`() {
        val html = renderer.renderHtml(
            listOf(
                finding(
                    assetName = "<script>alert(1)</script>",
                    componentName = "Ampersand & \"quoted\"",
                    cloudInstanceId = "i-<img src=x onerror=alert(1)>"
                )
            ),
            today,
            includeComponent = true
        )

        assertThat(html).doesNotContain("<script>")
        assertThat(html).doesNotContain("<img")
        assertThat(html).contains("&lt;script&gt;")
        assertThat(html).contains("Ampersand &amp;")
        assertThat(html).contains("&quot;quoted&quot;")
    }

    @Test
    fun `text rendering strips CR LF so a value cannot forge extra rows`() {
        val text = renderer.renderText(
            listOf(finding(assetName = "web-01\r\nSystem: attacker-controlled")),
            today
        )

        val bodyLines = text.lines().filter { it.contains("attacker-controlled") }
        assertThat(bodyLines).hasSize(1)
        assertThat(bodyLines.single()).contains("web-01 System: attacker-controlled")
    }

    @Test
    fun `missing values render as a dash rather than an empty cell`() {
        val html = renderer.renderHtml(
            listOf(finding(cloudAccountId = null, cloudInstanceId = null, adDomain = null, componentVersion = null)),
            today
        )

        assertThat(countOccurrences(html, ">-</td>")).isEqualTo(4)
        assertThat(html).doesNotContain("></td>")
    }

    @Test
    fun `row count is capped and the overflow is disclosed rather than silently dropped`() {
        val rows = (1..EolFindingTableRenderer.MAX_ROWS + 25).map { finding(assetName = "host-$it") }

        val html = renderer.renderHtml(rows, today)
        val text = renderer.renderText(rows, today)

        // header row + MAX_ROWS body rows + the overflow footer row
        assertThat(countOccurrences(html, "<tr>")).isEqualTo(EolFindingTableRenderer.MAX_ROWS + 2)
        assertThat(html).contains("25 further")
        assertThat(text).contains("25 further")
    }

    @Test
    fun `an empty finding list renders nothing at all`() {
        assertThat(renderer.renderHtml(emptyList(), today)).isEmpty()
        assertThat(renderer.renderText(emptyList(), today)).isEmpty()
    }

    @Test
    fun `deadline wording distinguishes past from future and unknown`() {
        val past = renderer.renderText(listOf(finding(eolDate = LocalDate.of(2025, 6, 30))), today)
        val future = renderer.renderText(listOf(finding(eolDate = LocalDate.of(2026, 3, 2))), today)
        val unknown = renderer.renderText(listOf(finding(eolDate = null, status = EolStatus.EOL)), today)

        assertThat(past).contains("already end of life")
        assertThat(future).contains("in 60 days")
        assertThat(unknown).contains("already end of life")
    }

    @Test
    fun `the component column is opt-in so the single-product broadcast omits it`() {
        val rows = listOf(finding(componentName = "Amazon Linux"))

        assertThat(renderer.renderHtml(rows, today)).doesNotContain(">Component</th>")
        assertThat(renderer.renderHtml(rows, today, includeComponent = true))
            .contains(">Component</th>")
            .contains(">Amazon Linux</td>")
    }

    @Test
    fun `rows are ordered by soonest deadline so the most urgent system reads first`() {
        val html = renderer.renderHtml(
            listOf(
                finding(assetName = "later", eolDate = LocalDate.of(2027, 1, 1)),
                finding(assetName = "sooner", eolDate = LocalDate.of(2024, 1, 1))
            ),
            today
        )

        assertThat(html.indexOf("sooner")).isLessThan(html.indexOf("later"))
    }

    private fun countOccurrences(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1
}
