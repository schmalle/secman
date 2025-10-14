import React, { useState, useEffect } from 'react';
import type { FormEvent } from 'react';
import { authenticatedGet, authenticatedPost, authenticatedPut, authenticatedDelete } from '../utils/auth';
import { getUserMappings, createMapping, updateMapping, deleteMapping, type UserMapping, type CreateMappingRequest, type UpdateMappingRequest } from '../api/userMappings';

// Define an interface for the user data expected from the backend
interface User {
    id: number;
    username: string;
    email: string;
    roles: string[];
    workgroups?: WorkgroupSummary[];
}

interface WorkgroupSummary {
    id: number;
    name: string;
}

interface Workgroup {
    id: number;
    name: string;
    description?: string;
}

// Define a type for the global variable
declare global {
    interface Window {
        currentUser?: User | null;
    }
}

// Available roles - could be fetched from backend in a real app
const AVAILABLE_ROLES = ['USER', 'ADMIN', 'VULN'];

const UserManagement = () => {
    const [isAdmin, setIsAdmin] = useState(false);
    const [isLoading, setIsLoading] = useState(true); // Start loading
    const [users, setUsers] = useState<User[]>([]);
    const [workgroups, setWorkgroups] = useState<Workgroup[]>([]);
    const [error, setError] = useState<string | null>(null);

    // State for Add User Modal
    const [showAddUserModal, setShowAddUserModal] = useState(false);
    const [newUser, setNewUser] = useState({
        username: '',
        email: '',
        password: '',
        roles: ['USER'] as string[], // Default to USER
        workgroupIds: [] as number[],
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
        workgroupIds: [] as number[],
    });
    const [editUserError, setEditUserError] = useState<string | null>(null);
    const [isSubmittingEdit, setIsSubmittingEdit] = useState(false);

    // State for User Mappings (Feature 017)
    const [mappings, setMappings] = useState<UserMapping[]>([]);
    const [mappingsError, setMappingsError] = useState<string | null>(null);
    const [mappingsSuccess, setMappingsSuccess] = useState<string | null>(null);
    const [isLoadingMappings, setIsLoadingMappings] = useState(false);
    const [isAddingMapping, setIsAddingMapping] = useState(false);
    const [newMapping, setNewMapping] = useState<CreateMappingRequest>({
        awsAccountId: '',
        domain: ''
    });
    const [editingMappingId, setEditingMappingId] = useState<number | null>(null);
    const [editMappingData, setEditMappingData] = useState<UpdateMappingRequest>({
        awsAccountId: '',
        domain: ''
    });


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

                // If admin, fetch users and workgroups
                if (userIsAdmin) {
                    await Promise.all([fetchUsers(isMounted), fetchWorkgroups()]);
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
            const response = await authenticatedGet('/api/users?includeWorkgroups=true');
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

    const fetchWorkgroups = async () => {
        try {
            console.log("Fetching workgroups...");
            const response = await authenticatedGet('/api/workgroups');
            if (response.ok) {
                const data: Workgroup[] = await response.json();
                setWorkgroups(data);
            }
        } catch (err: any) {
            console.error("Error fetching workgroups:", err);
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

    const handleNewUserWorkgroupsChange = (e: React.ChangeEvent<HTMLInputElement>) => {
        const workgroupId = parseInt(e.target.value);
        const { checked } = e.target;
        setNewUser(prev => {
            const newWorkgroupIds = checked
                ? [...prev.workgroupIds, workgroupId]
                : prev.workgroupIds.filter(id => id !== workgroupId);
            return { ...prev, workgroupIds: newWorkgroupIds };
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
                setNewUser({ username: '', email: '', password: '', roles: ['USER'], workgroupIds: [] }); // Reset form
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
            workgroupIds: user.workgroups?.map(wg => wg.id) || [],
        });
        setEditUserError(null);
        setShowEditUserModal(true);
        // Load mappings for this user (Feature 017)
        loadUserMappings(user.id);
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

    const handleEditUserWorkgroupsChange = (e: React.ChangeEvent<HTMLInputElement>) => {
        const workgroupId = parseInt(e.target.value);
        const { checked } = e.target;
        setEditUser(prev => {
            const newWorkgroupIds = checked
                ? [...prev.workgroupIds, workgroupId]
                : prev.workgroupIds.filter(id => id !== workgroupId);
            return { ...prev, workgroupIds: newWorkgroupIds };
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
                workgroupIds: editUser.workgroupIds,
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
                setEditUser({ username: '', email: '', password: '', roles: [], workgroupIds: [] }); // Reset form
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

    // --- User Mapping Management Functions (Feature 017) ---
    
    const loadUserMappings = async (userId: number) => {
        setIsLoadingMappings(true);
        try {
            const data = await getUserMappings(userId);
            setMappings(data);
            setMappingsError(null);
        } catch (err: any) {
            console.error('Failed to load mappings:', err);
            setMappingsError(err.message || 'Failed to load mappings');
        } finally {
            setIsLoadingMappings(false);
        }
    };

    const handleAddMapping = async () => {
        if (!editingUser) return;
        
        // Validate at least one field
        const hasAwsId = newMapping.awsAccountId && newMapping.awsAccountId.trim() !== '';
        const hasDomain = newMapping.domain && newMapping.domain.trim() !== '';
        
        if (!hasAwsId && !hasDomain) {
            setMappingsError('At least one of AWS Account ID or Domain must be provided');
            return;
        }

        setIsLoadingMappings(true);
        try {
            const mappingData: CreateMappingRequest = {
                awsAccountId: hasAwsId ? newMapping.awsAccountId : null,
                domain: hasDomain ? newMapping.domain : null
            };

            await createMapping(editingUser.id, mappingData);
            setNewMapping({ awsAccountId: '', domain: '' });
            setIsAddingMapping(false);
            setMappingsError(null);
            setMappingsSuccess('Mapping added successfully');
            setTimeout(() => setMappingsSuccess(null), 5000);
            await loadUserMappings(editingUser.id);
        } catch (err: any) {
            setMappingsError(err.message || 'Failed to create mapping');
            setTimeout(() => setMappingsError(null), 5000);
        } finally {
            setIsLoadingMappings(false);
        }
    };

    const handleDeleteMapping = async (mappingId: number) => {
        if (!editingUser) return;
        
        if (!confirm('Are you sure you want to delete this mapping?')) {
            return;
        }

        setIsLoadingMappings(true);
        try {
            await deleteMapping(editingUser.id, mappingId);
            setMappingsSuccess('Mapping deleted successfully');
            setTimeout(() => setMappingsSuccess(null), 5000);
            await loadUserMappings(editingUser.id);
        } catch (err: any) {
            setMappingsError(err.message || 'Failed to delete mapping');
            setTimeout(() => setMappingsError(null), 5000);
        } finally {
            setIsLoadingMappings(false);
        }
    };

    const handleEditMapping = (mapping: UserMapping) => {
        setEditingMappingId(mapping.id);
        setEditMappingData({
            awsAccountId: mapping.awsAccountId || '',
            domain: mapping.domain || ''
        });
        setMappingsError(null);
    };

    const handleSaveMapping = async () => {
        if (!editingUser || editingMappingId === null) return;
        
        // Validate at least one field
        const hasAwsId = editMappingData.awsAccountId && editMappingData.awsAccountId.trim() !== '';
        const hasDomain = editMappingData.domain && editMappingData.domain.trim() !== '';
        
        if (!hasAwsId && !hasDomain) {
            setMappingsError('At least one of AWS Account ID or Domain must be provided');
            return;
        }

        setIsLoadingMappings(true);
        try {
            const updateData: UpdateMappingRequest = {
                awsAccountId: hasAwsId ? editMappingData.awsAccountId : null,
                domain: hasDomain ? editMappingData.domain : null
            };

            await updateMapping(editingUser.id, editingMappingId, updateData);
            setEditingMappingId(null);
            setEditMappingData({ awsAccountId: '', domain: '' });
            setMappingsError(null);
            setMappingsSuccess('Mapping updated successfully');
            setTimeout(() => setMappingsSuccess(null), 5000);
            await loadUserMappings(editingUser.id);
        } catch (err: any) {
            setMappingsError(err.message || 'Failed to update mapping');
            setTimeout(() => setMappingsError(null), 5000);
        } finally {
            setIsLoadingMappings(false);
        }
    };

    const handleCancelEdit = () => {
        setEditingMappingId(null);
        setEditMappingData({ awsAccountId: '', domain: '' });
        setMappingsError(null);
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
                                    <div className="mb-3">
                                        <label className="form-label">Workgroups</label>
                                        <div>
                                            {workgroups.length > 0 ? (
                                                workgroups.map(workgroup => (
                                                    <div className="form-check" key={workgroup.id}>
                                                        <input
                                                            className="form-check-input"
                                                            type="checkbox"
                                                            id={`new-workgroup-${workgroup.id}`}
                                                            value={workgroup.id}
                                                            checked={newUser.workgroupIds.includes(workgroup.id)}
                                                            onChange={handleNewUserWorkgroupsChange}
                                                            disabled={isSubmittingUser}
                                                        />
                                                        <label className="form-check-label" htmlFor={`new-workgroup-${workgroup.id}`}>
                                                            {workgroup.name}
                                                        </label>
                                                    </div>
                                                ))
                                            ) : (
                                                <small className="text-muted">No workgroups available</small>
                                            )}
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
                                    <div className="mb-3">
                                        <label className="form-label">Workgroups</label>
                                        <div>
                                            {workgroups.length > 0 ? (
                                                workgroups.map(workgroup => (
                                                    <div className="form-check" key={workgroup.id}>
                                                        <input
                                                            className="form-check-input"
                                                            type="checkbox"
                                                            id={`edit-workgroup-${workgroup.id}`}
                                                            value={workgroup.id}
                                                            checked={editUser.workgroupIds.includes(workgroup.id)}
                                                            onChange={handleEditUserWorkgroupsChange}
                                                            disabled={isSubmittingEdit}
                                                        />
                                                        <label className="form-check-label" htmlFor={`edit-workgroup-${workgroup.id}`}>
                                                            {workgroup.name}
                                                        </label>
                                                    </div>
                                                ))
                                            ) : (
                                                <small className="text-muted">No workgroups available</small>
                                            )}
                                        </div>
                                    </div>

                                    {/* User Mappings Section (Feature 017) */}
                                    <div className="mb-3 mt-4">
                                        <h3>Access Mappings</h3>
                                        
                                        {mappingsSuccess && (
                                            <div className="alert alert-success alert-dismissible fade show" role="alert">
                                                <i className="bi bi-check-circle me-2"></i>{mappingsSuccess}
                                                <button type="button" className="btn-close" onClick={() => setMappingsSuccess(null)} aria-label="Close"></button>
                                            </div>
                                        )}
                                        
                                        {mappingsError && (
                                            <div className="alert alert-danger alert-dismissible fade show" role="alert">
                                                <i className="bi bi-exclamation-triangle me-2"></i>{mappingsError}
                                                <button type="button" className="btn-close" onClick={() => setMappingsError(null)} aria-label="Close"></button>
                                            </div>
                                        )}
                                        
                                        {isLoadingMappings && (
                                            <div className="text-center my-3">
                                                <div className="spinner-border text-primary" role="status">
                                                    <span className="visually-hidden">Loading mappings...</span>
                                                </div>
                                            </div>
                                        )}
                                        
                                        <table className="table table-sm">
                                            <thead>
                                                <tr>
                                                    <th>AWS Account ID</th>
                                                    <th>Domain</th>
                                                    <th>Created</th>
                                                    <th>Actions</th>
                                                </tr>
                                            </thead>
                                            <tbody>
                                                {mappings.length > 0 ? (
                                                    mappings.map(mapping => (
                                                        <tr key={mapping.id}>
                                                            {editingMappingId === mapping.id ? (
                                                                // Edit mode
                                                                <>
                                                                    <td>
                                                                        <input
                                                                            type="text"
                                                                            className="form-control form-control-sm"
                                                                            value={editMappingData.awsAccountId}
                                                                            onChange={e => setEditMappingData({...editMappingData, awsAccountId: e.target.value})}
                                                                            onKeyDown={e => {
                                                                                if (e.key === 'Enter') handleSaveMapping();
                                                                                if (e.key === 'Escape') handleCancelEdit();
                                                                            }}
                                                                            pattern="\d{12}"
                                                                            placeholder="123456789012"
                                                                            disabled={isLoadingMappings}
                                                                            aria-label="AWS Account ID"
                                                                            aria-describedby="aws-id-help"
                                                                            autoFocus
                                                                        />
                                                                    </td>
                                                                    <td>
                                                                        <input
                                                                            type="text"
                                                                            className="form-control form-control-sm"
                                                                            value={editMappingData.domain}
                                                                            onChange={e => setEditMappingData({...editMappingData, domain: e.target.value})}
                                                                            onKeyDown={e => {
                                                                                if (e.key === 'Enter') handleSaveMapping();
                                                                                if (e.key === 'Escape') handleCancelEdit();
                                                                            }}
                                                                            placeholder="example.com"
                                                                            disabled={isLoadingMappings}
                                                                            aria-label="Domain"
                                                                            aria-describedby="domain-help"
                                                                        />
                                                                    </td>
                                                                    <td>{new Date(mapping.createdAt).toLocaleDateString()}</td>
                                                                    <td>
                                                                        <button 
                                                                            className="btn btn-sm btn-success me-1"
                                                                            onClick={handleSaveMapping}
                                                                            type="button"
                                                                            disabled={isLoadingMappings}
                                                                            aria-label="Save mapping changes"
                                                                        >
                                                                            {isLoadingMappings ? (
                                                                                <span className="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
                                                                            ) : 'Save'}
                                                                        </button>
                                                                        <button 
                                                                            className="btn btn-sm btn-secondary"
                                                                            onClick={handleCancelEdit}
                                                                            type="button"
                                                                            disabled={isLoadingMappings}
                                                                            aria-label="Cancel editing"
                                                                        >
                                                                            Cancel
                                                                        </button>
                                                                    </td>
                                                                </>
                                                            ) : (
                                                                // View mode
                                                                <>
                                                                    <td>{mapping.awsAccountId || '-'}</td>
                                                                    <td>{mapping.domain || '-'}</td>
                                                                    <td>{new Date(mapping.createdAt).toLocaleDateString()}</td>
                                                                    <td>
                                                                        <button 
                                                                            className="btn btn-sm btn-primary me-1"
                                                                            onClick={() => handleEditMapping(mapping)}
                                                                            type="button"
                                                                            disabled={isLoadingMappings || editingMappingId !== null}
                                                                            aria-label={`Edit mapping for ${mapping.awsAccountId || mapping.domain}`}
                                                                        >
                                                                            <i className="bi bi-pencil me-1"></i>Edit
                                                                        </button>
                                                                        <button 
                                                                            className="btn btn-sm btn-danger"
                                                                            onClick={() => handleDeleteMapping(mapping.id)}
                                                                            type="button"
                                                                            disabled={isLoadingMappings || editingMappingId !== null}
                                                                            aria-label={`Delete mapping for ${mapping.awsAccountId || mapping.domain}`}
                                                                        >
                                                                            <i className="bi bi-trash me-1"></i>Delete
                                                                        </button>
                                                                    </td>
                                                                </>
                                                            )}
                                                        </tr>
                                                    ))
                                                ) : (
                                                    <tr>
                                                        <td colSpan={4} className="text-center text-muted">
                                                            No mappings configured for this user
                                                        </td>
                                                    </tr>
                                                )}
                                            </tbody>
                                        </table>
                                        
                                        {!isAddingMapping && (
                                            <button 
                                                type="button"
                                                className="btn btn-primary btn-sm"
                                                onClick={() => setIsAddingMapping(true)}
                                                disabled={isLoadingMappings || editingMappingId !== null}
                                                aria-label="Add new mapping"
                                            >
                                                <i className="bi bi-plus-circle me-1"></i>Add Mapping
                                            </button>
                                        )}
                                        
                                        {isAddingMapping && (
                                            <div className="card mt-2">
                                                <div className="card-body">
                                                    <h5 className="card-title">Add New Mapping</h5>
                                                    <p className="text-muted small">Provide at least one of AWS Account ID or Domain</p>
                                                    <div className="mb-2">
                                                        <label htmlFor="new-aws-id" className="form-label">AWS Account ID (12 digits)</label>
                                                        <input
                                                            id="new-aws-id"
                                                            type="text"
                                                            className="form-control"
                                                            value={newMapping.awsAccountId}
                                                            onChange={e => setNewMapping({...newMapping, awsAccountId: e.target.value})}
                                                            onKeyDown={e => {
                                                                if (e.key === 'Enter') handleAddMapping();
                                                                if (e.key === 'Escape') {
                                                                    setIsAddingMapping(false);
                                                                    setNewMapping({ awsAccountId: '', domain: '' });
                                                                    setMappingsError(null);
                                                                }
                                                            }}
                                                            pattern="\d{12}"
                                                            placeholder="123456789012"
                                                            disabled={isLoadingMappings}
                                                            aria-describedby="aws-id-help"
                                                            autoFocus
                                                        />
                                                        <small id="aws-id-help" className="form-text text-muted">Must be exactly 12 digits</small>
                                                    </div>
                                                    <div className="mb-2">
                                                        <label htmlFor="new-domain" className="form-label">Domain</label>
                                                        <input
                                                            id="new-domain"
                                                            type="text"
                                                            className="form-control"
                                                            value={newMapping.domain}
                                                            onChange={e => setNewMapping({...newMapping, domain: e.target.value})}
                                                            onKeyDown={e => {
                                                                if (e.key === 'Enter') handleAddMapping();
                                                                if (e.key === 'Escape') {
                                                                    setIsAddingMapping(false);
                                                                    setNewMapping({ awsAccountId: '', domain: '' });
                                                                    setMappingsError(null);
                                                                }
                                                            }}
                                                            placeholder="example.com"
                                                            disabled={isLoadingMappings}
                                                            aria-describedby="domain-help"
                                                        />
                                                        <small id="domain-help" className="form-text text-muted">Lowercase letters, numbers, dots, and hyphens</small>
                                                    </div>
                                                    <button 
                                                        type="button"
                                                        className="btn btn-success btn-sm me-2" 
                                                        onClick={handleAddMapping}
                                                        disabled={isLoadingMappings}
                                                        aria-label="Save new mapping"
                                                    >
                                                        {isLoadingMappings ? (
                                                            <>
                                                                <span className="spinner-border spinner-border-sm me-1" role="status" aria-hidden="true"></span>
                                                                Saving...
                                                            </>
                                                        ) : (
                                                            <>
                                                                <i className="bi bi-check-lg me-1"></i>Save
                                                            </>
                                                        )}
                                                    </button>
                                                    <button 
                                                        type="button"
                                                        className="btn btn-secondary btn-sm" 
                                                        onClick={() => {
                                                            setIsAddingMapping(false);
                                                            setNewMapping({ awsAccountId: '', domain: '' });
                                                            setMappingsError(null);
                                                        }}
                                                        disabled={isLoadingMappings}
                                                        aria-label="Cancel adding mapping"
                                                    >
                                                        <i className="bi bi-x-lg me-1"></i>Cancel
                                                    </button>
                                                </div>
                                            </div>
                                        )}
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
                        <th>Workgroups</th>
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
                                    {user.workgroups && user.workgroups.length > 0 ? (
                                        <div>
                                            {user.workgroups.map(wg => (
                                                <span key={wg.id} className="badge bg-info me-1">{wg.name}</span>
                                            ))}
                                        </div>
                                    ) : (
                                        <span className="text-muted">None</span>
                                    )}
                                </td>
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
                            <td colSpan={6} className="text-center">
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
