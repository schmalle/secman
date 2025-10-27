import React, { useState, useEffect } from 'react';
import { hasVulnAccess } from '../utils/auth';
import {
    canAccessNormManagement,
    canAccessStandardManagement,
    canAccessUseCaseManagement,
    canAccessReleases,
    canAccessCompareReleases,
    hasRiskAccess,
    hasReqAccess
} from '../utils/permissions';
import { connectToBadgeUpdates } from '../services/exceptionBadgeService';

const Sidebar = () => {
    const [requirementsExpanded, setRequirementsExpanded] = useState(false);
    const [riskManagementExpanded, setRiskManagementExpanded] = useState(false);
    const [vulnMenuOpen, setVulnMenuOpen] = useState(false);
    const [ioMenuOpen, setIoMenuOpen] = useState(false);
    const [importMenuOpen, setImportMenuOpen] = useState(false);
    const [exportMenuOpen, setExportMenuOpen] = useState(false);
    const [adminMenuOpen, setAdminMenuOpen] = useState(false);
    const [isAdmin, setIsAdmin] = useState(false);
    const [hasVuln, setHasVuln] = useState(false);
    const [hasRisk, setHasRisk] = useState(false);
    const [hasReq, setHasReq] = useState(false);
    const [userRoles, setUserRoles] = useState<string[]>([]);
    const [pendingExceptionCount, setPendingExceptionCount] = useState<number>(0);

    const toggleRequirements = () => {
        setRequirementsExpanded(!requirementsExpanded);
    };

    const toggleRiskManagement = () => {
        setRiskManagementExpanded(!riskManagementExpanded);
    };

    // Check if user has admin role and access permissions
    // Feature: 025-role-based-access-control
    useEffect(() => {
        function checkRoles() {
            const user = (window as any).currentUser;
            const roles = user?.roles || [];
            const hasAdmin = roles.includes('ADMIN');
            setIsAdmin(hasAdmin);
            setHasVuln(hasVulnAccess());
            setHasRisk(hasRiskAccess(roles));
            setHasReq(hasReqAccess(roles));
            setUserRoles(roles);
        }

        // Check on mount
        checkRoles();

        // Listen for user data updates
        window.addEventListener('userLoaded', checkRoles);

        // Cleanup listener on unmount
        return () => window.removeEventListener('userLoaded', checkRoles);
    }, []);

    // Connect to real-time SSE updates for exception approval badge
    // Feature: 031-vuln-exception-approval, Phase 6: Real-Time Badge Updates
    useEffect(() => {
        // Only connect if user has ADMIN or SECCHAMPION role (can approve exceptions)
        const canApprove = userRoles.includes('ADMIN') || userRoles.includes('SECCHAMPION');
        if (!canApprove) {
            // Reset count if user doesn't have permission
            setPendingExceptionCount(0);
            return;
        }

        // Connect to SSE endpoint for real-time count updates
        const disconnect = connectToBadgeUpdates((count) => {
            setPendingExceptionCount(count);
        });

        // Cleanup on unmount or when roles change
        return () => {
            disconnect();
        };
    }, [userRoles.join(',')]); // Re-connect only when actual role membership changes

    return (
        <nav id="sidebar" className="bg-light border-end">
            <div className="sidebar-header p-3">
                <h3> </h3>
            </div>

            <ul className="list-unstyled components p-2">
                {/* OVERVIEW & ANALYTICS Section */}
                <li className="sidebar-section-header">OVERVIEW & ANALYTICS</li>
                <li>
                    <a href="/" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                        <i className="bi bi-house-door me-2"></i> Dashboard
                    </a>
                </li>
                <li>
                    <a href="/notification-preferences" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                        <i className="bi bi-bell me-2"></i> Notifications
                    </a>
                </li>

                {/* ASSET MANAGEMENT Section */}
                <li className="sidebar-section-header">ASSET MANAGEMENT</li>
                <li>
                    <a href="/assets" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                        <i className="bi bi-server me-2"></i> Assets Overview
                    </a>
                </li>

                {/* REQUIREMENTS Section - ADMIN, REQ, or SECCHAMPION only (Feature: 025-role-based-access-control) */}
                {hasReq && (
                    <li>
                        <div
                            onClick={toggleRequirements}
                            className="sidebar-section-header-clickable d-flex align-items-center cursor-pointer"
                            style={{ cursor: 'pointer' }}
                        >
                            <i className="bi bi-card-checklist me-2"></i>
                            REQUIREMENTS
                            <i className={`bi ${requirementsExpanded ? 'bi-chevron-down' : 'bi-chevron-right'} ms-auto`}></i>
                        </div>
                        {requirementsExpanded && (
                            <ul className="list-unstyled ps-4">
                                <li>
                                    <a href="/requirements" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                        <i className="bi bi-card-checklist me-2"></i> Requirements Overview
                                    </a>
                                </li>
                                {canAccessNormManagement(userRoles) && (
                                    <li>
                                        <a href="/norms" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                            <i className="bi bi-bookmark-star me-2"></i> Norm Management
                                        </a>
                                    </li>
                                )}
                                {canAccessStandardManagement(userRoles) && (
                                    <li>
                                        <a href="/standards" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                            <i className="bi bi-list-check me-2"></i> Standard Management
                                        </a>
                                    </li>
                                )}
                                {canAccessUseCaseManagement(userRoles) && (
                                    <li>
                                        <a href="/usecases" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                            <i className="bi bi-diagram-3 me-2"></i> UseCase Management
                                        </a>
                                    </li>
                                )}
                                {canAccessReleases(userRoles) && (
                                    <li>
                                        <a href="/releases" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                            <i className="bi bi-archive me-2"></i> Releases
                                        </a>
                                    </li>
                                )}
                                {canAccessCompareReleases(userRoles) && (
                                    <li>
                                        <a href="/releases/compare" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                            <i className="bi bi-columns-gap me-2"></i> Compare Releases
                                        </a>
                                    </li>
                                )}
                            </ul>
                        )}
                    </li>
                )}

                {/* RISK MANAGEMENT Section - ADMIN, RISK, or SECCHAMPION only (Feature: 025-role-based-access-control) */}
                {hasRisk && (
                    <li>
                        <div
                            onClick={toggleRiskManagement}
                            className="sidebar-section-header-clickable d-flex align-items-center cursor-pointer"
                            style={{ cursor: 'pointer' }}
                        >
                            <i className="bi bi-exclamation-triangle-fill me-2"></i>
                            RISK MANAGEMENT
                            <i className={`bi ${riskManagementExpanded ? 'bi-chevron-down' : 'bi-chevron-right'} ms-auto`}></i>
                        </div>
                        {riskManagementExpanded && (
                            <ul className="list-unstyled ps-4">
                                <li>
                                    <a href="/risks" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                        <i className="bi bi-exclamation-triangle-fill me-2"></i> Risk Management Overview
                                    </a>
                                </li>
                                <li>
                                    <a href="/riskassessment" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                        <i className="bi bi-clipboard-data me-2"></i> Risk Assessment
                                    </a>
                                </li>
                                <li>
                                    <a href="/reports" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                        <i className="bi bi-bar-chart-fill me-2"></i> Reports
                                    </a>
                                </li>
                            </ul>
                        )}
                    </li>
                )}

                {/* DEMAND MANAGEMENT Section */}
                <li className="sidebar-section-header">DEMAND MANAGEMENT</li>
                <li>
                    <a href="/demands" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                        <i className="bi bi-clipboard-plus me-2"></i> Demand Management
                    </a>
                </li>

                {/* VULNERABILITY MANAGEMENT Section - ADMIN or VULN role (Feature: 004-i-want-to) */}
                {hasVuln && (
                    <li>
                        <div
                            onClick={() => setVulnMenuOpen(!vulnMenuOpen)}
                            className="sidebar-section-header-clickable d-flex align-items-center cursor-pointer"
                            style={{ cursor: 'pointer' }}
                        >
                            <i className="bi bi-shield-exclamation me-2"></i>
                            VULNERABILITY MANAGEMENT
                            <i className={`bi ${vulnMenuOpen ? 'bi-chevron-down' : 'bi-chevron-right'} ms-auto`}></i>
                        </div>
                        {vulnMenuOpen && (
                            <ul className="list-unstyled ps-4">
                                <li>
                                    <a href="/vulnerabilities/current" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                        <i className="bi bi-list-ul me-2"></i> Vuln overview
                                    </a>
                                </li>
                                <li>
                                    <a href="/vulnerabilities/domain" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                        <i className="bi bi-globe me-2"></i> Domain vulns
                                    </a>
                                </li>
                                <li>
                                    <a href="/vulnerabilities/system" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                        <i className="bi bi-hdd me-2"></i> System vulns
                                    </a>
                                </li>
                                <li>
                                    <a
                                        href={isAdmin ? "#" : "/account-vulns"}
                                        className={`d-flex align-items-center p-2 text-decoration-none rounded ${isAdmin ? 'text-muted' : 'text-dark hover-bg-secondary'}`}
                                        title={isAdmin ? "Admins should use System Vulns view" : "View vulnerabilities for your AWS accounts"}
                                        style={isAdmin ? { cursor: 'not-allowed', pointerEvents: 'none' } : {}}
                                    >
                                        <i className="bi bi-cloud me-2"></i> Account vulns
                                    </a>
                                </li>
                                <li>
                                    <a
                                        href={isAdmin ? "#" : "/wg-vulns"}
                                        className={`d-flex align-items-center p-2 text-decoration-none rounded ${isAdmin ? 'text-muted' : 'text-dark hover-bg-secondary'}`}
                                        title={isAdmin ? "Admins should use System Vulns view" : "View vulnerabilities for your workgroups"}
                                        style={isAdmin ? { cursor: 'not-allowed', pointerEvents: 'none' } : {}}
                                    >
                                        <i className="bi bi-people-fill me-2"></i> WG vulns
                                    </a>
                                </li>
                                <li>
                                    <a href="/outdated-assets" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                        <i className="bi bi-hourglass-split me-2"></i> Outdated Assets
                                    </a>
                                </li>
                                <li>
                                    <a href="/vulnerabilities/exceptions" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                        <i className="bi bi-x-circle me-2"></i> Exceptions
                                    </a>
                                </li>
                                <li>
                                    <a href="/my-exception-requests" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                        <i className="bi bi-clipboard-check me-2"></i> My Exception Requests
                                    </a>
                                </li>
                                {(userRoles.includes('ADMIN') || userRoles.includes('SECCHAMPION')) && (
                                    <li>
                                        <a href="/exception-approvals" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                            <i className="bi bi-shield-check me-2"></i> Approve Exceptions
                                            {pendingExceptionCount > 0 && (
                                                <span className="badge bg-danger ms-auto" title={`${pendingExceptionCount} pending approval${pendingExceptionCount > 1 ? 's' : ''}`}>
                                                    {pendingExceptionCount}
                                                </span>
                                            )}
                                        </a>
                                    </li>
                                )}
                            </ul>
                        )}
                    </li>
                )}

                {/* I/O Section */}
                <li>
                    <div
                        onClick={() => setIoMenuOpen(!ioMenuOpen)}
                        className="sidebar-section-header-clickable d-flex align-items-center cursor-pointer"
                        style={{ cursor: 'pointer' }}
                    >
                        <i className="bi bi-arrow-down-up me-2"></i>
                        I/O
                        <i className={`bi ${ioMenuOpen ? 'bi-chevron-down' : 'bi-chevron-right'} ms-auto`}></i>
                    </div>
                    {ioMenuOpen && (
                        <ul className="list-unstyled ps-4">
                            {/* Import sub-menu */}
                            <li>
                                <div
                                    onClick={() => setImportMenuOpen(!importMenuOpen)}
                                    className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary cursor-pointer"
                                    style={{ cursor: 'pointer' }}
                                >
                                    <i className="bi bi-cloud-upload me-2"></i>
                                    Import
                                    <i className={`bi ${importMenuOpen ? 'bi-chevron-down' : 'bi-chevron-right'} ms-auto`}></i>
                                </div>
                                {importMenuOpen && (
                                    <ul className="list-unstyled ps-4">
                                        <li>
                                            <a href="/import" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                                <i className="bi bi-file-earmark-excel me-2"></i> Requirements
                                            </a>
                                        </li>
                                        <li>
                                            <a href="/import?type=assets" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                                <i className="bi bi-hdd-rack me-2"></i> Assets
                                            </a>
                                        </li>
                                    </ul>
                                )}
                            </li>
                            {/* Export sub-menu */}
                            <li>
                                <div
                                    onClick={() => setExportMenuOpen(!exportMenuOpen)}
                                    className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary cursor-pointer"
                                    style={{ cursor: 'pointer' }}
                                >
                                    <i className="bi bi-download me-2"></i>
                                    Export
                                    <i className={`bi ${exportMenuOpen ? 'bi-chevron-down' : 'bi-chevron-right'} ms-auto`}></i>
                                </div>
                                {exportMenuOpen && (
                                    <ul className="list-unstyled ps-4">
                                        <li>
                                            <a href="/export" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                                <i className="bi bi-file-earmark-excel me-2"></i> Requirements
                                            </a>
                                        </li>
                                        <li>
                                            <a href="/export?type=assets" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                                <i className="bi bi-hdd-rack me-2"></i> Assets
                                            </a>
                                        </li>
                                    </ul>
                                )}
                            </li>
                        </ul>
                    )}
                </li>

                {/* TOOLS Section */}
                <li className="sidebar-section-header">TOOLS</li>
                <li>
                    <a href="/public-classification" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                        <i className="bi bi-funnel me-2"></i> Classification Tool
                    </a>
                </li>

                {/* ADMIN Section - expandable menu (only visible to admin users) */}
                {isAdmin && (
                    <li>
                        <div
                            onClick={() => setAdminMenuOpen(!adminMenuOpen)}
                            className="sidebar-section-header-clickable d-flex align-items-center cursor-pointer"
                            style={{ cursor: 'pointer' }}
                        >
                            <i className="bi bi-speedometer2 me-2"></i>
                            ADMIN
                            <i className={`bi ${adminMenuOpen ? 'bi-chevron-down' : 'bi-chevron-right'} ms-auto`}></i>
                        </div>
                        {adminMenuOpen && (
                            <ul className="list-unstyled ps-4">
                                <li>
                                    <a href="/admin" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                        <i className="bi bi-gear me-2"></i> General
                                    </a>
                                </li>
                                <li>
                                    <a href="/admin/user-management" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                        <i className="bi bi-people-fill me-2"></i> Manage Users
                                    </a>
                                </li>
                                <li>
                                    <a href="/workgroups" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                        <i className="bi bi-people-fill me-2"></i> Workgroups
                                    </a>
                                </li>
                                <li>
                                    <a href="/scans" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                        <i className="bi bi-diagram-3 me-2"></i> Scans
                                    </a>
                                </li>
                                <li>
                                    <a href="/notification-logs" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                        <i className="bi bi-envelope-paper me-2"></i> Notification Logs
                                    </a>
                                </li>
                            </ul>
                        )}
                    </li>
                )}

                {/* About at the bottom */}
                <li>
                    <a href="/about" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                        <i className="bi bi-info-circle me-2"></i> About
                    </a>
                </li>
            </ul>
        </nav>
    );
};

