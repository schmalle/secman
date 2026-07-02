import React, { useState, useEffect, useCallback } from 'react';
import { formatServerDateTime } from '../utils/dateUtils';
import { authenticatedGet, getUser, hasRole } from '../utils/auth';
import {
    listSharingRules,
    createSharingRule,
    updateSharingRule,
    deleteSharingRule,
    listSourceAccounts,
    type AwsAccountSharing,
    type SharingUser,
} from '../services/awsAccountSharingService';

// Dropdown selection is encoded as "id:<number>" for active users or
// "email:<addr>" for pending users (those recorded in UserMapping but never
// logged in). This keeps a single string value on the <select> while
// preserving which side of the API contract to use at submit time.
const encodeUserOption = (u: SharingUser): string =>
    u.id != null ? `id:${u.id}` : `email:${u.email}`;

const decodeUserOption = (value: string): { id?: number; email?: string } => {
    if (value.startsWith('id:')) return { id: Number(value.slice(3)) };
    if (value.startsWith('email:')) return { email: value.slice(6) };
    return {};
};

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
    // Radio mode for the target column. 'existing' uses the dropdown
    // (legacy behavior); 'invite' uses a free-text email box scoped to
    // the inviter's own email domain.
    const [targetMode, setTargetMode] = useState<'existing' | 'invite'>('existing');
    const [inviteEmail, setInviteEmail] = useState<string>('');
    // Encoded "id:<n>" or "email:<addr>" — see encodeUserOption.
    const [sourceSelection, setSourceSelection] = useState<string>('');
    const [sourceSearch, setSourceSearch] = useState<string>('');
    const [targetSelection, setTargetSelection] = useState<string>('');
    const [targetSearch, setTargetSearch] = useState<string>('');
    const [isCreating, setIsCreating] = useState(false);
    // Inline form-submission error. Sticky (no auto-dismiss) so it survives
    // an open <select> overlay covering the page-top alert region.
    const [formError, setFormError] = useState<string | null>(null);
    // Per-account picker state. shareAll === true means submit no
    // awsAccountIds; otherwise submit the selectedAccountIds set.
    const [sourceAccounts, setSourceAccounts] = useState<string[]>([]);
    const [sourceAccountsLoading, setSourceAccountsLoading] = useState(false);
    const [shareAll, setShareAll] = useState(true);
    const [selectedAccountIds, setSelectedAccountIds] = useState<Set<string>>(new Set());

    // Edit modal state. Keyed by sharing id; null = closed.
    const [editingRule, setEditingRule] = useState<AwsAccountSharing | null>(null);
    const [editShareAll, setEditShareAll] = useState(true);
    const [editSelected, setEditSelected] = useState<Set<string>>(new Set());
    const [editAccounts, setEditAccounts] = useState<string[]>([]);
    const [editAccountsLoading, setEditAccountsLoading] = useState(false);
    const [isSavingEdit, setIsSavingEdit] = useState(false);
    const [editError, setEditError] = useState<string | null>(null);

    // Delete confirmation
    const [deletingId, setDeletingId] = useState<number | null>(null);

    useEffect(() => {
        setHasFullViewAccess(checkHasFullViewAccess());
        setCanManageAnyRule(checkCanManageAnyRule());
        const user = getUser();
        setCurrentUser(user);
    }, []);

    // Domain extracted from the logged-in user's email — drives the
    // helper text and the client-side domain match. Backend remains
    // authoritative on this check.
    const callerDomain = (currentUser?.email ?? '').split('@')[1]?.toLowerCase() ?? '';

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

    // Keep the source <select> aligned with its search filter too. Clearing a
    // now-hidden source also resets the account picker through the source effect.
    useEffect(() => {
        if (!sourceSelection) return;
        const search = sourceSearch.trim().toLowerCase();
        if (!search) return;
        const stillVisible = users.some(u => {
            if (encodeUserOption(u) !== sourceSelection) return false;
            return u.username.toLowerCase().includes(search) ||
                u.email.toLowerCase().includes(search);
        });
        if (!stillVisible) setSourceSelection('');
    }, [sourceSearch, sourceSelection, users]);

    // When the effective source user changes, re-fetch its account list and
    // reset the picker. Until accounts arrive, lock to share-all.
    useEffect(() => {
        if (!showCreateForm) return;
        const effectiveSource = canManageAnyRule
            ? sourceSelection
            : (currentUser ? `id:${currentUser.id}` : '');
        if (!effectiveSource) {
            setSourceAccounts([]);
            setSelectedAccountIds(new Set());
            setShareAll(true);
            return;
        }
        const decoded = decodeUserOption(effectiveSource);
        setSourceAccountsLoading(true);
        listSourceAccounts({ userId: decoded.id ?? null, email: decoded.email ?? null })
            .then(rows => {
                setSourceAccounts(rows.map(r => r.awsAccountId));
                setSelectedAccountIds(new Set());
                setShareAll(true);
            })
            .catch(() => {
                setSourceAccounts([]);
                setSelectedAccountIds(new Set());
                setShareAll(true);
            })
            .finally(() => setSourceAccountsLoading(false));
    }, [showCreateForm, sourceSelection, canManageAnyRule, currentUser]);

    const toggleSelectedAccount = (id: string) => {
        setSelectedAccountIds(prev => {
            const next = new Set(prev);
            if (next.has(id)) next.delete(id); else next.add(id);
            return next;
        });
    };

    const toggleEditSelectedAccount = (id: string) => {
        setEditSelected(prev => {
            const next = new Set(prev);
            if (next.has(id)) next.delete(id); else next.add(id);
            return next;
        });
    };

    const handleCreate = async (e: React.FormEvent) => {
        e.preventDefault();
        setFormError(null);

        // Non-privileged users never pick a source — it's forced to self.
        const effectiveSourceSelection = canManageAnyRule
            ? sourceSelection
            : (currentUser ? `id:${currentUser.id}` : '');

        if (!effectiveSourceSelection) {
            setFormError(canManageAnyRule ? 'Please select a source user' : 'Cannot determine your user record');
            return;
        }

        if (targetMode === 'existing' && !targetSelection) {
            setFormError('Please select a target user');
            return;
        }
        if (targetMode === 'invite') {
            const e = inviteEmail.trim();
            const atCount = (e.match(/@/g) ?? []).length;
            if (atCount !== 1 || e.startsWith('@') || e.endsWith('@')) {
                setFormError('Please enter a valid email address');
                return;
            }
            const domain = e.split('@')[1].toLowerCase();
            if (!callerDomain || domain !== callerDomain) {
                setFormError(`Email must end in @${callerDomain || 'your domain'}`);
                return;
            }
            if (currentUser && e.toLowerCase() === currentUser.email.toLowerCase()) {
                setFormError('You cannot share with yourself');
                return;
            }
        }
        if (targetMode === 'existing' && effectiveSourceSelection === targetSelection) {
            setFormError('Source and target user cannot be the same');
            return;
        }
        if (!shareAll && selectedAccountIds.size === 0) {
            setFormError('Select at least one AWS account, or switch back to "Share all".');
            return;
        }

        const src = decodeUserOption(effectiveSourceSelection);

        setIsCreating(true);
        try {
            const result = await createSharingRule({
                sourceUserId: src.id ?? null,
                sourceUserEmail: src.email ?? null,
                targetUserId: targetMode === 'existing' ? (decodeUserOption(targetSelection).id ?? null) : null,
                targetUserEmail: targetMode === 'existing'
                    ? (decodeUserOption(targetSelection).email ?? null)
                    : inviteEmail.trim(),
                inviteByEmail: targetMode === 'invite',
                awsAccountIds: shareAll ? null : Array.from(selectedAccountIds),
            });
            const scopeMsg = result.shareAllAccounts
                ? `${result.sharedAwsAccountCount} accounts (all)`
                : `${result.sharedAwsAccountCount} of ${sourceAccounts.length} accounts`;
            const inviteSuffix = targetMode === 'invite' ? ' (account created, login link emailed)' : '';
            showSuccess(
                `Sharing rule created: ${result.sourceUserEmail} → ${result.targetUserEmail} (${scopeMsg})${inviteSuffix}`
            );
            setShowCreateForm(false);
            setSourceSelection('');
            setSourceSearch('');
            setTargetSelection('');
            setTargetSearch('');
            setTargetMode('existing');
            setInviteEmail('');
            setShareAll(true);
            setSelectedAccountIds(new Set());
            setSourceAccounts([]);
            setFormError(null);
            fetchSharingRules();
            fetchUsers(); // Pending users / new invitees may have just materialized — refresh dropdowns.
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

    // Open the edit modal for a rule, prefilling its current selection and
    // loading the source user's full account list to render the picker.
    const openEditModal = (rule: AwsAccountSharing) => {
        setEditingRule(rule);
        setEditError(null);
        setEditShareAll(rule.shareAllAccounts);
        setEditSelected(new Set(rule.selectedAwsAccountIds));
        setEditAccounts([]);
        setEditAccountsLoading(true);
        listSourceAccounts({ userId: rule.sourceUserId })
            .then(rows => setEditAccounts(rows.map(r => r.awsAccountId)))
            .catch(err => setEditError(err.message || 'Failed to load source accounts'))
            .finally(() => setEditAccountsLoading(false));
    };

    const closeEditModal = () => {
        setEditingRule(null);
        setEditAccounts([]);
        setEditSelected(new Set());
        setEditError(null);
    };

    const handleSaveEdit = async () => {
        if (!editingRule) return;
        setEditError(null);
        if (!editShareAll && editSelected.size === 0) {
            setEditError('Select at least one account, or switch back to "Share all".');
            return;
        }
        setIsSavingEdit(true);
        try {
            const result = await updateSharingRule(editingRule.id, {
                awsAccountIds: editShareAll ? null : Array.from(editSelected),
            });
            const scopeMsg = result.shareAllAccounts
                ? `${result.sharedAwsAccountCount} accounts (all)`
                : `${result.sharedAwsAccountCount} of ${editAccounts.length} accounts`;
            showSuccess(`Sharing rule updated: ${result.sourceUserEmail} → ${result.targetUserEmail} (${scopeMsg})`);
            closeEditModal();
            fetchSharingRules();
        } catch (err: any) {
            setEditError(err.message || 'Failed to update sharing rule');
        } finally {
            setIsSavingEdit(false);
        }
    };

    const formatDate = (dateStr: string) => {
        try {
            return formatServerDateTime(dateStr);
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
                            // and reset all create-form local state so a fresh
                            // re-open starts clean.
                            setFormError(null);
                            setTargetMode('existing');
                            setInviteEmail('');
                            setSourceSearch('');
                            setTargetSearch('');
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
                    <>Share AWS account visibility between users. By default a rule covers ALL of the source user's
                    AWS accounts; you can also share a specific subset of accounts. Sharing is directional and non-transitive.</>
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
                                        {(() => {
                                            const search = sourceSearch.trim().toLowerCase();
                                            const filtered = search
                                                ? users.filter(u =>
                                                    u.username.toLowerCase().includes(search) ||
                                                    u.email.toLowerCase().includes(search))
                                                : users;
                                            return (
                                                <>
                                                    <input
                                                        type="search"
                                                        className="form-control form-control-sm mb-2"
                                                        placeholder="Search by username or email…"
                                                        value={sourceSearch}
                                                        onChange={(e) => setSourceSearch(e.target.value)}
                                                        aria-label="Search source user"
                                                    />
                                                    <select
                                                        id="sourceUser"
                                                        className="form-select"
                                                        value={sourceSelection}
                                                        onChange={(e) => setSourceSelection(e.target.value)}
                                                        required
                                                    >
                                                        <option value="">
                                                            {filtered.length === 0
                                                                ? '-- No matching users --'
                                                                : `-- Select source user${search ? ` (${filtered.length} match${filtered.length === 1 ? '' : 'es'})` : ''} --`}
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
                                    <label className="form-label">Target User (receives visibility)</label>
                                    <div className="btn-group btn-group-sm mb-2" role="group" aria-label="Target user mode">
                                        <input
                                            type="radio"
                                            className="btn-check"
                                            name="targetMode"
                                            id="targetMode-existing"
                                            checked={targetMode === 'existing'}
                                            onChange={() => {
                                                setTargetMode('existing');
                                                setInviteEmail('');
                                                setFormError(null);
                                            }}
                                        />
                                        <label className="btn btn-outline-secondary" htmlFor="targetMode-existing">
                                            Pick existing user
                                        </label>
                                        <input
                                            type="radio"
                                            className="btn-check"
                                            name="targetMode"
                                            id="targetMode-invite"
                                            checked={targetMode === 'invite'}
                                            onChange={() => {
                                                setTargetMode('invite');
                                                setTargetSelection('');
                                                setTargetSearch('');
                                                setFormError(null);
                                            }}
                                            disabled={!callerDomain}
                                        />
                                        <label className="btn btn-outline-secondary" htmlFor="targetMode-invite">
                                            Invite by email
                                        </label>
                                    </div>

                                    {targetMode === 'existing' ? (
                                        (() => {
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
                                                        required={targetMode === 'existing'}
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
                                        })()
                                    ) : (
                                        <>
                                            <input
                                                type="email"
                                                id="inviteEmail"
                                                className="form-control"
                                                placeholder={`name@${callerDomain || 'yourdomain'}`}
                                                value={inviteEmail}
                                                onChange={(e) => setInviteEmail(e.target.value)}
                                                required={targetMode === 'invite'}
                                                aria-describedby="inviteEmailHelp"
                                            />
                                            <small id="inviteEmailHelp" className="form-text text-muted d-block mt-1">
                                                Email must end in <code>@{callerDomain || '…'}</code>.
                                                A SecMan account will be created with <code>USER + VULN</code> roles
                                                and the invitee will be emailed a login link.
                                            </small>
                                        </>
                                    )}
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

                            {/* Per-account picker. Hidden until source has been resolved
                                and its accounts loaded — there's nothing useful to show
                                otherwise. The default ("share all") matches legacy behavior. */}
                            <div className="row mt-3">
                                <div className="col-12">
                                    <label className="form-label">AWS Accounts to share</label>
                                    <div className="border rounded p-3 bg-light-subtle">
                                        <div className="form-check form-check-inline mb-2">
                                            <input
                                                className="form-check-input"
                                                type="radio"
                                                id="scopeAll"
                                                checked={shareAll}
                                                onChange={() => setShareAll(true)}
                                            />
                                            <label className="form-check-label" htmlFor="scopeAll">
                                                Share <strong>all</strong> of the source user's accounts
                                                <small className="text-muted ms-2">(also auto-includes future accounts)</small>
                                            </label>
                                        </div>
                                        <div className="form-check form-check-inline mb-2">
                                            <input
                                                className="form-check-input"
                                                type="radio"
                                                id="scopeSelected"
                                                checked={!shareAll}
                                                onChange={() => setShareAll(false)}
                                                disabled={sourceAccounts.length === 0}
                                            />
                                            <label className="form-check-label" htmlFor="scopeSelected">
                                                Share <strong>selected</strong> accounts only
                                            </label>
                                        </div>

                                        {sourceAccountsLoading && (
                                            <div className="text-muted small">
                                                <span className="spinner-border spinner-border-sm me-1"></span>
                                                Loading source's AWS accounts…
                                            </div>
                                        )}
                                        {!sourceAccountsLoading && sourceAccounts.length === 0 && (
                                            <div className="text-muted small">
                                                Pick a source user above to see available accounts.
                                            </div>
                                        )}
                                        {!sourceAccountsLoading && sourceAccounts.length > 0 && !shareAll && (
                                            <div className="d-flex flex-wrap gap-1 mb-2">
                                                <button
                                                    type="button"
                                                    className="btn btn-sm btn-outline-secondary"
                                                    onClick={() => setSelectedAccountIds(new Set(sourceAccounts))}
                                                >
                                                    Select all
                                                </button>
                                                <button
                                                    type="button"
                                                    className="btn btn-sm btn-outline-secondary"
                                                    onClick={() => setSelectedAccountIds(new Set())}
                                                >
                                                    Clear
                                                </button>
                                                <span className="ms-2 align-self-center text-muted small">
                                                    {selectedAccountIds.size} of {sourceAccounts.length} selected
                                                </span>
                                            </div>
                                        )}
                                        {!sourceAccountsLoading && sourceAccounts.length > 0 && (
                                            <div className="row g-1" style={{ maxHeight: 220, overflowY: 'auto' }}>
                                                {sourceAccounts.map(acc => (
                                                    <div className="col-md-4 col-sm-6" key={acc}>
                                                        <div className="form-check">
                                                            <input
                                                                className="form-check-input"
                                                                type="checkbox"
                                                                id={`acc-${acc}`}
                                                                checked={shareAll || selectedAccountIds.has(acc)}
                                                                disabled={shareAll}
                                                                onChange={() => toggleSelectedAccount(acc)}
                                                            />
                                                            <label className="form-check-label font-monospace small" htmlFor={`acc-${acc}`}>
                                                                {acc}
                                                            </label>
                                                        </div>
                                                    </div>
                                                ))}
                                            </div>
                                        )}
                                    </div>
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
                                {sharingRules.map((rule) => {
                                    // A user may edit/delete a rule if they can manage any rule,
                                    // or they are the source user on this specific rule.
                                    const canManageThisRule = canManageAnyRule ||
                                        rule.sourceUserId === currentUser?.id;
                                    return (
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
                                            <span className={`badge ${rule.shareAllAccounts ? 'bg-info' : 'bg-warning text-dark'}`}>
                                                {rule.sharedAwsAccountCount}
                                            </span>
                                            <small className="text-muted ms-2">
                                                {rule.shareAllAccounts ? 'all' : 'selected'}
                                            </small>
                                        </td>
                                        <td>{rule.createdByUsername}</td>
                                        <td>{formatDate(rule.createdAt)}</td>
                                        <td>
                                            {!canManageThisRule ? null : deletingId === rule.id ? (
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
                                                <div className="btn-group btn-group-sm">
                                                    <button
                                                        className="btn btn-outline-primary btn-sm"
                                                        onClick={() => openEditModal(rule)}
                                                        title="Edit shared accounts"
                                                    >
                                                        <i className="bi bi-pencil"></i>
                                                    </button>
                                                    <button
                                                        className="btn btn-outline-danger btn-sm"
                                                        onClick={() => setDeletingId(rule.id)}
                                                        title="Delete sharing rule"
                                                    >
                                                        <i className="bi bi-trash"></i>
                                                    </button>
                                                </div>
                                            )}
                                        </td>
                                    </tr>
                                );})}
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

            {/* Edit Modal */}
            {editingRule && (
                <div className="modal show d-block" tabIndex={-1} role="dialog" style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
                    <div className="modal-dialog modal-lg" role="document">
                        <div className="modal-content">
                            <div className="modal-header">
                                <h5 className="modal-title">
                                    Edit shared accounts: <code>{editingRule.sourceUserEmail}</code> → <code>{editingRule.targetUserEmail}</code>
                                </h5>
                                <button type="button" className="btn-close" onClick={closeEditModal} aria-label="Close"></button>
                            </div>
                            <div className="modal-body">
                                {editError && (
                                    <div className="alert alert-danger" role="alert">{editError}</div>
                                )}
                                <div className="form-check mb-2">
                                    <input
                                        className="form-check-input"
                                        type="radio"
                                        id="editScopeAll"
                                        checked={editShareAll}
                                        onChange={() => setEditShareAll(true)}
                                    />
                                    <label className="form-check-label" htmlFor="editScopeAll">
                                        Share <strong>all</strong> of the source user's accounts
                                        <small className="text-muted ms-2">(auto-includes future accounts)</small>
                                    </label>
                                </div>
                                <div className="form-check mb-2">
                                    <input
                                        className="form-check-input"
                                        type="radio"
                                        id="editScopeSelected"
                                        checked={!editShareAll}
                                        onChange={() => setEditShareAll(false)}
                                        disabled={editAccounts.length === 0}
                                    />
                                    <label className="form-check-label" htmlFor="editScopeSelected">
                                        Share <strong>selected</strong> accounts only
                                    </label>
                                </div>

                                {editAccountsLoading && (
                                    <div className="text-muted small">
                                        <span className="spinner-border spinner-border-sm me-1"></span>
                                        Loading source's AWS accounts…
                                    </div>
                                )}
                                {!editAccountsLoading && editAccounts.length > 0 && !editShareAll && (
                                    <div className="d-flex flex-wrap gap-1 mb-2">
                                        <button
                                            type="button"
                                            className="btn btn-sm btn-outline-secondary"
                                            onClick={() => setEditSelected(new Set(editAccounts))}
                                        >
                                            Select all
                                        </button>
                                        <button
                                            type="button"
                                            className="btn btn-sm btn-outline-secondary"
                                            onClick={() => setEditSelected(new Set())}
                                        >
                                            Clear
                                        </button>
                                        <span className="ms-2 align-self-center text-muted small">
                                            {editSelected.size} of {editAccounts.length} selected
                                        </span>
                                    </div>
                                )}
                                {!editAccountsLoading && editAccounts.length > 0 && (
                                    <div className="row g-1" style={{ maxHeight: 320, overflowY: 'auto' }}>
                                        {editAccounts.map(acc => (
                                            <div className="col-md-4 col-sm-6" key={acc}>
                                                <div className="form-check">
                                                    <input
                                                        className="form-check-input"
                                                        type="checkbox"
                                                        id={`edit-acc-${acc}`}
                                                        checked={editShareAll || editSelected.has(acc)}
                                                        disabled={editShareAll}
                                                        onChange={() => toggleEditSelectedAccount(acc)}
                                                    />
                                                    <label className="form-check-label font-monospace small" htmlFor={`edit-acc-${acc}`}>
                                                        {acc}
                                                    </label>
                                                </div>
                                            </div>
                                        ))}
                                    </div>
                                )}
                            </div>
                            <div className="modal-footer">
                                <button type="button" className="btn btn-secondary" onClick={closeEditModal} disabled={isSavingEdit}>
                                    Cancel
                                </button>
                                <button type="button" className="btn btn-primary" onClick={handleSaveEdit} disabled={isSavingEdit}>
                                    {isSavingEdit && <span className="spinner-border spinner-border-sm me-1"></span>}
                                    Save changes
                                </button>
                            </div>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};

export default AwsAccountSharingManager;
