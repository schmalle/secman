package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.dto.*
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import jakarta.inject.Singleton
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.imageio.ImageIO

data class ValidatedIntegrationAttachment(val fileName: String, val contentType: String, val bytes: ByteArray, val decodedSize: Int = bytes.size)
data class ValidatedIntegrationRun(val request: IntegrationRunRequest, val digest: String, val findingsJson: String, val attachments: List<List<ValidatedIntegrationAttachment>>)

object IntegrationLifecycle {
    fun resolvesAbsent(run: IntegrationRunRequest) = run.status == "SUCCESS" && run.completeCoverage
    fun isNewer(run: IntegrationRunRequest, lastScanAt: Instant?) = lastScanAt == null || run.completedAt > lastScanAt
}

@Singleton
class IntegrationRunValidator(private val mapper: ObjectMapper) {
    companion object {
        val STATUSES = setOf("SUCCESS", "PARTIAL", "FAILED", "SKIPPED")
        val SEVERITIES = setOf("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO")
        val SOURCES = setOf("GITHUB_AI", "VISUAL")
        const val MAX_ATTACHMENT_BYTES = 1024 * 1024
        const val MAX_RUN_BYTES = 5 * MAX_ATTACHMENT_BYTES
    }

    fun validate(request: IntegrationRunRequest, now: Instant = Instant.now()): ValidatedIntegrationRun {
        check(request.scannerId > 0 && request.subjectId > 0, "Invalid scanner or subject")
        text(request.runKey, 128, true)
        check(request.runKey.matches(Regex("[A-Za-z0-9._:/-]+")), "Invalid run key")
        check(request.status in STATUSES, "Invalid status")
        check(request.startedAt >= Instant.parse("2000-01-01T00:00:00Z") &&
            request.startedAt <= request.completedAt && request.completedAt <= now.plusSeconds(300), "Invalid timestamps")
        check(request.findings.size <= 500, "At most 500 findings are allowed")
        check(request.findings.map { it.externalId }.toSet().size == request.findings.size, "Duplicate external IDs")
        text(request.metadataJson, 16000)
        check(parseJson(request.metadataJson).isObject, "metadataJson must be an object")
        var total = 0L
        val attachments = request.findings.map { finding ->
            validateFinding(finding)
            total += listOfNotNull(finding.description, finding.recommendation, finding.evidence).sumOf { it.toByteArray().size.toLong() }
            finding.attachments.map {
                val attachment = validateAttachment(it)
                total += maxOf(attachment.decodedSize, attachment.bytes.size)
                check(total <= MAX_RUN_BYTES, "Evidence exceeds 5 MiB")
                attachment
            }
        }
        check(total <= MAX_RUN_BYTES, "Evidence exceeds 5 MiB")
        // Hash the fixed DTO representation, so HTTP and MCP retries share a digest.
        val digest = MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(request))
            .joinToString("") { "%02x".format(it) }
        return ValidatedIntegrationRun(request, digest,
            mapper.writeValueAsString(request.findings.map { it.copy(attachments = emptyList()) }), attachments)
    }

    private fun validateFinding(f: IntegrationFindingInput) {
        text(f.externalId, 200, true)
        check(f.severity in SEVERITIES, "Invalid severity")
        text(f.title, 500, true)
        listOf(f.description, f.recommendation, f.evidence).forEach { text(it, 16000) }
        text(f.filePath, 1024); text(f.lineRange, 100); text(f.engine, 100); text(f.model, 200)
        text(f.commitSha, 64)
        listOf(f.url, f.issueUrl, f.fixPrUrl).forEach { url ->
            text(url, 2048)
            if (url != null) {
                val uri = try { URI(url) } catch (_: Exception) { invalid("Invalid URL") }
                check(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null, "Invalid URL")
            }
        }
        check(f.confidence == null || (f.confidence.isFinite() && f.confidence in 0.0..1.0), "Invalid confidence")
        check(f.legacyIds.size <= 10 && f.legacyIds.toSet().size == f.legacyIds.size, "Invalid legacy IDs")
        f.legacyIds.forEach { text(it, 255, true) }
        check(f.attachments.size <= 10, "At most 10 attachments per finding")
    }

    private fun validateAttachment(input: IntegrationAttachmentInput): ValidatedIntegrationAttachment {
        text(input.fileName, 200, true)
        check(input.fileName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._ -]*")) && !input.fileName.contains(".."), "Invalid file name")
        val extensions = mapOf("image/png" to setOf("png"), "image/jpeg" to setOf("jpg", "jpeg"),
            "text/plain" to setOf("txt"), "application/json" to setOf("json"))
        check(input.fileName.substringAfterLast('.').lowercase() in (extensions[input.contentType] ?: emptySet()), "Invalid attachment type")
        check(input.base64.length <= ((MAX_ATTACHMENT_BYTES + 2) / 3) * 4, "Attachment exceeds 1 MiB")
        val bytes = try { Base64.getDecoder().decode(input.base64) } catch (_: IllegalArgumentException) { invalid("Invalid base64") }
        check(bytes.isNotEmpty() && bytes.size <= MAX_ATTACHMENT_BYTES, "Invalid attachment size")
        val safeBytes = if (input.contentType.startsWith("image/")) sanitizeImage(bytes, input.contentType) else {
            val decoded = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (_: Exception) { invalid("Attachment must be UTF-8") }
            check(decoded.none { it == '\u0000' }, "Invalid text attachment")
            if (input.contentType == "application/json") parseJson(decoded)
            bytes
        }
        check(safeBytes.size <= MAX_ATTACHMENT_BYTES, "Attachment exceeds 1 MiB after normalization")
        return ValidatedIntegrationAttachment(input.fileName, input.contentType, safeBytes, bytes.size)
    }

    private fun sanitizeImage(bytes: ByteArray, contentType: String): ByteArray {
        try {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
                val readers = ImageIO.getImageReaders(stream)
                check(readers.hasNext(), "Invalid image")
                val reader = readers.next()
                try {
                    reader.input = stream
                    val format = reader.formatName.lowercase()
                    check(if (contentType == "image/png") format == "png" else format in setOf("jpeg", "jpg"), "Image type mismatch")
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    check(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 16_000_000, "Image dimensions exceed limit")
                    val output = ByteArrayOutputStream()
                    check(ImageIO.write(reader.read(0), format, output), "Unsupported image")
                    return output.toByteArray()
                } finally { reader.dispose() }
            }
        } catch (e: HttpStatusException) { throw e
        } catch (_: Exception) { invalid("Invalid image") }
    }

    private fun parseJson(value: String): com.fasterxml.jackson.databind.JsonNode = try {
        mapper.readerFor(com.fasterxml.jackson.databind.JsonNode::class.java)
            .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .readValue<com.fasterxml.jackson.databind.JsonNode>(value) ?: invalid("Invalid JSON")
    } catch (_: com.fasterxml.jackson.core.JsonProcessingException) { invalid("Invalid JSON") }

    private fun text(value: String?, max: Int, required: Boolean = false) {
        check(!required || !value.isNullOrBlank(), "Required text is missing")
        check(value == null || (value.length <= max && value.none { it == '\u0000' }), "Text exceeds limit or contains invalid characters")
    }
    private fun check(condition: Boolean, message: String) { if (!condition) invalid(message) }
    private fun invalid(message: String): Nothing = throw HttpStatusException(HttpStatus.BAD_REQUEST, message)
}
