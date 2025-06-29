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

const TopBar = () => {
    const [user, setUser] = useState<User | null>(null);
    const [isLoading, setIsLoading] = useState(true); // Start as loading

    useEffect(() => {
        let isMounted = true; // Track mount status

        const updateUserStatus = () => {
            console.log("TopBar: Updating user status. Current window.currentUser:", window.currentUser);
            if (isMounted) {
                setUser(window.currentUser || null);
                setIsLoading(false); // Finished loading/checking
            }
        };

        // Check immediately if user data is already available
        if (window.currentUser !== undefined) {
             console.log("TopBar: Initial check, currentUser exists:", window.currentUser);
             updateUserStatus();
        } else {
             console.log("TopBar: Initial check, currentUser undefined, waiting for event.");
             // Set loading true explicitly if we need to wait
             setIsLoading(true);
        }


        // Listen for the custom event dispatched when user data is loaded/updated
        window.addEventListener('userLoaded', updateUserStatus);
        console.log("TopBar: Added userLoaded listener.");

        // Also listen for logout event if we implement one, or re-check on visibility change
        // For simplicity, we rely on page reload after logout for now.

        // Cleanup listener on component unmount
        return () => {
            isMounted = false;
            window.removeEventListener('userLoaded', updateUserStatus);
            console.log("TopBar: Removed userLoaded listener.");
        };
    }, []); // Empty dependency array ensures this runs once on mount and cleans up on unmount

    return (
        <div className="ms-auto d-flex align-items-center"> {/* Use ms-auto to push to the right */}
            {isLoading ? (
                <span className="navbar-text me-3">Loading...</span>
            ) : user ? (
                <span className="navbar-text me-3">
                    Welcome, <i className="bi bi-person-fill me-1"></i>
                    <strong>{user.username}</strong>
                    <small className="ms-1">({user.roles?.join(', ') || 'No Roles'})</small>
                </span>
                // Optionally add a dropdown here with logout, profile, etc.
            ) : (
                <a href="/login" className="btn btn-outline-primary btn-sm"> {/* Added btn-sm */}
                   <i className="bi bi-box-arrow-in-right me-1"></i> Sign In
                </a>
            )}
        </div>
    );
};

export default TopBar;