export default Sidebar;

// Basic styling (can be moved to a CSS file)
const styles = `
#sidebar {
    min-width: 250px;
    max-width: 250px;
    min-height: 100vh;
    transition: all 0.3s;
}

#sidebar.active {
    margin-left: -250px;
}

.hover-bg-secondary:hover {
    background-color: #e9ecef; /* Bootstrap secondary background color */
}

.sidebar-section-header {
    font-size: 0.75rem;
    font-weight: 600;
    text-transform: uppercase;
    color: #6c757d;
    padding: 12px 8px 6px 8px;
    margin-top: 8px;
    letter-spacing: 0.5px;
    background-color: #f8f9fa;
    border-left: 3px solid #0d6efd;
}

.sidebar-section-header:first-child {
    margin-top: 0;
}

.sidebar-section-header-clickable {
    font-size: 0.75rem;
    font-weight: 600;
    text-transform: uppercase;
    color: #6c757d;
    padding: 12px 8px 8px 8px;
    margin-top: 8px;
    letter-spacing: 0.5px;
    background-color: #f8f9fa;
    border-left: 3px solid #0d6efd;
    transition: all 0.2s ease;
}

.sidebar-section-header-clickable:hover {
    background-color: #e9ecef;
    color: #495057;
}
`;

// Inject styles into the head
if (typeof window !== 'undefined') {
    const styleSheet = document.createElement("style");
    styleSheet.type = "text/css";
    styleSheet.innerText = styles;
    document.head.appendChild(styleSheet);
}
