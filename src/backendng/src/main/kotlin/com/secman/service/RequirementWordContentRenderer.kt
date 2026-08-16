package com.secman.service

import com.secman.domain.Requirement
import org.apache.poi.xwpf.usermodel.BreakType
import org.apache.poi.xwpf.usermodel.ParagraphAlignment
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph

/**
 * Renders the requirement body — chapter headings, one "card" per requirement, details /
 * motivation / example / norm reference, and the internal ID line — using [WordExportStyle].
 *
 * This is the single implementation shared by every `.docx` builder that needs a requirement
 * list: the built-in (no-template) requirement export, the public download endpoint
 * (`PublicRequirementDownloadController`), the MCP `export_requirements` tool, and in-place /
 * append rendering into a company template (`RequirementController.createTemplatedWordDocument`).
 * Before this, four call sites hand-rolled the same loop with drifting styling; this is the fix.
 *
 * The translated export (`RequirementController.createTranslatedWordDocument`) is not routed
 * through here: it substitutes pre-translated text per field rather than a [Requirement]'s own
 * fields, which this renderer assumes. It applies the same [WordExportStyle] constants directly.
 */
object RequirementWordContentRenderer {

    private val CANONICAL_USE_CASES = setOf("IT", "OT", "NT")

    fun append(
        document: XWPFDocument,
        requirements: List<Requirement>,
        newParagraph: () -> XWPFParagraph = { document.createParagraph() }
    ) {
        val requirementsByChapter = requirements.groupBy { it.chapter ?: "No Chapter" }
        var requirementNumber = 1
        var isFirstChapter = true
        for ((chapter, chapterRequirements) in requirementsByChapter) {
            if (!isFirstChapter) {
                newParagraph().createRun().addBreak(BreakType.PAGE)
            }
            isFirstChapter = false

            WordExportStyle.sectionHeading(document, chapter, newParagraph)
            newParagraph()

            for (requirement in chapterRequirements) {
                val reqHeaderParagraph = newParagraph()
                WordExportStyle.shadeParagraph(reqHeaderParagraph)
                reqHeaderParagraph.spacingBefore = 80
                reqHeaderParagraph.spacingAfter = 80
                WordExportStyle.run(
                    reqHeaderParagraph, "REQ-$requirementNumber:",
                    size = WordExportStyle.SIZE_REQUIREMENT_ID, bold = true, color = WordExportStyle.COLOR_ACCENT
                )
                WordExportStyle.run(
                    reqHeaderParagraph, " ${requirement.shortreq}",
                    size = WordExportStyle.SIZE_REQUIREMENT_ID, bold = true
                )

                requirement.details?.let { RequirementRichTextRenderer.write(it, newParagraph) }
                requirement.motivation?.let {
                    newParagraph().let { p -> WordExportStyle.run(p, "Motivation", bold = true, color = WordExportStyle.COLOR_SECONDARY) }
                    RequirementRichTextRenderer.write(it, newParagraph)
                }
                requirement.example?.let {
                    newParagraph().let { p -> WordExportStyle.run(p, "Example", bold = true, color = WordExportStyle.COLOR_SECONDARY) }
                    RequirementRichTextRenderer.write(it, newParagraph)
                }
                requirement.norm?.let {
                    val paragraph = newParagraph()
                    WordExportStyle.run(paragraph, "Norm reference: ", bold = true, color = WordExportStyle.COLOR_SECONDARY)
                    WordExportStyle.run(paragraph, it)
                }

                // Internal ID with use cases — small, non-dominant. Only the canonical use case
                // IDs (IT/OT/NT) are appended.
                val idSuffix = buildString {
                    append(requirementNumber)
                    append(".")
                    append(requirement.versionNumber)
                    requirement.usecases.map { it.name }.filter { it in CANONICAL_USE_CASES }.sorted().forEach {
                        append(".")
                        append(it)
                    }
                }
                val idParagraph = newParagraph()
                idParagraph.alignment = ParagraphAlignment.LEFT
                WordExportStyle.run(idParagraph, "ID $idSuffix", size = WordExportStyle.SIZE_META, color = WordExportStyle.COLOR_SECONDARY)
                newParagraph()
                requirementNumber++
            }
        }
    }
}
