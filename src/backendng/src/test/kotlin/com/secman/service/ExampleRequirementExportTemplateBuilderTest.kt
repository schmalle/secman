package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * The example template is what a fresh installation exports with, so it has to satisfy the same
 * validator every uploaded template does. These tests are the guard against the builder and the
 * validator drifting apart — a change to either that makes the shipped example unusable would
 * otherwise only surface at the first export on a new installation.
 */
class ExampleRequirementExportTemplateBuilderTest {

    private val builder = ExampleRequirementExportTemplateBuilder()

    private val validationService = RequirementExportTemplateValidationService(
        objectMapper = ObjectMapper(),
        maxFileSizeBytes = 5 * 1024 * 1024,
        maxUncompressedSizeBytes = 20 * 1024 * 1024,
        maxZipEntries = 512
    )

    @Test
    fun `the example template passes validation in its strictest mode`() {
        val report = validationService.validate(
            bytes = builder.build(),
            filename = ExampleRequirementExportTemplateBuilder.FILENAME,
            contentType = RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE,
            requireRequirementsPlaceholder = true
        )

        assertThat(report.errors).isEmpty()
        assertThat(report.valid).isTrue()
    }

    @Test
    fun `the example template uses only placeholders the exporter substitutes`() {
        val report = validationService.validate(
            bytes = builder.build(),
            filename = ExampleRequirementExportTemplateBuilder.FILENAME,
            contentType = RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE
        )

        // Every unsupported placeholder produces a warning, so no warnings means every ${...} in
        // the shipped document is one the exporter actually fills in.
        assertThat(report.warnings).isEmpty()
        assertThat(report.placeholders)
            .isSubsetOf(RequirementExportTemplateValidationService.ALLOWED_PLACEHOLDERS)
    }

    @Test
    fun `the example template carries the release metadata a cover page needs`() {
        val report = validationService.validate(
            bytes = builder.build(),
            filename = ExampleRequirementExportTemplateBuilder.FILENAME,
            contentType = RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE
        )

        assertThat(report.placeholders).contains(
            "requirements",
            "documentTitle",
            "classification",
            "releaseName",
            "releaseVersion",
            "releaseDate",
            "releaseStatus"
        )
    }

    @Test
    fun `the requirements marker sits in the document body between front and back matter`() {
        XWPFDocument(ByteArrayInputStream(builder.build())).use { document ->
            val bodyTexts = document.paragraphs.map { paragraph ->
                paragraph.runs.joinToString(separator = "") { it.text() ?: "" }
            }

            val markerIndex = bodyTexts.indexOfFirst { it.contains("\${requirements}") }
            assertThat(markerIndex)
                .describedAs("the insertion point must be a top-level body paragraph")
                .isGreaterThanOrEqualTo(0)

            // Front matter before it, back matter after it: that is the whole point of an
            // insertion point rather than appending at the end.
            assertThat(bodyTexts.take(markerIndex)).anyMatch { it.contains("\${documentTitle}") }
            assertThat(bodyTexts.drop(markerIndex + 1)).anyMatch { it.contains("Approval") }
        }
    }

    @Test
    fun `the header and footer carry the classification and export provenance`() {
        XWPFDocument(ByteArrayInputStream(builder.build())).use { document ->
            val headerText = document.headerList.flatMap { it.paragraphs }
                .joinToString(separator = " ") { paragraph -> paragraph.runs.joinToString("") { it.text() ?: "" } }
            val footerText = document.footerList.flatMap { it.paragraphs }
                .joinToString(separator = " ") { paragraph -> paragraph.runs.joinToString("") { it.text() ?: "" } }

            assertThat(headerText).contains("\${classification}")
            assertThat(footerText).contains("\${exportedBy}")
            assertThat(footerText).contains("\${exportDate}")
        }
    }

    @Test
    fun `the builder is deterministic in the content it produces`() {
        // Byte equality is not asserted: POI stamps zip entry timestamps, so two runs differ
        // byte-for-byte while carrying identical content. The placeholder set is the stable part.
        val first = validationService.validate(
            bytes = builder.build(),
            filename = ExampleRequirementExportTemplateBuilder.FILENAME,
            contentType = RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE
        )
        val second = validationService.validate(
            bytes = builder.build(),
            filename = ExampleRequirementExportTemplateBuilder.FILENAME,
            contentType = RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE
        )

        assertThat(first.placeholders).isEqualTo(second.placeholders)
    }

    @Test
    fun `loadOrBuild returns a usable template whether or not the artefact is committed`() {
        // The committed .docx under resources/templates is optional: it is generated from this
        // builder, so its absence must degrade to building rather than to no template at all.
        val report = validationService.validate(
            bytes = builder.loadOrBuild(),
            filename = ExampleRequirementExportTemplateBuilder.FILENAME,
            contentType = RequirementExportTemplateValidationService.DOCX_MEDIA_TYPE,
            requireRequirementsPlaceholder = true
        )

        assertThat(report.valid).isTrue()
    }
}
