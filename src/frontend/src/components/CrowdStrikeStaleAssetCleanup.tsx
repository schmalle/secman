import React, { useEffect, useState } from 'react';
import axios from 'axios';

interface CleanupConfig {
    enabled: boolean;
    staleDays: number;
    maxDeletePercent: number;
    cron: string;
    // Feature 087.
    includeLegacy: boolean;
}

interface CleanupRun {
    id: number;
    status: 'SUCCESS' | 'PARTIAL' | 'ABORTED_SAFETY_BRAKE' | 'FAILED';
    triggeredBy: string;
    staleDays: number;
    cutoff: string;
    candidateCount: number;
    deletedCount: number;
    errorCount: number;
    totalCrowdStrikeTracked: number;
    startedAt: string;
    completedAt: string | null;
    durationMs: number | null;
    errorMessage: string | null;
    // Feature 087: legacy-rule contribution. Default 0 on rows persisted before V210.
    legacyCandidateCount: number;
    legacyDeletedCount: number;
}

const STATUS_BADGE: Record<CleanupRun['status'], string> = {
    SUCCESS: 'bg-success',
    PARTIAL: 'bg-warning text-dark',
    ABORTED_SAFETY_BRAKE: 'bg-danger',
    FAILED: 'bg-danger'
};

const CrowdStrikeStaleAssetCleanup: React.FC = () => {
    const [isAdmin, setIsAdmin] = useState(false);
    const [loading, setLoading] = useState(true);
    const [config, setConfig] = useState<CleanupConfig | null>(null);
    const [runs, setRuns] = useState<CleanupRun[]>([]);
    const [error, setError] = useState<string | null>(null);
    const [info, setInfo] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    const [days, setDays] = useState<number>(30);
    // Feature 087: legacy toggle. Initial state must come from the backend's
    // configured default (spec SC-006) — never hardcoded false.
    const [includeLegacy, setIncludeLegacy] = useState<boolean>(false);

    useEffect(() => {
        let timeoutId: ReturnType<typeof setTimeout> | null = null;
        let eventHandled = false;

        const resolve = () => {
            const user = (window as any).currentUser;
            const admin = user?.roles?.includes('ADMIN') || false;
            setIsAdmin(admin);
            if (admin) {
                void load();
            } else {
                setLoading(false);
            }
        };

        // window.currentUser is populated asynchronously by auth-init.ts.
        // If the island hydrates first, wait for the userLoaded event (with timeout).
        if ((window as any).currentUser !== undefined) {
            resolve();
        } else {
            const onLoaded = () => {
                eventHandled = true;
                if (timeoutId) clearTimeout(timeoutId);
                resolve();
            };
            window.addEventListener('userLoaded', onLoaded, { once: true });
            timeoutId = setTimeout(() => {
                if (!eventHandled) {
                    window.removeEventListener('userLoaded', onLoaded);
                    setIsAdmin(false);
                    setLoading(false);
                }
            }, 5000);
        }

        return () => {
            if (timeoutId) clearTimeout(timeoutId);
        };
    }, []);

    const load = async () => {
        try {
            setLoading(true);
            setError(null);
            const [cfgRes, runsRes] = await Promise.all([
                axios.get<CleanupConfig>('/api/crowdstrike/cleanup/config'),
                axios.get<CleanupRun[]>('/api/crowdstrike/cleanup/runs?limit=20')
            ]);
            setConfig(cfgRes.data);
            setDays(cfgRes.data.staleDays);
            setIncludeLegacy(Boolean(cfgRes.data.includeLegacy));
            setRuns(runsRes.data);
        } catch (e: any) {
            setError(e.response?.data?.error || 'Failed to load cleanup data');
        } finally {
            setLoading(false);
        }
    };

    const trigger = async (dryRun: boolean) => {
        if (!isAdmin) return;
        if (!dryRun) {
            const legacyNote = includeLegacy
                ? '\n\nLegacy CrowdStrike rows (no import timestamp, owner = "CrowdStrike Import", no manual creator/scan uploader) are also included.'
                : '';
            const ok = window.confirm(
                `Delete all CrowdStrike-tracked assets not re-imported in the last ${days} days?\n\n` +
                `This cascades to vulnerabilities, scan results, and workgroup links. ` +
                `It cannot be undone. Run a dry-run first if you have not.` +
                legacyNote
            );
            if (!ok) return;
        }
        try {
            setBusy(true);
            setError(null);
            setInfo(null);
            const res = await axios.post('/api/assets/delete-not-seen-by-crowdstrike', {
                days,
                dryRun,
                includeLegacy
            });
            const r = res.data || {};
            const verb = dryRun ? 'Dry-run' : 'Cleanup';
            const total = r.candidateCount ?? 0;
            const legacyN = r.legacyCandidateCount ?? 0;
            const timestampN = Math.max(0, total - legacyN);
            const deletedN = r.deletedCount ?? 0;
            const splitText = includeLegacy
                ? `candidates: ${total} (timestamp: ${timestampN}, legacy: ${legacyN}, deleted: ${deletedN})`
                : `candidates: ${total}, deleted: ${deletedN}`;
            const errCount = (r.errors?.length) ?? 0;
            setInfo(
                `${verb} complete — ${splitText}` +
                (errCount > 0 ? `, errors: ${errCount}` : '') +
                (r.status ? ` (status: ${r.status})` : '')
            );
            await load();
        } catch (e: any) {
            setError(e.response?.data?.error || 'Cleanup request failed');
        } finally {
            setBusy(false);
        }
    };

    if (!isAdmin && !loading) {
        return null;
    }

    return (
        <div className="card mt-4">
            <div className="card-header d-flex justify-content-between align-items-center">
                <h5 className="mb-0">
                    <i className="bi bi-trash3 me-2"></i>
                    Stale Asset Cleanup
                </h5>
                <button
                    type="button"
                    className="btn btn-sm btn-outline-secondary"
                    onClick={load}
                    disabled={loading || busy}
                    title="Refresh"
                >
                    <i className="bi bi-arrow-clockwise"></i>
                </button>
            </div>
            <div className="card-body">
                {error && <div className="alert alert-danger">{error}</div>}
                {info && <div className="alert alert-info">{info}</div>}

                {loading ? (
                    <div>Loading…</div>
                ) : (
                    <>
                        <div className="row g-3 mb-3">
                            <div className="col-md-3">
                                <div className="border rounded p-2">
                                    <small className="text-muted">Scheduler</small>
                                    <div>
                                        {config?.enabled ? (
                                            <span className="badge bg-success">Enabled</span>
                                        ) : (
                                            <span className="badge bg-secondary">Disabled</span>
                                        )}
                                    </div>
                                </div>
                            </div>
                            <div className="col-md-3">
                                <div className="border rounded p-2">
                                    <small className="text-muted">Stale threshold</small>
                                    <div>{config?.staleDays} days</div>
                                </div>
                            </div>
                            <div className="col-md-3">
                                <div className="border rounded p-2">
                                    <small className="text-muted">Safety brake</small>
                                    <div>
                                        {config?.maxDeletePercent}%
                                        {config && config.maxDeletePercent >= 100 && (
                                            <span className="text-muted ms-2">(off)</span>
                                        )}
                                    </div>
                                </div>
                            </div>
                            <div className="col-md-3">
                                <div className="border rounded p-2">
                                    <small className="text-muted">Cron (server TZ)</small>
                                    <div><code>{config?.cron}</code></div>
                                </div>
                            </div>
                        </div>

                        <div className="row g-2 align-items-end mb-3">
                            <div className="col-auto">
                                <label className="form-label">Stale days for manual run</label>
                                <input
                                    type="number"
                                    min={1}
                                    max={3650}
                                    className="form-control"
                                    value={days}
                                    onChange={e => setDays(Number(e.target.value) || 0)}
                                    disabled={busy}
                                />
                            </div>
                            <div className="col-auto">
                                {/* Feature 087: legacy toggle. Default comes from
                                    config.includeLegacy on load (spec SC-006). */}
                                <div className="form-check mt-4">
                                    <input
                                        id="csIncludeLegacyToggle"
                                        type="checkbox"
                                        className="form-check-input"
                                        checked={includeLegacy}
                                        onChange={e => setIncludeLegacy(e.target.checked)}
                                        disabled={busy}
                                    />
                                    <label className="form-check-label" htmlFor="csIncludeLegacyToggle">
                                        Include legacy CrowdStrike rows
                                    </label>
                                </div>
                            </div>
                            <div className="col-auto">
                                <button
                                    type="button"
                                    className="btn btn-outline-primary"
                                    onClick={() => trigger(true)}
                                    disabled={busy || days <= 0}
                                >
                                    <i className="bi bi-eye me-1"></i>
                                    Dry-run
                                </button>
                            </div>
                            <div className="col-auto">
                                <button
                                    type="button"
                                    className="btn btn-danger"
                                    onClick={() => trigger(false)}
                                    disabled={busy || days <= 0}
                                >
                                    <i className="bi bi-trash me-1"></i>
                                    Run cleanup now
                                </button>
                            </div>
                            <div className="col text-muted small">
                                Manual runs do not apply the safety brake. Always dry-run first.
                            </div>
                        </div>

                        <h6>Recent runs</h6>
                        {runs.length === 0 ? (
                            <p className="text-muted mb-0">No cleanup runs recorded yet.</p>
                        ) : (
                            <div className="table-responsive">
                                <table className="table table-sm align-middle">
                                    <thead>
                                        <tr>
                                            <th>Started</th>
                                            <th>Status</th>
                                            <th>Trigger</th>
                                            <th className="text-end">Stale days</th>
                                            <th className="text-end">Candidates</th>
                                            <th className="text-end">Deleted</th>
                                            <th className="text-end">Legacy (cand/del)</th>
                                            <th className="text-end">Errors</th>
                                            <th className="text-end">Duration</th>
                                            <th>Note</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {runs.map(r => {
                                            // Feature 087: render legacy contribution. Rows persisted
                                            // before V210 read 0/0 — display them dim so 0/0 doesn't
                                            // imply "definitely no legacy candidates existed".
                                            const lc = r.legacyCandidateCount ?? 0;
                                            const ld = r.legacyDeletedCount ?? 0;
                                            const legacyClass = (lc === 0 && ld === 0) ? 'text-muted' : '';
                                            return (
                                                <tr key={r.id}>
                                                    <td><code>{r.startedAt}</code></td>
                                                    <td>
                                                        <span className={`badge ${STATUS_BADGE[r.status]}`}>
                                                            {r.status}
                                                        </span>
                                                    </td>
                                                    <td>{r.triggeredBy}</td>
                                                    <td className="text-end">{r.staleDays}</td>
                                                    <td className="text-end">{r.candidateCount}</td>
                                                    <td className="text-end">{r.deletedCount}</td>
                                                    <td className={`text-end ${legacyClass}`}>{lc}/{ld}</td>
                                                    <td className="text-end">{r.errorCount}</td>
                                                    <td className="text-end">
                                                        {r.durationMs != null ? `${r.durationMs} ms` : '—'}
                                                    </td>
                                                    <td className="text-muted small" style={{ maxWidth: 320 }}>
                                                        {r.errorMessage ?? ''}
                                                    </td>
                                                </tr>
                                            );
                                        })}
                                    </tbody>
                                </table>
                            </div>
                        )}
                    </>
                )}
            </div>
        </div>
    );
};

export default CrowdStrikeStaleAssetCleanup;
