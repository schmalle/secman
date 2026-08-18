import { useCallback, useEffect, useState } from 'react';
import { buildPublicStandardUrl, buildRequirementDownloadUrl } from './requirementDownloadUrl';
import {
    defaultReleaseId,
    releaseOptionLabel,
    selectableReleases,
    type PublicRelease,
} from './publicStandardReleases';
import { downloadResponse } from '../utils/download';

interface Standard {
    id: number;
    name: string;
}

type Format = 'docx' | 'xlsx';

/**
 * Standalone, unauthenticated download page for a complete security standard.
 *
 * Every endpoint it touches is @Secured(IS_ANONYMOUS): /api/standards/public, /api/releases and
 * /api/requirements/export/{docx,xlsx}. It therefore uses plain fetch rather than the
 * authenticated* helpers in utils/auth.ts, which redirect to /login on a 401 — the wrong reflex
 * on a page written for visitors who have no account.
 *
 * Contract for the URLs it builds: docs/PUBLIC_STANDARD_DOWNLOAD.md.
 */
export default function PublicStandardDownload() {
    const [standards, setStandards] = useState<Standard[]>([]);
    const [releases, setReleases] = useState<PublicRelease[]>([]);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);

    const [selectedStandardId, setSelectedStandardId] = useState<number | null>(null);
    // null is a real selection here, not "unset": it means the live requirement set.
    const [selectedReleaseId, setSelectedReleaseId] = useState<number | null>(null);

    const [downloading, setDownloading] = useState<Format | null>(null);
    const [error, setError] = useState<string | null>(null);

    // window.location.origin is read after mount: this island is server rendered first, where
    // window does not exist. Until it lands, the direct-link block stays hidden rather than
    // showing a host-less relative URL in a box whose whole purpose is being copy-pasteable.
    const [origin, setOrigin] = useState('');
    useEffect(() => {
        setOrigin(window.location.origin);
    }, []);

    useEffect(() => {
        let cancelled = false;

        const load = async () => {
            try {
                // pageSize is raised past the server default of 20 because the endpoint paginates
                // an already fully materialised list — one request returns everything.
                const [standardsRes, releasesRes] = await Promise.all([
                    fetch('/api/standards/public'),
                    fetch('/api/releases?pageSize=200'),
                ]);
                if (cancelled) return;

                if (!standardsRes.ok) {
                    setLoadError('Could not load the list of standards. Please try again later.');
                    return;
                }

                const standardList: Standard[] = await standardsRes.json();

                // A missing release list is not fatal — the live requirement set stays available,
                // so degrade to that rather than blocking the download entirely.
                let releaseList: PublicRelease[] = [];
                if (releasesRes.ok) {
                    const body = await releasesRes.json();
                    releaseList = selectableReleases(Array.isArray(body?.data) ? body.data : []);
                }

                if (cancelled) return;
                setStandards(standardList);
                setReleases(releaseList);
                setSelectedReleaseId(defaultReleaseId(releaseList));
                if (standardList.length === 1) {
                    // One standard is the common case; pre-selecting it removes a pointless click.
                    setSelectedStandardId(standardList[0].id);
                }
            } catch {
                if (!cancelled) {
                    setLoadError('Could not reach the server. Please try again later.');
                }
            } finally {
                if (!cancelled) setLoading(false);
            }
        };

        load();
        return () => { cancelled = true; };
    }, []);

    const selectedStandard = standards.find(s => s.id === selectedStandardId) ?? null;
    const selectedRelease = releases.find(r => r.id === selectedReleaseId) ?? null;

    const handleDownload = useCallback(async (format: Format) => {
        if (selectedStandardId === null) return;

        setDownloading(format);
        setError(null);
        try {
            const response = await fetch(buildRequirementDownloadUrl({
                format,
                standardId: selectedStandardId,
                releaseId: selectedReleaseId,
            }));

            if (!response.ok) {
                const body = await response.json().catch(() => null);
                setError(body?.error || `Download failed (${response.status}).`);
                return;
            }

            // The backend answers 200 with JSON, not a document, when nothing matches.
            if ((response.headers.get('Content-Type') || '').includes('application/json')) {
                const body = await response.json();
                setError(body.message || 'No requirements found for this standard.');
                return;
            }

            await downloadResponse(response, `standard.${format}`);
        } catch {
            setError('Download failed. Please try again.');
        } finally {
            setDownloading(null);
        }
    }, [selectedStandardId, selectedReleaseId]);

    const directLink = selectedStandard === null
        ? ''
        : buildPublicStandardUrl(origin, selectedStandard.name, 'docx', selectedRelease?.version ?? null);

    const renderButton = (format: Format) => (
        <button
            type="button"
            className={`btn ${format === 'docx' ? 'btn-success' : 'btn-outline-success'} btn-lg`}
            onClick={() => handleDownload(format)}
            disabled={selectedStandardId === null || downloading !== null}
        >
            {downloading === format ? (
                <>
                    <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                    Generating...
                </>
            ) : (
                <>
                    <i className={`bi ${format === 'docx' ? 'bi-file-earmark-word' : 'bi-file-earmark-excel'} me-2`}></i>
                    Download as {format === 'docx' ? 'Word (.docx)' : 'Excel (.xlsx)'}
                </>
            )}
        </button>
    );

    return (
        <div className="container py-5" style={{ maxWidth: '760px' }}>
            <header className="mb-4">
                <div className="d-flex align-items-center gap-2 mb-3">
                    <i className="bi bi-shield-check fs-3 text-success"></i>
                    <span className="fs-4 fw-semibold">SECMAN</span>
                </div>
                <h1 className="h3 mb-2">Download a Security Standard</h1>
                <p className="text-muted mb-0">
                    Choose a standard and a version to download the complete document.
                    No account or login is required.
                </p>
            </header>

            <div className="card shadow-sm">
                <div className="card-body p-4">
                    {loading && (
                        <div className="text-center py-4 text-muted">
                            <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                            Loading standards...
                        </div>
                    )}

                    {!loading && loadError && (
                        <div className="alert alert-danger mb-0" role="alert">{loadError}</div>
                    )}

                    {!loading && !loadError && standards.length === 0 && (
                        <div className="alert alert-info mb-0" role="alert">
                            No standards are published for download yet.
                        </div>
                    )}

                    {!loading && !loadError && standards.length > 0 && (
                        <>
                            <div className="mb-3">
                                <label htmlFor="public-standard-select" className="form-label fw-semibold">
                                    Standard
                                </label>
                                <select
                                    id="public-standard-select"
                                    className="form-select form-select-lg"
                                    data-testid="public-standard-selector"
                                    value={selectedStandardId === null ? '' : String(selectedStandardId)}
                                    onChange={e => {
                                        setSelectedStandardId(e.target.value === '' ? null : parseInt(e.target.value, 10));
                                        setError(null);
                                    }}
                                >
                                    <option value="">Select a standard...</option>
                                    {standards.map(std => (
                                        <option key={std.id} value={std.id}>{std.name}</option>
                                    ))}
                                </select>
                            </div>

                            <div className="mb-4">
                                <label htmlFor="public-release-select" className="form-label fw-semibold">
                                    Version
                                </label>
                                <select
                                    id="public-release-select"
                                    className="form-select"
                                    data-testid="public-release-selector"
                                    value={selectedReleaseId === null ? '' : String(selectedReleaseId)}
                                    onChange={e => {
                                        setSelectedReleaseId(e.target.value === '' ? null : parseInt(e.target.value, 10));
                                        setError(null);
                                    }}
                                >
                                    <option value="">Current requirement set (live)</option>
                                    {releases.map(rel => (
                                        <option key={rel.id} value={rel.id}>{releaseOptionLabel(rel)}</option>
                                    ))}
                                </select>
                                <small className="text-muted d-block mt-1">
                                    {selectedRelease === null
                                        ? 'The requirements as they stand today. This changes as requirements are edited.'
                                        : 'A frozen snapshot — these bytes do not change as requirements are edited later.'}
                                </small>
                            </div>

                            {error && (
                                <div className="alert alert-warning alert-dismissible fade show" role="alert">
                                    {error}
                                    <button
                                        type="button"
                                        className="btn-close"
                                        onClick={() => setError(null)}
                                        aria-label="Close"
                                    ></button>
                                </div>
                            )}

                            <div className="d-flex flex-wrap gap-2">
                                {renderButton('docx')}
                                {renderButton('xlsx')}
                            </div>

                            {selectedStandardId === null && (
                                <small className="text-muted d-block mt-3">
                                    Select a standard to enable the downloads.
                                </small>
                            )}

                            {selectedStandard !== null && origin !== '' && (
                                <div className="mt-4 pt-3 border-top">
                                    <small className="text-muted d-block mb-2">
                                        <i className="bi bi-link-45deg me-1"></i>
                                        Direct link — share it, bookmark it, or fetch it from a script:
                                    </small>
                                    <code className="d-block small text-break bg-light p-2 rounded">
                                        {directLink}
                                    </code>
                                </div>
                            )}
                        </>
                    )}
                </div>
            </div>
        </div>
    );
}
