import React, { useState, useEffect } from 'react';

// Define an interface for the user data expected from the backend
interface User {
    id: number;
    username: string;
    email: string;
    roles: string[];
}

// Define a type for the global variable
declare global {
    interface Window {
        currentUser?: User | null;
    }
}

const RequirementsAdmin = () => {
    const [isAdmin, setIsAdmin] = useState(false);
    const [isLoading, setIsLoading] = useState(true);
    const [requirementsCount, setRequirementsCount] = useState(0);
    const [error, setError] = useState<string | null>(null);
    
    // Modal states
    const [showFirstModal, setShowFirstModal] = useState(false);
    const [showSecondModal, setShowSecondModal] = useState(false);
    const [confirmationText, setConfirmationText] = useState('');
    const [isDeleting, setIsDeleting] = useState(false);
    const [deleteSuccess, setDeleteSuccess] = useState<string | null>(null);
    const [deleteError, setDeleteError] = useState<string | null>(null);

    useEffect(() => {
        let isMounted = true;
        const listener = async () => {
            await checkAdminAndFetchCount();
        };

        const checkAdminAndFetchCount = async () => {
            let userIsAdmin = false;
            if (window.currentUser) {
                userIsAdmin = window.currentUser.roles?.includes('ADMIN') ?? false;
                if (isMounted) {
                    setIsAdmin(userIsAdmin);
                    setIsLoading(false);
                }

                // If admin, fetch requirements count
                if (userIsAdmin) {
                    await fetchRequirementsCount(isMounted);
                } else {
                    if (isMounted) setIsLoading(false);
                }
            }
        };

        // Check immediately if user data is already loaded
        if (window.currentUser) {
            checkAdminAndFetchCount();
        } else {
            // If not loaded, add the event listener
            window.addEventListener('userLoaded', listener);

            // Set a timeout as a fallback
            const timeoutId = setTimeout(() => {
                if (isLoading && isMounted) {
                    setIsLoading(false);
                    setIsAdmin(false);
                    if (!window.currentUser) {
                        setError("Could not verify user permissions.");
                    }
                }
            }, 3000);

            // Cleanup for timeout
            return () => {
                clearTimeout(timeoutId);
                window.removeEventListener('userLoaded', listener);
                isMounted = false;
            };
        }

        // Cleanup for case where user data was immediately available
        return () => {
            isMounted = false;
        };
    }, []);

    const fetchRequirementsCount = async (isMounted: boolean) => {
        try {
            const response = await fetch('/api/requirements');
            if (!response.ok) {
                if (response.status === 403) {
                    throw new Error('Access Denied: You do not have permission to fetch requirements.');
                } else {
                    const errorData = await response.json().catch(() => ({}));
                    throw new Error(errorData.error || `Failed to fetch requirements: ${response.status} ${response.statusText}`);
                }
            }
            const data = await response.json();
            if (isMounted) {
                setRequirementsCount(Array.isArray(data) ? data.length : 0);
                setError(null);
            }
        } catch (err: any) {
            console.error("Error fetching requirements count:", err);
            if (isMounted) {
                setError(err.message || "Could not load requirements data.");
                setRequirementsCount(0);
            }
        }
    };

    const handleRefreshCount = () => {
        setError(null);
        fetchRequirementsCount(true);
    };

    const handleFirstModalConfirm = () => {
        setShowFirstModal(false);
        setShowSecondModal(true);
        setConfirmationText('');
    };

    const handleSecondModalConfirm = async () => {
        if (confirmationText !== 'DELETE ALL') {
            setDeleteError('You must type "DELETE ALL" exactly to confirm.');
            return;
        }

        setIsDeleting(true);
        setDeleteError(null);

        try {
            const response = await fetch('/api/requirements/all', {
                method: 'DELETE',
            });

            const data = await response.json();

            if (response.ok) {
                setDeleteSuccess(`Successfully deleted ${data.deletedCount} requirements.`);
                setRequirementsCount(0);
                setShowSecondModal(false);
                setConfirmationText('');
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

    const closeAllModals = () => {
        setShowFirstModal(false);
        setShowSecondModal(false);
        setConfirmationText('');
        setDeleteError(null);
    };

    // --- Render Logic ---
    if (isLoading) {
        return <div className="alert alert-info">Checking permissions and loading data...</div>;
    }

    if (!isAdmin) {
        const message = error && error.includes("Access Denied") 
                        ? error 
                        : "Access Denied: You do not have permission to manage requirements.";
        const finalMessage = error && !isAdmin ? error : message;
        return <div className="alert alert-danger">{finalMessage}</div>;
    }

    return (
        <div>
            <div className="d-flex justify-content-between align-items-center mb-4">
                <h2>Requirements Management</h2>
                <a href="/admin" className="btn btn-secondary">
                    <i className="bi bi-arrow-left me-2"></i>Back to Admin
                </a>
            </div>
            
            <p>Manage system requirements and perform administrative operations.</p>

            {/* Display non-permission errors here */}
            {error && !error.includes("Access Denied") && (
                <div className="alert alert-warning" role="alert">
                    Error loading data: {error}
                </div>
            )}

            {/* Success message */}
            {deleteSuccess && (
                <div className="alert alert-success alert-dismissible fade show" role="alert">
                    {deleteSuccess}
                    <button type="button" className="btn-close" onClick={() => setDeleteSuccess(null)} aria-label="Close"></button>
                </div>
            )}

            {/* Current Status Card */}
            <div className="card mb-4">
                <div className="card-header">
                    <h5 className="card-title mb-0">
                        <i className="bi bi-info-circle me-2"></i>
                        Current Status
                    </h5>
                </div>
                <div className="card-body">
                    <div className="row">
                        <div className="col-md-6">
                            <h6>Total Requirements:</h6>
                            <p className="h4 text-primary">{requirementsCount}</p>
                        </div>
                        <div className="col-md-6">
                            <button 
                                className="btn btn-outline-primary" 
                                onClick={handleRefreshCount}
                                disabled={isDeleting}
                            >
                                <i className="bi bi-arrow-clockwise me-2"></i>
                                Refresh Count
                            </button>
                        </div>
                    </div>
                </div>
            </div>

            {/* Danger Zone Card */}
            <div className="card border-danger">
                <div className="card-header bg-danger text-white">
                    <h5 className="card-title mb-0">
                        <i className="bi bi-exclamation-triangle-fill me-2"></i>
                        Danger Zone
                    </h5>
                </div>
                <div className="card-body">
                    <h6>Delete All Requirements</h6>
                    <p className="text-muted">
                        This action will permanently delete all requirements from the system. 
                        This cannot be undone and will also remove all associated relationships.
                    </p>
                    <button 
                        className="btn btn-danger"
                        onClick={() => setShowFirstModal(true)}
                        disabled={isDeleting || requirementsCount === 0}
                    >
                        <i className="bi bi-trash-fill me-2"></i>
                        Delete All Requirements
                    </button>
                    {requirementsCount === 0 && (
                        <small className="text-muted d-block mt-2">
                            No requirements to delete.
                        </small>
                    )}
                </div>
            </div>

            {/* First Confirmation Modal */}
            {showFirstModal && (
                <div className="modal fade show d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
                    <div className="modal-dialog modal-dialog-centered">
                        <div className="modal-content">
                            <div className="modal-header bg-warning">
                                <h5 className="modal-title">
                                    <i className="bi bi-exclamation-triangle-fill me-2"></i>
                                    Confirm Deletion
                                </h5>
                                <button type="button" className="btn-close" onClick={closeAllModals} aria-label="Close"></button>
                            </div>
                            <div className="modal-body">
                                <div className="alert alert-danger">
                                    <strong>Warning:</strong> You are about to delete all requirements!
                                </div>
                                <p><strong>This action will:</strong></p>
                                <ul>
                                    <li>Delete all <strong>{requirementsCount}</strong> requirements</li>
                                    <li>Remove all associated relationships (use case associations)</li>
                                    <li>Cannot be undone</li>
                                </ul>
                                <p className="mb-0">Are you sure you want to continue?</p>
                            </div>
                            <div className="modal-footer">
                                <button type="button" className="btn btn-secondary" onClick={closeAllModals}>Cancel</button>
                                <button type="button" className="btn btn-warning" onClick={handleFirstModalConfirm}>
                                    Yes, Continue
                                </button>
                            </div>
                        </div>
                    </div>
                </div>
            )}

            {/* Second Confirmation Modal */}
            {showSecondModal && (
                <div className="modal fade show d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
                    <div className="modal-dialog modal-dialog-centered">
                        <div className="modal-content">
                            <div className="modal-header bg-danger text-white">
                                <h5 className="modal-title">
                                    <i className="bi bi-exclamation-octagon-fill me-2"></i>
                                    Final Confirmation
                                </h5>
                                <button type="button" className="btn-close btn-close-white" onClick={closeAllModals} aria-label="Close" disabled={isDeleting}></button>
                            </div>
                            <div className="modal-body">
                                <div className="alert alert-danger">
                                    <strong>FINAL WARNING:</strong> This action is irreversible!
                                </div>
                                <p>To confirm deletion of all <strong>{requirementsCount}</strong> requirements, type <strong>DELETE ALL</strong> in the box below:</p>
                                
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
                                <button type="button" className="btn btn-secondary" onClick={closeAllModals} disabled={isDeleting}>Cancel</button>
                                <button 
                                    type="button" 
                                    className="btn btn-danger" 
                                    onClick={handleSecondModalConfirm}
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
        </div>
    );
};

export default RequirementsAdmin;