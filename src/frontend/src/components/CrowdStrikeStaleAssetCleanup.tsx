import React, { useEffect, useState } from 'react';
import axios from 'axios';

interface CleanupConfig {
    enabled: boolean;
    staleDays: number;
    maxDeletePercent: number;
    cron: string;
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

    useEffect(() => {
        const user = (window as any).currentUser;
        const admin = user?.roles?.includes('ADMIN') || false;
        setIsAdmin(admin);
        if (admin) {
            void load();
        } else {
            setLoading(false);
        }
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
            const ok = window.confirm(
                `Delete all CrowdStrike-tracked assets not re-imported in the last ${days} days?\n\n` +
                `This cascades to vulnerabilities, scan results, and workgroup links. ` +
                `It cannot be undone. Run a dry-run first if you have not.`
            );
            if (!ok) return;
        }
        try {
            setBusy(true);
            setError(null);
            setInfo(null);
            const res = await axios.post('/api/assets/delete-not-seen-by-crowdstrike', {
                days,
                dryRun
            });
            const r = res.data || {};
            const verb = dryRun ? 'Dry-run' : 'Cleanup';
            setInfo(
                `${verb} complete — candidates: ${r.candidateCount ?? 0}, ` +
                `deleted: ${r.deletedCount ?? 0}, errors: ${(r.errors?.length) ?? 0}` +
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
                                            <th className="text-end">Errors</th>
                                            <th className="text-end">Duration</th>
                                            <th>Note</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {runs.map(r => (
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
                                                <td className="text-end">{r.errorCount}</td>
                                                <td className="text-end">
                                                    {r.durationMs != null ? `${r.durationMs} ms` : '—'}
                                                </td>
                                                <td className="text-muted small" style={{ maxWidth: 320 }}>
                                                    {r.errorMessage ?? ''}
                                                </td>
                                            </tr>
                                        ))}
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
