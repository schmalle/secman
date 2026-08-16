package com.secman.service

import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RequirementRichTextRendererTest {

    @Test
    fun `isLikelyHtml detects markup produced by the HtmlEditor component`() {
        assertThat(RequirementRichTextRenderer.isLikelyHtml("<ul><li>item</li></ul>")).isTrue()
        assertThat(RequirementRichTextRenderer.isLikelyHtml("<p>Hello</p>")).isTrue()
        assertThat(RequirementRichTextRenderer.isLikelyHtml("- item one\n- item two")).isFalse()
        assertThat(RequirementRichTextRenderer.isLikelyHtml("Plain text, no markup here.")).isFalse()
    }

    @Test
    fun `html list items render as one bullet paragraph each`() {
        val document = XWPFDocument()
        val html = "<ul><li>identification of the asset</li><li>criticality of the asset</li></ul>"

        RequirementRichTextRenderer.write(html) { document.createParagraph() }

        val texts = document.paragraphs.map { it.text }
        assertThat(texts).containsExactly(
            "• identification of the asset",
            "• criticality of the asset"
        )
    }

    @Test
    fun `html paragraphs and line breaks become separate paragraphs`() {
        val document = XWPFDocument()
        val html = "<p>First line.</p><p>Second line.<br/>Third line.</p>"

        RequirementRichTextRenderer.write(html) { document.createParagraph() }

        assertThat(document.paragraphs.map { it.text }).containsExactly(
            "First line.",
            "Second line.",
            "Third line."
        )
    }

    @Test
    fun `html entities are decoded after tags are stripped`() {
        val document = XWPFDocument()
        val html = "<p>Tom &amp; Jerry &mdash;&nbsp;a &quot;classic&quot;</p>"

        RequirementRichTextRenderer.write(html) { document.createParagraph() }

        assertThat(document.paragraphs.map { it.text }).containsExactly("Tom & Jerry &mdash; a \"classic\"")
    }

    @Test
    fun `legacy markdown bullet lines render as one bullet paragraph each`() {
        val document = XWPFDocument()
        val markdown = "The inventory must provide:\n- identification of the asset\n- criticality of the asset"

        RequirementRichTextRenderer.write(markdown) { document.createParagraph() }

        assertThat(document.paragraphs.map { it.text }).containsExactly(
            "The inventory must provide:",
            "• identification of the asset",
            "• criticality of the asset"
        )
    }

    @Test
    fun `plain single-line text renders as a single paragraph`() {
        val document = XWPFDocument()

        RequirementRichTextRenderer.write("A suitable asset inventory is the basis.") { document.createParagraph() }

        assertThat(document.paragraphs.map { it.text }).containsExactly("A suitable asset inventory is the basis.")
    }
}
