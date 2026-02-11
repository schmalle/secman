/**
 * Comparison Export Utility
 *
 * Client-side Excel generation for release comparison results using exceljs
 *
 * Features:
 * - Generates Excel workbook with comparison data
 * - Summary sheet with release info and statistics
 * - Consolidated "All Changes" sheet with color-coded rows
 * - Separate sheets for Added, Deleted, Modified requirements
 * - Auto-filter, frozen headers, alternating row colors, data borders
 * - Field-level diff display for modified requirements
 *
 * Related to: Feature 012-build-ui-for, User Story 4 (Compare Releases)
 */

import ExcelJS from 'exceljs';

export interface ReleaseInfo {
    id: number;
    version: string;
    name: string;
    createdAt: string;
}

export interface RequirementSnapshotSummary {
    id: number;
    originalRequirementId: number;
    internalId: string;
    revision: number;
    idRevision: string;
    shortreq: string;
    chapter?: string;
    norm?: string;
    details: string | null;
    motivation?: string | null;
    example?: string | null;
    usecase?: string | null;
    language?: string | null;
}

export interface FieldChange {
    fieldName: string;
    oldValue: string | null;
    newValue: string | null;
}

export interface RequirementDiff {
    id: number;
    originalRequirementId?: number;
    internalId: string;
    oldRevision: number;
    newRevision: number;
    shortreq: string;
    chapter?: string;
    norm?: string;
    changes: FieldChange[];
}

export interface ComparisonResult {
    fromRelease: ReleaseInfo;
    toRelease: ReleaseInfo;
    added: RequirementSnapshotSummary[];
    deleted: RequirementSnapshotSummary[];
    modified: RequirementDiff[];
    unchanged: number;
}

const COLORS = {
    headerBlue: 'FF4472C4',
    addedGreen: 'FF70AD47',
    addedRowLight: 'FFE2EFDA',
    deletedRed: 'FFE74C3C',
    deletedRowLight: 'FFFCE4EC',
    modifiedYellow: 'FFFFC000',
    modifiedRowLight: 'FFFFF8E1',
    alternateGray: 'FFF2F2F2',
    white: 'FFFFFFFF',
    black: 'FF000000',
};

const THIN_BORDER: Partial<ExcelJS.Borders> = {
    top: { style: 'thin' },
    left: { style: 'thin' },
    bottom: { style: 'thin' },
    right: { style: 'thin' },
};

/**
 * Export comparison results to Excel file
 */
export async function exportComparisonToExcel(comparison: ComparisonResult): Promise<void> {
    const workbook = new ExcelJS.Workbook();

    workbook.creator = 'Secman';
    workbook.created = new Date();
    workbook.modified = new Date();
    workbook.lastModifiedBy = 'Secman';

    // Normalize arrays - API may return undefined/null for empty lists
    const added = comparison.added ?? [];
    const deleted = comparison.deleted ?? [];
    const modified = comparison.modified ?? [];
    const safe = { ...comparison, added, deleted, modified };

    createSummarySheet(workbook, safe);
    createAllChangesSheet(workbook, safe);

    if (added.length > 0) {
        createSnapshotSheet(workbook, 'Added', added, 'ADDED', COLORS.addedGreen, COLORS.addedRowLight);
    }
    if (deleted.length > 0) {
        createSnapshotSheet(workbook, 'Deleted', deleted, 'DELETED', COLORS.deletedRed, COLORS.deletedRowLight);
    }
    if (modified.length > 0) {
        createModifiedSheet(workbook, modified);
    }

    const buffer = await workbook.xlsx.writeBuffer();
    const blob = new Blob([buffer], {
        type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    });

    const fromVersion = comparison.fromRelease.version.replace(/[^a-zA-Z0-9._-]/g, '');
    const toVersion = comparison.toRelease.version.replace(/[^a-zA-Z0-9._-]/g, '');
    const dateStr = new Date().toISOString().split('T')[0];

    const url = window.URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `Release_Comparison_${fromVersion}_vs_${toVersion}_${dateStr}.xlsx`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    window.URL.revokeObjectURL(url);
}

