package com.secman.util

import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

/** Shared allowlist for rich text authored in mail editors. */
object EmailHtmlSanitizer {
    private val allowedHtml = Safelist()
        .addTags(
            "p", "br", "strong", "b", "em", "i", "u",
            "h1", "h2", "h3", "h4", "h5", "h6",
            "ul", "ol", "li", "blockquote", "code", "pre",
            "table", "thead", "tbody", "tr", "th", "td", "a"
        )
        .addAttributes("a", "href", "title")
        .addProtocols("a", "href", "http", "https", "mailto")

    fun sanitize(html: String): String = Jsoup.clean(html, allowedHtml)
}
