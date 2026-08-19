package com.secman.service

import com.secman.domain.ProductClass
import com.secman.domain.ProductClassificationRule
import com.secman.domain.RuleMatchField
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Cases are drawn from measured tenant data (2026-08-19), not invented:
 *  - "Chrome Installer" (222 EOL findings, 17910 Discover rows, NO installation path)
 *  - "Photon Setup" (24), "SQL Server Setup Bootstrapper" (9)
 *  - "App Installer" — a real Microsoft Store product that the `* installer` rule would
 *    otherwise swallow; the allowlist rule exists precisely for it
 *  - Splunk "Universal Forwarder" — 100% `C:\Windows\Installer\*.msi` and genuinely running
 */
class ProductClassifierTest {

    private fun rule(
        field: RuleMatchField = RuleMatchField.PRODUCT_NAME,
        pattern: String,
        classification: ProductClass = ProductClass.INSTALLER_ARTIFACT,
        priority: Int = 100,
        enabled: Boolean = true,
        id: Long? = 1L
    ) = ProductClassificationRule(
        id = id, matchField = field, pattern = pattern,
        classification = classification, priority = priority, enabled = enabled
    )

    /** The rule set the migration seeds. */
    private val seeded = ProductClassifier.compile(
        listOf(
            rule(pattern = "app installer*", classification = ProductClass.INSTALLED, priority = 0, id = 1),
            rule(pattern = "* installer*", id = 10),
            rule(pattern = "* setup*", id = 11),
            rule(field = RuleMatchField.INSTALL_PATH, pattern = "*/downloads/*", id = 20),
            rule(field = RuleMatchField.INSTALL_PATH, pattern = "*/ccmcache/*", id = 21)
        )
    )

    // --- product identity ---

    @Test
    fun `installer-named products are artifacts`() {
        for (name in listOf("Chrome Installer", "Photon Setup", "SQL Server Setup Bootstrapper")) {
            assertThat(ProductClassifier.classifyProduct(name, null, emptyList(), seeded))
                .describedAs(name)
                .isEqualTo(ProductClass.INSTALLER_ARTIFACT)
        }
    }

    @Test
    fun `real products stay installed`() {
        for (name in listOf("Chrome", "Internet Explorer", "Universal Forwarder", "SQL Server")) {
            assertThat(ProductClassifier.classifyProduct(name, null, emptyList(), seeded))
                .describedAs(name)
                .isEqualTo(ProductClass.INSTALLED)
        }
    }

    @Test
    fun `allowlist rule beats an artifact rule regardless of priority order`() {
        // "App Installer" matches `* installer*` too; the INSTALLED rule must win.
        assertThat(ProductClassifier.classifyProduct("App Installer", null, emptyList(), seeded))
            .isEqualTo(ProductClass.INSTALLED)
    }

    @Test
    fun `matching is case and whitespace insensitive`() {
        assertThat(ProductClassifier.classifyProduct("  CHROME   INSTALLER ", null, emptyList(), seeded))
            .isEqualTo(ProductClass.INSTALLER_ARTIFACT)
    }

    @Test
    fun `a blank name is unknown rather than installed`() {
        assertThat(ProductClassifier.classifyProduct("  ", null, emptyList(), seeded))
            .isEqualTo(ProductClass.UNKNOWN)
        assertThat(ProductClassifier.classifyProduct(null, null, emptyList(), seeded))
            .isEqualTo(ProductClass.UNKNOWN)
    }

    @Test
    fun `no rules means everything stays visible`() {
        assertThat(ProductClassifier.classifyProduct("Chrome Installer", null, emptyList(), emptyList()))
            .isEqualTo(ProductClass.INSTALLED)
    }

    @Test
    fun `disabled rules are ignored`() {
        val rules = ProductClassifier.compile(listOf(rule(pattern = "* installer*", enabled = false)))
        assertThat(ProductClassifier.classifyProduct("Chrome Installer", null, emptyList(), rules))
            .isEqualTo(ProductClass.INSTALLED)
    }

    // --- the MSI-cache trap ---

    @Test
    fun `MSI cache and Package Cache paths do NOT make a product an artifact`() {
        // Universal Forwarder is 100% C:\Windows\Installer\*.msi in this tenant and is running.
        assertThat(
            ProductClassifier.classifyProduct(
                "Universal Forwarder", "Splunk",
                listOf("""C:\Windows\Installer\8d07c.msi"""), seeded
            )
        ).isEqualTo(ProductClass.INSTALLED)

        assertThat(
            ProductClassifier.classifyProduct(
                "Visual Studio Tools For Applications", "Microsoft",
                listOf("""C:\ProgramData\Package Cache\{f76baab3}\vsta_sdk.exe"""), seeded
            )
        ).isEqualTo(ProductClass.INSTALLED)
    }

    // --- path rules ---

