import React, { useEffect, useRef, useState } from 'react';
import {
  startAiJob,
  pollJobUntilTerminal,
  cancelAiJob,
  getAiJob,
  openJobEventStream,
  type AiJobStatusDto,
  type ConfidenceBand,
  type JobProgressEvent,
  type SuggestionScope,
} from '../services/aiSuggestions';

/**
 * Feature 088 — AI-Assisted Risk Assessment Answers.
 *
 * Modal that lets an ADMIN/SECCHAMPION trigger an AI pre-fill on a given
 * risk assessment. Once started, polls the job until terminal and surfaces a
 * live counter. SSE replaces polling in US2 (T051/T054).
 */
interface Props {
  assessmentId: number;
  assessmentLabel: string;
  hasEditedRows: boolean;
  onClose: () => void;
  onCompleted: () => void;
}

const AiPrefillModal: React.FC<Props> = ({
  assessmentId,
  assessmentLabel,
  hasEditedRows,
  onClose,
  onCompleted,
}) => {
  const [scope, setScope] = useState<SuggestionScope>('WHOLE_ASSESSMENT');
  const [force, setForce] = useState(false);
  const [starting, setStarting] = useState(false);
  const [jobId, setJobId] = useState<number | null>(null);
  const [status, setStatus] = useState<AiJobStatusDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [bandCounts, setBandCounts] = useState<Record<ConfidenceBand, number>>({ HIGH: 0, MEDIUM: 0, LOW: 0 });
  const esRef = useRef<EventSource | null>(null);

  const running = !!jobId && status && status.status !== 'COMPLETED' && status.status !== 'FAILED' && status.status !== 'CANCELLED';

  // Once we have a jobId, prefer SSE; fall back to polling if it errors.
  useEffect(() => {
    if (!jobId) return;
    let cancelled = false;
    let pollPromise: Promise<unknown> | null = null;

    const startPolling = () => {
      pollPromise = pollJobUntilTerminal(
        assessmentId,
        jobId,
        s => { if (!cancelled) setStatus(s); }
      )
        .then(() => { if (!cancelled) onCompleted(); })
        .catch(e => { if (!cancelled) setError(e.message ?? 'Polling failed'); });
    };

    try {
      const es = openJobEventStream(assessmentId, jobId);
      esRef.current = es;
      es.onmessage = (msg: MessageEvent) => {
        try {
          const ev = JSON.parse(msg.data) as JobProgressEvent;
          setStatus(prev => prev ? {
            ...prev,
            completedCount: ev.completedCount,
            failedCount: ev.failedCount,
            totalCount: ev.totalCount,
            totalCostUsd: ev.totalCostUsd ?? prev.totalCostUsd,
            progressPercent: ev.totalCount === 0 ? 0 : Math.round(((ev.completedCount + ev.failedCount) * 100) / ev.totalCount),
            status: ev.type === 'PROGRESS' ? prev.status : ev.type,
            errorMessage: ev.errorMessage ?? prev.errorMessage,
          } : prev);
          if (ev.band && ev.type === 'PROGRESS') {
            setBandCounts(prev => ({ ...prev, [ev.band as ConfidenceBand]: (prev[ev.band as ConfidenceBand] ?? 0) + 1 }));
          }
          if (ev.type === 'COMPLETED' || ev.type === 'FAILED' || ev.type === 'CANCELLED') {
            es.close();
            if (!cancelled) {
              // Refresh final status once before signalling completion.
              getAiJob(assessmentId, jobId).then(final => {
                if (!cancelled) setStatus(final);
                if (!cancelled) onCompleted();
              }).catch(() => { if (!cancelled) onCompleted(); });
            }
          }
        } catch {
          // ignore malformed events; the polling fallback below catches anything we miss
        }
      };
      es.onerror = () => {
        es.close();
        if (!cancelled && !pollPromise) startPolling();
      };
    } catch {
      startPolling();
    }

    return () => {
      cancelled = true;
      esRef.current?.close();
    };
  }, [assessmentId, jobId, onCompleted]);

  const handleStart = async () => {
    setStarting(true);
    setError(null);
    try {
      const r = await startAiJob(assessmentId, { scope, force });
      setJobId(r.jobId);
      setStatus({
        id: r.jobId,
        status: 'QUEUED',
        model: '',
        scope,
        totalCount: r.totalCount,
        completedCount: 0,
        failedCount: 0,
        progressPercent: 0,
        totalCostUsd: '0',
        estimatedCostUsd: r.estimatedCostUsd,
        startedAt: null,
        finishedAt: null,
        errorMessage: null,
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to start AI job');
    } finally {
      setStarting(false);
    }
  };

  const handleCancel = async () => {
    if (!jobId) return;
    try {
      await cancelAiJob(assessmentId, jobId);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to cancel job');
    }
  };

  return (
    <div className="modal show fade d-block" tabIndex={-1} role="dialog" style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
      <div className="modal-dialog modal-lg" role="document">
        <div className="modal-content">
          <div className="modal-header">
            <h5 className="modal-title">AI Pre-fill — {assessmentLabel}</h5>
            <button type="button" className="btn-close" aria-label="Close" onClick={onClose} disabled={!!running}></button>
          </div>
          <div className="modal-body">
            {!jobId && (
              <>
                <p className="text-muted">
                  Ask the AI to draft answers for this assessment's compliance questions.
                  Each answer becomes a draft Response with a confidence band and citations
                  that you can review and edit before submitting.
                </p>
                <div className="mb-3">
                  <label className="form-label fw-semibold">Scope</label>
                  <select
                    className="form-select"
                    value={scope}
                    onChange={e => setScope(e.target.value as SuggestionScope)}
                  >
                    <option value="WHOLE_ASSESSMENT">All requirements in this assessment</option>
                  </select>
                  <div className="form-text">
                    Subset / single-requirement modes are available via API; UI controls land later.
                  </div>
                </div>
                {hasEditedRows && (
                  <div className="form-check mb-3">
                    <input
                      className="form-check-input"
                      type="checkbox"
                      id="forceEditedCheck"
                      checked={force}
                      onChange={e => setForce(e.target.checked)}
                    />
                    <label className="form-check-label" htmlFor="forceEditedCheck">
                      Also re-run on rows I have already edited
                      <span className="text-danger small d-block">
                        Off (recommended): leaves your edits untouched.
                      </span>
                    </label>
                  </div>
                )}
              </>
            )}
            {error && <div className="alert alert-danger">{error}</div>}
            {status && (
              <div className="mt-2">
                <div className="d-flex justify-content-between mb-1">
                  <strong>Status: {status.status}</strong>
                  <span>
                    {status.completedCount}/{status.totalCount} completed
                    {status.failedCount > 0 && <span className="text-danger ms-2">· {status.failedCount} failed</span>}
                  </span>
                </div>
                <div className="progress" style={{ height: '1.2rem' }}>
                  <div
                    className={`progress-bar ${status.status === 'FAILED' ? 'bg-danger' : status.status === 'CANCELLED' ? 'bg-warning' : 'bg-primary'}`}
                    role="progressbar"
                    style={{ width: `${status.progressPercent}%` }}
                  >
                    {status.progressPercent}%
                  </div>
                </div>
                {(bandCounts.HIGH + bandCounts.MEDIUM + bandCounts.LOW) > 0 && (
                  <div className="small mt-1">
                    <span className="badge bg-success me-1">{bandCounts.HIGH} HIGH</span>
                    <span className="badge bg-warning text-dark me-1">{bandCounts.MEDIUM} MED</span>
                    <span className="badge bg-danger me-1">{bandCounts.LOW} LOW</span>
                  </div>
                )}
                {status.estimatedCostUsd && (
                  <div className="small text-muted mt-1">
                    Estimated cost ≈ ${status.estimatedCostUsd}; spent ${status.totalCostUsd}
                  </div>
                )}
                {status.errorMessage && (
                  <div className="alert alert-danger mt-2">{status.errorMessage}</div>
                )}
              </div>
            )}
          </div>
          <div className="modal-footer">
            {!jobId && (
              <>
                <button type="button" className="btn btn-secondary" onClick={onClose}>Cancel</button>
                <button type="button" className="btn btn-primary" onClick={handleStart} disabled={starting}>
                  {starting ? 'Starting…' : 'Start AI pre-fill'}
                </button>
              </>
            )}
            {jobId && running && (
              <button type="button" className="btn btn-warning" onClick={handleCancel}>Cancel job</button>
            )}
            {jobId && !running && (
              <button type="button" className="btn btn-primary" onClick={onClose}>Close</button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};

export default AiPrefillModal;
