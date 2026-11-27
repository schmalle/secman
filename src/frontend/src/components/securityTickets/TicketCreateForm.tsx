import React, { useEffect, useRef, useState } from 'react';
import AccountPicker from './AccountPicker';
import TicketPreviewPanel from './TicketPreviewPanel';
import {
  buildRequest, emptyDraft, errorsByField, foreignArns, newReference, readyForPreview, slaLabel,
  type CreateResult, type Severity, type TicketDraft, type TicketMeta,
} from '../../services/securityTicketLogic';
import { securityTicketService } from '../../services/securityTicketService';

const SEVERITIES: Severity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'];
const PREVIEW_DELAY_MS = 500;

/**
 * Re-runs a server dry run 500 ms after the last edit, once the required fields are filled.
 * Only the newest check may update the preview, so a slow answer never overwrites a fresher one.
 */
function useDryRunPreview(draft: TicketDraft, reference: string) {
  const [preview, setPreview] = useState<CreateResult | null>(null);
  const [checking, setChecking] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const ready = readyForPreview(draft);
  const latest = useRef(0);

  useEffect(() => {
    if (!ready) { setPreview(null); return; }
    const seq = ++latest.current;
    const handle = setTimeout(async () => {
      setChecking(true);
      const { ticket, delivery } = buildRequest(draft, reference, true);
      const result = await securityTicketService.create(ticket, delivery);
      if (seq !== latest.current) return;
      setChecking(false);
      setPreview(result.ok ? result.data : null);
      setFailure(result.ok ? null : result.error.message);
    }, PREVIEW_DELAY_MS);
    return () => clearTimeout(handle);
  }, [draft, ready, reference]);

  return { preview, setPreview, checking, failure, setFailure, ready };
}

/**
 * Single-ticket form. Validation is the server's: a debounced dry run after each edit shows
 * field errors in place, the recipients and the due date, so no rule is copied into the UI.
 * The form carries one idempotency key from opening to submit, so a double click or a retry
 * returns the same ticket instead of creating two.
 */
const TicketCreateForm: React.FC<{ meta: TicketMeta | null }> = ({ meta }) => {
  const [draft, setDraft] = useState<TicketDraft>(emptyDraft);
  const [reference] = useState(() => newReference());
  const [submitting, setSubmitting] = useState(false);
  const { preview, setPreview, checking, failure, setFailure, ready } = useDryRunPreview(draft, reference);

  const set = <K extends keyof TicketDraft>(key: K, value: TicketDraft[K]) => setDraft(d => ({ ...d, [key]: value }));
  const errors = errorsByField(preview?.errors ?? []);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setSubmitting(true);
    setFailure(null);
    const { ticket, delivery } = buildRequest(draft, reference, false);
    const result = await securityTicketService.create(ticket, delivery);
    setSubmitting(false);
    if (!result.ok) { setFailure(result.error.message); return; }
    if (result.data.ticketId && (result.data.outcome === 'CREATED' || result.data.outcome === 'DUPLICATE')) {
      window.location.href = `/security-tickets/${result.data.ticketId}`;
      return;
    }
    setPreview(result.data);
  };

  const canSubmit = ready && !submitting && preview !== null && preview.outcome !== 'REJECTED';
  return (
    <form className="row g-4" onSubmit={submit} noValidate>
      <div className="col-lg-8">
        {failure && <div className="alert alert-danger">{failure}</div>}
        <TargetSection draft={draft} set={set} errors={errors} />
        <FindingSection draft={draft} set={set} errors={errors} meta={meta} />
        <EvidenceSection draft={draft} set={set} errors={errors} />
        <DeliverySection draft={draft} set={set} errors={errors} meta={meta} />
        <div className="d-flex gap-2">
          <button type="submit" className="btn btn-primary" disabled={!canSubmit}>
            {submitting ? 'Creating…' : 'Create ticket'}
          </button>
          <button type="button" className="btn btn-outline-secondary" onClick={() => setDraft(emptyDraft())}>Clear</button>
        </div>
      </div>
      <div className="col-lg-4">
        <TicketPreviewPanel preview={preview} checking={checking} ready={ready} slaHint={slaLabel(meta, draft.severity)} />
      </div>
    </form>
  );
};

