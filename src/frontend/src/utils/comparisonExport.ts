/**
 * Comparison Export Utility
 *
 * Client-side Excel generation for release comparison results using exceljs
 *
 * Features:
 * - Generates Excel workbook with comparison data
 * - Separate sheets for Added, Deleted, Modified requirements
 * - Change Type column for easy identification
 * - Field-level diff display for modified requirements
 * - Formatted headers and styling
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

/**
 * Export comparison results to Excel file
 * 
 * @param comparison Comparison result data
 * @returns Promise that resolves when file is generated and downloaded
 */
export async function exportComparisonToExcel(comparison: ComparisonResult): Promise<void> {
    const workbook = new ExcelJS.Workbook();
    
    // Set workbook properties
    workbook.creator = 'Secman';
    workbook.created = new Date();
    workbook.modified = new Date();
    workbook.lastModifiedBy = 'Secman';

    // Create summary sheet
    const summarySheet = workbook.addWorksheet('Summary');
    
    // Add summary information
    summarySheet.columns = [
        { header: 'Property', key: 'property', width: 30 },
        { header: 'Value', key: 'value', width: 50 },
    ];

    summarySheet.addRows([
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
        { property: '  Added Requirements', value: comparison.added.length },
        { property: '  Deleted Requirements', value: comparison.deleted.length },
        { property: '  Modified Requirements', value: comparison.modified.length },
        { property: '  Unchanged Requirements', value: comparison.unchanged },
        { property: '  Total', value: comparison.added.length + comparison.deleted.length + comparison.modified.length + comparison.unchanged },
    ]);

    // Style summary sheet
    summarySheet.getRow(1).font = { bold: true, size: 12 };
    summarySheet.getRow(1).fill = {
        type: 'pattern',
        pattern: 'solid',
        fgColor: { argb: 'FF4472C4' },
    };
    summarySheet.getRow(1).font = { ...summarySheet.getRow(1).font, color: { argb: 'FFFFFFFF' } };

    // Create Added sheet
    if (comparison.added.length > 0) {
        const addedSheet = workbook.addWorksheet('Added');
        addedSheet.columns = [
            { header: 'Change Type', key: 'changeType', width: 15 },
            { header: 'ID.Revision', key: 'idRevision', width: 15 },
            { header: 'Short Req', key: 'shortreq', width: 20 },
            { header: 'Chapter', key: 'chapter', width: 15 },
            { header: 'Norm', key: 'norm', width: 15 },
            { header: 'Details', key: 'details', width: 50 },
            { header: 'Motivation', key: 'motivation', width: 40 },
            { header: 'Example', key: 'example', width: 40 },
            { header: 'Use Case', key: 'usecase', width: 30 },
        ];

        comparison.added.forEach((req) => {
            addedSheet.addRow({
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
        });

        // Style header
        styleHeaderRow(addedSheet, 'FF70AD47'); // Green
    }

    // Create Deleted sheet
    if (comparison.deleted.length > 0) {
        const deletedSheet = workbook.addWorksheet('Deleted');
        deletedSheet.columns = [
            { header: 'Change Type', key: 'changeType', width: 15 },
            { header: 'ID.Revision', key: 'idRevision', width: 15 },
            { header: 'Short Req', key: 'shortreq', width: 20 },
            { header: 'Chapter', key: 'chapter', width: 15 },
            { header: 'Norm', key: 'norm', width: 15 },
            { header: 'Details', key: 'details', width: 50 },
            { header: 'Motivation', key: 'motivation', width: 40 },
            { header: 'Example', key: 'example', width: 40 },
            { header: 'Use Case', key: 'usecase', width: 30 },
        ];

        comparison.deleted.forEach((req) => {
            deletedSheet.addRow({
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
        });

        // Style header
        styleHeaderRow(deletedSheet, 'FFE74C3C'); // Red
    }

    // Create Modified sheet
    if (comparison.modified.length > 0) {
        const modifiedSheet = workbook.addWorksheet('Modified');
        modifiedSheet.columns = [
            { header: 'Change Type', key: 'changeType', width: 15 },
            { header: 'ID', key: 'internalId', width: 12 },
            { header: 'Old Rev', key: 'oldRevision', width: 10 },
            { header: 'New Rev', key: 'newRevision', width: 10 },
            { header: 'Short Req', key: 'shortreq', width: 20 },
            { header: 'Chapter', key: 'chapter', width: 15 },
            { header: 'Norm', key: 'norm', width: 15 },
            { header: 'Field Changed', key: 'field', width: 20 },
            { header: 'Old Value', key: 'oldValue', width: 40 },
            { header: 'New Value', key: 'newValue', width: 40 },
        ];

        comparison.modified.forEach((req) => {
            // Add a row for each field change
            req.changes.forEach((change, index) => {
                modifiedSheet.addRow({
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
            });

            // Add blank row between requirements
            modifiedSheet.addRow({});
        });

        // Style header
        styleHeaderRow(modifiedSheet, 'FFFFC000'); // Orange/Yellow
    }

    // Generate buffer and trigger download
    const buffer = await workbook.xlsx.writeBuffer();
    const blob = new Blob([buffer], { 
        type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' 
    });

    // Create download link
    const url = window.URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `Release_Comparison_${comparison.fromRelease.version}_to_${comparison.toRelease.version}_${new Date().toISOString().split('T')[0]}.xlsx`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    window.URL.revokeObjectURL(url);
}

/**
 * Style the header row of a worksheet
 */
function styleHeaderRow(sheet: ExcelJS.Worksheet, color: string): void {
    const headerRow = sheet.getRow(1);
    headerRow.font = { bold: true, size: 11, color: { argb: 'FFFFFFFF' } };
    headerRow.fill = {
        type: 'pattern',
        pattern: 'solid',
        fgColor: { argb: color },
    };
    headerRow.alignment = { vertical: 'middle', horizontal: 'left' };
    headerRow.height = 20;

    // Add borders
    headerRow.eachCell((cell) => {
        cell.border = {
            top: { style: 'thin' },
            left: { style: 'thin' },
            bottom: { style: 'thin' },
            right: { style: 'thin' },
        };
    });

    // Enable text wrap for all cells
    sheet.eachRow((row) => {
        row.eachCell((cell) => {
            cell.alignment = { ...cell.alignment, wrapText: true, vertical: 'top' };
        });
    });
}
