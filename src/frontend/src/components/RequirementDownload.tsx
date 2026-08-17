import { useState, useEffect, useCallback } from 'react';
import { authenticatedFetch, authenticatedGet } from '../utils/auth';
import ReleaseIndicator from './ReleaseIndicator';
import ReleaseSelector from './ReleaseSelector';
import { buildPublicStandardUrl, buildRequirementDownloadUrl } from './requirementDownloadUrl';
import { downloadResponse } from '../utils/download';

interface UseCase {
    id: number;
    name: string;
}

interface Standard {
    id: number;
    name: string;
}

// Feature 067: Release interface for historical viewing
interface Release {
    id: number;
    version: string;
    name: string;
    status: string;
    requirementCount: number;
}

type DownloadTarget =
    | 'all-docx'
    | 'all-xlsx'
    | 'usecase-docx'
    | 'usecase-xlsx'
    | 'standard-docx'
    | 'standard-xlsx';

export default function RequirementDownload() {
    const [useCases, setUseCases] = useState<UseCase[]>([]);
    const [selectedUseCaseId, setSelectedUseCaseId] = useState<number | null>(null);
    const [standards, setStandards] = useState<Standard[]>([]);
    const [selectedStandardId, setSelectedStandardId] = useState<number | null>(null);
    const [downloading, setDownloading] = useState<DownloadTarget | null>(null);
    const [error, setError] = useState<string | null>(null);

    // Release viewing state — mirrors RequirementManagement (restore after mount
    // to avoid SSR hydration mismatch; ReleaseSelector defaults to ACTIVE release)
    const [selectedReleaseId, setSelectedReleaseId] = useState<number | null>(null);
    const [selectedRelease, setSelectedRelease] = useState<Release | null>(null);

    useEffect(() => {
        const stored = sessionStorage.getItem('secman_selectedReleaseId');
        if (stored) {
            const parsed = parseInt(stored, 10);
            if (!isNaN(parsed)) {
                setSelectedReleaseId(parsed);
            }
        }
    }, []);

    useEffect(() => {
        const fetchUseCases = async () => {
            try {
                const response = await authenticatedGet('/api/usecases');
                if (response.ok) {
                    setUseCases(await response.json());
                }
            } catch (err) {
                console.error('Error fetching use cases:', err);
            }
        };
        fetchUseCases();
    }, []);

    // Standards come from the slim public endpoint, so the picker also works logged out.
    useEffect(() => {
        const fetchStandards = async () => {
            try {
                const response = await authenticatedGet('/api/standards/public');
                if (response.ok) {
                    setStandards(await response.json());
                }
            } catch (err) {
                console.error('Error fetching standards:', err);
            }
        };
        fetchStandards();
    }, []);

    // Fetch release details for the indicator when the selection changes
    useEffect(() => {
        if (selectedReleaseId === null) {
            setSelectedRelease(null);
            return;
        }
        const fetchReleaseDetails = async () => {
            try {
                const response = await authenticatedGet(`/api/releases/${selectedReleaseId}`);
                if (response.ok) {
                    setSelectedRelease(await response.json());
                }
            } catch (err) {
                console.error('Error fetching release details:', err);
            }
        };
        fetchReleaseDetails();
    }, [selectedReleaseId]);

    const handleReleaseChange = (releaseId: number | null) => {
        setSelectedReleaseId(releaseId);
    };

    const handleClearRelease = () => {
        setSelectedReleaseId(null);
    };

    const handleDownload = useCallback(async (target: DownloadTarget) => {
        const format = target.endsWith('docx') ? 'docx' : 'xlsx';
        const useCaseId = target.startsWith('usecase') ? selectedUseCaseId : null;
        const standardId = target.startsWith('standard') ? selectedStandardId : null;
        if (target.startsWith('usecase') && useCaseId === null) {
            return;
        }
        if (target.startsWith('standard') && standardId === null) {
            return;
        }

        const downloadUrl = buildRequirementDownloadUrl({
            format,
            useCaseId,
            standardId,
            releaseId: selectedReleaseId,
        });

        setDownloading(target);
        setError(null);

        try {
            const response = await authenticatedFetch(downloadUrl);

            if (!response.ok) {
                const errorData = await response.json().catch(() => null);
                setError(errorData?.error || `Download failed (${response.status})`);
                return;
            }

            // Backend answers with JSON when no requirements match
            const contentType = response.headers.get('Content-Type') || '';
            if (contentType.includes('application/json')) {
                const data = await response.json();
                setError(data.message || 'No requirements found');
                return;
            }

            await downloadResponse(response, `requirements.${format}`);
        } catch (err) {
            console.error('Download failed:', err);
            setError('Download failed. Please try again.');
        } finally {
            setDownloading(null);
        }
    }, [selectedUseCaseId, selectedStandardId, selectedReleaseId]);

    // The equivalent name-based URL for the current selection, so the page doubles as the
    // documentation for scripting the download. Origin is read lazily: this island is server
    // rendered first, where `window` does not exist.
    const selectedStandard = standards.find(std => std.id === selectedStandardId) ?? null;
    const directStandardLink = selectedStandard === null
        ? ''
        : buildPublicStandardUrl(
            typeof window === 'undefined' ? '' : window.location.origin,
            selectedStandard.name,
            'docx',
            selectedRelease?.version ?? null,
        );

    const renderButton = (target: DownloadTarget, format: 'docx' | 'xlsx', disabled: boolean) => (
        <button
            className="btn btn-success"
            onClick={() => handleDownload(target)}
            disabled={disabled || downloading !== null}
        >
            {downloading === target ? (
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
        <div className="container-fluid p-4">
            {/* Release selector header — same as Requirement Management */}
            <div className="row mb-3">
                <div className="col-md-6">
                    <div className="d-flex align-items-center gap-3">
                        <label className="form-label mb-0 text-muted small">View Release:</label>
                        <div style={{ minWidth: '250px' }}>
                            <ReleaseSelector
                                onReleaseChange={handleReleaseChange}
                                selectedReleaseId={selectedReleaseId}
                                className="release-selector-compact"
                            />
                        </div>
                    </div>
                </div>
                <div className="col-md-6 d-flex justify-content-end">
                    <ReleaseIndicator
                        selectedRelease={selectedRelease}
                        onClearRelease={handleClearRelease}
                    />
                </div>
            </div>

            <div className="row">
                <div className="col-12">
                    <div className="d-flex justify-content-between align-items-center mb-4">
                        <h2>Requirement Download</h2>
                    </div>
                </div>
            </div>

            {error && (
                <div className="row mb-3">
                    <div className="col-12">
                        <div className="alert alert-warning alert-dismissible fade show" role="alert">
                            {error}
                            <button type="button" className="btn-close" onClick={() => setError(null)} aria-label="Close"></button>
                        </div>
                    </div>
                </div>
            )}

            <div className="row">
                <div className="col-xl-4 col-md-6 mb-4">
                    <div className="card h-100">
                        <div className="card-header">
                            <h5 className="card-title mb-0">
                                <i className="bi bi-card-checklist me-2"></i>
                                Complete Requirement Set
                            </h5>
                        </div>
                        <div className="card-body">
                            <p className="text-muted">
                                Download all requirements of the {selectedRelease !== null
                                    ? <>selected release <strong>v{selectedRelease.version}</strong></>
                                    : 'current requirement set'}.
                            </p>
                            <div className="d-flex gap-2">
                                {renderButton('all-docx', 'docx', false)}
                                {renderButton('all-xlsx', 'xlsx', false)}
                            </div>
                        </div>
                    </div>
                </div>

                <div className="col-xl-4 col-md-6 mb-4">
                    <div className="card h-100">
                        <div className="card-header">
                            <h5 className="card-title mb-0">
                                <i className="bi bi-diagram-3 me-2"></i>
                                Requirements per Use Case
                            </h5>
                        </div>
                        <div className="card-body">
                            <div className="mb-3">
                                <label htmlFor="usecase-select" className="form-label">Filter by Use Case:</label>
                                <select
                                    id="usecase-select"
                                    className="form-select"
                                    value={selectedUseCaseId === null ? '' : selectedUseCaseId.toString()}
                                    onChange={(e) => setSelectedUseCaseId(e.target.value === '' ? null : parseInt(e.target.value, 10))}
                                >
                                    <option value="">Select a use case...</option>
                                    {useCases.map(uc => (
                                        <option key={uc.id} value={uc.id}>{uc.name}</option>
                                    ))}
                                </select>
                            </div>
                            <div className="d-flex gap-2">
                                {renderButton('usecase-docx', 'docx', selectedUseCaseId === null)}
                                {renderButton('usecase-xlsx', 'xlsx', selectedUseCaseId === null)}
                            </div>
                            {selectedUseCaseId === null && (
                                <small className="text-muted d-block mt-2">
                                    Select a use case to enable the download.
                                </small>
                            )}
                        </div>
                    </div>
                </div>

                <div className="col-xl-4 col-md-6 mb-4">
                    <div className="card h-100">
                        <div className="card-header">
                            <h5 className="card-title mb-0">
                                <i className="bi bi-journal-text me-2"></i>
                                Complete Standard
                            </h5>
                        </div>
                        <div className="card-body">
                            <div className="mb-3">
                                <label htmlFor="standard-select" className="form-label">Select Standard:</label>
                                <select
                                    id="standard-select"
                                    className="form-select"
                                    data-testid="standard-selector"
                                    value={selectedStandardId === null ? '' : selectedStandardId.toString()}
                                    onChange={(e) => setSelectedStandardId(e.target.value === '' ? null : parseInt(e.target.value, 10))}
                                >
                                    <option value="">Select a standard...</option>
                                    {standards.map(std => (
                                        <option key={std.id} value={std.id}>{std.name}</option>
                                    ))}
                                </select>
                            </div>
                            <div className="d-flex gap-2">
                                {renderButton('standard-docx', 'docx', selectedStandardId === null)}
                                {renderButton('standard-xlsx', 'xlsx', selectedStandardId === null)}
                            </div>
                            {selectedStandardId === null ? (
                                <small className="text-muted d-block mt-2">
                                    Select a standard to enable the download.
                                </small>
                            ) : (
                                <div className="mt-3">
                                    <small className="text-muted d-block mb-1">
                                        Direct link (no login required):
                                    </small>
                                    <code className="d-block small text-break bg-light p-2 rounded">
                                        {directStandardLink}
                                    </code>
                                </div>
                            )}
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
}
