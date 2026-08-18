/**
 * Presentation of the requirement XLSX import result.
 *
 * Lives in a `.ts` module rather than inside the two `.tsx` components that show it, because those
 * components had byte-identical status lines and because pure logic has to be outside JSX to be
 * unit-testable at all (docs/TESTING.md §Frontend).
 *
 * The shape mirrors `ImportController.ImportResponse`. `rowsSkipped` / `skipReasons` are optional:
 * a client running against an older backend simply gets the count-only message.
 */

export interface RequirementImportResponse {
    message?: string;
    requirementsProcessed?: number;
    rowsSkipped?: number;
    skipReasons?: string[];
}

export interface ImportResultSummary {
    /** One-line headline for the status banner. */
    headline: string;
    /** Per-row explanations, already bounded by the server. Empty when nothing was skipped. */
    details: string[];
    /** Skipped rows mean the spreadsheet and the result disagree — worth a warning, not a success. */
    hasSkips: boolean;
}

/**
 * Turns an import response into something that answers "why did I get N?" without reading a log.
 *
 * A partial import used to render as plain "Success: … (105 requirements added)", giving no hint
 * that the file held more rows. The count of skipped rows is therefore part of the headline, not
 * hidden behind a details toggle.
 */
export function summarizeImportResult(data: RequirementImportResponse | null | undefined): ImportResultSummary {
    const processed = data?.requirementsProcessed ?? 0;
    const skipped = data?.rowsSkipped ?? 0;
    const message = data?.message?.trim() || 'File uploaded and processed.';

    if (skipped > 0) {
        return {
            headline: `${message} ${processed} requirement${processed === 1 ? '' : 's'} added, ` +
                `${skipped} row${skipped === 1 ? '' : 's'} skipped.`,
            details: data?.skipReasons ?? [],
            hasSkips: true,
        };
    }

    return {
        headline: `${message} (${processed} requirement${processed === 1 ? '' : 's'} added)`,
        details: [],
        hasSkips: false,
    };
}
