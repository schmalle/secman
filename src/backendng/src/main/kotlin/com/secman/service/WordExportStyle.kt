package com.secman.service

import org.apache.poi.wp.usermodel.HeaderFooterType
import org.apache.poi.xwpf.usermodel.ParagraphAlignment
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFRun
import org.apache.poi.xwpf.usermodel.XWPFTable
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder
import java.math.BigInteger

/**
 * The visual design system for every Word document secman generates directly (not the
 * user-uploaded company template, which is deliberately the customer's own design — see
 * `docs/REQUIREMENT_EXPORT_TEMPLATES.md` and [ExampleRequirementExportTemplateBuilder]).
 *
 * One quiet accent colour, near-black body text on a plain white page, generous whitespace, no
 * decoration that doesn't carry information. Every `.docx` builder in the codebase (the built-in
 * requirement export, the public download endpoint, the MCP export tool, the translated export,
 * and the shipped example template) pulls its palette, type scale and structural helpers from
 * here so the four no longer drift into four different-looking documents, as they had.
 */
object WordExportStyle {

    /**
     * Word resolves a font it cannot find to a metric-compatible substitute (typically Arial) for
     * on-screen display while keeping the requested name in the file, so this renders as intended
     * on macOS/iOS (where Helvetica Neue ships) and degrades gracefully everywhere else — the same
     * mechanism a rebranded company template already relies on for its own fonts.
     */
    const val FONT_FAMILY = "Helvetica Neue"

    /** Primary body/heading text. Near-black rather than pure #000000 — easier to read at length. */
    const val COLOR_TEXT = "1D1D1F"

    /** The one accent colour: section headings, requirement ID badges, rules, links. */
    const val COLOR_ACCENT = "0071E3"

    /** De-emphasised text: captions, header/footer, internal ID lines, hints. */
    const val COLOR_SECONDARY = "6E6E73"

    /** Hairline rules and table borders. */
    const val COLOR_DIVIDER = "D2D2D7"

    /** Card/table-header background fill. Never used for body text on top of it below 10pt. */
    const val COLOR_SURFACE = "F5F5F7"

    /** Classification / sensitivity labels only. */
    const val COLOR_CLASSIFICATION = "D70015"

    const val SIZE_DOCUMENT_TITLE = 28
    const val SIZE_KICKER = 10
    const val SIZE_SECTION_HEADING = 18
    const val SIZE_REQUIREMENT_ID = 11
    const val SIZE_BODY = 10
    const val SIZE_META = 8

    /** Applies the shared font/size/weight/colour to a run. Every run in a generated document goes through this. */
    fun style(
        run: XWPFRun,
        size: Int = SIZE_BODY,
        bold: Boolean = false,
        italic: Boolean = false,
        color: String = COLOR_TEXT
    ): XWPFRun {
        run.fontFamily = FONT_FAMILY
        run.fontSize = size
        run.isBold = bold
        run.isItalic = italic
        run.color = color
        return run
    }

    /** Creates a run on [paragraph] and applies [style] in one call. */
    fun run(
        paragraph: XWPFParagraph,
        text: String,
        size: Int = SIZE_BODY,
        bold: Boolean = false,
        italic: Boolean = false,
        color: String = COLOR_TEXT
    ): XWPFRun = style(paragraph.createRun(), size, bold, italic, color).apply { setText(text) }

    /** Solid background fill behind a paragraph (the requirement-header "card" look). */
    fun shadeParagraph(paragraph: XWPFParagraph, hexFill: String = COLOR_SURFACE) {
        val ctp = paragraph.ctp
        val ppr = if (ctp.isSetPPr) ctp.pPr else ctp.addNewPPr()
        val shd = if (ppr.isSetShd) ppr.shd else ppr.addNewShd()
        shd.fill = hexFill
    }

