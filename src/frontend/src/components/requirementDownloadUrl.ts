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

/** Scope for the anonymous public download route (no translation variant). */
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
 * Scope for the authenticated requirement exports on the Export and Import/Export
 * screens. Differs from RequirementDownloadScope in supporting the translated
 * endpoint variants (`/translated/{language}`), which the public download does not have.
 */
export interface AuthenticatedRequirementExportScope {
    format: RequirementExportFormat;
    /** Narrow to one use case (path segment, like the public route). */
    useCaseId?: number | null;
    /** Freeze to a release. Null or undefined means the live requirement set. */
    releaseId?: number | null;
    /**
     * Language for a translated export, or null for the plain endpoint.
     * Callers pass null for 'english' — English is the untranslated source.
     */
    translationLanguage?: string | null;
}

/**
 * Build the endpoint for an authenticated requirement export.
 *
 * One builder for both the Export and Import/Export screens — their four
 * hand-rolled copies had drifted (the Import/Export side lost the releaseId
 * parameter on the use-case route and never gained the translated variants).
 * Route shapes, matching RequirementController:
 *   /api/requirements/export/{format}[/usecase/{id}][/translated/{language}][?releaseId=N]
 */
export function buildAuthenticatedRequirementExportUrl(scope: AuthenticatedRequirementExportScope): string {
    let base = scope.useCaseId != null
        ? `/api/requirements/export/${scope.format}/usecase/${scope.useCaseId}`
        : `/api/requirements/export/${scope.format}`;

    if (scope.translationLanguage) {
        base += `/translated/${encodeURIComponent(scope.translationLanguage)}`;
    }

    return scope.releaseId != null ? `${base}?releaseId=${scope.releaseId}` : base;
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
