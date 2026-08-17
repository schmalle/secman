/**
 * The release the user is currently viewing, remembered for the lifetime of the tab.
 *
 * Four components touch this value — `ReleaseSelector` writes it, `RequirementManagement`,
 * `RequirementDownload` and `Export` read it — and each used to hand-roll the same
 * `sessionStorage.getItem('secman_selectedReleaseId')` + `parseInt` block against a repeated magic
 * string. None of them validated the result, which is how a *deleted* release kept driving
 * requests: the id was trusted, the release-scoped fetch 404'd, and nothing ever forgot the id, so
 * the same two 404s repeated on every visit for the rest of the tab's life.
 *
 * Reading and clearing therefore belong together in one place, and in a `.ts` module rather than
 * inside a `.tsx` component — that is what makes the parsing rules unit-testable at all
 * (docs/TESTING.md §Frontend).
 *
 * This is not a security control. The id names a release, the backend authorises every
 * release-scoped request on its own, and a forged value can only produce a 404.
 */

export const SELECTED_RELEASE_KEY = 'secman_selectedReleaseId';

/**
 * Storage is absent during a server render and throws outright in some hardened/private browser
 * modes, so every access is guarded. Losing the remembered release is a cosmetic downgrade;
 * taking the page down over it is not.
 */
function withStorage<T>(fallback: T, action: (storage: Storage) => T): T {
    try {
        if (typeof sessionStorage === 'undefined' || sessionStorage === null) return fallback;
        return action(sessionStorage);
    } catch {
        return fallback;
    }
}

/**
 * The remembered release id, or null when there is nothing usable stored.
 *
 * Strict on purpose. `parseInt` alone answers `NaN` for `"abc"` and `44727` for `"44727abc"`, and
 * both go straight into a URL — one guaranteeing `/api/releases/NaN`, the other silently
 * addressing the wrong row. Only a plain positive integer is accepted.
 */
export function readSelectedReleaseId(): number | null {
    const stored = withStorage<string | null>(null, (s) => s.getItem(SELECTED_RELEASE_KEY));
    if (stored === null || !/^\d+$/.test(stored)) return null;
    const parsed = Number(stored);
    return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
}

/** Remembers a release id; `null` forgets the current selection. */
export function writeSelectedReleaseId(id: number | null): void {
    if (id === null) {
        clearSelectedReleaseId();
        return;
    }
    withStorage(undefined, (s) => s.setItem(SELECTED_RELEASE_KEY, String(id)));
}

/**
 * Forgets the remembered release.
 *
 * Call this whenever a release-scoped request comes back 404: the release was deleted from
 * another session, and keeping the id would repeat the failure on every later visit.
 */
export function clearSelectedReleaseId(): void {
    withStorage(undefined, (s) => s.removeItem(SELECTED_RELEASE_KEY));
}
