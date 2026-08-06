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

    private fun docxTemplate(text: String): ByteArray {
        val document = XWPFDocument()
        document.createParagraph().createRun().setText(text)
        val output = ByteArrayOutputStream()
        document.write(output)
        document.close()
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
