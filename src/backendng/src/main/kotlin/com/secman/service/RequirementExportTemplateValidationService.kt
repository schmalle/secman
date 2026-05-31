package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.micronaut.context.annotation.Value
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

@Singleton
open class RequirementExportTemplateValidationService(
    private val objectMapper: ObjectMapper,
    @Value("\${secman.requirement-export-templates.max-file-size-bytes:5242880}")
    private val maxFileSizeBytes: Long,
    @Value("\${secman.requirement-export-templates.max-uncompressed-size-bytes:20971520}")
    private val maxUncompressedSizeBytes: Long,
    @Value("\${secman.requirement-export-templates.max-zip-entries:512}")
    private val maxZipEntries: Int
) {
    companion object {
        const val DOCX_MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        val ALLOWED_PLACEHOLDERS = setOf(
            "requirements",
            "documentTitle",
            "exportDate",
            "releaseVersion",
            "releaseStatus",
            "useCaseName",
            "exportedBy",
            "language",
            "requirementCount",
            "classification"
        )
    }

    @Serdeable
    data class ValidationReport(
        val valid: Boolean,
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
        val placeholders: List<String> = emptyList(),
        val sha256: String,
        val fileSizeBytes: Long,
        val uncompressedSizeBytes: Long = 0,
        val entryCount: Int = 0
    )

    fun validate(
        bytes: ByteArray,
        filename: String?,
        contentType: String?,
        requireRequirementsPlaceholder: Boolean = true
    ): ValidationReport {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val placeholders = linkedSetOf<String>()
        val safeFilename = filename.orEmpty().lowercase(Locale.ROOT)
        val normalizedContentType = contentType?.substringBefore(';')?.trim().orEmpty()
        val sha256 = sha256(bytes)

        if (!safeFilename.endsWith(".docx")) {
            errors += "Only .docx Word templates are supported."
        }
        if (safeFilename.endsWith(".docm") || safeFilename.endsWith(".dotm")) {
            errors += "Macro-enabled Word templates are not allowed."
        }
        if (normalizedContentType.isNotBlank() && normalizedContentType != DOCX_MEDIA_TYPE && normalizedContentType != "application/octet-stream") {
            errors += "Invalid content type for a Word template."
        }
        if (bytes.isEmpty()) {
            errors += "Template file is empty."
        }
        if (bytes.size > maxFileSizeBytes) {
            errors += "Template exceeds the maximum allowed file size."
        }
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            errors += "Template is not a valid OpenXML ZIP package."
        }

        var hasContentTypes = false
        var hasWordDocument = false
        var hasMainDocumentContentType = false
        var uncompressedSize = 0L
        var entryCount = 0

        if (errors.none { it == "Template is not a valid OpenXML ZIP package." }) {
            try {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                    var entry = zip.nextEntry
                    val buffer = ByteArray(8192)
                    while (entry != null) {
                        entryCount++
                        if (entryCount > maxZipEntries) {
                            errors += "Template contains too many files."
                            break
                        }

                        val entryName = entry.name.replace('\\', '/')
                        if (entryName.contains("../") || entryName.startsWith('/')) {
                            errors += "Template contains an unsafe ZIP entry path."
                        }
                        if (isForbiddenEntry(entryName)) {
                            errors += "Template contains unsupported active or embedded content."
                        }

                        val entryBytes = readEntry(zip, buffer) { readBytes ->
                            uncompressedSize += readBytes
                            uncompressedSize <= maxUncompressedSizeBytes
                        }
                        if (uncompressedSize > maxUncompressedSizeBytes) {
                            errors += "Template uncompressed size exceeds the configured limit."
                            break
                        }

                        when (entryName) {
                            "[Content_Types].xml" -> {
                                hasContentTypes = true
                                val xml = entryBytes.toString(Charsets.UTF_8)
                                hasMainDocumentContentType = xml.contains("application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml")
                                if (xml.contains("macroEnabled", ignoreCase = true) || xml.contains("vbaProject", ignoreCase = true)) {
                                    errors += "Macro-enabled Word packages are not allowed."
                                }
                            }
                            "word/document.xml" -> {
                                hasWordDocument = true
                                placeholders += extractPlaceholders(entryBytes.toString(Charsets.UTF_8))
                            }
                            else -> {
                                if (entryName.startsWith("word/header") || entryName.startsWith("word/footer")) {
                                    placeholders += extractPlaceholders(entryBytes.toString(Charsets.UTF_8))
                                }
                                if (entryName.endsWith(".rels")) {
                                    val rels = entryBytes.toString(Charsets.UTF_8)
                                    if (rels.contains("TargetMode=\"External\"") || rels.contains("TargetMode='External'")) {
                                        errors += "External links, remote images, and remote templates are not allowed."
                                    }
                                }
                            }
                        }

                        entry = zip.nextEntry
                    }
                }
            } catch (e: Exception) {
                errors += "Template is not a valid .docx file."
            }
        }

        if (!hasContentTypes) errors += "Template is missing OpenXML content types."
        if (!hasWordDocument) errors += "Template is missing the Word document body."
        if (!hasMainDocumentContentType) errors += "Template is not a standard .docx Word document."
        if (requireRequirementsPlaceholder && "requirements" !in placeholders) {
            errors += "Template must include the ${'$'}{requirements} placeholder or use append mode."
        }

        val unsupportedPlaceholders = placeholders - ALLOWED_PLACEHOLDERS
        if (unsupportedPlaceholders.isNotEmpty()) {
            warnings += "Unsupported placeholders will be left unchanged: ${unsupportedPlaceholders.sorted().joinToString(", ")}."
        }

        return ValidationReport(
            valid = errors.isEmpty(),
            errors = errors.distinct(),
            warnings = warnings.distinct(),
            placeholders = placeholders.sorted(),
            sha256 = sha256,
            fileSizeBytes = bytes.size.toLong(),
            uncompressedSizeBytes = uncompressedSize,
            entryCount = entryCount
        )
    }

    fun toJson(report: ValidationReport): String = objectMapper.writeValueAsString(report)

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { "%02x".format(it) }

    private fun isForbiddenEntry(entryName: String): Boolean {
        val lower = entryName.lowercase(Locale.ROOT)
        return lower.endsWith("vbaproject.bin") ||
            lower.contains("/activex/") ||
            lower.contains("/embeddings/") ||
            lower.contains("oleobject") ||
            lower.endsWith(".bin") && lower.contains("word/")
    }

    private fun readEntry(zip: ZipInputStream, buffer: ByteArray, allowMore: (Long) -> Boolean): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        while (true) {
            val read = zip.read(buffer)
            if (read == -1) break
            if (!allowMore(read.toLong())) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun extractPlaceholders(xml: String): Set<String> {
        val regex = Regex("\\$\\{([A-Za-z][A-Za-z0-9]*)}")
        return regex.findAll(xml).map { it.groupValues[1] }.toSet()
    }
}
