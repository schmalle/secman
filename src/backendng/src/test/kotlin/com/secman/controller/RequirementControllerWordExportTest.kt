package com.secman.controller

import com.secman.domain.Release
import com.secman.domain.Requirement
import com.secman.domain.UseCase
import com.secman.repository.NormRepository
import com.secman.repository.ReleaseRepository
import com.secman.repository.RequirementExportTemplateRepository
import com.secman.repository.RequirementExportTemplateUsageRepository
import com.secman.repository.RequirementRepository
import com.secman.repository.RequirementSnapshotRepository
import com.secman.repository.UseCaseRepository
import com.secman.service.InputValidationService
import com.secman.service.ReleaseRequirementScopeService
import com.secman.service.RequirementExportTemplateValidationService
import com.secman.service.RequirementIdService
import com.secman.service.RequirementService
import com.secman.service.TranslationService
import io.mockk.every
import io.mockk.mockk
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CompletableFuture

class RequirementControllerWordExportTest {

    private val translationService: TranslationService = mockk {
        every { getSupportedLanguages() } returns mapOf("de" to "German")
        every { translateTexts(any(), "de") } answers {
            CompletableFuture.completedFuture(firstArg<List<String>>())
        }
    }

    private val controller = RequirementController(
        requirementRepository = mockk<RequirementRepository>(relaxed = true),
        useCaseRepository = mockk<UseCaseRepository>(relaxed = true),
        normRepository = mockk<NormRepository>(relaxed = true),
        translationService = translationService,
        inputValidationService = mockk<InputValidationService>(relaxed = true),
        releaseRepository = mockk<ReleaseRepository>(relaxed = true),
        snapshotRepository = mockk<RequirementSnapshotRepository>(relaxed = true),
        requirementService = mockk<RequirementService>(relaxed = true),
        requirementIdService = mockk<RequirementIdService>(relaxed = true),
        exportTemplateRepository = mockk<RequirementExportTemplateRepository>(relaxed = true),
        exportTemplateUsageRepository = mockk<RequirementExportTemplateUsageRepository>(relaxed = true),
        exportTemplateValidationService = mockk<RequirementExportTemplateValidationService>(relaxed = true),
        releaseRequirementScopeService = mockk<ReleaseRequirementScopeService>(relaxed = true)
    )
    private val publicController = PublicRequirementDownloadController(
        requirementRepository = mockk<RequirementRepository>(relaxed = true),
        useCaseRepository = mockk<UseCaseRepository>(relaxed = true)
    )

    @Test
    fun `translated word export prints each chapter once for grouped requirements`() {
        val requirements = listOf(
            requirement(id = 1L, internalId = "REQ-285", shortreq = "Use SSM for EC2 console access"),
            requirement(id = 2L, internalId = "REQ-286", shortreq = "Restrict direct SSH access")
        )

        val document = createTranslatedWordDocument(requirements)

        val chapterHeadingCount = document.paragraphs
            .map { it.text }
            .count { it == "Chapter: AWS Configuration Requirements" }

        assertThat(chapterHeadingCount).isEqualTo(1)
    }

    @Test
    fun `word export rebases visible requirement numbering to one`() {
        val requirements = listOf(
            requirement(id = 285L, internalId = "REQ-285", shortreq = "Use SSM for EC2 console access"),
            requirement(id = 286L, internalId = "REQ-286", shortreq = "Encrypt private AMIs")
        )

        val document = createWordDocument(requirements)
        val paragraphTexts = document.paragraphs.map { it.text }

        assertThat(paragraphTexts).contains(
            "REQ-1: Use SSM for EC2 console access",
            "ID 1.1",
            "REQ-2: Encrypt private AMIs",
            "ID 2.1"
        )
        assertThat(paragraphTexts).doesNotContain(
            "REQ-285: Use SSM for EC2 console access",
            "ID 285.1"
        )
    }

