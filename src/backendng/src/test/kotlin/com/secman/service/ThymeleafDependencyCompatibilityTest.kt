package com.secman.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context
import org.thymeleaf.templateresolver.StringTemplateResolver

class ThymeleafDependencyCompatibilityTest {
    @Test
    fun `patched OGNL renders email expressions and escapes untrusted values`() {
        val engine = TemplateEngine().apply {
            setTemplateResolver(StringTemplateResolver())
        }
        val context = Context().apply {
            setVariable("recipient", "<script>alert(1)</script>")
            setVariable("findings", listOf("CVE-2026-1", "CVE-2026-2"))
        }

        val html = engine.process(
            """<p th:text="${'$'}{recipient}"></p><ul><li th:each="finding : ${'$'}{findings}" th:text="${'$'}{finding}"></li></ul>""",
            context
        )

        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;")
        assertThat(html).contains("<li>CVE-2026-1</li>", "<li>CVE-2026-2</li>")
        assertThat(html).doesNotContain("<script>")
    }
}
