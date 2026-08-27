import React, { useState, useEffect } from 'react';
import { getJson, ApiError, deleteJson } from '../utils/apiJson';
import WorkgroupAccountsModal from './WorkgroupAccountsModal';
import WorkgroupDomainsModal from './WorkgroupDomainsModal';
import WorkgroupFormModal from './WorkgroupFormModal';
import WorkgroupAssignUsersModal from './WorkgroupAssignUsersModal';
import WorkgroupAssignAssetsModal from './WorkgroupAssignAssetsModal';
import { isAwsWorkgroup } from '../services/workgroupApi';
import { formatServerDate } from '../utils/dateUtils';
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

  const closeForm = () => {
    setShowForm(false);
    setEditingWorkgroup(null);
  };

  if (loading) {
    return <div className="text-center p-4">Loading workgroups...</div>;
  }

  return (
    <div className="container-fluid mt-4">
      <div className="d-flex justify-content-between align-items-center mb-4">
        <h2>Workgroup Management</h2>
        <button className="btn btn-primary" onClick={() => setShowForm(true)}>
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
      <div className="table-responsive">
        <table className="table table-striped table-hover">
          <thead>
            <tr>
              <th>Parent</th>
              <th>Name</th>
              <th>Users</th>
              <th>Assets</th>
              <th>Accounts</th>
              <th>Domains</th>
              <th>Created</th>
              <th>Actions</th>
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
                <td colSpan={8} className="text-center text-muted">
                  {hiddenAwsCount > 0
                    ? 'AWS- workgroups are hidden. Enable "Show AWS- workgroups" to see them.'
                    : 'No visible workgroups found.'}
                </td>
              </tr>
            ) : (
              visibleWorkgroups.map(workgroup => {
                return (
                  <tr key={workgroup.id}>
                    <td>
                      {workgroup.parentName
                        ? workgroup.parentName
                        : <span className="text-muted fst-italic">root</span>}
                    </td>
                    <td><strong>{workgroup.name}</strong></td>
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
                    <div className="btn-group btn-group-sm">
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
                      <button
                        className="btn btn-outline-danger"
                        onClick={() => handleDelete(workgroup.id)}
                        title="Delete workgroup"
                      >
                        Delete
                      </button>
                    </div>
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