    @Test
    fun `translated word export rebases visible ID numbering to one`() {
        val requirements = listOf(
            requirement(id = 285L, internalId = "REQ-285", shortreq = "Use SSM for EC2 console access")
        )

        val document = createTranslatedWordDocument(requirements)
        val paragraphTexts = document.paragraphs.map { it.text }

        assertThat(paragraphTexts).contains("ID 1.1")
        assertThat(paragraphTexts).doesNotContain("ID 285.1")
    }

    @Test
    fun `translated word export applies green background to requirement headers`() {
        val requirements = listOf(
            requirement(id = 285L, internalId = "REQ-285", shortreq = "Use SSM for EC2 console access")
        )

        val document = createTranslatedWordDocument(requirements)
        val headerParagraph = document.paragraphs.first { it.text == "REQ-1: Use SSM for EC2 console access" }

        assertThat(shadingFillToHex(headerParagraph.ctp.pPr.shd.fill)).isEqualTo("C1D5C0")
    }

    @Test
    fun `translated word export does not append use cases to ID line`() {
        val requirements = listOf(
            requirement(id = 285L, internalId = "REQ-285", shortreq = "Use SSM for EC2 console access").apply {
                usecases = mutableSetOf(
                    UseCase(id = 1L, name = "Appsec"),
                    UseCase(id = 2L, name = "Aws_Cov")
                )
            }
        )

        val document = createTranslatedWordDocument(requirements)
        val paragraphTexts = document.paragraphs.map { it.text }

        assertThat(paragraphTexts).contains("ID 1.1")
        assertThat(paragraphTexts).doesNotContain("ID 1.1.Appsec.Aws_Cov")
    }

    @Test
    fun `public word export rebases visible requirement numbering to one`() {
        val requirements = listOf(
            requirement(id = 285L, internalId = "REQ-285", shortreq = "Use SSM for EC2 console access")
        )

        val document = createPublicWordDocument(requirements)
        val paragraphTexts = document.paragraphs.map { it.text }

        assertThat(paragraphTexts).contains(
            "REQ-1: Use SSM for EC2 console access",
            "ID 1.1"
        )
        assertThat(paragraphTexts).doesNotContain(
            "REQ-285: Use SSM for EC2 console access",
            "ID 285.1"
        )
    }

    @Test
    fun `templated export renders requirements where the placeholder sat, not at the end`() {
        val document = createTemplatedWordDocument(
            templateBytes = templateDocx(
                "Cover: \${documentTitle}",
                "\${requirements}",
                "Appendix: approval signatures"
            ),
            requirements = listOf(requirement(1L, "REQ-1", "Use SSM for EC2 console access"))
        )

        val texts = document.paragraphs.map { it.text }
        val appendixIndex = texts.indexOfFirst { it.contains("Appendix") }
        val requirementIndex = texts.indexOfFirst { it.contains("REQ-1:") }

        assertThat(requirementIndex).describedAs("requirement content must be rendered").isGreaterThanOrEqualTo(0)
        assertThat(appendixIndex).describedAs("template back matter must survive").isGreaterThanOrEqualTo(0)
        assertThat(requirementIndex)
            .describedAs("requirements must land before the template's back matter")
            .isLessThan(appendixIndex)
    }

    @Test
    fun `templated export removes the placeholder paragraph`() {
        val document = createTemplatedWordDocument(
            templateBytes = templateDocx("Cover", "\${requirements}", "Appendix"),
            requirements = listOf(requirement(1L, "REQ-1", "Use SSM for EC2 console access"))
        )

        assertThat(document.paragraphs.map { it.text }).noneMatch { it.contains("\${requirements}") }
    }

