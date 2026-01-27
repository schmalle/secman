/**
 * React component for displaying most vulnerable products table
 *
 * Displays top 10 products ranked by distinct vulnerability count.
 * Features:
 * - Bootstrap table with responsive design
 * - Critical/High severity counts with color coding
 * - Click handlers for drill-down navigation to product details
 * - Loading, error, and empty states
 * - Excel export for affected systems
 *
 * Feature: 036-vuln-stats-lense
 */

import React, { useEffect, useState } from 'react';
import ExcelJS from 'exceljs';
import { vulnerabilityStatisticsApi, type MostVulnerableProductDto, type AssetsByProductDto } from '../../services/api/vulnerabilityStatisticsApi';

/**
 * Props for MostVulnerableProducts component
 *
 * Feature: 059-vuln-stats-domain-filter
 * Task: T013 [US1]
 */
interface MostVulnerableProductsProps {
  /** Optional AD domain filter (null = all domains) */
  domain?: string | null;
}

export default function MostVulnerableProducts({ domain }: MostVulnerableProductsProps = {}) {
  const [data, setData] = useState<MostVulnerableProductDto[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  // Modal state for product drilldown
  const [showModal, setShowModal] = useState<boolean>(false);
  const [modalLoading, setModalLoading] = useState<boolean>(false);
  const [modalData, setModalData] = useState<AssetsByProductDto | null>(null);
  const [modalError, setModalError] = useState<string | null>(null);

  // Export state
  const [exporting, setExporting] = useState<boolean>(false);

  /**
   * Handle row click - show systems with this product
   */
  const handleRowClick = async (product: string) => {
    setShowModal(true);
    setModalLoading(true);
    setModalError(null);
    setModalData(null);

    try {
      const result = await vulnerabilityStatisticsApi.getAssetsByProduct(product, domain);
      setModalData(result);
    } catch (err) {
      console.error('Error fetching assets with product:', err);
      setModalError('Failed to load systems. Please try again.');
    } finally {
      setModalLoading(false);
    }
  };

  const closeModal = () => {
    setShowModal(false);
    setModalData(null);
    setModalError(null);
  };

  /**
   * Export assets with product to Excel
   */
  const handleExportAssets = async () => {
    if (!modalData || modalData.assets.length === 0) return;

    setExporting(true);
    try {
      const workbook = new ExcelJS.Workbook();
      workbook.creator = 'Secman';
      workbook.created = new Date();

      const sheet = workbook.addWorksheet('Systems with Product');
      sheet.columns = [
        { header: 'System Name', key: 'name', width: 30 },
        { header: 'IP Address', key: 'ip', width: 18 },
        { header: 'Domain', key: 'domain', width: 20 },
        { header: 'Type', key: 'type', width: 15 },
        { header: 'Vulnerabilities', key: 'vulns', width: 15 },
      ];

      modalData.assets.forEach((asset) => {
        sheet.addRow({
          name: asset.assetName,
          ip: asset.assetIp || '-',
          domain: asset.adDomain || '-',
          type: asset.assetType || '-',
          vulns: asset.vulnerabilityCount,
        });
      });

      // Style header
      const headerRow = sheet.getRow(1);
      headerRow.font = { bold: true, color: { argb: 'FFFFFFFF' } };
      headerRow.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FF3D4F4F' } };
      headerRow.height = 22;

      // Generate and download
      const buffer = await workbook.xlsx.writeBuffer();
      const blob = new Blob([buffer], {
        type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
      });

      const safeProduct = modalData.product.replace(/[^a-zA-Z0-9-]/g, '_').substring(0, 50);
      const dateStr = new Date().toISOString().split('T')[0];
      const url = window.URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `Systems_${safeProduct}_${dateStr}.xlsx`;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      window.URL.revokeObjectURL(url);
    } catch (err) {
      console.error('Export failed:', err);
    } finally {
      setExporting(false);
    }
  };

  useEffect(() => {
    const fetchData = async () => {
      try {
        setLoading(true);
        setError(null);
        const result = await vulnerabilityStatisticsApi.getMostVulnerableProducts(domain);
        setData(result);
      } catch (err) {
        console.error('Error fetching most vulnerable products:', err);
        setError('Failed to load product statistics. Please try again later.');
      } finally {
        setLoading(false);
      }
    };

    fetchData();
  }, [domain]); // Re-fetch when domain changes

  // Loading state
  if (loading) {
    return (
      <div className="card">
        <div className="card-body text-center py-5">
          <div className="spinner-border text-primary" role="status">
            <span className="visually-hidden">Loading...</span>
          </div>
          <p className="mt-3 text-muted">Loading product statistics...</p>
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
        <div className="card-body text-center py-5">
          <i className="bi bi-inbox display-4 text-muted"></i>
          <p className="mt-3 text-muted">No product vulnerability data available.</p>
          <small className="text-muted">
            This could mean no vulnerabilities with product information have been imported yet.
          </small>
        </div>
      </div>
    );
  }

  // Data table
  return (
    <div className="card">
      <div className="card-header" style={{backgroundColor: 'var(--scand-bg-header)', color: 'var(--scand-text-light)'}}>
        <h5 className="mb-0">
          <i className="bi bi-box-seam me-2"></i>
          Top 10 Most Vulnerable Products
        </h5>
      </div>
      <div className="card-body p-0">
        <div className="table-responsive">
          <table className="table table-hover mb-0">
            <thead className="table-light">
              <tr>
                <th scope="col">#</th>
                <th scope="col">Product</th>
                <th scope="col">Vulnerabilities</th>
                <th scope="col">Affected Assets</th>
                <th scope="col">Critical</th>
                <th scope="col">High</th>
              </tr>
            </thead>
            <tbody>
              {data.map((product, index) => (
                <tr
                  key={`${product.product}-${index}`}
                  onClick={() => handleRowClick(product.product)}
                  style={{ cursor: 'pointer' }}
                  title={`Click to view systems with ${product.product}`}
                >
                  <td className="align-middle">{index + 1}</td>
                  <td className="align-middle">
                    <strong>{product.product}</strong>
                  </td>
                  <td className="align-middle">
                    <span className="badge bg-light text-dark">
                      {product.vulnerabilityCount.toLocaleString()}
                    </span>
                  </td>
                  <td className="align-middle">
                    <span className="badge bg-light text-dark">
                      {product.affectedAssetCount.toLocaleString()}
                    </span>
                  </td>
                  <td className="align-middle">
                    {product.criticalCount > 0 ? (
                      <span className="badge scand-critical">
                        {product.criticalCount.toLocaleString()}
                      </span>
                    ) : (
                      <span className="badge scand-neutral">0</span>
                    )}
                  </td>
                  <td className="align-middle">
                    {product.highCount > 0 ? (
                      <span className="badge scand-high">
                        {product.highCount.toLocaleString()}
                      </span>
                    ) : (
                      <span className="badge scand-neutral">0</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <div className="card-footer text-muted small">
        <i className="bi bi-info-circle me-1"></i>
        Showing top 10 products ranked by distinct vulnerability count. Click any row for details.
      </div>

      {/* Product Systems Modal */}
      {showModal && (
        <div className="modal show d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
          <div className="modal-dialog modal-lg modal-dialog-scrollable">
            <div className="modal-content">
              <div className="modal-header" style={{ backgroundColor: 'var(--scand-bg-header)', color: 'var(--scand-text-light)' }}>
                <h5 className="modal-title">
                  <i className="bi bi-box-seam me-2"></i>
                  Systems with Product {modalData && `- ${modalData.product}`}
                </h5>
                <button type="button" className="btn-close btn-close-white" onClick={closeModal}></button>
              </div>
              <div className="modal-body">
                {modalLoading && (
                  <div className="text-center py-4">
                    <div className="spinner-border text-primary" role="status">
                      <span className="visually-hidden">Loading...</span>
                    </div>
                    <p className="mt-2 text-muted">Loading systems...</p>
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
                      <span className="text-muted">
                        {modalData.totalCount} system{modalData.totalCount !== 1 ? 's' : ''} found
                        {domain && <span className="ms-1">(in domain: {domain})</span>}
                      </span>
                    </div>

                    {modalData.assets.length === 0 ? (
                      <p className="text-muted">No systems found with this product in your accessible scope.</p>
                    ) : (
                      <div className="table-responsive">
                        <table className="table table-sm table-hover mb-0">
                          <thead className="table-light">
                            <tr>
                              <th>System Name</th>
                              <th>IP Address</th>
                              <th>Domain</th>
                              <th>Type</th>
                              <th>Vulnerabilities</th>
                            </tr>
                          </thead>
                          <tbody>
                            {modalData.assets.map((asset) => (
                              <tr key={asset.assetId}>
                                <td>
                                  <a href={`/assets/${asset.assetId}`} className="text-decoration-none">
                                    {asset.assetName}
                                  </a>
                                </td>
                                <td>{asset.assetIp || '-'}</td>
                                <td>{asset.adDomain || '-'}</td>
                                <td>{asset.assetType || '-'}</td>
                                <td>
                                  <span className="badge bg-light text-dark">
                                    {asset.vulnerabilityCount.toLocaleString()}
                                  </span>
                                </td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    )}

                    {modalData.totalCount >= 100 && (
                      <div className="alert alert-info mt-3 mb-0" role="alert">
                        <i className="bi bi-info-circle me-2"></i>
                        Showing first 100 systems. Use the asset overview for a complete list.
                      </div>
                    )}
                  </>
                )}
              </div>
              <div className="modal-footer">
                {modalData && modalData.assets.length > 0 && (
                  <button
                    type="button"
                    className="btn btn-outline-success me-auto"
                    onClick={handleExportAssets}
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
