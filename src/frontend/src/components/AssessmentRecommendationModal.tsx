import React, { useCallback, useEffect, useRef, useState } from 'react';
import { authenticatedGet } from '../utils/auth';
import { formatServerDateTime } from '../utils/dateUtils';

type Recommendation = 'OK' | 'NOT_OK' | 'NEEDS_REVIEW';

interface RecommendationFinding {
  requirementId: number;
  internalId?: string | null;
  shortreq?: string | null;
  answerType: string | null;
  reason: string;
  comment?: string | null;
}

interface AssessmentRecommendation {
  assessmentId: number;
  answerRevision: number;
  recommendation: Recommendation;
  verdict: 'COMPLIANT' | 'NON_COMPLIANT';
  summary: string;
  answerCounts: Record<string, number>;
  requirementCount: number;
  missingAnswerCount: number;
  findings: RecommendationFinding[];
  generatedAt: string;
  advisory: boolean;
  policyVersion: string;
}

interface Props {
  assessmentId: number;
  assessmentLabel?: string;
  onClose: () => void;
  onReviewAnswers?: (requirementId?: number) => void;
}

const RECOMMENDATION_BADGES: Record<Recommendation, { className: string; icon: string; text: string }> = {
  OK: { className: 'bg-success', icon: '✓', text: 'OK' },
  NOT_OK: { className: 'bg-danger', icon: '✗', text: 'Not OK' },
  NEEDS_REVIEW: { className: 'bg-warning text-dark', icon: '!', text: 'Needs review' },
};

const FALLBACK_BADGE = { className: 'bg-secondary', icon: '?', text: 'Unknown recommendation' };

const answerTypeBadge = (answerType: string | null): { className: string; text: string } => {
  switch (answerType) {
    case 'NO':
      return { className: 'bg-danger', text: 'No' };
    case 'N_A':
      return { className: 'bg-secondary', text: 'N/A' };
    case 'YES':
      return { className: 'bg-success', text: 'Yes' };
    default:
      return { className: 'bg-warning text-dark', text: 'No answer submitted' };
  }
};

