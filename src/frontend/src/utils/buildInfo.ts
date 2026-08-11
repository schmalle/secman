/**
 * Build metadata rendered in the application footer.
 *
 * The values are resolved ONCE, at build time, by `buildinfo.mjs` and inlined
 * into the bundle through Vite's `define` (see `astro.config.mjs`). They are
 * deliberately not resolved while rendering: Astro runs with `output: "server"`,
 * so component frontmatter executes on *every* request, and the deployed
 * container ships neither a `.git` directory nor a `git` binary.
 *
 * This module holds the pure half — validation and formatting — so it can be
 * unit tested without a build.
 */

/** Raw, unvalidated stamps as collected by `buildinfo.mjs`. */
export interface RawBuildInfo {
    /** When the frontend bundle was built. Any `Date`-parseable string. */
    buildTime?: string | null;
    /** Commit the build was made from. Any `Date`-parseable string. */
    commitTime?: string | null;
    /** Abbreviated commit hash of that commit. */
    commitHash?: string | null;
}

/** Validated build metadata. `null` means "the build could not determine this". */
export interface BuildInfo {
    /** ISO-8601 UTC timestamp of the build, or `null` if unknown. */
    buildTime: string | null;
    /** ISO-8601 UTC timestamp of the source commit, or `null` if unknown. */
    commitTime: string | null;
    /** Abbreviated commit hash, or `null` if the build had no git context. */
    commitHash: string | null;
}

export const APP_NAME = 'SecMan';
export const APP_VERSION = '1.0.0';

/** First year of the copyright range shown in the footer. */
export const COPYRIGHT_START_YEAR = 2017;

/** Rendered when a stamp is missing — never a stale hardcoded date. */
export const UNKNOWN_LABEL = 'unknown';

/** Abbreviated or full git object name. Never interpolated unvalidated. */
const COMMIT_HASH_PATTERN = /^[0-9a-f]{7,40}$/;

/** Guards against a pathological `define` payload reaching `Date.parse`. */
const MAX_STAMP_LENGTH = 64;

const EMPTY_BUILD_INFO: BuildInfo = { buildTime: null, commitTime: null, commitHash: null };

/**
 * Canonicalise a timestamp to ISO-8601 UTC.
 *
 * Accepts anything `Date.parse` understands — git's `%cI` carries the committer's
 * local offset (`2026-08-11T22:18:51+02:00`), which is normalised to `Z` here so
 * the footer never depends on the build machine's timezone.
 */
export function normalizeTimestamp(value: unknown): string | null {
    if (typeof value !== 'string') return null;
    const trimmed = value.trim();
    if (trimmed.length === 0 || trimmed.length > MAX_STAMP_LENGTH) return null;

    const parsed = Date.parse(trimmed);
    if (Number.isNaN(parsed)) return null;

    return new Date(parsed).toISOString();
}

/** Accept a commit hash only if it looks like one; anything else becomes `null`. */
export function normalizeCommitHash(value: unknown): string | null {
    if (typeof value !== 'string') return null;
    const trimmed = value.trim().toLowerCase();
    return COMMIT_HASH_PATTERN.test(trimmed) ? trimmed : null;
}

/**
 * Validate the injected payload.
 *
 * Accepts the JSON string produced by `define`, an already-parsed object, or
 * `null`/`undefined` when the injection did not happen at all (a plain `astro
 * dev` of a stale checkout, say). Every failure mode degrades to "unknown"
 * rather than to a wrong date.
 */
export function normalizeBuildInfo(value: unknown): BuildInfo {
    let raw: unknown = value;

    if (typeof raw === 'string') {
        try {
            raw = JSON.parse(raw);
        } catch {
            return { ...EMPTY_BUILD_INFO };
        }
    }

    if (raw === null || typeof raw !== 'object') return { ...EMPTY_BUILD_INFO };

    const candidate = raw as RawBuildInfo;
    return {
        buildTime: normalizeTimestamp(candidate.buildTime),
        commitTime: normalizeTimestamp(candidate.commitTime),
        commitHash: normalizeCommitHash(candidate.commitHash),
    };
}

/** `YYYY-MM-DD HH:MM UTC`, always in UTC so every deployment reads the same. */
export function formatBuildTimestamp(isoTimestamp: string | null): string {
    if (isoTimestamp === null) return UNKNOWN_LABEL;
    // "2026-08-11T20:18:51.000Z" -> "2026-08-11 20:18 UTC"
    return `${isoTimestamp.slice(0, 10)} ${isoTimestamp.slice(11, 16)} UTC`;
}

/**
 * The footer's build stamp: when the bundle was built, plus the commit it came
 * from when the build had git context.
 */
export function formatBuildStamp(info: BuildInfo): string {
    const built = formatBuildTimestamp(info.buildTime);
    return info.commitHash === null ? built : `${built} (${info.commitHash})`;
}

/** Tooltip detail — the commit date, which is not the same thing as the build date. */
export function formatCommitTooltip(info: BuildInfo): string | undefined {
    if (info.commitTime === null) return undefined;
    return `Commit ${info.commitHash ?? ''} committed ${formatBuildTimestamp(info.commitTime)}`.replace(/\s+/g, ' ').trim();
}

/**
 * `2017–2026`, ending at the build year rather than a hardcoded literal that
 * silently goes stale every January.
 */
export function formatCopyrightRange(
    isoTimestamp: string | null,
    startYear: number = COPYRIGHT_START_YEAR,
): string {
    if (isoTimestamp === null) return String(startYear);

    const buildYear = Number.parseInt(isoTimestamp.slice(0, 4), 10);
    if (Number.isNaN(buildYear) || buildYear <= startYear) return String(startYear);

    return `${startYear}–${buildYear}`;
}
