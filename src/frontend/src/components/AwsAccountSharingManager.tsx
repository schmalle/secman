import React, { useState, useEffect, useCallback } from 'react';
import { authenticatedGet, getUser, hasRole } from '../utils/auth';
import {
    listSharingRules,
    createSharingRule,
    deleteSharingRule,
    type AwsAccountSharing,
    type SharingUser,
} from '../services/awsAccountSharingService';

// Dropdown selection is encoded as "id:<number>" for active users or
// "email:<addr>" for pending users (those recorded in UserMapping but never
// logged in). This keeps a single string value on the <select> while
// preserving which side of the API contract to use at submit time.
const encodeUserOption = (u: SharingUser): string =>
    u.id != null ? `id:${u.id}` : `email:${u.email}`;

// View-all users (ADMIN, SECCHAMPION) see every sharing rule in the system.
// Everyone else (including VULN) is scoped on the backend to rules where they
// are source OR target — the component still renders the "admin-style" layout
// for those users, because they see rules involving OTHER accounts (as target).
const checkHasFullViewAccess = (): boolean =>
    hasRole('ADMIN') || hasRole('SECCHAMPION');

// Manage-any users (ADMIN, SECCHAMPION) can create rules with any source and
// delete any rule. VULN and everyone else are restricted to source = self.
const checkCanManageAnyRule = (): boolean =>
    hasRole('ADMIN') || hasRole('SECCHAMPION');

interface User {
    id: number;
    username: string;
    email: string;
    roles: string[];
}

