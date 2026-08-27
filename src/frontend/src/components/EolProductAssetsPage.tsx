import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { getEolFindingsForProduct, type EolFinding } from '../services/eolService';
import {
  createEolProductBroadcast,
  getEolProductJob,
  getEolProductRecipientCount,
  type EmailBroadcastJob,
} from '../services/emailBroadcastService';
import { getUser } from '../utils/auth';
import { downloadCsv } from '../utils/csv';
import { canNotifyProductUsers } from './productNotifyAccess';
import HtmlEditor from './admin/HtmlEditor';
import { describeDeadline, statusBadge, subjectLabel } from './eolFormat';

const PAGE_SIZE = 100;
/** Backend caps `pageSize` at 500 (EolQueryService.MAX_PAGE_SIZE) — ask for exactly that. */
const EXPORT_PAGE_SIZE = 500;
/** Backstop so a server that stops advancing cannot spin the export loop forever. */
const MAX_EXPORT_PAGES = 200;
const ACTIVE_STATUSES = new Set<EmailBroadcastJob['status']>(['PENDING', 'PROCESSING']);
const CC_EMAIL_PATTERN = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

interface Props {
  product: string;
}

/**
 * Product names come from the upstream catalogue and reach us verbatim
 * ("Universal Forwarder", ".NET Core"), so they are reduced to a filename-safe
 * slug before being used as one.
 */
function slugifyProduct(product: string): string {
  const slug = product.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
  return slug || 'product';
}

/**
 * Systems affected by one EOL product, reached by clicking a row in the
 * "Top 10 Most Often EOL Products" table. Everything here is already
 * asset-scoped by the backend (`GET /api/eol/products/{product}/assets`) —
 * this component never applies its own access filter.
 */
