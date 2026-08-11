package com.secman.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The shared classpath-template renderer.
 *
 * Two things here are security controls rather than formatting: HTML escaping applied to the
 * *values* only, and the closed template allowlist that stops a configured basename from
 * becoming a classpath-traversal read.
 */
class EmailTemplateRendererTest {

    private val renderer = EmailTemplateRenderer()

    @Test
    fun `placeholders are substituted`() {
        val out = renderer.render("Account {id} owned by {owner}", mapOf("id" to "1", "owner" to "a@b.c"), escape = false)
        assertThat(out).isEqualTo("Account 1 owned by a@b.c")
    }

    @Test
    fun `values are escaped in the HTML part and left alone in the text part`() {
        val template = "Use case: {useCase}"
        val values = mapOf("useCase" to "<b>x</b> & \"y\"")

        assertThat(renderer.render(template, values, escape = true))
            .isEqualTo("Use case: &lt;b&gt;x&lt;/b&gt; &amp; &quot;y&quot;")
        // The plain-text part must stay raw, or readers see &amp; in their mail client.
        assertThat(renderer.render(template, values, escape = false))
            .isEqualTo("Use case: <b>x</b> & \"y\"")
    }

    @Test
    fun `the template itself is never escaped`() {
        // Only values are escaped; escaping the template would mangle every tag in the HTML mail.
        val out = renderer.render("<p>{v}</p>", mapOf("v" to "x"), escape = true)
        assertThat(out).isEqualTo("<p>x</p>")
    }

    @Test
    fun `an unreferenced placeholder is left in place rather than silently blanked`() {
        assertThat(renderer.render("{a}-{b}", mapOf("a" to "1"), escape = false)).isEqualTo("1-{b}")
    }

    @Test
    fun `a conditional block renders its contents when included`() {
        val out = renderer.renderConditionalBlock("A{ifVersion}B{/ifVersion}C", "ifVersion", include = true)
        assertThat(out).isEqualTo("ABC")
    }

    @Test
    fun `a stripped conditional block leaves no blank hole`() {
        val out = renderer.renderConditionalBlock("A\n{ifVersion}B{/ifVersion}\nC", "ifVersion", include = false)
        assertThat(out).isEqualTo("A\nC")
    }

    @Test
    fun `repeated conditional blocks render independently`() {
        val template = "{x}1{/x}-{x}2{/x}"
        assertThat(renderer.renderConditionalBlock(template, "x", include = true)).isEqualTo("1-2")
        assertThat(renderer.renderConditionalBlock(template, "x", include = false)).isEqualTo("-")
    }

    @Test
    fun `a conditional block may span lines`() {
        val template = "{ifSimulated}\nline1\nline2\n{/ifSimulated}tail"
        assertThat(renderer.renderConditionalBlock(template, "ifSimulated", include = false)).isEqualTo("tail")
        assertThat(renderer.renderConditionalBlock(template, "ifSimulated", include = true))
            .isEqualTo("\nline1\nline2\ntail")
    }

    // --- The allowlist -------------------------------------------------------

    @Test
    fun `every allowlisted template exists on the classpath, in both parts`() {
        // Catches the reverse mistake of the traversal guard: a name allowlisted but never
        // shipped fails at send time, to a recipient, in production.
        for (basename in EmailTemplateRenderer.ALLOWED_TEMPLATES) {
            assertThat(renderer.readHtml(basename)).describedAs("$basename.html").isNotEmpty()
            assertThat(renderer.readText(basename)).describedAs("$basename.txt").isNotEmpty()
        }
    }

    @Test
    fun `a name outside the allowlist is refused`() {
        assertThatThrownBy { renderer.requireAllowed("some-other-template") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Unknown email template")
    }

    @Test
    fun `traversal out of the template directory is refused`() {
        // The reason the allowlist exists: these basenames come from operator configuration
        // (secman.account-onboarding.*-template), and interpolating one into getResourceAsStream
        // would hand back application.yml, secrets and all.
        for (name in listOf("../application", "../../application", "/application", "account-welcome/../../application")) {
            assertThatThrownBy { renderer.requireAllowed(name) }
                .describedAs(name)
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `the onboarding templates carry the placeholders the service supplies`() {
        val html = renderer.readHtml("account-onboarding-questionnaire")
        val text = renderer.readText("account-onboarding-questionnaire")
        for (placeholder in listOf("{awsAccountId}", "{questionnaireUrl}", "{expiresAt}")) {
            assertThat(html).describedAs("html $placeholder").contains(placeholder)
            assertThat(text).describedAs("text $placeholder").contains(placeholder)
        }
        // The simulated banner is what stops the simulate surface being a usable phishing tool.
        assertThat(html).contains("{ifSimulated}").contains("{simulatedBy}")
        assertThat(text).contains("{ifSimulated}").contains("{simulatedBy}")
    }

    @Test
    fun `the welcome template names the owner and the portal`() {
        for (body in listOf(renderer.readHtml("account-welcome"), renderer.readText("account-welcome"))) {
            assertThat(body).contains("{awsAccountId}").contains("{portalUrl}").contains("{ownerEmail}")
        }
    }

    @Test
    fun `the logo is loaded under the CID the templates reference`() {
        val images = renderer.loadLogoInlineImage()
        assertThat(images).containsKey(EmailTemplateRenderer.LOGO_CID)
        assertThat(images[EmailTemplateRenderer.LOGO_CID]!!.first).isNotEmpty()
        assertThat(renderer.readHtml("account-welcome")).contains("cid:${EmailTemplateRenderer.LOGO_CID}")
    }

    @Test
    fun `escapeHtml covers the four characters that matter in an attribute or a body`() {
        assertThat(renderer.escapeHtml("&<>\"")).isEqualTo("&amp;&lt;&gt;&quot;")
    }
}
