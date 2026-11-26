import React, { useState } from 'react';
import { bulkDelivery, prepareBulk, type BatchResult, type CreateResult, type TicketFields } from '../../services/securityTicketLogic';
import { securityTicketService } from '../../services/securityTicketService';

const MAX_BATCH = 100;
const MAX_FILE_BYTES = 2 * 1024 * 1024;

/**
 * Bulk submission: paste or upload JSON or CSV, check it with a server dry run, then submit
 * only the rows that would be created. Owners get one digest mail per submission.
 */
const BulkTicketImport: React.FC = () => {
  const [text, setText] = useState('');
  const [recipientsText, setRecipientsText] = useState('');
  const [tickets, setTickets] = useState<TicketFields[]>([]);
  const [check, setCheck] = useState<BatchResult | null>(null);
  const [done, setDone] = useState<BatchResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const loadFile = async (file: File | undefined) => {
    if (!file) return;
    if (file.size > MAX_FILE_BYTES) { setProblem('File is larger than 2 MB'); return; }
    setText(await file.text());
  };

  const runCheck = async () => {
    setProblem(null); setDone(null); setCheck(null);
    const parsed = prepareBulk(text, MAX_BATCH);
    if (parsed.error) { setProblem(parsed.error); return; }
    setBusy(true);
    const result = await securityTicketService.createBatch(parsed.tickets, bulkDelivery(recipientsText, true));
    setBusy(false);
    if (result.ok) { setTickets(parsed.tickets); setCheck(result.data); } else setProblem(result.error.message);
  };

  const submit = async () => {
    if (!check) return;
    const accepted = check.results.filter(r => r.outcome === 'WOULD_CREATE').map(r => tickets[r.index]);
    if (accepted.length === 0) return;
    setBusy(true);
    const result = await securityTicketService.createBatch(accepted, bulkDelivery(recipientsText, false));
    setBusy(false);
    if (!result.ok) { setProblem(result.error.message); return; }
    setDone(result.data);
    setCheck(null);
  };

  const willCreate = check?.results.filter(r => r.outcome === 'WOULD_CREATE').length ?? 0;
  return (
    <div>
      {problem && <div className="alert alert-danger">{problem}</div>}
      <BulkInputCard text={text} recipientsText={recipientsText} busy={busy} willCreate={willCreate}
        onText={value => { setText(value); setCheck(null); }} onRecipients={value => { setRecipientsText(value); setCheck(null); }}
        onFile={loadFile} onCheck={runCheck} onSubmit={submit} />
      {check && <BulkResultTable title="Check (nothing stored yet)" batch={check} />}
      {done && <BulkResultTable title="Submitted" batch={done} />}
    </div>
  );
};

interface InputCardProps {
  text: string; recipientsText: string; busy: boolean; willCreate: number;
  onText: (v: string) => void; onRecipients: (v: string) => void; onFile: (f: File | undefined) => void;
  onCheck: () => void; onSubmit: () => void;
}

/** The paste area, file upload, optional recipients and the Check / Create buttons. */
const BulkInputCard: React.FC<InputCardProps> = props => (
  <div className="card shadow-sm mb-3">
    <div className="card-body">
      <label htmlFor="bulk-input" className="form-label">
        Tickets as JSON (array) or CSV with a header row
        (<code>externalReference,awsAccountId,title,severity,description,resources,cves</code>; lists separated by <code>;</code>)
      </label>
      <textarea id="bulk-input" rows={10} className="form-control font-monospace" value={props.text}
        onChange={e => props.onText(e.target.value)} />
      <div className="d-flex flex-wrap gap-2 align-items-center mt-2">
        <input type="file" accept=".json,.csv,application/json,text/csv" className="form-control form-control-sm w-auto"
          aria-label="Upload file" onChange={e => props.onFile(e.target.files?.[0])} />
        <input className="form-control form-control-sm w-auto flex-grow-1" placeholder="Optional: mail these people instead of the owners"
          aria-label="Recipients" value={props.recipientsText} onChange={e => props.onRecipients(e.target.value)} />
        <button type="button" className="btn btn-outline-primary btn-sm" disabled={props.busy || !props.text.trim()} onClick={props.onCheck}>Check</button>
        <button type="button" className="btn btn-primary btn-sm" disabled={props.busy || props.willCreate === 0} onClick={props.onSubmit}>
          Create {props.willCreate || ''} ticket{props.willCreate === 1 ? '' : 's'}
        </button>
      </div>
      <div className="form-text">Give every row an externalReference: rerunning a file then reports duplicates instead of creating them twice.</div>
    </div>
  </div>
);

const OUTCOME_STYLE: Record<CreateResult['outcome'], string> = {
  WOULD_CREATE: 'bg-success', CREATED: 'bg-success', DUPLICATE: 'bg-secondary', REJECTED: 'bg-danger',
};

/** One row per ticket with its outcome and, for rejections, every field error. */
const BulkResultTable: React.FC<{ title: string; batch: BatchResult }> = ({ title, batch }) => (
  <div className="card shadow-sm">
    <div className="card-header">
      {title}: {batch.created} {batch.dryRun ? 'would be created' : 'created'}, {batch.duplicates} duplicate, {batch.rejected} rejected
    </div>
    <div className="table-responsive">
      <table className="table table-sm mb-0 align-middle">
        <thead><tr><th>#</th><th>Outcome</th><th>Ticket</th><th>Recipients / problems</th></tr></thead>
        <tbody>
          {batch.results.map(r => (
            <tr key={r.index}>
              <td>{r.index + 1}</td>
              <td><span className={`badge ${OUTCOME_STYLE[r.outcome]}`}>{r.outcome.replace('_', ' ')}</span></td>
              <td>{r.ticketId ? <a href={`/security-tickets/${r.ticketId}`}>{r.ticketKey}</a> : '—'}</td>
              <td className="small">
                {r.outcome === 'REJECTED'
                  ? r.errors.map((e, i) => <div key={i} className="text-danger"><code>{e.field}</code>: {e.message}</div>)
                  : r.recipients.join(', ') || (r.outcome === 'DUPLICATE' ? 'already submitted' : 'nobody (unrouted)')}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  </div>
);

export default BulkTicketImport;
