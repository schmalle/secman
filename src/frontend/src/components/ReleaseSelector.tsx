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

const ReleaseSelector: React.FC<ReleaseSelectorProps> = ({
    onReleaseChange,
    selectedReleaseId = null,
    className = ''
}) => {
    const [releases, setReleases] = useState<Release[]>([]);
    const [isLoading, setIsLoading] = useState<boolean>(false);
    const [error, setError] = useState<string>('');
    const [selectedId, setSelectedId] = useState<number | null>(selectedReleaseId);

    useEffect(() => {
        fetchReleases();
    }, []);

    const fetchReleases = async () => {
        setIsLoading(true);
        setError('');

        try {
            const response = await authenticatedFetch('/api/releases');

            if (!response.ok) {
                throw new Error('Failed to fetch releases');
            }

            const data = await response.json();
            setReleases(data);
        } catch (err) {
            console.error('Error fetching releases:', err);
            setError('Failed to load releases');
        } finally {
            setIsLoading(false);
        }
    };

    const handleChange = (event: React.ChangeEvent<HTMLSelectElement>) => {
        const value = event.target.value;
        const newReleaseId = value === '' ? null : parseInt(value, 10);

        setSelectedId(newReleaseId);
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
                    releases.map((release) => (
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
        </div>
    );
};

export default ReleaseSelector;
