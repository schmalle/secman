import React, { useState, useEffect } from 'react';
import { getJson, ApiError, deleteJson, putJson } from '../utils/apiJson';
import WorkgroupAccountsModal from './WorkgroupAccountsModal';
import WorkgroupDomainsModal from './WorkgroupDomainsModal';
import WorkgroupFormModal from './WorkgroupFormModal';
import WorkgroupAssignUsersModal from './WorkgroupAssignUsersModal';
import WorkgroupAssignAssetsModal from './WorkgroupAssignAssetsModal';
import { isAwsWorkgroup } from '../services/workgroupApi';
import { formatServerDate } from '../utils/dateUtils';
import { useClientHasRole } from '../utils/useClientAuth';
import { scrollContainerStyle, stickyHeaderCellStyle } from './scrollableTableStyles';
import type { Workgroup, WorkgroupAsset, WorkgroupUser } from './workgroupTypes';

interface WorkgroupManagementProps {
  /** When false (default), workgroups named "AWS-…" are hidden from the table. */
  showAwsWorkgroups?: boolean;
}

/**
 * Workgroup admin screen: the table plus launcher state for the five dialogs.
 * Each dialog (create/edit form, assign users, manage assets, AWS accounts,
 * AD domains) is its own component owning its modal-scoped state; this parent
 * fetches the shared lists, renders the table, and refetches after saves.
 */
