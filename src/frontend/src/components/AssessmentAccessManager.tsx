import React, { useEffect, useState } from 'react';
import { authenticatedDelete, authenticatedGet, authenticatedPost } from '../utils/auth';

type Assignment = { id: number; email: string; role: string; requirementIds: string; revoked: boolean; submitted: boolean };

/** Manage task grants separately from the underlying asset permissions. */
export default function AssessmentAccessManager({ assessmentId }: { assessmentId: number }) {
  const [rows, setRows] = useState<Assignment[]>([]);
  const [email, setEmail] = useState('');
  const [role, setRole] = useState('RESPONDENT');
  const [requirements, setRequirements] = useState('');
  const [error, setError] = useState('');
  const load = async () => {
    const response = await authenticatedGet(`/api/risk-assessments/${assessmentId}/workflow/assignments`);
    if (!response.ok) throw new Error('Could not load assignments.');
    setRows(await response.json());
  };
  useEffect(() => { load().catch(err => setError(err.message)); }, [assessmentId]);
  const run = async (action: () => Promise<globalThis.Response>) => {
    setError('');
    try {
      const response = await action();
      if (!response.ok) throw new Error('Action rejected. Check the recipient and assessment requirements.');
      await load();
    } catch (err) { setError(err instanceof Error ? err.message : 'Action failed'); }
  };
  return <details className="mt-2">
    <summary>Manage assessment assignments</summary>
    {error && <p role="alert" className="text-danger">{error}</p>}
    <ul>{rows.filter(row => !row.revoked).map(row => <li key={row.id}>
      {row.email} — {row.role} — {row.submitted ? 'Submitted' : 'Open'} — {row.requirementIds || 'All questions'}
      <button className="btn btn-sm btn-outline-danger ms-2" onClick={() => run(() => authenticatedDelete(`/api/risk-assessments/${assessmentId}/workflow/assignments/${row.id}`))}>Revoke</button>
    </li>)}</ul>
    <label className="form-label">Recipient email<input className="form-control" type="email" value={email} onChange={e => setEmail(e.target.value)} /></label>
    <p className="form-text">Existing users receive the task in SecMan. Respondents without an account use a scoped email link.</p>
    <select className="form-select" aria-label="Assignment role" value={role} onChange={e => setRole(e.target.value)}><option>RESPONDENT</option><option>ASSESSOR</option></select>
    <label className="form-label">Requirement IDs (comma-separated; blank means all)<input className="form-control" value={requirements} onChange={e => setRequirements(e.target.value)} /></label>
    <button className="btn btn-primary ms-2" onClick={() => run(() => authenticatedPost(`/api/risk-assessments/${assessmentId}/workflow/assignments`, {
      userId: null, email, role,
      requirementIds: requirements ? requirements.split(',').map(value => Number(value.trim())) : []
    }))}>Assign</button>
    <button className="btn btn-outline-warning ms-2" onClick={() => run(() => authenticatedPost(`/api/risk-assessments/${assessmentId}/workflow/reopen`, {}))}>Reopen answers and invalidate acceptance</button>
  </details>;
}
