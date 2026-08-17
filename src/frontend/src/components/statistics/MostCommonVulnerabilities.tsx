/**
 * React component for displaying most common vulnerabilities table
 *
 * Displays top 10 vulnerabilities ranked by occurrence frequency.
 * Features:
 * - Bootstrap table with responsive design
 * - Severity badges with color coding (CRITICAL=red, HIGH=orange, MEDIUM=yellow, LOW=blue, UNKNOWN=gray)
 * - Click handlers for drill-down navigation to vulnerability details
 * - Loading, error, and empty states
 *
 * Feature: 036-vuln-stats-lense
 * Task: T016 [US1]
 * Spec reference: spec.md FR-001, FR-002
 * User Story: US1 - View Most Common Vulnerabilities (P1/MVP)
 */

import React, { useState } from 'react';
import { vulnerabilityStatisticsApi, type MostCommonVulnerabilityDto, type AffectedAssetsByCveDto } from '../../services/api/vulnerabilityStatisticsApi';
import CveLink from '../CveLink';
import { downloadSingleSheetWorkbook, sanitizeFilenamePart, todayStamp } from '../../utils/excelExport';
import { ChartCardEmpty, ChartCardError, ChartCardLoading } from './ChartCardStates';
import { useChartData } from './useChartData';

/**
 * Map severity levels to Scandinavian design system badge classes
 */
const severityBadgeClass = (severity: string): string => {
  switch (severity.toUpperCase()) {
    case 'CRITICAL':
      return 'badge scand-critical';
    case 'HIGH':
      return 'badge scand-high';
    case 'MEDIUM':
      return 'badge scand-medium';
    case 'LOW':
      return 'badge scand-low';
    case 'UNKNOWN':
    default:
      return 'badge scand-neutral';
  }
};

/**
 * Props for MostCommonVulnerabilities component
 *
 * Feature: 059-vuln-stats-domain-filter
 * Task: T012 [US1]
 */
interface MostCommonVulnerabilitiesProps {
  /** Optional AD domain filter (null = all domains) */
  domain?: string | null;
  /** Optional AWS hosted filter (true = only cloud-hosted assets) */
  awsHosted?: boolean;
}

