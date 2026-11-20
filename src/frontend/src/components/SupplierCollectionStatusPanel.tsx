import { useEffect, useState } from 'react';
import { authenticatedGet, authenticatedPost } from '../utils/auth';
import { formatServerDateTime } from '../utils/dateUtils';

interface CollectionStatus {
  invitationId: string;
  serviceScope: string;
  invitationState: string;
  deliveryState: string | null;
  pollState: string | null;
  checkedAt: string | null;
  nextCheckAt: string | null;
  observedAt: string | null;
  importedAt: string | null;
  importedRevision: number | null;
  integrityConflict: boolean;
  incidentHeldAt: string | null;
  canRetry: boolean;
}

const DELIVERY: Record<string, string> = {
  PROVISION_PENDING: 'Waiting to send',
  PROVISIONING: 'Preparing the portal',
  MAIL_SENDING: 'Sending email',
  SENT: 'Email accepted by the mail provider',
  FAILED: 'Delivery failed',
  UNCERTAIN: 'Delivery uncertain',
  REVOKE_PENDING: 'Revocation pending',
  REVOKING: 'Revocation pending',
  REVOKED: 'Revoked',
};

const COLLECTION: Record<string, string> = {
  WAITING: 'Waiting for supplier',
  MISSING: 'Portal could not find the invitation',
  UNAVAILABLE: 'Portal unavailable',
  OBSERVED: 'Submission received by portal',
  IMPORT_FAILED: 'Import failed',
  INVALID: 'Submission could not be validated',
  HELD: 'Submission held for review',
  CONFLICT: 'Submission conflicts with the existing record',
  IMPORTED: 'Imported',
  WINDOW_CLOSED: 'Automatic checks ended',
};

interface Props {
  assessmentId: number;
  answerRevision: number;
  accepted: boolean;
  reloadAnswers: () => void;
  onIntegrityConflict: (conflict: boolean) => void;
}

export default function SupplierCollectionStatusPanel({
  assessmentId, answerRevision, accepted, reloadAnswers, onIntegrityConflict,
}: Props) {
  const [collection, setCollection] = useState<CollectionStatus | null>(null);
  const [reload, setReload] = useState(0);
  const [busy, setBusy] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const [message, setMessage] = useState('');
  const endpoint = `/api/risk-assessments/${assessmentId}/supplier-invitation`;

  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    async function load() {
      try {
        const response = await authenticatedGet(`${endpoint}/status`, { signal: controller.signal });
        if ([204, 403, 404].includes(response.status)) {
          if (!controller.signal.aborted) setCollection(null);
          return;
        }
        if (!response.ok) throw new Error('Could not read supplier collection status');
        const status: CollectionStatus = await response.json();
        if (controller.signal.aborted) return;
        setCollection(status);
        onIntegrityConflict(status.integrityConflict || Boolean(status.incidentHeldAt));
        setLoadError(false);
        timer = setTimeout(load, 20_000);
      } catch {
        if (controller.signal.aborted) return;
        setLoadError(true);
        timer = setTimeout(load, 20_000);
      }
    }
    void load();
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [endpoint, onIntegrityConflict, reload]);

  const requestCheck = async () => {
    if (!collection?.canRetry || busy) return;
    setBusy(true);
    setMessage('');
    try {
      const response = await authenticatedPost(`${endpoint}/${encodeURIComponent(collection.invitationId)}/check`);
      if (!response.ok) throw new Error('Could not request a check');
      setMessage('Check queued.');
      setReload(value => value + 1);
    } catch {
      setMessage('Could not request a check. Refresh the assessment and try again.');
    } finally {
      setBusy(false);
    }
  };

  if (!collection) return null;

  const pollLabel = collection.invitationState === 'REVOKED'
    ? 'Collection closed'
    : COLLECTION[collection.pollState ?? ''] ?? 'Waiting for the first portal check';

  return (
    <section className="card mb-3" aria-label="Supplier collection">
      <div className="card-body">
        <h6 className="card-title">Supplier collection</h6>
        <p>{collection.serviceScope}</p>
        <dl className="row mb-2">
          <dt className="col-sm-4">Invitation</dt>
          <dd className="col-sm-8">{collection.invitationState === 'EXPIRED' ? 'Link expired'
            : collection.invitationState === 'REVOKED' ? 'Revoked' : collection.deliveryState ? 'Open' : 'Prepared'}</dd>
          <dt className="col-sm-4">Email delivery</dt>
          <dd className="col-sm-8">{DELIVERY[collection.deliveryState ?? ''] ?? 'Unsent'}</dd>
          <dt className="col-sm-4">Collection</dt>
          <dd className="col-sm-8">{pollLabel}</dd>
          <dt className="col-sm-4">Last portal check</dt>
          <dd className="col-sm-8">{formatServerDateTime(collection.checkedAt, undefined, 'Pending')}</dd>
          {collection.nextCheckAt && <><dt className="col-sm-4">Next check due</dt>
            <dd className="col-sm-8">{formatServerDateTime(collection.nextCheckAt)}</dd></>}
          {collection.observedAt && <><dt className="col-sm-4">First seen by SecMan</dt>
            <dd className="col-sm-8">{formatServerDateTime(collection.observedAt)}</dd></>}
          {collection.importedAt && <><dt className="col-sm-4">Imported</dt>
            <dd className="col-sm-8">{formatServerDateTime(collection.importedAt)} · Revision {collection.importedRevision}
              {' · '}{collection.incidentHeldAt ? 'On incident hold' : accepted ? 'Accepted' : 'Awaiting internal review'}</dd></>}
        </dl>
        {collection.integrityConflict && <p className="alert alert-warning" role="alert">
          The portal returned conflicting answers. SecMan kept the original record. Verify the supplier’s answers through a trusted channel.
        </p>}
        {collection.incidentHeldAt && <p className="alert alert-warning" role="alert">
          This revision is on incident hold. The original answers are preserved and acceptance is blocked.
        </p>}
        {loadError && <p className="text-warning" role="status">Live status is unavailable. Showing the last known result.</p>}
        <div className="d-flex flex-wrap gap-2 align-items-center">
          {collection.canRetry && <button type="button" className="btn btn-sm btn-outline-primary" onClick={requestCheck} disabled={busy}>
            {busy ? 'Queueing…' : 'Check for submission'}
          </button>}
          {collection.importedRevision !== null && collection.importedRevision > answerRevision && (
            <button type="button" className="btn btn-sm btn-primary" onClick={reloadAnswers}>Load imported answers</button>
          )}
          {message && <span role="status">{message}</span>}
        </div>
      </div>
    </section>
  );
}
