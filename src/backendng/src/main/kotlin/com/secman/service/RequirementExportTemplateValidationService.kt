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

        /** The insertion-point token, as it appears in a template. */
        const val REQUIREMENTS_TOKEN = "\${requirements}"

        /** How far past a `${` opener to look when the token was split across runs. */
        private const val SPLIT_PLACEHOLDER_WINDOW = 4096

        /**
         * Cap on split-placeholder probes. Bounds the work a hostile `word/document.xml` can
         * demand; see [findRequirementsAnchorOffset].
         */
        private const val MAX_SPLIT_PLACEHOLDER_PROBES = 256

        // Precompiled: these run over documents up to the uncompressed cap, and recompiling a
        // pattern per call is the difference between one scan and one scan plus a parser.
        private val XML_TAG = Regex("<[^>]*>")
        private val TABLE_OPEN = Regex("<w:tbl(?=[ >/])")
        private val TABLE_CLOSE = Regex("</w:tbl>")

        val ALLOWED_PLACEHOLDERS = setOf(
            "requirements",
            "documentTitle",
            "exportDate",
            "releaseName",
            "releaseVersion",
            "releaseDate",
            "releaseStatus",
            "releaseDescription",
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
        // Tracked separately from `placeholders`: only a body-level ${requirements} can act as the
        // insertion anchor. One that sits in a header, a footer or a table cell cannot host
        // body-level content, so the export would silently drop the requirements there.
        var requirementsAnchorInBody = false
        var requirementsAnchorInTable = false

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
                                val documentXml = entryBytes.toString(Charsets.UTF_8)
                                placeholders += extractPlaceholders(documentXml)
                                val anchorOffset = findRequirementsAnchorOffset(documentXml)
                                if (anchorOffset >= 0) {
                                    requirementsAnchorInTable = isInsideTable(documentXml, anchorOffset)
                                    requirementsAnchorInBody = !requirementsAnchorInTable
                                }
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
        if ("requirements" in placeholders && !requirementsAnchorInBody) {
            // The placeholder exists but cannot be used as an insertion point, so requirement
            // content would be appended at the end instead of rendered where the author put it.
            val where = if (requirementsAnchorInTable) "inside a table" else "in a header or footer"
            val message = "The ${'$'}{requirements} placeholder is $where. Requirement content can " +
                "only be inserted at the top level of the document body; move the placeholder into " +
                "its own paragraph outside any table, header or footer."
            if (requireRequirementsPlaceholder) errors += message else warnings += message
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

    /**
     * Character offset of the `${requirements}` token in `word/document.xml`, or -1.
     *
     * Word may split a typed placeholder across runs, in which case the literal token is absent
     * from the raw XML even though the rendered paragraph reads correctly. Fall back to locating
     * the `${` opener and confirming the rest of the token survives once the intervening XML tags
     * are stripped.
     */
    private fun findRequirementsAnchorOffset(xml: String): Int {
        val literal = xml.indexOf(REQUIREMENTS_TOKEN)
        if (literal >= 0) return literal

        // Bounded on purpose. Each probe copies a window and runs a regex over it, so an
        // unbounded loop turns a document.xml of repeated "${" into ~(uncompressed cap / 2)
        // probes — tens of GB of work for one upload. A real template has a handful of
        // placeholders; anything past the cap is not a template we need to accommodate.
        var searchFrom = 0
        var probes = 0
        while (probes < MAX_SPLIT_PLACEHOLDER_PROBES) {
            val opener = xml.indexOf("\${", searchFrom)
            if (opener < 0) return -1
            probes++
            // Look ahead far enough to cover a placeholder shredded into several runs.
            val window = xml.substring(opener, minOf(xml.length, opener + SPLIT_PLACEHOLDER_WINDOW))
            if (stripXmlTags(window).startsWith(REQUIREMENTS_TOKEN)) return opener
            searchFrom = opener + 2
        }
        return -1
    }

    private fun stripXmlTags(xml: String): String = xml.replace(XML_TAG, "")

    /**
     * Whether [offset] falls inside a `<w:tbl>` element. Counts table open/close tags before the
     * offset rather than parsing, which is sufficient because the tags nest strictly.
     */
    private fun isInsideTable(xml: String, offset: Int): Boolean {
        val before = xml.subSequence(0, offset)
        val opened = TABLE_OPEN.findAll(before).count()
        val closed = TABLE_CLOSE.findAll(before).count()
        return opened > closed
    }
}
