import { useState, useEffect } from 'react';
import ReactMarkdown from 'react-markdown'; // Import ReactMarkdown
import { authenticatedGet, authenticatedPost, authenticatedPut, authenticatedDelete } from '../utils/auth';
import ReleaseIndicator from './ReleaseIndicator';
import ReleaseSelector from './ReleaseSelector';

interface UseCase {
    id: number;
    name: string;
}

interface Norm {
    id: number;
    name: string;
    version?: string;
    year?: number;
}

interface Requirement {
    id?: number;
    internalId?: string;
    revision?: number;
    idRevision?: string;
    shortreq: string; // Renamed from title
    details: string;  // Renamed from description
    language?: string; // New optional field
    example?: string;  // New optional field
    motivation?: string; // New optional field
    createdAt?: string; // Assuming backend sends these as strings
    updatedAt?: string;
    usecases?: UseCase[]; // Added for associated use cases
    norms?: Norm[]; // Added for associated norms
}

// Feature 067: Release interface for historical viewing
interface Release {
    id: number;
    version: string;
    name: string;
    status: string;
    requirementCount: number;
}

export default function RequirementManagement() {
    const [requirements, setRequirements] = useState<Requirement[]>([]);
    const [filteredRequirements, setFilteredRequirements] = useState<Requirement[]>([]);
    const [allUseCases, setAllUseCases] = useState<UseCase[]>([]); // To store all available use cases
    const [allNorms, setAllNorms] = useState<Norm[]>([]); // To store all available norms
    const [selectedRequirement, setSelectedRequirement] = useState<Requirement | null>(null);
    const [isAddingRequirement, setIsAddingRequirement] = useState(false);
    const [filterUseCaseId, setFilterUseCaseId] = useState<number | null>(null);
    const [filterNormId, setFilterNormId] = useState<number | null>(null);
    const [formData, setFormData] = useState<Requirement>({
        shortreq: '',
        details: '',
        language: '',
        example: '',
        motivation: '',
        usecases: [],
        norms: []
    });
    const [selectedUseCaseIds, setSelectedUseCaseIds] = useState<Set<number>>(new Set());
    const [selectedNormIds, setSelectedNormIds] = useState<Set<number>>(new Set());
    const [useCaseSearchFilter, setUseCaseSearchFilter] = useState('');
    const [normSearchFilter, setNormSearchFilter] = useState('');
    
    // Delete all modal states
    const [showFirstDeleteModal, setShowFirstDeleteModal] = useState(false);
    const [showSecondDeleteModal, setShowSecondDeleteModal] = useState(false);
    const [confirmationText, setConfirmationText] = useState('');
    const [isDeleting, setIsDeleting] = useState(false);
    const [deleteSuccess, setDeleteSuccess] = useState<string | null>(null);
    const [deleteError, setDeleteError] = useState<string | null>(null);
    
    // Norm mapping states
    const [isMappingInProgress, setIsMappingInProgress] = useState(false);
    const [mappingSuggestions, setMappingSuggestions] = useState<any>(null);
    const [showMappingModal, setShowMappingModal] = useState(false);
    const [mappingSuccess, setMappingSuccess] = useState<string | null>(null);
    const [mappingError, setMappingError] = useState<string | null>(null);
    const [partialFailureWarning, setPartialFailureWarning] = useState<{
        batchesFailed: number;
        totalBatches: number;
        errors: Array<{ batchNumber: number; errorType: string; errorMessage: string }>;
    } | null>(null);

    // Feature 067: Release viewing state
    const [selectedReleaseId, setSelectedReleaseId] = useState<number | null>(null);
    const [selectedRelease, setSelectedRelease] = useState<Release | null>(null);
    const [isViewingHistorical, setIsViewingHistorical] = useState(false);

    // Fetch requirements, use cases, and norms from backend
    useEffect(() => {
        fetchRequirements();
        fetchAllUseCases();
        fetchAllNorms();
    }, []);

    // Feature 067: Fetch requirements when release selection changes
    useEffect(() => {
        fetchRequirements();
        if (selectedReleaseId !== null) {
            fetchReleaseDetails(selectedReleaseId);
        } else {
            setSelectedRelease(null);
            setIsViewingHistorical(false);
        }
    }, [selectedReleaseId]);

    // Filter requirements when filter changes
    useEffect(() => {
        let filtered = requirements;
        
        // Filter by use case if selected
        if (filterUseCaseId !== null) {
            filtered = filtered.filter(req => 
                req.usecases?.some(uc => uc.id === filterUseCaseId)
            );
        }
        
        // Filter by norm if selected
        if (filterNormId !== null) {
            filtered = filtered.filter(req => 
                req.norms?.some(norm => norm.id === filterNormId)
            );
        }
        
        setFilteredRequirements(filtered);
    }, [requirements, filterUseCaseId, filterNormId]);

    const fetchRequirements = async () => {
        try {
            if (selectedReleaseId !== null) {
                // Feature 067: Fetch historical requirements from release snapshot
                const response = await authenticatedGet(`/api/releases/${selectedReleaseId}/requirements`);
                const data = await response.json();
                // Transform snapshot data to requirement format
                const snapshots = Array.isArray(data) ? data : (data.data || []);
                const transformedRequirements: Requirement[] = snapshots.map((snapshot: any) => ({
                    id: snapshot.originalRequirementId,
                    internalId: snapshot.internalId,
                    revision: snapshot.revision,
                    idRevision: snapshot.idRevision || `${snapshot.internalId}.${snapshot.revision}`,
                    shortreq: snapshot.shortreq,
                    details: snapshot.details || '',
                    language: snapshot.language,
                    example: snapshot.example,
                    motivation: snapshot.motivation,
                    usecases: [],  // Snapshots don't store full objects
                    norms: []      // Snapshots don't store full objects
                }));
                setRequirements(transformedRequirements);
                setIsViewingHistorical(true);
            } else {
                // Fetch current requirements
                const response = await authenticatedGet('/api/requirements');
                const data = await response.json();
                setRequirements(data);
                setIsViewingHistorical(false);
            }
        } catch (error) {
            console.error('Error fetching requirements:', error);
        }
    };

    // Feature 067: Fetch release details for indicator
    const fetchReleaseDetails = async (releaseId: number) => {
        try {
            const response = await authenticatedGet(`/api/releases/${releaseId}`);
            const data = await response.json();
            setSelectedRelease(data);
        } catch (error) {
            console.error('Error fetching release details:', error);
        }
    };

    // Feature 067: Handle release selection change
    const handleReleaseChange = (releaseId: number | null) => {
        setSelectedReleaseId(releaseId);
    };

    // Feature 067: Clear release selection
    const handleClearRelease = () => {
        setSelectedReleaseId(null);
    };

    const fetchAllUseCases = async () => {
        try {
            const response = await authenticatedGet('/api/usecases'); // Assuming this endpoint exists
            const data = await response.json();
            setAllUseCases(data);
        } catch (error) {
            console.error('Error fetching all use cases:', error);
        }
    };

    const fetchAllNorms = async () => {
        try {
            const response = await authenticatedGet('/api/norms');
            const data = await response.json();
            setAllNorms(data);
        } catch (error) {
            console.error('Error fetching all norms:', error);
        }
    };

    const handleInputChange = (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => {
        setFormData({
            ...formData,
            [e.target.name]: e.target.value
        });
    };

    const handleUseCaseChange = (useCaseId: number) => {
        const newSelectedUseCaseIds = new Set(selectedUseCaseIds);
        if (newSelectedUseCaseIds.has(useCaseId)) {
            newSelectedUseCaseIds.delete(useCaseId);
        } else {
            newSelectedUseCaseIds.add(useCaseId);
        }
        setSelectedUseCaseIds(newSelectedUseCaseIds);
    };

    const handleNormChange = (normId: number) => {
        const newSelectedNormIds = new Set(selectedNormIds);
        if (newSelectedNormIds.has(normId)) {
            newSelectedNormIds.delete(normId);
        } else {
            newSelectedNormIds.add(normId);
        }
        setSelectedNormIds(newSelectedNormIds);
    };

    const handleFilterChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
        const value = e.target.value;
        setFilterUseCaseId(value === '' ? null : parseInt(value));
    };

    const handleNormFilterChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
        const value = e.target.value;
        setFilterNormId(value === '' ? null : parseInt(value));
    };

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        try {
            const requirementDataToSubmit = {
                ...formData,
                usecases: Array.from(selectedUseCaseIds).map(id => ({ id })), // Send only IDs of selected use cases
                norms: Array.from(selectedNormIds).map(id => ({ id })) // Send only IDs of selected norms
            };

            const url = selectedRequirement 
                ? `/api/requirements/${selectedRequirement.id}` 
                : '/api/requirements';
            
            const response = selectedRequirement 
                ? await authenticatedPut(url, requirementDataToSubmit)
                : await authenticatedPost(url, requirementDataToSubmit);

            if (response.ok) {
                fetchRequirements();
                resetForm();
            }
        } catch (error) {
            console.error('Error saving requirement:', error);
        }
    };

    const handleDelete = async (id: number) => {
        if (!confirm('Are you sure you want to delete this requirement?')) return;
        
        try {
            const response = await authenticatedDelete(`/api/requirements/${id}`);

            if (response.ok) {
                fetchRequirements();
            }
        } catch (error) {
            console.error('Error deleting requirement:', error);
        }
    };

    const handleFirstDeleteModalConfirm = () => {
        setShowFirstDeleteModal(false);
        setShowSecondDeleteModal(true);
        setConfirmationText('');
    };

    const handleSecondDeleteModalConfirm = async () => {
        if (confirmationText !== 'DELETE ALL') {
            setDeleteError('You must type "DELETE ALL" exactly to confirm.');
            return;
        }

        setIsDeleting(true);
        setDeleteError(null);

        try {
            const response = await authenticatedDelete('/api/requirements/all');

            const data = await response.json();

            if (response.ok) {
                setDeleteSuccess(`Successfully deleted ${data.deletedCount} requirements.`);
                setShowSecondDeleteModal(false);
                setConfirmationText('');
                fetchRequirements();
            } else {
                setDeleteError(data.error || 'Failed to delete requirements.');
            }
        } catch (err: any) {
            console.error('Delete all requirements request failed:', err);
            setDeleteError('An error occurred while deleting requirements. Please try again.');
        } finally {
            setIsDeleting(false);
        }
    };

    const closeAllDeleteModals = () => {
        setShowFirstDeleteModal(false);
        setShowSecondDeleteModal(false);
        setConfirmationText('');
        setDeleteError(null);
    };

    const handleMissingMappings = async () => {
        setIsMappingInProgress(true);
        setMappingError(null);
        setMappingSuccess(null);
        setPartialFailureWarning(null);

        try {
            // Use auto-apply endpoint - processes one requirement at a time and auto-applies mappings
            const response = await authenticatedPost('/api/norm-mapping/auto-apply', {});

            if (response.ok) {
                const data = await response.json();

                if (data.totalRequirementsAnalyzed === 0) {
                    setMappingSuccess('All requirements already have norm mappings.');
                } else {
                    // Build success message
                    const timeSeconds = (data.processingTimeMs / 1000).toFixed(1);
                    let message = `Successfully mapped ${data.requirementsSuccessfullyMapped} of ${data.totalRequirementsAnalyzed} requirements `;
                    message += `with ${data.totalMappingsApplied} norm mappings in ${timeSeconds}s.`;

                    if (data.newNormsCreated > 0) {
                        message += ` Created ${data.newNormsCreated} new norms.`;
                    }

                    setMappingSuccess(message);

                    // Show partial failure warning if some requirements failed
                    if (data.requirementsFailed > 0) {
                        setPartialFailureWarning({
                            batchesFailed: data.requirementsFailed,
                            totalBatches: data.totalRequirementsAnalyzed,
                            errors: data.failedRequirements?.map((f: any) => ({
                                batchNumber: f.requirementId,
                                errorType: f.errorType,
                                errorMessage: f.errorMessage
                            })) || []
                        });
                    }

                    // Refresh the requirements list to show updated mappings
                    fetchRequirements();
                }
            } else {
                const error = await response.json();
                if (response.status === 400) {
                    setMappingError(error.error || 'Configuration error. Please ensure OpenRouter API is configured.');
                } else if (response.status === 503) {
                    setMappingError('AI service is temporarily unavailable. Please try again later.');
                } else {
                    setMappingError(error.error || 'Failed to apply mappings');
                }
            }
        } catch (error) {
            console.error('Error during auto-apply norm mapping:', error);
            if (error instanceof Error && error.message.includes('timeout')) {
                setMappingError(
                    'Request timed out while processing requirements. ' +
                    'The AI service may be overloaded. Please try again later.'
                );
            } else {
                setMappingError('Error applying mappings. Please try again.');
            }
        } finally {
            setIsMappingInProgress(false);
        }
    };

    const handleApplyMappings = async (selectedMappings: any) => {
        try {
            const response = await authenticatedPost('/api/norm-mapping/apply', { mappings: selectedMappings });

            if (response.ok) {
                const result = await response.json();
                setMappingSuccess(`Successfully applied mappings to ${result.updatedRequirements} requirements.`);
                setShowMappingModal(false);
                fetchRequirements(); // Refresh the requirements list
            } else {
                const error = await response.json();
                setMappingError(error.error || 'Failed to apply mappings');
            }
        } catch (error) {
            console.error('Error applying norm mappings:', error);
            setMappingError('Error applying mappings. Please try again.');
        }
    };

    const closeMappingModal = () => {
        setShowMappingModal(false);
        setMappingSuggestions(null);
        setMappingError(null);
    };

    const handleEdit = (requirement: Requirement) => {
        setSelectedRequirement(requirement);
        setFormData(requirement);
        // Initialize selectedUseCaseIds based on the requirement being edited
        const currentUseCaseIds = new Set(requirement.usecases?.map(uc => uc.id) || []);
        setSelectedUseCaseIds(currentUseCaseIds);
        // Initialize selectedNormIds based on the requirement being edited
        const currentNormIds = new Set(requirement.norms?.map(norm => norm.id) || []);
        setSelectedNormIds(currentNormIds);
        setIsAddingRequirement(true);
        // Scroll to top to show the edit form
        window.scrollTo({ top: 0, behavior: 'smooth' });
    };

    const resetForm = () => {
        setFormData({
            shortreq: '',
            details: '',
            language: '',
            example: '',
            motivation: '',
            usecases: [],
            norms: []
        });
        setSelectedUseCaseIds(new Set());
        setSelectedNormIds(new Set());
        setUseCaseSearchFilter('');
        setNormSearchFilter('');
        setSelectedRequirement(null);
        setIsAddingRequirement(false);
    };

    return (
        <div className="container-fluid p-4">
            {/* Feature 067: Release indicator and selector header */}
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

            {/* Historical view warning banner */}
            {isViewingHistorical && (
                <div className="alert alert-warning d-flex align-items-center mb-3" role="alert">
                    <i className="bi bi-info-circle me-2"></i>
                    <div>
                        <strong>Historical View:</strong> You are viewing requirements from release <strong>v{selectedRelease?.version}</strong>.
                        Editing is disabled. <a href="#" onClick={(e) => { e.preventDefault(); handleClearRelease(); }} className="alert-link">Return to current requirements</a>.
                    </div>
                </div>
            )}

            <div className="row">
                <div className="col-12">
                    <div className="d-flex justify-content-between align-items-center mb-4">
                        <h2>Requirement Management</h2>
                        {/* Hide action buttons when viewing historical release */}
                        {!isViewingHistorical && (
                            <div className="d-flex gap-2">
                                <button
                                    className="btn btn-outline-primary"
                                    onClick={() => setIsAddingRequirement(!isAddingRequirement)}
                                >
                                    {isAddingRequirement ? 'Cancel' : 'Add Requirement'}
                                </button>
                                {requirements.length > 0 && (
                                    <button
                                        className="btn btn-outline-secondary"
                                        title="AI-powered mapping to ISO 27001 and IEC 62443"
                                        onClick={handleMissingMappings}
                                        disabled={isMappingInProgress}
                                    >
                                        {isMappingInProgress ? (
                                            <>
                                                <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                                                Analyzing...
                                            </>
                                        ) : (
                                            'Missing mapping'
                                        )}
                                    </button>
                                )}
                                {requirements.length > 0 && (
                                    <button
                                        className="btn btn-outline-warning"
                                        onClick={() => setShowFirstDeleteModal(true)}
                                        title="Delete all requirements"
                                        disabled={isDeleting || isMappingInProgress}
                                    >
                                        Delete All Requirements
                                    </button>
                                )}
                            </div>
                        )}
                    </div>
                </div>
            </div>

            {/* Success messages */}
            {deleteSuccess && (
                <div className="row mb-3">
                    <div className="col-12">
                        <div className="alert alert-success alert-dismissible fade show" role="alert">
                            {deleteSuccess}
                            <button type="button" className="btn-close" onClick={() => setDeleteSuccess(null)} aria-label="Close"></button>
                        </div>
                    </div>
                </div>
            )}
            
            {mappingSuccess && (
                <div className="row mb-3">
                    <div className="col-12">
                        <div className="alert alert-success alert-dismissible fade show" role="alert">
                            <i className="bi bi-check-circle me-2"></i>
                            {mappingSuccess}
                            <button type="button" className="btn-close" onClick={() => setMappingSuccess(null)} aria-label="Close"></button>
                        </div>
                    </div>
                </div>
            )}
            
            {mappingError && (
                <div className="row mb-3">
                    <div className="col-12">
                        <div className="alert alert-danger alert-dismissible fade show" role="alert">
                            <i className="bi bi-exclamation-triangle me-2"></i>
                            {mappingError}
                            <button type="button" className="btn-close" onClick={() => setMappingError(null)} aria-label="Close"></button>
                        </div>
                    </div>
                </div>
            )}

            {partialFailureWarning && (
                <div className="row mb-3">
                    <div className="col-12">
                        <div className="alert alert-warning alert-dismissible fade show" role="alert">
                            <i className="bi bi-exclamation-triangle me-2"></i>
                            <strong>Partial Results:</strong> {partialFailureWarning.batchesFailed} of{' '}
                            {partialFailureWarning.totalBatches} requirements failed to process.
                            {partialFailureWarning.errors.length > 0 && (
                                <details className="mt-2">
                                    <summary style={{ cursor: 'pointer' }}>View failure details</summary>
                                    <ul className="mb-0 mt-2 small">
                                        {partialFailureWarning.errors.map((err, idx) => (
                                            <li key={idx}>
                                                Requirement #{err.batchNumber}: {err.errorType} - {err.errorMessage}
                                            </li>
                                        ))}
                                    </ul>
                                </details>
                            )}
                            <button
                                type="button"
                                className="btn-close"
                                onClick={() => setPartialFailureWarning(null)}
                                aria-label="Close"
                            ></button>
                        </div>
                    </div>
                </div>
            )}

            {isAddingRequirement && !isViewingHistorical && (
                <div className="row mb-4">
                    <div className="col-12">
                        <div className="card">
                            <div className="card-body">
                                <h5 className="card-title">
                                    {selectedRequirement ? 'Edit Requirement' : 'Add New Requirement'}
                                </h5>
                                <form onSubmit={handleSubmit}>
                                    <div className="mb-3">
                                        <label htmlFor="shortreq" className="form-label">Short Requirement</label>
                                        <input
                                            type="text"
                                            className="form-control"
                                            id="shortreq"
                                            name="shortreq"
                                            value={formData.shortreq}
                                            onChange={handleInputChange}
                                            required
                                        />
                                    </div>

                                    {/* Details Field */}
                                    {selectedRequirement && formData.details && (
                                        <div className="mb-2 p-2 border rounded bg-light">
                                            <label className="form-label fw-bold">Details Preview:</label>
                                            <ReactMarkdown>{formData.details}</ReactMarkdown>
                                        </div>
                                    )}
                                    <div className="mb-3">
                                        <label htmlFor="details" className="form-label">Details</label>
                                        <textarea
                                            className="form-control"
                                            id="details"
                                            name="details"
                                            value={formData.details}
                                            onChange={handleInputChange}
                                            rows={5} // Increased rows slightly
                                        />
                                    </div>

                                    <div className="mb-3">
                                        <label htmlFor="language" className="form-label">Language</label>
                                        <input
                                            type="text"
                                            className="form-control"
                                            id="language"
                                            name="language"
                                            value={formData.language}
                                            onChange={handleInputChange}
                                        />
                                    </div>

                                    {/* Example Field */}
                                    {selectedRequirement && formData.example && (
                                        <div className="mb-2 p-2 border rounded bg-light">
                                            <label className="form-label fw-bold">Example Preview:</label>
                                            <ReactMarkdown>{formData.example}</ReactMarkdown>
                                        </div>
                                    )}
                                    <div className="mb-3">
                                        <label htmlFor="example" className="form-label">Example</label>
                                        <textarea
                                            className="form-control"
                                            id="example"
                                            name="example"
                                            value={formData.example}
                                            onChange={handleInputChange}
                                            rows={5} // Increased rows slightly
                                        />
                                    </div>

                                    <div className="mb-3">
                                        <label htmlFor="motivation" className="form-label">Motivation</label>
                                        <textarea
                                            className="form-control"
                                            id="motivation"
                                            name="motivation"
                                            value={formData.motivation}
                                            onChange={handleInputChange}
                                            rows={3}
                                        />
                                    </div>

                                    {/* Use Case Selection - Compact with Search */}
                                    <div className="mb-3">
                                        <label className="form-label">Associated Use Cases</label>

                                        {/* Selected use cases display */}
                                        {selectedUseCaseIds.size > 0 && (
                                            <div className="mb-2">
                                                <small className="text-muted">Selected ({selectedUseCaseIds.size}):</small>
                                                <div className="d-flex flex-wrap gap-1 mt-1">
                                                    {allUseCases.filter(uc => selectedUseCaseIds.has(uc.id)).map(uc => (
                                                        <span
                                                            key={uc.id}
                                                            className="badge bg-success d-flex align-items-center gap-1"
                                                            style={{ cursor: 'pointer' }}
                                                            onClick={() => handleUseCaseChange(uc.id)}
                                                            title="Click to remove"
                                                        >
                                                            {uc.name}
                                                            <span className="ms-1">&times;</span>
                                                        </span>
                                                    ))}
                                                </div>
                                            </div>
                                        )}

                                        {/* Search input */}
                                        <input
                                            type="text"
                                            className="form-control form-control-sm mb-2"
                                            placeholder="Search use cases..."
                                            value={useCaseSearchFilter}
                                            onChange={(e) => setUseCaseSearchFilter(e.target.value)}
                                        />

                                        {/* Scrollable use case list */}
                                        <div
                                            className="border rounded"
                                            style={{ maxHeight: '200px', overflowY: 'auto' }}
                                        >
                                            {allUseCases
                                                .filter(uc => {
                                                    if (!useCaseSearchFilter) return !selectedUseCaseIds.has(uc.id);
                                                    const searchLower = useCaseSearchFilter.toLowerCase();
                                                    return uc.name.toLowerCase().includes(searchLower) && !selectedUseCaseIds.has(uc.id);
                                                })
                                                .map(uc => (
                                                    <div
                                                        key={uc.id}
                                                        className="form-check px-3 py-1 border-bottom"
                                                        style={{ fontSize: '0.875rem' }}
                                                    >
                                                        <input
                                                            className="form-check-input"
                                                            type="checkbox"
                                                            id={`usecase-${uc.id}`}
                                                            checked={selectedUseCaseIds.has(uc.id)}
                                                            onChange={() => handleUseCaseChange(uc.id)}
                                                        />
                                                        <label
                                                            className="form-check-label"
                                                            htmlFor={`usecase-${uc.id}`}
                                                            style={{ cursor: 'pointer' }}
                                                        >
                                                            {uc.name}
                                                        </label>
                                                    </div>
                                                ))
                                            }
                                            {allUseCases.filter(uc => !selectedUseCaseIds.has(uc.id) && (!useCaseSearchFilter || uc.name.toLowerCase().includes(useCaseSearchFilter.toLowerCase()))).length === 0 && (
                                                <div className="text-muted text-center py-2" style={{ fontSize: '0.875rem' }}>
                                                    {useCaseSearchFilter ? 'No matching use cases' : 'All use cases selected'}
                                                </div>
                                            )}
                                        </div>
                                    </div>

                                    {/* Norm Selection - Compact with Search */}
                                    <div className="mb-3">
                                        <label className="form-label">Associated Norms</label>

                                        {/* Selected norms display */}
                                        {selectedNormIds.size > 0 && (
                                            <div className="mb-2">
                                                <small className="text-muted">Selected ({selectedNormIds.size}):</small>
                                                <div className="d-flex flex-wrap gap-1 mt-1">
                                                    {allNorms.filter(n => selectedNormIds.has(n.id)).map(norm => (
                                                        <span
                                                            key={norm.id}
                                                            className="badge bg-primary d-flex align-items-center gap-1"
                                                            style={{ cursor: 'pointer' }}
                                                            onClick={() => handleNormChange(norm.id)}
                                                            title="Click to remove"
                                                        >
                                                            {norm.name}{norm.version && ` (${norm.version})`}
                                                            <span className="ms-1">&times;</span>
                                                        </span>
                                                    ))}
                                                </div>
                                            </div>
                                        )}

                                        {/* Search input */}
                                        <input
                                            type="text"
                                            className="form-control form-control-sm mb-2"
                                            placeholder="Search norms..."
                                            value={normSearchFilter}
                                            onChange={(e) => setNormSearchFilter(e.target.value)}
                                        />

                                        {/* Scrollable norm list */}
                                        <div
                                            className="border rounded"
                                            style={{ maxHeight: '200px', overflowY: 'auto' }}
                                        >
                                            {allNorms
                                                .filter(norm => {
                                                    if (!normSearchFilter) return !selectedNormIds.has(norm.id);
                                                    const searchLower = normSearchFilter.toLowerCase();
                                                    const normText = `${norm.name} ${norm.version || ''} ${norm.year || ''}`.toLowerCase();
                                                    return normText.includes(searchLower) && !selectedNormIds.has(norm.id);
                                                })
                                                .map(norm => (
                                                    <div
                                                        key={norm.id}
                                                        className="form-check px-3 py-1 border-bottom"
                                                        style={{ fontSize: '0.875rem' }}
                                                    >
                                                        <input
                                                            className="form-check-input"
                                                            type="checkbox"
                                                            id={`norm-${norm.id}`}
                                                            checked={selectedNormIds.has(norm.id)}
                                                            onChange={() => handleNormChange(norm.id)}
                                                        />
                                                        <label
                                                            className="form-check-label"
                                                            htmlFor={`norm-${norm.id}`}
                                                            style={{ cursor: 'pointer' }}
                                                        >
                                                            {norm.name}{norm.version && ` (${norm.version})`}{norm.year && ` - ${norm.year}`}
                                                        </label>
                                                    </div>
                                                ))
                                            }
                                            {allNorms.filter(n => !selectedNormIds.has(n.id) && (!normSearchFilter || `${n.name} ${n.version || ''} ${n.year || ''}`.toLowerCase().includes(normSearchFilter.toLowerCase()))).length === 0 && (
                                                <div className="text-muted text-center py-2" style={{ fontSize: '0.875rem' }}>
                                                    {normSearchFilter ? 'No matching norms' : 'All norms selected'}
                                                </div>
                                            )}
                                        </div>
                                    </div>

                                    <div className="d-flex gap-2">
                                        <button type="submit" className="btn btn-success">
                                            {selectedRequirement ? 'Update' : 'Save'}
                                        </button>
                                        <button type="button" className="btn btn-secondary" onClick={resetForm}>
                                            Cancel
                                        </button>
                                    </div>
                                </form>
                            </div>
                        </div>
                    </div>
                </div>
            )}

            {/* Filter Section */}
            <div className="row mb-3">
                <div className="col-md-3">
                    <label htmlFor="usecaseFilter" className="form-label">Filter by Use Case:</label>
                    <select
                        id="usecaseFilter"
                        className="form-select"
                        value={filterUseCaseId || ''}
                        onChange={handleFilterChange}
                    >
                        <option value="">All Use Cases</option>
                        {allUseCases.map(uc => (
                            <option key={uc.id} value={uc.id}>{uc.name}</option>
                        ))}
                    </select>
                </div>
                <div className="col-md-3">
                    <label htmlFor="normFilter" className="form-label">Filter by Norm:</label>
                    <select
                        id="normFilter"
                        className="form-select"
                        value={filterNormId || ''}
                        onChange={handleNormFilterChange}
                    >
                        <option value="">All Norms</option>
                        {allNorms.map(norm => (
                            <option key={norm.id} value={norm.id}>
                                {norm.name} {norm.version && `(${norm.version})`}
                            </option>
                        ))}
                    </select>
                </div>
                <div className="col-md-6 d-flex align-items-end">
                    <span className="text-muted">
                        Showing {filteredRequirements.length} of {requirements.length} requirements
                        {' | '}
                        <span className={requirements.filter(r => !r.norms || r.norms.length === 0).length > 0 ? 'text-warning' : 'text-success'}>
                            {requirements.filter(r => !r.norms || r.norms.length === 0).length} missing norm mappings
                        </span>
                    </span>
                </div>
            </div>

            <div className="row">
                <div className="col-12">
                    <div className="table-responsive">
                        <table className="table table-striped">
                            <thead>
                                <tr>
                                    <th style={{ width: '100px' }}>ID.Rev</th>
                                    <th>Short Requirement</th>
                                    {!isViewingHistorical && <th style={{ width: '120px' }}>Actions</th>}
                                </tr>
                            </thead>
                            <tbody>
                                {filteredRequirements.map(req => (
                                    <tr key={req.id}>
                                        <td>
                                            <span className="badge bg-light text-dark font-monospace">
                                                {req.idRevision || `${req.internalId || '?'}.${req.revision || 1}`}
                                            </span>
                                        </td>
                                        <td>
                                            {req.shortreq}
                                            {req.usecases && req.usecases.length > 0 && (
                                                <div className="mt-1">
                                                    <small className="text-muted me-2">Use Cases:</small>
                                                    {req.usecases.map(uc => (
                                                        <span key={uc.id} className="badge bg-secondary me-1">{uc.name}</span>
                                                    ))}
                                                </div>
                                            )}
                                            {req.norms && req.norms.length > 0 && (
                                                <div className="mt-1">
                                                    <small className="text-muted me-2">Norms:</small>
                                                    {req.norms.map(norm => (
                                                        <span key={norm.id} className="badge bg-info me-1">
                                                            {norm.name} {norm.version && `(${norm.version})`}
                                                        </span>
                                                    ))}
                                                </div>
                                            )}
                                        </td>
                                        {!isViewingHistorical && (
                                            <td>
                                                <div className="btn-group" role="group">
                                                    <button
                                                        className="btn btn-sm btn-outline-primary"
                                                        onClick={() => handleEdit(req)}
                                                    >
                                                        Edit
                                                    </button>
                                                    <button
                                                        className="btn btn-sm btn-outline-danger"
                                                        onClick={() => req.id && handleDelete(req.id)}
                                                    >
                                                        Delete
                                                    </button>
                                                </div>
                                            </td>
                                        )}
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                </div>
            </div>

            {/* Back to Home button */}
            <div className="row mt-4">
                <div className="col-12">
                    <a href="/" className="btn btn-secondary">Back to Home</a>
                </div>
            </div>

            {/* First Confirmation Modal */}
            {showFirstDeleteModal && (
                <div className="modal fade show d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
                    <div className="modal-dialog modal-dialog-centered">
                        <div className="modal-content">
                            <div className="modal-header bg-warning">
                                <h5 className="modal-title">
                                    <i className="bi bi-exclamation-triangle-fill me-2"></i>
                                    Confirm Deletion
                                </h5>
                                <button type="button" className="btn-close" onClick={closeAllDeleteModals} aria-label="Close"></button>
                            </div>
                            <div className="modal-body">
                                <div className="alert alert-danger">
                                    <strong>Warning:</strong> You are about to delete all requirements!
                                </div>
                                <p><strong>This action will:</strong></p>
                                <ul>
                                    <li>Delete all <strong>{requirements.length}</strong> requirements</li>
                                    <li>Remove all associated relationships (use case and norm associations)</li>
                                    <li>Cannot be undone</li>
                                </ul>
                                <p className="mb-0">Are you sure you want to continue?</p>
                            </div>
                            <div className="modal-footer">
                                <button type="button" className="btn btn-secondary" onClick={closeAllDeleteModals}>Cancel</button>
                                <button type="button" className="btn btn-warning" onClick={handleFirstDeleteModalConfirm}>
                                    Yes, Continue
                                </button>
                            </div>
                        </div>
                    </div>
                </div>
            )}

            {/* Second Confirmation Modal */}
            {showSecondDeleteModal && (
                <div className="modal fade show d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
                    <div className="modal-dialog modal-dialog-centered">
                        <div className="modal-content">
                            <div className="modal-header bg-danger text-white">
                                <h5 className="modal-title">
                                    <i className="bi bi-exclamation-octagon-fill me-2"></i>
                                    Final Confirmation
                                </h5>
                                <button type="button" className="btn-close btn-close-white" onClick={closeAllDeleteModals} aria-label="Close" disabled={isDeleting}></button>
                            </div>
                            <div className="modal-body">
                                <div className="alert alert-danger">
                                    <strong>FINAL WARNING:</strong> This action is irreversible!
                                </div>
                                <p>To confirm deletion of all <strong>{requirements.length}</strong> requirements, type <strong>DELETE ALL</strong> in the box below:</p>
                                
                                {deleteError && <div className="alert alert-danger">{deleteError}</div>}
                                
                                <div className="mb-3">
                                    <input 
                                        type="text" 
                                        className="form-control" 
                                        placeholder="Type DELETE ALL here"
                                        value={confirmationText}
                                        onChange={(e) => setConfirmationText(e.target.value)}
                                        disabled={isDeleting}
                                    />
                                </div>
                            </div>
                            <div className="modal-footer">
                                <button type="button" className="btn btn-secondary" onClick={closeAllDeleteModals} disabled={isDeleting}>Cancel</button>
                                <button 
                                    type="button" 
                                    className="btn btn-danger" 
                                    onClick={handleSecondDeleteModalConfirm}
                                    disabled={isDeleting || confirmationText !== 'DELETE ALL'}
                                >
                                    {isDeleting ? (
                                        <>
                                            <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                                            Deleting...
                                        </>
                                    ) : (
                                        'Delete All Requirements'
                                    )}
                                </button>
                            </div>
                        </div>
                    </div>
                </div>
            )}

            {/* Norm Mapping Suggestions Modal */}
            {showMappingModal && mappingSuggestions && (
                <MappingSuggestionsModal
                    suggestions={mappingSuggestions}
                    onApply={handleApplyMappings}
                    onClose={closeMappingModal}
                />
            )}
        </div>
    );
}

// Mapping Suggestions Modal Component
interface NormSuggestionItem {
    standard: string;
    control: string;
    controlName: string;
    confidence: number;
    reasoning: string;
    normId?: number | null;
}

interface RequirementSuggestionItem {
    requirementId: number;
    requirementTitle: string;
    suggestions: NormSuggestionItem[];
}

interface MappingSuggestionsModalProps {
    suggestions: {
        suggestions: RequirementSuggestionItem[];
        totalRequirementsAnalyzed: number;
        totalSuggestionsGenerated: number;
        batchesProcessed?: number;
        batchesFailed?: number;
        processingTimeMs?: number;
    };
    onApply: (selectedMappings: any) => void;
    onClose: () => void;
}

// Generate unique key for a suggestion
function getSuggestionKey(reqId: number, suggestion: NormSuggestionItem): string {
    return `${reqId}-${suggestion.standard}-${suggestion.control}`;
}

function MappingSuggestionsModal({ suggestions, onApply, onClose }: MappingSuggestionsModalProps) {
    // Track selections by unique key (requirement+standard+control)
    const [selectedKeys, setSelectedKeys] = useState<Set<string>>(() => {
        // Pre-select suggestions with confidence >= 4
        const preSelected = new Set<string>();
        suggestions.suggestions?.forEach(req => {
            req.suggestions.forEach(norm => {
                if (norm.confidence >= 4) {
                    preSelected.add(getSuggestionKey(req.requirementId, norm));
                }
            });
        });
        return preSelected;
    });

    const handleMappingToggle = (requirementId: number, normSuggestion: NormSuggestionItem) => {
        const key = getSuggestionKey(requirementId, normSuggestion);
        const newSelected = new Set(selectedKeys);

        if (newSelected.has(key)) {
            newSelected.delete(key);
        } else {
            newSelected.add(key);
        }
        setSelectedKeys(newSelected);
    };

    const handleApplySelected = () => {
        // Build mappings object in the format expected by the API
        const mappings: { [reqId: string]: Array<{ normId?: number; standard?: string; control?: string; version?: string }> } = {};

        suggestions.suggestions?.forEach(req => {
            const reqNorms: Array<{ normId?: number; standard?: string; control?: string; version?: string }> = [];

            req.suggestions.forEach(norm => {
                const key = getSuggestionKey(req.requirementId, norm);
                if (selectedKeys.has(key)) {
                    if (norm.normId) {
                        // Existing norm - just pass the ID
                        reqNorms.push({ normId: norm.normId });
                    } else {
                        // New norm - pass standard/control for creation
                        reqNorms.push({
                            standard: norm.standard,
                            control: norm.control,
                            version: extractVersion(norm.standard)
                        });
                    }
                }
            });

            if (reqNorms.length > 0) {
                mappings[req.requirementId.toString()] = reqNorms;
            }
        });

        onApply(mappings);
    };

    const totalSuggestionsCount = suggestions.totalSuggestionsGenerated || 0;
    const hasSelections = selectedKeys.size > 0;
    const selectedCount = selectedKeys.size;

    return (
        <div className="modal fade show d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
            <div className="modal-dialog modal-xl modal-dialog-scrollable">
                <div className="modal-content">
                    <div className="modal-header bg-info text-white">
                        <h5 className="modal-title">
                            <i className="bi bi-diagram-3 me-2"></i>
                            AI Norm Mapping Suggestions
                        </h5>
                        <button type="button" className="btn-close btn-close-white" onClick={onClose} aria-label="Close"></button>
                    </div>
                    <div className="modal-body">
                        <div className="alert alert-info">
                            <strong>AI Analysis Complete:</strong> Analyzed {suggestions.totalRequirementsAnalyzed} requirements,
                            generated {totalSuggestionsCount} suggestions
                            {suggestions.processingTimeMs && (
                                <> in {(suggestions.processingTimeMs / 1000).toFixed(1)}s</>
                            )}.
                            {suggestions.batchesFailed && suggestions.batchesFailed > 0 && (
                                <div className="mt-2 text-warning">
                                    <i className="bi bi-exclamation-triangle me-1"></i>
                                    Warning: {suggestions.batchesFailed} batch(es) failed.
                                    Some requirements may not have suggestions.
                                </div>
                            )}
                            <br />
                            <small className="text-muted">
                                Suggestions with confidence 4-5 are pre-selected. Review and adjust selections as needed.
                            </small>
                        </div>

                        {suggestions.suggestions && suggestions.suggestions.length > 0 ? (
                            <div className="row">
                                {suggestions.suggestions.map((suggestion) => (
                                    <div key={suggestion.requirementId} className="col-12 mb-4">
                                        <div className="card">
                                            <div className="card-header bg-light">
                                                <h6 className="mb-0">
                                                    <strong>Requirement #{suggestion.requirementId}:</strong> {suggestion.requirementTitle}
                                                </h6>
                                            </div>
                                            <div className="card-body">
                                                {suggestion.suggestions.length > 0 ? (
                                                    suggestion.suggestions.map((normSuggestion, normIndex) => {
                                                        const key = getSuggestionKey(suggestion.requirementId, normSuggestion);
                                                        const isSelected = selectedKeys.has(key);
                                                        return (
                                                            <div key={normIndex} className="form-check mb-3 p-3 border rounded">
                                                                <input
                                                                    className="form-check-input"
                                                                    type="checkbox"
                                                                    id={`mapping-${key}`}
                                                                    checked={isSelected}
                                                                    onChange={() => handleMappingToggle(suggestion.requirementId, normSuggestion)}
                                                                />
                                                                <label className="form-check-label w-100" htmlFor={`mapping-${key}`}>
                                                                    <div className="d-flex justify-content-between align-items-start">
                                                                        <div>
                                                                            <strong>{normSuggestion.standard}: {normSuggestion.control}</strong>
                                                                            <br />
                                                                            <span className="text-muted">{normSuggestion.controlName}</span>
                                                                        </div>
                                                                        <div className="text-end">
                                                                            <span className={`badge ${getConfidenceBadgeClass(normSuggestion.confidence)}`}>
                                                                                Confidence: {normSuggestion.confidence}/5
                                                                            </span>
                                                                            {!normSuggestion.normId && (
                                                                                <span className="badge bg-secondary ms-2" title="This norm will be created when applied">
                                                                                    New
                                                                                </span>
                                                                            )}
                                                                        </div>
                                                                    </div>
                                                                    <div className="text-muted small mt-2 fst-italic">
                                                                        {normSuggestion.reasoning}
                                                                    </div>
                                                                </label>
                                                            </div>
                                                        );
                                                    })
                                                ) : (
                                                    <p className="text-muted mb-0">No suggestions for this requirement.</p>
                                                )}
                                            </div>
                                        </div>
                                    </div>
                                ))}
                            </div>
                        ) : (
                            <div className="alert alert-success">
                                <i className="bi bi-check-circle me-2"></i>
                                Great! All requirements already have appropriate norm mappings.
                            </div>
                        )}
                    </div>
                    <div className="modal-footer">
                        <span className="me-auto text-muted">
                            {selectedCount} suggestion{selectedCount !== 1 ? 's' : ''} selected
                        </span>
                        <button type="button" className="btn btn-secondary" onClick={onClose}>
                            Close
                        </button>
                        {totalSuggestionsCount > 0 && (
                            <button
                                type="button"
                                className="btn btn-success"
                                onClick={handleApplySelected}
                                disabled={!hasSelections}
                            >
                                Apply Selected Mappings
                            </button>
                        )}
                    </div>
                </div>
            </div>
        </div>
    );
}

// Extract version year from standard string (e.g., "ISO 27001:2022" -> "2022")
function extractVersion(standard: string): string {
    const match = standard.match(/(\d{4})/);
    return match ? match[1] : '';
}

function getConfidenceBadgeClass(confidence: number): string {
    if (confidence >= 4) return 'bg-success';
    if (confidence >= 3) return 'bg-warning';
    return 'bg-secondary';
}