function createSummarySheet(workbook: ExcelJS.Workbook, comparison: ComparisonResult): void {
    const sheet = workbook.addWorksheet('Summary');

    sheet.columns = [
        { header: 'Property', key: 'property', width: 30 },
        { header: 'Value', key: 'value', width: 50 },
    ];

    sheet.addRows([
        { property: 'Comparison Date', value: new Date().toLocaleString() },
        { property: '', value: '' },
        { property: 'From Release', value: '' },
        { property: '  Version', value: comparison.fromRelease.version },
        { property: '  Name', value: comparison.fromRelease.name },
        { property: '  Created', value: new Date(comparison.fromRelease.createdAt).toLocaleString() },
        { property: '', value: '' },
        { property: 'To Release', value: '' },
        { property: '  Version', value: comparison.toRelease.version },
        { property: '  Name', value: comparison.toRelease.name },
        { property: '  Created', value: new Date(comparison.toRelease.createdAt).toLocaleString() },
        { property: '', value: '' },
        { property: 'Summary', value: '' },
        { property: '  Added Requirements', value: (comparison.added ?? []).length },
        { property: '  Deleted Requirements', value: (comparison.deleted ?? []).length },
        { property: '  Modified Requirements', value: (comparison.modified ?? []).length },
        { property: '  Unchanged Requirements', value: comparison.unchanged ?? 0 },
        {
            property: '  Total',
            value:
                (comparison.added ?? []).length +
                (comparison.deleted ?? []).length +
                (comparison.modified ?? []).length +
                (comparison.unchanged ?? 0),
        },
    ]);

    // Style header row
    const headerRow = sheet.getRow(1);
    headerRow.font = { bold: true, size: 12, color: { argb: COLORS.white } };
    headerRow.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: COLORS.headerBlue } };

    // Bold section headers
    [4, 9, 14].forEach((rowNum) => {
        const row = sheet.getRow(rowNum);
        row.font = { bold: true, size: 11 };
    });

    // Bold totals row
    const totalRow = sheet.getRow(19);
    totalRow.font = { bold: true };
}

function createAllChangesSheet(workbook: ExcelJS.Workbook, comparison: ComparisonResult): void {
    const added = comparison.added ?? [];
    const deleted = comparison.deleted ?? [];
    const modified = comparison.modified ?? [];
    const totalChanges = added.length + deleted.length + modified.length;
    if (totalChanges === 0) return;

    const sheet = workbook.addWorksheet('All Changes');

    sheet.columns = [
        { header: 'Change Type', key: 'changeType', width: 14 },
        { header: 'ID.Revision', key: 'idRevision', width: 15 },
        { header: 'Short Req', key: 'shortreq', width: 30 },
        { header: 'Chapter', key: 'chapter', width: 15 },
        { header: 'Norm', key: 'norm', width: 15 },
        { header: 'Details', key: 'details', width: 40 },
        { header: 'Motivation', key: 'motivation', width: 30 },
        { header: 'Example', key: 'example', width: 30 },
        { header: 'Use Case', key: 'usecase', width: 25 },
    ];

    // Add added rows
    added.forEach((req) => {
        const row = sheet.addRow({
            changeType: 'ADDED',
            idRevision: req.idRevision,
            shortreq: req.shortreq,
            chapter: req.chapter || '',
            norm: req.norm || '',
            details: req.details || '',
            motivation: req.motivation || '',
            example: req.example || '',
            usecase: req.usecase || '',
        });
        applyRowFill(row, COLORS.addedRowLight);
    });

    // Add deleted rows
    deleted.forEach((req) => {
        const row = sheet.addRow({
            changeType: 'DELETED',
            idRevision: req.idRevision,
            shortreq: req.shortreq,
            chapter: req.chapter || '',
            norm: req.norm || '',
            details: req.details || '',
            motivation: req.motivation || '',
            example: req.example || '',
            usecase: req.usecase || '',
        });
        applyRowFill(row, COLORS.deletedRowLight);
    });

    // Add modified rows
    modified.forEach((req) => {
        const row = sheet.addRow({
            changeType: 'MODIFIED',
            idRevision: `${req.internalId}.${req.newRevision}`,
            shortreq: req.shortreq,
            chapter: req.chapter || '',
            norm: req.norm || '',
            details: req.changes.map((c) => `${c.fieldName}: "${c.oldValue || ''}" -> "${c.newValue || ''}"`).join('; '),
            motivation: '',
            example: '',
            usecase: '',
        });
        applyRowFill(row, COLORS.modifiedRowLight);
    });

    applySheetFormatting(sheet, COLORS.headerBlue);
}

