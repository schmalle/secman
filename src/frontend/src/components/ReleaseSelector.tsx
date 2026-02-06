import React, { useState, useEffect } from 'react';
import { authenticatedFetch } from '../utils/auth';

interface Release {
    id: number;
    version: string;
    name: string;
    status: string;
    requirementCount: number;
    createdAt: string;
}

interface ReleaseSelectorProps {
    onReleaseChange: (releaseId: number | null) => void;
    selectedReleaseId?: number | null;
    className?: string;
}

const SESSION_STORAGE_KEY = 'secman_selectedReleaseId';

const ReleaseSelector: React.FC<ReleaseSelectorProps> = ({
    onReleaseChange,
    selectedReleaseId = null,
    className = ''
}) => {
    const [releases, setReleases] = useState<Release[]>([]);
    const [isLoading, setIsLoading] = useState<boolean>(false);
    const [error, setError] = useState<string>('');
    const [selectedId, setSelectedId] = useState<number | null>(selectedReleaseId);
    const [initialized, setInitialized] = useState(false);

    useEffect(() => {
        if (typeof window !== 'undefined') {
            fetchReleases();
        }
    }, []);

    // After releases load, default to ACTIVE release or restore from sessionStorage
    useEffect(() => {
        if (releases.length === 0 || initialized) return;

        // Try restoring from sessionStorage first
        const stored = sessionStorage.getItem(SESSION_STORAGE_KEY);
        if (stored) {
            const storedId = parseInt(stored, 10);
            if (!isNaN(storedId) && releases.some(r => r.id === storedId)) {
                setSelectedId(storedId);
                onReleaseChange(storedId);
                setInitialized(true);
                return;
            }
        }

        // Default to ACTIVE release
        const activeRelease = releases.find(r => r.status === 'ACTIVE');
        if (activeRelease) {
            setSelectedId(activeRelease.id);
            sessionStorage.setItem(SESSION_STORAGE_KEY, activeRelease.id.toString());
            onReleaseChange(activeRelease.id);
        }
        setInitialized(true);
    }, [releases]);

    const fetchReleases = async () => {
        setIsLoading(true);
        setError('');

        try {
            const response = await authenticatedFetch('/api/releases');

            if (!response.ok) {
                throw new Error('Failed to fetch releases');
            }

            const data = await response.json();

            // Handle paginated response format
            if (Array.isArray(data)) {
                // Direct array response
                setReleases(data);
            } else if (data && Array.isArray(data.data)) {
                // Paginated response with data field
                setReleases(data.data);
            } else if (data && Array.isArray(data.releases)) {
                // Alternative wrapped response
                setReleases(data.releases);
            } else {
                console.error('Invalid releases data format:', data);
                setReleases([]);
                setError('Invalid data format from server');
            }
        } catch (err) {
            console.error('Error fetching releases:', err);
            setError('Failed to load releases');
            setReleases([]);
        } finally {
            setIsLoading(false);
        }
    };

    const handleChange = (event: React.ChangeEvent<HTMLSelectElement>) => {
        const value = event.target.value;
        const newReleaseId = value === '' ? null : parseInt(value, 10);

        setSelectedId(newReleaseId);
        if (newReleaseId !== null) {
            sessionStorage.setItem(SESSION_STORAGE_KEY, newReleaseId.toString());
        } else {
            sessionStorage.removeItem(SESSION_STORAGE_KEY);
        }
        onReleaseChange(newReleaseId);
    };

    return (
        <div className={`release-selector ${className}`}>
            {!className.includes('compact') && (
                <label htmlFor="release-select" className="form-label">
                    Select Release Version:
                </label>
            )}

            {error && (
                <div className="alert alert-warning alert-dismissible fade show" role="alert">
                    {error}
                    <button
                        type="button"
                        className="btn-close"
                        onClick={() => setError('')}
                        aria-label="Close"
                    ></button>
                </div>
            )}

            <select
                id="release-select"
                className="form-select"
                value={selectedId === null ? '' : selectedId.toString()}
                onChange={handleChange}
                disabled={isLoading}
                data-testid="release-selector"
            >
                <option value="">Current Version (Live)</option>

                {isLoading ? (
                    <option disabled>Loading releases...</option>
                ) : (
                    Array.isArray(releases) && releases.map((release) => (
                        <option key={release.id} value={release.id}>
                            {release.version} - {release.name} ({release.status})
                        </option>
                    ))
                )}
            </select>

            {selectedId !== null && (
                <small className="form-text text-muted mt-1">
                    You are viewing a historical snapshot. Changes to requirements will not affect this release.
                </small>
            )}
            {selectedId === null && initialized && !isLoading && (
                <small className="form-text text-warning mt-1">
                    <i className="bi bi-info-circle me-1"></i>
                    Showing live requirements (no active release selected)
                </small>
            )}
        </div>
    );
};

export default React.memo(ReleaseSelector);
