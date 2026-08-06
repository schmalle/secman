package com.secman.service

import com.secman.domain.Requirement
import com.secman.dto.AssessmentContext
import com.secman.dto.FewShotExample
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Feature 088 — AI-Assisted Risk Assessment Answers.
 *
 * Locks the redaction contract from spec NFR-4: even if the caller leaks
 * owner emails, IP addresses, or internal hostnames into the context, none
 * of those must appear in the built prompt sent to the LLM.
 */
class PromptBuilderTest {

    private val builder = PromptBuilder()

    private fun req() = Requirement(
        id = 42L,
        internalId = "REQ-042",
        shortreq = "Ensure cloud workloads are encrypted at rest",
        details = "Refers to AES-256 or equivalent.",
        motivation = "Required by GDPR Art. 32.",
        norm = "ISO 27001",
        chapter = "A.8.24"
    )

    @Test
    fun `prompt contains the requirement core fields`() {
        val ctx = AssessmentContext(
            basisType = "ASSET",
            basisLabel = "prod-app-01",
            assetType = "Linux server",
            assetGroups = listOf("payments", "pci"),
            osVersion = "Ubuntu 22.04"
        )
        val prompt = builder.build(req(), ctx)
        assertTrue(prompt.contains("Ensure cloud workloads are encrypted at rest"))
        assertTrue(prompt.contains("ISO 27001"))
        assertTrue(prompt.contains("A.8.24"))
        assertTrue(prompt.contains("prod-app-01"))
        assertTrue(prompt.contains("Linux server"))
        assertTrue(prompt.contains("Ubuntu 22.04"))
        assertTrue(prompt.contains("payments"))
    }

    @Test
    fun `owner email is redacted from the prompt`() {
        // Use a context whose label was carelessly populated with an email —
        // the builder must still scrub it before output.
        val ctx = AssessmentContext(
            basisType = "ASSET",
            basisLabel = "alice@example.com's laptop",
            assetType = "Workstation"
        )
        val prompt = builder.build(req(), ctx)
        assertFalse(prompt.contains("alice@example.com"), "email leaked: $prompt")
        assertTrue(prompt.contains("[REDACTED]"))
    }

    @Test
    fun `IPv4 addresses are redacted from the prompt`() {
        val ctx = AssessmentContext(
            basisType = "ASSET",
            basisLabel = "host 10.0.0.5",
            assetType = "Server",
            demandDescription = "Failover targets at 192.168.1.10 and 8.8.8.8."
        )
        val prompt = builder.build(req(), ctx)
        assertFalse(prompt.contains("10.0.0.5"))
        assertFalse(prompt.contains("192.168.1.10"))
        assertFalse(prompt.contains("8.8.8.8"))
    }

    @Test
    fun `internal URLs are redacted from the prompt`() {
        val ctx = AssessmentContext(
            basisType = "DEMAND",
            basisLabel = "Internal app",
            demandDescription = "See https://confluence.internal/runbooks/foo for context."
        )
        val prompt = builder.build(req(), ctx)
        assertFalse(prompt.contains("confluence.internal"))
        assertTrue(prompt.contains("[REDACTED]"))
    }

    @Test
    fun `few-shot examples are included when present`() {
        val ctx = AssessmentContext(
            basisType = "ASSET",
            basisLabel = "host",
            assetType = "Server",
            fewShotExamples = listOf(
                FewShotExample("Similar requirement A", "YES", "Encrypted via KMS"),
                FewShotExample("Similar requirement B", "NO", "Pending rollout")
            )
        )
        val prompt = builder.build(req(), ctx)
        assertTrue(prompt.contains("Similar requirement A"))
        assertTrue(prompt.contains("Similar requirement B"))
        assertTrue(prompt.contains("Encrypted via KMS"))
    }
}
