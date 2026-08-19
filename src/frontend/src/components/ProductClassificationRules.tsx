import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  createRule,
  deleteRule,
  getRules,
  getStats,
  reclassifyAll,
  testValue,
  updateRule,
  type ProductClass,
  type ProductClassificationRule,
  type ProductClassificationRuleInput,
  type ProductClassificationStats,
  type ProductClassificationTestResult,
  type RuleMatchField,
} from '../services/productClassificationService';

const MATCH_FIELDS: { value: RuleMatchField; label: string; hint: string }[] = [
  { value: 'PRODUCT_NAME', label: 'Product name', hint: 'Matches vulnerability and installed-product names' },
  { value: 'VENDOR', label: 'Vendor', hint: 'Installed products only' },
  { value: 'INSTALL_PATH', label: 'Install path', hint: 'Installed products only; populated for ~18% of rows' },
];

const EMPTY_FORM: ProductClassificationRuleInput = {
  matchField: 'PRODUCT_NAME',
  pattern: '',
  classification: 'INSTALLER_ARTIFACT',
  priority: 100,
  enabled: true,
  description: '',
};

function classBadge(value: ProductClass): string {
  if (value === 'INSTALLER_ARTIFACT') return 'bg-warning-subtle text-warning-emphasis';
  if (value === 'INSTALLED') return 'bg-success-subtle text-success-emphasis';
  return 'bg-secondary-subtle text-secondary-emphasis';
}

