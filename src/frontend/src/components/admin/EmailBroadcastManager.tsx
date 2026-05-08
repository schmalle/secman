import { useEffect, useMemo, useRef, useState } from 'react';
import HtmlEditor from './HtmlEditor';
import {
  createBroadcast,
  getJob,
  getRecipientCount,
  listJobs,
  type EmailBroadcastJob,
} from '../../services/emailBroadcastService';

const ACTIVE_STATUSES = new Set<EmailBroadcastJob['status']>(['PENDING', 'PROCESSING']);

function StatusBadge({ status }: { status: EmailBroadcastJob['status'] }) {
  const cls =
    status === 'COMPLETED'
      ? 'bg-success'
      : status === 'FAILED'
      ? 'bg-danger'
      : status === 'PROCESSING'
      ? 'bg-primary'
      : 'bg-secondary';
  return <span className={`badge ${cls}`}>{status}</span>;
}

function progressPercent(job: EmailBroadcastJob): number {
  if (!job.totalRecipients) return 0;
  return Math.min(100, Math.round(((job.sentCount + job.failedCount) * 100) / job.totalRecipients));
}

export default function EmailBroadcastManager() {
  const [recipientCount, setRecipientCount] = useState<number | null>(null);
  const [subject, setSubject] = useState('');
  const [html, setHtml] = useState('<p>Hello,</p><p>Type your announcement here.</p>');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [jobs, setJobs] = useState<EmailBroadcastJob[]>([]);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [activeJobId, setActiveJobId] = useState<number | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const refresh = async () => {
    try {
      const [count, list] = await Promise.all([getRecipientCount(), listJobs()]);
      setRecipientCount(count);
      setJobs(list);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'Failed to load broadcast data';
      setError(msg);
    }
  };

  useEffect(() => {
    refresh();
  }, []);

  // Poll while there's an active job in the list.
  useEffect(() => {
    const hasActive = jobs.some((j) => ACTIVE_STATUSES.has(j.status));
    if (!hasActive) {
      if (pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
      return;
    }
    if (!pollRef.current) {
      pollRef.current = setInterval(() => {
        listJobs()
          .then(setJobs)
          .catch(() => {});
      }, 2500);
    }
    return () => {
      if (pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
    };
  }, [jobs]);

  const canSubmit = useMemo(() => {
    return subject.trim().length > 0 && html.replace(/<[^>]*>/g, '').trim().length > 0 && !submitting;
  }, [subject, html, submitting]);

  const handleSend = async () => {
    setError(null);
    setSubmitting(true);
    try {
      const job = await createBroadcast({ subject: subject.trim(), htmlContent: html });
      setActiveJobId(job.id);
      setJobs((prev) => [job, ...prev]);
      setConfirmOpen(false);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'Failed to start broadcast';
      setError(msg);
    } finally {
      setSubmitting(false);
    }
  };

  const activeJob = activeJobId ? jobs.find((j) => j.id === activeJobId) || null : null;

  // Poll the active job for live progress.
  useEffect(() => {
    if (!activeJobId) return;
    const id = activeJobId;
    const t = setInterval(async () => {
      try {
        const fresh = await getJob(id);
        setJobs((prev) => {
          const next = [...prev];
          const idx = next.findIndex((j) => j.id === id);
          if (idx >= 0) next[idx] = fresh;
          return next;
        });
        if (!ACTIVE_STATUSES.has(fresh.status)) {
          clearInterval(t);
        }
      } catch {
        /* ignore */
      }
    }, 2000);
    return () => clearInterval(t);
  }, [activeJobId]);

  return (
    <div>
      <div className="row mt-3">
        <div className="col-lg-8">
          <div className="card">
            <div className="card-header d-flex justify-content-between align-items-center">
              <h5 className="mb-0">Compose Broadcast</h5>
              <span className="text-muted small">
                Recipients:{' '}
                <strong>
                  {recipientCount === null ? '…' : recipientCount}
                </strong>{' '}
                user{recipientCount === 1 ? '' : 's'} (excludes pending accounts that have never logged in)
              </span>
            </div>
            <div className="card-body">
              {error && (
                <div className="alert alert-danger" role="alert">
                  {error}
                </div>
              )}

              <div className="mb-3">
                <label htmlFor="subject" className="form-label">
                  Subject
                </label>
                <input
                  id="subject"
                  className="form-control"
                  value={subject}
                  maxLength={255}
                  onChange={(e) => setSubject(e.target.value)}
                  placeholder="e.g. Scheduled maintenance this Saturday"
                />
              </div>

              <div className="mb-3">
                <label className="form-label">Message</label>
                <HtmlEditor value={html} onChange={setHtml} />
                <div className="form-text">
                  The SecMan logo and a footer are added automatically when sent.
                </div>
              </div>

              <div className="d-flex gap-2">
                <button
                  type="button"
                  className="btn btn-primary"
                  disabled={!canSubmit}
                  onClick={() => setConfirmOpen(true)}
                >
                  <i className="bi bi-send me-1"></i>
                  Send to {recipientCount ?? '…'} user{recipientCount === 1 ? '' : 's'}
                </button>
                <button
                  type="button"
                  className="btn btn-outline-secondary"
                  onClick={() => {
                    setSubject('');
                    setHtml('<p></p>');
                  }}
                >
                  Clear
                </button>
              </div>
            </div>
          </div>

          <div className="card mt-4">
            <div className="card-header">
              <h5 className="mb-0">Live Preview</h5>
            </div>
            <div className="card-body p-0" style={{ background: '#f4f4f4' }}>
              <div
                style={{
                  maxWidth: 600,
                  margin: '20px auto',
                  background: '#fff',
                  borderRadius: 6,
                  overflow: 'hidden',
                }}
              >
                <div style={{ padding: '24px 24px 0 24px', textAlign: 'center' }}>
                  <img
                    src="/SecManLogo.png"
                    alt="SecMan"
                    style={{ maxWidth: 180, height: 'auto' }}
                  />
                </div>
                <div
                  style={{ padding: 24, lineHeight: 1.6, fontSize: 14, color: '#333' }}
                  dangerouslySetInnerHTML={{ __html: html }}
                />
                <div
                  style={{
                    padding: '16px 24px',
                    borderTop: '1px solid #e5e5e5',
                    fontSize: 12,
                    color: '#888',
                    textAlign: 'center',
                  }}
                >
                  This is an automated notification from SecMan. Please do not reply to this email.
                </div>
              </div>
            </div>
          </div>
        </div>

        <div className="col-lg-4">
          <div className="card">
            <div className="card-header d-flex justify-content-between align-items-center">
              <h5 className="mb-0">Recent broadcasts</h5>
              <button type="button" className="btn btn-sm btn-outline-secondary" onClick={refresh}>
                <i className="bi bi-arrow-clockwise"></i>
              </button>
            </div>
            <div className="list-group list-group-flush">
              {jobs.length === 0 && <div className="list-group-item text-muted">No broadcasts yet.</div>}
              {jobs.map((j) => (
                <div key={j.id} className="list-group-item">
                  <div className="d-flex justify-content-between align-items-start">
                    <div className="me-2" style={{ minWidth: 0, flex: 1 }}>
                      <div className="text-truncate fw-semibold" title={j.subject}>
                        {j.subject}
                      </div>
                      <div className="small text-muted">
                        {j.createdBy} · {new Date(j.createdAt).toLocaleString()}
                      </div>
                    </div>
                    <StatusBadge status={j.status} />
                  </div>
                  <div className="progress mt-2" style={{ height: 6 }}>
                    <div
                      className={`progress-bar ${j.status === 'FAILED' ? 'bg-danger' : ''}`}
                      role="progressbar"
                      style={{ width: `${progressPercent(j)}%` }}
                      aria-valuenow={progressPercent(j)}
                      aria-valuemin={0}
                      aria-valuemax={100}
                    />
                  </div>
                  <div className="small text-muted mt-1">
                    {j.sentCount} sent
                    {j.failedCount > 0 && (
                      <span className="text-danger ms-2">{j.failedCount} failed</span>
                    )}{' '}
                    of {j.totalRecipients}
                  </div>
                  {j.errorMessage && (
                    <div className="small text-danger mt-1" title={j.errorMessage}>
                      {j.errorMessage}
                    </div>
                  )}
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>

      {/* Confirm modal */}
      {confirmOpen && (
        <div
          className="modal show d-block"
          tabIndex={-1}
          role="dialog"
          style={{ background: 'rgba(0,0,0,0.5)' }}
          onClick={() => !submitting && setConfirmOpen(false)}
        >
          <div className="modal-dialog modal-dialog-centered" onClick={(e) => e.stopPropagation()}>
            <div className="modal-content">
              <div className="modal-header">
                <h5 className="modal-title">Confirm broadcast</h5>
                <button
                  type="button"
                  className="btn-close"
                  disabled={submitting}
                  onClick={() => setConfirmOpen(false)}
                />
              </div>
              <div className="modal-body">
                <p>
                  This will send the email to{' '}
                  <strong>
                    {recipientCount ?? 0} user{recipientCount === 1 ? '' : 's'}
                  </strong>
                  . Pending accounts that have never logged in are excluded.
                </p>
                <p className="mb-0">
                  Subject: <em>{subject}</em>
                </p>
              </div>
              <div className="modal-footer">
                <button
                  type="button"
                  className="btn btn-secondary"
                  disabled={submitting}
                  onClick={() => setConfirmOpen(false)}
                >
                  Cancel
                </button>
                <button
                  type="button"
                  className="btn btn-primary"
                  disabled={submitting}
                  onClick={handleSend}
                >
                  {submitting ? 'Sending…' : 'Send now'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {activeJob && ACTIVE_STATUSES.has(activeJob.status) && (
        <div
          className="position-fixed bottom-0 end-0 m-4 p-3 bg-white border rounded shadow"
          style={{ width: 320, zIndex: 1080 }}
        >
          <div className="d-flex justify-content-between align-items-center mb-2">
            <strong>Sending broadcast…</strong>
            <StatusBadge status={activeJob.status} />
          </div>
          <div className="progress" style={{ height: 6 }}>
            <div
              className="progress-bar progress-bar-striped progress-bar-animated"
              style={{ width: `${progressPercent(activeJob)}%` }}
            />
          </div>
          <div className="small text-muted mt-1">
            {activeJob.sentCount + activeJob.failedCount} / {activeJob.totalRecipients}
          </div>
        </div>
      )}
    </div>
  );
}
