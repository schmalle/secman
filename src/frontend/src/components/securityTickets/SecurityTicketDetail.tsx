import React, { useEffect, useState } from 'react';
import RichContent from '../RichContent';
import { dueText, SEVERITY_BADGE, type TicketStatus } from '../../services/securityTicketLogic';
import { securityTicketService, type TicketDetail } from '../../services/securityTicketService';

/**
 * One ticket: what Cyber Defence reported, the activity log, and the moves the caller may
 * make. Allowed moves and comment rules come from the server response, not from role
 * checks here; every change sends the version it was based on.
 */
const SecurityTicketDetail: React.FC<{ ticketId: number }> = ({ ticketId }) => {
  const [detail, setDetail] = useState<TicketDetail | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  const load = async () => {
    const result = await securityTicketService.get(ticketId);
    if (result.ok) { setDetail(result.data); setFailure(null); }
    else setFailure(result.status === 404 ? 'This ticket does not exist or belongs to an account you cannot access.' : result.error.message);
  };
  useEffect(() => { load(); }, [ticketId]);

  if (failure && !detail) return <div className="container py-4"><div className="alert alert-warning">{failure}</div></div>;
  if (!detail) return null;
  const t = detail.ticket;
  return (
    <div className="container-fluid py-3">
      <a href="/security-tickets" className="small">&larr; Security tickets</a>
      <div className="d-flex flex-wrap align-items-center gap-2 mt-2 mb-3">
        <h2 className="h4 mb-0">{t.ticketKey}</h2>
        <span className={`badge ${SEVERITY_BADGE[t.severity] ?? 'bg-secondary'}`}>{t.severity}</span>
        <span className={`badge ${t.status === 'OPEN' ? 'bg-primary' : 'bg-success'}`}>{t.status}</span>
        {t.overdue && <span className="badge bg-danger">{dueText(t.dueAt, t.status)}</span>}
      </div>
      {failure && <div className="alert alert-danger">{failure}</div>}
      <div className="row g-4">
        <div className="col-lg-8">
          <TicketBody detail={detail} />
          <Timeline detail={detail} />
        </div>
        <div className="col-lg-4">
          <TicketFacts detail={detail} />
          <TicketActions detail={detail} onChanged={setDetail} onFailure={setFailure} />
        </div>
      </div>
    </div>
  );
};

const TicketBody: React.FC<{ detail: TicketDetail }> = ({ detail }) => (
  <div className="card shadow-sm mb-3">
    <div className="card-body">
      <h3 className="h5">{detail.ticket.title}</h3>
      <RichContent value={detail.description} />
      {detail.recommendedAction && (<><h4 className="h6 mt-3">Recommended action</h4><RichContent value={detail.recommendedAction} /></>)}
      {detail.resources.length > 0 && (
        <>
          <h4 className="h6 mt-3">Affected resources</h4>
          <table className="table table-sm small"><tbody>
            {detail.resources.map((r, i) => (
              <tr key={i}><td><span className="badge bg-light text-dark">{r.type}</span></td>
                <td className="font-monospace text-break">{r.identifier}</td><td>{r.region ?? ''}</td>
                <td><button type="button" className="btn btn-link btn-sm p-0" aria-label="Copy identifier"
                  onClick={() => navigator.clipboard?.writeText(r.identifier)}><i className="bi bi-clipboard"></i></button></td></tr>
            ))}
          </tbody></table>
        </>
      )}
      {detail.cves.length > 0 && <div className="mt-2">{detail.cves.map(c => <span key={c} className="badge bg-secondary me-1">{c}</span>)}</div>}
      {detail.references.length > 0 && (
        <ul className="mt-2 small">{detail.references.map(u => <li key={u}><a href={u} target="_blank" rel="noopener noreferrer">{u}</a></li>)}</ul>
      )}
    </div>
  </div>
);

