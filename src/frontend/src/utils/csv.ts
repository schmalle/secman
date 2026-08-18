/**
 * Client-side CSV generation.
 *
 * Exports built in the browser never pass through the backend's
 * `ExcelSanitizer`, so the formula-injection guard has to exist here too
 * (CLAUDE.md §OWASP A03): a cell whose text starts with `=`, `+`, `-`, `@`,
 * TAB or CR is executed as a formula when the file is opened in Excel or
 * LibreOffice — `=cmd|'/c calc'!A1`, `=HYPERLINK("http://evil.com","Click")`.
 * Prefixing such a cell with an apostrophe forces it to be read as text.
 *
 * Quoting follows RFC 4180: wrap when the value contains a separator, a quote
 * or a newline, and double any embedded quote.
 */

import { downloadBlob } from './download';

/** Same set the backend's `ExcelSanitizer` neutralizes — keep the two in step. */
const FORMULA_PREFIXES = ['=', '+', '-', '@', '\t', '\r'];

/** A cell we know how to render. Numbers cannot be formulas, so they pass through. */
export type CsvCell = string | number | null | undefined;

/**
 * Render one cell: neutralize a leading formula character, then quote per RFC 4180.
 *
 * Sanitizing happens before quoting — the apostrophe has to end up *inside* the
 * quoted field, otherwise the spreadsheet never sees it.
 */
export function escapeCsvCell(value: CsvCell): string {
    if (value === null || value === undefined) return '';

    let text = typeof value === 'number' ? String(value) : value;
    if (typeof value === 'string' && FORMULA_PREFIXES.includes(text.charAt(0))) {
        text = `'${text}`;
    }

    if (/[",\r\n]/.test(text)) {
        return `"${text.replace(/"/g, '""')}"`;
    }
    return text;
}

/**
 * Build a CSV document from a header row and data rows.
 *
 * Lines are terminated with CRLF, which RFC 4180 requires and Excel on Windows
 * needs to avoid collapsing the file into a single row.
 */
export function buildCsv(headers: string[], rows: CsvCell[][]): string {
    const lines = [headers, ...rows].map((row) => row.map(escapeCsvCell).join(','));
    return lines.join('\r\n');
}

/**
 * Build a CSV and hand it to the browser as a download.
 *
 * The UTF-8 BOM is deliberate: without it Excel decodes the file as the local
 * ANSI code page and mangles every non-ASCII owner name or hostname.
 */
export function downloadCsv(filename: string, headers: string[], rows: CsvCell[][]): void {
    const blob = new Blob(['\uFEFF', buildCsv(headers, rows)], { type: 'text/csv;charset=utf-8;' });
    downloadBlob(blob, filename);
}
