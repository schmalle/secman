/**
 * URL construction for the public requirement/standard download endpoints.
 *
 * Extracted from RequirementDownload.tsx so it can be unit tested: the frontend test tier
 * runs on Node's own runner, which cannot parse JSX.
 *
 * The same endpoints are documented for direct GET use in docs/PUBLIC_STANDARD_DOWNLOAD.md —
 * keep the two in step.
 */

export type RequirementExportFormat = 'docx' | 'xlsx';

export interface RequirementDownloadScope {
    format: RequirementExportFormat;
    /** Narrow to one use case. Takes precedence over standardId if both are somehow set. */
    useCaseId?: number | null;
    /** Narrow to one standard (its use cases' requirements). */
    standardId?: number | null;
    /** Freeze to a release. Null or undefined means the live requirement set. */
    releaseId?: number | null;
}

/**
 * Build the download URL for a scope.
 *
 * Use case stays a path segment (`/export/docx/usecase/12`) because that route predates the
 * standard filter; standard and release ride as query parameters, which is also what the
 * documented `?standard=IT%2FOT%20Security&release=latest` form uses.
 */
export function buildRequirementDownloadUrl(scope: RequirementDownloadScope): string {
    const base = scope.useCaseId != null
        ? `/api/requirements/export/${scope.format}/usecase/${scope.useCaseId}`
        : `/api/requirements/export/${scope.format}`;

    const params = new URLSearchParams();
    if (scope.useCaseId == null && scope.standardId != null) {
        params.set('standardId', String(scope.standardId));
    }
    if (scope.releaseId != null) {
        params.set('releaseId', String(scope.releaseId));
    }

    const query = params.toString();
    return query ? `${base}?${query}` : base;
}

/**
 * The shareable, name-based equivalent of a standard download — the form meant to be pasted
 * into a script or a browser without knowing any internal id.
 *
 * `release` is a release *version* string, the literal `latest` for whichever release is
 * ACTIVE, or null to omit the parameter and get the live (unfrozen) requirement set. Null is
 * deliberately not a synonym for `latest`: "live" and "the active release" are different
 * answers, and a link should say which one it means.
 */
export function buildPublicStandardUrl(
    origin: string,
    standardName: string,
    format: RequirementExportFormat,
    release: string | null,
): string {
    const params = new URLSearchParams();
    params.set('standard', standardName);
    if (release !== null) {
        params.set('release', release);
    }
    return `${origin}/api/requirements/export/${format}?${params.toString()}`;
}