/** Account, due date, reporter and reach at a glance. */
const TicketFacts: React.FC<{ detail: TicketDetail }> = ({ detail }) => {
  const t = detail.ticket;
  return (
    <div className="card shadow-sm mb-3"><div className="card-body small">
      <dl className="mb-0">
        <dt>Account</dt><dd><code>{t.awsAccountId}</code> {t.awsAccountName}</dd>
        <dt>Due</dt><dd>{t.dueAt ? new Date(t.dueAt).toLocaleString() : 'no deadline'} <span className="text-muted">{dueText(t.dueAt, t.status)}</span></dd>
        <dt>Reported by</dt><dd>{t.createdByUsername}{t.sourceSystem && ` via ${t.sourceSystem}`}, {new Date(t.createdAt).toLocaleString()}</dd>
        <dt>Notified</dt><dd className="mb-0">{detail.recipientCount} recipient(s)</dd>
      </dl>
    </div></div>
  );
};

const Timeline: React.FC<{ detail: TicketDetail }> = ({ detail }) => (
  <div className="card shadow-sm"><div className="card-header">Activity</div>
    <ul className="list-group list-group-flush">
      {detail.events.map(e => (
        <li key={e.id} className="list-group-item small">
          <div className="d-flex justify-content-between">
            <span><strong>{e.actorUsername ?? 'system'}</strong> <span className="badge bg-light text-dark">{e.actorKind}</span>{' '}
              {e.eventType === 'CREATED' ? 'opened the ticket' : e.eventType === 'COMMENT' ? 'commented' : `set status to ${e.toStatus}`}</span>
            <span className="text-muted">{new Date(e.createdAt).toLocaleString()}</span>
          </div>
          {e.comment && <div className="mt-1" style={{ whiteSpace: 'pre-wrap' }}>{e.comment}</div>}
        </li>
      ))}
    </ul>
  </div>
);

/** Close, reopen and comment. Only the moves the server lists for this caller are offered. */
const TicketActions: React.FC<{ detail: TicketDetail; onChanged: (d: TicketDetail) => void; onFailure: (m: string | null) => void }> =
  ({ detail, onChanged, onFailure }) => {
    const [comment, setComment] = useState('');
    const [busy, setBusy] = useState(false);

    // Runs one change; a version conflict gets its own message because reloading fixes it.
    const act = async (call: () => ReturnType<typeof securityTicketService.comment>) => {
      setBusy(true);
      const result = await call();
      setBusy(false);
      if (result.ok) { onChanged(result.data); setComment(''); onFailure(null); return; }
      onFailure(result.error.error === 'VERSION_CONFLICT'
        ? 'Someone changed this ticket in the meantime. Reload the page to see the latest state.'
        : result.error.message);
    };
    const move = (to: TicketStatus) => act(() => securityTicketService.transition(detail.ticket.id, to, comment.trim(), detail.ticket.version));
    if (!detail.canComment && detail.allowedTransitions.length === 0) return null;
    return (
      <div className="card shadow-sm"><div className="card-body">
        <label htmlFor="ticket-comment" className="form-label small">Comment</label>
        <textarea id="ticket-comment" rows={3} className="form-control mb-2" maxLength={4000} value={comment}
          placeholder="What was done, or a question for the other side" onChange={e => setComment(e.target.value)} />
        <div className="d-flex flex-wrap gap-2">
          {detail.allowedTransitions.map(to => {
            const needsComment = detail.commentRequiredFor.includes(to);
            return (
              <button key={to} type="button" className={`btn btn-sm ${to === 'CLOSED' ? 'btn-success' : 'btn-warning'}`}
                disabled={busy || (needsComment && !comment.trim())} title={needsComment ? 'Needs a comment' : undefined}
                onClick={() => move(to)}>{to === 'CLOSED' ? 'Close ticket' : 'Reopen ticket'}</button>
            );
          })}
          {detail.canComment && (
            <button type="button" className="btn btn-sm btn-outline-secondary" disabled={busy || !comment.trim()}
              onClick={() => act(() => securityTicketService.comment(detail.ticket.id, comment.trim()))}>Add comment</button>
          )}
        </div>
      </div></div>
    );
  };

export default SecurityTicketDetail;