const WorkgroupManagement: React.FC<WorkgroupManagementProps> = ({ showAwsWorkgroups = false }) => {
  const [workgroups, setWorkgroups] = useState<Workgroup[]>([]);
  const [users, setUsers] = useState<WorkgroupUser[]>([]);
  const [assets, setAssets] = useState<WorkgroupAsset[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [editingWorkgroup, setEditingWorkgroup] = useState<Workgroup | null>(null);
  const [assignUsersWorkgroup, setAssignUsersWorkgroup] = useState<Workgroup | null>(null);
  const [assignAssetsWorkgroup, setAssignAssetsWorkgroup] = useState<Workgroup | null>(null);
  const [accountsModalState, setAccountsModalState] = useState<{
    isOpen: boolean;
    workgroupId: number | null;
    workgroupName: string;
  }>({ isOpen: false, workgroupId: null, workgroupName: '' });
  const [domainsModalState, setDomainsModalState] = useState<{
    isOpen: boolean;
    workgroupId: number | null;
    workgroupName: string;
  }>({ isOpen: false, workgroupId: null, workgroupName: '' });
  const canChangeStatus = useClientHasRole(['ADMIN', 'SECCHAMPION']);

  useEffect(() => {
    fetchWorkgroups();
    fetchUsers();
    fetchAssets();
  }, []);

  const fetchWorkgroups = async () => {
    try {
      setWorkgroups(await getJson<Workgroup[]>('/api/workgroups', 'Failed to fetch workgroups'));
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    } finally {
      setLoading(false);
    }
  };

  const fetchUsers = async () => {
    // Self-service workgroup management is open to non-ADMIN members; the canonical
    // /api/users list is ADMIN-only. The aws-account-sharing endpoint is the established
    // non-admin-safe user list (same {id, username, email, isPending} shape).
    // See AwsAccountSharingController.listUsersForSharing — deprecated pending a generic
    // public-safe replacement on /api/users.
    try {
      setUsers(await getJson<WorkgroupUser[]>('/api/aws-account-sharing/users', 'Failed to fetch users'));
    } catch (err) {
      console.error('Failed to fetch users:', err);
    }
  };

  const fetchAssets = async () => {
    try {
      setAssets(await getJson<WorkgroupAsset[]>('/api/assets', 'Failed to fetch assets'));
    } catch (err) {
      console.error('Failed to fetch assets:', err);
    }
  };

  const handleDelete = async (id: number) => {
    if (!window.confirm('Are you sure you want to delete this workgroup? This will remove all user and asset assignments.')) {
      return;
    }

    try {
      await deleteJson(`/api/workgroups/${id}`, undefined, 'Failed to delete workgroup');
      await fetchWorkgroups();
      setError(null);
    } catch (err) {
      const message = err instanceof ApiError || err instanceof Error
        ? err.message
        : 'An error occurred while deleting the workgroup';
      setError(message);
    }
  };

  const handleStatusChange = async (workgroup: Workgroup) => {
    const nextEnabled = workgroup.enabled === false;
    if (!nextEnabled && !window.confirm(
      `Disable ${workgroup.name}? Its users, assets, accounts, and domains will remain stored but will no longer grant access.`
    )) {
      return;
    }

    try {
      await putJson(`/api/workgroups/${workgroup.id}`, { enabled: nextEnabled }, 'Failed to update workgroup status');
      await fetchWorkgroups();
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred while updating the workgroup status');
    }
  };

  const closeForm = () => {
    setShowForm(false);
    setEditingWorkgroup(null);
  };

  if (loading) {
    return <div className="text-center p-4">Loading workgroups...</div>;
  }

  return (
    <div className="container-fluid mt-4 d-flex flex-column flex-grow-1" style={{ minHeight: 0 }}>
      <div className="d-flex justify-content-between align-items-center mb-4 flex-shrink-0">
        <h2>Workgroup Management</h2>
        <button disabled={!canChangeStatus} className="btn btn-primary" onClick={() => setShowForm(true)}>
          Create Workgroup
        </button>
      </div>

      {error && (
        <div className="alert alert-danger alert-dismissible fade show" role="alert">
          {error}
          <button type="button" className="btn-close" onClick={() => setError(null)}></button>
        </div>
      )}

      {/* Create/Edit Form Modal */}
      {showForm && (
        <WorkgroupFormModal
          workgroup={editingWorkgroup}
          onClose={closeForm}
          onSaved={async () => {
            await fetchWorkgroups();
            closeForm();
            setError(null);
          }}
          onError={setError}
        />
      )}

      {/* Assign Users Modal */}
      {assignUsersWorkgroup && (
        <WorkgroupAssignUsersModal
          workgroup={assignUsersWorkgroup}
          users={users}
          onClose={() => setAssignUsersWorkgroup(null)}
          onSaved={async () => {
            await fetchWorkgroups();
            setAssignUsersWorkgroup(null);
            setError(null);
          }}
          onError={setError}
        />
      )}

      {/* Manage Assets Modal */}
      {assignAssetsWorkgroup && (
        <WorkgroupAssignAssetsModal
          workgroup={assignAssetsWorkgroup}
          assets={assets}
          onClose={() => setAssignAssetsWorkgroup(null)}
          onSaved={async () => {
            await fetchWorkgroups();
            setAssignAssetsWorkgroup(null);
            setError(null);
          }}
          onError={setError}
        />
      )}

      {/* Accounts Modal */}
      {accountsModalState.workgroupId !== null && (
        <WorkgroupAccountsModal
          workgroupId={accountsModalState.workgroupId}
          workgroupName={accountsModalState.workgroupName}
          isOpen={accountsModalState.isOpen}
          onClose={() =>
            setAccountsModalState({ isOpen: false, workgroupId: null, workgroupName: '' })
          }
          onChange={() => {
            fetchWorkgroups();
          }}
        />
      )}

      {/* Domains Modal */}
      {domainsModalState.workgroupId !== null && (
        <WorkgroupDomainsModal
          workgroupId={domainsModalState.workgroupId}
          workgroupName={domainsModalState.workgroupName}
          isOpen={domainsModalState.isOpen}
          onClose={() =>
            setDomainsModalState({ isOpen: false, workgroupId: null, workgroupName: '' })
          }
          onChange={() => {
            fetchWorkgroups();
          }}
        />
      )}

      {/* Workgroups Table */}
      <div className="table-responsive flex-grow-1" style={scrollContainerStyle}>
        <table className="table table-striped table-hover" style={{ borderCollapse: 'separate', borderSpacing: 0 }}>
          <thead className="table-light">
            <tr>
              <th style={stickyHeaderCellStyle}>Parent</th>
              <th style={stickyHeaderCellStyle}>Name</th>
              <th style={stickyHeaderCellStyle}>AD Owner</th>
              <th style={stickyHeaderCellStyle}>Status</th>
              <th style={stickyHeaderCellStyle}>Users</th>
              <th style={stickyHeaderCellStyle}>Assets</th>
              <th style={stickyHeaderCellStyle}>Accounts</th>
              <th style={stickyHeaderCellStyle}>Domains</th>
              <th style={stickyHeaderCellStyle}>Created</th>
              <th style={stickyHeaderCellStyle}>Actions</th>
            </tr>
          </thead>
          <tbody>
            {(() => {
              const visibleWorkgroups = workgroups.filter(
                wg => showAwsWorkgroups || !isAwsWorkgroup(wg.name)
              );
              const hiddenAwsCount = workgroups.length - visibleWorkgroups.length;
              return visibleWorkgroups.length === 0 ? (
              <tr>
                <td colSpan={10} className="text-center text-muted">
                  {hiddenAwsCount > 0
                    ? 'AWS- workgroups are hidden. Enable "Show AWS- workgroups" to see them.'
                    : 'No visible workgroups found.'}
                </td>
              </tr>
            ) : (
              visibleWorkgroups.map(workgroup => {
                const enabled = workgroup.enabled !== false;
                return (
                  <tr key={workgroup.id} className={enabled ? undefined : 'table-secondary'}>
                    <td>
                      {workgroup.parentName
                        ? workgroup.parentName
                        : <span className="text-muted fst-italic">root</span>}
                    </td>
                    <td><strong>{workgroup.name}</strong></td>
                    <td>
                      {workgroup.ownerEmail ?? <span className="text-muted fst-italic">not set</span>}
                    </td>
                    <td>
                      <span className={`badge ${enabled ? 'bg-success' : 'bg-secondary'}`}>
                        {enabled ? 'Enabled' : 'Disabled'}
                      </span>
                    </td>
                    <td>
                      <span className="badge bg-info">{workgroup.userCount}</span>
                    </td>
                    <td>
                      <span className="badge bg-success">{workgroup.assetCount}</span>
                    </td>
                    <td>
                      <span className="badge bg-secondary">{workgroup.awsAccountsCount ?? 0}</span>
                    </td>
                    <td>
                      <span className="badge bg-secondary">{workgroup.adDomainsCount ?? 0}</span>
                    </td>
                    <td>{formatServerDate(workgroup.createdAt)}</td>
                  <td>
                    <fieldset disabled={!canChangeStatus} className="btn-group btn-group-sm">
                      <button
                        className="btn btn-outline-primary"
                        onClick={() => {
                          setEditingWorkgroup(workgroup);
                          setShowForm(true);
                        }}
                        title="Edit workgroup"
                      >
                        Edit
                      </button>
                      <button
                        className="btn btn-outline-info"
                        onClick={() => setAssignUsersWorkgroup(workgroup)}
                        title="Assign users"
                      >
                        Users
                      </button>
                      <button
                        type="button"
                        className="btn btn-sm btn-info ms-1"
                        onClick={() =>
                          setAccountsModalState({
                            isOpen: true,
                            workgroupId: workgroup.id,
                            workgroupName: workgroup.name,
                          })
                        }
                        title="Manage AWS accounts"
                      >
                        Accounts
                      </button>
                      <button
                        type="button"
                        className="btn btn-sm btn-secondary ms-1"
                        onClick={() =>
                          setDomainsModalState({
                            isOpen: true,
                            workgroupId: workgroup.id,
                            workgroupName: workgroup.name,
                          })
                        }
                        title="Manage AD domains"
                      >
                        Domains
                      </button>
                      <button
                        className="btn btn-outline-success"
                        onClick={() => setAssignAssetsWorkgroup(workgroup)}
                        title="Manage assets"
                      >
                        Assets
                      </button>
                      {canChangeStatus && (
                        <button
                          className={`btn ${enabled ? 'btn-outline-warning' : 'btn-outline-success'}`}
                          onClick={() => handleStatusChange(workgroup)}
                          title={`${enabled ? 'Disable' : 'Enable'} workgroup`}
                        >
                          {enabled ? 'Disable' : 'Enable'}
                        </button>
                      )}
                      <button
                        className="btn btn-outline-danger"
                        onClick={() => handleDelete(workgroup.id)}
                        title="Delete workgroup"
                      >
                        Delete
                      </button>
                    </fieldset>
                  </td>
                </tr>
                );
              })
            );
            })()}
          </tbody>
        </table>
      </div>
    </div>
  );
};

export default WorkgroupManagement;
