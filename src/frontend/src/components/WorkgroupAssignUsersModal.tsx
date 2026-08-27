import React, { useState, useEffect } from 'react';
import { getJson, postJson, deleteJson, ApiError } from '../utils/apiJson';
import { getEffectiveAssignedUserCount } from './workgroupAssignLogic';
import type { AssignedUser, UserRef, Workgroup, WorkgroupUser } from './workgroupTypes';

/**
 * Assign/remove workgroup members, extracted from WorkgroupManagement.tsx.
 * Owns all modal-scoped state (current members, staged additions and removals,
 * search, invite-by-email) and the save API calls; the parent supplies the
 * candidate user list and learns only "saved" / "closed" / an error message.
 *
 * Removals are applied before additions on save: if the add step fails, the
 * workgroup is still in its intended steady state for what succeeded.
 */
interface WorkgroupAssignUsersModalProps {
  workgroup: Workgroup;
  /** Candidate users (the non-admin-safe list the parent already fetched). */
  users: WorkgroupUser[];
  onClose: () => void;
  /** Called after a successful save; parent refetches and closes. */
  onSaved: () => void | Promise<void>;
  onError: (message: string) => void;
}

const WorkgroupAssignUsersModal: React.FC<WorkgroupAssignUsersModalProps> = ({
  workgroup,
  users,
  onClose,
  onSaved,
  onError,
}) => {
  const [selectedUserRefs, setSelectedUserRefs] = useState<UserRef[]>([]);
  const [userSearchTerm, setUserSearchTerm] = useState('');
  const [assignedUsers, setAssignedUsers] = useState<AssignedUser[]>([]);
  const [assignedUsersError, setAssignedUsersError] = useState<string | null>(null);
  // Pending removals: ids the user has marked × in the "Currently assigned" panel.
  // Strikethrough until Save changes — keeps the operation reversible inside the modal.
  const [userIdsToRemove, setUserIdsToRemove] = useState<number[]>([]);

  useEffect(() => {
    // Load the current membership when the dialog opens for a workgroup.
    const fetchAssigned = async () => {
      try {
        const data = await getJson<AssignedUser[]>(
          `/api/workgroups/${workgroup.id}/users`,
          'Failed to load current members'
        );
        setAssignedUsers(Array.isArray(data) ? data : []);
      } catch (err) {
        setAssignedUsersError(err instanceof Error ? err.message : 'Failed to load current members');
      }
    };
    void fetchAssigned();
  }, [workgroup.id]);

  const modalExpectedUserCount = workgroup.userCount;
  const modalEffectiveUserCount = getEffectiveAssignedUserCount(assignedUsers, userIdsToRemove);
  const hasUserCountMismatch = !assignedUsersError && modalExpectedUserCount !== modalEffectiveUserCount;

  // Flip a member row between kept and pending-removal (applied on Save).
  const toggleUserRemoval = (userId: number) => {
    setUserIdsToRemove(prev =>
      prev.includes(userId) ? prev.filter(id => id !== userId) : [...prev, userId]
    );
  };

  // Stage or unstage a candidate: existing users match by id, invites by email.
  const toggleUserSelection = (user: WorkgroupUser) => {
    setSelectedUserRefs(prev => {
      const matches = (r: UserRef) =>
        (user.id != null && r.id === user.id) ||
        (user.id == null && r.email.toLowerCase() === user.email.toLowerCase());
      if (prev.some(matches)) {
        return prev.filter(r => !matches(r));
      }
      return user.id != null
        ? [...prev, { id: user.id, email: user.email }]
        : [...prev, { email: user.email }];
    });
  };

  // Apply the staged changes: removals first, then additions (see component doc).
  const submitAssignUsers = async () => {
    if (selectedUserRefs.length === 0 && userIdsToRemove.length === 0) {
      onError('Select at least one user to add or remove');
      return;
    }

    try {
      if (userIdsToRemove.length > 0) {
        await deleteJson(
          `/api/workgroups/${workgroup.id}/users`,
          { userIds: userIdsToRemove },
          'Failed to remove users'
        );
      }

      if (selectedUserRefs.length > 0) {
        await postJson(
          `/api/workgroups/${workgroup.id}/users`,
          { userRefs: selectedUserRefs },
          'Failed to assign users'
        );
      }

      await onSaved();
    } catch (err) {
      onError(err instanceof ApiError || err instanceof Error ? err.message : 'An error occurred');
    }
  };

  return (
    <div className="modal show d-block" tabIndex={-1} style={{ backgroundColor: 'var(--scand-overlay)' }}>
      <div className="modal-dialog modal-lg">
        <div className="modal-content">
          <div className="modal-header">
            <h5 className="modal-title">Assign Users to {workgroup.name}</h5>
            <button type="button" className="btn-close" onClick={onClose}></button>
          </div>
          <div className="modal-body">
            {/* Currently assigned members — mirrors the Manage Assets dialog. The × marks
                a row for removal but does not call the API until "Save changes" is pressed. */}
            <div className="mb-3 p-2 border rounded bg-light">
              <div className="d-flex justify-content-between align-items-center mb-1">
                <strong>Currently assigned ({assignedUsers.length})</strong>
                {userIdsToRemove.length > 0 && (
                  <span className="badge bg-warning text-dark">
                    {userIdsToRemove.length} pending removal
                  </span>
                )}
              </div>
              {hasUserCountMismatch && (
                <div className="alert alert-warning py-2 px-2 my-2 small mb-0" role="alert">
                  <i className="bi bi-exclamation-triangle me-1"></i>
                  Count mismatch: table shows <strong>{modalExpectedUserCount}</strong>, but current members in this
                  dialog are <strong>{modalEffectiveUserCount}</strong>.
                </div>
              )}
              {assignedUsersError && (
                <div className="alert alert-warning py-1 px-2 my-1 small mb-0" role="alert">
                  {assignedUsersError}
                </div>
              )}
              {assignedUsers.length === 0 && !assignedUsersError && (
                <div className="text-muted small fst-italic">No users assigned yet.</div>
              )}
              {assignedUsers.length > 0 && (
                <div style={{ maxHeight: '180px', overflowY: 'auto' }}>
                  {assignedUsers.map(u => {
                    const pending = userIdsToRemove.includes(u.id);
                    return (
                      <div
                        key={u.id}
                        className="d-flex justify-content-between align-items-center py-1 px-1 border-bottom"
                      >
                        <span
                          style={{
                            textDecoration: pending ? 'line-through' : 'none',
                            color: pending ? 'var(--scand-text-secondary)' : 'inherit',
                          }}
                        >
                          <i className="bi bi-person-check me-1"></i>
                          <strong>{u.username}</strong>
                          <span className="text-muted small"> ({u.email})</span>
                        </span>
                        <button
                          type="button"
                          className={`btn btn-sm ${pending ? 'btn-outline-secondary' : 'btn-outline-danger'}`}
                          onClick={() => toggleUserRemoval(u.id)}
                          title={pending ? 'Undo removal' : 'Mark for removal'}
                        >
                          {pending ? 'Undo' : '× Remove'}
                        </button>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>

            <p className="text-muted mb-2">Select users to add to this workgroup:</p>

            {/* Search input — mirrors the asset-assign modal, debounced via React's controlled-input render cycle. */}
            <div className="mb-3">
              <div className="input-group">
                <span className="input-group-text">
                  <i className="bi bi-search"></i>
                </span>
                <input
                  type="text"
                  className="form-control"
                  placeholder="Search existing users — or type a full email to invite someone new"
                  value={userSearchTerm}
                  onChange={(e) => setUserSearchTerm(e.target.value)}
                  autoFocus
                />
                {userSearchTerm && (
                  <button
                    type="button"
                    className="btn btn-outline-secondary"
                    onClick={() => setUserSearchTerm('')}
                    title="Clear search"
                  >
                    <i className="bi bi-x-lg"></i>
                  </button>
                )}
              </div>
              {/* Persistent helper — many users never realize the search box doubles as an invite-by-email
                  field. State the affordance up front so they don't have to discover it. */}
              {(() => {
                const caller = (typeof window !== 'undefined' ? window.currentUser : null) || null;
                const dom = caller?.email?.split('@')[1] || 'your-domain.com';
                const isAdmin = (caller?.roles || []).includes('ADMIN');
                return (
                  <div className="form-text mt-1">
                    <i className="bi bi-lightbulb me-1"></i>
                    Not in the list? Type a full email like <code>name@{dom}</code> to invite a new user.
                    {!isAdmin && (
                      <> New users must be at <code>@{dom}</code> (your domain).</>
                    )}
                  </div>
                );
              })()}
            </div>

            {(() => {
              const assignedIds = new Set(assignedUsers.map(u => u.id));
              const assignedEmails = new Set(assignedUsers.map(u => u.email.toLowerCase()));
              const termRaw = userSearchTerm.trim();
              const term = termRaw.toLowerCase();
              const filtered = users.filter(u => {
                if (!term) return true;
                return (u.username || '').toLowerCase().includes(term)
                    || (u.email || '').toLowerCase().includes(term);
              });
              // Sort: already-assigned first (so the admin sees who is in the group at a glance), then alpha by username.
              const sorted = [...filtered].sort((a, b) => {
                const aAssigned = (a.id != null && assignedIds.has(a.id)) || assignedEmails.has(a.email.toLowerCase());
                const bAssigned = (b.id != null && assignedIds.has(b.id)) || assignedEmails.has(b.email.toLowerCase());
                if (aAssigned !== bAssigned) return aAssigned ? -1 : 1;
                return (a.username || a.email).localeCompare(b.username || b.email);
              });

              // Invite-by-email affordance: visible when the search term parses as an
              // email that isn't already in the user list. Domain-restricted on the
              // client (UX), enforced again on the server (security).
              const looksLikeEmail = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(termRaw);
              const matchesExistingEmail = users.some(u => u.email.toLowerCase() === term);
              const caller = (typeof window !== 'undefined' ? window.currentUser : null) || null;
              const callerEmail = caller?.email || '';
              const callerDomain = callerEmail.split('@')[1]?.toLowerCase() || '';
              const callerIsAdmin = (caller?.roles || []).includes('ADMIN');
              const inviteDomain = looksLikeEmail ? (termRaw.split('@')[1]?.toLowerCase() || '') : '';
              const inviteShouldShow = looksLikeEmail && !matchesExistingEmail;
              const inviteAllowed = inviteShouldShow && (callerIsAdmin || (!!callerDomain && callerDomain === inviteDomain));
              const inviteChecked = inviteShouldShow && selectedUserRefs.some(r => r.email.toLowerCase() === term);

              const inviteRow = inviteShouldShow ? (
                <label
                  key={`invite:${term}`}
                  className={`list-group-item list-group-item-action ${inviteAllowed ? '' : 'list-group-item-light text-muted'}`}
                  title={
                    inviteAllowed
                      ? 'Invite this email as a new pending user'
                      : `New users must share your email domain (@${callerDomain || '?'})`
                  }
                >
                  <input
                    type="checkbox"
                    className="form-check-input me-2"
                    checked={inviteChecked}
                    disabled={!inviteAllowed}
                    onChange={() => toggleUserSelection({ id: null, username: termRaw.split('@')[0], email: termRaw, isPending: true })}
                  />
                  <i className={`bi ${inviteAllowed ? 'bi-person-plus' : 'bi-shield-lock'} me-1`}></i>
                  <strong>Invite new user:</strong> {termRaw}
                  <span className={`badge ms-2 ${inviteAllowed ? 'scand-success' : 'scand-critical'}`}>
                    {inviteAllowed ? 'new pending' : 'wrong domain'}
                  </span>
                </label>
              ) : null;

              if (sorted.length === 0 && !inviteRow) {
                // No existing user match AND not a parseable email yet. Tell the
                // user exactly what to type next so they don't get stuck — the
                // common failure mode is typing a username and giving up.
                const hasAtSign = termRaw.includes('@');
                return (
                  <div className="text-center text-muted py-4 border rounded bg-light">
                    <div className="mb-2">
                      <i className="bi bi-search me-1"></i>
                      No users match "<strong>{userSearchTerm}</strong>"
                    </div>
                    <div className="small">
                      <i className="bi bi-person-plus me-1"></i>
                      {hasAtSign
                        ? <>Keep typing the rest of the email address to invite a new user.</>
                        : <>To invite someone new, type their <strong>complete email address</strong> here.</>}
                    </div>
                  </div>
                );
              }

              return (
                <div className="list-group" style={{ maxHeight: '400px', overflowY: 'auto' }}>
                  {/* Invite row first — putting it at the top of a possibly-long list
                      guarantees the user sees it without scrolling. */}
                  {inviteRow}
                  {sorted.map(user => {
                    const isAssigned = (user.id != null && assignedIds.has(user.id))
                      || assignedEmails.has(user.email.toLowerCase());
                    const checked = selectedUserRefs.some(r =>
                      (user.id != null && r.id === user.id) ||
                      (user.id == null && r.email.toLowerCase() === user.email.toLowerCase())
                    );
                    return (
                      <label
                        key={user.id ?? `pending:${user.email}`}
                        className={`list-group-item list-group-item-action ${isAssigned ? 'list-group-item-light' : ''}`}
                        title={isAssigned ? 'Already a member of this workgroup' : undefined}
                      >
                        <input
                          type="checkbox"
                          className="form-check-input me-2"
                          checked={isAssigned || checked}
                          disabled={isAssigned}
                          onChange={() => toggleUserSelection(user)}
                        />
                        <strong>{user.username}</strong> ({user.email})
                        {isAssigned && (
                          <span className="badge bg-success ms-2">
                            <i className="bi bi-check-circle me-1"></i>assigned
                          </span>
                        )}
                        {user.isPending && (
                          <span
                            className="badge bg-warning text-dark ms-2"
                            title="This email is known via AWS / domain mapping but has never logged in. Selecting it will create an account placeholder."
                          >
                            pending
                          </span>
                        )}
                      </label>
                    );
                  })}
                </div>
              );
            })()}

            {(() => {
              // Show staged "new pending" invites separately from existing-user picks so
              // the admin can audit them even after clearing the search box.
              const knownEmails = new Set(users.map(u => u.email.toLowerCase()));
              const stagedInvites = selectedUserRefs.filter(
                r => r.id == null && !knownEmails.has(r.email.toLowerCase())
              );
              if (stagedInvites.length === 0) return null;
              return (
                <div className="mt-3 p-2 border rounded border-success-subtle bg-success-subtle">
                  <div className="d-flex align-items-center mb-1">
                    <i className="bi bi-person-plus me-1 text-success"></i>
                    <strong className="small">Will create {stagedInvites.length} new user{stagedInvites.length !== 1 ? 's' : ''}</strong>
                  </div>
                  <div className="d-flex flex-wrap gap-1">
                    {stagedInvites.map(r => (
                      <span key={`staged:${r.email}`} className="badge scand-success">
                        {r.email}
                        <button
                          type="button"
                          className="btn-close btn-close-sm ms-2"
                          style={{ fontSize: '0.6rem' }}
                          aria-label={`Cancel invite for ${r.email}`}
                          onClick={() => toggleUserSelection({ id: null, username: r.email.split('@')[0], email: r.email, isPending: true })}
                        ></button>
                      </span>
                    ))}
                  </div>
                </div>
              );
            })()}

            <p className="mt-3 text-muted small">
              {selectedUserRefs.length} new user(s) selected · showing {users.length} total
            </p>
          </div>
          <div className="modal-footer">
            <button type="button" className="btn btn-secondary" onClick={onClose}>
              Cancel
            </button>
            <button
              type="button"
              className="btn btn-primary"
              onClick={submitAssignUsers}
              disabled={selectedUserRefs.length === 0 && userIdsToRemove.length === 0}
            >
              Save changes
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default WorkgroupAssignUsersModal;
