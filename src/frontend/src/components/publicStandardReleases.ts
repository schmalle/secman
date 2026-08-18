/**
 * Release-option logic for the public standard download page (PublicStandardDownload.tsx).
 *
 * Extracted from the component so it can be unit tested: the frontend test tier runs on Node's
 * own runner, which cannot parse JSX.
 *
 * The page is served to visitors with no SecMan account, which drives the rules below: only
 * *released* versions are listed, and the ACTIVE one is the default. A deployment with nothing
 * released yet falls back to the live requirement set (see defaultReleaseId).
 */

/** The subset of a release this page needs. Deliberately narrower than the API row. */
export interface PublicRelease {
    id: number;
    version: string;
    name: string;
    status: string;
}

/**
 * Statuses a visitor without an account may be offered.
 *
 * The lifecycle is PREPARATION -> ALIGNMENT -> ACTIVE -> ARCHIVED. The first two are drafts
 * being worked on; publishing them here would present an unfinished document as the standard.
 * ARCHIVED stays because an auditor legitimately needs the version that was in force back then.
 */
export const PUBLISHED_STATUSES = ['ACTIVE', 'ARCHIVED'] as const;

/**
 * Narrow an API release list to what the picker may show, newest-looking first.
 *
 * ACTIVE sorts ahead of every ARCHIVED entry so the version in force is the first option;
 * archived entries then follow by descending id, which is the closest thing to
 * reverse-chronological available without trusting a client-side date parse.
 */
export function selectableReleases(releases: PublicRelease[]): PublicRelease[] {
    const published = releases.filter(r => (PUBLISHED_STATUSES as readonly string[]).includes(r.status));
    return published.sort((a, b) => {
        if (a.status !== b.status) {
            return a.status === 'ACTIVE' ? -1 : 1;
        }
        return b.id - a.id;
    });
}

/**
 * The release the page should start on: the ACTIVE one.
 *
 * Returns null when nothing is ACTIVE, which the callers read as "the live requirement set" —
 * the same meaning `releaseId: null` already carries in buildRequirementDownloadUrl and
 * `release: null` in buildPublicStandardUrl, i.e. omit the parameter entirely.
 *
 * Note this page never sends `release=latest`. That spelling answers `404 {"error": "No active
 * release"}` when nothing is ACTIVE; selecting a concrete id, or omitting the parameter, cannot
 * 404 that way. A deployment with no releases at all is a normal early state, not an error.
 */
export function defaultReleaseId(releases: PublicRelease[]): number | null {
    return releases.find(r => r.status === 'ACTIVE')?.id ?? null;
}

/**
 * Human label for one option in the release dropdown.
 *
 * The version carries the meaning, so it leads; the name is a parenthetical and is omitted when
 * it would only repeat the version. ACTIVE is marked because "which one is current" is the
 * question a first-time visitor actually has.
 */
export function releaseOptionLabel(release: PublicRelease): string {
    const trimmedName = release.name?.trim() ?? '';
    const base = trimmedName === '' || trimmedName === release.version
        ? `v${release.version}`
        : `v${release.version} (${trimmedName})`;
    return release.status === 'ACTIVE' ? `${base} — current` : base;
}
