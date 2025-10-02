import React, { useState, useEffect } from 'react';
import type { FormEvent } from 'react';
import { authenticatedGet, authenticatedPost, authenticatedPut, authenticatedDelete } from '../utils/auth';

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

// Available roles - could be fetched from backend in a real app
const AVAILABLE_ROLES = ['USER', 'ADMIN'];

const UserManagement = () => {
    const [isAdmin, setIsAdmin] = useState(false);
    const [isLoading, setIsLoading] = useState(true); // Start loading
    const [users, setUsers] = useState<User[]>([]);
    const [error, setError] = useState<string | null>(null);

    // State for Add User Modal
    const [showAddUserModal, setShowAddUserModal] = useState(false);
    const [newUser, setNewUser] = useState({
        username: '',
        email: '',
        password: '',
        roles: ['USER'] as string[], // Default to USER
    });
    const [addUserError, setAddUserError] = useState<string | null>(null);
    const [isSubmittingUser, setIsSubmittingUser] = useState(false);
    const [deletingUserId, setDeletingUserId] = useState<number | null>(null);

    // State for Edit User Modal
    const [showEditUserModal, setShowEditUserModal] = useState(false);
    const [editingUser, setEditingUser] = useState<User | null>(null);
    const [editUser, setEditUser] = useState({
        username: '',
        email: '',
        password: '', // Optional - only update if provided
        roles: [] as string[],
    });
    const [editUserError, setEditUserError] = useState<string | null>(null);
    const [isSubmittingEdit, setIsSubmittingEdit] = useState(false);


    useEffect(() => {
        let isMounted = true; // Track if component is mounted
        const listener = async () => {
            console.log("userLoaded event received in UserManagement");
            await checkAdminAndFetchUsers();
        };

        const checkAdminAndFetchUsers = async () => {
            console.log("Checking admin status and fetching users...");
            let userIsAdmin = false;
            if (window.currentUser) {
                userIsAdmin = window.currentUser.roles?.includes('ADMIN') ?? false;
                if (isMounted) {
                    setIsAdmin(userIsAdmin);
                    setIsLoading(false); // Permission check done
                }

                // If admin, fetch users
                if (userIsAdmin) {
                    await fetchUsers(isMounted);
                } else {
                     // Not an admin, ensure loading is false
                     if (isMounted) setIsLoading(false);
                }
            } else {
                // User data not yet available, listener should handle it.
                console.log("User data not yet available, waiting for event...");
            }
        };

        // Check immediately if user data is already loaded
        if (window.currentUser) {
            checkAdminAndFetchUsers();
        } else {
            // If not loaded, add the event listener
            console.log("Adding userLoaded listener for UserManagement");
            window.addEventListener('userLoaded', listener);

            // Set a timeout as a fallback
            const timeoutId = setTimeout(() => {
                if (isLoading && isMounted) { // If still loading after timeout
                    console.log("Timeout waiting for user data in UserManagement");
                    setIsLoading(false);
                    setIsAdmin(false); // Assume not admin if data never arrived
                    if (!window.currentUser) { // Set error only if user data is truly missing
                         setError("Could not verify user permissions.");
                    }
                }
            }, 3000); // Wait 3 seconds

            // Cleanup for timeout
            return () => {
                clearTimeout(timeoutId);
                window.removeEventListener('userLoaded', listener);
                isMounted = false;
                console.log("UserManagement component unmounting - listener removed");
            };
        }
        
        // Cleanup for case where user data was immediately available
        return () => {
             isMounted = false;
             console.log("UserManagement component unmounting");
        };

    }, []); // Run only once on mount

    const fetchUsers = async (isMounted: boolean) => {
        try {
            console.log("Fetching users...");
            const response = await authenticatedGet('/api/users');
            if (response.ok && isMounted) {
                const data: User[] = await response.json();
                setUsers(data);
                setError(null);
            } else if (isMounted && !response.ok) {
                setError(`Failed to fetch users: ${response.status}`);
            }
        } catch (err: any) {
            console.error("Error fetching users:", err);
            if (isMounted) {
                // Handle authentication-specific errors
                if (err.status === 403) {
                    setError('Access Denied: You do not have permission to fetch users.');
                } else {
                    setError(err.message || "Could not load user data.");
                }
                setUsers([]);
            }
        }
    };

    const handleNewUserChange = (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
        const { name, value } = e.target;
        setNewUser(prev => ({ ...prev, [name]: value }));
    };

    const handleNewUserRolesChange = (e: React.ChangeEvent<HTMLInputElement>) => {
        const { value, checked } = e.target;
        setNewUser(prev => {
            const newRoles = checked
                ? [...prev.roles, value]
                : prev.roles.filter(role => role !== value);
            return { ...prev, roles: newRoles };
        });
    };

    const handleAddUserSubmit = async (e: FormEvent) => {
        e.preventDefault();
        setAddUserError(null);
        setIsSubmittingUser(true);

        if (!newUser.username || !newUser.email || !newUser.password) {
            setAddUserError("Username, email, and password are required.");
            setIsSubmittingUser(false);
            return;
        }
        if (newUser.roles.length === 0) {
            setAddUserError("At least one role must be selected.");
            setIsSubmittingUser(false);
            return;
        }

        try {
            const response = await authenticatedPost('/api/users', newUser);
            if (response.ok) {
                const data = await response.json();
                setUsers(prevUsers => [...prevUsers, data]);
                setShowAddUserModal(false);
                setNewUser({ username: '', email: '', password: '', roles: ['USER'] }); // Reset form
            } else {
                throw new Error(`Failed to create user: ${response.status}`);
            }
        } catch (err) {
            console.error('Add user request failed:', err);
            setAddUserError('An error occurred while creating the user. Please try again.');
        } finally {
            setIsSubmittingUser(false);
        }
    };

    const handleDeleteUser = async (userId: number, username: string) => {
        if (!confirm(`Are you sure you want to delete user "${username}"?`)) {
            return;
        }

        setDeletingUserId(userId);
        try {
            await authenticatedDelete(`/api/users/${userId}`);
            setUsers(prevUsers => prevUsers.filter(user => user.id !== userId));
        } catch (err) {
            console.error('Delete user request failed:', err);
            alert('An error occurred while deleting the user. Please try again.');
        } finally {
            setDeletingUserId(null);
        }
    };

    const handleEditClick = (user: User) => {
        setEditingUser(user);
        setEditUser({
            username: user.username,
            email: user.email,
            password: '', // Leave blank - only update if user enters a new password
            roles: [...user.roles],
        });
        setEditUserError(null);
        setShowEditUserModal(true);
    };

    const handleEditUserChange = (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
        const { name, value } = e.target;
        setEditUser(prev => ({ ...prev, [name]: value }));
    };

    const handleEditUserRolesChange = (e: React.ChangeEvent<HTMLInputElement>) => {
        const { value, checked } = e.target;
        setEditUser(prev => {
            const newRoles = checked
                ? [...prev.roles, value]
                : prev.roles.filter(role => role !== value);
            return { ...prev, roles: newRoles };
        });
    };

    const handleEditUserSubmit = async (e: FormEvent) => {
        e.preventDefault();
        setEditUserError(null);
        setIsSubmittingEdit(true);

        if (!editingUser) {
            setEditUserError("No user selected for editing.");
            setIsSubmittingEdit(false);
            return;
        }

        if (!editUser.username || !editUser.email) {
            setEditUserError("Username and email are required.");
            setIsSubmittingEdit(false);
            return;
        }
        if (editUser.roles.length === 0) {
            setEditUserError("At least one role must be selected.");
            setIsSubmittingEdit(false);
            return;
        }

        try {
            // Build update payload - only include password if it was provided
            const updatePayload: any = {
                username: editUser.username,
                email: editUser.email,
                roles: editUser.roles,
            };

            // Only include password if user entered one
            if (editUser.password && editUser.password.trim() !== '') {
                updatePayload.password = editUser.password;
            }

            const response = await authenticatedPut(`/api/users/${editingUser.id}`, updatePayload);
            if (response.ok) {
                const data = await response.json();
                setUsers(prevUsers => prevUsers.map(u => u.id === editingUser.id ? data : u));
                setShowEditUserModal(false);
                setEditingUser(null);
                setEditUser({ username: '', email: '', password: '', roles: [] }); // Reset form
            } else {
                const errorData = await response.json().catch(() => ({ error: 'Unknown error' }));
                throw new Error(errorData.error || `Failed to update user: ${response.status}`);
            }
        } catch (err: any) {
            console.error('Edit user request failed:', err);
            setEditUserError(err.message || 'An error occurred while updating the user. Please try again.');
        } finally {
            setIsSubmittingEdit(false);
        }
    };


    // --- Render Logic ---
    if (isLoading) {
        return <div className="alert alert-info">Checking permissions and loading user data...</div>;
    }

    if (!isAdmin) {
        // Show specific error if API call failed, otherwise generic access denied
        const message = error && error.includes("Access Denied") 
                        ? error 
                        : "Access Denied: You do not have permission to manage users.";
        // Also show error if timeout occurred without user data
        const finalMessage = error && !isAdmin ? error : message;
        return <div className="alert alert-danger">{finalMessage}</div>;
    }

    // Render user management content if user is admin
    return (
        <div>
            <h2>User Management</h2>
            <p>Manage application users.</p>

            {/* Display non-permission errors here */}
            {error && !error.includes("Access Denied") && (
                <div className="alert alert-warning" role="alert">
                    Error loading data: {error}
                </div>
            )}

            <button className="btn btn-success mb-3" onClick={() => setShowAddUserModal(true)}>
                 <i className="bi bi-plus-circle me-2"></i> Add User
            </button>

            {/* Add User Modal */}
            {showAddUserModal && (
                <div className="modal fade show d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
                    <div className="modal-dialog modal-dialog-centered">
                        <div className="modal-content">
                            <form onSubmit={handleAddUserSubmit}>
                                <div className="modal-header">
                                    <h5 className="modal-title">Add New User</h5>
                                    <button type="button" className="btn-close" onClick={() => setShowAddUserModal(false)} aria-label="Close" disabled={isSubmittingUser}></button>
                                </div>
                                <div className="modal-body">
                                    {addUserError && <div className="alert alert-danger">{addUserError}</div>}
                                    <div className="mb-3">
                                        <label htmlFor="username" className="form-label">Username</label>
                                        <input type="text" className="form-control" id="username" name="username" value={newUser.username} onChange={handleNewUserChange} required disabled={isSubmittingUser} />
                                    </div>
                                    <div className="mb-3">
                                        <label htmlFor="email" className="form-label">Email</label>
                                        <input type="email" className="form-control" id="email" name="email" value={newUser.email} onChange={handleNewUserChange} required disabled={isSubmittingUser} />
                                    </div>
                                    <div className="mb-3">
                                        <label htmlFor="password" className="form-label">Password</label>
                                        <input type="password" className="form-control" id="password" name="password" value={newUser.password} onChange={handleNewUserChange} required disabled={isSubmittingUser} />
                                    </div>
                                    <div className="mb-3">
                                        <label className="form-label">Roles</label>
                                        <div>
                                            {AVAILABLE_ROLES.map(role => (
                                                <div className="form-check form-check-inline" key={role}>
                                                    <input
                                                        className="form-check-input"
                                                        type="checkbox"
                                                        id={`role-${role}`}
                                                        value={role}
                                                        checked={newUser.roles.includes(role)}
                                                        onChange={handleNewUserRolesChange}
                                                        disabled={isSubmittingUser}
                                                    />
                                                    <label className="form-check-label" htmlFor={`role-${role}`}>{role}</label>
                                                </div>
                                            ))}
                                        </div>
                                    </div>
                                </div>
                                <div className="modal-footer">
                                    <button type="button" className="btn btn-secondary" onClick={() => setShowAddUserModal(false)} disabled={isSubmittingUser}>Close</button>
                                    <button type="submit" className="btn btn-primary" disabled={isSubmittingUser}>
                                        {isSubmittingUser ? 'Adding...' : 'Add User'}
                                    </button>
                                </div>
                            </form>
                        </div>
                    </div>
                </div>
            )}

            {/* Edit User Modal */}
            {showEditUserModal && editingUser && (
                <div className="modal fade show d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
                    <div className="modal-dialog modal-dialog-centered">
                        <div className="modal-content">
                            <form onSubmit={handleEditUserSubmit}>
                                <div className="modal-header">
                                    <h5 className="modal-title">Edit User: {editingUser.username}</h5>
                                    <button type="button" className="btn-close" onClick={() => setShowEditUserModal(false)} aria-label="Close" disabled={isSubmittingEdit}></button>
                                </div>
                                <div className="modal-body">
                                    {editUserError && <div className="alert alert-danger">{editUserError}</div>}
                                    <div className="mb-3">
                                        <label htmlFor="edit-username" className="form-label">Username</label>
                                        <input type="text" className="form-control" id="edit-username" name="username" value={editUser.username} onChange={handleEditUserChange} required disabled={isSubmittingEdit} />
                                    </div>
                                    <div className="mb-3">
                                        <label htmlFor="edit-email" className="form-label">Email</label>
                                        <input type="email" className="form-control" id="edit-email" name="email" value={editUser.email} onChange={handleEditUserChange} required disabled={isSubmittingEdit} />
                                    </div>
                                    <div className="mb-3">
                                        <label htmlFor="edit-password" className="form-label">Password</label>
                                        <input type="password" className="form-control" id="edit-password" name="password" value={editUser.password} onChange={handleEditUserChange} placeholder="Leave blank to keep current password" disabled={isSubmittingEdit} />
                                        <small className="form-text text-muted">Leave blank to keep the current password</small>
                                    </div>
                                    <div className="mb-3">
                                        <label className="form-label">Roles</label>
                                        <div>
                                            {AVAILABLE_ROLES.map(role => (
                                                <div className="form-check form-check-inline" key={role}>
                                                    <input
                                                        className="form-check-input"
                                                        type="checkbox"
                                                        id={`edit-role-${role}`}
                                                        value={role}
                                                        checked={editUser.roles.includes(role)}
                                                        onChange={handleEditUserRolesChange}
                                                        disabled={isSubmittingEdit}
                                                    />
                                                    <label className="form-check-label" htmlFor={`edit-role-${role}`}>{role}</label>
                                                </div>
                                            ))}
                                        </div>
                                    </div>
                                </div>
                                <div className="modal-footer">
                                    <button type="button" className="btn btn-secondary" onClick={() => setShowEditUserModal(false)} disabled={isSubmittingEdit}>Close</button>
                                    <button type="submit" className="btn btn-primary" disabled={isSubmittingEdit}>
                                        {isSubmittingEdit ? 'Updating...' : 'Update User'}
                                    </button>
                                </div>
                            </form>
                        </div>
                    </div>
                </div>
            )}

            <table className="table table-striped table-hover">
                <thead>
                    <tr>
                        <th>ID</th>
                        <th>Username</th>
                        <th>Email</th>
                        <th>Roles</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    {users.length > 0 ? (
                        users.map(user => (
                            <tr key={user.id}>
                                <td>{user.id}</td>
                                <td>{user.username}</td>
                                <td>{user.email}</td>
                                <td>{user.roles?.join(', ') || 'N/A'}</td>
                                <td>
                                    <button
                                        className="btn btn-sm btn-primary me-1"
                                        title="Edit"
                                        onClick={() => handleEditClick(user)}
                                    >
                                        <i className="bi bi-pencil-fill"></i>
                                    </button>
                                    <button
                                        className="btn btn-sm btn-danger"
                                        title="Delete"
                                        onClick={() => handleDeleteUser(user.id, user.username)}
                                        disabled={deletingUserId === user.id}
                                    >
                                         {deletingUserId === user.id ? (
                                             <i className="bi bi-hourglass-split"></i>
                                         ) : (
                                             <i className="bi bi-trash-fill"></i>
                                         )}
                                    </button>
                                </td>
                            </tr>
                        ))
                    ) : (
                        <tr>
                            <td colSpan={5} className="text-center">
                                {/* Show different message based on whether there was an error or just no users */}
                                {error && !error.includes("Access Denied") ? "Could not load users." : "No users found."}
                            </td>
                        </tr>
                    )}
                </tbody>
            </table>
        </div>
    );
};

export default UserManagement;
