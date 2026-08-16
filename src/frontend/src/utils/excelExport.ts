/**
 * Single-sheet Excel export helper.
 *
 * Three components under `components/statistics/` each hand-rolled the same routine:
 * dynamic-import exceljs, stamp the workbook, declare columns, add rows, style row 1,
 * write the buffer, wrap it in a Blob with the OOXML MIME type, and download it. Only
 * the columns, the header colour and the filename ever differed.
 *
 * This covers the simple case — one sheet, one header row, plain data rows. The two
 * richer exporters (`comparisonExport.ts`, `vulnerabilityExport.ts`) build several
 * sheets with per-cell fills and auto-filters and keep driving exceljs directly; they
 * share `downloadBlob` and the helpers below rather than this function.
 *
 * Note for anyone unifying further: the three call sites do NOT agree on a header
 * colour (`FF4472C4` vs `FF3D4F4F`), and the other two exporters add a third and
 * fourth (`FF2C3E50`, and comparisonExport's own COLORS table). That drift is
 * preserved here rather than silently resolved — picking one is a design decision,
 * not a refactor.
 */

import type ExcelJS from 'exceljs';
import { downloadBlob } from './download';

/** OOXML spreadsheet MIME type — the string every export site had inlined. */
const XLSX_MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

const THIN_BORDER: Partial<ExcelJS.Borders> = {
    top: { style: 'thin' },
    left: { style: 'thin' },
    bottom: { style: 'thin' },
    right: { style: 'thin' },
};

export interface SheetColumn {
    header: string;
    /** Key the row objects are addressed by. */
    key: string;
    width: number;
}

export interface SingleSheetExport {
    /** Name of the downloaded file, including the `.xlsx` extension. */
    filename: string;
    /** Worksheet tab name. */
    sheetName: string;
    columns: SheetColumn[];
    /** Row objects keyed by `SheetColumn.key`. */
    rows: Array<Record<string, unknown>>;
    /** ARGB fill for the header row. */
    headerColor: string;
    /** Header row height in points. */
    headerHeight?: number;
    /** Centre the header text instead of leaving it left-aligned. */
    centerHeader?: boolean;
    /** Draw thin borders around every cell, header included. */
    bordered?: boolean;
}

/**
 * Build a one-sheet workbook and hand it to the browser as a download.
 *
 * exceljs is imported dynamically so it stays out of the initial bundle — it is by far
 * the heaviest dependency here and only matters once someone clicks Export.
 */
export async function downloadSingleSheetWorkbook(options: SingleSheetExport): Promise<void> {
    const { default: ExcelJSRuntime } = await import('exceljs');
    const workbook = new ExcelJSRuntime.Workbook();
    workbook.creator = 'Secman';
    workbook.created = new Date();

    const sheet = workbook.addWorksheet(options.sheetName);
    sheet.columns = options.columns;
    options.rows.forEach((row) => sheet.addRow(row));

    const headerRow = sheet.getRow(1);
    headerRow.font = { bold: true, color: { argb: 'FFFFFFFF' } };
    headerRow.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: options.headerColor } };
    headerRow.height = options.headerHeight ?? 22;
    if (options.centerHeader) {
        headerRow.alignment = { vertical: 'middle', horizontal: 'center' };
    }

    if (options.bordered) {
        sheet.eachRow((row) => {
            row.eachCell((cell) => {
                cell.border = THIN_BORDER;
            });
        });
    }

    const buffer = await workbook.xlsx.writeBuffer();
    downloadBlob(new Blob([buffer], { type: XLSX_MIME }), options.filename);
}

/**
 * Reduce a value to characters that are safe in a filename on every platform.
 *
 * Anything outside `[A-Za-z0-9-]` becomes an underscore, so a CVE id, product name or
 * hostname can be dropped straight into an export filename. `maxLength` truncates long
 * product names, which can otherwise exceed the path limits of the target filesystem.
 */
export function sanitizeFilenamePart(value: string, maxLength = 50): string {
    return value.replace(/[^a-zA-Z0-9-]/g, '_').substring(0, maxLength);
}

/** Today's date as `YYYY-MM-DD`, the suffix every export filename carries. */
export function todayStamp(): string {
    return new Date().toISOString().split('T')[0];
}
