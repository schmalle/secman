import { useEffect, useState } from 'react';
import HtmlEditor from './HtmlEditor';
import { authenticatedGet, authenticatedPut } from '../../utils/auth';
import type { UseCaseOption } from '../../services/accountOnboardingService';

interface Settings {
  mode: 'WELCOME_ONLY' | 'DIRECT';
  riskAssessmentUseCase?: string | null;
  riskAssessmentDeadlineDays: number;
  welcomeSubject: string;
  welcomeBodyHtml: string;
}

export default function AccountOnboardingSettings({ admin, useCases }: { admin: boolean; useCases: UseCaseOption[] }) {
  const [settings, setSettings] = useState<Settings | null>(null);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState(false);
  const [saving, setSaving] = useState(false);
  useEffect(() => {
    authenticatedGet('/api/account-onboarding/settings').then(async response => {
      if (!response.ok) throw new Error('Could not load onboarding settings.');
      setSettings(await response.json());
    }).catch(error => setError(error.message));
  }, []);
  const save = async () => {
    setSaving(true); setError(''); setSaved(false);
    try {
      const response = await authenticatedPut('/api/account-onboarding/settings', settings);
      const body = await response.json();
      if (!response.ok) throw new Error(body.message || 'Could not save onboarding settings.');
      setSettings(body); setSaved(true);
    } catch (error) { setError(error instanceof Error ? error.message : 'Could not save settings.'); }
    finally { setSaving(false); }
  };
  return <section className="card mb-4"><div className="card-body">
    <h2 className="h5">New AWS account action</h2>
    <p>When an import requests notification of new accounts, SecMan uses this saved action. An explicit import mode overrides it.</p>
    {error && <div className="alert alert-danger" role="alert">{error}</div>}
    {saved && <div className="alert alert-success" role="status">Onboarding settings saved.</div>}
    {settings && <>
      <label className="form-label" htmlFor="onboarding-default-action">Action</label>
      <select id="onboarding-default-action" className="form-select mb-3" disabled={!admin || saving} value={settings.mode}
        onChange={e => { setSaved(false); setSettings({ ...settings, mode: e.target.value as Settings['mode'] }); }}>
        <option value="WELCOME_ONLY">Send a welcome email (recommended)</option>
        <option value="DIRECT">Start a risk assessment</option>
      </select>
      {settings.mode === 'DIRECT' && <>
        <label className="form-label" htmlFor="onboarding-default-usecase">Assessment use case</label>
        <select id="onboarding-default-usecase" className="form-select mb-3" disabled={!admin || saving} value={settings.riskAssessmentUseCase || ''}
          onChange={e => { setSaved(false); setSettings({ ...settings, riskAssessmentUseCase: e.target.value }); }}>
          <option value="">Select a use case</option>
          {useCases.map(useCase => <option key={useCase.id} value={useCase.name}>{useCase.name}</option>)}
        </select>
        <label className="form-label" htmlFor="onboarding-default-deadline">Deadline in days</label>
        <input id="onboarding-default-deadline" className="form-control mb-3" type="number" min="1" max="3650" disabled={!admin || saving}
          value={settings.riskAssessmentDeadlineDays} onChange={e => { setSaved(false); setSettings({ ...settings, riskAssessmentDeadlineDays: Number(e.target.value) }); }} />
        <p className="text-muted">The selected use case needs requirements in an ACTIVE release. The owner receives the assessment invitation.</p>
      </>}
      {admin ? <>
        <label className="form-label" htmlFor="onboarding-welcome-subject">Welcome email subject</label>
        <input id="onboarding-welcome-subject" className="form-control mb-3" maxLength={255} value={settings.welcomeSubject}
          onChange={e => { setSaved(false); setSettings({ ...settings, welcomeSubject: e.target.value }); }} />
        <div className="form-label">Welcome email body</div>
        <p className="text-muted">Available placeholders: {'{awsAccountId}, {ownerEmail}, {portalUrl}, {requirementsVersion}'}. Formatting and safe links are supported.</p>
        <HtmlEditor value={settings.welcomeBodyHtml} onChange={welcomeBodyHtml => { setSaved(false); setSettings({ ...settings, welcomeBodyHtml }); }} />
        <button className="btn btn-primary mt-3" disabled={saving} onClick={save}>{saving ? 'Saving…' : 'Save onboarding settings'}</button>
      </> : <p className="text-muted">An administrator can change the action and welcome email.</p>}
    </>}
  </div></section>;
}
