import { useEffect, useRef, useState } from 'react';
import { authenticatedGet, authenticatedPost } from '../utils/auth';

const endpoint = '/api/vulnerability-exception-requests/batches';
const lines = (text: string) => [...new Set(text.split(/\r?\n/).map(s => s.trim()).filter(Boolean))];

interface Draft {
  accounts: string[];
  products: { exact: string[]; glob: string[] };
  through: string;
  reason: string;
}
interface Preview {
  previewId: string;
  impact: {
    draft: Draft;
    observedAt: string;
    combinations: number;
    findings: number;
    additional: number;
    expectedStatus: string;
    accounts: { account: string; findings: number; assets: number; additional: number; unresolved: number }[];
    products: { selector: string; mode: string; findings: number; examples: string[] }[];
  };
}
interface Receipt { batchId: string; status: string; draft: Draft; decidedBy?: string; actions: string[]; history: { actor: string; outcome: string; note?: string }[] }
async function body<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const details = await response.json().catch(() => ({}));
    throw new Error(details.message || `Request failed (HTTP ${response.status})`);
  }
  return response.json();
}

export default function ProductExceptionBatchForm() {
  const details = useRef<HTMLDetailsElement>(null);
  const [available, setAvailable] = useState<Record<string, string>>({});
  const [accounts, setAccounts] = useState<string[]>([]);
  const [search, setSearch] = useState('');
  const [labelSearch, setLabelSearch] = useState('');
  const [suggestions, setSuggestions] = useState<string[]>([]);
  const [exact, setExact] = useState('');
  const [glob, setGlob] = useState('');
  const [through, setThrough] = useState('');
  const [reason, setReason] = useState('');
  const [preview, setPreview] = useState<Preview>();
  const [receipt, setReceipt] = useState<Receipt>();
  const [key, setKey] = useState('');
  const [lookup, setLookup] = useState('');
  const [reviewImpact, setReviewImpact] = useState<Preview["impact"]>();
  useEffect(() => {
    let active = true;
    setReviewImpact(undefined);
    if (receipt?.status === "PENDING") authenticatedGet(endpoint + "/" + receipt.batchId + "/impact")
      .then(body<Preview["impact"]>).then(result => { if (active) setReviewImpact(result); })
      .catch(e => { if (active) setError(e.message); });
    return () => { active = false; };
  }, [receipt]);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => {
    authenticatedGet(endpoint + '/accounts').then(body<Record<string, string>>).then(setAvailable).catch(e => setError(e.message));
    const id = new URLSearchParams(window.location.search).get('batch');
    if (id) {
      if (details.current) details.current.open = true;
      setLookup(id);
      authenticatedGet(endpoint + '/' + encodeURIComponent(id)).then(body<Receipt>).then(setReceipt).catch(e => setError(e.message));
    }
  }, []);

  async function run(action: () => Promise<void>) {
    setBusy(true);
    setError('');
    try { await action(); } catch (e) { setError(e instanceof Error ? e.message : 'Request failed'); }
    finally { setBusy(false); }
  }

  async function inspect() {
    setReceipt(undefined);
    setPreview(undefined);
    const request = { version: 1, accounts, products: { exact: lines(exact), glob: lines(glob) }, through, reason };
    const value = await body<Preview>(await authenticatedPost(endpoint + '/preview', request));
    setPreview(value);
    setKey(crypto.randomUUID());
  }

  async function submit() {
    if (!preview) return;
    const result = await body<Receipt>(await authenticatedPost(endpoint, { previewId: preview.previewId, requestKey: key }));
    setReceipt(result);
    setLookup(result.batchId);
    setPreview(undefined);
  }

  return <details className="container my-3" ref={details}>
    <summary className="h5">Request product exceptions</summary>
    <fieldset disabled={busy}>
      <form onChange={() => { setPreview(undefined); setReceipt(undefined); }} onSubmit={e => { e.preventDefault(); void run(inspect); }}>
        <label className="form-label">Find AWS account <input className="form-control" value={search} onChange={e => setSearch(e.target.value)} /></label>
        <div className="border rounded p-2 overflow-auto mb-3" style={{ maxHeight: '12rem' }}>
          {Object.keys(available).filter(id => (id + " " + available[id]).toLowerCase().includes(search.toLowerCase())).map(id => <label key={id} className="d-block">
            <input type="checkbox" checked={accounts.includes(id)} onChange={e => { setAccounts(e.target.checked ? [...accounts, id] : accounts.filter(a => a !== id)); setSuggestions([]); }} /> {id} {available[id]}
          </label>)}
        </div>
        <label>Find observed product labels<input className="form-control" value={labelSearch}
          onChange={e => { setLabelSearch(e.target.value); setSuggestions([]); }} /></label>
        <button type="button" className="btn btn-outline-secondary ms-2" disabled={!accounts.length || labelSearch.trim().length < 2}
          onClick={() => void run(async () => {
            const labels = await body<string[]>(await authenticatedPost(endpoint + '/products', { accounts, search: labelSearch }));
            setSuggestions(labels);
            if (!labels.length) setError('No matching labels in visible inventory for these accounts.');
          })}>Find labels</button>
        {suggestions.length > 0 && <ul>{suggestions.map(s => <li key={s}><button type="button" className="btn btn-link" onClick={() => {
          setExact(lines(exact + '\n' + s).join('\n'));
          setPreview(undefined);
          setSuggestions([]);
        }}>Add {s}</button></li>)}</ul>}
        <label className="d-block">Exact product labels, one per line<textarea className="form-control" rows={4} value={exact} onChange={e => setExact(e.target.value)} /></label>
        <label className="d-block">Wildcard product labels, one per line<textarea className="form-control" rows={2} value={glob} onChange={e => setGlob(e.target.value)} /></label>
        <p className="form-text">An asterisk matches any characters within one label, including future product versions.</p>
        <label className="d-block">Through date (inclusive, UTC)<input required type="date" className="form-control" value={through} onChange={e => setThrough(e.target.value)} /></label>
        <label className="d-block">Business justification (50–2048 characters)<textarea required minLength={50} maxLength={2048} className="form-control" value={reason} onChange={e => setReason(e.target.value)} /></label>
        <button className="btn btn-primary my-2" disabled={!accounts.length || (!exact.trim() && !glob.trim())}>Preview impact</button>
      </form>
      {preview && <section aria-live="polite">
        <h3>Impact through {preview.impact.draft.through} UTC</h3>
        <p>{preview.impact.accounts.length} accounts × {preview.impact.products.length} selectors = {preview.impact.combinations} combinations. {preview.impact.findings} matched findings, {preview.impact.additional} additional. Visible inventory at {preview.impact.observedAt}.</p>
        <table className="table"><thead><tr>{['AWS account', 'Matched findings', 'Assets', 'Additional', 'Unresolved labels'].map(t => <th key={t}>{t}</th>)}</tr></thead>
          <tbody>{preview.impact.accounts.map(a => <tr key={a.account}><td>{a.account}</td><td>{a.findings}</td><td>{a.assets}</td><td>{a.additional}</td><td>{a.unresolved}</td></tr>)}</tbody></table>
        <ul>{preview.impact.products.map(p => <li key={p.mode+p.selector}>{p.mode} {p.selector} — {p.findings ? `${p.findings} findings (${(p.examples ?? []).join(', ')})` : 'No current matches. May match future findings.'}</li>)}</ul>
        <button className="btn btn-primary" onClick={() => void run(submit)}>{preview.impact.expectedStatus === 'APPROVED' ? 'Create approved exception' : 'Submit for approval'}</button>
      </section>}
      <form className="mt-3" onSubmit={e => { e.preventDefault(); void run(async () => setReceipt(await body<Receipt>(await authenticatedGet(endpoint + '/' + encodeURIComponent(lookup))))); }}>
        <label>Look up batch ID<input required className="form-control" value={lookup} onChange={e => { setLookup(e.target.value); setReceipt(undefined); }} /></label>
        <button className="btn btn-outline-secondary ms-2">View batch</button>
      </form>
      {receipt && <section aria-live="polite">
        <h3>{receipt.status} · {receipt.batchId}</h3>
        <p>Through {receipt.draft.through} UTC · {receipt.draft.accounts.join(', ')}</p>
        <p>{receipt.draft.reason}</p>
        <div>Exact: {receipt.draft.products.exact.join(', ')}</div><div>Wildcards: {receipt.draft.products.glob.join(', ') || 'None'}</div>
        {reviewImpact && <section><h4>Current visible impact</h4>
          <ul>{reviewImpact.accounts.map(a => <li key={a.account}>{a.account}: {a.findings} findings, {a.additional} additional, {a.unresolved} unresolved</li>)}</ul>
          <ul>{reviewImpact.products.map(p => <li key={p.mode+p.selector}>{p.selector}: {p.findings} findings {(p.examples ?? []).join(', ')}</li>)}</ul></section>}
        <button className="btn btn-outline-secondary my-2" onClick={() => {
          setAccounts(receipt.draft.accounts);
          setExact(receipt.draft.products.exact.join('\n'));
          setGlob(receipt.draft.products.glob.join('\n'));
          setReason(receipt.draft.reason);
          setThrough(receipt.draft.through);
          setReceipt(undefined);
          setPreview(undefined);
          details.current?.scrollIntoView();
        }}>Copy to new draft</button>
        <ul>{(receipt.history ?? []).map((h, index) => <li key={index}>{h.outcome} · {h.actor} {h.note}</li>)}</ul>
        {(receipt.actions ?? []).includes('reject') && <label>Reason if rejecting<input className="form-control" maxLength={2048} value={note} onChange={e => setNote(e.target.value)} /></label>}
        {(receipt.actions ?? []).map(action =>
          <button key={action} disabled={(action === 'reject' && !note.trim()) || (action === 'approve' && !reviewImpact)} className="btn btn-outline-secondary me-2" onClick={() => void run(async () => setReceipt(await body<Receipt>(await authenticatedPost(`${endpoint}/${receipt.batchId}/${action}`, { note }))))}>{action}</button>)}
      </section>}
    </fieldset>
    {error && <p role="alert" className="text-danger">{error}</p>}
  </details>;
}