    @Test
    fun `templated export falls back to appending when the template has no insertion point`() {
        val document = createTemplatedWordDocument(
            templateBytes = templateDocx("Cover: \${documentTitle}", "Appendix: approval signatures"),
            requirements = listOf(requirement(1L, "REQ-1", "Use SSM for EC2 console access"))
        )

        val texts = document.paragraphs.map { it.text }
        val appendixIndex = texts.indexOfFirst { it.contains("Appendix") }
        val requirementIndex = texts.indexOfFirst { it.contains("REQ-1:") }

        assertThat(requirementIndex).isGreaterThan(appendixIndex)
    }

    @Test
    fun `release placeholders bind from the release entity, not the document title`() {
        val release = Release(
            id = 7L,
            version = "2026.1",
            name = "Baseline security requirements",
            description = "Everything in force for the 2026 audit",
            status = Release.ReleaseStatus.ACTIVE,
            releaseDate = LocalDate.of(2026, 3, 14).atStartOfDay(ZoneId.systemDefault()).toInstant()
        )

        val document = createTemplatedWordDocument(
            templateBytes = templateDocx(
                "Name: \${releaseName}",
                "Version: \${releaseVersion}",
                "Date: \${releaseDate}",
                "Status: \${releaseStatus}",
                "About: \${releaseDescription}",
                "\${requirements}"
            ),
            requirements = listOf(requirement(1L, "REQ-1", "Use SSM for EC2 console access")),
            // Deliberately a title that does NOT contain the version, so a regression back to
            // slicing `title.substringAfter("Release ")` fails this test.
            title = "Corporate Security Standard",
            release = release
        )

        val texts = document.paragraphs.map { it.text }
        assertThat(texts).contains(
            "Name: Baseline security requirements",
            "Version: 2026.1",
            "Date: 2026-03-14",
            "Status: ACTIVE",
            "About: Everything in force for the 2026 audit"
        )
    }

    @Test
    fun `release placeholders resolve to empty when the export is not pinned to a release`() {
        val document = createTemplatedWordDocument(
            templateBytes = templateDocx("Version:\${releaseVersion}", "Status:\${releaseStatus}", "\${requirements}"),
            requirements = listOf(requirement(1L, "REQ-1", "Use SSM for EC2 console access")),
            title = "All Requirements",
            release = null
        )

        val texts = document.paragraphs.map { it.text }
        assertThat(texts).contains("Version:", "Status:")
    }

    @Test
    fun `use case placeholder binds from the use case entity`() {
        val document = createTemplatedWordDocument(
            templateBytes = templateDocx("UseCase: \${useCaseName}", "\${requirements}"),
            requirements = listOf(requirement(1L, "REQ-1", "Use SSM for EC2 console access")),
            title = "Some unrelated title",
            useCase = UseCase(id = 3L, name = "OT")
        )

        assertThat(document.paragraphs.map { it.text }).contains("UseCase: OT")
    }

    @Test
    fun `control characters in a placeholder value are stripped`() {
        // `classification` is caller-supplied and lands in both the document and the audit log,
        // so CR/LF must never survive into it (log forging).
        val document = createTemplatedWordDocument(
            templateBytes = templateDocx("Class: \${classification}", "\${requirements}"),
            requirements = listOf(requirement(1L, "REQ-1", "Use SSM for EC2 console access")),
            classification = "Secret\r\nX-Injected: true"
        )

        val text = document.paragraphs.map { it.text }.first { it.startsWith("Class:") }
        assertThat(text).doesNotContain("\r")
        assertThat(text).doesNotContain("\n")
        assertThat(text).contains("Secret")
    }

    @Test
    fun `an over-long placeholder value is bounded`() {
        val document = createTemplatedWordDocument(
            templateBytes = templateDocx("Class: \${classification}", "\${requirements}"),
            requirements = listOf(requirement(1L, "REQ-1", "Use SSM for EC2 console access")),
            classification = "A".repeat(5000)
        )

        val text = document.paragraphs.map { it.text }.first { it.startsWith("Class:") }
        assertThat(text.length).isLessThanOrEqualTo("Class: ".length + 512)
    }

