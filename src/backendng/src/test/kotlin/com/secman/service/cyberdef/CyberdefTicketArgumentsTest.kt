package com.secman.service.cyberdef

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit tests for MCP argument parsing into the shared request types. */
class CyberdefTicketArgumentsTest {
    @Test
    fun `ticket and delivery map onto the request`() {
        val (request, errors) = CyberdefTicketArguments.single(mapOf(
            "ticket" to mapOf("awsAccountId" to 123456789012L, "title" to "t", "severity" to "LOW", "description" to "d",
                "resources" to listOf(mapOf("identifier" to "arn:aws:s3:::b"))),
            "delivery" to mapOf("dryRun" to true, "recipients" to listOf("a@soc.example"))
        ))
        assertThat(errors).isEmpty()
        assertThat(request!!.ticket!!.awsAccountId).isEqualTo("123456789012")
        assertThat(request.ticket.resources!!.single().identifier).isEqualTo("arn:aws:s3:::b")
        assertThat(request.delivery).isEqualTo(CyberdefDelivery(dryRun = true, recipients = listOf("a@soc.example")))
    }

    @Test
    fun `every unknown key and wrong type is reported with its path`() {
        val (request, errors) = CyberdefTicketArguments.batch(mapOf(
            "tickets" to listOf(mapOf("sevrity" to "HIGH", "cves" to "CVE-2026-1"), "nope",
                mapOf("resources" to listOf(mapOf("identifier" to "i-1", "zone" to "a")))),
            "delivery" to mapOf("dryRun" to "yes", "recipient" to listOf("a@x.example")),
            "extra" to 1
        ))
        assertThat(request).isNull()
        assertThat(errors.map { it.field to it.code }).containsExactlyInAnyOrder(
            "extra" to CyberdefFieldError.UNKNOWN_FIELD,
            "tickets[0].sevrity" to CyberdefFieldError.UNKNOWN_FIELD,
            "tickets[0].cves" to CyberdefFieldError.WRONG_TYPE,
            "tickets[1]" to CyberdefFieldError.WRONG_TYPE,
            "tickets[2].resources[0].zone" to CyberdefFieldError.UNKNOWN_FIELD,
            "delivery.recipient" to CyberdefFieldError.UNKNOWN_FIELD,
            "delivery.dryRun" to CyberdefFieldError.WRONG_TYPE
        )
    }

    @Test
    fun `a missing ticket is reported`() {
        val (_, errors) = CyberdefTicketArguments.single(emptyMap())
        assertThat(errors.single().field).isEqualTo("ticket")
    }
}
