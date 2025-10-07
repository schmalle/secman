/**
 * ReleaseCreateModal Component
 *
 * Modal form for creating new releases with validation
 *
 * Features:
 * - Semantic versioning validation (MAJOR.MINOR.PATCH)
 * - Duplicate version detection
 * - Required field validation
 * - Success/error handling
 * - Loading states
 *
 * Related to: Feature 012-build-ui-for, User Story 2 (Create New Release)
 */

import React, { useState, useEffect } from 'react';
import { releaseService, type CreateReleaseRequest } from '../services/releaseService';

interface ReleaseCreateModalProps {
    isOpen: boolean;
    onClose: () => void;
    onSuccess: () => void;
}

/**
 * Validate semantic versioning format (MAJOR.MINOR.PATCH)
 * @param version Version string to validate
 * @returns true if valid, false otherwise
 */
export function validateSemanticVersion(version: string): boolean {
    const semanticVersionRegex = /^\d+\.\d+\.\d+$/;
    return semanticVersionRegex.test(version);
}

const ReleaseCreateModal: React.FC<ReleaseCreateModalProps> = ({ isOpen, onClose, onSuccess }) => {
    // Form state
    const [formData, setFormData] = useState<CreateReleaseRequest>({
        version: '',
        name: '',
        description: '',
    });

    // UI state
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [validationErrors, setValidationErrors] = useState<{
        version?: string;
        name?: string;
    }>({});

    // Reset form when modal opens/closes
    useEffect(() => {
        if (isOpen) {
            setFormData({ version: '', name: '', description: '' });
            setError(null);
            setValidationErrors({});
        }
    }, [isOpen]);

    // Handle input changes
    function handleChange(e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) {
        const { name, value } = e.target;
        setFormData((prev) => ({ ...prev, [name]: value }));
        
        // Clear validation error for this field
        setValidationErrors((prev) => ({ ...prev, [name]: undefined }));
        setError(null);
    }

    // Validate form
    function validateForm(): boolean {
        const errors: { version?: string; name?: string } = {};

        // Validate version (required and semantic versioning)
        if (!formData.version.trim()) {
            errors.version = 'Version is required';
        } else if (!validateSemanticVersion(formData.version)) {
            errors.version = 'Version must follow semantic versioning format (MAJOR.MINOR.PATCH, e.g., 1.0.0)';
        }

        // Validate name (required)
        if (!formData.name.trim()) {
            errors.name = 'Name is required';
        }

        setValidationErrors(errors);
        return Object.keys(errors).length === 0;
    }

    // Handle form submission
    async function handleSubmit(e: React.FormEvent) {
        e.preventDefault();

        // Validate form
        if (!validateForm()) {
            return;
        }

        setLoading(true);
        setError(null);

        try {
            // Create release
            await releaseService.create(formData);

            // Success - close modal and notify parent
            onSuccess();
            onClose();
            
            // Show success message (will be handled by parent component)
        } catch (err) {
            console.error('Failed to create release:', err);
            const errorMessage = err instanceof Error ? err.message : 'Failed to create release. Please try again.';
            
            // Check for specific error types
            if (errorMessage.toLowerCase().includes('already exists') || 
                errorMessage.toLowerCase().includes('duplicate')) {
                setError(`Version ${formData.version} already exists. Please use a different version number.`);
            } else if (errorMessage.toLowerCase().includes('no requirements')) {
                setError('Warning: No requirements exist in the system. This release will be empty.');
            } else {
                setError(errorMessage);
            }
        } finally {
            setLoading(false);
        }
    }

    // Handle cancel
    function handleCancel() {
        onClose();
    }

    // Don't render if not open
    if (!isOpen) {
        return null;
    }

    return (
        <>
            {/* Backdrop */}
            <div
                className="modal-backdrop fade show"
                onClick={handleCancel}
                style={{ zIndex: 1040 }}
            ></div>

            {/* Modal */}
            <div
                className="modal fade show d-block"
                tabIndex={-1}
                role="dialog"
                style={{ zIndex: 1050 }}
                aria-labelledby="createReleaseModalLabel"
                aria-modal="true"
            >
                <div className="modal-dialog modal-dialog-centered">
                    <div className="modal-content">
                        {/* Header */}
                        <div className="modal-header">
                            <h5 className="modal-title" id="createReleaseModalLabel" data-testid="modal-title">
                                Create New Release
                            </h5>
                            <button
                                type="button"
                                className="btn-close"
                                aria-label="Close"
                                onClick={handleCancel}
                                disabled={loading}
                            ></button>
                        </div>

                        {/* Body */}
                        <form onSubmit={handleSubmit}>
                            <div className="modal-body">
                                {/* Error Alert */}
                                {error && (
                                    <div className="alert alert-danger" role="alert">
                                        {error}
                                    </div>
                                )}

                                {/* Version Field */}
                                <div className="mb-3">
                                    <label htmlFor="version" className="form-label">
                                        Version <span className="text-danger">*</span>
                                    </label>
                                    <input
                                        type="text"
                                        className={`form-control ${validationErrors.version ? 'is-invalid' : ''}`}
                                        id="version"
                                        name="version"
                                        placeholder="1.0.0"
                                        value={formData.version}
                                        onChange={handleChange}
                                        disabled={loading}
                                        autoFocus
                                    />
                                    {validationErrors.version && (
                                        <div className="invalid-feedback d-block">
                                            {validationErrors.version}
                                        </div>
                                    )}
                                    <div className="form-text">
                                        Use semantic versioning format: MAJOR.MINOR.PATCH (e.g., 1.0.0, 2.1.3)
                                    </div>
                                </div>

                                {/* Name Field */}
                                <div className="mb-3">
                                    <label htmlFor="name" className="form-label">
                                        Name <span className="text-danger">*</span>
                                    </label>
                                    <input
                                        type="text"
                                        className={`form-control ${validationErrors.name ? 'is-invalid' : ''}`}
                                        id="name"
                                        name="name"
                                        placeholder="Q4 2024 Compliance Review"
                                        value={formData.name}
                                        onChange={handleChange}
                                        disabled={loading}
                                    />
                                    {validationErrors.name && (
                                        <div className="invalid-feedback d-block">
                                            {validationErrors.name}
                                        </div>
                                    )}
                                </div>

                                {/* Description Field */}
                                <div className="mb-3">
                                    <label htmlFor="description" className="form-label">
                                        Description
                                    </label>
                                    <textarea
                                        className="form-control"
                                        id="description"
                                        name="description"
                                        rows={3}
                                        placeholder="Describe the purpose of this release..."
                                        value={formData.description}
                                        onChange={handleChange}
                                        disabled={loading}
                                    ></textarea>
                                    <div className="form-text">
                                        Optional: Provide context about this release for future reference
                                    </div>
                                </div>

                                {/* Info Text */}
                                <div className="alert alert-info mb-0">
                                    <i className="bi bi-info-circle me-2"></i>
                                    <strong>Note:</strong> The release will be created with <strong>DRAFT</strong> status.
                                    All current requirements will be frozen as snapshots in this release.
                                </div>
                            </div>

                            {/* Footer */}
                            <div className="modal-footer">
                                <button
                                    type="button"
                                    className="btn btn-secondary"
                                    onClick={handleCancel}
                                    disabled={loading}
                                >
                                    Cancel
                                </button>
                                <button
                                    type="submit"
                                    className="btn btn-primary"
                                    disabled={loading}
                                >
                                    {loading ? (
                                        <>
                                            <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                                            Creating...
                                        </>
                                    ) : (
                                        <>
                                            <i className="bi bi-plus-circle me-2"></i>
                                            Create Release
                                        </>
                                    )}
                                </button>
                            </div>
                        </form>
                    </div>
                </div>
            </div>
        </>
    );
};

export default ReleaseCreateModal;
