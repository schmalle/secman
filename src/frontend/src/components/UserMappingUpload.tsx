import React, { useState } from 'react';
import type { ImportResult } from '../services/userMappingService';
import { uploadUserMappings, uploadUserMappingsCSV, getSampleFileUrl, downloadCSVTemplate } from '../services/userMappingService';

/**
 * UserMappingUpload Component
 * Feature: 013-user-mapping-upload (Excel), 016-i-want-to (CSV)
 *
 * Provides UI for uploading Excel and CSV files with user-to-AWS-account-to-domain mappings.
 * Displays file requirements, handles upload, and shows results/errors.
 */
const UserMappingUpload: React.FC = () => {
  const [excelFile, setExcelFile] = useState<File | null>(null);
  const [csvFile, setCsvFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [result, setResult] = useState<ImportResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const handleExcelFileChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    const selectedFile = event.target.files?.[0];
    if (selectedFile) {
      setExcelFile(selectedFile);
      setResult(null);
      setError(null);
    }
  };

  const handleCsvFileChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    const selectedFile = event.target.files?.[0];
    if (selectedFile) {
      setCsvFile(selectedFile);
      setResult(null);
      setError(null);
    }
  };

  const handleExcelUpload = async () => {
    if (!excelFile) {
      setError('Please select an Excel file');
      return;
    }

    setUploading(true);
    setError(null);
    setResult(null);

    try {
      const response = await uploadUserMappings(excelFile);
      setResult(response);
      setExcelFile(null);
      // Clear file input
      const fileInput = document.getElementById('excelMappingFile') as HTMLInputElement;
      if (fileInput) {
        fileInput.value = '';
      }
    } catch (err: any) {
      const errorMessage = err.response?.data?.error || err.message || 'Failed to upload Excel file';
      setError(errorMessage);
    } finally {
      setUploading(false);
    }
  };

  const handleCsvUpload = async () => {
    if (!csvFile) {
      setError('Please select a CSV file');
      return;
    }

    setUploading(true);
    setError(null);
    setResult(null);

    try {
      const response = await uploadUserMappingsCSV(csvFile);
      setResult(response);
      setCsvFile(null);
      // Clear file input
      const fileInput = document.getElementById('csvMappingFile') as HTMLInputElement;
      if (fileInput) {
        fileInput.value = '';
      }
    } catch (err: any) {
      const errorMessage = err.response?.data?.error || err.message || 'Failed to upload CSV file';
      setError(errorMessage);
    } finally {
      setUploading(false);
    }
  };

  const handleCSVTemplateDownload = async () => {
    try {
      await downloadCSVTemplate();
    } catch (err: any) {
      const errorMessage = err.message || 'Failed to download CSV template';
      setError(errorMessage);
    }
  };

  return (
    <div className="container mt-4">
      <h2>
        <i className="bi bi-diagram-3-fill me-2"></i>
        User Mapping Upload
      </h2>
      <p className="text-muted">
        Upload Excel or CSV files to map users (by email) to AWS account IDs and organizational domains.
      </p>

      {/* File Requirements Card */}
      <div className="card mb-4">
        <div className="card-header bg-info text-white">
          <i className="bi bi-info-circle me-2"></i>
          File Requirements
        </div>
        <div className="card-body">
          <div className="row">
            <div className="col-md-6">
              <h6 className="fw-bold">Excel Format (.xlsx)</h6>
              <ul>
                <li><strong>Max Size:</strong> 10 MB</li>
                <li><strong>Required Columns:</strong>
                  <ul className="small">
                    <li><code>Email Address</code></li>
                    <li><code>AWS Account ID</code> (12 digits)</li>
                    <li><code>Domain</code></li>
                  </ul>
                </li>
                <li>
                  <a href={getSampleFileUrl()} download className="btn btn-sm btn-outline-primary">
                    <i className="bi bi-file-earmark-excel me-1"></i>
                    Download Excel Template
                  </a>
                </li>
              </ul>
            </div>
            <div className="col-md-6">
              <h6 className="fw-bold">CSV Format (.csv)</h6>
              <ul>
                <li><strong>Max Size:</strong> 10 MB</li>
                <li><strong>Required Columns:</strong>
                  <ul className="small">
                    <li><code>account_id</code> (12 digits)</li>
                    <li><code>owner_email</code></li>
                    <li><code>domain</code> (optional, defaults to "-NONE-")</li>
                  </ul>
                </li>
                <li><strong>Supported:</strong> Comma, semicolon, tab delimiters; case-insensitive headers; scientific notation</li>
                <li>
                  <button onClick={handleCSVTemplateDownload} className="btn btn-sm btn-outline-secondary">
                    <i className="bi bi-file-earmark-text me-1"></i>
                    Download CSV Template
                  </button>
                </li>
              </ul>
            </div>
          </div>
        </div>
      </div>

      {/* File Upload Section */}
      <div className="row">
        {/* Excel Upload */}
        <div className="col-md-6">
          <div className="card mb-4">
            <div className="card-header">
              <i className="bi bi-file-earmark-excel me-2"></i>
              Upload Excel File
            </div>
            <div className="card-body">
              <div className="mb-3">
                <label htmlFor="excelMappingFile" className="form-label">
                  Select Excel File (.xlsx)
                </label>
                <input
                  type="file"
                  className="form-control"
                  id="excelMappingFile"
                  accept=".xlsx"
                  onChange={handleExcelFileChange}
                  disabled={uploading}
                />
                {excelFile && (
                  <div className="form-text">
                    <i className="bi bi-check-circle-fill text-success me-1"></i>
                    Selected: {excelFile.name} ({(excelFile.size / 1024).toFixed(2)} KB)
                  </div>
                )}
              </div>

              <button
                className="btn btn-primary w-100"
                onClick={handleExcelUpload}
                disabled={!excelFile || uploading}
              >
                {uploading ? (
                  <>
                    <span className="spinner-border spinner-border-sm me-2" role="status" aria-label="Uploading"></span>
                    Uploading Excel...
                  </>
                ) : (
                  <>
                    <i className="bi bi-upload me-2"></i>
                    Upload Excel
                  </>
                )}
              </button>
            </div>
          </div>
        </div>

        {/* CSV Upload */}
        <div className="col-md-6">
          <div className="card mb-4">
            <div className="card-header">
              <i className="bi bi-file-earmark-text me-2"></i>
              Upload CSV File
            </div>
            <div className="card-body">
              <div className="mb-3">
                <label htmlFor="csvMappingFile" className="form-label">
                  Select CSV File (.csv)
                </label>
                <input
                  type="file"
                  className="form-control"
                  id="csvMappingFile"
                  accept=".csv"
                  onChange={handleCsvFileChange}
                  disabled={uploading}
                />
                {csvFile && (
                  <div className="form-text">
                    <i className="bi bi-check-circle-fill text-success me-1"></i>
                    Selected: {csvFile.name} ({(csvFile.size / 1024).toFixed(2)} KB)
                  </div>
                )}
              </div>

              <button
                className="btn btn-success w-100"
                onClick={handleCsvUpload}
                disabled={!csvFile || uploading}
              >
                {uploading ? (
                  <>
                    <span className="spinner-border spinner-border-sm me-2" role="status" aria-label="Uploading"></span>
                    Uploading CSV...
                  </>
                ) : (
                  <>
                    <i className="bi bi-upload me-2"></i>
                    Upload CSV
                  </>
                )}
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Error Alert */}
      {error && (
        <div className="alert alert-danger alert-dismissible fade show" role="alert">
          <i className="bi bi-exclamation-triangle-fill me-2"></i>
          <strong>Upload Failed</strong>
          <p className="mb-0 mt-2">{error}</p>
          <button
            type="button"
            className="btn-close"
            onClick={() => setError(null)}
            aria-label="Close"
          ></button>
        </div>
      )}

      {/* Success Alert */}
      {result && (
        <div className="alert alert-success alert-dismissible fade show" role="alert">
          <i className="bi bi-check-circle-fill me-2"></i>
          <strong>Import Complete</strong>
          <div className="mt-2">
            <p className="mb-1">{result.message}</p>
            <ul className="mb-0">
              <li><strong>Imported:</strong> {result.imported} mappings</li>
              <li><strong>Skipped:</strong> {result.skipped} mappings</li>
            </ul>
          </div>

          {/* Error List */}
          {result.errors && result.errors.length > 0 && (
            <div className="mt-3">
              <strong>Details:</strong>
              <div className="mt-2" style={{ maxHeight: '200px', overflowY: 'auto' }}>
                <ul className="mb-0 small">
                  {result.errors.map((err, idx) => (
                    <li key={idx} className="text-danger">{err}</li>
                  ))}
                </ul>
              </div>
            </div>
          )}

          <button
            type="button"
            className="btn-close"
            onClick={() => setResult(null)}
            aria-label="Close"
          ></button>
        </div>
      )}
    </div>
  );
};

export default UserMappingUpload;
