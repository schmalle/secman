package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.dto.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class IntegrationRunValidatorTest {
    private val validator = IntegrationRunValidator(ObjectMapper().findAndRegisterModules())
    private val time = Instant.parse("2026-09-06T10:00:00Z")
    private fun request() = IntegrationRunRequest(1, 2, "retry-key", "SUCCESS", true, time, time)

    @Test
    fun `shared v1 contract deserializes and validates`() {
        val mapper = ObjectMapper().findAndRegisterModules().registerModule(com.fasterxml.jackson.module.kotlin.KotlinModule.Builder().build())
        val fixture = java.nio.file.Path.of("../../docs/contracts/integration-run-v1.json")
        val body = mapper.readValue(java.nio.file.Files.readString(fixture), IntegrationRunRequest::class.java)
        val validated = validator.validate(body, time.plusSeconds(60))
        assertThat(validated.request.findings.single().severity).isEqualTo("INFO")
        assertThat(validated.attachments.single().single().bytes).isEqualTo("evidence".toByteArray())
    }

    @Test
    fun `only complete successful newer snapshots resolve`() {
        for (status in listOf("SUCCESS", "PARTIAL", "FAILED", "SKIPPED")) {
            for (complete in listOf(false, true)) {
                val run = request().copy(status = status, completeCoverage = complete)
                assertThat(IntegrationLifecycle.resolvesAbsent(run)).isEqualTo(status == "SUCCESS" && complete)
            }
        }
        assertThat(IntegrationLifecycle.isNewer(request(), time)).isFalse()
        assertThat(IntegrationLifecycle.isNewer(request(), time.minusSeconds(1))).isTrue()
        assertThat(IntegrationLifecycle.isNewer(request(), null)).isTrue()
    }

    @Test
    fun `validates zero finding snapshots and stable replay digests`() {
        val validated = validator.validate(request(), time)
        assertThat(validated.attachments).isEmpty()
        assertThat(validated.digest).isEqualTo(validator.validate(request(), time).digest)
        assertThat(validated.digest).isNotEqualTo(validator.validate(request().copy(completeCoverage = false), time).digest)
    }

    @Test
    fun `rejects oversized duplicate invalid evidence and future snapshots`() {
        val finding = IntegrationFindingInput("rule:location", severity = "INFO", title = "Observation")
        val invalid = listOf(
            request().copy(findings = List(501) { finding.copy(externalId = "rule-$it") }),
            request().copy(findings = listOf(finding, finding)),
            request().copy(completedAt = time.plusSeconds(601)),
            request().copy(findings = listOf(finding.copy(confidence = Double.NaN))),
            request().copy(findings = listOf(finding.copy(url = "javascript:alert(1)"))),
            request().copy(findings = listOf(finding.copy(attachments = listOf(IntegrationAttachmentInput("../a.txt", "text/plain", "YQ=="))))),
            request().copy(findings = listOf(finding.copy(attachments = listOf(IntegrationAttachmentInput("a.png", "image/png", "YQ=="))))),
            request().copy(findings = listOf(finding.copy(attachments = listOf(IntegrationAttachmentInput("a.txt", "text/plain", "%%%")))))
        )
        invalid.forEach { body -> assertThatThrownBy { validator.validate(body, time) }.isInstanceOf(io.micronaut.http.exceptions.HttpStatusException::class.java) }
    }

    @Test
    fun `INFO and UTF8 evidence survive validation`() {
        val run = request().copy(findings = listOf(IntegrationFindingInput("id", severity = "INFO", title = "Note",
            attachments = listOf(IntegrationAttachmentInput("note.txt", "text/plain", "ZXZpZGVuY2U=")))))
        assertThat(String(validator.validate(run, time).attachments.single().single().bytes)).isEqualTo("evidence")
    }
}