const EolProductAssetsPage: React.FC<Props> = ({ product }) => {
  // Roles live in sessionStorage, which does not exist during Astro's server
  // render. Reading them on the first render would make server and client HTML
  // disagree and React would discard the island (see EolDashboard.tsx), so both
  // sides render role-free and the Contact button appears after mount.
  const [roles, setRoles] = useState<string[]>([]);
  useEffect(() => {
    setRoles(getUser()?.roles ?? []);
  }, []);
  const canContact = canNotifyProductUsers(roles);

  const [findings, setFindings] = useState<EolFinding[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const eolCount = useMemo(() => findings.filter((f) => f.status === 'EOL').length, [findings]);
  const approachingCount = useMemo(
    () => findings.filter((f) => f.status === 'APPROACHING_EOL').length,
    [findings],
  );

  const load = useCallback(() => {
    setLoading(true);
    getEolFindingsForProduct(product, page, PAGE_SIZE)
      .then((response) => {
        setFindings(response.findings);
        setTotal(response.total);
        setError(null);
      })
      .catch((err) => setError(err instanceof Error ? err.message : 'Failed to load affected systems'))
      .finally(() => setLoading(false));
  }, [product, page]);

  useEffect(() => {
    load();
  }, [load]);

  // CSV export state. The table only ever holds one page, so the export re-reads
  // every page from the API rather than serialising what happens to be on screen.
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);

  const handleExportCsv = async () => {
    setExporting(true);
    setExportError(null);

    try {
      const all: EolFinding[] = [];
      for (let pageIndex = 0; pageIndex < MAX_EXPORT_PAGES; pageIndex++) {
        const response = await getEolFindingsForProduct(product, pageIndex, EXPORT_PAGE_SIZE);
        all.push(...response.findings);
        if (response.findings.length === 0 || all.length >= response.total) break;
      }

      downloadCsv(
        `eol-${slugifyProduct(product)}-${new Date().toISOString().slice(0, 10)}.csv`,
        [
          'System',
          'Owner',
          'Cloud account',
          'Cloud instance',
          'AD domain',
          'Version',
          'Release cycle',
          'End of support',
          'Status',
        ],
        all.map((finding) => [
          finding.assetName || '',
          finding.assetOwner || '',
          finding.cloudAccountId || '',
          finding.cloudInstanceId || '',
          finding.adDomain || '',
          finding.componentVersion || '',
          finding.cycle,
          describeDeadline(finding.eolDate, finding.daysUntilEol),
          statusBadge(finding.status).label,
        ]),
      );
    } catch (err) {
      console.error('Failed to export EOL findings as CSV:', err);
      setExportError(err instanceof Error ? err.message : 'Failed to export the affected systems.');
    } finally {
      setExporting(false);
    }
  };

  // Contact modal state
  const [showContactModal, setShowContactModal] = useState(false);
  const [contactSubject, setContactSubject] = useState('');
  const [contactHtml, setContactHtml] = useState('');
  const [recipientCount, setRecipientCount] = useState<number | null>(null);
  const [loadingRecipients, setLoadingRecipients] = useState(false);
  const [sending, setSending] = useState(false);
  const [contactJob, setContactJob] = useState<EmailBroadcastJob | null>(null);
  const [contactError, setContactError] = useState<string | null>(null);
  const [ccEmails, setCcEmails] = useState<string[]>([]);
  const [ccInput, setCcInput] = useState('');
  const [ccInputError, setCcInputError] = useState<string | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const openContactModal = async () => {
    setContactSubject(`Action required: ${product} is reaching end of life`);
    setContactHtml(
      `<p>Hello,</p><p>please review the systems assigned to you that are running <strong>${product}</strong>, which is end of life or approaching end of life.</p>`,
    );
    setRecipientCount(null);
    setContactJob(null);
    setContactError(null);
    setCcEmails([]);
    setCcInput('');
    setCcInputError(null);
    setShowContactModal(true);
    setLoadingRecipients(true);

    try {
      const count = await getEolProductRecipientCount(product);
      setRecipientCount(count);
    } catch (err) {
      console.error('Failed to load EOL contact recipients:', err);
      setContactError(err instanceof Error ? err.message : 'Failed to load recipients for this product.');
    } finally {
      setLoadingRecipients(false);
    }
  };

  const closeContactModal = () => {
    if (sending) return;
    setShowContactModal(false);
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  };

  const addCcEmail = () => {
    const value = ccInput.trim();
    if (!value) return;
    if (!CC_EMAIL_PATTERN.test(value)) {
      setCcInputError('Enter a valid email address.');
      return;
    }
    if (ccEmails.some((email) => email.toLowerCase() === value.toLowerCase())) {
      setCcInput('');
      setCcInputError(null);
      return;
    }
    setCcEmails((current) => [...current, value]);
    setCcInput('');
    setCcInputError(null);
  };

  const removeCcEmail = (email: string) => {
    setCcEmails((current) => current.filter((existing) => existing !== email));
  };

  const handleCcInputKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault();
      addCcEmail();
    }
  };

  const handleSendContact = async () => {
    const trimmedSubject = contactSubject.trim();
    const trimmedHtml = contactHtml.trim();
    if (!trimmedSubject || !trimmedHtml) {
      setContactError('Subject and message are required.');
      return;
    }

    setSending(true);
    setContactError(null);

    try {
      const job = await createEolProductBroadcast({
        productName: product,
        subject: trimmedSubject,
        htmlContent: trimmedHtml,
        ccRecipients: ccEmails,
      });
      setContactJob(job);
      setRecipientCount(job.totalRecipients);

      if (ACTIVE_STATUSES.has(job.status)) {
        pollRef.current = setInterval(async () => {
          try {
            const fresh = await getEolProductJob(job.id);
            setContactJob(fresh);
            if (!ACTIVE_STATUSES.has(fresh.status) && pollRef.current) {
              clearInterval(pollRef.current);
              pollRef.current = null;
            }
          } catch {
            /* ignore transient polling errors */
          }
        }, 2000);
      }
    } catch (err) {
      console.error('Failed to send EOL contact message:', err);
      setContactError(err instanceof Error ? err.message : 'Failed to send message.');
    } finally {
      setSending(false);
    }
  };

  useEffect(() => {
    return () => {
      if (pollRef.current) clearInterval(pollRef.current);
    };
  }, []);

  return (
    <div className="container-fluid py-4">
      <nav aria-label="breadcrumb">
        <ol className="breadcrumb">
          <li className="breadcrumb-item">
            <a href="/vulnerability-statistics">Vulnerability Statistics</a>
          </li>
          <li className="breadcrumb-item active" aria-current="page">
            {product}
          </li>
        </ol>
      </nav>

      <div className="d-flex flex-wrap justify-content-between align-items-center mb-3 gap-2">
        <h1 className="h3 mb-0">
          <i className="bi bi-hourglass-bottom me-2"></i>
          {product}
        </h1>
        <div className="d-flex flex-wrap gap-2">
          <button
            type="button"
            className="btn btn-outline-secondary"
            onClick={handleExportCsv}
            disabled={loading || exporting || total === 0}
            title="Download every affected system as CSV"
          >
            {exporting ? (
              <>
                <span className="spinner-border spinner-border-sm me-1" role="status"></span>
                Exporting...
              </>
            ) : (
              <>
                <i className="bi bi-filetype-csv me-1"></i>
                Export CSV
              </>
            )}
          </button>
          {canContact && (
          <button
            type="button"
            className="btn btn-outline-primary"
            onClick={openContactModal}
            disabled={loading || findings.length === 0}
            title="Contact the owners of the affected systems"
          >
            <i className="bi bi-envelope me-1"></i>
            Contact affected owners
          </button>
          )}
        </div>
      </div>

      {error && (
        <div className="alert alert-danger" role="alert">
          <i className="bi bi-exclamation-triangle me-2"></i>
          {error}
        </div>
      )}

      {exportError && (
        <div className="alert alert-danger" role="alert">
          <i className="bi bi-exclamation-triangle me-2"></i>
          {exportError}
        </div>
      )}

      <div className="row mb-3">
        <div className="col-auto">
          <span className="badge scand-critical fs-6">{eolCount} end of life</span>
        </div>
        <div className="col-auto">
          <span className="badge scand-high fs-6">{approachingCount} approaching EOL</span>
        </div>
        <div className="col-auto">
          <span className="badge bg-light text-dark fs-6">{total} total finding{total === 1 ? '' : 's'}</span>
        </div>
      </div>

      <div className="card">
        <div
          className="card-header"
          style={{ backgroundColor: 'var(--scand-bg-header)', color: 'var(--scand-text-on-header)' }}
        >
          <h5 className="mb-0">Affected systems</h5>
        </div>
        <div className="table-responsive">
          <table className="table table-sm table-hover mb-0">
            <thead className="table-light">
              <tr>
                <th>System</th>
                <th>Type</th>
                <th>Cloud account</th>
                <th>Cloud instance</th>
                <th>AD domain</th>
                <th>Version</th>
                <th>Release cycle</th>
                <th>End of support</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {loading && (
                <tr>
                  <td colSpan={9} className="text-center py-4">
                    Loading…
                  </td>
                </tr>
              )}
              {!loading && findings.length === 0 && (
                <tr>
                  <td colSpan={9} className="text-center text-muted py-4">
                    No systems found for this product in your accessible scope.
                  </td>
                </tr>
              )}
              {!loading &&
                findings.map((finding) => {
                  const badge = statusBadge(finding.status);
                  return (
                    <tr key={finding.id}>
                      <td>
                        {finding.assetId ? (
                          <a href={`/assets/${finding.assetId}`}>{finding.assetName}</a>
                        ) : (
                          finding.assetName || '-'
                        )}
                      </td>
                      <td className="text-muted small">{subjectLabel(finding.subjectType)}</td>
                      <td>{finding.cloudAccountId || '-'}</td>
                      <td>{finding.cloudInstanceId || '-'}</td>
                      <td>{finding.adDomain || '-'}</td>
                      <td>{finding.componentVersion || '-'}</td>
                      <td>{finding.cycle}</td>
                      <td>{describeDeadline(finding.eolDate, finding.daysUntilEol)}</td>
                      <td>
                        <span className={badge.className}>{badge.label}</span>
                      </td>
                    </tr>
                  );
                })}
            </tbody>
          </table>
        </div>
        <div className="card-footer d-flex justify-content-between align-items-center">
          <span className="text-muted small">
            {total} system{total === 1 ? '' : 's'} · page {page + 1} of {totalPages}
          </span>
          <div className="btn-group">
            <button
              type="button"
              className="btn btn-outline-secondary btn-sm"
              disabled={page === 0}
              onClick={() => setPage((current) => Math.max(0, current - 1))}
            >
              Previous
            </button>
            <button
              type="button"
              className="btn btn-outline-secondary btn-sm"
              disabled={page + 1 >= totalPages}
              onClick={() => setPage((current) => current + 1)}
            >
              Next
            </button>
          </div>
        </div>
      </div>

      {showContactModal && (
        <>
          <div className="modal d-block" tabIndex={-1} role="dialog" aria-modal="true">
            <div className="modal-dialog modal-lg modal-dialog-centered">
              <div className="modal-content">
                <div className="modal-header">
                  <h2 className="modal-title h5">
                    <i className="bi bi-envelope me-2"></i>
                    Contact affected owners
                  </h2>
                  <button
                    type="button"
                    className="btn-close"
                    onClick={closeContactModal}
                    aria-label="Close"
                    disabled={sending}
                  ></button>
                </div>
                <div className="modal-body">
                  <div className="d-flex flex-wrap align-items-center gap-2 mb-3">
                    <span className="badge bg-secondary">{product}</span>
                    {loadingRecipients ? (
                      <span className="text-muted small">
                        <span className="spinner-border spinner-border-sm me-1" role="status"></span>
                        Loading recipients...
                      </span>
                    ) : (
                      <span className="text-muted small">
                        {recipientCount ?? 0} recipient{recipientCount === 1 ? '' : 's'}
                      </span>
                    )}
                  </div>

                  {contactError && (
                    <div className="alert alert-danger" role="alert">
                      <i className="bi bi-exclamation-triangle me-2"></i>
                      {contactError}
                    </div>
                  )}

                  {contactJob && (
                    <div className="alert alert-success" role="alert">
                      <i className="bi bi-check-circle me-2"></i>
                      Message queued for {contactJob.totalRecipients} recipient
                      {contactJob.totalRecipients === 1 ? '' : 's'}
                      {ACTIVE_STATUSES.has(contactJob.status)
                        ? ` — sending (${contactJob.sentCount + contactJob.failedCount}/${contactJob.totalRecipients})…`
                        : ` — ${contactJob.sentCount} sent${contactJob.failedCount > 0 ? `, ${contactJob.failedCount} failed` : ''}.`}
                    </div>
                  )}

                  <div className="mb-3">
                    <label htmlFor="eolContactSubject" className="form-label">
                      Subject
                    </label>
                    <input
                      id="eolContactSubject"
                      type="text"
                      className="form-control"
                      value={contactSubject}
                      onChange={(e) => setContactSubject(e.target.value)}
                      disabled={sending || Boolean(contactJob)}
                      maxLength={255}
                    />
                  </div>

                  <div className="mb-3">
                    <label htmlFor="eolContactCc" className="form-label">
                      Cc (optional)
                    </label>
                    <div className="d-flex gap-2">
                      <input
                        id="eolContactCc"
                        type="email"
                        className="form-control"
                        placeholder="name@example.com"
                        value={ccInput}
                        onChange={(e) => {
                          setCcInput(e.target.value);
                          setCcInputError(null);
                        }}
                        onKeyDown={handleCcInputKeyDown}
                        disabled={sending || Boolean(contactJob)}
                      />
                      <button
                        type="button"
                        className="btn btn-outline-secondary text-nowrap"
                        onClick={addCcEmail}
                        disabled={sending || Boolean(contactJob) || !ccInput.trim()}
                      >
                        Add
                      </button>
                    </div>
                    {ccInputError && <div className="text-danger small mt-1">{ccInputError}</div>}
                    {ccEmails.length > 0 && (
                      <div className="d-flex flex-wrap gap-1 mt-2">
                        {ccEmails.map((email) => (
                          <span
                            key={email}
                            className="badge bg-light text-dark border d-inline-flex align-items-center gap-1"
                          >
                            {email}
                            {!sending && !contactJob && (
                              <button
                                type="button"
                                className="btn-close"
                                style={{ fontSize: '0.55rem' }}
                                aria-label={`Remove ${email}`}
                                onClick={() => removeCcEmail(email)}
                              ></button>
                            )}
                          </span>
                        ))}
                      </div>
                    )}
                  </div>

                  <div className="mb-0">
                    <label className="form-label">Message</label>
                    <HtmlEditor value={contactHtml} onChange={setContactHtml} minHeight={220} />
                    <div className="form-text">
                      <i className="bi bi-table me-1"></i>
                      The affected-systems table is appended automatically below your message.
                      Each recipient only sees the systems they have access to.
                    </div>
                  </div>
                </div>
                <div className="modal-footer">
                  <button
                    type="button"
                    className="btn btn-outline-secondary"
                    onClick={closeContactModal}
                    disabled={sending}
                  >
                    Close
                  </button>
                  <button
                    type="button"
                    className="btn btn-primary"
                    onClick={handleSendContact}
                    disabled={sending || loadingRecipients || Boolean(contactJob) || recipientCount === 0}
                  >
                    {sending ? (
                      <>
                        <span className="spinner-border spinner-border-sm me-1" role="status"></span>
                        Sending...
                      </>
                    ) : (
                      <>
                        <i className="bi bi-send me-1"></i>
                        Send message
                      </>
                    )}
                  </button>
                </div>
              </div>
            </div>
          </div>
          <div className="modal-backdrop show"></div>
        </>
      )}
    </div>
  );
};

export default EolProductAssetsPage;