interface SectionProps {
  draft: TicketDraft;
  set: <K extends keyof TicketDraft>(key: K, value: TicketDraft[K]) => void;
  errors: Record<string, string[]>;
}

/** Messages for one field, from the last dry run. */
const FieldErrors: React.FC<{ messages?: string[] }> = ({ messages }) =>
  messages?.length ? <div className="invalid-feedback d-block">{messages.map((m, i) => <div key={i}>{m}</div>)}</div> : null;

const Section: React.FC<{ title: string; icon: string; children: React.ReactNode }> = ({ title, icon, children }) => (
  <fieldset className="card shadow-sm mb-3">
    <legend className="card-header h6 mb-0 float-none w-auto fs-6"><i className={`bi ${icon} me-2`}></i>{title}</legend>
    <div className="card-body">{children}</div>
  </fieldset>
);

const TargetSection: React.FC<SectionProps> = ({ draft, set, errors }) => (
  <Section title="Target" icon="bi-bullseye">
    <label htmlFor="ticket-account" className="form-label">AWS account</label>
    <AccountPicker value={draft.awsAccountId} invalid={!!errors.awsAccountId} onChange={id => set('awsAccountId', id)} />
    <FieldErrors messages={errors.awsAccountId} />
  </Section>
);

const FindingSection: React.FC<SectionProps & { meta: TicketMeta | null }> = ({ draft, set, errors, meta }) => (
  <Section title="Finding" icon="bi-shield-exclamation">
    <div className="mb-3">
      <label htmlFor="ticket-title" className="form-label">Title</label>
      <input id="ticket-title" className={`form-control${errors.title ? ' is-invalid' : ''}`} value={draft.title}
        maxLength={meta?.limits.title ?? 200} onChange={e => set('title', e.target.value)} />
      <FieldErrors messages={errors.title} />
    </div>
    <div className="mb-3">
      <span className="form-label d-block">Severity</span>
      <div className="btn-group" role="group" aria-label="Severity">
        {SEVERITIES.map(s => (
          <React.Fragment key={s}>
            <input type="radio" className="btn-check" name="severity" id={`sev-${s}`} checked={draft.severity === s}
              onChange={() => set('severity', s)} />
            <label className="btn btn-outline-secondary btn-sm" htmlFor={`sev-${s}`}>{s}</label>
          </React.Fragment>
        ))}
      </div>
      <span className="ms-2 small text-muted">{slaLabel(meta, draft.severity)}</span>
    </div>
    <div className="mb-3">
      <label htmlFor="ticket-description" className="form-label">Description <span className="text-muted small">(Markdown)</span></label>
      <textarea id="ticket-description" rows={6} className={`form-control${errors.description ? ' is-invalid' : ''}`}
        value={draft.description} onChange={e => set('description', e.target.value)} />
      <FieldErrors messages={errors.description} />
    </div>
    <div className="row g-3">
      <div className="col-md-8">
        <label htmlFor="ticket-action" className="form-label">Recommended action</label>
        <textarea id="ticket-action" rows={2} className="form-control" value={draft.recommendedAction}
          onChange={e => set('recommendedAction', e.target.value)} />
      </div>
      <div className="col-md-4">
        <label htmlFor="ticket-due" className="form-label">Due (optional)</label>
        <input id="ticket-due" type="datetime-local" className={`form-control${errors.dueAt ? ' is-invalid' : ''}`}
          value={draft.dueAt} onChange={e => set('dueAt', e.target.value)} />
        <FieldErrors messages={errors.dueAt} />
      </div>
    </div>
  </Section>
);

