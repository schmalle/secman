import React, { useState, useEffect } from 'react';
import { hasVulnAccess } from '../utils/auth';
import { 
    canAccessNormManagement, 
    canAccessStandardManagement, 
    canAccessUseCaseManagement,
    canAccessReleases,
    canAccessCompareReleases
} from '../utils/permissions';

const Sidebar = () => {
    const [requirementsExpanded, setRequirementsExpanded] = useState(false);
    const [riskManagementExpanded, setRiskManagementExpanded] = useState(false);
    const [vulnMenuOpen, setVulnMenuOpen] = useState(false);
    const [ioMenuOpen, setIoMenuOpen] = useState(false);
    const [adminMenuOpen, setAdminMenuOpen] = useState(false);
    const [isAdmin, setIsAdmin] = useState(false);
    const [hasVuln, setHasVuln] = useState(false);
    const [userRoles, setUserRoles] = useState<string[]>([]);

    const toggleRequirements = () => {
        setRequirementsExpanded(!requirementsExpanded);
    };

    const toggleRiskManagement = () => {
        setRiskManagementExpanded(!riskManagementExpanded);
    };

    // Check if user has admin role and vuln access
    useEffect(() => {
        function checkRoles() {
            const user = (window as any).currentUser;
            const roles = user?.roles || [];
            const hasAdmin = roles.includes('ADMIN');
            setIsAdmin(hasAdmin);
            setHasVuln(hasVulnAccess());
            setUserRoles(roles);
        }

        // Check on mount
        checkRoles();

        // Listen for user data updates
        window.addEventListener('userLoaded', checkRoles);

        // Cleanup listener on unmount
        return () => window.removeEventListener('userLoaded', checkRoles);
    }, []);

    return (
        <nav id="sidebar" className="bg-light border-end">
            <div className="sidebar-header p-3">
                <h3> </h3>
            </div>

            <ul className="list-unstyled components p-2">
                <li>
                    <a href="/" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                        <i className="bi bi-house-door me-2"></i> Dashboard
                    </a>
                </li>
                
                {/* Requirements with sub-items */}
                <li>
                    <div 
                        onClick={toggleRequirements}
                        className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary cursor-pointer"
                        style={{ cursor: 'pointer' }}
                    >
                        <i className="bi bi-card-checklist me-2"></i> 
                        Requirements
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

                
                {/* Risk Management with sub-items */}
                <li>
                    <div 
                        onClick={toggleRiskManagement}
                        className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary cursor-pointer"
                        style={{ cursor: 'pointer' }}
                    >
                        <i className="bi bi-exclamation-triangle-fill me-2"></i> 
                        Risk Management
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

                <li>
                    <a href="/assets" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                        <i className="bi bi-server me-2"></i> Asset Management
                    </a>
                </li>
                <li>
                    <a href="/demands" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                        <i className="bi bi-clipboard-plus me-2"></i> Demand Management
                    </a>
                </li>
                {/* I/O section with Import and Export sub-items */}
                <li>
                    <div
                        onClick={() => setIoMenuOpen(!ioMenuOpen)}
                        className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary cursor-pointer"
                        style={{ cursor: 'pointer' }}
                    >
                        <i className="bi bi-arrow-down-up me-2"></i>
                        I/O
                        <i className={`bi ${ioMenuOpen ? 'bi-chevron-down' : 'bi-chevron-right'} ms-auto`}></i>
                    </div>
                    {ioMenuOpen && (
                        <ul className="list-unstyled ps-4">
                            <li>
                                <a href="/import" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                    <i className="bi bi-cloud-upload me-2"></i> Import
                                </a>
                            </li>
                            <li>
                                <a href="/export" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                    <i className="bi bi-download me-2"></i> Export
                                </a>
                            </li>
                        </ul>
                    )}
                </li>

                {/* Vulnerability Management - ADMIN or VULN role (Feature: 004-i-want-to) */}
                {hasVuln && (
                    <li>
                        <div
                            onClick={() => setVulnMenuOpen(!vulnMenuOpen)}
                            className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary cursor-pointer"
                            style={{ cursor: 'pointer' }}
                        >
                            <i className="bi bi-shield-exclamation me-2"></i>
                            Vuln Management
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
                                    <a href="/vulnerabilities/exceptions" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                        <i className="bi bi-x-circle me-2"></i> Exceptions
                                    </a>
                                </li>
                                {isAdmin && (
                                    <li>
                                        <a href="/scans" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                            <i className="bi bi-diagram-3 me-2"></i> Scans
                                        </a>
                                    </li>
                                )}
                            </ul>
                        )}
                    </li>
                )}
                <li>
                    <a href="/public-classification" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                        <i className="bi bi-funnel me-2"></i> Classification Tool
                    </a>
                </li>
                
                {/* Admin section - expandable menu (only visible to admin users) */}
                {isAdmin && (
                    <li>
                        <div
                            onClick={() => setAdminMenuOpen(!adminMenuOpen)}
                            className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary cursor-pointer"
                            style={{ cursor: 'pointer' }}
                        >
                            <i className="bi bi-speedometer2 me-2"></i>
                            Admin
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
                            </ul>
                        )}
                    </li>
                )}
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
`;

// Inject styles into the head
if (typeof window !== 'undefined') {
    const styleSheet = document.createElement("style");
    styleSheet.type = "text/css";
    styleSheet.innerText = styles;
    document.head.appendChild(styleSheet);
}
