import React, { useState, useEffect } from 'react';
import { hasVulnAccess } from '../utils/auth';
import {
    canAccessNormManagement,
    canAccessStandardManagement,
    canAccessUseCaseManagement,
    canAccessReleases,
    canAccessCompareReleases,
    hasRiskAccess,
    hasReqAccess,
    hasClassificationAccess,
    canAccessAccountOnboarding
} from '../utils/permissions';
import { connectToBadgeUpdates } from '../services/exceptionBadgeService';
import { ADMIN_NAV, activeHrefForPath, filterAdminNav, groupKeyForPath } from './adminNav';

const Sidebar = () => {
    const [assetsExpanded, setAssetsExpanded] = useState(false);
    const [ioMenuOpen, setIoMenuOpen] = useState(false);
    const [requirementsExpanded, setRequirementsExpanded] = useState(false);
    const [riskManagementExpanded, setRiskManagementExpanded] = useState(false);
    const [vulnMenuOpen, setVulnMenuOpen] = useState(false);
    const [exportMenuOpen, setExportMenuOpen] = useState(false);
    const [adminMenuOpen, setAdminMenuOpen] = useState(false);
    const [isAdmin, setIsAdmin] = useState(false);
    const [hasVuln, setHasVuln] = useState(false);
    const [hasRisk, setHasRisk] = useState(false);
    const [hasReq, setHasReq] = useState(false);
    const [hasClassification, setHasClassification] = useState(false);
    const [userRoles, setUserRoles] = useState<string[]>([]);
    const [workgroupCount, setWorkgroupCount] = useState<number>(0);
    const [awsAccountCount, setAwsAccountCount] = useState<number>(0);
    const [domainCount, setDomainCount] = useState<number>(0);
    const [pendingExceptionCount, setPendingExceptionCount] = useState<number>(0);
    // ADMIN rail (design 1b): the filter text, the one open group, and the entry
    // for the page we are on. The latter two are derived from the URL on mount —
    // `location` does not exist during Astro's server render.
    const [adminQuery, setAdminQuery] = useState('');
    const [openAdminGroup, setOpenAdminGroup] = useState<string | null>(null);
    const [activeAdminHref, setActiveAdminHref] = useState<string | null>(null);

    const toggleAssets = () => {
        setAssetsExpanded(!assetsExpanded);
        // Collapse all other sections
        setRequirementsExpanded(false);
        setRiskManagementExpanded(false);
        setVulnMenuOpen(false);
        setAdminMenuOpen(false);
        setIoMenuOpen(false);
    };

    const toggleRequirements = () => {
        setRequirementsExpanded(!requirementsExpanded);
        // Collapse all other sections
        setAssetsExpanded(false);
        setRiskManagementExpanded(false);
        setVulnMenuOpen(false);
        setAdminMenuOpen(false);
        setIoMenuOpen(false);
    };

    const toggleRiskManagement = () => {
        setRiskManagementExpanded(!riskManagementExpanded);
        // Collapse all other sections
        setAssetsExpanded(false);
        setRequirementsExpanded(false);
        setVulnMenuOpen(false);
        setAdminMenuOpen(false);
        setIoMenuOpen(false);
    };

    const toggleVulnManagement = () => {
        setVulnMenuOpen(!vulnMenuOpen);
        // Collapse all other sections
        setAssetsExpanded(false);
        setRequirementsExpanded(false);
        setRiskManagementExpanded(false);
        setAdminMenuOpen(false);
        setIoMenuOpen(false);
    };

    const toggleAdminMenu = () => {
        setAdminMenuOpen(!adminMenuOpen);
        // Reopening a rail that is still filtered from last time looks like items
        // went missing, so the query dies with the section.
        if (adminMenuOpen) setAdminQuery('');
        // Collapse all other sections
        setAssetsExpanded(false);
        setRequirementsExpanded(false);
        setRiskManagementExpanded(false);
        setVulnMenuOpen(false);
        setIoMenuOpen(false);
    };

    const toggleIoMenu = () => {
        setIoMenuOpen(!ioMenuOpen);
        // Collapse all other sections
        setAssetsExpanded(false);
        setRequirementsExpanded(false);
        setRiskManagementExpanded(false);
        setVulnMenuOpen(false);
        setAdminMenuOpen(false);
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
            setHasClassification(hasClassificationAccess(roles));
            setUserRoles(roles);
            setWorkgroupCount(user?.workgroupCount || 0);
            setAwsAccountCount(user?.awsAccountCount || 0);
            setDomainCount(user?.domainCount || 0);
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

    /*
     * Mark the link for the page we are on. Done by querying the rendered rail
     * rather than threading an `active` prop through ~40 anchors: the markup
     * stays untouched, and a link added later is picked up for free.
     *
     * `location` does not exist during Astro's server render, so this runs in an
     * effect — the server and client HTML agree, and the highlight appears on
     * mount (same reasoning as the roles read above).
     */
    useEffect(() => {
        const path = window.location.pathname.replace(/\/+$/, '') || '/';
        let best: Element | null = null;
        let bestLength = 0;

        document.querySelectorAll('#sidebar a[href]').forEach((link) => {
            const href = (link.getAttribute('href') || '').replace(/\/+$/, '') || '/';
            // Longest matching prefix wins, so /assets/123 highlights /assets
            // without /  also claiming every page.
            const matches = path === href || (href !== '/' && path.startsWith(href + '/'));
            if (matches && href.length > bestLength) {
                best = link;
                bestLength = href.length;
            }
        });

        best?.classList.add('sidebar-item-active');
        return () => best?.classList.remove('sidebar-item-active');
    }, []);

    /*
     * "Groups folded shut except the one you are in" — the fold's starting
     * position comes from the URL. Landing on an admin page also unrolls the
     * ADMIN section itself, so the rail shows where you are rather than making
     * you find it again.
     */
    useEffect(() => {
        const path = window.location.pathname;
        const groupKey = groupKeyForPath(ADMIN_NAV, path);
        setActiveAdminHref(activeHrefForPath(ADMIN_NAV, path));
        setOpenAdminGroup(groupKey);
        if (groupKey) setAdminMenuOpen(true);
    }, []);

    const visibleAdminGroups = filterAdminNav(ADMIN_NAV, adminQuery);

    return (
        <nav id="sidebar" className="bg-light border-end">
            <div className="sidebar-header p-3">
                <h3> </h3>
            </div>

            <ul className="list-unstyled components p-2">
                {/* ASSETS Section */}
                <li>
                    <div
                        onClick={toggleAssets}
                        className="sidebar-section-header-clickable d-flex align-items-center cursor-pointer"
                        style={{ cursor: 'pointer' }}
                    >
                        <i className="bi bi-server me-2"></i>
                        ASSETS
                        <i className={`bi ${assetsExpanded ? 'bi-chevron-down' : 'bi-chevron-right'} ms-auto`}></i>
                    </div>
                    {assetsExpanded && (
                        <ul className="list-unstyled ps-4">
                            <li>
                                <a href="/assets" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                    <i className="bi bi-server me-2"></i> Asset Register
                                </a>
                            </li>
                            <li>
                                <a href="/applications" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                    <i className="bi bi-window-stack me-2"></i> Application Register
                                </a>
                            </li>
                        </ul>
                    )}
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
                                <li>
                                    <a href="/requirements/download" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                        <i className="bi bi-download me-2"></i> Requirement download
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
                                {/* Feature 067: Releases navigation below Requirements */}
                                {canAccessReleases(userRoles) && (
                                    <li>
                                        <a href="/releases" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                            <i className="bi bi-tag me-2"></i> Releases
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
                                <li>
                                    <a href="/demands" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                        <i className="bi bi-clipboard-plus me-2"></i> Demand Management
                                    </a>
                                </li>
                                {hasClassification && (
                                    <li>
                                        <a href="/public-classification" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                            <i className="bi bi-funnel me-2"></i> Demand Classification
                                        </a>
                                    </li>
                                )}
                                {/*
                                  * Account Onboarding lives here, NOT in the ADMIN section below.
                                  * That section is gated on `isAdmin` alone, so a SECCHAMPION would
                                  * never see the link even though the page and its API allow them.
                                  * RISK MANAGEMENT is gated on hasRisk (ADMIN/RISK/SECCHAMPION), so
                                  * nesting the ADMIN-or-SECCHAMPION check inside it works — the same
                                  * shape `hasClassification` uses just above.
                                  */}
                                {canAccessAccountOnboarding(userRoles) && (
                                    <li>
                                        <a href="/admin/account-onboarding" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                            <i className="bi bi-envelope-paper me-2"></i> Account Onboarding
                                        </a>
                                    </li>
                                )}
                            </ul>
                        )}
                    </li>
                )}

                {/* VULNERABILITY MANAGEMENT Section - ADMIN or VULN role (Feature: 004-i-want-to) */}
                {hasVuln && (
                    <li>
                        <div
                            onClick={toggleVulnManagement}
                            className="sidebar-section-header-clickable d-flex align-items-center cursor-pointer"
                            style={{ cursor: 'pointer' }}
                        >
                            <i className="bi bi-shield-exclamation me-2"></i>
                            VULNERABILITY MANAGEMENT
                            <i className={`bi ${vulnMenuOpen ? 'bi-chevron-down' : 'bi-chevron-right'} ms-auto`}></i>
                        </div>
                        {vulnMenuOpen && (
                            <ul className="list-unstyled ps-4">
                                <li className="sidebar-subsection-header">Analyze</li>
                                <li>
                                    <a href="/analytics" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary"
                                        title="Overview, Lense and Heatmap in one place">
                                        <i className="bi bi-graph-up me-2"></i> Analytics
                                    </a>
                                </li>
                                {userRoles.includes('ADMIN') && (
                                <li>
                                    <a href="/account-finding-age" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary"
                                        title="AWS accounts ranked by the age of their oldest still-open finding">
                                        <i className="bi bi-hourglass-bottom me-2"></i> Account Aging
                                    </a>
                                </li>
                                )}

                                <li className="sidebar-subsection-header">Inventory</li>
                                {(isAdmin || userRoles.includes('SECCHAMPION')) && (
                                <li>
                                    <a href="/vulnerabilities/system" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                        <i className="bi bi-hdd me-2"></i> System vulns
                                    </a>
                                </li>
                                )}
                                {!isAdmin && (userRoles.includes('SECCHAMPION') || domainCount > 0) && (
                                <li>
                                    <a href="/vulnerabilities/domain" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                        <i className="bi bi-globe me-2"></i> Domain vulns
                                    </a>
                                </li>
                                )}
                                {!isAdmin && (userRoles.includes('SECCHAMPION') || awsAccountCount > 0) && (
                                <li>
                                    <a href="/account-vulns" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary"
                                        title="View vulnerabilities for your AWS accounts">
                                        <i className="bi bi-cloud me-2"></i> Account vulns
                                    </a>
                                </li>
                                )}
                                {!isAdmin && (userRoles.includes('SECCHAMPION') || workgroupCount > 0) && (
                                <li>
                                    <a href="/wg-vulns" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary"
                                        title="View vulnerabilities for your workgroups">
                                        <i className="bi bi-people-fill me-2"></i> WG vulns
                                    </a>
                                </li>
                                )}
                                <li>
                                    <a href="/products" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                        <i className="bi bi-box-seam me-2"></i> Vulnerable products
                                    </a>
                                </li>
                                <li>
                                    <a href="/installed-products" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                        <i className="bi bi-boxes me-2"></i> Installed products
                                    </a>
                                </li>
                                <li>
                                    <a href="/outdated-assets" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                                        <i className="bi bi-hourglass-split me-2"></i> Outdated assets
                                    </a>
                                </li>
                                {/* End-of-life software — scoped server-side to the caller's
                                    accessible systems (GET /api/eol/findings), so every role
                                    in this section may open it. */}
                                <li>
                                    <a href="/vulnerabilities/eol" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary"
                                        title="Operating systems and software on your systems that are end of life or approaching it">
                                        <i className="bi bi-calendar-x me-2"></i> End of life
                                    </a>
                                </li>
                                {(userRoles.includes('ADMIN') || userRoles.includes('VULN') || userRoles.includes('SECCHAMPION')) && (
                                <li>
                                    <a href="/github-repos" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary"
                                        title="GitHub repositories with their open high/critical Dependabot alerts">
                                        <i className="bi bi-github me-2"></i> GitHub
                                    </a>
                                </li>
                                )}

                                <li className="sidebar-subsection-header">Workflow</li>
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
                                {/* AWS Sharing:
                                    - ADMIN / VULN / SECCHAMPION → /admin/aws-account-sharing (full view, all rules)
                                    - Other users with AWS accounts → /aws-account-sharing (self-service) */}
                                {(isAdmin || userRoles.includes('VULN') || userRoles.includes('SECCHAMPION')) ? (
                                <li>
                                    <a href="/admin/aws-account-sharing" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary"
                                        title="View and manage AWS account sharing rules across all users">
                                        <i className="bi bi-share-fill me-2"></i> AWS Sharing
                                    </a>
                                </li>
                                ) : awsAccountCount > 0 ? (
                                <li>
                                    <a href="/aws-account-sharing" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary"
                                        title="Manage sharing of your AWS accounts with other users">
                                        <i className="bi bi-share-fill me-2"></i> AWS Sharing
                                    </a>
                                </li>
                                ) : null}
                            </ul>
                        )}
                    </li>
                )}

                {/* Self-service Workgroups link for non-admins. Admins reach it via the ADMIN
                    section below; this entry lets a regular user create their own workgroup
                    and edit/delete the workgroups they are a member of. */}
                {!isAdmin && (
                    <li>
                        <a href="/workgroups" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary"
                            title="Create a workgroup or manage workgroups you are a member of">
                            <i className="bi bi-diagram-2 me-2"></i> Workgroups
                            {workgroupCount > 0 && (
                                <span className="badge bg-secondary ms-auto">{workgroupCount}</span>
                            )}
                        </a>
                    </li>
                )}

                {/* I/O Section for non-admin SECCHAMPIONs. Admins reach Import/Export via
                    the I/O subsection of the ADMIN menu below. */}
                {!isAdmin && userRoles.includes('SECCHAMPION') && (
                    <li>
                        <div
                            onClick={toggleIoMenu}
                            className="sidebar-section-header-clickable d-flex align-items-center cursor-pointer"
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
                )}

                {/*
                  * ADMIN Section — filter and fold (design 1b).
                  *
                  * Twenty-plus entries used to unroll as one column taller than the
                  * viewport. Now a filter field sits at the top and the groups stay
                  * shut except the one holding the page you are on; opening a group
                  * closes the previous one, so the rail is never longer than a screen.
                  * The entries themselves live in adminNav.ts — markup cannot be
                  * searched, a list can.
                  */}
                {isAdmin && (
                    <li>
                        <div
                            onClick={toggleAdminMenu}
                            className="sidebar-section-header-clickable d-flex align-items-center cursor-pointer"
                            style={{ cursor: 'pointer' }}
                        >
                            <i className="bi bi-speedometer2 me-2"></i>
                            ADMIN
                            <i className={`bi ${adminMenuOpen ? 'bi-chevron-down' : 'bi-chevron-right'} ms-auto`}></i>
                        </div>
                        {adminMenuOpen && (
                            <div className="admin-nav">
                                <div className="admin-nav-filter">
                                    <i className="bi bi-search" aria-hidden="true"></i>
                                    <input
                                        type="search"
                                        value={adminQuery}
                                        onChange={(e) => setAdminQuery(e.target.value)}
                                        placeholder="Filter"
                                        aria-label="Filter admin menu"
                                    />
                                </div>
                                <ul className="list-unstyled mb-0">
                                    {visibleAdminGroups.length === 0 && (
                                        <li className="admin-nav-empty">Nothing matches “{adminQuery.trim()}”</li>
                                    )}
                                    {visibleAdminGroups.map((group) => {
                                        // A filter overrides the fold: what you searched for is
                                        // no use hidden behind a group you still have to open.
                                        const open = adminQuery.trim() !== ''
                                            || group.label === null
                                            || openAdminGroup === group.key;
                                        return (
                                            <React.Fragment key={group.key}>
                                                {group.label && (
                                                    <li>
                                                        <button
                                                            type="button"
                                                            className="admin-nav-group"
                                                            aria-expanded={open}
                                                            onClick={() => setOpenAdminGroup(
                                                                openAdminGroup === group.key ? null : group.key,
                                                            )}
                                                        >
                                                            <span>{group.label}</span>
                                                            {!open && (
                                                                <span className="admin-nav-count">{group.items.length}</span>
                                                            )}
                                                        </button>
                                                    </li>
                                                )}
                                                {open && group.items.map((item) => (
                                                    <li key={item.href}>
                                                        <a
                                                            href={item.href}
                                                            title={item.title}
                                                            className={
                                                                'admin-nav-item d-flex align-items-center p-2 text-decoration-none'
                                                                + (item.href === activeAdminHref ? ' sidebar-item-active' : '')
                                                            }
                                                        >
                                                            <i className={`bi ${item.icon} me-2`}></i> {item.label}
                                                        </a>
                                                    </li>
                                                ))}
                                            </React.Fragment>
                                        );
                                    })}
                                </ul>
                            </div>
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

// Editorial Design System styling
const styles = `
#sidebar {
    min-width: 250px;
    max-width: 250px;
    min-height: 100vh;
    transition: all 0.3s;
    background-color: var(--scand-bg-sidebar);
    /* No divider. The rail sits on the same canvas as the content; separation
       comes from the left margin of the content column, not from a rule. */
    border-right: 0 !important;
}

#sidebar.active {
    margin-left: -250px;
}

.hover-bg-secondary:hover {
    background-color: var(--scand-sidebar-hover-bg);
}

/* Consistent font size for all top-level sidebar items */
#sidebar .components > li > a {
    font-size: var(--scand-label-size);
    font-weight: var(--scand-label-weight);
    text-transform: uppercase;
    letter-spacing: var(--scand-label-tracking);
    color: var(--scand-text-secondary);
}

.sidebar-section-header {
    font-size: var(--scand-label-size);
    font-weight: var(--scand-label-weight);
    text-transform: uppercase;
    color: var(--scand-text-secondary);
    padding: 12px 8px 6px 8px;
    margin-top: 8px;
    letter-spacing: var(--scand-label-tracking);
    background-color: var(--scand-sidebar-section-bg);
}

.sidebar-section-header:first-child {
    margin-top: 0;
}

.sidebar-section-header-clickable {
    font-size: 0.75rem;
    font-weight: 600;
    text-transform: uppercase;
    color: var(--scand-text-secondary);
    padding: 12px 8px 8px 8px;
    margin-top: 8px;
    letter-spacing: var(--scand-label-tracking);
    background-color: var(--scand-sidebar-section-bg);
    transition: all var(--scand-transition-normal);
}

.sidebar-section-header-clickable:hover {
    background-color: var(--scand-sidebar-hover-bg);
    color: var(--scand-text-primary);
}

/*
 * The active item — a blue tint with a left accent bar. This is the one place
 * the rail uses colour, so it reads as position rather than decoration.
 */
#sidebar a.sidebar-item-active {
    background-color: var(--scand-sidebar-active-bg);
    box-shadow: inset 3px 0 0 0 var(--scand-sidebar-active-bar);
    color: var(--scand-text-primary) !important;
    font-weight: var(--scand-font-weight-semibold);
}

/*
 * Subsection label inside an expanded section — Vulnerability Management's
 * Analyze / Inventory / Workflow groups. The ADMIN rail used to share this rule
 * under a second class name; its groups are now buttons (.admin-nav-group), so
 * only the generic name is left.
 */
.sidebar-subsection-header {
    font-size: 0.7rem;
    font-weight: 500;
    text-transform: uppercase;
    color: var(--scand-text-secondary);
    padding: 8px 8px 4px 8px;
    margin-top: 12px;
    letter-spacing: var(--scand-label-tracking);
    border-bottom: 1px solid var(--scand-border);
}

/* ========================================================================
   ADMIN rail — filter and fold (design 1b)
   ======================================================================== */

.admin-nav {
    padding-bottom: 8px;
}

/*
 * The filter field. A tinted well rather than a bordered input: the rail sits on
 * the page canvas, and a boxed control here would read as a form.
 */
.admin-nav-filter {
    display: flex;
    align-items: center;
    gap: 8px;
    background-color: var(--scand-bg-hover);
    padding: 6px 10px;
    margin: 8px 8px 6px 8px;
}

.admin-nav-filter i {
    font-size: 0.8rem;
    color: var(--scand-text-secondary);
}

.admin-nav-filter input {
    width: 100%;
    border: 0;
    background: transparent;
    outline: none;
    font-family: var(--scand-font-family);
    font-size: 0.9rem;
    color: var(--scand-text-primary);
}

.admin-nav-filter input::placeholder {
    color: var(--scand-text-secondary);
}

/* A group heading is now a control, so it is a <button> — stripped back to type. */
.admin-nav-group {
    display: flex;
    align-items: center;
    width: 100%;
    background: none;
    border: 0;
    text-align: left;
    cursor: pointer;
    font-size: 0.7rem;
    font-weight: 500;
    text-transform: uppercase;
    letter-spacing: var(--scand-label-tracking);
    color: var(--scand-text-secondary);
    padding: 14px 8px 5px 8px;
    transition: color var(--scand-transition-fast);
}

.admin-nav-group:hover,
.admin-nav-group:focus-visible {
    color: var(--scand-text-primary);
}

/* How many entries are folded away. Letterspacing off — it is a number, not a label. */
.admin-nav-count {
    margin-left: auto;
    letter-spacing: 0;
    font-size: 0.7rem;
    color: var(--scand-text-secondary);
}

.admin-nav-item {
    color: var(--scand-text-secondary);
    padding-left: 1rem !important;
}

.admin-nav-item i {
    color: var(--scand-text-secondary);
}

.admin-nav-item:hover {
    background-color: var(--scand-sidebar-hover-bg);
    color: var(--scand-text-primary);
}

/* The one place the rail uses colour, matching the active-row rule above. */
.admin-nav-item.sidebar-item-active i {
    color: var(--scand-sidebar-active-bar);
}

.admin-nav-empty {
    font-size: 0.85rem;
    font-style: italic;
    color: var(--scand-text-secondary);
    padding: 6px 8px 6px 16px;
}
`;

// Inject styles into the head
if (typeof window !== 'undefined') {
    const styleSheet = document.createElement("style");
    styleSheet.type = "text/css";
    styleSheet.innerText = styles;
    document.head.appendChild(styleSheet);
}
