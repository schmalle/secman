/**
 * Date Utilities
 *
 * Deterministic, cross-browser parsing of server-supplied timestamp strings.
 *
 * Why this exists: backend timestamps have historically arrived as zoneless values
 * ("2026-07-02T04:14:18.246830" or space-separated "2026-07-02 04:14:18.246830") with
 * microsecond precision. Native `new Date(...)` parses these DIFFERENTLY across browser JS
 * engines — V8 (Edge/Chromium) vs JavaScriptCore (Safari):
 *   - Out-of-spec fractional precision (>3 digits) can yield Invalid Date in one engine only.
 *   - A zoneless string is interpreted as *browser-local* time, so any age/expiry math is off
 *     by the client's UTC offset — differing by machine/timezone.
 * Both effects produced a production bug that appeared only in Edge/Windows.
 *
 * Route every LOAD-BEARING date parse (comparisons, sorts, age/expiry decisions) through
 * `parseServerDate` so all engines agree on the same instant.
 */

/**
 * Parse a server timestamp string into a Date deterministically across engines.
 *
 * Normalization applied:
 *  - space separator ("YYYY-MM-DD HH:MM:SS") -> ISO 'T'
 *  - fractional seconds truncated to 3 digits (ECMAScript only guarantees millisecond precision)
 *  - if no timezone designator is present, the value is treated as UTC (trailing 'Z') so every
 *    engine yields the same instant instead of browser-local time
 *
 * @param value Server timestamp string (ISO-8601, with or without zone / sub-second precision)
 * @returns A valid Date, or null for empty/unparseable input (never a NaN Date)
 */
export function parseServerDate(value: string | null | undefined): Date | null {
    if (!value) return null;
    let s = String(value).trim();
    if (!s) return null;

    // "YYYY-MM-DD HH:MM:SS" (space) -> ISO 'T'
    if (s.includes(' ') && !s.includes('T')) {
        s = s.replace(' ', 'T');
    }

    // Does it already carry a timezone designator (Z or ±hh[:]mm)?
    const hasZone = /(?:[zZ]|[+-]\d\d:?\d\d)$/.test(s);

    // Truncate fractional seconds to at most 3 digits; the trailing zone (if any) is preserved
    // because the regex only consumes digits immediately after a 3-digit fraction.
    s = s.replace(/(\.\d{3})\d+/, '$1');

    if (!hasZone) {
        s = s + 'Z';
    }

    const d = new Date(s);
    return Number.isNaN(d.getTime()) ? null : d;
}

/**
 * Convenience: parse a server timestamp to epoch milliseconds, or null if unparseable.
 * Useful for sort comparators that must be NaN-safe.
 */
export function parseServerDateMs(value: string | null | undefined): number | null {
    const d = parseServerDate(value);
    return d ? d.getTime() : null;
}

/**
 * Format a server timestamp for DISPLAY as a date+time, deterministically parsed across engines.
 *
 * Parsing is via `parseServerDate` (engine-safe, UTC-normalized). Formatting keeps the browser's
 * default locale (pass `options` to override), so users see their familiar format — the fix here is
 * that the value is never "Invalid Date" and never shifted by the client timezone.
 *
 * @param value Server timestamp string
 * @param options Intl.DateTimeFormat options (optional)
 * @param fallback Text returned for empty/unparseable input (default "—")
 */
export function formatServerDateTime(
    value: string | null | undefined,
    options?: Intl.DateTimeFormatOptions,
    fallback: string = '—'
): string {
    const d = parseServerDate(value);
    return d ? d.toLocaleString(undefined, options) : fallback;
}

/**
 * Format a server timestamp for DISPLAY as a date only. See `formatServerDateTime`.
 */
export function formatServerDate(
    value: string | null | undefined,
    options?: Intl.DateTimeFormatOptions,
    fallback: string = '—'
): string {
    const d = parseServerDate(value);
    return d ? d.toLocaleDateString(undefined, options) : fallback;
}
