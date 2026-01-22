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

import React, { useEffect, useState } from 'react';
import ExcelJS from 'exceljs';
import { vulnerabilityStatisticsApi, type TopAssetByVulnerabilitiesDto } from '../../services/api/vulnerabilityStatisticsApi';

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
  const workbook = new ExcelJS.Workbook();
  workbook.creator = 'Secman';
  workbook.created = new Date();

  const sheet = workbook.addWorksheet('Top Assets by Vulnerabilities');

  // Define columns
  sheet.columns = [
    { header: '#', key: 'rank', width: 5 },
    { header: 'Asset Name', key: 'assetName', width: 40 },
    { header: 'IP Address', key: 'assetIp', width: 18 },
    { header: 'Type', key: 'assetType', width: 15 },
    { header: 'Total', key: 'total', width: 10 },
    { header: 'Critical', key: 'critical', width: 10 },
    { header: 'High', key: 'high', width: 10 },
    { header: 'Medium', key: 'medium', width: 10 },
    { header: 'Low', key: 'low', width: 10 },
  ];

  // Add data rows
  data.forEach((asset, index) => {
    sheet.addRow({
      rank: index + 1,
      assetName: asset.assetName,
      assetIp: asset.assetIp || '',
      assetType: asset.assetType || 'Unknown',
      total: asset.totalVulnerabilityCount,
      critical: asset.criticalCount,
      high: asset.highCount,
      medium: asset.mediumCount,
      low: asset.lowCount,
    });
  });

  // Style header row
  const headerRow = sheet.getRow(1);
  headerRow.font = { bold: true, size: 11, color: { argb: 'FFFFFFFF' } };
  headerRow.fill = {
    type: 'pattern',
    pattern: 'solid',
    fgColor: { argb: 'FF4472C4' },
  };
  headerRow.alignment = { vertical: 'middle', horizontal: 'center' };
  headerRow.height = 20;

  // Add borders to all cells
  sheet.eachRow((row) => {
    row.eachCell((cell) => {
      cell.border = {
        top: { style: 'thin' },
        left: { style: 'thin' },
        bottom: { style: 'thin' },
        right: { style: 'thin' },
      };
    });
  });

  // Generate and download
  const buffer = await workbook.xlsx.writeBuffer();
  const blob = new Blob([buffer], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });

  const url = window.URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `Top_50_Assets_by_Vulnerabilities_${new Date().toISOString().split('T')[0]}.xlsx`;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  window.URL.revokeObjectURL(url);
}

export default function TopAssetsByVulnerabilities() {
  const [data, setData] = useState<TopAssetByVulnerabilitiesDto[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [exporting, setExporting] = useState<boolean>(false);

  useEffect(() => {
    const fetchData = async () => {
      try {
        setLoading(true);
        setError(null);
        const result = await vulnerabilityStatisticsApi.getTopAssetsByVulnerabilities();
        setData(result);
      } catch (err) {
        console.error('Error fetching top assets by vulnerabilities:', err);
        setError('Failed to load asset statistics. Please try again later.');
      } finally {
        setLoading(false);
      }
    };

    fetchData();
  }, []);

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

  // Loading state
  if (loading) {
    return (
      <div className="card">
        <div className="card-body text-center py-5">
          <div className="spinner-border text-primary" role="status">
            <span className="visually-hidden">Loading...</span>
          </div>
          <p className="mt-3 text-muted">Loading asset statistics...</p>
        </div>
      </div>
    );
  }

  // Error state
  if (error) {
    return (
      <div className="card">
        <div className="card-body">
          <div className="alert alert-danger" role="alert">
            <i className="bi bi-exclamation-triangle me-2"></i>
            {error}
          </div>
        </div>
      </div>
    );
  }

  // Empty state
  if (data.length === 0) {
    return (
      <div className="card">
        <div className="card-header">
          <h5 className="mb-0">
            <i className="bi bi-server me-2"></i>
            Top 50 Assets by Vulnerability Count
          </h5>
        </div>
        <div className="card-body text-center py-5">
          <i className="bi bi-inbox display-4 text-muted"></i>
          <p className="mt-3 text-muted">No asset data available.</p>
        </div>
      </div>
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