const AwsAccountSharingManager: React.FC = () => {
    const [sharingRules, setSharingRules] = useState<AwsAccountSharing[]>([]);
    const [users, setUsers] = useState<SharingUser[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [successMessage, setSuccessMessage] = useState<string | null>(null);
    const [hasFullViewAccess, setHasFullViewAccess] = useState(false);
    const [canManageAnyRule, setCanManageAnyRule] = useState(false);
    const [currentUser, setCurrentUser] = useState<User | null>(null);

    // Pagination
    const [page, setPage] = useState(0);
    const [totalPages, setTotalPages] = useState(0);
    const [totalElements, setTotalElements] = useState(0);
    const pageSize = 20;

    // Create form
    const [showCreateForm, setShowCreateForm] = useState(false);
    // Encoded "id:<n>" or "email:<addr>" — see encodeUserOption.
    const [sourceSelection, setSourceSelection] = useState<string>('');
    const [targetSelection, setTargetSelection] = useState<string>('');
    const [targetSearch, setTargetSearch] = useState<string>('');
    const [isCreating, setIsCreating] = useState(false);
    // Inline form-submission error. Sticky (no auto-dismiss) so it survives
    // an open <select> overlay covering the page-top alert region.
    const [formError, setFormError] = useState<string | null>(null);

    // Delete confirmation
    const [deletingId, setDeletingId] = useState<number | null>(null);

    useEffect(() => {
        setHasFullViewAccess(checkHasFullViewAccess());
        setCanManageAnyRule(checkCanManageAnyRule());
        const user = getUser();
        setCurrentUser(user);
    }, []);

    const showSuccess = (msg: string) => {
        setSuccessMessage(msg);
        setTimeout(() => setSuccessMessage(null), 5000);
    };

    const showError = (msg: string) => {
        setError(msg);
        setTimeout(() => setError(null), 8000);
    };

    const fetchSharingRules = useCallback(async () => {
        try {
            setIsLoading(true);
            const result = await listSharingRules(page, pageSize);
            setSharingRules(result.content);
            setTotalPages(result.totalPages);
            setTotalElements(result.totalElements);
        } catch (err: any) {
            showError(err.message || 'Failed to load sharing rules');
        } finally {
            setIsLoading(false);
        }
    }, [page]);

    const fetchUsers = useCallback(async () => {
        try {
            // Always use the sharing-specific endpoint — it returns both active
            // users and pending users (known via UserMapping but never logged in).
            // The admin /api/users endpoint, by contrast, only lists real User
            // records and would drop pending entries from the dropdowns.
            const response = await authenticatedGet('/api/aws-account-sharing/users');
            if (response.ok) {
                const data = (await response.json()) as SharingUser[];
                setUsers(data);
            }
        } catch {
            // Non-critical - user dropdowns will be empty
        }
    }, []);

    useEffect(() => {
        fetchSharingRules();
        fetchUsers();
    }, [fetchSharingRules, fetchUsers]);

    // If the active target search filters out the currently selected target,
    // or the chosen source equals the chosen target, drop the target so the
    // visible <select> and form state stay aligned.
    useEffect(() => {
        if (!targetSelection) return;
        const effectiveSource = canManageAnyRule
            ? sourceSelection
            : (currentUser ? `id:${currentUser.id}` : '');
        if (effectiveSource && effectiveSource === targetSelection) {
            setTargetSelection('');
            return;
        }
        const search = targetSearch.trim().toLowerCase();
        if (!search) return;
        const stillVisible = users.some(u => {
            if (encodeUserOption(u) !== targetSelection) return false;
            return u.username.toLowerCase().includes(search) ||
                u.email.toLowerCase().includes(search);
        });
        if (!stillVisible) setTargetSelection('');
    }, [targetSearch, targetSelection, users, sourceSelection, canManageAnyRule, currentUser]);

    const handleCreate = async (e: React.FormEvent) => {
        e.preventDefault();
        setFormError(null);

        // Non-privileged users never pick a source — it's forced to self.
        const effectiveSourceSelection = canManageAnyRule
            ? sourceSelection
            : (currentUser ? `id:${currentUser.id}` : '');

        if (!effectiveSourceSelection || !targetSelection) {
            setFormError(canManageAnyRule ? 'Please select both source and target users' : 'Please select a target user');
            return;
        }
        if (effectiveSourceSelection === targetSelection) {
            setFormError('Source and target user cannot be the same');
            return;
        }

        // Decode "id:<n>" or "email:<addr>" into the matching request fields.
        const decode = (value: string): { id?: number; email?: string } => {
            if (value.startsWith('id:')) return { id: Number(value.slice(3)) };
            if (value.startsWith('email:')) return { email: value.slice(6) };
            return {};
        };
        const src = decode(effectiveSourceSelection);
        const tgt = decode(targetSelection);

        setIsCreating(true);
        try {
            const result = await createSharingRule({
                sourceUserId: src.id ?? null,
                sourceUserEmail: src.email ?? null,
                targetUserId: tgt.id ?? null,
                targetUserEmail: tgt.email ?? null,
            });
            showSuccess(
                `Sharing rule created: ${result.sourceUserEmail} → ${result.targetUserEmail} (${result.sharedAwsAccountCount} accounts)`
            );
            setShowCreateForm(false);
            setSourceSelection('');
            setTargetSelection('');
            setTargetSearch('');
            setFormError(null);
            fetchSharingRules();
            fetchUsers(); // A pending user may have just been materialized — refresh dropdowns.
        } catch (err: any) {
            setFormError(err.message || 'Failed to create sharing rule');
        } finally {
            setIsCreating(false);
        }
    };

    const handleDelete = async (id: number) => {
        try {
            await deleteSharingRule(id);
            showSuccess('Sharing rule deleted successfully');
            setDeletingId(null);
            fetchSharingRules();
        } catch (err: any) {
            showError(err.message || 'Failed to delete sharing rule');
        }
    };

    const formatDate = (dateStr: string) => {
        try {
            return new Date(dateStr).toLocaleString();
        } catch {
            return dateStr;
        }
    };

    return (
        <div>
            <div className="d-flex justify-content-between align-items-center mb-3">
                <h2>
                    <i className="bi bi-share-fill me-2"></i>
                    AWS Account Sharing
                </h2>
                <button
                    className="btn btn-primary"
                    onClick={() => {
                        if (showCreateForm) {
                            // Closing the form: drop any stale submission error
                            // so it doesn't reappear next time we open it.
                            setFormError(null);
                        }
                        setShowCreateForm(!showCreateForm);
                    }}
                >
                    <i className={`bi ${showCreateForm ? 'bi-x-lg' : 'bi-plus-lg'} me-1`}></i>
                    {showCreateForm ? 'Cancel' : 'Create Sharing Rule'}
                </button>
            </div>

            <p className="text-muted mb-3">
                {hasFullViewAccess ? (
                    <>Share AWS account visibility between users. When User A's accounts are shared with User B,
                    User B can see all assets belonging to User A's AWS accounts. Sharing is directional and non-transitive.</>
                ) : (
                    <>View the AWS account sharing rules you are involved in — either sharing your AWS accounts
                    out, or receiving visibility into another user's accounts. Sharing is directional and non-transitive.</>
                )}
            </p>

            {/* Alerts */}
            {successMessage && (
                <div className="alert alert-success alert-dismissible fade show" role="alert">
                    {successMessage}
                    <button type="button" className="btn-close" onClick={() => setSuccessMessage(null)}></button>
                </div>
            )}
            {error && (
                <div className="alert alert-danger alert-dismissible fade show" role="alert">
                    {error}
                    <button type="button" className="btn-close" onClick={() => setError(null)}></button>
                </div>
            )}

            {/* Create Form */}
            {showCreateForm && (
                <div className="card mb-4">
                    <div className="card-body">
                        <h5 className="card-title">Create Sharing Rule</h5>
                        {formError && (
                            <div className="alert alert-danger d-flex align-items-start mb-3" role="alert">
                                <i className="bi bi-exclamation-triangle-fill me-2 mt-1"></i>
                                <div className="flex-grow-1">
                                    <strong>Cannot create sharing rule.</strong>
                                    <div>{formError}</div>
                                    {/* Hint when the source side has nothing to share — common
                                        for non-ADMIN users who lack any AWS UserMapping. */}
                                    {/no AWS account mappings/i.test(formError) && (
                                        <small className="text-muted d-block mt-1">
                                            Ask an administrator to create an AWS user mapping for the
                                            source user before sharing.
                                        </small>
                                    )}
                                </div>
                                <button
                                    type="button"
                                    className="btn-close ms-2"
                                    aria-label="Dismiss"
                                    onClick={() => setFormError(null)}
                                ></button>
                            </div>
                        )}
                        <form onSubmit={handleCreate}>
                            <div className="row g-3">
                                {canManageAnyRule ? (
                                    /* ADMIN/SECCHAMPION: full source user selector */
                                    <div className="col-md-5">
                                        <label htmlFor="sourceUser" className="form-label">
                                            Source User (shares their AWS accounts)
                                        </label>
                                        <select
                                            id="sourceUser"
                                            className="form-select"
                                            value={sourceSelection}
                                            onChange={(e) => setSourceSelection(e.target.value)}
                                            required
                                        >
                                            <option value="">-- Select source user --</option>
                                            {users.map((user) => (
                                                <option key={encodeUserOption(user)} value={encodeUserOption(user)}>
                                                    {user.username} ({user.email}){user.isPending ? ' — pending' : ''}
                                                </option>
                                            ))}
                                        </select>
                                    </div>
                                ) : (
                                    /* VULN and other users: source is fixed to current user */
                                    <div className="col-md-5">
                                        <label className="form-label">
                                            Source User (you)
                                        </label>
                                        <input
                                            type="text"
                                            className="form-control"
                                            value={currentUser ? `${currentUser.username} (${currentUser.email})` : ''}
                                            disabled
                                        />
                                    </div>
                                )}
                                <div className="col-md-1 d-flex align-items-end justify-content-center pb-2">
                                    <i className="bi bi-arrow-right fs-4"></i>
                                </div>
                                <div className="col-md-5">
                                    <label htmlFor="targetUser" className="form-label">
                                        Target User (receives visibility)
                                    </label>
                                    {(() => {
                                        const search = targetSearch.trim().toLowerCase();
                                        // Exclude whichever user is currently chosen as the source —
                                        // for non-ADMIN users that's themselves (sourceSelection is
                                        // forced to "id:<currentUser.id>" at submit time, but is
                                        // empty in state); for ADMIN/SECCHAMPION it's whatever they
                                        // picked in the source dropdown.
                                        const effectiveSource = canManageAnyRule
                                            ? sourceSelection
                                            : (currentUser ? `id:${currentUser.id}` : '');
                                        const eligible = users.filter(u =>
                                            encodeUserOption(u) !== effectiveSource
                                        );
                                        const filtered = search
                                            ? eligible.filter(u =>
                                                u.username.toLowerCase().includes(search) ||
                                                u.email.toLowerCase().includes(search))
                                            : eligible;
                                        return (
                                            <>
                                                <input
                                                    type="search"
                                                    className="form-control form-control-sm mb-2"
                                                    placeholder="Search by username or email…"
                                                    value={targetSearch}
                                                    onChange={(e) => setTargetSearch(e.target.value)}
                                                    aria-label="Search target user"
                                                />
                                                <select
                                                    id="targetUser"
                                                    className="form-select"
                                                    value={targetSelection}
                                                    onChange={(e) => setTargetSelection(e.target.value)}
                                                    required
                                                >
                                                    <option value="">
                                                        {filtered.length === 0
                                                            ? '-- No matching users --'
                                                            : `-- Select target user${search ? ` (${filtered.length} match${filtered.length === 1 ? '' : 'es'})` : ''} --`}
                                                    </option>
                                                    {filtered.map((user) => (
                                                        <option key={encodeUserOption(user)} value={encodeUserOption(user)}>
                                                            {user.username} ({user.email}){user.isPending ? ' — pending' : ''}
                                                        </option>
                                                    ))}
                                                </select>
                                            </>
                                        );
                                    })()}
                                </div>
                                <div className="col-md-1 d-flex align-items-end">
                                    <button
                                        type="submit"
                                        className="btn btn-success"
                                        disabled={isCreating}
                                    >
                                        {isCreating ? (
                                            <span className="spinner-border spinner-border-sm me-1"></span>
                                        ) : (
                                            <i className="bi bi-check-lg me-1"></i>
                                        )}
                                        Create
                                    </button>
                                </div>
                            </div>
                        </form>
                    </div>
                </div>
            )}

            {/* Table */}
            {isLoading ? (
                <div className="text-center py-4">
                    <div className="spinner-border" role="status">
                        <span className="visually-hidden">Loading...</span>
                    </div>
                </div>
            ) : sharingRules.length === 0 ? (
                <div className="alert alert-info">
                    {hasFullViewAccess
                        ? 'No AWS account sharing rules configured. Click "Create Sharing Rule" to add one.'
                        : 'No sharing rules involve you yet. Click "Create Sharing Rule" to share your accounts with someone else.'}
                </div>
            ) : (
                <>
                    <div className="table-responsive">
                        <table className="table table-striped table-hover">
                            <thead className="table-dark">
                                <tr>
                                    {/* Always show source — non-privileged users now see rules
                                        where they're the TARGET, so the source column is their
                                        view into who shared accounts with them. */}
                                    <th>Source User</th>
                                    <th>Target User</th>
                                    <th>Shared Accounts</th>
                                    <th>Created By</th>
                                    <th>Created At</th>
                                    <th>Actions</th>
                                </tr>
                            </thead>
                            <tbody>
                                {sharingRules.map((rule) => (
                                    <tr key={rule.id}>
                                        <td>
                                            <strong>{rule.sourceUserUsername}</strong>
                                            <br />
                                            <small className="text-muted">{rule.sourceUserEmail}</small>
                                        </td>
                                        <td>
                                            <strong>{rule.targetUserUsername}</strong>
                                            <br />
                                            <small className="text-muted">{rule.targetUserEmail}</small>
                                        </td>
                                        <td>
                                            <span className="badge bg-info">{rule.sharedAwsAccountCount}</span>
                                        </td>
                                        <td>{rule.createdByUsername}</td>
                                        <td>{formatDate(rule.createdAt)}</td>
                                        <td>
                                            {(() => {
                                                // A user may delete a rule if they can manage any rule,
                                                // or they are the source user on this specific rule.
                                                const canDeleteThisRule = canManageAnyRule ||
                                                    rule.sourceUserId === currentUser?.id;
                                                if (!canDeleteThisRule) return null;
                                                return deletingId === rule.id ? (
                                                    <div className="btn-group btn-group-sm">
                                                        <button
                                                            className="btn btn-danger"
                                                            onClick={() => handleDelete(rule.id)}
                                                        >
                                                            Confirm
                                                        </button>
                                                        <button
                                                            className="btn btn-secondary"
                                                            onClick={() => setDeletingId(null)}
                                                        >
                                                            Cancel
                                                        </button>
                                                    </div>
                                                ) : (
                                                    <button
                                                        className="btn btn-outline-danger btn-sm"
                                                        onClick={() => setDeletingId(rule.id)}
                                                        title="Delete sharing rule"
                                                    >
                                                        <i className="bi bi-trash"></i>
                                                    </button>
                                                );
                                            })()}
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>

                    {/* Pagination */}
                    {totalPages > 1 && (
                        <nav aria-label="Sharing rules pagination">
                            <div className="d-flex justify-content-between align-items-center">
                                <span className="text-muted">
                                    Showing {page * pageSize + 1}-{Math.min((page + 1) * pageSize, totalElements)} of {totalElements} rules
                                </span>
                                <ul className="pagination mb-0">
                                    <li className={`page-item ${page === 0 ? 'disabled' : ''}`}>
                                        <button className="page-link" onClick={() => setPage(p => Math.max(0, p - 1))}>
                                            Previous
                                        </button>
                                    </li>
                                    {Array.from({ length: totalPages }, (_, i) => (
                                        <li key={i} className={`page-item ${page === i ? 'active' : ''}`}>
                                            <button className="page-link" onClick={() => setPage(i)}>
                                                {i + 1}
                                            </button>
                                        </li>
                                    ))}
                                    <li className={`page-item ${page >= totalPages - 1 ? 'disabled' : ''}`}>
                                        <button className="page-link" onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}>
                                            Next
                                        </button>
                                    </li>
                                </ul>
                            </div>
                        </nav>
                    )}
                </>
            )}
        </div>
    );
};

export default AwsAccountSharingManager;
