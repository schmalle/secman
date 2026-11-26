/**
 * Pure helpers for the security-ticket pages. No DOM, no network, so node:test covers them
 * (securityTicketLogic.test.ts). The server stays the authority on every rule; these only
 * shape input and output for the form.
 */

export type Severity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'INFO';
/** A ticket is open until someone closes it; closed tickets can be reopened. */
export type TicketStatus = 'OPEN' | 'CLOSED';

/** One affected resource; type and region may be left out for ARNs. */
export interface ResourceInput { type?: string; identifier: string; region?: string }

/** The finding as the API takes it (`ticket` in a request). */
export interface TicketFields {
  externalReference?: string;
  awsAccountId: string;
  title: string;
  severity: string;
  description: string;
  recommendedAction?: string;
  cves?: string[];
  resources?: ResourceInput[];
  references?: string[];
  sourceSystem?: string;
  dueAt?: string;
}

/** Sending options, once per request (`delivery`). */
export interface Delivery { dryRun?: boolean; recipients?: string[]; allowUnrouted?: boolean }

/** One server-side problem with one input field, e.g. `resources[2].region`. */
export interface FieldError { field: string; code: string; message: string }

/** Outcome of one submitted ticket, the same shape for single and batch calls. */
export interface CreateResult {
  index: number;
  outcome: 'CREATED' | 'DUPLICATE' | 'REJECTED' | 'WOULD_CREATE';
  ticketId?: number | null;
  ticketKey?: string | null;
  status?: string | null;
  recipientCount?: number | null;
  recipients: string[];
  recipientsOverridden: boolean;
  dueAt?: string | null;
  dryRun: boolean;
  errorCode?: string | null;
  errors: FieldError[];
}

/** Totals plus one result per submitted ticket. */
export interface BatchResult { dryRun: boolean; created: number; duplicates: number; rejected: number; results: CreateResult[] }

/** Submission rules from GET /api/security-tickets/meta. */
export interface TicketMeta {
  severities: { name: Severity; slaDays: number | null }[];
  limits: Record<string, number>;
  resourceTypes: string[];
  maxTicketsPerHour: number;
  recipientOverrideDomains: string[];
}

/** One account in the picker; no email addresses. */
export interface AccountOption { awsAccountId: string; accountName: string | null; recipientCount: number; routable: boolean }

/** The editable state of the single-ticket form. List fields are kept as raw text until submit. */
export interface TicketDraft {
  awsAccountId: string;
  title: string;
  severity: Severity;
  description: string;
  recommendedAction: string;
  cvesText: string;
  resourcesText: string;
  referencesText: string;
  sourceSystem: string;
  dueAt: string;
  deliverTo: 'owners' | 'specific';
  recipientsText: string;
  allowUnrouted: boolean;
}

/** A blank form; HIGH is preselected as the most common severity for Cyber Defence findings. */
export const emptyDraft = (): TicketDraft => ({
  awsAccountId: '', title: '', severity: 'HIGH', description: '', recommendedAction: '', cvesText: '',
  resourcesText: '', referencesText: '', sourceSystem: '', dueAt: '', deliverTo: 'owners', recipientsText: '',
  allowUnrouted: false,
});

/** Splits on commas, semicolons, whitespace and newlines; drops empties and repeats. */
export function splitList(text: string): string[] {
  return [...new Set(text.split(/[\s,;]+/).map(s => s.trim()).filter(Boolean))];
}

/** The account id inside an ARN, '' for account-less ARNs (S3), or null when `value` is no ARN. */
export function arnAccount(value: string): string | null {
  const parts = value.trim().split(':');
  if (parts[0] !== 'arn' || parts.length < 6) return null;
  return parts[4] === 'aws' ? '' : parts[4];
}

/**
 * One resource per line. An ARN is sent as-is and the server derives type and region from
 * it; other lines are `TYPE=identifier[@region]` or a bare identifier (the server then asks
 * for a type).
 */
