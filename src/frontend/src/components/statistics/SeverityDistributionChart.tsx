/**
 * React component for displaying vulnerability severity distribution pie chart
 *
 * Displays interactive pie chart showing distribution of vulnerabilities across severity levels.
 * Features:
 * - Chart.js pie chart with color-coded severity levels
 * - Bootstrap card wrapper with responsive design
 * - Click handlers for drill-down to filtered vulnerability lists
 * - Loading, error, and empty states
 * - Color mapping: CRITICAL=#dc3545 (red), HIGH=#fd7e14 (orange), MEDIUM=#ffc107 (yellow), LOW=#0dcaf0 (cyan), UNKNOWN=#6c757d (gray)
 *
 * Feature: 036-vuln-stats-lense
 * Task: T026 [US2]
 * Spec reference: spec.md FR-003, FR-004
 * User Story: US2 - View Severity Distribution (P2)
 */

import React, { useEffect, useState } from 'react';
import { Chart as ChartJS, ArcElement, Tooltip, Legend } from 'chart.js';
import { Pie } from 'react-chartjs-2';
import { vulnerabilityStatisticsApi, type SeverityDistributionDto } from '../../services/api/vulnerabilityStatisticsApi';

// Register Chart.js components
ChartJS.register(ArcElement, Tooltip, Legend);

/**
 * Color mapping for severity levels (Bootstrap color palette)
 */
const SEVERITY_COLORS = {
  critical: '#dc3545',    // Bootstrap danger (red)
  high: '#fd7e14',        // Bootstrap warning (orange)
  medium: '#ffc107',      // Bootstrap warning (yellow)
  low: '#0dcaf0',         // Bootstrap info (cyan)
  unknown: '#6c757d'      // Bootstrap secondary (gray)
};

/**
 * Handle segment click - navigate to filtered vulnerability list
 * (Future enhancement: will link to /vulnerabilities page with severity filter)
 */
const handleSegmentClick = (severity: string) => {
  // TODO: Implement navigation to filtered vulnerability list in future story
  console.log(`Navigate to vulnerabilities filtered by severity: ${severity}`);
  // Future: window.location.href = `/vulnerabilities?severity=${severity}`;
};

export default function SeverityDistributionChart() {
  const [data, setData] = useState<SeverityDistributionDto | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const fetchData = async () => {
      try {
        setLoading(true);
        setError(null);
        const result = await vulnerabilityStatisticsApi.getSeverityDistribution();
        setData(result);
      } catch (err) {
        console.error('Error fetching severity distribution:', err);
        setError('Failed to load severity distribution. Please try again later.');
      } finally {
        setLoading(false);
      }
    };

    fetchData();
  }, []);

  // Loading state
  if (loading) {
    return (
      <div className="card">
        <div className="card-body text-center py-5">
          <div className="spinner-border text-primary" role="status">
            <span className="visually-hidden">Loading...</span>
          </div>
          <p className="mt-3 text-muted">Loading severity distribution...</p>
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
  if (!data || data.total === 0) {
    return (
      <div className="card">
        <div className="card-header">
          <h5 className="mb-0">
            <i className="bi bi-pie-chart me-2"></i>
            Severity Distribution
          </h5>
        </div>
        <div className="card-body text-center py-5">
          <i className="bi bi-inbox display-4 text-muted"></i>
          <p className="mt-3 text-muted">No vulnerability data available.</p>
          <small className="text-muted">
            Import vulnerability data to see severity distribution.
          </small>
        </div>
      </div>
    );
  }

  // Prepare chart data
  const chartData = {
    labels: ['Critical', 'High', 'Medium', 'Low', 'Unknown'],
    datasets: [
      {
        label: 'Vulnerabilities',
        data: [
          data.critical,
          data.high,
          data.medium,
          data.low,
          data.unknown
        ],
        backgroundColor: [
          SEVERITY_COLORS.critical,
          SEVERITY_COLORS.high,
          SEVERITY_COLORS.medium,
          SEVERITY_COLORS.low,
          SEVERITY_COLORS.unknown
        ],
        borderColor: '#ffffff',
        borderWidth: 2
      }
    ]
  };

  const chartOptions = {
    responsive: true,
    maintainAspectRatio: true,
    plugins: {
      legend: {
        position: 'bottom' as const,
        labels: {
          padding: 15,
          font: {
            size: 12
          }
        }
      },
      tooltip: {
        callbacks: {
          label: function(context: any) {
            const label = context.label || '';
            const value = context.parsed || 0;
            const percentage = data ?
              (context.dataIndex === 0 ? data.criticalPercentage :
               context.dataIndex === 1 ? data.highPercentage :
               context.dataIndex === 2 ? data.mediumPercentage :
               context.dataIndex === 3 ? data.lowPercentage :
               data.unknownPercentage) : 0;
            return `${label}: ${value} (${percentage.toFixed(1)}%)`;
          }
        }
      }
    },
    onClick: (_event: any, elements: any[]) => {
      if (elements.length > 0) {
        const index = elements[0].index;
        const severities = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN'];
        handleSegmentClick(severities[index]);
      }
    }
  };

  // Data table with counts and percentages
  return (
    <div className="card">
      <div className="card-header">
        <h5 className="mb-0">
          <i className="bi bi-pie-chart me-2"></i>
          Severity Distribution
        </h5>
      </div>
      <div className="card-body">
        <div className="row">
          <div className="col-12 col-md-7 mb-3 mb-md-0">
            <div style={{ maxWidth: '350px', margin: '0 auto' }}>
              <Pie data={chartData} options={chartOptions} />
            </div>
          </div>
          <div className="col-12 col-md-5">
            <table className="table table-sm">
              <thead>
                <tr>
                  <th>Severity</th>
                  <th className="text-end">Count</th>
                  <th className="text-end">%</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td><span className="badge" style={{ backgroundColor: SEVERITY_COLORS.critical }}>Critical</span></td>
                  <td className="text-end">{data.critical.toLocaleString()}</td>
                  <td className="text-end">{data.criticalPercentage.toFixed(1)}%</td>
                </tr>
                <tr>
                  <td><span className="badge" style={{ backgroundColor: SEVERITY_COLORS.high }}>High</span></td>
                  <td className="text-end">{data.high.toLocaleString()}</td>
                  <td className="text-end">{data.highPercentage.toFixed(1)}%</td>
                </tr>
                <tr>
                  <td><span className="badge" style={{ backgroundColor: SEVERITY_COLORS.medium }}>Medium</span></td>
                  <td className="text-end">{data.medium.toLocaleString()}</td>
                  <td className="text-end">{data.mediumPercentage.toFixed(1)}%</td>
                </tr>
                <tr>
                  <td><span className="badge" style={{ backgroundColor: SEVERITY_COLORS.low }}>Low</span></td>
                  <td className="text-end">{data.low.toLocaleString()}</td>
                  <td className="text-end">{data.lowPercentage.toFixed(1)}%</td>
                </tr>
                <tr>
                  <td><span className="badge" style={{ backgroundColor: SEVERITY_COLORS.unknown }}>Unknown</span></td>
                  <td className="text-end">{data.unknown.toLocaleString()}</td>
                  <td className="text-end">{data.unknownPercentage.toFixed(1)}%</td>
                </tr>
                <tr className="fw-bold">
                  <td>Total</td>
                  <td className="text-end">{data.total.toLocaleString()}</td>
                  <td className="text-end">100.0%</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
      <div className="card-footer text-muted small">
        <i className="bi bi-info-circle me-1"></i>
        Click any segment to filter vulnerabilities by severity.
      </div>
    </div>
  );
}
