import React from 'react';
import type { CreateResult } from '../../services/securityTicketLogic';

interface Props {
  preview: CreateResult | null;
  checking: boolean;
  ready: boolean;
  slaHint: string;
}

/**
 * What submitting would do, from a server dry run: the recipients and due date, or the
 * reason it would be rejected. Stays beside the form while it is being filled in.
 */
const TicketPreviewPanel: React.FC<Props> = ({ preview, checking, ready, slaHint }) => (
  <div className="card shadow-sm position-sticky" style={{ top: 16 }}>
    <div className="card-header d-flex justify-content-between align-items-center">
      <span><i className="bi bi-eye me-2"></i>Preview</span>
      {checking && <span className="spinner-border spinner-border-sm" role="status" aria-label="Checking"></span>}
    </div>
    <div className="card-body small">
      {!ready && <p className="text-muted mb-0">Fill in account, title and description to see who will be notified.</p>}
      {ready && preview && <PreviewBody preview={preview} slaHint={slaHint} />}
    </div>
  </div>
);

/** The three possible previews: rejected with reasons, already submitted, or ready with recipients and due date. */
const PreviewBody: React.FC<{ preview: CreateResult; slaHint: string }> = ({ preview, slaHint }) => {
  if (preview.outcome === 'REJECTED') {
    return (
      <div className="text-danger">
        <strong><i className="bi bi-x-octagon me-1"></i>Would be rejected</strong>
        {preview.errorCode && <span className="ms-1">({preview.errorCode})</span>}
        <ul className="mb-0 mt-2 ps-3">{preview.errors.map((e, i) => <li key={i}><code>{e.field}</code>: {e.message}</li>)}</ul>
      </div>
    );
  }
  if (preview.outcome === 'DUPLICATE') {
    return <p className="mb-0">Already submitted as <a href={`/security-tickets/${preview.ticketId}`}>{preview.ticketKey}</a>.</p>;
  }
  return (
    <>
      <p className="mb-2 text-success"><i className="bi bi-check-circle me-1"></i>Ready to create</p>
      <dl className="mb-0">
        <dt>Notifies {preview.recipientsOverridden ? '(named recipients)' : '(account owners)'}</dt>
        <dd>
          {preview.recipients.length === 0
            ? <span className="text-warning">nobody: unrouted ticket</span>
            : <ul className="ps-3 mb-0">{preview.recipients.map(r => <li key={r}>{r}</li>)}</ul>}
        </dd>
        <dt>Due</dt>
        <dd className="mb-0">{preview.dueAt ? new Date(preview.dueAt).toLocaleString() : 'no deadline'} <span className="text-muted">{slaHint}</span></dd>
      </dl>
    </>
  );
};

export default TicketPreviewPanel;
