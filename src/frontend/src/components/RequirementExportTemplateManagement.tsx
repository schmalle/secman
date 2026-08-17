import React, { useCallback, useEffect, useState } from 'react';
import { authenticatedFetch } from '../utils/auth';
import { useClientHasRole } from '../utils/useClientAuth';
import { downloadResponse } from '../utils/download';
import {
  TEMPLATES_ENDPOINT,
  SUPPORTED_PLACEHOLDERS,
  buildUploadFormData,
  describeValidationReport,
  formatFileSize,
  shortSha256,
  sortTemplates,
  statusBadgeClass,
  unsupportedPlaceholders,
  validateTemplateFile,
  type RequirementExportTemplateSummary,
  type ValidationReport,
} from '../services/requirementExportTemplates';

/**
 * Admin surface for the company Word templates used by requirement exports.
 *
 * The RBAC gate here is UX only — `RequirementExportTemplateController` enforces ADMIN/REQADMIN on
 * every write, and that is the boundary.
 */
const RequirementExportTemplateManagement: React.FC = () => {
  const [templates, setTemplates] = useState<RequirementExportTemplateSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [report, setReport] = useState<ValidationReport | null>(null);
  const [busy, setBusy] = useState(false);

  const [file, setFile] = useState<File | null>(null);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [versionLabel, setVersionLabel] = useState('');
  const [activate, setActivate] = useState(true);
  const [requirePlaceholder, setRequirePlaceholder] = useState(true);

  const canManage = useClientHasRole(['ADMIN', 'REQADMIN']);

  const loadTemplates = useCallback(async () => {
    setLoading(true);
    try {
      const response = await authenticatedFetch(`${TEMPLATES_ENDPOINT}?includeInactive=true`);
      if (!response.ok) {
        setError('Could not load requirement export templates.');
        return;
      }
      setTemplates(sortTemplates(await response.json()));
      setError(null);
    } catch (e) {
      setError('Could not load requirement export templates.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadTemplates();
  }, [loadTemplates]);

  const resetForm = () => {
    setFile(null);
    setName('');
    setDescription('');
    setVersionLabel('');
    setReport(null);
    const input = document.getElementById('template-file') as HTMLInputElement | null;
    if (input) input.value = '';
  };

  /** Reads a JSON error body, falling back to the status text when there is none. */
  const errorMessageFrom = async (response: Response, fallback: string): Promise<string> => {
    try {
      const body = await response.json();
      if (body?.errors?.length) return body.errors.join('; ');
      if (body?.error) return body.error;
    } catch (e) {
      // Not a JSON body; fall through to the status text.
    }
    return response.statusText || fallback;
  };

  const handleValidate = async () => {
    const fileError = validateTemplateFile(file);
    if (fileError || !file) {
      setError(fileError);
      setReport(null);
      return;
    }
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const formData = new FormData();
      formData.append('templateFile', file);
      formData.append('requireRequirementsPlaceholder', String(requirePlaceholder));
      const response = await fetch(`${TEMPLATES_ENDPOINT}/validate`, {
        method: 'POST',
        body: formData,
        credentials: 'include',
      });
      // Both outcomes return the report; only the status differs.
      setReport(await response.json());
      if (response.ok) setNotice('Template passed validation. It has not been saved yet.');
    } catch (e) {
      setError('Validation request failed.');
    } finally {
      setBusy(false);
    }
  };

  const handleUpload = async () => {
    const fileError = validateTemplateFile(file);
    if (fileError || !file) {
      setError(fileError);
      return;
    }
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const response = await fetch(TEMPLATES_ENDPOINT, {
        method: 'POST',
        body: buildUploadFormData({
          file,
          name,
          description,
          versionLabel,
          activate,
          requireRequirementsPlaceholder: requirePlaceholder,
        }),
        credentials: 'include',
      });
      if (!response.ok) {
        const body = await response.json().catch(() => null);
        if (body && 'valid' in body) setReport(body);
        setError(body?.errors?.join('; ') || body?.error || 'Upload failed.');
        return;
      }
      resetForm();
      setNotice('Template uploaded.');
      await loadTemplates();
    } catch (e) {
      setError('Upload failed.');
    } finally {
      setBusy(false);
    }
  };

  /** POST /activate and /deactivate share a shape, so they share a handler. */
  const handleLifecycle = async (id: number, action: 'activate' | 'deactivate') => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const response = await authenticatedFetch(`${TEMPLATES_ENDPOINT}/${id}/${action}`, { method: 'POST' });
      if (!response.ok) {
        setError(await errorMessageFrom(response, `Could not ${action} the template.`));
        return;
      }
      await loadTemplates();
    } catch (e) {
      setError(`Could not ${action} the template.`);
    } finally {
      setBusy(false);
    }
  };

  const handleDelete = async (template: RequirementExportTemplateSummary) => {
    // Past exports keep working — they embed their own copy — and the export history rows survive
    // the template, so the only thing lost is the template itself.
    if (!window.confirm(`Delete template "${template.name}"? This cannot be undone. Its export history is kept.`)) {
      return;
    }
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const response = await authenticatedFetch(`${TEMPLATES_ENDPOINT}/${template.id}`, { method: 'DELETE' });
      if (!response.ok) {
        setError(await errorMessageFrom(response, 'Could not delete the template.'));
        return;
      }
      setNotice(`Template "${template.name}" deleted.`);
      await loadTemplates();
    } catch (e) {
      setError('Could not delete the template.');
    } finally {
      setBusy(false);
    }
  };

  /** Streams a blob response to the browser's downloads. */
  const downloadFile = async (url: string, fallbackFilename: string) => {
    setError(null);
    try {
      const response = await authenticatedFetch(url);
      if (!response.ok) {
        setError(await errorMessageFrom(response, 'Download failed.'));
        return;
      }
      await downloadResponse(response, fallbackFilename);
    } catch (e) {
      setError('Download failed.');
    }
  };

  if (!canManage) {
    return (
      <div className="container mt-4">
        <div className="alert alert-warning" data-testid="template-rbac-denied">
          You need the ADMIN or REQADMIN role to manage requirement export templates.
        </div>
      </div>
    );
  }

  const described = describeValidationReport(report);
  const unsupported = unsupportedPlaceholders(described.placeholders);

  return (
    <div className="container mt-4" data-testid="requirement-export-template-management">
      <h1 className="h3 mb-1">Requirement Export Templates</h1>
      <p className="text-muted">
        The active template gives every Word requirement export your company design. Start from the
        example, restyle it in Word, and upload it here.
      </p>

      {error && <div className="alert alert-danger" data-testid="template-error">{error}</div>}
      {notice && <div className="alert alert-info" data-testid="template-notice">{notice}</div>}

      <div className="card mb-4">
        <div className="card-header d-flex justify-content-between align-items-center">
          <span>Upload a template</span>
          <button
            type="button"
            className="btn btn-sm btn-outline-secondary"
            data-testid="download-example-template"
            onClick={() => downloadFile(`${TEMPLATES_ENDPOINT}/example`, 'secman-company-requirements-template.docx')}
          >
            <i className="bi bi-download me-1" /> Download example template
          </button>
        </div>
        <div className="card-body">
          <div className="row g-3">
            <div className="col-md-6">
              <label className="form-label" htmlFor="template-file">Word template (.docx)</label>
              <input
                id="template-file"
                type="file"
                accept=".docx"
                className="form-control"
                data-testid="template-file"
                onChange={(e) => {
                  setFile(e.target.files?.[0] ?? null);
                  setReport(null);
                }}
              />
            </div>
            <div className="col-md-6">
              <label className="form-label" htmlFor="template-name">Name</label>
              <input
                id="template-name"
                type="text"
                className="form-control"
                data-testid="template-name"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="Defaults to the filename"
              />
            </div>
            <div className="col-md-4">
              <label className="form-label" htmlFor="template-version">Version label</label>
              <input
                id="template-version"
                type="text"
                className="form-control"
                data-testid="template-version-label"
                value={versionLabel}
                onChange={(e) => setVersionLabel(e.target.value)}
              />
            </div>
            <div className="col-md-8">
              <label className="form-label" htmlFor="template-description">Description</label>
              <input
                id="template-description"
                type="text"
                className="form-control"
                data-testid="template-description"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
              />
            </div>
            <div className="col-12">
              <div className="form-check">
                <input
                  className="form-check-input"
                  type="checkbox"
                  id="template-activate"
                  data-testid="template-activate"
                  checked={activate}
                  onChange={(e) => setActivate(e.target.checked)}
                />
                <label className="form-check-label" htmlFor="template-activate">
                  Activate immediately (exports use the newest active template)
                </label>
              </div>
              <div className="form-check">
                <input
                  className="form-check-input"
                  type="checkbox"
                  id="template-require-placeholder"
                  data-testid="template-require-placeholder"
                  checked={requirePlaceholder}
                  onChange={(e) => setRequirePlaceholder(e.target.checked)}
                />
                <label className="form-check-label" htmlFor="template-require-placeholder">
                  Require the <code>{'${requirements}'}</code> insertion point (uncheck to append
                  requirements at the end of the document instead)
                </label>
              </div>
            </div>
          </div>

          <div className="mt-3 d-flex gap-2">
            <button
              type="button"
              className="btn btn-outline-primary"
              data-testid="validate-template"
              disabled={busy}
              onClick={handleValidate}
            >
              Validate only
            </button>
            <button
              type="button"
              className="btn btn-primary"
              data-testid="upload-template"
              disabled={busy}
              onClick={handleUpload}
            >
              Upload
            </button>
          </div>

          {report && (
            <div className="mt-3" data-testid="validation-report">
              <div className={`alert ${described.valid ? 'alert-success' : 'alert-danger'} mb-2`}>
                {described.valid ? 'Template is valid.' : 'Template was rejected.'}
              </div>
              {described.errors.length > 0 && (
                <ul className="text-danger small">
                  {described.errors.map((message) => <li key={message}>{message}</li>)}
                </ul>
              )}
              {described.warnings.length > 0 && (
                <ul className="text-warning small">
                  {described.warnings.map((message) => <li key={message}>{message}</li>)}
                </ul>
              )}
              {described.placeholders.length > 0 && (
                <p className="small mb-0">
                  Placeholders found: {described.placeholders.join(', ')}
                  {unsupported.length > 0 && (
                    <span className="text-warning"> (not substituted: {unsupported.join(', ')})</span>
                  )}
                </p>
              )}
            </div>
          )}
        </div>
      </div>

      <div className="card mb-4">
        <div className="card-header">Supported placeholders</div>
        <div className="card-body">
          <p className="small text-muted">
            Put these anywhere in the document, including headers and footers. Anything else is left
            exactly as written. <code>{'${requirements}'}</code> is special: it is a position, not a
            value, and must sit in its own paragraph in the document body — not inside a table,
            header or footer.
          </p>
          <div className="row">
            {SUPPORTED_PLACEHOLDERS.map((placeholder) => (
              <div className="col-md-6 small" key={placeholder.name}>
                <code>{'${' + placeholder.name + '}'}</code> — {placeholder.description}
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className="card">
        <div className="card-header">Templates</div>
        <div className="table-responsive">
          <table className="table table-sm mb-0" data-testid="template-list">
            <thead>
              <tr>
                <th>Name</th>
                <th>Version</th>
                <th>Status</th>
                <th>Size</th>
                <th>SHA-256</th>
                <th>Uploaded by</th>
                <th>Last used</th>
                <th className="text-end">Actions</th>
              </tr>
            </thead>
            <tbody>
              {loading && (
                <tr><td colSpan={8} className="text-center text-muted py-3">Loading…</td></tr>
              )}
              {!loading && templates.length === 0 && (
                <tr><td colSpan={8} className="text-center text-muted py-3">No templates yet.</td></tr>
              )}
              {!loading && templates.map((template) => (
                <tr key={template.id} data-testid={`template-row-${template.id}`}>
                  <td>
                    {template.name}
                    {template.description && <div className="small text-muted">{template.description}</div>}
                  </td>
                  <td>{template.versionLabel ?? '-'}</td>
                  <td><span className={statusBadgeClass(template.status)}>{template.status}</span></td>
                  <td>{formatFileSize(template.fileSizeBytes)}</td>
                  <td><code className="small" title={template.sha256}>{shortSha256(template.sha256)}</code></td>
                  <td>{template.uploadedBy}</td>
                  <td>{template.lastUsedAt ? new Date(template.lastUsedAt).toLocaleString() : 'never'}</td>
                  <td className="text-end">
                    <div className="btn-group btn-group-sm">
                      <button
                        type="button"
                        className="btn btn-outline-secondary"
                        title="Download"
                        onClick={() => downloadFile(`${TEMPLATES_ENDPOINT}/${template.id}/download`, template.originalFilename)}
                      >
                        <i className="bi bi-download" />
                      </button>
                      {template.status === 'ACTIVE' ? (
                        <button
                          type="button"
                          className="btn btn-outline-warning"
                          disabled={busy}
                          onClick={() => handleLifecycle(template.id, 'deactivate')}
                        >
                          Deactivate
                        </button>
                      ) : (
                        <button
                          type="button"
                          className="btn btn-outline-success"
                          disabled={busy}
                          onClick={() => handleLifecycle(template.id, 'activate')}
                        >
                          Activate
                        </button>
                      )}
                      <button
                        type="button"
                        className="btn btn-outline-danger"
                        disabled={busy}
                        onClick={() => handleDelete(template)}
                      >
                        Delete
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
};

export default RequirementExportTemplateManagement;