    /**
     * A thin accent rule under a paragraph — used beneath section headings instead of a heavier
     * background block, the understated "underline" a section title gets rather than a banner.
     */
    fun addBottomRule(paragraph: XWPFParagraph, colorHex: String = COLOR_ACCENT, eighthPoints: Int = 6) {
        val ctp = paragraph.ctp
        val ppr = if (ctp.isSetPPr) ctp.pPr else ctp.addNewPPr()
        val pBdr = if (ppr.isSetPBdr) ppr.pBdr else ppr.addNewPBdr()
        val bottom = pBdr.addNewBottom()
        bottom.`val` = STBorder.SINGLE
        bottom.sz = BigInteger.valueOf(eighthPoints.toLong())
        bottom.space = BigInteger.ZERO
        bottom.color = colorHex
    }

    /** A section heading styled consistently across every builder: Heading1 style, accent-ruled. */
    fun sectionHeading(document: XWPFDocument, text: String, newParagraph: () -> XWPFParagraph = { document.createParagraph() }): XWPFParagraph {
        val paragraph = newParagraph()
        paragraph.style = "Heading1"
        paragraph.spacingAfter = 120
        run(paragraph, text, size = SIZE_SECTION_HEADING, bold = true)
        addBottomRule(paragraph)
        return paragraph
    }

    /** 1-inch margins on every side, set explicitly rather than left to whatever default applies. */
    fun setStandardMargins(document: XWPFDocument, twips: Int = 1440) {
        val sectPr = document.document.body.let { body ->
            if (body.isSetSectPr) body.sectPr else body.addNewSectPr()
        }
        val margins = if (sectPr.isSetPgMar) sectPr.pgMar else sectPr.addNewPgMar()
        margins.top = BigInteger.valueOf(twips.toLong())
        margins.bottom = BigInteger.valueOf(twips.toLong())
        margins.left = BigInteger.valueOf(twips.toLong())
        margins.right = BigInteger.valueOf(twips.toLong())
    }

    /**
     * A minimal header/footer for the documents secman builds itself (not the company template,
     * which brings its own). Small, secondary-grey, out of the way of the content.
     */
    fun addStandardHeaderFooter(document: XWPFDocument, documentTitle: String) {
        val header = document.createHeader(HeaderFooterType.DEFAULT)
        val headerParagraph = header.createParagraph()
        headerParagraph.alignment = ParagraphAlignment.RIGHT
        run(headerParagraph, documentTitle, size = SIZE_META, color = COLOR_SECONDARY)

        val footer = document.createFooter(HeaderFooterType.DEFAULT)
        val footerParagraph = footer.createParagraph()
        footerParagraph.alignment = ParagraphAlignment.CENTER
        run(footerParagraph, "secman", size = SIZE_META, color = COLOR_SECONDARY)
    }

    /** Clean hairline borders on every cell, replacing POI's default plain grid. */
    fun applyHairlineBorders(table: XWPFTable, sizeEighthPoints: Int = 4) {
        table.setTopBorder(XWPFTable.XWPFBorderType.SINGLE, sizeEighthPoints, 0, COLOR_DIVIDER)
        table.setBottomBorder(XWPFTable.XWPFBorderType.SINGLE, sizeEighthPoints, 0, COLOR_DIVIDER)
        table.setLeftBorder(XWPFTable.XWPFBorderType.SINGLE, sizeEighthPoints, 0, COLOR_DIVIDER)
        table.setRightBorder(XWPFTable.XWPFBorderType.SINGLE, sizeEighthPoints, 0, COLOR_DIVIDER)
        table.setInsideHBorder(XWPFTable.XWPFBorderType.SINGLE, sizeEighthPoints, 0, COLOR_DIVIDER)
        table.setInsideVBorder(XWPFTable.XWPFBorderType.SINGLE, sizeEighthPoints, 0, COLOR_DIVIDER)
    }

    /** Shades a table's first row as a header band and bolds its text. */
    fun shadeHeaderRow(table: XWPFTable) {
        table.getRow(0)?.tableCells?.forEach { cell ->
            cell.color = COLOR_SURFACE
            cell.paragraphs.forEach { paragraph -> paragraph.runs.forEach { it.isBold = true } }
        }
    }
}
