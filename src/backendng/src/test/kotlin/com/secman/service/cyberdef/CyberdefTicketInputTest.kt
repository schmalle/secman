package com.secman.service.cyberdef

import com.secman.domain.CyberdefSeverity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/** Unit tests for CyberdefTicketInput; no database, collaborators are mocked. */
class CyberdefTicketInputTest {
    private val now = Instant.parse("2026-11-26T10:00:00Z")

    private fun valid(overrides: CyberdefTicketFields.() -> CyberdefTicketFields = { this }) =
        CyberdefTicketFields(
            externalReference = "SOC-2026-4711", awsAccountId = "123456789012", title = "Public RDS snapshot",
            severity = "high", description = "Snapshot is shared with all."
        ).overrides()

    private fun errorsOf(fields: CyberdefTicketFields) = (CyberdefTicketInput.validate(fields, now) as Validated.Invalid).errors

    @Test
    fun `a complete submission is normalised`() {
        val ticket = (CyberdefTicketInput.validate(valid {
            copy(cves = listOf("cve-2026-12345", "CVE-2026-12345"),
                resources = listOf(CyberdefResourceInput("rds_snapshot", " db-snap ", "EU-CENTRAL-1")),
                references = listOf("https://docs.aws.amazon.com/x"), dueAt = "2026-11-27T00:00:00Z")
        }, now) as Validated.Valid).value
        assertThat(ticket.severity).isEqualTo(CyberdefSeverity.HIGH)
        assertThat(ticket.cves).containsExactly("CVE-2026-12345")
        assertThat(ticket.resources.single()).isEqualTo(CyberdefResourceInput("RDS_SNAPSHOT", "db-snap", "eu-central-1"))
        assertThat(ticket.dueAt).isEqualTo(Instant.parse("2026-11-27T00:00:00Z"))
    }

    @Test
    fun `every problem is reported with its field and code`() {
        val errors = errorsOf(CyberdefTicketFields(
            awsAccountId = "12345", title = "", severity = "urgent", description = "",
            externalReference = "bad ref with spaces", cves = listOf("CVE-1"), dueAt = "yesterday"
        ))
        assertThat(errors.map { it.field to it.code }).containsExactlyInAnyOrder(
            "awsAccountId" to CyberdefFieldError.INVALID, "title" to CyberdefFieldError.REQUIRED,
            "severity" to CyberdefFieldError.INVALID, "description" to CyberdefFieldError.REQUIRED,
            "externalReference" to CyberdefFieldError.INVALID, "cves[0]" to CyberdefFieldError.INVALID,
            "dueAt" to CyberdefFieldError.INVALID
        )
    }

    @Test
    fun `an ARN fills in type and region and must name the ticket's account`() {
        val ticket = (CyberdefTicketInput.validate(valid {
            copy(resources = listOf(CyberdefResourceInput(identifier = "arn:aws:ec2:eu-west-1:123456789012:instance/i-0abc")))
        }, now) as Validated.Valid).value
        assertThat(ticket.resources.single()).isEqualTo(
            CyberdefResourceInput("EC2_INSTANCE", "arn:aws:ec2:eu-west-1:123456789012:instance/i-0abc", "eu-west-1"))

        val mismatch = errorsOf(valid {
            copy(resources = listOf(CyberdefResourceInput(identifier = "x"),
                CyberdefResourceInput(identifier = "arn:aws:rds:eu-central-1:210987654321:snapshot:s")))
        })
        assertThat(mismatch.map { it.field to it.code }).containsExactly(
            "resources[0].type" to CyberdefFieldError.REQUIRED,
            "resources[1].identifier" to CyberdefFieldError.ACCOUNT_MISMATCH)
        assertThat(errorsOf(valid { copy(resources = listOf(CyberdefResourceInput(identifier = "arn:aws:broken"))) })
            .first { it.field == "resources[0].identifier" }.code).isEqualTo(CyberdefFieldError.INVALID)
    }

    @Test
    fun `control characters cannot forge log lines or mail headers`() {
        val ticket = (CyberdefTicketInput.validate(valid {
            copy(title = "Alert\r\nBcc: attacker@example.test", description = "line1\nline2\u0007\u001b[31m")
        }, now) as Validated.Valid).value
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
        val errors = errorsOf(valid {
            copy(title = "x".repeat(201), cves = List(101) { "CVE-2026-%05d".format(it) },
                references = List(21) { "https://example.test/$it" })
        })
        assertThat(errors.map { it.field to it.code }).contains(
            "title" to CyberdefFieldError.TOO_LONG, "cves" to CyberdefFieldError.TOO_MANY,
            "references" to CyberdefFieldError.TOO_MANY)
    }

    @Test
    fun `past due dates are rejected`() {
        assertThat(errorsOf(valid { copy(dueAt = "2026-11-25T00:00:00Z") }).single())
            .isEqualTo(CyberdefFieldError("dueAt", CyberdefFieldError.NOT_FUTURE, "must be in the future"))
    }

    @Test
    fun `delivery recipients are normalised, deduplicated and bounded`() {
        val delivery = (CyberdefTicketInput.validateDelivery(CyberdefDelivery(dryRun = true,
            recipients = listOf(" Analyst@SOC.example ", "analyst@soc.example", ""))) as Validated.Valid).value
        assertThat(delivery).isEqualTo(ValidDelivery(dryRun = true, recipients = listOf("analyst@soc.example"), allowUnrouted = false))

        val tooMany = CyberdefTicketInput.validateDelivery(CyberdefDelivery(recipients = List(21) { "a$it@soc.example" }))
        assertThat((tooMany as Validated.Invalid).errors.first().field).isEqualTo("delivery.recipients")
        val malformed = CyberdefTicketInput.validateDelivery(CyberdefDelivery(recipients = listOf("not-an-address")))
        assertThat((malformed as Validated.Invalid).errors.single().field).isEqualTo("delivery.recipients[0]")
        assertThat(CyberdefTicketInput.validateDelivery(null)).isEqualTo(Validated.Valid(ValidDelivery(false, emptyList(), false)))
    }

    @Test
    fun `comments are trimmed, bounded and blank means none`() {
        assertThat(CyberdefTicketInput.cleanComment("  \n ")).isNull()
        assertThat(CyberdefTicketInput.cleanComment(" fixed\r\n ")).isEqualTo("fixed")
        assertThatThrownBy { CyberdefTicketInput.cleanComment("x".repeat(4001)) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
