import React, { useState, useEffect } from 'react';

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
        dispatchEvent(event: Event): boolean;
    }
}

const Header = () => {
    const [user, setUser] = useState<User | null | undefined>(undefined); // undefined initially, null if not logged in, User if logged in

    const handleLogout = async () => {
        try {
            const token = localStorage.getItem('authToken');
            const response = await fetch('/api/auth/logout', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    ...(token && { 'Authorization': `Bearer ${token}` }),
                },
            });
            if (response.ok) {
                // Clear authentication data
                localStorage.removeItem('authToken');
                localStorage.removeItem('user');

                // Also clear the authToken cookie
                document.cookie = 'authToken=; path=/; max-age=0; SameSite=Strict';

                window.currentUser = null;
                setUser(null);
                window.dispatchEvent(new CustomEvent('userLoggedOut'));
                window.location.href = '/login';
            } else {
                const errorData = await response.json().catch(() => ({ message: 'Logout failed. Please try again.' }));
                console.error('Logout failed:', response.status, errorData);
                alert(errorData.message || 'Logout failed. Please try again.');
            }
        } catch (error) {
            console.error('Logout request failed:', error);
            alert('An error occurred during logout.');
        }
    };

    useEffect(() => {
        const updateUserState = () => {
            setUser(window.currentUser);
        };

        // Initial check
        if (typeof window !== 'undefined') {
            updateUserState();

            // Listen for userLoaded event (e.g., after login or initial auth check)
            window.addEventListener('userLoaded', updateUserState);
            // Listen for userLoggedOut event (e.g., after explicit logout)
            window.addEventListener('userLoggedOut', updateUserState);

            return () => {
                window.removeEventListener('userLoaded', updateUserState);
                window.removeEventListener('userLoggedOut', updateUserState);
            };
        }
    }, []);

    if (user === undefined) {
        // Still loading or initial state before first check
        return (
            <nav className="navbar navbar-expand-lg navbar-light bg-light fixed-top" style={{minHeight: '3.75rem'}}>
                <div className="container-fluid">
                    <a className="navbar-brand" href="/" style={{marginLeft: '1cm'}}>
                    <img src="/SecManLogo.png" alt="SecMan" height="64" style={{backgroundColor: '#f8f9fa', padding: '5px', borderRadius: '4px'}} />
                </a>
                    <div className="ms-auto">
                        <span className="navbar-text">Loading...</span>
                    </div>
                </div>
            </nav>
        );
    }

    return (
        <nav className="navbar navbar-expand-lg navbar-light bg-light fixed-top" style={{minHeight: '3.75rem'}}>
            <div className="container-fluid">
                <a className="navbar-brand" href="/" style={{marginLeft: '1cm'}}>
                    <img src="/SecManLogo.png" alt="SecMan" height="64" style={{backgroundColor: '#f8f9fa', padding: '5px', borderRadius: '4px'}} />
                </a>
                <button className="navbar-toggler" type="button" data-bs-toggle="collapse" data-bs-target="#navbarNavDropdown" aria-controls="navbarNavDropdown" aria-expanded="false" aria-label="Toggle navigation">
                    <span className="navbar-toggler-icon"></span>
                </button>
                <div className="collapse navbar-collapse" id="navbarNavDropdown">
                    <ul className="navbar-nav ms-auto">
                        {user ? (
                            <li className="nav-item dropdown">
                                <a className="nav-link dropdown-toggle" href="#" id="navbarDropdownMenuLink" role="button" data-bs-toggle="dropdown" aria-expanded="false">
                                    <i className="bi bi-person-circle me-1"></i> {user.username}
                                </a>
                                <ul className="dropdown-menu dropdown-menu-end" aria-labelledby="navbarDropdownMenuLink">
                                    <li><a className="dropdown-item" href="#">Profile</a></li> {/* Placeholder */}
                                    <li><a className="dropdown-item" href="#">Settings</a></li> {/* Placeholder */}
                                    <li><hr className="dropdown-divider" /></li>
                                    <li>
                                        <button className="dropdown-item" type="button" onClick={handleLogout}>
                                            <i className="bi bi-box-arrow-right me-2"></i>Logout
                                        </button>
                                    </li>
                                </ul>
                            </li>
                        ) : (
                            <li className="nav-item">
                                <a className="nav-link" href="/login">
                                    <i className="bi bi-box-arrow-in-right me-2"></i>Login
                                </a>
                            </li>
                        )}
                    </ul>
                </div>
            </div>
        </nav>
    );
};

export default Header;