    @Test
    fun `templated export preserves a table that precedes the insertion point`() {
        // Regression guard: removing the placeholder by paragraph index rather than body-element
        // index deletes the wrong element once a template contains a table.
        val document = createTemplatedWordDocument(
            templateBytes = templateDocxWithTable(),
            requirements = listOf(requirement(1L, "REQ-1", "Use SSM for EC2 console access"))
        )

        assertThat(document.tables).describedAs("the template's release table must survive").isNotEmpty()
        assertThat(document.paragraphs.map { it.text }).anyMatch { it.contains("Appendix") }
        assertThat(document.paragraphs.map { it.text }).noneMatch { it.contains("\${requirements}") }
    }

    private fun requirement(id: Long, internalId: String, shortreq: String): Requirement =
        Requirement(
            id = id,
            internalId = internalId,
            shortreq = shortreq,
            details = "Details for $shortreq",
            example = "Example for $shortreq",
            motivation = "Motivation for $shortreq",
            chapter = "AWS Configuration Requirements"
        )

    /** A minimal template: one paragraph per supplied line. */
    private fun templateDocx(vararg lines: String): ByteArray {
        val document = XWPFDocument()
        lines.forEach { line -> document.createParagraph().createRun().setText(line) }
        val output = ByteArrayOutputStream()
        document.write(output)
        document.close()
        return output.toByteArray()
    }

    /** A template shaped like the shipped example: cover, release table, marker, back matter. */
    private fun templateDocxWithTable(): ByteArray {
        val document = XWPFDocument()
        document.createParagraph().createRun().setText("Cover: \${documentTitle}")
        val table = document.createTable(1, 2)
        table.getRow(0).getCell(0).paragraphs.first().createRun().setText("Version")
        table.getRow(0).getCell(1).paragraphs.first().createRun().setText("\${releaseVersion}")
        document.createParagraph().createRun().setText("\${requirements}")
        document.createParagraph().createRun().setText("Appendix: approval signatures")
        val output = ByteArrayOutputStream()
        document.write(output)
        document.close()
        return output.toByteArray()
    }

    private fun createTemplatedWordDocument(
        templateBytes: ByteArray,
        requirements: List<Requirement>,
        title: String = "Corporate Security Standard",
        exportedBy: String = "admin",
        language: String = "english",
        classification: String = "Internal",
        release: Release? = null,
        useCase: UseCase? = null
    ): XWPFDocument {
        val method = RequirementController::class.java.getDeclaredMethod(
            "createTemplatedWordDocument",
            ByteArray::class.java,
            List::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            Release::class.java,
            UseCase::class.java
        )
        method.isAccessible = true
        return method.invoke(
            controller, templateBytes, requirements, title, exportedBy, language, classification, release, useCase
        ) as XWPFDocument
    }

    private fun createTranslatedWordDocument(requirements: List<Requirement>): XWPFDocument {
        val method = RequirementController::class.java.getDeclaredMethod(
            "createTranslatedWordDocument",
            List::class.java,
            String::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(controller, requirements, "Translated Requirements", "de") as XWPFDocument
    }

    private fun createWordDocument(requirements: List<Requirement>): XWPFDocument {
        val method = RequirementController::class.java.getDeclaredMethod(
            "createWordDocument",
            List::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(controller, requirements, "All Requirements") as XWPFDocument
    }

    private fun createPublicWordDocument(requirements: List<Requirement>): XWPFDocument {
        val method = PublicRequirementDownloadController::class.java.getDeclaredMethod(
            "createWordDocument",
            List::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(publicController, requirements, "All Requirements") as XWPFDocument
    }

    private fun shadingFillToHex(fill: Any): String =
        when (fill) {
            is ByteArray -> fill.joinToString(separator = "") { "%02X".format(it.toInt() and 0xFF) }
            else -> fill.toString()
        }
}
