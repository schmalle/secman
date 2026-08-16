/**
 * Browser download helpers.
 *
 * Every export in the app ends the same way: take a Blob, invent an anchor, click it,
 * revoke the object URL. That eight-line dance was copy-pasted into two dozen call
 * sites, and the copies had drifted — some forgot `revokeObjectURL` (leaking the blob
 * for the life of the document), some removed the anchor before the click had been
 * dispatched, and the Content-Disposition parsing existed in four mutually incompatible
 * regex spellings:
 *
 *   /filename="([^"]+)"/     - misses unquoted `filename=foo.csv`
 *   /filename="(.+)"/        - greedy, swallows everything up to the LAST quote
 *   /filename="?([^"]+)"?/   - matches unquoted, but stops at the first `;` only by luck
 *   /filename="?([^"]+)"?/i  - the same, case-insensitively
 *
 * Route every download through here so there is one behaviour to reason about and one
 * place to fix. Nothing in this module talks to the network — callers own the fetch.
 */

/** Suggested filename when a server gives us nothing usable. */
const UNNAMED = 'download';

/**
 * Trigger a browser download of `blob` under `filename`.
 *
 * The anchor stays in the document across the synchronous `click()` and is only removed
 * afterwards, because Firefox ignores a click on a detached node. The object URL is
 * always revoked — leaking it pins the blob's bytes in memory until the page unloads.
 */
export function downloadBlob(blob: Blob, filename: string): void {
    const url = URL.createObjectURL(blob);
    try {
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = filename || UNNAMED;
        anchor.style.display = 'none';
        document.body.appendChild(anchor);
        anchor.click();
        anchor.remove();
    } finally {
        URL.revokeObjectURL(url);
    }
}

/**
 * Extract the filename from a `Content-Disposition` header value.
 *
 * Handles the three spellings servers actually send, in RFC 6266 precedence order:
 *   - `filename*=UTF-8''report%20Q1.xlsx` (RFC 5987, percent-decoded, wins when present)
 *   - `filename="report Q1.xlsx"` (quoted)
 *   - `filename=report.xlsx` (bare token, terminated by `;` or whitespace)
 *
 * The result is reduced to its basename: a header is server-controlled input, and while
 * browsers already refuse to honour a path in `a.download`, stripping it here means the
 * value is safe for any other use a caller puts it to.
 *
 * @param header Raw header value, or null/undefined when the header was absent
 * @param fallback Returned when the header is missing or carries no usable filename
 */
export function filenameFromContentDisposition(
    header: string | null | undefined,
    fallback: string,
): string {
    if (!header) return fallback;

    // RFC 5987 extended form takes precedence over the plain form when both are sent.
    const extended = header.match(/filename\*\s*=\s*[^']*'[^']*'([^;]+)/i);
    if (extended) {
        const decoded = safeDecode(extended[1].trim());
        const name = basename(decoded);
        if (name) return name;
    }

    const plain = header.match(/filename\s*=\s*(?:"([^"]*)"|([^;\s]+))/i);
    if (plain) {
        const name = basename((plain[1] ?? plain[2] ?? '').trim());
        if (name) return name;
    }

    return fallback;
}

/**
 * Download the body of a `fetch` Response, naming the file from its `Content-Disposition`
 * header and falling back to `fallbackFilename`.
 *
 * Callers are responsible for checking `response.ok` first — a 4xx/5xx body would
 * otherwise be saved to disk as if it were the export.
 */
export async function downloadResponse(response: Response, fallbackFilename: string): Promise<void> {
    const blob = await response.blob();
    const filename = filenameFromContentDisposition(
        response.headers.get('Content-Disposition'),
        fallbackFilename,
    );
    downloadBlob(blob, filename);
}

/** Strip any directory component a server may have put in the header. */
function basename(value: string): string {
    const trimmed = value.replace(/\\/g, '/').split('/').pop()?.trim() ?? '';
    return trimmed === '.' || trimmed === '..' ? '' : trimmed;
}

/** Percent-decode, tolerating the malformed sequences `decodeURIComponent` throws on. */
function safeDecode(value: string): string {
    try {
        return decodeURIComponent(value);
    } catch {
        return value;
    }
}
