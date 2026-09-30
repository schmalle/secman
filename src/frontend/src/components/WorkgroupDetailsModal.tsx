import { useEffect, useRef, useState } from 'react';
import { ApiError, getJson } from '../utils/apiJson';
import type { AssignedAsset, AssignedUser, Workgroup } from './workgroupTypes';

interface Details {
  workgroup: Workgroup;
  users: AssignedUser[];
  assets: AssignedAsset[];
  accounts: Array<{ id: number; awsAccountId: string }>;
  domains: Array<{ id: number; adDomain: string }>;
}

interface Props {
  workgroupId: number;
  workgroupName: string;
  onClose: () => void;
}

export default function WorkgroupDetailsModal({ workgroupId, workgroupName, onClose }: Props) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [details, setDetails] = useState<Details | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  const handleClose = () => {
    dialog.current?.close();
    onClose();
  };

  useEffect(() => {
    if (!dialog.current?.open) dialog.current?.showModal();
    let active = true;
    const url = `/api/workgroups/${workgroupId}`;
    Promise.all([
      getJson<Workgroup>(url),
      getJson<Details['users']>(`${url}/users`),
      getJson<Details['assets']>(`${url}/assets`),
      getJson<Details['accounts']>(`${url}/aws-accounts`),
      getJson<Details['domains']>(`${url}/ad-domains`),
    ]).then(([workgroup, users, assets, accounts, domains]) => {
      if (active) setDetails({ workgroup, users, assets, accounts, domains });
    }).catch((cause: unknown) => {
      if (!active) return;
      setError(cause instanceof ApiError && cause.status === 403
        ? 'You do not have permission to view this workgroup.'
        : cause instanceof ApiError && cause.status === 404
          ? 'This workgroup no longer exists.'
          : 'Could not load all workgroup details. Please try again.');
    });
    // Closing or switching groups must not let an old response replace the current view.
    return () => { active = false; };
  }, [workgroupId, attempt]);

  return (
    <dialog
      ref={dialog}
      aria-labelledby="workgroup-details-title"
      onCancel={event => { event.preventDefault(); handleClose(); }}
      className="border-0 rounded p-0 shadow"
      style={{ width: 'min(1100px, calc(100% - 2rem))', maxHeight: '85vh' }}
    >
      <div className="modal-content">
        <div className="modal-header p-3">
          <h5 id="workgroup-details-title" className="modal-title text-break">
            Workgroup Details — {details?.workgroup.name ?? workgroupName}
          </h5>
          <button type="button" className="btn-close" aria-label="Close workgroup details" onClick={handleClose} />
        </div>
        <div className="modal-body p-3">
          {error ? (
            <div role="alert" className="alert alert-danger">
              {error}
              <button type="button" className="btn btn-sm btn-outline-danger ms-3" onClick={() => {
                setError(null);
                setDetails(null);
                setAttempt(value => value + 1);
              }}>Retry</button>
            </div>
          ) : !details ? (
            <p role="status">Loading workgroup details…</p>
          ) : (
            <>
              {details.workgroup.description && <p>{details.workgroup.description}</p>}
              <dl className="row">
                <dt className="col-sm-3">Status</dt>
                <dd className="col-sm-9">{details.workgroup.enabled !== false ? 'Enabled' : 'Disabled'}</dd>
                <dt className="col-sm-3">AD Owner</dt>
                <dd className="col-sm-9 text-break">{details.workgroup.ownerEmail || 'Not assigned'}</dd>
                <dt className="col-sm-3">Criticality</dt>
                <dd className="col-sm-9">{details.workgroup.criticality}</dd>
              </dl>
              <section aria-labelledby="workgroup-users-title" className="mb-4">
                <h6 id="workgroup-users-title">Users ({details.users.length})</h6>
                {details.users.length === 0 ? <p className="text-muted">No users assigned.</p> : (
                  <div className="table-responsive">
                    <table className="table table-sm">
                      <thead><tr><th scope="col">Username</th><th scope="col">Email</th></tr></thead>
                      <tbody>{details.users.map(user => (
                        <tr key={user.id}><td>{user.username}</td><td>{user.email}</td></tr>
                      ))}</tbody>
                    </table>
                  </div>
                )}
              </section>
              <section aria-labelledby="workgroup-assets-title" className="mb-4">
                <h6 id="workgroup-assets-title">Assets ({details.assets.length})</h6>
                <p className="small text-muted">Directly assigned assets. AWS account and AD domain assignments can grant access to additional assets.</p>
                {details.assets.length === 0 ? <p className="text-muted">No assets directly assigned.</p> : (
                  <div className="table-responsive">
                    <table className="table table-sm">
                      <thead><tr><th scope="col">Name</th><th scope="col">Type</th><th scope="col">IP</th><th scope="col">Owner</th></tr></thead>
                      <tbody>{details.assets.map(asset => (
                        <tr key={asset.id}><td>{asset.name}</td><td>{asset.type || '—'}</td><td>{asset.ip || '—'}</td><td>{asset.owner || '—'}</td></tr>
                      ))}</tbody>
                    </table>
                  </div>
                )}
              </section>
              <section aria-labelledby="workgroup-accounts-title" className="mb-4">
                <h6 id="workgroup-accounts-title">AWS Accounts ({details.accounts.length})</h6>
                {details.accounts.length === 0 ? <p className="text-muted">No AWS accounts assigned.</p> : (
                  <ul>{details.accounts.map(account => <li key={account.id}><code>{account.awsAccountId}</code></li>)}</ul>
                )}
              </section>
              <section aria-labelledby="workgroup-domains-title">
                <h6 id="workgroup-domains-title">AD Domains ({details.domains.length})</h6>
                {details.domains.length === 0 ? <p className="text-muted">No AD domains assigned.</p> : (
                  <ul>{details.domains.map(domain => <li key={domain.id}>{domain.adDomain}</li>)}</ul>
                )}
              </section>
            </>
          )}
        </div>
        <div className="modal-footer p-3">
          <a className="btn btn-outline-primary" href={`/workgroups?workgroupId=${encodeURIComponent(workgroupId)}`}>Open Workgroup Management</a>
          <button type="button" className="btn btn-secondary" onClick={handleClose}>Close</button>
        </div>
      </div>
    </dialog>
  );
}
