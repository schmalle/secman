import React, { useState, useRef, useEffect } from 'react';
import axios from 'axios';
import { API_BASE_URL } from '../services/api';

interface ImportResult {
  success: boolean;
  message: string;
  imported: ImportCounts;
  skipped: ImportCounts;
  errors: string[];
  warnings: string[];
  newMcpApiKeys: NewMcpApiKey[];
}

interface ImportCounts {
  users: number;
  workgroups: number;
  userMappings: number;
  identityProviders: number;
  falconConfigs: number;
  mcpApiKeys: number;
}

interface NewMcpApiKey {
  name: string;
  userEmail: string;
  keyId: string;
  keySecret: string;
}

interface ValidationResult {
  isValid: boolean;
  schemaVersion: string;
  conflicts: ConflictInfo[];
  requiredSecrets: RequiredSecretInfo[];
  errors: string[];
  warnings: string[];
}

interface ConflictInfo {
  entityType: string;
  identifier: string;
  conflictType: string;
}

interface RequiredSecretInfo {
  entityType: string;
  identifier: string;
  secretType: string;
}

const ConfigBundleManager: React.FC = () => {
  const [activeTab, setActiveTab] = useState<'export' | 'import'>('export');
  const [isExporting, setIsExporting] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);
  const [exportSuccess, setExportSuccess] = useState(false);

  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [isImporting, setIsImporting] = useState(false);
  const [isValidating, setIsValidating] = useState(false);
  const [importResult, setImportResult] = useState<ImportResult | null>(null);
  const [validationResult, setValidationResult] = useState<ValidationResult | null>(null);
  const [importError, setImportError] = useState<string | null>(null);
  const [providedSecrets, setProvidedSecrets] = useState<Record<string, string>>({});
  const [showSecretsModal, setShowSecretsModal] = useState(false);

  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    // Set up axios interceptors for auth
    const token = sessionStorage.getItem('jwt_token');
    if (token) {
      axios.defaults.headers.common['Authorization'] = `Bearer ${token}`;
    }
  }, []);

  const handleExport = async () => {
    setIsExporting(true);
    setExportError(null);
    setExportSuccess(false);

    try {
      const response = await axios.get(`${API_BASE_URL}/api/config-bundle/export`, {
        responseType: 'blob',
        headers: {
          'Authorization': `Bearer ${sessionStorage.getItem('jwt_token')}`
        }
      });

      // Create download link
      const url = window.URL.createObjectURL(new Blob([response.data]));
      const link = document.createElement('a');
      link.href = url;

      // Get filename from content-disposition header if available
      const contentDisposition = response.headers['content-disposition'];
      const filenameMatch = contentDisposition?.match(/filename="(.+)"/);
      const filename = filenameMatch ? filenameMatch[1] : `secman_config_bundle_${new Date().toISOString().slice(0, 10)}.json`;

      link.setAttribute('download', filename);
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.URL.revokeObjectURL(url);

      setExportSuccess(true);
      setTimeout(() => setExportSuccess(false), 5000);
    } catch (error: any) {
      console.error('Export error:', error);
      setExportError(error.response?.data?.error || 'Failed to export configuration bundle');
    } finally {
      setIsExporting(false);
    }
  };

  const handleFileSelect = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (file) {
      if (!file.name.endsWith('.json')) {
        setImportError('Please select a JSON file');
        return;
      }
      if (file.size > 50 * 1024 * 1024) {
        setImportError('File size exceeds 50MB limit');
        return;
      }
      setSelectedFile(file);
      setImportError(null);
      setValidationResult(null);
      setImportResult(null);
    }
  };

  const handleValidate = async () => {
    if (!selectedFile) return;

    setIsValidating(true);
    setValidationResult(null);
    setImportError(null);

    const formData = new FormData();
    formData.append('file', selectedFile);

    try {
      const response = await axios.post(`${API_BASE_URL}/api/config-bundle/validate`, formData, {
        headers: {
          'Content-Type': 'multipart/form-data',
          'Authorization': `Bearer ${sessionStorage.getItem('jwt_token')}`
        }
      });

      setValidationResult(response.data);

      // If there are required secrets, prepare the secrets form
      if (response.data.requiredSecrets && response.data.requiredSecrets.length > 0) {
        const secrets: Record<string, string> = {};
        response.data.requiredSecrets.forEach((secret: RequiredSecretInfo) => {
          const key = `${secret.entityType}:${secret.identifier}:${secret.secretType}`;
          secrets[key] = '';
        });
        setProvidedSecrets(secrets);
        setShowSecretsModal(true);
      }
    } catch (error: any) {
      console.error('Validation error:', error);
      setImportError(error.response?.data?.error || 'Failed to validate configuration bundle');
    } finally {
      setIsValidating(false);
    }
  };

  const handleImport = async () => {
    if (!selectedFile) return;

    setIsImporting(true);
    setImportResult(null);
    setImportError(null);

    const formData = new FormData();
    formData.append('file', selectedFile);

    // Add options
    const options = {
      skipExisting: true,
      generateTempPasswords: true,
      dryRun: false
    };
    formData.append('options', JSON.stringify(options));

    // Add provided secrets if any
    if (Object.keys(providedSecrets).length > 0) {
      formData.append('secrets', JSON.stringify(providedSecrets));
    }

    try {
      const response = await axios.post(`${API_BASE_URL}/api/config-bundle/import`, formData, {
        headers: {
          'Content-Type': 'multipart/form-data',
          'Authorization': `Bearer ${sessionStorage.getItem('jwt_token')}`
        }
      });

      setImportResult(response.data);
      setShowSecretsModal(false);
    } catch (error: any) {
      console.error('Import error:', error);
      setImportError(error.response?.data?.error || 'Failed to import configuration bundle');
    } finally {
      setIsImporting(false);
    }
  };

  const handleSecretChange = (key: string, value: string) => {
    setProvidedSecrets(prev => ({
      ...prev,
      [key]: value
    }));
  };

  const downloadNewKeys = () => {
    if (!importResult?.newMcpApiKeys || importResult.newMcpApiKeys.length === 0) return;

    const keysText = importResult.newMcpApiKeys.map(key =>
      `Name: ${key.name}\nUser: ${key.userEmail}\nKey ID: ${key.keyId}\nSecret: ${key.keySecret}\n---\n`
    ).join('\n');

    const blob = new Blob([keysText], { type: 'text/plain' });
    const url = window.URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.setAttribute('download', 'mcp_api_keys.txt');
    document.body.appendChild(link);
    link.click();
    link.remove();
    window.URL.revokeObjectURL(url);
  };

  return (
    <div className="card">
      <div className="card-header">
        <ul className="nav nav-tabs card-header-tabs">
          <li className="nav-item">
            <button
              className={`nav-link ${activeTab === 'export' ? 'active' : ''}`}
              onClick={() => setActiveTab('export')}
            >
              <i className="bi bi-box-arrow-up me-2"></i>
              Export Configuration
            </button>
          </li>
          <li className="nav-item">
            <button
              className={`nav-link ${activeTab === 'import' ? 'active' : ''}`}
              onClick={() => setActiveTab('import')}
            >
              <i className="bi bi-box-arrow-in-down me-2"></i>
              Import Configuration
            </button>
          </li>
        </ul>
      </div>

      <div className="card-body">
        {activeTab === 'export' && (
          <div>
            <h5 className="card-title">Export Configuration Bundle</h5>
            <p className="card-text text-muted">
              Export all system configuration data including users, workgroups, identity providers, CrowdStrike settings, and MCP API keys.
              Sensitive data such as passwords and API secrets will be masked for security.
            </p>

            <div className="alert alert-info">
              <i className="bi bi-info-circle me-2"></i>
              <strong>What's included:</strong>
              <ul className="mb-0 mt-2">
                <li>All users (without passwords)</li>
                <li>Workgroups and hierarchies</li>
                <li>User mappings (AWS, domains, IPs)</li>
                <li>Identity providers (OAuth/SAML configurations)</li>
                <li>CrowdStrike Falcon configuration</li>
                <li>MCP API key metadata (without secrets)</li>
              </ul>
            </div>

            {exportError && (
              <div className="alert alert-danger">
                <i className="bi bi-exclamation-triangle me-2"></i>
                {exportError}
              </div>
            )}

            {exportSuccess && (
              <div className="alert alert-success">
                <i className="bi bi-check-circle me-2"></i>
                Configuration bundle exported successfully!
              </div>
            )}

            <button
              className="btn btn-primary"
              onClick={handleExport}
              disabled={isExporting}
            >
              {isExporting ? (
                <>
                  <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                  Exporting...
                </>
              ) : (
                <>
                  <i className="bi bi-download me-2"></i>
                  Export Configuration
                </>
              )}
            </button>
          </div>
        )}

        {activeTab === 'import' && (
          <div>
            <h5 className="card-title">Import Configuration Bundle</h5>
            <p className="card-text text-muted">
              Import a previously exported configuration bundle. The system will validate the file and handle conflicts automatically.
            </p>

            <div className="mb-3">
              <label htmlFor="bundleFile" className="form-label">Select Configuration Bundle File</label>
              <input
                ref={fileInputRef}
                type="file"
                className="form-control"
                id="bundleFile"
                accept=".json"
                onChange={handleFileSelect}
              />
              <div className="form-text">Maximum file size: 50MB. JSON format only.</div>
            </div>

            {selectedFile && (
              <div className="alert alert-secondary">
                <i className="bi bi-file-earmark-text me-2"></i>
                Selected file: <strong>{selectedFile.name}</strong> ({(selectedFile.size / 1024 / 1024).toFixed(2)} MB)
              </div>
            )}

            {importError && (
              <div className="alert alert-danger">
                <i className="bi bi-exclamation-triangle me-2"></i>
                {importError}
              </div>
            )}

            {validationResult && (
              <div className="card mb-3">
                <div className="card-body">
                  <h6 className="card-subtitle mb-2 text-muted">Validation Results</h6>

                  {validationResult.isValid ? (
                    <div className="alert alert-success">
                      <i className="bi bi-check-circle me-2"></i>
                      Bundle is valid and ready for import
                    </div>
                  ) : (
                    <div className="alert alert-danger">
                      <i className="bi bi-x-circle me-2"></i>
                      Bundle validation failed
                    </div>
                  )}

                  {validationResult.conflicts.length > 0 && (
                    <div className="mb-3">
                      <h6>Conflicts (will be skipped):</h6>
                      <ul className="list-group">
                        {validationResult.conflicts.map((conflict, idx) => (
                          <li key={idx} className="list-group-item">
                            <strong>{conflict.entityType}</strong>: {conflict.identifier}
                            <span className="badge bg-warning ms-2">{conflict.conflictType}</span>
                          </li>
                        ))}
                      </ul>
                    </div>
                  )}

                  {validationResult.errors.length > 0 && (
                    <div className="mb-3">
                      <h6>Errors:</h6>
                      <ul className="list-group">
                        {validationResult.errors.map((error, idx) => (
                          <li key={idx} className="list-group-item list-group-item-danger">{error}</li>
                        ))}
                      </ul>
                    </div>
                  )}

                  {validationResult.warnings.length > 0 && (
                    <div className="mb-3">
                      <h6>Warnings:</h6>
                      <ul className="list-group">
                        {validationResult.warnings.map((warning, idx) => (
                          <li key={idx} className="list-group-item list-group-item-warning">{warning}</li>
                        ))}
                      </ul>
                    </div>
                  )}
                </div>
              </div>
            )}

            {importResult && (
              <div className="card mb-3">
                <div className="card-body">
                  <h6 className="card-subtitle mb-2 text-muted">Import Results</h6>

                  <div className={`alert ${importResult.success ? 'alert-success' : 'alert-warning'}`}>
                    <i className={`bi ${importResult.success ? 'bi-check-circle' : 'bi-exclamation-triangle'} me-2`}></i>
                    {importResult.message}
                  </div>

                  <div className="row mb-3">
                    <div className="col-md-6">
                      <h6>Imported:</h6>
                      <ul className="list-unstyled">
                        <li><i className="bi bi-check text-success me-2"></i>Users: {importResult.imported.users}</li>
                        <li><i className="bi bi-check text-success me-2"></i>Workgroups: {importResult.imported.workgroups}</li>
                        <li><i className="bi bi-check text-success me-2"></i>User Mappings: {importResult.imported.userMappings}</li>
                        <li><i className="bi bi-check text-success me-2"></i>Identity Providers: {importResult.imported.identityProviders}</li>
                        <li><i className="bi bi-check text-success me-2"></i>Falcon Configs: {importResult.imported.falconConfigs}</li>
                        <li><i className="bi bi-check text-success me-2"></i>MCP API Keys: {importResult.imported.mcpApiKeys}</li>
                      </ul>
                    </div>
                    <div className="col-md-6">
                      <h6>Skipped:</h6>
                      <ul className="list-unstyled">
                        <li><i className="bi bi-dash text-warning me-2"></i>Users: {importResult.skipped.users}</li>
                        <li><i className="bi bi-dash text-warning me-2"></i>Workgroups: {importResult.skipped.workgroups}</li>
                        <li><i className="bi bi-dash text-warning me-2"></i>User Mappings: {importResult.skipped.userMappings}</li>
                        <li><i className="bi bi-dash text-warning me-2"></i>Identity Providers: {importResult.skipped.identityProviders}</li>
                        <li><i className="bi bi-dash text-warning me-2"></i>Falcon Configs: {importResult.skipped.falconConfigs}</li>
                        <li><i className="bi bi-dash text-warning me-2"></i>MCP API Keys: {importResult.skipped.mcpApiKeys}</li>
                      </ul>
                    </div>
                  </div>

                  {importResult.newMcpApiKeys.length > 0 && (
                    <div className="alert alert-warning">
                      <h6><i className="bi bi-key me-2"></i>New MCP API Keys Generated</h6>
                      <p>The following API keys were generated. Please save them now as the secrets cannot be retrieved later.</p>
                      <button className="btn btn-sm btn-primary" onClick={downloadNewKeys}>
                        <i className="bi bi-download me-2"></i>
                        Download Keys
                      </button>
                    </div>
                  )}

                  {importResult.errors.length > 0 && (
                    <div className="mb-3">
                      <h6>Errors:</h6>
                      <ul className="list-group">
                        {importResult.errors.map((error, idx) => (
                          <li key={idx} className="list-group-item list-group-item-danger">{error}</li>
                        ))}
                      </ul>
                    </div>
                  )}

                  {importResult.warnings.length > 0 && (
                    <div className="mb-3">
                      <h6>Warnings:</h6>
                      <ul className="list-group">
                        {importResult.warnings.map((warning, idx) => (
                          <li key={idx} className="list-group-item list-group-item-warning">{warning}</li>
                        ))}
                      </ul>
                    </div>
                  )}
                </div>
              </div>
            )}

            <div className="d-flex gap-2">
              <button
                className="btn btn-secondary"
                onClick={handleValidate}
                disabled={!selectedFile || isValidating || isImporting}
              >
                {isValidating ? (
                  <>
                    <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                    Validating...
                  </>
                ) : (
                  <>
                    <i className="bi bi-check2-circle me-2"></i>
                    Validate Bundle
                  </>
                )}
              </button>

              <button
                className="btn btn-primary"
                onClick={handleImport}
                disabled={!selectedFile || !validationResult?.isValid || isImporting}
              >
                {isImporting ? (
                  <>
                    <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                    Importing...
                  </>
                ) : (
                  <>
                    <i className="bi bi-upload me-2"></i>
                    Import Configuration
                  </>
                )}
              </button>
            </div>
          </div>
        )}
      </div>

      {/* Secrets Modal */}
      {showSecretsModal && validationResult?.requiredSecrets && (
        <div className="modal show d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
          <div className="modal-dialog modal-lg">
            <div className="modal-content">
              <div className="modal-header">
                <h5 className="modal-title">Required Secrets</h5>
                <button type="button" className="btn-close" onClick={() => setShowSecretsModal(false)}></button>
              </div>
              <div className="modal-body">
                <p>The following secrets need to be provided for import:</p>
                {validationResult.requiredSecrets.map((secret) => {
                  const key = `${secret.entityType}:${secret.identifier}:${secret.secretType}`;
                  return (
                    <div key={key} className="mb-3">
                      <label htmlFor={key} className="form-label">
                        <strong>{secret.entityType}</strong> - {secret.identifier} ({secret.secretType})
                      </label>
                      <input
                        type="password"
                        className="form-control"
                        id={key}
                        value={providedSecrets[key] || ''}
                        onChange={(e) => handleSecretChange(key, e.target.value)}
                        placeholder={`Enter ${secret.secretType}`}
                      />
                    </div>
                  );
                })}
              </div>
              <div className="modal-footer">
                <button type="button" className="btn btn-secondary" onClick={() => setShowSecretsModal(false)}>
                  Cancel
                </button>
                <button
                  type="button"
                  className="btn btn-primary"
                  onClick={() => {
                    setShowSecretsModal(false);
                    handleImport();
                  }}
                >
                  Proceed with Import
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default ConfigBundleManager;