export function parseResourceLines(text: string): ResourceInput[] {
  return text.split('\n').map(line => line.trim()).filter(Boolean).map(line => {
    if (arnAccount(line) !== null) return { identifier: line };
    const eq = line.indexOf('=');
    if (eq <= 0) return { identifier: line };
    const type = line.slice(0, eq).trim().toUpperCase();
    let identifier = line.slice(eq + 1).trim();
    let region: string | undefined;
    const at = identifier.lastIndexOf('@');
    if (at > 0 && /^[a-z0-9-]+$/.test(identifier.slice(at + 1))) {
      region = identifier.slice(at + 1);
      identifier = identifier.slice(0, at);
    }
    return region ? { type, identifier, region } : { type, identifier };
  });
}

/** Pasted ARNs whose account differs from the ticket's, so the form can warn before the server rejects. */
export function foreignArns(text: string, accountId: string): string[] {
  if (!/^\d{12}$/.test(accountId)) return [];
  return parseResourceLines(text)
    .map(r => r.identifier)
    .filter(id => { const acct = arnAccount(id); return acct !== null && acct !== '' && acct !== accountId; });
}

const optional = (value: string): string | undefined => (value.trim() ? value.trim() : undefined);

/** Builds `{ticket, delivery}` from the form. [reference] is the per-form idempotency key. */
export function buildRequest(draft: TicketDraft, reference: string, dryRun: boolean): { ticket: TicketFields; delivery: Delivery } {
  const dueAt = optional(draft.dueAt);
  const ticket: TicketFields = {
    externalReference: reference,
    awsAccountId: draft.awsAccountId.trim(),
    title: draft.title,
    severity: draft.severity,
    description: draft.description,
    recommendedAction: optional(draft.recommendedAction),
    cves: splitList(draft.cvesText),
    resources: parseResourceLines(draft.resourcesText),
    references: splitList(draft.referencesText),
    sourceSystem: optional(draft.sourceSystem),
    // <input type="datetime-local"> has no zone; the form means the viewer's local time.
    dueAt: dueAt ? new Date(dueAt).toISOString() : undefined,
  };
  const delivery: Delivery = { dryRun };
  if (draft.deliverTo === 'specific') delivery.recipients = splitList(draft.recipientsText);
  else if (draft.allowUnrouted) delivery.allowUnrouted = true;
  return { ticket, delivery };
}

/** True once the four required fields have something in them; earlier dry runs would only report blanks. */
export function readyForPreview(draft: TicketDraft): boolean {
  return /^\d{12}$/.test(draft.awsAccountId.trim()) && !!draft.title.trim() && !!draft.description.trim();
}

/** Server field paths → form field keys, so each message sits next to its input. */
export function errorsByField(errors: FieldError[]): Record<string, string[]> {
  const byField: Record<string, string[]> = {};
  for (const e of errors) {
    const root = e.field.startsWith('delivery.recipients') ? 'recipients' : e.field.replace(/\[.*$/, '').split('.')[0];
    const key = ({ cves: 'cves', resources: 'resources', references: 'references' } as Record<string, string>)[root] ?? root;
    const prefix = e.field.includes('[') ? `${e.field}: ` : '';
    (byField[key] ??= []).push(prefix + e.message);
  }
  return byField;
}

/** "due in 7 days (HIGH SLA)" or "no deadline". */
export function slaLabel(meta: TicketMeta | null, severity: Severity): string {
  const days = meta?.severities.find(s => s.name === severity)?.slaDays;
  if (days === undefined) return '';
  return days ? `due in ${days} day${days === 1 ? '' : 's'} (${severity} SLA)` : 'no deadline';
}

/** A fresh idempotency key per opened form, so a double submit returns DUPLICATE. */
export function newReference(random: () => string = () => crypto.randomUUID()): string {
  return `ui-${random()}`;
}

/** "3 days overdue", "due in 2 days", "due today", or '' when there is no due date or it is closed. */
export function dueText(dueAt: string | null | undefined, status: TicketStatus, now: Date = new Date()): string {
  if (!dueAt || status === 'CLOSED') return '';
  const days = Math.floor((new Date(dueAt).getTime() - now.getTime()) / 86_400_000);
  if (days < 0) return `${-days} day${days === -1 ? '' : 's'} overdue`;
  if (days === 0) return 'due today';
  return `due in ${days} day${days === 1 ? '' : 's'}`;
}

export const SEVERITY_BADGE: Record<string, string> = {
  CRITICAL: 'bg-danger', HIGH: 'bg-warning text-dark', MEDIUM: 'bg-info text-dark', LOW: 'bg-secondary', INFO: 'bg-light text-dark',
};

/** Minimal RFC 4180 reader: quoted fields, doubled quotes, commas and newlines inside quotes. */
export function parseCsv(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = '';
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"' && text[i + 1] === '"') { field += '"'; i++; }
      else if (c === '"') quoted = false;
      else field += c;
    } else if (c === '"') quoted = true;
    else if (c === ',') { row.push(field); field = ''; }
    else if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') i++;
      row.push(field); field = '';
      if (row.some(v => v.trim())) rows.push(row);
      row = [];
    } else field += c;
  }
  row.push(field);
  if (row.some(v => v.trim())) rows.push(row);
  return rows;
}