const AssessmentRecommendationModal: React.FC<Props> = ({
  assessmentId,
  assessmentLabel,
  onClose,
  onReviewAnswers,
}) => {
  const [data, setData] = useState<AssessmentRecommendation | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<{ message: string; retryable: boolean } | null>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const requestRef = useRef<AbortController | null>(null);
  const [stale, setStale] = useState(false);

  const fetchRecommendation = useCallback(async () => {
    requestRef.current?.abort();
    const controller = new AbortController();
    requestRef.current = controller;
    setLoading(true);
    setData(null);
    setError(null);
    setStale(false);
    try {
      const response = await authenticatedGet(`/api/risk-assessments/${assessmentId}/recommendation`, { signal: controller.signal });
      if (controller.signal.aborted) return;
      if (response.ok) {
        setData(await response.json());
      } else if (response.status === 403) {
        setData(null);
        setError({ message: "You don't have review authority for this assessment.", retryable: false });
      } else if (response.status === 404) {
        setData(null);
        setError({ message: 'Assessment not found or not visible to you.', retryable: false });
      } else {
        setData(null);
        setError({ message: `Could not analyze the answers (server responded with status ${response.status}).`, retryable: true });
      }
    } catch (err) {
      if (controller.signal.aborted) return;
      setData(null);
      setError({ message: err instanceof Error ? err.message : 'Could not analyze the answers.', retryable: true });
    } finally {
      if (!controller.signal.aborted) setLoading(false);
    }
  }, [assessmentId]);

  useEffect(() => {
    void fetchRecommendation();
    return () => requestRef.current?.abort();
  }, [fetchRecommendation]);

  useEffect(() => {
    const previousFocus = document.activeElement as HTMLElement | null;
    dialogRef.current?.focus();
    return () => previousFocus?.focus();
  }, []);

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
      if (e.key === 'Tab' && dialogRef.current) {
        const items = Array.from(dialogRef.current.querySelectorAll<HTMLElement>('button:not(:disabled), a[href], [tabindex="0"]'));
        const first = items[0];
        const last = items[items.length - 1];
        if (!first) { e.preventDefault(); return; }
        if (e.shiftKey && (document.activeElement === first || document.activeElement === dialogRef.current)) {
          e.preventDefault(); last.focus();
        } else if (!e.shiftKey && (document.activeElement === last || document.activeElement === dialogRef.current)) {
          e.preventDefault(); first.focus();
        }
      }
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [onClose]);

  useEffect(() => {
    if (!data) return;
    const controller = new AbortController();
    const checkRevision = async () => {
      try {
        const response = await authenticatedGet(`/api/risk-assessments/${assessmentId}`, { signal: controller.signal });
        if (controller.signal.aborted) return;
        if (!response.ok) {
          setData(null);
          setError({ message: 'Assessment access could not be verified. Refresh to try again.', retryable: true });
          return;
        }
        const current = await response.json();
        if (current.answerRevision !== data.answerRevision) {
          setData(null);
          setStale(true);
        }
      } catch {
        if (!controller.signal.aborted) { setData(null); setStale(true); }
      }
    };
    const timer = window.setInterval(() => void checkRevision(), 15000);
    window.addEventListener('focus', checkRevision);
    return () => { controller.abort(); window.clearInterval(timer); window.removeEventListener('focus', checkRevision); };
  }, [assessmentId, data]);

  const badge = data ? (RECOMMENDATION_BADGES[data.recommendation] ?? FALLBACK_BADGE) : null;

  return (
    <div
      className="modal show fade d-block"
      tabIndex={-1}
      role="dialog"
      aria-modal="true"
      aria-labelledby="assessmentRecommendationModalTitle"
      ref={dialogRef}
      style={{ backgroundColor: 'var(--scand-overlay)' }}
    >
      <div className="modal-dialog modal-lg" role="document">
        <div className="modal-content">
          <div className="modal-header">
            <h5 className="modal-title" id="assessmentRecommendationModalTitle">
              Analyze answers{assessmentLabel ? ` — ${assessmentLabel}` : ''}
            </h5>
            <button type="button" className="btn-close" aria-label="Close" onClick={onClose}></button>
          </div>
          <div className="modal-body">
            {stale && <p className="alert alert-warning" role="alert">The answers changed or their revision could not be verified. Refresh before using this recommendation.</p>}
            {loading && (
              <div className="d-flex align-items-center gap-2">
                <div className="spinner-border spinner-border-sm" role="status">
                  <span className="visually-hidden">Loading...</span>
                </div>
                <span>Analyzing answers...</span>
              </div>
            )}

            {!loading && error && (
              <div className="alert alert-danger d-flex justify-content-between align-items-center" role="alert">
                <span>{error.message}</span>
                {error.retryable && (
                  <button type="button" className="btn btn-outline-danger btn-sm" onClick={fetchRecommendation}>
                    Retry
                  </button>
                )}
              </div>
            )}

            {!loading && !error && data && badge && (
              <>
                <div className="d-flex align-items-center flex-wrap gap-2 mb-3">
                  <span
                    className={`badge fs-6 ${badge.className}`}
                    role="status"
                    aria-label={`Recommendation: ${badge.text}`}
                  >
                    <span aria-hidden="true">{badge.icon}</span> {badge.text}
                  </span>
                  <small className="text-muted">Advisory — human approval is still required</small>
                </div>

                <p>{data.summary}</p>
                <p className="small text-muted">Policy: submitted questionnaires with only Yes answers are OK. No means Not OK. N/A, missing answers, an empty scope, or an unsubmitted questionnaire require review. The legacy compliance verdict is not an approval.</p>

                <h6 className="text-muted">Answer counts</h6>
                <dl className="row small mb-3">
                  <dt className="col-sm-4">Answered &quot;Yes&quot;</dt>
                  <dd className="col-sm-8">{data.answerCounts.YES ?? 0}</dd>
                  <dt className="col-sm-4">Answered &quot;No&quot;</dt>
                  <dd className="col-sm-8">{data.answerCounts.NO ?? 0}</dd>
                  <dt className="col-sm-4">Answered &quot;N/A&quot;</dt>
                  <dd className="col-sm-8">{data.answerCounts.N_A ?? 0}</dd>
                  <dt className="col-sm-4">Missing answers</dt>
                  <dd className="col-sm-8">{data.missingAnswerCount}</dd>
                  <dt className="col-sm-4">Requirements in scope</dt>
                  <dd className="col-sm-8">{data.requirementCount}</dd>
                </dl>

                <h6 className="text-muted">Findings</h6>
                {data.findings.length === 0 ? (
                  <p className="text-muted">No findings — every requirement is answered and none are unmet.</p>
                ) : (
                  <ul className="list-group">
                    {data.findings.map(finding => {
                      const answerBadge = answerTypeBadge(finding.answerType);
                      return (
                        <li key={finding.requirementId} className="list-group-item">
                          <div className="d-flex justify-content-between align-items-start gap-2">
                            <strong>{onReviewAnswers ? <button className="btn btn-link p-0" onClick={() => onReviewAnswers(finding.requirementId)}>{finding.internalId || `Requirement #${finding.requirementId}`}</button> : (finding.internalId || `Requirement #${finding.requirementId}`)}</strong>
                            <span className={`badge ${answerBadge.className}`}>{answerBadge.text}</span>
                          </div>
                          {finding.shortreq && <div>{finding.shortreq}</div>}
                          <div className="small">{finding.reason}</div>
                          {finding.comment && (
                            <div className="small text-muted">Comment: {finding.comment}</div>
                          )}
                        </li>
                      );
                    })}
                  </ul>
                )}
              </>
            )}
          </div>
          <div className="modal-footer d-flex justify-content-between align-items-center flex-wrap gap-2">
            <small className="text-muted">
              {data
                ? `Answer revision ${data.answerRevision} · Policy v${data.policyVersion} · Generated ${formatServerDateTime(data.generatedAt)}`
                : ''}
            </small>
            <div className="d-flex gap-2">
              <button type="button" className="btn btn-outline-primary" onClick={fetchRecommendation} disabled={loading}>
                Refresh
              </button>
              {onReviewAnswers && (
                <button type="button" className="btn btn-outline-warning" onClick={() => onReviewAnswers()}>
                  Review answers
                </button>
              )}
              <button type="button" className="btn btn-secondary" onClick={onClose}>
                Close
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default AssessmentRecommendationModal;
