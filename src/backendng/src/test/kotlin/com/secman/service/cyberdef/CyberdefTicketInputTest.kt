package com.secman.service.cyberdef

import com.secman.domain.CyberdefSeverity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/** Unit tests for CyberdefTicketInput; no database, collaborators are mocked. */
class CyberdefTicketInputTest {
    private val now = Instant.parse("2026-11-26T10:00:00Z")

    private fun valid(overrides: CyberdefTicketCreateRequest.() -> CyberdefTicketCreateRequest = { this }) =
        CyberdefTicketCreateRequest(
            externalReference = "SOC-2026-4711", awsAccountId = "123456789012", title = "Public RDS snapshot",
            severity = "high", description = "Snapshot is shared with all."
        ).overrides()

    @Test
    fun `a complete submission is normalised`() {
        val outcome = CyberdefTicketInput.validate(valid {
            copy(cves = listOf("cve-2026-12345", "CVE-2026-12345"),
                resources = listOf(CyberdefResourceInput("rds_snapshot", " arn:aws:rds:eu-central-1:123456789012:snapshot:x ", "EU-CENTRAL-1")),
                references = listOf("https://docs.aws.amazon.com/x"), dueAt = "2026-11-27T00:00:00Z")
        }, now)
        val ticket = (outcome as ValidationOutcome.Valid).ticket
        assertThat(ticket.severity).isEqualTo(CyberdefSeverity.HIGH)
        assertThat(ticket.cves).containsExactly("CVE-2026-12345")
        assertThat(ticket.resources.single()).isEqualTo(
            CyberdefResourceInput("RDS_SNAPSHOT", "arn:aws:rds:eu-central-1:123456789012:snapshot:x", "eu-central-1"))
        assertThat(ticket.dueAt).isEqualTo(Instant.parse("2026-11-27T00:00:00Z"))
        assertThat(ticket.allowUnrouted).isFalse()
    }

    @Test
    fun `every bad field is reported, not just the first`() {
        val outcome = CyberdefTicketInput.validate(CyberdefTicketCreateRequest(
            awsAccountId = "12345", title = "", severity = "urgent", description = "",
            externalReference = "bad ref with spaces", cves = listOf("CVE-1"), dueAt = "yesterday"
        ), now)
        val errors = (outcome as ValidationOutcome.Invalid).errors
        assertThat(errors).hasSize(7)
        assertThat(errors.joinToString()).contains("awsAccountId", "title", "severity", "description", "externalReference", "CVE", "dueAt")
    }

    @Test
    fun `control characters cannot forge log lines or mail headers`() {
        val ticket = (CyberdefTicketInput.validate(valid {
            copy(title = "Alert\r\nBcc: attacker@example.test", description = "line1\nline2\u0007\u001b[31m")
        }, now) as ValidationOutcome.Valid).ticket
        assertThat(ticket.title).doesNotContain("\r", "\n")
        assertThat(ticket.description).isEqualTo("line1\nline2[31m")
    }

    @Test
    fun `references must be plain https links`() {
        assertThat(CyberdefTicketInput.isSafeHttpsUrl("https://example.test/a?b=c")).isTrue()
        listOf("http://example.test", "javascript:alert(1)", "https://user:pw@example.test", "https:///nohost",
            "https://example.test/ a", "file:///etc/passwd", "").forEach {
            assertThat(CyberdefTicketInput.isSafeHttpsUrl(it)).describedAs(it).isFalse()
        }
    }

    @Test
    fun `size limits are enforced`() {
        val outcome = CyberdefTicketInput.validate(valid {
            copy(title = "x".repeat(201), cves = List(101) { "CVE-2026-%05d".format(it) },
                references = List(21) { "https://example.test/$it" })
        }, now)
        assertThat((outcome as ValidationOutcome.Invalid).errors.joinToString()).contains("title", "100 cves", "20 references")
    }

    @Test
    fun `past due dates are rejected`() {
        val outcome = CyberdefTicketInput.validate(valid { copy(dueAt = "2026-11-25T00:00:00Z") }, now)
        assertThat((outcome as ValidationOutcome.Invalid).errors).containsExactly("dueAt must be in the future")
    }

    @Test
    fun `mcp arguments with unknown or mistyped keys are refused`() {
        val (request, errors) = CyberdefTicketInput.fromArguments(mapOf("awsAccountId" to "123456789012", "sevrity" to "HIGH",
            "cves" to "CVE-2026-1", "resources" to listOf(mapOf("type" to "EC2", "identifier" to "i-1", "zone" to "a"))))
        assertThat(request).isNull()
        assertThat(errors.joinToString()).contains("sevrity", "cves must be an array", "zone")
    }

    @Test
    fun `mcp arguments map onto the request`() {
        val (request, errors) = CyberdefTicketInput.fromArguments(mapOf("awsAccountId" to 123456789012L, "title" to "t",
            "severity" to "LOW", "description" to "d", "allowUnrouted" to true,
            "resources" to listOf(mapOf("type" to "EC2_INSTANCE", "identifier" to "i-0abc"))))
        assertThat(errors).isEmpty()
        assertThat(request!!.awsAccountId).isEqualTo("123456789012")
        assertThat(request.allowUnrouted).isTrue()
        assertThat(request.resources!!.single().identifier).isEqualTo("i-0abc")
    }

    @Test
    fun `comments are trimmed, bounded and blank means none`() {
        assertThat(CyberdefTicketInput.cleanComment("  \n ")).isNull()
        assertThat(CyberdefTicketInput.cleanComment(" fixed\r\n ")).isEqualTo("fixed")
        assertThatThrownBy { CyberdefTicketInput.cleanComment("x".repeat(4001)) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `override recipients are normalised, deduplicated and bounded`() {
        val ticket = (CyberdefTicketInput.validate(valid {
            copy(recipients = listOf(" Analyst@SOC.example ", "analyst@soc.example", ""), dryRun = true)
        }, now) as ValidationOutcome.Valid).ticket
        assertThat(ticket.recipientOverride).containsExactly("analyst@soc.example")
        assertThat(ticket.dryRun).isTrue()

        val tooMany = CyberdefTicketInput.validate(valid { copy(recipients = List(21) { "a$it@soc.example" }) }, now)
        assertThat((tooMany as ValidationOutcome.Invalid).errors).contains("at most 20 recipients")
        val malformed = CyberdefTicketInput.validate(valid { copy(recipients = listOf("not-an-address")) }, now)
        assertThat((malformed as ValidationOutcome.Invalid).errors.single()).contains("recipients[0]")
    }

    @Test
    fun `mcp dryRun and recipients are typed`() {
        val (request, errors) = CyberdefTicketInput.fromArguments(mapOf("awsAccountId" to "123456789012", "title" to "t",
            "severity" to "LOW", "description" to "d", "dryRun" to true, "recipients" to listOf("a@soc.example")))
        assertThat(errors).isEmpty()
        assertThat(request!!.dryRun).isTrue()
        assertThat(request.recipients).containsExactly("a@soc.example")

        val (_, typeErrors) = CyberdefTicketInput.fromArguments(mapOf("dryRun" to "yes", "recipients" to "a@soc.example"))
        assertThat(typeErrors.joinToString()).contains("dryRun must be a boolean", "recipients must be an array")
    }
}
