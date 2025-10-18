import React from 'react';

/**
 * Permission Denied Component
 * Feature: 025-role-based-access-control
 *
 * Displays generic error message when user lacks required permissions
 * Per spec: No role disclosure to prevent information leakage
 *
 * Constitutional Compliance:
 * - Principle I (Security-First): Generic error messages
 * - Principle V (RBAC): Clear communication of access denial
 */

interface PermissionDeniedProps {
    /** Optional custom message (still generic, no role disclosure) */
    message?: string;
    /** Whether to show contact admin suggestion */
    showContactAdmin?: boolean;
    /** Custom redirect path for "Go Back" button */
    backPath?: string;
}

const PermissionDenied: React.FC<PermissionDeniedProps> = ({
    message = "You do not have permission to access this resource.",
    showContactAdmin = true,
    backPath = "/"
}) => {
    return (
        <div className="container mt-5">
            <div className="row justify-content-center">
                <div className="col-md-8">
                    <div className="card border-danger">
                        <div className="card-header bg-danger text-white">
                            <h4 className="mb-0">
                                <i className="bi bi-shield-x me-2"></i>
                                Access Denied
                            </h4>
                        </div>
                        <div className="card-body">
                            <div className="text-center mb-4">
                                <i className="bi bi-shield-x text-danger" style={{ fontSize: '4rem' }}></i>
                            </div>

                            <h5 className="text-center mb-3">403 Forbidden</h5>

                            <p className="text-center text-muted mb-4">
                                {message}
                            </p>

                            {showContactAdmin && (
                                <div className="alert alert-info" role="alert">
                                    <i className="bi bi-info-circle me-2"></i>
                                    <strong>Need access?</strong> If you believe you should have access to this resource,
                                    please contact your system administrator.
                                </div>
                            )}

                            <div className="d-flex justify-content-center gap-3 mt-4">
                                <a href={backPath} className="btn btn-primary">
                                    <i className="bi bi-arrow-left me-2"></i>
                                    Go Back
                                </a>
                                <a href="/" className="btn btn-outline-secondary">
                                    <i className="bi bi-house-door me-2"></i>
                                    Go to Dashboard
                                </a>
                            </div>

                            <hr className="my-4" />

                            <div className="text-muted small">
                                <p className="mb-1">
                                    <strong>Why am I seeing this?</strong>
                                </p>
                                <ul className="mb-0">
                                    <li>Your user account may not have the required permissions for this resource</li>
                                    <li>The resource you're trying to access may be restricted to specific roles</li>
                                    <li>Your session may have expired - try logging out and back in</li>
                                </ul>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
};

export default PermissionDenied;
