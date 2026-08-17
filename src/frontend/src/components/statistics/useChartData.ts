/**
 * The fetch-into-state effect every card on the vulnerability statistics page runs.
 *
 * Each of the six cards had its own copy: set loading, clear error, await the API,
 * store the result, log-and-set a message on failure, clear loading in `finally`. The
 * copies had already drifted in whether `error` was reset before a refetch, which is
 * the difference between a transient failure clearing on the next domain change and
 * sticking on screen.
 */

import { useEffect, useState } from 'react';

export interface ChartDataState<T> {
    /** Result of the most recent successful fetch, or null before the first one lands. */
    data: T | null;
    loading: boolean;
    /** `errorMessage` when the most recent fetch threw, else null. */
    error: string | null;
}

/**
 * Run `fetcher` on mount and whenever `deps` change, tracking loading and error state.
 *
 * A failed fetch leaves the previous `data` in place and surfaces `errorMessage`;
 * callers render the error state instead, so stale data is never shown as current.
 *
 * @param fetcher Loads the card's data. Re-created each render — `deps` decides when it runs.
 * @param deps Values that should trigger a refetch, e.g. `[domain, awsHosted]`
 * @param errorMessage User-facing text for a failed fetch
 * @param logContext Prefix for the console.error, e.g. "severity distribution"
 */
export function useChartData<T>(
    fetcher: () => Promise<T>,
    deps: unknown[],
    errorMessage: string,
    logContext: string,
): ChartDataState<T> {
    const [data, setData] = useState<T | null>(null);
    const [loading, setLoading] = useState<boolean>(true);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        // Guards against a slow first request resolving after a faster second one and
        // overwriting it — the individual copies of this effect had no such guard.
        let current = true;

        (async () => {
            setLoading(true);
            setError(null);
            try {
                const result = await fetcher();
                if (current) setData(result);
            } catch (err) {
                console.error(`Error fetching ${logContext}:`, err);
                if (current) setError(errorMessage);
            } finally {
                if (current) setLoading(false);
            }
        })();

        return () => {
            current = false;
        };
        // `fetcher` is intentionally excluded: it is a fresh closure on every render,
        // and `deps` is the caller's statement of what actually changes the result.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, deps);

    return { data, loading, error };
}