const CSV_COLUMNS = ['externalReference', 'awsAccountId', 'title', 'severity', 'description', 'recommendedAction',
  'cves', 'resources', 'references', 'sourceSystem', 'dueAt'] as const;

/** [parseBulk] plus the per-call size limit, ready for a batch request. */
export function prepareBulk(text: string, max = 100): { tickets: TicketFields[]; error?: string } {
  const parsed = parseBulk(text);
  if (parsed.error) return parsed;
  if (parsed.tickets.length > max) return { tickets: [], error: `At most ${max} tickets per submission (found ${parsed.tickets.length})` };
  return parsed;
}

/** Delivery for a bulk run: named recipients when given, the account owners otherwise. */
export function bulkDelivery(recipientsText: string, dryRun: boolean): Delivery {
  const recipients = splitList(recipientsText);
  return recipients.length ? { dryRun, recipients } : { dryRun };
}

/**
 * Bulk input: a JSON array (or `{tickets: [...]}`) of ticket objects, or CSV with a header row
 * using the ticket field names. In CSV, `cves`, `resources` and `references` hold several
 * values separated by `;`; resources use the same syntax as the form.
 */
export function parseBulk(text: string): { tickets: TicketFields[]; error?: string } {
  const trimmed = text.trim();
  if (!trimmed) return { tickets: [], error: 'Nothing to import' };
  if (trimmed.startsWith('[') || trimmed.startsWith('{')) {
    try {
      const parsed = JSON.parse(trimmed);
      const list = Array.isArray(parsed) ? parsed : parsed?.tickets;
      if (!Array.isArray(list)) return { tickets: [], error: 'JSON must be an array of tickets or {"tickets": [...]}' };
      return { tickets: list as TicketFields[] };
    } catch {
      return { tickets: [], error: 'Not valid JSON' };
    }
  }
  const [header, ...rows] = parseCsv(trimmed);
  const columns = header.map(h => h.trim());
  const unknown = columns.filter(c => !(CSV_COLUMNS as readonly string[]).includes(c));
  if (unknown.length) return { tickets: [], error: `Unknown CSV column(s): ${unknown.join(', ')}` };
  const tickets = rows.map(cells => {
    const value = (name: string) => (cells[columns.indexOf(name)] ?? '').trim();
    const ticket: TicketFields = { awsAccountId: value('awsAccountId'), title: value('title'), severity: value('severity'),
      description: value('description') };
    for (const name of ['externalReference', 'recommendedAction', 'sourceSystem', 'dueAt'] as const) {
      if (value(name)) ticket[name] = value(name);
    }
    if (value('cves')) ticket.cves = splitList(value('cves'));
    if (value('references')) ticket.references = splitList(value('references'));
    if (value('resources')) ticket.resources = parseResourceLines(value('resources').split(';').join('\n'));
    return ticket;
  });
  return { tickets };
}
