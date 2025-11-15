// filepath: /Users/flake/sources/misc/secman/src/frontend/src/components/AdminPage.tsx
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

const AdminPage = () => {
    const [isAdmin, setIsAdmin] = useState(false);
    const [isLoading, setIsLoading] = useState(true);

    useEffect(() => {
        let timeoutId: NodeJS.Timeout | null = null;
        let eventHandled = false;

        const checkAdminRole = () => {
            if (window.currentUser) {
                setIsAdmin(window.currentUser.roles?.includes('ADMIN') ?? false);
                setIsLoading(false);
            } else {
                // If currentUser isn't loaded yet, wait for the event
                const handleUserLoaded = () => {
                    eventHandled = true;
                    if (timeoutId) clearTimeout(timeoutId);
                    setIsAdmin(window.currentUser?.roles?.includes('ADMIN') ?? false);
                    setIsLoading(false);
                };

                window.addEventListener('userLoaded', handleUserLoaded, { once: true });

                // Set a timeout in case the event never fires (e.g., user not logged in)
                timeoutId = setTimeout(() => {
                    if (!eventHandled) {
                        window.removeEventListener('userLoaded', handleUserLoaded);
                        setIsLoading(false);
                        setIsAdmin(false); // Assume not admin if data never arrived
                    }
                }, 5000); // Wait 5 seconds (increased for IE/Edge)
            }
        };

        checkAdminRole();

        // Cleanup function
        return () => {
            if (timeoutId) clearTimeout(timeoutId);
        };
    }, []); // Empty dependency array

    if (isLoading) {
        return <div className="alert alert-info">Checking permissions...</div>;
    }

    if (!isAdmin) {
        return <div className="alert alert-danger">Access Denied: You do not have permission to view this page.</div>;
    }

    // Render admin content if user is admin
    return (
        <div>
            <h2>Admin Section</h2>
            <p>Welcome, Administrator!</p>
            
            <div className="row mt-4">
                <div className="col-md-4 mb-3">
                    <div className="card">
                        <div className="card-body">
                            <h5 className="card-title">
                                <i className="bi bi-people-fill me-2"></i>
                                User Management
                            </h5>
                            <p className="card-text">Manage user accounts, roles, and permissions.</p>
                            <a href="/admin/user-management" className="btn btn-primary">
                                Manage Users
                            </a>
                        </div>
                    </div>
                </div>
                
                <div className="col-md-4 mb-3">
                    <div className="card">
                        <div className="card-body">
                            <h5 className="card-title">
                                <i className="bi bi-envelope-gear me-2"></i>
                                Email Configuration
                            </h5>
                            <p className="card-text">Configure email settings and notifications.</p>
                            <a href="/admin/email-config" className="btn btn-primary">
                                Configure Email
                            </a>
                        </div>
                    </div>
                </div>
                
                <div className="col-md-4 mb-3">
                    <div className="card">
                        <div className="card-body">
                            <h5 className="card-title">
                                <i className="bi bi-translate me-2"></i>
                                Translation Configuration
                            </h5>
                            <p className="card-text">Configure OpenRouter API for document translation.</p>
                            <a href="/admin/translation-config" className="btn btn-primary">
                                Configure Translation
                            </a>
                        </div>
                    </div>
                </div>

                <div className="col-md-4 mb-3">
                    <div className="card">
                        <div className="card-body">
                            <h5 className="card-title">
                                <i className="bi bi-shield-lock me-2"></i>
                                Identity Providers
                            </h5>
                            <p className="card-text">Configure external identity providers for SSO authentication.</p>
                            <a href="/admin/identity-providers" className="btn btn-primary">
                                Manage Identity Providers
                            </a>
                        </div>
                    </div>
                </div>
                
                <div className="col-md-4 mb-3">
                    <div className="card">
                        <div className="card-body">
                            <h5 className="card-title">
                                <i className="bi bi-list-task me-2"></i>
                                Requirements Management
                            </h5>
                            <p className="card-text">Manage system requirements and perform bulk operations.</p>
                            <a href="/admin/requirements" className="btn btn-primary">
                                Manage Requirements
                            </a>
                        </div>
                    </div>
                </div>
                
                <div className="col-md-4 mb-3">
                    <div className="card">
                        <div className="card-body">
                            <h5 className="card-title">
                                <i className="bi bi-funnel-fill me-2"></i>
                                Classification Rules
                            </h5>
                            <p className="card-text">Manage demand classification rules and security assessment logic.</p>
                            <a href="/admin/classification-rules" className="btn btn-primary">
                                Manage Classification Rules
                            </a>
                        </div>
                    </div>
                </div>
                
                <div className="col-md-4 mb-3">
                    <div className="card">
                        <div className="card-body">
                            <h5 className="card-title">
                                <i className="bi bi-key-fill me-2"></i>
                                MCP API Keys
                            </h5>
                            <p className="card-text">Manage API keys for Model Context Protocol integration with AI assistants.</p>
                            <a href="/admin/mcp-api-keys" className="btn btn-primary">
                                Manage API Keys
                            </a>
                        </div>
                    </div>
                </div>

                <div className="col-md-4 mb-3">
                    <div className="card">
                        <div className="card-body">
                            <h5 className="card-title">
                                <i className="bi bi-shield-lock me-2"></i>
                                CrowdStrike Falcon
                            </h5>
                            <p className="card-text">Configure CrowdStrike Falcon API credentials for vulnerability scanning.</p>
                            <a href="/admin/falcon-config" className="btn btn-primary">
                                Configure Falcon
                            </a>
                        </div>
                    </div>
                </div>

                <div className="col-md-4 mb-3">
                    <div className="card">
                        <div className="card-body">
                            <h5 className="card-title">
                                <i className="bi bi-shield-exclamation me-2"></i>
                                Vulnerability Settings
                            </h5>
                            <p className="card-text">Configure vulnerability overdue thresholds and manage exception policies.</p>
                            <a href="/admin/vulnerability-config" className="btn btn-primary">
                                <i className="bi bi-gear me-2"></i>
                                Configure Settings
                            </a>
                        </div>
                    </div>
                </div>

                <div className="col-md-4 mb-3">
                    <div className="card">
                        <div className="card-body">
                            <h5 className="card-title">
                                <i className="bi bi-diagram-3-fill me-2"></i>
                                User Mappings
                            </h5>
                            <p className="card-text">Upload and manage user-to-AWS-account-to-domain mappings for role-based access control.</p>
                            <a href="/admin/user-mappings" className="btn btn-primary">
                                Manage Mappings
                            </a>
                        </div>
                    </div>
                </div>

                <div className="col-md-4 mb-3">
                    <div className="card">
                        <div className="card-body">
                            <h5 className="card-title">
                                <i className="bi bi-bell-fill me-2"></i>
                                Notification Settings
                            </h5>
                            <p className="card-text">Configure email notifications for new user registrations.</p>
                            <a href="/admin/notification-settings" className="btn btn-primary">
                                Configure Notifications
                            </a>
                        </div>
                    </div>
                </div>

                <div className="col-md-4 mb-3">
                    <div className="card">
                        <div className="card-body">
                            <h5 className="card-title">
                                <i className="bi bi-exclamation-triangle-fill text-warning me-2"></i>
                                Maintenance Banners
                            </h5>
                            <p className="card-text">Create and schedule maintenance notifications displayed on the login page.</p>
                            <a href="/admin/maintenance-banners" className="btn btn-primary">
                                Manage Banners
                            </a>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
};

export default AdminPage;