export default function MostCommonVulnerabilities({ domain, awsHosted }: MostCommonVulnerabilitiesProps = {}) {
  const { data: fetched, loading, error } = useChartData<MostCommonVulnerabilityDto[]>(
    () => vulnerabilityStatisticsApi.getMostCommonVulnerabilities(domain, awsHosted),
    [domain, awsHosted],
    'Failed to load vulnerability statistics. Please try again later.',
    'most common vulnerabilities',
  );
  const data = fetched ?? [];

  // Modal state for CVE drilldown
  const [showModal, setShowModal] = useState<boolean>(false);
  const [modalLoading, setModalLoading] = useState<boolean>(false);
  const [modalData, setModalData] = useState<AffectedAssetsByCveDto | null>(null);
  const [modalError, setModalError] = useState<string | null>(null);

  /**
   * Handle row click - show affected systems modal
   */
  const handleRowClick = async (cveId: string) => {
    setShowModal(true);
    setModalLoading(true);
    setModalError(null);
    setModalData(null);

    try {
      const result = await vulnerabilityStatisticsApi.getAffectedAssetsByCve(cveId, domain, awsHosted);
      setModalData(result);
    } catch (err) {
      console.error('Error fetching affected assets:', err);
      setModalError('Failed to load affected systems. Please try again.');
    } finally {
      setModalLoading(false);
    }
  };

  const closeModal = () => {
    setShowModal(false);
    setModalData(null);
    setModalError(null);
  };

  // Export state
  const [exporting, setExporting] = useState<boolean>(false);

  /**
   * Export affected systems to Excel
   */
  const handleExportAffectedSystems = async () => {
    if (!modalData || modalData.affectedAssets.length === 0) return;

    setExporting(true);
    try {
      await downloadSingleSheetWorkbook({
        filename: `Affected_Systems_${sanitizeFilenamePart(modalData.cveId)}_${todayStamp()}.xlsx`,
        sheetName: 'Affected Systems',
        headerColor: 'FF3D4F4F',
        columns: [
          { header: 'System Name', key: 'name', width: 30 },
          { header: 'IP Address', key: 'ip', width: 18 },
          { header: 'Instance ID', key: 'instanceId', width: 22 },
          { header: 'Domain', key: 'domain', width: 20 },
          { header: 'Type', key: 'type', width: 15 },
        ],
        rows: modalData.affectedAssets.map((asset) => ({
          name: asset.assetName,
          ip: asset.assetIp || '-',
          instanceId: asset.cloudInstanceId || '-',
          domain: asset.adDomain || '-',
          type: asset.assetType || '-',
        })),
      });
    } catch (err) {
      console.error('Export failed:', err);
    } finally {
      setExporting(false);
    }
  };

  if (loading) return <ChartCardLoading label="Loading vulnerability statistics..." />;
  if (error) return <ChartCardError message={error} />;
  if (data.length === 0) {
    return (
      <ChartCardEmpty
        message="No vulnerability data available."
        hint="This could mean no vulnerabilities have been imported yet, or you don't have access to any workgroups with vulnerabilities."
      />
    );
  }

  // Data table
  return (
    <div className="card">
      <div className="card-header" style={{backgroundColor: 'var(--scand-bg-header)', color: 'var(--scand-text-light)'}}>
        <h5 className="mb-0">
          <i className="bi bi-shield-exclamation me-2"></i>
          Top 10 Most Common Vulnerabilities
        </h5>
      </div>
      <div className="card-body p-0">
        <div className="table-responsive">
          <table className="table table-hover mb-0">
            <thead className="table-light">
              <tr>
                <th scope="col">#</th>
                <th scope="col">CVE ID</th>
                <th scope="col">Severity</th>
                <th scope="col">Total Occurrences</th>
                <th scope="col">Affected Assets</th>
              </tr>
            </thead>
            <tbody>
              {data.map((vuln, index) => (
                <tr
                  key={`${vuln.vulnerabilityId}-${vuln.cvssSeverity}`}
                  onClick={() => handleRowClick(vuln.vulnerabilityId)}
                  style={{ cursor: 'pointer' }}
                  title={`Click to view details for ${vuln.vulnerabilityId}`}
                >
                  <td className="align-middle">{index + 1}</td>
                  <td className="align-middle">
                    <CveLink cveId={vuln.vulnerabilityId} />
                  </td>
                  <td className="align-middle">
                    <span className={severityBadgeClass(vuln.cvssSeverity)}>
                      {vuln.cvssSeverity}
                    </span>
                  </td>
                  <td className="align-middle">
                    <span className="badge bg-light text-dark">
                      {vuln.occurrenceCount.toLocaleString()}
                    </span>
                  </td>
                  <td className="align-middle">
                    <span className="badge bg-light text-dark">
                      {vuln.affectedAssetCount.toLocaleString()}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <div className="card-footer text-muted small">
        <i className="bi bi-info-circle me-1"></i>
        Showing top 10 vulnerabilities ranked by total occurrences. Click any row for details.
      </div>

      {/* CVE Affected Systems Modal */}
      {showModal && (
        <div className="modal show d-block" tabIndex={-1} style={{ backgroundColor: 'var(--scand-overlay)' }}>
          <div className="modal-dialog modal-lg modal-dialog-scrollable">
            <div className="modal-content">
              <div className="modal-header" style={{ backgroundColor: 'var(--scand-bg-header)', color: 'var(--scand-text-light)' }}>
                <h5 className="modal-title">
                  <i className="bi bi-server me-2"></i>
                  Affected Systems {modalData && `- ${modalData.cveId}`}
                </h5>
                <button type="button" className="btn-close btn-close-white" onClick={closeModal}></button>
              </div>
              <div className="modal-body">
                {modalLoading && (
                  <div className="text-center py-4">
                    <div className="spinner-border text-primary" role="status">
                      <span className="visually-hidden">Loading...</span>
                    </div>
                    <p className="mt-2 text-muted">Loading affected systems...</p>
                  </div>
                )}

                {modalError && (
                  <div className="alert alert-danger" role="alert">
                    <i className="bi bi-exclamation-triangle me-2"></i>
                    {modalError}
                  </div>
                )}

                {modalData && !modalLoading && (
                  <>
                    <div className="mb-3">
                      <span className={severityBadgeClass(modalData.severity)}>{modalData.severity}</span>
                      <span className="ms-2 text-muted">
                        {modalData.totalCount} system{modalData.totalCount !== 1 ? 's' : ''} affected
                        {domain && <span className="ms-1">(in domain: {domain})</span>}
                      </span>
                    </div>

                    {modalData.affectedAssets.length === 0 ? (
                      <p className="text-muted">No systems found for this vulnerability in your accessible scope.</p>
                    ) : (
                      <div className="table-responsive">
                        <table className="table table-sm table-hover mb-0">
                          <thead className="table-light">
                            <tr>
                              <th>System Name</th>
                              <th>IP Address</th>
                              <th>Instance ID</th>
                              <th>Domain</th>
                              <th>Type</th>
                            </tr>
                          </thead>
                          <tbody>
                            {modalData.affectedAssets.map((asset) => (
                              <tr key={asset.assetId}>
                                <td>
                                  <a href={`/assets/${asset.assetId}`} className="text-decoration-none">
                                    {asset.assetName}
                                  </a>
                                </td>
                                <td>{asset.assetIp || '-'}</td>
                                <td>{asset.cloudInstanceId || '-'}</td>
                                <td>{asset.adDomain || '-'}</td>
                                <td>{asset.assetType || '-'}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    )}

                    {modalData.totalCount >= 100 && (
                      <div className="alert alert-info mt-3 mb-0" role="alert">
                        <i className="bi bi-info-circle me-2"></i>
                        Showing first 100 affected systems. Use the asset overview for a complete list.
                      </div>
                    )}
                  </>
                )}
              </div>
              <div className="modal-footer">
                {modalData && modalData.affectedAssets.length > 0 && (
                  <button
                    type="button"
                    className="btn btn-outline-success me-auto"
                    onClick={handleExportAffectedSystems}
                    disabled={exporting}
                  >
                    {exporting ? (
                      <>
                        <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                        Exporting...
                      </>
                    ) : (
                      <>
                        <i className="bi bi-file-earmark-excel me-2"></i>
                        Export to Excel
                      </>
                    )}
                  </button>
                )}
                <button type="button" className="btn btn-secondary" onClick={closeModal}>Close</button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
