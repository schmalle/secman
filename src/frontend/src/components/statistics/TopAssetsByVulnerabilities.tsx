/**
 * React component for displaying top assets by vulnerability count
 *
 * Displays top 50 assets ranked by total vulnerability count with severity breakdowns.
 * Features:
 * - Bootstrap table with responsive design
 * - Severity badges for counts
 * - Click handlers for navigation to system vulnerabilities page
 * - Excel export functionality
 * - Loading, error, and empty states
 *
 * Feature: 036-vuln-stats-lense
 * Task: T040 [US3]
 * Spec reference: spec.md FR-005, FR-006
 * User Story: US3 - View Asset Vulnerability Statistics (P3)
 */

import React, { useState } from 'react';
import { vulnerabilityStatisticsApi, type TopAssetByVulnerabilitiesDto } from '../../services/api/vulnerabilityStatisticsApi';
import { downloadSingleSheetWorkbook, todayStamp } from '../../utils/excelExport';
import { ChartCardEmpty, ChartCardError, ChartCardLoading } from './ChartCardStates';
import { useChartData } from './useChartData';

/**
 * Handle row click - navigate to system vulnerabilities page
 */
const handleRowClick = (assetName: string) => {
  window.location.href = `/vulnerabilities/system?hostname=${encodeURIComponent(assetName)}`;
};

/**
 * Export data to Excel file
 */
async function exportToExcel(data: TopAssetByVulnerabilitiesDto[]): Promise<void> {
  await downloadSingleSheetWorkbook({
    filename: `Top_50_Assets_by_Vulnerabilities_${todayStamp()}.xlsx`,
    sheetName: 'Top Assets by Vulnerabilities',
    headerColor: 'FF4472C4',
    headerHeight: 20,
    centerHeader: true,
    bordered: true,
    columns: [
      { header: '#', key: 'rank', width: 5 },
      { header: 'Asset Name', key: 'assetName', width: 40 },
      { header: 'IP Address', key: 'assetIp', width: 18 },
      { header: 'Type', key: 'assetType', width: 15 },
      { header: 'Total', key: 'total', width: 10 },
      { header: 'Critical', key: 'critical', width: 10 },
      { header: 'High', key: 'high', width: 10 },
      { header: 'Medium', key: 'medium', width: 10 },
      { header: 'Low', key: 'low', width: 10 },
    ],
    rows: data.map((asset, index) => ({
      rank: index + 1,
      assetName: asset.assetName,
      assetIp: asset.assetIp || '',
      assetType: asset.assetType || 'Unknown',
      total: asset.totalVulnerabilityCount,
      critical: asset.criticalCount,
      high: asset.highCount,
      medium: asset.mediumCount,
      low: asset.lowCount,
    })),
  });
}

interface TopAssetsByVulnerabilitiesProps {
  domain?: string | null;
  awsHosted?: boolean;
}

export default function TopAssetsByVulnerabilities({ domain, awsHosted }: TopAssetsByVulnerabilitiesProps) {
  const [exporting, setExporting] = useState<boolean>(false);
  const { data: fetched, loading, error } = useChartData<TopAssetByVulnerabilitiesDto[]>(
    () => vulnerabilityStatisticsApi.getTopAssetsByVulnerabilities(domain, awsHosted),
    [domain, awsHosted],
    'Failed to load asset statistics. Please try again later.',
    'top assets by vulnerabilities',
  );
  const data = fetched ?? [];

  const handleExport = async () => {
    try {
      setExporting(true);
      await exportToExcel(data);
    } catch (err) {
      console.error('Error exporting to Excel:', err);
    } finally {
      setExporting(false);
    }
  };

  if (loading) return <ChartCardLoading label="Loading asset statistics..." />;
  if (error) return <ChartCardError message={error} />;
  if (data.length === 0) {
    return (
      <ChartCardEmpty
        heading={{ icon: 'bi-server', title: 'Top 50 Assets by Vulnerability Count' }}
        message="No asset data available."
      />
    );
  }

  // Data table
  return (
    <div className="card">
      <div className="card-header d-flex justify-content-between align-items-center">
        <h5 className="mb-0">
          <i className="bi bi-server me-2"></i>
          Top 50 Assets by Vulnerability Count
        </h5>
        <button
          className="btn btn-sm btn-outline-success"
          onClick={handleExport}
          disabled={exporting}
          title="Export to Excel"
        >
          {exporting ? (
            <>
              <span className="spinner-border spinner-border-sm me-1" role="status" aria-hidden="true"></span>
              Exporting...
            </>
          ) : (
            <>
              <i className="bi bi-file-earmark-excel me-1"></i>
              Export Excel
            </>
          )}
        </button>
      </div>
      <div className="card-body p-0">
        <div className="table-responsive" style={{ maxHeight: '600px', overflowY: 'auto' }}>
          <table className="table table-hover table-sm mb-0">
            <thead className="table-light sticky-top">
              <tr>
                <th scope="col">#</th>
                <th scope="col">Asset Name</th>
                <th scope="col">Type</th>
                <th scope="col">Total</th>
                <th scope="col">Critical</th>
                <th scope="col">High</th>
                <th scope="col">Medium</th>
                <th scope="col">Low</th>
              </tr>
            </thead>
            <tbody>
              {data.map((asset, index) => (
                <tr
                  key={asset.assetId}
                  onClick={() => handleRowClick(asset.assetName)}
                  style={{ cursor: 'pointer' }}
                  title={`Click to view vulnerabilities for ${asset.assetName}`}
                >
                  <td className="align-middle">{index + 1}</td>
                  <td className="align-middle">
                    <strong>{asset.assetName}</strong>
                    {asset.assetIp && <><br /><small className="text-muted">{asset.assetIp}</small></>}
                  </td>
                  <td className="align-middle">
                    <span className="badge bg-secondary">{asset.assetType || 'Unknown'}</span>
                  </td>
                  <td className="align-middle">
                    <span className="badge bg-dark">{asset.totalVulnerabilityCount}</span>
                  </td>
                  <td className="align-middle">
                    {asset.criticalCount > 0 && <span className="badge bg-danger">{asset.criticalCount}</span>}
                  </td>
                  <td className="align-middle">
                    {asset.highCount > 0 && <span className="badge bg-warning text-dark">{asset.highCount}</span>}
                  </td>
                  <td className="align-middle">
                    {asset.mediumCount > 0 && <span className="badge bg-info text-dark">{asset.mediumCount}</span>}
                  </td>
                  <td className="align-middle">
                    {asset.lowCount > 0 && <span className="badge bg-primary">{asset.lowCount}</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <div className="card-footer text-muted small">
        <i className="bi bi-info-circle me-1"></i>
        Click any row to view system vulnerabilities. Showing {data.length} assets.
      </div>
    </div>
  );
}