/** Resources (ARNs or TYPE=id@region), CVEs and links, with a warning for foreign ARNs. */
const EvidenceSection: React.FC<SectionProps> = ({ draft, set, errors }) => {
  const foreign = foreignArns(draft.resourcesText, draft.awsAccountId.trim());
  return (
    <Section title="Evidence" icon="bi-search">
      <div className="mb-3">
        <label htmlFor="ticket-resources" className="form-label">Affected resources <span className="text-muted small">(one per line)</span></label>
        <textarea id="ticket-resources" rows={4} className={`form-control font-monospace${errors.resources ? ' is-invalid' : ''}`}
          placeholder={'arn:aws:ec2:eu-central-1:123456789012:instance/i-0abc\nHOSTNAME=web01.example.com'}
          value={draft.resourcesText} onChange={e => set('resourcesText', e.target.value)} />
        <div className="form-text">Paste ARNs as they are: type and region are taken from them. Other lines: <code>TYPE=identifier@region</code>.</div>
        {foreign.length > 0 && <div className="text-warning small">{foreign.length} ARN(s) belong to another account.</div>}
        <FieldErrors messages={errors.resources} />
      </div>
      <div className="row g-3">
        <div className="col-md-6">
          <label htmlFor="ticket-cves" className="form-label">CVEs</label>
          <input id="ticket-cves" className={`form-control${errors.cves ? ' is-invalid' : ''}`} placeholder="CVE-2026-12345, …"
            value={draft.cvesText} onChange={e => set('cvesText', e.target.value)} />
          <FieldErrors messages={errors.cves} />
        </div>
        <div className="col-md-6">
          <label htmlFor="ticket-links" className="form-label">Reference links (https)</label>
          <input id="ticket-links" className={`form-control${errors.references ? ' is-invalid' : ''}`}
            value={draft.referencesText} onChange={e => set('referencesText', e.target.value)} />
          <FieldErrors messages={errors.references} />
        </div>
      </div>
    </Section>
  );
};

const DeliverySection: React.FC<SectionProps & { meta: TicketMeta | null }> = ({ draft, set, errors, meta }) => (
  <Section title="Delivery" icon="bi-envelope">
    <div className="form-check form-check-inline">
      <input className="form-check-input" type="radio" id="deliver-owners" checked={draft.deliverTo === 'owners'}
        onChange={() => set('deliverTo', 'owners')} />
      <label className="form-check-label" htmlFor="deliver-owners">Account owners</label>
    </div>
    <div className="form-check form-check-inline">
      <input className="form-check-input" type="radio" id="deliver-specific" checked={draft.deliverTo === 'specific'}
        onChange={() => set('deliverTo', 'specific')} />
      <label className="form-check-label" htmlFor="deliver-specific">Specific people</label>
    </div>
    {draft.deliverTo === 'owners' ? (
      <div className="form-check mt-2">
        <input className="form-check-input" type="checkbox" id="allow-unrouted" checked={draft.allowUnrouted}
          onChange={e => set('allowUnrouted', e.target.checked)} />
        <label className="form-check-label" htmlFor="allow-unrouted">Create even if nobody owns the account</label>
      </div>
    ) : (
      <div className="mt-2">
        <input className={`form-control${errors.recipients ? ' is-invalid' : ''}`} placeholder="name@company.example, …"
          aria-label="Recipients" value={draft.recipientsText} onChange={e => set('recipientsText', e.target.value)} />
        <div className="form-text">
          Up to {meta?.limits.recipients ?? 20} addresses known to SecMan
          {meta?.recipientOverrideDomains.length ? ` or on ${meta.recipientOverrideDomains.join(', ')}` : ''}. Mail grants no access.
        </div>
        <FieldErrors messages={errors.recipients} />
      </div>
    )}
  </Section>
);

export default TicketCreateForm;
