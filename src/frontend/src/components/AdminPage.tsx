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
        const checkAdminRole = () => {
            if (window.currentUser) {
                setIsAdmin(window.currentUser.roles?.includes('ADMIN') ?? false);
                setIsLoading(false);
            } else {
                // If currentUser isn't loaded yet, wait for the event
                window.addEventListener('userLoaded', () => {
                     setIsAdmin(window.currentUser?.roles?.includes('ADMIN') ?? false);
                     setIsLoading(false);
                }, { once: true }); // Only listen once

                // Set a timeout in case the event never fires (e.g., user not logged in)
                 setTimeout(() => {
                    if (isLoading) { // If still loading after timeout
                        setIsLoading(false);
                        setIsAdmin(false); // Assume not admin if data never arrived
                    }
                 }, 2000); // Wait 2 seconds
            }
        };

        checkAdminRole();

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
                            <a href="/admin/user-management" className="btn btn-outline-primary">
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
                            <a href="/admin/email-config" className="btn btn-outline-primary">
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
                            <a href="/admin/translation-config" className="btn btn-outline-primary">
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
                            <a href="/admin/identity-providers" className="btn btn-outline-primary">
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
                            <a href="/admin/requirements" className="btn btn-outline-primary">
                                Manage Requirements
                            </a>
                        </div>
                    </div>
                </div>
                
                <div className="col-md-4 mb-3">
                    <div className="card">
                        <div className="card-body">
                            <h5 className="card-title">
                                <i className="bi bi-gear-fill me-2"></i>
                                System Settings
                            </h5>
                            <p className="card-text">Configure system-wide settings and preferences.</p>
                            <button className="btn btn-secondary" disabled>
                                Coming Soon
                            </button>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
};

export default AdminPage;
