import { useState, useEffect, useCallback } from 'react';

interface UseCase {
    id: number;
    name: string;
}

export default function ReqDownload() {
    const [useCases, setUseCases] = useState<UseCase[]>([]);
    const [selectedUseCaseIds, setSelectedUseCaseIds] = useState<number[]>([]);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [loadingUseCases, setLoadingUseCases] = useState(true);

    useEffect(() => {
        const fetchUseCases = async () => {
            try {
                const response = await fetch('/api/reqdl/usecases');
                if (response.ok) {
                    const data = await response.json();
                    setUseCases(data);
                }
            } catch {
                // Use cases are optional, proceed without them
            } finally {
                setLoadingUseCases(false);
            }
        };
        fetchUseCases();
    }, []);

    const toggleUseCase = useCallback((id: number) => {
        setSelectedUseCaseIds(prev =>
            prev.includes(id) ? prev.filter(x => x !== id) : [...prev, id]
        );
    }, []);

    const handleDownload = useCallback(async () => {
        setLoading(true);
        setError(null);

        try {
            let url = '/api/reqdl/export/docx';
            if (selectedUseCaseIds.length > 0) {
                url += `?usecaseIds=${selectedUseCaseIds.join(',')}`;
            }

            const response = await fetch(url);

            if (!response.ok) {
                const errorData = await response.json().catch(() => null);
                setError(errorData?.error || `Download failed (${response.status})`);
                return;
            }

            const contentType = response.headers.get('Content-Type') || '';
            if (contentType.includes('application/json')) {
                const data = await response.json();
                setError(data.message || 'No requirements found');
                return;
            }

            const blob = await response.blob();
            const contentDisposition = response.headers.get('Content-Disposition');
            let filename = 'requirements.docx';
            if (contentDisposition) {
                const match = contentDisposition.match(/filename="?([^"]+)"?/);
                if (match) filename = match[1];
            }

            const blobUrl = window.URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = blobUrl;
            a.download = filename;
            document.body.appendChild(a);
            a.click();
            a.remove();
            window.URL.revokeObjectURL(blobUrl);
        } catch {
            setError('Download failed. Please try again.');
        } finally {
            setLoading(false);
        }
    }, [selectedUseCaseIds]);

    return (
        <div className="container py-5" style={{ maxWidth: '700px' }}>
            <div className="text-center mb-4">
                <h1 className="h3">
                    <i className="bi bi-file-earmark-word me-2"></i>
                    Requirements Download
                </h1>
                <p className="text-muted">
                    Download the current security requirements as a Word document.
                </p>
            </div>

            {useCases.length > 0 && (
                <div className="card mb-4">
                    <div className="card-header">
                        <h5 className="card-title mb-0">
                            <i className="bi bi-tags me-2"></i>
                            Filter by Use Case
                        </h5>
                    </div>
                    <div className="card-body">
                        {loadingUseCases ? (
                            <div className="text-center py-2">
                                <div className="spinner-border spinner-border-sm" role="status">
                                    <span className="visually-hidden">Loading...</span>
                                </div>
                            </div>
                        ) : (
                            <div className="d-flex flex-wrap gap-2">
                                {useCases.map(uc => (
                                    <button
                                        key={uc.id}
                                        type="button"
                                        className={`btn btn-sm ${selectedUseCaseIds.includes(uc.id) ? 'btn-primary' : 'btn-outline-secondary'}`}
                                        onClick={() => toggleUseCase(uc.id)}
                                    >
                                        {uc.name}
                                    </button>
                                ))}
                            </div>
                        )}
                        {selectedUseCaseIds.length > 0 && (
                            <div className="mt-2">
                                <small className="text-muted">
                                    {selectedUseCaseIds.length} use case{selectedUseCaseIds.length !== 1 ? 's' : ''} selected
                                    {' '}&mdash;{' '}
                                    <a href="#" onClick={e => { e.preventDefault(); setSelectedUseCaseIds([]); }}>
                                        Clear all
                                    </a>
                                </small>
                            </div>
                        )}
                    </div>
                </div>
            )}

            {error && (
                <div className="alert alert-warning" role="alert">
                    {error}
                </div>
            )}

            <div className="d-grid">
                <button
                    className="btn btn-success btn-lg"
                    onClick={handleDownload}
                    disabled={loading}
                >
                    {loading ? (
                        <>
                            <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                            Generating...
                        </>
                    ) : (
                        <>
                            <i className="bi bi-download me-2"></i>
                            Download as Word (.docx)
                            {selectedUseCaseIds.length > 0 && (
                                <span className="badge bg-light text-dark ms-2">{selectedUseCaseIds.length} filter{selectedUseCaseIds.length !== 1 ? 's' : ''}</span>
                            )}
                        </>
                    )}
                </button>
            </div>

            <div className="text-center mt-3">
                <small className="text-muted">
                    {selectedUseCaseIds.length === 0
                        ? 'All requirements will be included.'
                        : 'Only requirements matching the selected use cases will be included.'}
                </small>
            </div>
        </div>
    );
}
