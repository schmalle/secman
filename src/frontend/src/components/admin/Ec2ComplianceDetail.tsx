/**
 * EC2 Compliance Detail Component
 *
 * Displays a vertical timeline of compliance status changes for a single asset.
 *
 * Feature: ec2-vulnerability-tracking
 */

import React, { useState, useEffect } from 'react';
import {
  getAssetComplianceHistory,
  type AssetComplianceHistoryEntry,
} from '../../services/assetComplianceApi';

interface Props {
  assetId: number;
}

const Ec2ComplianceDetail: React.FC<Props> = ({ assetId }) => {
  const [history, setHistory] = useState<AssetComplianceHistoryEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const fetchHistory = async () => {
      setLoading(true);
      setError(null);
      try {
        const data = await getAssetComplianceHistory(assetId);
        setHistory(data.history);
      } catch (err: any) {
        setError(err.message || 'Failed to load compliance history');
      } finally {
        setLoading(false);
      }
    };
    fetchHistory();
  }, [assetId]);

  const formatDate = (dateStr: string) => {
    const date = new Date(dateStr);
    return date.toLocaleDateString('en-US', {
      year: 'numeric', month: 'short', day: 'numeric',
      hour: '2-digit', minute: '2-digit',
    });
  };

  const formatDuration = (days: number) => {
    if (days === 0) return 'less than a day';
    if (days === 1) return '1 day';
    if (days < 30) return `${days} days`;
    const months = Math.floor(days / 30);
    const remainingDays = days % 30;
    if (remainingDays === 0) return `${months} month${months > 1 ? 's' : ''}`;
    return `${months} month${months > 1 ? 's' : ''}, ${remainingDays} day${remainingDays > 1 ? 's' : ''}`;
  };

  const formatSource = (source: string) => {
    return source.replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
  };

  if (loading) {
    return (
      <div className="text-center py-4">
        <div className="spinner-border text-primary" role="status">
          <span className="visually-hidden">Loading...</span>
        </div>
      </div>
    );
  }

  if (error) {
    return <div className="alert alert-danger">{error}</div>;
  }

  if (history.length === 0) {
    return (
      <div className="alert alert-info">
        No compliance history available for this asset. History tracking begins on the next vulnerability import.
      </div>
    );
  }

  // Current status is the first entry (sorted DESC)
  const currentEntry = history[0];

  return (
    <div>
      {/* Current Status Header */}
      <div className="card mb-4">
        <div className="card-body d-flex align-items-center">
          <div className="me-4">
            <span className={`badge fs-6 ${currentEntry.status === 'COMPLIANT' ? 'bg-success' : 'bg-danger'}`}>
              {currentEntry.status === 'COMPLIANT' ? 'Compliant' : 'Non-Compliant'}
            </span>
          </div>
          <div>
            <small className="text-muted">Current status since {formatDate(currentEntry.changedAt)}</small>
            {currentEntry.status === 'NON_COMPLIANT' && (
              <div className="mt-1">
                <small className="text-muted">
                  {currentEntry.overdueCount} overdue vulnerabilit{currentEntry.overdueCount === 1 ? 'y' : 'ies'}
                  {currentEntry.oldestVulnDays && ` (oldest: ${currentEntry.oldestVulnDays} days)`}
                </small>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Timeline */}
      <div className="card">
        <div className="card-header">
          <h5 className="mb-0">Status History ({history.length} transition{history.length !== 1 ? 's' : ''})</h5>
        </div>
        <div className="card-body">
          <div className="timeline">
            {history.map((entry, index) => {
              const isCompliant = entry.status === 'COMPLIANT';
              const isLatest = index === 0;

              return (
                <div key={entry.id} className="d-flex mb-4">
                  {/* Timeline indicator */}
                  <div className="d-flex flex-column align-items-center me-3" style={{ minWidth: '30px' }}>
                    <div
                      className={`rounded-circle d-flex align-items-center justify-content-center ${isCompliant ? 'bg-success' : 'bg-danger'}`}
                      style={{ width: '24px', height: '24px' }}
                    >
                      <i className={`bi ${isCompliant ? 'bi-check' : 'bi-x'} text-white`} style={{ fontSize: '14px' }}></i>
                    </div>
                    {index < history.length - 1 && (
                      <div className="border-start" style={{ height: '100%', minHeight: '40px', borderWidth: '2px !important' }}></div>
                    )}
                  </div>

                  {/* Content */}
                  <div className={`card flex-grow-1 ${isLatest ? 'border-primary' : ''}`}>
                    <div className="card-body py-2 px-3">
                      <div className="d-flex justify-content-between align-items-start">
                        <div>
                          <span className={`badge ${isCompliant ? 'bg-success' : 'bg-danger'} me-2`}>
                            {isCompliant ? 'Compliant' : 'Non-Compliant'}
                          </span>
                          {isLatest && <span className="badge bg-primary">Current</span>}
                        </div>
                        <small className="text-muted">{formatDate(entry.changedAt)}</small>
                      </div>
                      <div className="mt-2">
                        <small className="text-muted">
                          Duration: {formatDuration(entry.durationDays)}
                          {' | '}
                          Source: {formatSource(entry.source)}
                          {!isCompliant && entry.overdueCount > 0 && (
                            <>
                              {' | '}
                              Overdue: {entry.overdueCount}
                              {entry.oldestVulnDays && ` (oldest: ${entry.oldestVulnDays}d)`}
                            </>
                          )}
                        </small>
                      </div>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
};

export default Ec2ComplianceDetail;