    @Test
    fun `a download-folder path makes a product an artifact`() {
        assertThat(
            ProductClassifier.classifyProduct(
                "Firefox Portable", null,
                listOf("""C:\Users\CVTAG\Downloads\Software\FirefoxPortable\App\Firefox64\"""), seeded
            )
        ).isEqualTo(ProductClass.INSTALLER_ARTIFACT)
    }

    @Test
    fun `an artifact path rule needs every path to match`() {
        // Present both in Program Files and in Downloads: the real install wins.
        assertThat(
            ProductClassifier.classifyProduct(
                "Firefox", null,
                listOf("""C:\Program Files\Mozilla Firefox\firefox.exe""", """C:\Users\x\Downloads\setup.exe"""),
                seeded
            )
        ).isEqualTo(ProductClass.INSTALLED)
    }

    @Test
    fun `an allowlist path rule needs only one path to match`() {
        val rules = ProductClassifier.compile(
            listOf(
                rule(field = RuleMatchField.INSTALL_PATH, pattern = "*/program files/*",
                     classification = ProductClass.INSTALLED, priority = 0, id = 1),
                rule(field = RuleMatchField.INSTALL_PATH, pattern = "*/downloads/*", id = 2)
            )
        )
        assertThat(
            ProductClassifier.classifyProduct(
                "Firefox", null,
                listOf("""C:\Users\x\Downloads\setup.exe""", """C:\Program Files\Mozilla Firefox\firefox.exe"""),
                rules
            )
        ).isEqualTo(ProductClass.INSTALLED)
    }

    @Test
    fun `path normalization handles separators case and duplicate slashes`() {
        assertThat(ProductClassifier.normalizePath("""C:\\Users\\X\\Downloads\\"""))
            .isEqualTo("c:/users/x/downloads")
        assertThat(ProductClassifier.normalizePath("""\\server\share\tool.exe"""))
            .isEqualTo("/server/share/tool.exe")
    }

    @Test
    fun `a product with no path is never classified by a path rule`() {
        // "Chrome Installer" has no path at all in this tenant; only the NAME rule may fire.
        val pathOnly = ProductClassifier.compile(
            listOf(rule(field = RuleMatchField.INSTALL_PATH, pattern = "*/downloads/*"))
        )
        assertThat(ProductClassifier.classifyProduct("Chrome Installer", null, emptyList(), pathOnly))
            .isEqualTo(ProductClass.INSTALLED)
    }

    // --- vulnerability rows ---

    @Test
    fun `vulnerability product string is classified by name`() {
        assertThat(ProductClassifier.classifyVulnerability("Notepad++ Installer 8.4.1", seeded))
            .isEqualTo(ProductClass.INSTALLER_ARTIFACT)
        assertThat(ProductClassifier.classifyVulnerability("Google Chrome 100.0.4896.60", seeded))
            .isEqualTo(ProductClass.INSTALLED)
    }

    @Test
    fun `a vulnerability naming one real product among artifacts stays visible`() {
        assertThat(ProductClassifier.classifyVulnerability("Chrome Installer 1.0, Google Chrome 2.0", seeded))
            .isEqualTo(ProductClass.INSTALLED)
    }

    @Test
    fun `a vulnerability naming only artifacts is an artifact`() {
        assertThat(ProductClassifier.classifyVulnerability("Chrome Installer 1.0, Photon Setup 2.0", seeded))
            .isEqualTo(ProductClass.INSTALLER_ARTIFACT)
    }

    @Test
    fun `App Installer vulnerabilities stay visible`() {
        assertThat(ProductClassifier.classifyVulnerability("App Installer 1.21.3", seeded))
            .isEqualTo(ProductClass.INSTALLED)
    }

    @Test
    fun `a blank product string is unknown`() {
        assertThat(ProductClassifier.classifyVulnerability(null, seeded)).isEqualTo(ProductClass.UNKNOWN)
        assertThat(ProductClassifier.classifyVulnerability("", seeded)).isEqualTo(ProductClass.UNKNOWN)
        assertThat(ProductClassifier.classifyVulnerability(" , ", seeded)).isEqualTo(ProductClass.UNKNOWN)
    }

    @Test
    fun `path and vendor rules never fire on a vulnerability row`() {
        val rules = ProductClassifier.compile(
            listOf(
                rule(field = RuleMatchField.INSTALL_PATH, pattern = "*", id = 1),
                rule(field = RuleMatchField.VENDOR, pattern = "*", id = 2)
            )
        )
        assertThat(ProductClassifier.classifyVulnerability("Chrome Installer 1.0", rules))
            .isEqualTo(ProductClass.INSTALLED)
    }

    // --- glob safety ---

    @Test
    fun `glob metacharacters are escaped rather than interpreted as regex`() {
        val rules = ProductClassifier.compile(listOf(rule(pattern = "notepad++ installer*")))
        // `+` is a regex quantifier; as a glob it must be a literal.
        assertThat(ProductClassifier.classifyProduct("Notepad++ Installer", null, emptyList(), rules))
            .isEqualTo(ProductClass.INSTALLER_ARTIFACT)
        assertThat(ProductClassifier.classifyProduct("Notepad Installer", null, emptyList(), rules))
            .isEqualTo(ProductClass.INSTALLED)
    }

    @Test
    fun `globs are anchored so a bare word does not match a substring`() {
        val rules = ProductClassifier.compile(listOf(rule(pattern = "installer")))
        assertThat(ProductClassifier.classifyProduct("Chrome Installer", null, emptyList(), rules))
            .isEqualTo(ProductClass.INSTALLED)
        assertThat(ProductClassifier.classifyProduct("Installer", null, emptyList(), rules))
            .isEqualTo(ProductClass.INSTALLER_ARTIFACT)
    }

    @Test
    fun `an over-long or blank pattern is dropped instead of throwing`() {
        val rules = ProductClassifier.compile(
            listOf(
                rule(pattern = "x".repeat(ProductClassificationRule.MAX_PATTERN_LENGTH + 1), id = 1),
                rule(pattern = "   ", id = 2)
            )
        )
        assertThat(rules).isEmpty()
    }

    @Test
    fun `compile orders allowlist rules ahead of artifact rules`() {
        val compiled = ProductClassifier.compile(
            listOf(
                rule(pattern = "* installer*", priority = 1, id = 10),
                rule(pattern = "app installer*", classification = ProductClass.INSTALLED, priority = 900, id = 11)
            )
        )
        assertThat(compiled.first().classification).isEqualTo(ProductClass.INSTALLED)
    }
}
