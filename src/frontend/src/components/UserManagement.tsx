import React, { useState, useEffect, useMemo } from 'react';
import type { FormEvent } from 'react';
import { authenticatedGet, authenticatedPost, authenticatedPut, authenticatedDelete } from '../utils/auth';
import { getUserMappings, createMapping, updateMapping, deleteMapping, type UserMapping, type CreateMappingRequest, type UpdateMappingRequest } from '../api/userMappings';

type SortField = 'username' | 'email' | 'roles' | 'lastLogin' | 'workgroups';
type SortDirection = 'asc' | 'desc';

// Define an interface for the user data expected from the backend
interface User {
    id: number;
    username: string;
    email: string;
    roles: string[];
    workgroups?: WorkgroupSummary[];
    lastLogin?: string;
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

// Available roles - Feature: 025-role-based-access-control
// All roles implemented: USER (baseline), ADMIN (full access),
// VULN (vulnerabilities), RISK (risk management), REQ (requirements),
// SECCHAMPION (broad access: risk+req+vuln), RELEASE_MANAGER (release management)
const AVAILABLE_ROLES = ['USER', 'ADMIN', 'VULN', 'RISK', 'REQ', 'SECCHAMPION', 'RELEASE_MANAGER'];

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

    // State for search, sort, pagination
    const [searchQuery, setSearchQuery] = useState('');
    const [sortField, setSortField] = useState<SortField>('username');
    const [sortDirection, setSortDirection] = useState<SortDirection>('asc');
    const [currentPage, setCurrentPage] = useState(1);
    const [pageSize, setPageSize] = useState(25);

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
        domain: '',
        ipAddress: ''
    });
    const [editingMappingId, setEditingMappingId] = useState<number | null>(null);
    const [editMappingData, setEditMappingData] = useState<UpdateMappingRequest>({
        awsAccountId: '',
        domain: '',
        ipAddress: ''
    });


    useEffect(() => {
        let isMounted = true; // Track if component is mounted
        let timeoutId: NodeJS.Timeout | null = null;

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
                if (isMounted) {
                    setIsLoading(false);
                    setIsAdmin(false);
                }
            }
        };

        // Always check immediately on mount (like Sidebar does)
        checkAdminAndFetchUsers();

        // Always add event listener for future updates
        console.log("Adding userLoaded listener for UserManagement");
        window.addEventListener('userLoaded', checkAdminAndFetchUsers);

        // Set a timeout as a fallback for slow browsers (like IE)
        timeoutId = setTimeout(() => {
            if (isMounted && !window.currentUser) {
                console.log("Timeout waiting for user data in UserManagement");
                setIsLoading(false);
                setIsAdmin(false);
                setError("Could not verify user permissions.");
            }
        }, 5000); // Wait 5 seconds (increased from 3 for slow browsers)

        // Cleanup
        return () => {
            if (timeoutId) clearTimeout(timeoutId);
            window.removeEventListener('userLoaded', checkAdminAndFetchUsers);
            isMounted = false;
            console.log("UserManagement component unmounting - listener removed");
        };

    }, []); // Run only once on mount

    const fetchUsers = async (isMounted: boolean) => {
        try {
            console.log("Fetching users...");
            const response = await authenticatedGet('/api/users?includeWorkgroups=true');
            if (response.ok && isMounted) {
                const data: User[] = (await response.json()).map((u: User) => ({
                    ...u,
                    roles: u.roles ?? [],
                }));
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
            } else if (response.status === 403) {
                // User doesn't have ADMIN role
                console.info('Workgroups not available for non-admin users');
                setWorkgroups([]);
            } else {
                console.error('Failed to fetch workgroups, status:', response.status);
                setWorkgroups([]);
            }
        } catch (err: any) {
            console.error("Error fetching workgroups:", err);
            setWorkgroups([]);
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
                setUsers(prevUsers => [...prevUsers, { ...data, roles: data.roles ?? [] }]);
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

    const handleCloseEditModal = () => {
        setShowEditUserModal(false);
        setEditingUser(null);
        setEditUser({ username: '', email: '', password: '', roles: [], workgroupIds: [] });

        // Reset mapping form states to prevent data leaking between users
        setNewMapping({ awsAccountId: '', domain: '', ipAddress: '' });
        setEditMappingData({ awsAccountId: '', domain: '', ipAddress: '' });
        setIsAddingMapping(false);
        setEditingMappingId(null);
        setMappingsError(null);
        setMappingsSuccess(null);
    };

    const handleDeleteUser = async (userId: number, username: string) => {
        if (!confirm(`Are you sure you want to delete user "${username}"?`)) {
            return;
        }

        setDeletingUserId(userId);
        try {
            const response = await authenticatedDelete(`/api/users/${userId}`);

            // Feature 037: Handle last admin protection (HTTP 409 Conflict)
            if (response.status === 409) {
                const errorData = await response.json().catch(() => ({ message: 'Cannot delete the last administrator' }));
                alert(
                    errorData.message || 'Cannot delete the last administrator. At least one ADMIN user must remain in the system.\n\n' +
                    'Please create another admin user before deleting this one.'
                );
                setDeletingUserId(null);
                return;
            }

            if (!response.ok) {
                const errorData = await response.json().catch(() => ({ error: 'Unknown error' }));
                throw new Error(errorData.error || errorData.message || `Failed to delete user: ${response.status}`);
            }

            setUsers(prevUsers => prevUsers.filter(user => user.id !== userId));
        } catch (err: any) {
            console.error('Delete user request failed:', err);
            alert(err.message || 'An error occurred while deleting the user. Please try again.');
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
            roles: [...(user.roles ?? [])],
            workgroupIds: user.workgroups?.map(wg => wg.id) || [],
        });
        setEditUserError(null);

        // Reset mapping form states to prevent data leaking between users
        setNewMapping({ awsAccountId: '', domain: '', ipAddress: '' });
        setEditMappingData({ awsAccountId: '', domain: '', ipAddress: '' });
        setIsAddingMapping(false);
        setEditingMappingId(null);
        setMappingsError(null);
        setMappingsSuccess(null);

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

            // Feature 037: Handle last admin role protection (HTTP 409 Conflict)
            if (response.status === 409) {
                const errorData = await response.json().catch(() => ({ message: 'Cannot remove ADMIN role from the last administrator' }));
                setEditUserError(
                    errorData.message || 'Cannot remove ADMIN role from the last administrator. At least one ADMIN user must remain in the system.\n\n' +
                    'Please assign the ADMIN role to another user before removing it from this one.'
                );
                setIsSubmittingEdit(false);
                return;
            }

            if (response.ok) {
                const data = await response.json();
                setUsers(prevUsers => prevUsers.map(u => u.id === editingUser.id ? { ...data, roles: data.roles ?? [] } : u));
                handleCloseEditModal();
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
        const hasIpAddress = newMapping.ipAddress && newMapping.ipAddress.trim() !== '';

        if (!hasAwsId && !hasDomain && !hasIpAddress) {
            setMappingsError('At least one of AWS Account ID, IP Address, or Domain must be provided');
            return;
        }

        setIsLoadingMappings(true);
        try {
            const mappingData: CreateMappingRequest = {
                email: editingUser.email,
                awsAccountId: hasAwsId ? newMapping.awsAccountId : null,
                domain: hasDomain ? newMapping.domain : null,
                ipAddress: hasIpAddress ? newMapping.ipAddress : null
            };

            await createMapping(editingUser.id, mappingData);
            setNewMapping({ awsAccountId: '', domain: '', ipAddress: '' });
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
            domain: mapping.domain || '',
            ipAddress: mapping.ipAddress || ''
        });
        setMappingsError(null);
    };

    const handleSaveMapping = async () => {
        if (!editingUser || editingMappingId === null) return;

        // Validate at least one field
        const hasAwsId = editMappingData.awsAccountId && editMappingData.awsAccountId.trim() !== '';
        const hasDomain = editMappingData.domain && editMappingData.domain.trim() !== '';
        const hasIpAddress = editMappingData.ipAddress && editMappingData.ipAddress.trim() !== '';

        if (!hasAwsId && !hasDomain && !hasIpAddress) {
            setMappingsError('At least one of AWS Account ID, IP Address, or Domain must be provided');
            return;
        }

        setIsLoadingMappings(true);
        try {
            const updateData: UpdateMappingRequest = {
                email: editingUser.email,
                awsAccountId: hasAwsId ? editMappingData.awsAccountId : null,
                domain: hasDomain ? editMappingData.domain : null,
                ipAddress: hasIpAddress ? editMappingData.ipAddress : null
            };

            await updateMapping(editingUser.id, editingMappingId, updateData);
            setEditingMappingId(null);
            setEditMappingData({ awsAccountId: '', domain: '', ipAddress: '' });
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
        setEditMappingData({ awsAccountId: '', domain: '', ipAddress: '' });
        setMappingsError(null);
    };


    // --- Filtering, Sorting, Pagination ---
    const filteredAndSortedUsers = useMemo(() => {
        let result = [...users];

        // Filter
        if (searchQuery.trim()) {
            const q = searchQuery.toLowerCase();
            result = result.filter(u =>
                u.username.toLowerCase().includes(q) ||
                u.email.toLowerCase().includes(q) ||
                (u.roles ?? []).some(r => r.toLowerCase().includes(q)) ||
                (u.workgroups ?? []).some(wg => wg.name.toLowerCase().includes(q))
            );
        }

        // Sort
        result.sort((a, b) => {
            let cmp = 0;
            switch (sortField) {
                case 'username':
                    cmp = a.username.localeCompare(b.username);
                    break;
                case 'email':
                    cmp = a.email.localeCompare(b.email);
                    break;
                case 'roles':
                    cmp = (a.roles ?? []).join(',').localeCompare((b.roles ?? []).join(','));
                    break;
                case 'lastLogin': {
                    const aTime = a.lastLogin ? new Date(a.lastLogin).getTime() : 0;
                    const bTime = b.lastLogin ? new Date(b.lastLogin).getTime() : 0;
                    cmp = aTime - bTime;
                    break;
                }
                case 'workgroups': {
                    const aWg = (a.workgroups ?? []).map(w => w.name).join(',');
                    const bWg = (b.workgroups ?? []).map(w => w.name).join(',');
                    cmp = aWg.localeCompare(bWg);
                    break;
                }
            }
            return sortDirection === 'asc' ? cmp : -cmp;
        });

        return result;
    }, [users, searchQuery, sortField, sortDirection]);

    const totalPages = Math.max(1, Math.ceil(filteredAndSortedUsers.length / pageSize));
    const safePage = Math.min(currentPage, totalPages);
    const paginatedUsers = filteredAndSortedUsers.slice((safePage - 1) * pageSize, safePage * pageSize);

    // Reset to page 1 when search changes
    useEffect(() => {
        setCurrentPage(1);
    }, [searchQuery, pageSize]);

    const handleSort = (field: SortField) => {
        if (sortField === field) {
            setSortDirection(prev => prev === 'asc' ? 'desc' : 'asc');
        } else {
            setSortField(field);
            setSortDirection('asc');
        }
    };

    const renderSortIcon = (field: SortField) => {
        if (sortField !== field) {
            return <i className="bi bi-chevron-expand text-muted ms-1" style={{ fontSize: '0.75em' }}></i>;
        }
        return sortDirection === 'asc'
            ? <i className="bi bi-sort-up ms-1"></i>
            : <i className="bi bi-sort-down ms-1"></i>;
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
                    <div className="modal-dialog modal-dialog-centered modal-xl">
                        <div className="modal-content">
                            <form onSubmit={handleEditUserSubmit}>
                                <div className="modal-header">
                                    <h5 className="modal-title">Edit User: {editingUser.username}</h5>
                                    <button type="button" className="btn-close" onClick={handleCloseEditModal} aria-label="Close" disabled={isSubmittingEdit}></button>
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
                                                    <th>IP Address/Range</th>
                                                    <th>Type</th>
                                                    <th>IP Count</th>
                                                    <th>Domain</th>
                                                    <th>Created</th>
                                                    <th>Actions</th>
                                                </tr>
                                            </thead>
                                            <tbody>
                                                {mappings.length > 0 ? (
                                                    mappings.map(mapping => {
                                                        // Helper function to get badge for IP range type
                                                        const getRangeTypeBadge = (type?: string) => {
                                                            if (!type) return '-';
                                                            switch (type) {
                                                                case 'SINGLE':
                                                                    return <span className="badge bg-primary">Single IP</span>;
                                                                case 'CIDR':
                                                                    return <span className="badge bg-info">CIDR</span>;
                                                                case 'DASH_RANGE':
                                                                    return <span className="badge bg-warning text-dark">Dash Range</span>;
                                                                default:
                                                                    return <span className="badge bg-secondary">Unknown</span>;
                                                            }
                                                        };

                                                        // Helper function to format IP count
                                                        const formatIpCount = (count?: number) => {
                                                            if (!count) return '-';
                                                            if (count === 1) return '1 IP';
                                                            if (count < 1000) return `${count} IPs`;
                                                            if (count < 1000000) return `${(count / 1000).toFixed(1)}K IPs`;
                                                            return `${(count / 1000000).toFixed(1)}M IPs`;
                                                        };

                                                        return (
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
                                                                        <td colSpan={3}><small className="text-muted">IP editing not supported in this view</small></td>
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
                                                                        <td>{mapping.awsAccountId ? <code className="small">{mapping.awsAccountId}</code> : '-'}</td>
                                                                        <td>{mapping.ipAddress ? <code className="small">{mapping.ipAddress}</code> : '-'}</td>
                                                                        <td>{getRangeTypeBadge(mapping.ipRangeType)}</td>
                                                                        <td><small className="text-muted">{formatIpCount(mapping.ipCount)}</small></td>
                                                                        <td>{mapping.domain || '-'}</td>
                                                                        <td>{new Date(mapping.createdAt).toLocaleDateString()}</td>
                                                                        <td>
                                                                            <button
                                                                                className="btn btn-sm btn-primary me-1"
                                                                                onClick={() => handleEditMapping(mapping)}
                                                                                type="button"
                                                                                disabled={isLoadingMappings || editingMappingId !== null}
                                                                                aria-label={`Edit mapping for ${mapping.awsAccountId || mapping.ipAddress || mapping.domain}`}
                                                                            >
                                                                                <i className="bi bi-pencil"></i>
                                                                            </button>
                                                                            <button
                                                                                className="btn btn-sm btn-danger"
                                                                                onClick={() => handleDeleteMapping(mapping.id)}
                                                                                type="button"
                                                                                disabled={isLoadingMappings || editingMappingId !== null}
                                                                                aria-label={`Delete mapping for ${mapping.awsAccountId || mapping.ipAddress || mapping.domain}`}
                                                                            >
                                                                                <i className="bi bi-trash"></i>
                                                                            </button>
                                                                        </td>
                                                                    </>
                                                                )}
                                                            </tr>
                                                        );
                                                    })
                                                ) : (
                                                    <tr>
                                                        <td colSpan={7} className="text-center text-muted">
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
                                                    <p className="text-muted small">Provide at least one of AWS Account ID, IP Address, or Domain</p>
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
                                                                    setNewMapping({ awsAccountId: '', domain: '', ipAddress: '' });
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
                                                        <label htmlFor="new-ip-address" className="form-label">IP Address / Range</label>
                                                        <input
                                                            id="new-ip-address"
                                                            type="text"
                                                            className="form-control"
                                                            value={newMapping.ipAddress}
                                                            onChange={e => setNewMapping({...newMapping, ipAddress: e.target.value})}
                                                            onKeyDown={e => {
                                                                if (e.key === 'Enter') handleAddMapping();
                                                                if (e.key === 'Escape') {
                                                                    setIsAddingMapping(false);
                                                                    setNewMapping({ awsAccountId: '', domain: '', ipAddress: '' });
                                                                    setMappingsError(null);
                                                                }
                                                            }}
                                                            placeholder="192.168.1.100 or 10.0.0.0/24 or 172.16.0.1-172.16.0.100"
                                                            disabled={isLoadingMappings}
                                                            aria-describedby="ip-address-help"
                                                        />
                                                        <small id="ip-address-help" className="form-text text-muted">Single IP (192.168.1.100), CIDR (10.0.0.0/24), or dash range (172.16.0.1-172.16.0.100)</small>
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
                                                                    setNewMapping({ awsAccountId: '', domain: '', ipAddress: '' });
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
                                                            setNewMapping({ awsAccountId: '', domain: '', ipAddress: '' });
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
                                    <button type="button" className="btn btn-secondary" onClick={handleCloseEditModal} disabled={isSubmittingEdit}>Close</button>
                                    <button type="submit" className="btn btn-primary" disabled={isSubmittingEdit}>
                                        {isSubmittingEdit ? 'Updating...' : 'Update User'}
                                    </button>
                                </div>
                            </form>
                        </div>
                    </div>
                </div>
            )}

            {/* Search and page size controls */}
            <div className="d-flex justify-content-between align-items-center mb-3">
                <div className="d-flex align-items-center gap-3">
                    <div className="input-group" style={{ maxWidth: '350px' }}>
                        <span className="input-group-text"><i className="bi bi-search"></i></span>
                        <input
                            type="text"
                            className="form-control"
                            placeholder="Search users..."
                            value={searchQuery}
                            onChange={e => setSearchQuery(e.target.value)}
                        />
                        {searchQuery && (
                            <button className="btn btn-outline-secondary" type="button" onClick={() => setSearchQuery('')}>
                                <i className="bi bi-x-lg"></i>
                            </button>
                        )}
                    </div>
                    <small className="text-muted text-nowrap">
                        {filteredAndSortedUsers.length === users.length
                            ? `${users.length} users`
                            : `${filteredAndSortedUsers.length} of ${users.length} users`}
                    </small>
                </div>
                <div className="d-flex align-items-center gap-2">
                    <label htmlFor="page-size" className="form-label mb-0 text-nowrap small">Per page:</label>
                    <select
                        id="page-size"
                        className="form-select form-select-sm"
                        style={{ width: 'auto' }}
                        value={pageSize}
                        onChange={e => setPageSize(Number(e.target.value))}
                    >
                        <option value={25}>25</option>
                        <option value={50}>50</option>
                        <option value={100}>100</option>
                    </select>
                </div>
            </div>

            <table className="table table-striped table-hover">
                <thead>
                    <tr>
                        <th role="button" onClick={() => handleSort('username')} style={{ cursor: 'pointer', userSelect: 'none' }}>
                            Username{renderSortIcon('username')}
                        </th>
                        <th role="button" onClick={() => handleSort('email')} style={{ cursor: 'pointer', userSelect: 'none' }}>
                            Email{renderSortIcon('email')}
                        </th>
                        <th role="button" onClick={() => handleSort('roles')} style={{ cursor: 'pointer', userSelect: 'none' }}>
                            Roles{renderSortIcon('roles')}
                        </th>
                        <th role="button" onClick={() => handleSort('lastLogin')} style={{ cursor: 'pointer', userSelect: 'none' }}>
                            Last Login{renderSortIcon('lastLogin')}
                        </th>
                        <th role="button" onClick={() => handleSort('workgroups')} style={{ cursor: 'pointer', userSelect: 'none' }}>
                            Workgroups{renderSortIcon('workgroups')}
                        </th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    {paginatedUsers.length > 0 ? (
                        paginatedUsers.map(user => (
                            <tr key={user.id}>
                                <td>{user.username}</td>
                                <td>{user.email}</td>
                                <td>{user.roles?.join(', ') || 'N/A'}</td>
                                <td>
                                    {user.lastLogin ? (
                                        <span title={new Date(user.lastLogin).toLocaleString()}>
                                            {new Date(user.lastLogin).toLocaleDateString()}
                                        </span>
                                    ) : (
                                        <span className="text-muted">Never</span>
                                    )}
                                </td>
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
                                {searchQuery
                                    ? `No users matching "${searchQuery}"`
                                    : (error && !error.includes("Access Denied") ? "Could not load users." : "No users found.")}
                            </td>
                        </tr>
                    )}
                </tbody>
            </table>

            {/* Pagination */}
            {totalPages > 1 && (
                <nav aria-label="User list pagination">
                    <ul className="pagination justify-content-center mb-0">
                        <li className={`page-item ${safePage <= 1 ? 'disabled' : ''}`}>
                            <button className="page-link" onClick={() => setCurrentPage(1)} disabled={safePage <= 1}>
                                <i className="bi bi-chevron-double-left"></i>
                            </button>
                        </li>
                        <li className={`page-item ${safePage <= 1 ? 'disabled' : ''}`}>
                            <button className="page-link" onClick={() => setCurrentPage(p => Math.max(1, p - 1))} disabled={safePage <= 1}>
                                <i className="bi bi-chevron-left"></i>
                            </button>
                        </li>
                        {Array.from({ length: totalPages }, (_, i) => i + 1)
                            .filter(page => page === 1 || page === totalPages || Math.abs(page - safePage) <= 2)
                            .reduce<(number | 'ellipsis')[]>((acc, page, idx, arr) => {
                                if (idx > 0 && page - (arr[idx - 1] as number) > 1) {
                                    acc.push('ellipsis');
                                }
                                acc.push(page);
                                return acc;
                            }, [])
                            .map((item, idx) =>
                                item === 'ellipsis' ? (
                                    <li key={`ellipsis-${idx}`} className="page-item disabled">
                                        <span className="page-link">&hellip;</span>
                                    </li>
                                ) : (
                                    <li key={item} className={`page-item ${safePage === item ? 'active' : ''}`}>
                                        <button className="page-link" onClick={() => setCurrentPage(item as number)}>
                                            {item}
                                        </button>
                                    </li>
                                )
                            )}
                        <li className={`page-item ${safePage >= totalPages ? 'disabled' : ''}`}>
                            <button className="page-link" onClick={() => setCurrentPage(p => Math.min(totalPages, p + 1))} disabled={safePage >= totalPages}>
                                <i className="bi bi-chevron-right"></i>
                            </button>
                        </li>
                        <li className={`page-item ${safePage >= totalPages ? 'disabled' : ''}`}>
                            <button className="page-link" onClick={() => setCurrentPage(totalPages)} disabled={safePage >= totalPages}>
                                <i className="bi bi-chevron-double-right"></i>
                            </button>
                        </li>
                    </ul>
                </nav>
            )}
        </div>
    );
};

export default UserManagement;
