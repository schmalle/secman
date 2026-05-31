package com.secman.controller

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
import com.secman.service.RequirementExportTemplateValidationService
import com.secman.service.RequirementIdService
import com.secman.service.RequirementService
import com.secman.service.TranslationService
import io.mockk.every
import io.mockk.mockk
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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
        exportTemplateValidationService = mockk<RequirementExportTemplateValidationService>(relaxed = true)
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
