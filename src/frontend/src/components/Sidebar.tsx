import React, { useState, useEffect } from 'react';

const Sidebar = () => {
    const [requirementsExpanded, setRequirementsExpanded] = useState(false);
    const [riskManagementExpanded, setRiskManagementExpanded] = useState(false);
    const [isAdmin, setIsAdmin] = useState(false);

    const toggleRequirements = () => {
        setRequirementsExpanded(!requirementsExpanded);
    };

    const toggleRiskManagement = () => {
        setRiskManagementExpanded(!riskManagementExpanded);
    };

    // Check if user has admin role
    useEffect(() => {
        function checkAdminRole() {
            const user = (window as any).currentUser;
            const hasAdmin = user?.roles?.includes('ADMIN') || false;
            setIsAdmin(hasAdmin);
        }

        // Check on mount
        checkAdminRole();

        // Listen for user data updates
        window.addEventListener('userLoaded', checkAdminRole);

        // Cleanup listener on unmount
        return () => window.removeEventListener('userLoaded', checkAdminRole);
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
                            <li>
                                <a href="/norms" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                    <i className="bi bi-bookmark-star me-2"></i> Norm Management
                                </a>
                            </li>
                            <li>
                                <a href="/standards" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                    <i className="bi bi-list-check me-2"></i> Standard Management
                                </a>
                            </li>
                            <li>
                                <a href="/usecases" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                    <i className="bi bi-diagram-3 me-2"></i> UseCase Management
                                </a>
                            </li>
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
                
                <li>
                    <a href="/public-classification" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                        <i className="bi bi-funnel me-2"></i> Classification Tool
                    </a>
                </li>
                
                {/* Admin section - direct link (only visible to admin users) */}
                {isAdmin && (
                    <li>
                        <a href="/admin" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                            <i className="bi bi-speedometer2 me-2"></i> Admin
                        </a>
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
