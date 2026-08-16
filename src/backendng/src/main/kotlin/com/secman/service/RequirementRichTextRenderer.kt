package com.secman.service

import org.apache.poi.xwpf.usermodel.XWPFParagraph

/**
 * Renders a requirement's rich-text fields (details/motivation/example) into Word paragraphs.
 *
 * Those fields are authored either as HTML (via the frontend's HtmlEditor component, rendered
 * with RichContent + DOMPurify) or, for older rows, as Markdown-ish plain text using "- " bullet
 * lines. `RichContent.tsx`'s `isLikelyHtml` detects which; this mirrors that detection so the
 * .docx export shows the same paragraph/bullet structure the web UI renders, instead of the
 * historical behaviour of a single `XWPFRun.setText(...)` call, which silently drops every line
 * break (Word does not treat an embedded "\n" as a line break inside one run) and collapses a
 * bulleted list into one run-on sentence.
 */
object RequirementRichTextRenderer {

    private val HTML_TAG_RE = Regex(
        "</?(p|div|span|h[1-6]|ul|ol|li|strong|em|b|i|u|a|br|table|tr|td|th|blockquote|code|pre)(\\s|/?>)",
        RegexOption.IGNORE_CASE
    )
    private val LIST_ITEM_OPEN_RE = Regex("<li[^>]*>", RegexOption.IGNORE_CASE)
    private val LIST_ITEM_CLOSE_RE = Regex("</li>", RegexOption.IGNORE_CASE)
    private val LINE_BREAK_RE = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val BLOCK_CLOSE_RE = Regex("</(p|div|h[1-6]|tr|blockquote)>", RegexOption.IGNORE_CASE)
    private val BLOCK_OPEN_RE = Regex("<(p|div|h[1-6])[^>]*>", RegexOption.IGNORE_CASE)
    private val ANY_TAG_RE = Regex("<[^>]*>")
    private val BULLET_LINE_RE = Regex("^[-*]\\s+(.*)$")

    private val HTML_ENTITIES = linkedMapOf(
        "&nbsp;" to " ",
        "&quot;" to "\"",
        "&#39;" to "'",
        "&apos;" to "'",
        "&lt;" to "<",
        "&gt;" to ">",
        "&amp;" to "&"
    )

    fun isLikelyHtml(text: String): Boolean = HTML_TAG_RE.containsMatchIn(text)

    /** Splits [text] into logical lines; a "- "/"* " prefix marks a bullet-list item. */
    private fun toLines(text: String): List<String> {
        if (!isLikelyHtml(text)) {
            return text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        }
        var normalized = text
            .replace(LIST_ITEM_OPEN_RE, "\n- ")
            .replace(LIST_ITEM_CLOSE_RE, "")
            .replace(LINE_BREAK_RE, "\n")
            .replace(BLOCK_CLOSE_RE, "\n")
            .replace(BLOCK_OPEN_RE, "\n")
            .replace(ANY_TAG_RE, "")
        for ((entity, replacement) in HTML_ENTITIES) {
            normalized = normalized.replace(entity, replacement)
        }
        return normalized.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Writes [text] as one or more Word paragraphs via [newParagraph]. Each source line becomes
     * its own paragraph so line breaks survive, and a bullet-list line renders as an indented
     * bullet paragraph rather than plain text with a literal "-" prefix.
     */
    fun write(text: String, newParagraph: () -> XWPFParagraph) {
        for (line in toLines(text)) {
            val paragraph = newParagraph()
            paragraph.spacingAfter = 60
            val bulletMatch = BULLET_LINE_RE.matchEntire(line)
            if (bulletMatch != null) {
                paragraph.indentationLeft = 360
                WordExportStyle.run(paragraph, "• ${bulletMatch.groupValues[1]}")
            } else {
                WordExportStyle.run(paragraph, line)
            }
        }
    }
}
