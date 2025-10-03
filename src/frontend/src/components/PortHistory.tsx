import React, { useState, useEffect } from 'react';
import { authenticatedGet } from '../utils/auth';

interface Port {
  portNumber: number;
  protocol: string;
  state: string;
  service: string | null;
  version: string | null;
}

interface ScanPorts {
  scanId: number;
  scanDate: string;
  scanType: string;
  ports: Port[];
}

interface PortHistoryData {
  assetId: number;
  assetName: string;
  scans: ScanPorts[];
}

interface PortHistoryProps {
  assetId: number;
  assetName: string;
  onClose: () => void;
}

/**
 * PortHistory component
 *
 * Displays port scan history for an asset in a modal.
 * Shows timeline of port changes across multiple scans.
 *
 * Related to:
 * - Feature: 002-implement-a-parsing (Nmap Scan Import)
 * - FR-011: Display port scan history
 */
const PortHistory: React.FC<PortHistoryProps> = ({ assetId, assetName, onClose }) => {
  const [portHistory, setPortHistory] = useState<PortHistoryData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchPortHistory();
  }, [assetId]);

  const fetchPortHistory = async () => {
    setLoading(true);
    setError(null);

    try {
      const response = await authenticatedGet(`/api/assets/${assetId}/ports`);

      if (response.ok) {
        const data: PortHistoryData = await response.json();
        setPortHistory(data);
      } else if (response.status === 404) {
        setError('Asset not found.');
      } else {
        const errorData = await response.json().catch(() => ({ error: 'Unknown error' }));
        setError(errorData.error || `Failed to fetch port history: ${response.status}`);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    } finally {
      setLoading(false);
    }
  };

  const formatDate = (dateString: string): string => {
    const date = new Date(dateString);
    return date.toLocaleString();
  };

  const getStateBadgeClass = (state: string): string => {
    switch (state) {
      case 'open':
        return 'bg-success';
      case 'filtered':
        return 'bg-warning';
      case 'closed':
        return 'bg-secondary';
      default:
        return 'bg-secondary';
    }
  };

  const getPortDisplay = (port: Port): string => {
    let display = `${port.portNumber}/${port.protocol}`;
    if (port.service) {
      display += ` (${port.service})`;
    }
    return display;
  };

  return (
    <div className="modal show d-block" style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
      <div className="modal-dialog modal-xl">
        <div className="modal-content">
          <div className="modal-header">
            <h5 className="modal-title">
              <i className="bi bi-diagram-3"></i> Port History: {assetName}
            </h5>
            <button type="button" className="btn-close" onClick={onClose}></button>
          </div>
          <div className="modal-body">
            {loading ? (
              <div className="d-flex justify-content-center p-5">
                <div className="spinner-border" role="status">
                  <span className="visually-hidden">Loading...</span>
                </div>
              </div>
            ) : error ? (
              <div className="alert alert-danger" role="alert">
                {error}
              </div>
            ) : !portHistory || portHistory.scans.length === 0 ? (
              <div className="alert alert-info" role="alert">
                No scan data available for this asset. Upload a scan to see port history.
              </div>
            ) : (
              <>
                <div className="mb-3">
                  <p className="text-muted mb-2">
                    <strong>Asset ID:</strong> {portHistory.assetId} |
                    <strong> Total Scans:</strong> {portHistory.scans.length}
                  </p>
                </div>

                <div className="accordion" id="portHistoryAccordion">
                  {portHistory.scans.map((scan, index) => (
                    <div className="accordion-item" key={scan.scanId}>
                      <h2 className="accordion-header" id={`heading${scan.scanId}`}>
                        <button
                          className={`accordion-button ${index !== 0 ? 'collapsed' : ''}`}
                          type="button"
                          data-bs-toggle="collapse"
                          data-bs-target={`#collapse${scan.scanId}`}
                          aria-expanded={index === 0}
                          aria-controls={`collapse${scan.scanId}`}
                        >
                          <div className="d-flex align-items-center w-100">
                            <span className="badge bg-primary me-2">{scan.scanType}</span>
                            <span className="me-3">{formatDate(scan.scanDate)}</span>
                            <span className="text-muted">
                              ({scan.ports.length} port{scan.ports.length !== 1 ? 's' : ''})
                            </span>
                            {index === 0 && (
                              <span className="badge bg-info ms-auto me-3">Latest</span>
                            )}
                          </div>
                        </button>
                      </h2>
                      <div
                        id={`collapse${scan.scanId}`}
                        className={`accordion-collapse collapse ${index === 0 ? 'show' : ''}`}
                        aria-labelledby={`heading${scan.scanId}`}
                        data-bs-parent="#portHistoryAccordion"
                      >
                        <div className="accordion-body">
                          {scan.ports.length === 0 ? (
                            <p className="text-muted">No ports detected in this scan.</p>
                          ) : (
                            <div className="table-responsive">
                              <table className="table table-sm table-hover">
                                <thead>
                                  <tr>
                                    <th>Port</th>
                                    <th>Protocol</th>
                                    <th>State</th>
                                    <th>Service</th>
                                    <th>Version</th>
                                  </tr>
                                </thead>
                                <tbody>
                                  {scan.ports
                                    .sort((a, b) => a.portNumber - b.portNumber)
                                    .map((port, portIndex) => (
                                      <tr key={portIndex}>
                                        <td>
                                          <code>{port.portNumber}</code>
                                        </td>
                                        <td>
                                          <span className="text-uppercase">{port.protocol}</span>
                                        </td>
                                        <td>
                                          <span className={`badge ${getStateBadgeClass(port.state)}`}>
                                            {port.state}
                                          </span>
                                        </td>
                                        <td>{port.service || <span className="text-muted">—</span>}</td>
                                        <td>
                                          <small>{port.version || <span className="text-muted">—</span>}</small>
                                        </td>
                                      </tr>
                                    ))}
                                </tbody>
                              </table>
                            </div>
                          )}

                          {/* Port summary */}
                          <div className="mt-2">
                            <small className="text-muted">
                              <i className="bi bi-info-circle"></i> Summary:
                              {' '}{scan.ports.filter(p => p.state === 'open').length} open,
                              {' '}{scan.ports.filter(p => p.state === 'filtered').length} filtered,
                              {' '}{scan.ports.filter(p => p.state === 'closed').length} closed
                            </small>
                          </div>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>

                {/* Timeline visualization hint */}
                <div className="mt-3">
                  <p className="text-muted small">
                    <i className="bi bi-lightbulb"></i> <strong>Tip:</strong> Scans are ordered by date (newest first).
                    Compare port states across scans to track changes over time.
                  </p>
                </div>
              </>
            )}
          </div>
          <div className="modal-footer">
            <button type="button" className="btn btn-secondary" onClick={onClose}>
              Close
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default PortHistory;