function createSnapshotSheet(
    workbook: ExcelJS.Workbook,
    name: string,
    items: RequirementSnapshotSummary[],
    changeType: string,
    headerColor: string,
    rowTintColor: string
): void {
    const sheet = workbook.addWorksheet(name);

    sheet.columns = [
        { header: 'Change Type', key: 'changeType', width: 14 },
        { header: 'ID.Revision', key: 'idRevision', width: 15 },
        { header: 'Short Req', key: 'shortreq', width: 30 },
        { header: 'Chapter', key: 'chapter', width: 15 },
        { header: 'Norm', key: 'norm', width: 15 },
        { header: 'Details', key: 'details', width: 40 },
        { header: 'Motivation', key: 'motivation', width: 30 },
        { header: 'Example', key: 'example', width: 30 },
        { header: 'Use Case', key: 'usecase', width: 25 },
    ];

    items.forEach((req, index) => {
        const row = sheet.addRow({
            changeType: changeType,
            idRevision: req.idRevision,
            shortreq: req.shortreq,
            chapter: req.chapter || '',
            norm: req.norm || '',
            details: req.details || '',
            motivation: req.motivation || '',
            example: req.example || '',
            usecase: req.usecase || '',
        });

        // Alternating row colors
        if (index % 2 === 1) {
            applyRowFill(row, COLORS.alternateGray);
        }
    });

    applySheetFormatting(sheet, headerColor);
}

function createModifiedSheet(workbook: ExcelJS.Workbook, modified: RequirementDiff[]): void {
    const sheet = workbook.addWorksheet('Modified');

    sheet.columns = [
        { header: 'Change Type', key: 'changeType', width: 14 },
        { header: 'ID', key: 'internalId', width: 12 },
        { header: 'Old Rev', key: 'oldRevision', width: 10 },
        { header: 'New Rev', key: 'newRevision', width: 10 },
        { header: 'Short Req', key: 'shortreq', width: 30 },
        { header: 'Chapter', key: 'chapter', width: 15 },
        { header: 'Norm', key: 'norm', width: 15 },
        { header: 'Field Changed', key: 'field', width: 18 },
        { header: 'Old Value', key: 'oldValue', width: 35 },
        { header: 'New Value', key: 'newValue', width: 35 },
    ];

    modified.forEach((req) => {
        req.changes.forEach((change, index) => {
            const row = sheet.addRow({
                changeType: index === 0 ? 'MODIFIED' : '',
                internalId: index === 0 ? req.internalId : '',
                oldRevision: index === 0 ? req.oldRevision : '',
                newRevision: index === 0 ? req.newRevision : '',
                shortreq: index === 0 ? req.shortreq : '',
                chapter: index === 0 ? req.chapter || '' : '',
                norm: index === 0 ? req.norm || '' : '',
                field: change.fieldName,
                oldValue: change.oldValue || '',
                newValue: change.newValue || '',
            });

            // Tint the summary row for each requirement
            if (index === 0) {
                applyRowFill(row, COLORS.modifiedRowLight);
                row.font = { bold: true };
            }
        });
    });

    applySheetFormatting(sheet, COLORS.modifiedYellow);
}

/**
 * Apply common formatting to a data sheet: header styling, auto-filter, frozen header, borders, text wrap
 */
function applySheetFormatting(sheet: ExcelJS.Worksheet, headerColor: string): void {
    const headerRow = sheet.getRow(1);
    headerRow.font = { bold: true, size: 11, color: { argb: COLORS.white } };
    headerRow.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: headerColor } };
    headerRow.alignment = { vertical: 'middle', horizontal: 'left' };
    headerRow.height = 22;

    // Auto-filter
    const lastCol = sheet.columns.length;
    const lastRow = sheet.rowCount;
    if (lastRow > 1) {
        sheet.autoFilter = {
            from: { row: 1, column: 1 },
            to: { row: lastRow, column: lastCol },
        };
    }

    // Frozen header
    sheet.views = [{ state: 'frozen', ySplit: 1 }];

    // Borders + text wrap on all cells
    sheet.eachRow((row) => {
        row.eachCell((cell) => {
            cell.border = THIN_BORDER;
            cell.alignment = { ...cell.alignment, wrapText: true, vertical: 'top' };
        });
    });
}

function applyRowFill(row: ExcelJS.Row, color: string): void {
    row.eachCell({ includeEmpty: true }, (cell) => {
        cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: color } };
    });
}