export default function ProductClassificationRules() {
  const [rules, setRules] = useState<ProductClassificationRule[]>([]);
  const [stats, setStats] = useState<ProductClassificationStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [form, setForm] = useState<ProductClassificationRuleInput>(EMPTY_FORM);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [saving, setSaving] = useState(false);

  const [probe, setProbe] = useState('');
  const [probeField, setProbeField] = useState<RuleMatchField>('PRODUCT_NAME');
  const [probeResult, setProbeResult] = useState<ProductClassificationTestResult | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [loadedRules, loadedStats] = await Promise.all([getRules(), getStats()]);
      setRules(loadedRules);
      setStats(loadedStats);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load classification rules');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // Allowlist rules are evaluated before artifact rules regardless of priority, so the table
  // is grouped the same way the classifier reads them. Otherwise the order shown would not
  // explain the outcome.
  const ordered = useMemo(() => {
    const rank = (r: ProductClassificationRule) => (r.classification === 'INSTALLED' ? 0 : 1);
    return [...rules].sort((a, b) => rank(a) - rank(b) || a.priority - b.priority || a.id - b.id);
  }, [rules]);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setSaving(true);
    setError(null);
    try {
      const payload = { ...form, pattern: form.pattern.trim() };
      if (editingId !== null) {
        await updateRule(editingId, payload);
        setNotice('Rule updated. Run "Reclassify now" to apply it to stored findings.');
      } else {
        await createRule(payload);
        setNotice('Rule created. Run "Reclassify now" to apply it to stored findings.');
      }
      setForm(EMPTY_FORM);
      setEditingId(null);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save rule');
    } finally {
      setSaving(false);
    }
  };

  const startEdit = (rule: ProductClassificationRule) => {
    setEditingId(rule.id);
    setForm({
      matchField: rule.matchField,
      pattern: rule.pattern,
      classification: rule.classification,
      priority: rule.priority,
      enabled: rule.enabled,
      description: rule.description ?? '',
    });
  };

  const remove = async (rule: ProductClassificationRule) => {
    setError(null);
    try {
      await deleteRule(rule.id);
      setNotice('Rule deleted. Run "Reclassify now" to apply the change to stored findings.');
      if (editingId === rule.id) {
        setEditingId(null);
        setForm(EMPTY_FORM);
      }
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete rule');
    }
  };

  const runProbe = async () => {
    if (!probe.trim()) return;
    setError(null);
    try {
      setProbeResult(await testValue(probe.trim(), probeField));
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to test value');
    }
  };

  const runReclassify = async () => {
    setError(null);
    try {
      await reclassifyAll();
      setNotice('Reclassify started. It runs in the background; refresh the counts in a moment.');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to start reclassify');
    }
  };

  return (
    <div className="container-fluid py-3">
      <div className="d-flex flex-wrap align-items-center gap-2 mb-3">
        <h2 className="h4 mb-0">Product classification</h2>
        <div className="ms-auto d-flex gap-2">
          <button type="button" className="btn btn-outline-secondary btn-sm" onClick={() => void load()}>
            Refresh
          </button>
          <button type="button" className="btn btn-primary btn-sm" onClick={() => void runReclassify()}>
            Reclassify now
          </button>
        </div>
      </div>

      <p className="text-muted">
        CrowdStrike reports installer payloads as separate applications from the products they
        install, so findings are raised against things that never run. Rules here mark those rows as
        installer artifacts; every vulnerability and EOL list, count, export and statistic then hides
        them unless a user ticks &quot;Include installer / setup findings&quot;. Nothing is deleted.
      </p>
      <p className="text-muted small">
        Rules classifying as <strong>Installed</strong> are an allowlist and are always evaluated
        first, whatever their priority. Rows that match no rule stay visible.
      </p>

      {error && <div className="alert alert-danger">{error}</div>}
      {notice && <div className="alert alert-info">{notice}</div>}

      {stats && (
        <div className="row g-2 mb-3">
          <div className="col-6 col-lg-3">
            <div className="card"><div className="card-body py-2">
              <div className="small text-muted">Enabled rules</div>
              <div className="fs-5">{stats.enabledRules}</div>
            </div></div>
          </div>
          <div className="col-6 col-lg-3">
            <div className="card"><div className="card-body py-2">
              <div className="small text-muted">Installed products flagged</div>
              <div className="fs-5">{stats.installedProductArtifacts}</div>
            </div></div>
          </div>
          <div className="col-6 col-lg-3">
            <div className="card"><div className="card-body py-2">
              <div className="small text-muted">EOL findings flagged</div>
              <div className="fs-5">{stats.eolFindingArtifacts}</div>
            </div></div>
          </div>
          <div className="col-6 col-lg-3">
            <div className="card"><div className="card-body py-2">
              <div className="small text-muted">Vulnerabilities flagged</div>
              <div className="fs-5">{stats.vulnerabilityArtifacts}</div>
            </div></div>
          </div>
        </div>
      )}

      <div className="card mb-3">
        <div className="card-header">Test a value</div>
        <div className="card-body">
          <div className="row g-2 align-items-end">
            <div className="col-md-4">
              <label className="form-label" htmlFor="probe-value">Value</label>
              <input
                id="probe-value"
                className="form-control"
                value={probe}
                placeholder="Chrome Installer"
                onChange={(e) => setProbe(e.target.value)}
              />
            </div>
            <div className="col-md-3">
              <label className="form-label" htmlFor="probe-field">Treat as</label>
              <select
                id="probe-field"
                className="form-select"
                value={probeField}
                onChange={(e) => setProbeField(e.target.value as RuleMatchField)}
              >
                {MATCH_FIELDS.map((f) => (
                  <option key={f.value} value={f.value}>{f.label}</option>
                ))}
              </select>
            </div>
            <div className="col-md-2">
              <button type="button" className="btn btn-outline-primary w-100" onClick={() => void runProbe()}>
                Test
              </button>
            </div>
            <div className="col-md-3">
              {probeResult && (
                <div>
                  <span className={`badge ${classBadge(probeResult.classification)}`}>
                    {probeResult.classification}
                  </span>
                  <div className="small text-muted mt-1">
                    {probeResult.matchedPattern
                      ? <>matched <code>{probeResult.matchedPattern}</code></>
                      : 'no rule matched'}
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>
      </div>

      <div className="card mb-3">
        <div className="card-header">{editingId !== null ? 'Edit rule' : 'Add rule'}</div>
        <div className="card-body">
          <form onSubmit={submit} className="row g-2 align-items-end">
            <div className="col-md-2">
              <label className="form-label" htmlFor="rule-field">Match field</label>
              <select
                id="rule-field"
                className="form-select"
                value={form.matchField}
                onChange={(e) => setForm({ ...form, matchField: e.target.value as RuleMatchField })}
              >
                {MATCH_FIELDS.map((f) => (
                  <option key={f.value} value={f.value}>{f.label}</option>
                ))}
              </select>
              <div className="form-text">
                {MATCH_FIELDS.find((f) => f.value === form.matchField)?.hint}
              </div>
            </div>
            <div className="col-md-3">
              <label className="form-label" htmlFor="rule-pattern">Pattern (glob)</label>
              <input
                id="rule-pattern"
                className="form-control"
                required
                value={form.pattern}
                placeholder="* installer*"
                onChange={(e) => setForm({ ...form, pattern: e.target.value })}
              />
              <div className="form-text">
                <code>*</code> and <code>?</code> only; matched against the whole value, case-insensitively.
              </div>
            </div>
            <div className="col-md-2">
              <label className="form-label" htmlFor="rule-class">Classify as</label>
              <select
                id="rule-class"
                className="form-select"
                value={form.classification}
                onChange={(e) => setForm({ ...form, classification: e.target.value as ProductClass })}
              >
                <option value="INSTALLER_ARTIFACT">Installer artifact (hide)</option>
                <option value="INSTALLED">Installed (allowlist)</option>
              </select>
            </div>
            <div className="col-md-1">
              <label className="form-label" htmlFor="rule-priority">Priority</label>
              <input
                id="rule-priority"
                type="number"
                className="form-control"
                value={form.priority}
                onChange={(e) => setForm({ ...form, priority: Number(e.target.value) })}
              />
            </div>
            <div className="col-md-2">
              <label className="form-label" htmlFor="rule-description">Description</label>
              <input
                id="rule-description"
                className="form-control"
                value={form.description ?? ''}
                onChange={(e) => setForm({ ...form, description: e.target.value })}
              />
            </div>
            <div className="col-md-2 d-flex gap-2">
              <div className="form-check me-2">
                <input
                  className="form-check-input"
                  type="checkbox"
                  id="rule-enabled"
                  checked={form.enabled}
                  onChange={(e) => setForm({ ...form, enabled: e.target.checked })}
                />
                <label className="form-check-label" htmlFor="rule-enabled">Enabled</label>
              </div>
              <button type="submit" className="btn btn-primary" disabled={saving}>
                {editingId !== null ? 'Save' : 'Add'}
              </button>
              {editingId !== null && (
                <button
                  type="button"
                  className="btn btn-outline-secondary"
                  onClick={() => { setEditingId(null); setForm(EMPTY_FORM); }}
                >
                  Cancel
                </button>
              )}
            </div>
          </form>
        </div>
      </div>

      <div className="card">
        <div className="table-responsive">
          <table className="table table-sm table-hover mb-0">
            <thead>
              <tr>
                <th>Field</th>
                <th>Pattern</th>
                <th>Classify as</th>
                <th>Priority</th>
                <th>Enabled</th>
                <th>Description</th>
                <th className="text-end">Actions</th>
              </tr>
            </thead>
            <tbody>
              {loading && (
                <tr><td colSpan={7} className="text-center text-muted py-3">Loading…</td></tr>
              )}
              {!loading && ordered.length === 0 && (
                <tr><td colSpan={7} className="text-center text-muted py-3">No rules defined.</td></tr>
              )}
              {!loading && ordered.map((rule) => (
                <tr key={rule.id} className={rule.enabled ? undefined : 'text-muted'}>
                  <td>{MATCH_FIELDS.find((f) => f.value === rule.matchField)?.label ?? rule.matchField}</td>
                  <td><code>{rule.pattern}</code></td>
                  <td><span className={`badge ${classBadge(rule.classification)}`}>{rule.classification}</span></td>
                  <td>{rule.priority}</td>
                  <td>{rule.enabled ? 'Yes' : 'No'}</td>
                  <td className="small">{rule.description}</td>
                  <td className="text-end">
                    <button type="button" className="btn btn-sm btn-outline-secondary me-1" onClick={() => startEdit(rule)}>
                      Edit
                    </button>
                    <button type="button" className="btn btn-sm btn-outline-danger" onClick={() => void remove(rule)}>
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
