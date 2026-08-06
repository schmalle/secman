/**
 * React component for displaying temporal vulnerability trends line chart
 *
 * Displays time-series line chart showing vulnerability counts over 30/60/90 days.
 * Features:
 * - Chart.js line chart with smooth curves
 * - Interactive time range selector (Bootstrap button group)
 * - Multiple datasets for total and severity-specific counts
 * - Tooltips showing exact dates and counts
 * - Loading, error, and empty states
 *
 * Feature: 036-vuln-stats-lense
 * Task: T052 [US4]
 * Spec reference: spec.md FR-009, FR-010, FR-011
 * User Story: US4 - View Temporal Trends (P4)
 */

import React, { useEffect, useState } from 'react';
import {
  Chart as ChartJS,
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  Title,
  Tooltip,
  Legend
} from 'chart.js';
import { Line } from 'react-chartjs-2';
import { vulnerabilityStatisticsApi, type TemporalTrendsDto } from '../../services/api/vulnerabilityStatisticsApi';

// Register Chart.js components
ChartJS.register(
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  Title,
  Tooltip,
  Legend
);

type TimeRange = 30 | 60 | 90;

export default function TemporalTrendsChart() {
  const [data, setData] = useState<TemporalTrendsDto | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedRange, setSelectedRange] = useState<TimeRange>(30);

  useEffect(() => {
    const fetchData = async () => {
      try {
        setLoading(true);
        setError(null);
        const result = await vulnerabilityStatisticsApi.getTemporalTrends(selectedRange);
        setData(result);
      } catch (err) {
        console.error('Error fetching temporal trends:', err);
        setError('Failed to load temporal trends. Please try again later.');
      } finally {
        setLoading(false);
      }
    };

    fetchData();
  }, [selectedRange]);

  const handleRangeChange = (range: TimeRange) => {
    setSelectedRange(range);
  };

  // Loading state
  if (loading) {
    return (
      <div className="card">
        <div className="card-body text-center py-5">
          <div className="spinner-border text-primary" role="status">
            <span className="visually-hidden">Loading...</span>
          </div>
          <p className="mt-3 text-muted">Loading temporal trends...</p>
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
  if (!data || !data.dataPoints || data.dataPoints.length === 0) {
    return (
      <div className="card">
        <div className="card-header d-flex justify-content-between align-items-center">
          <h5 className="mb-0">
            <i className="bi bi-graph-up me-2"></i>
            Temporal Trends
          </h5>
        </div>
        <div className="card-body text-center py-5">
          <i className="bi bi-inbox display-4 text-muted"></i>
          <p className="mt-3 text-muted">No temporal trend data available.</p>
          <small className="text-muted">
            Import vulnerability scans with scan timestamps to view trends over time.
          </small>
        </div>
      </div>
    );
  }

  // Prepare chart data
  const labels = data.dataPoints.map(dp => dp.date);
  const chartData = {
    labels,
    datasets: [
      {
        label: 'Total',
        data: data.dataPoints.map(dp => dp.totalCount),
        borderColor: '#0d6efd',
        backgroundColor: 'rgba(13, 110, 253, 0.1)',
        tension: 0.4,
        fill: true
      },
      {
        label: 'Critical',
        data: data.dataPoints.map(dp => dp.criticalCount),
        borderColor: '#dc3545',
        backgroundColor: 'rgba(220, 53, 69, 0.1)',
        tension: 0.4
      },
      {
        label: 'High',
        data: data.dataPoints.map(dp => dp.highCount),
        borderColor: '#fd7e14',
        backgroundColor: 'rgba(253, 126, 20, 0.1)',
        tension: 0.4
      }
    ]
  };

  const chartOptions = {
    responsive: true,
    maintainAspectRatio: false,
    interaction: {
      mode: 'index' as const,
      intersect: false
    },
    plugins: {
      legend: {
        position: 'top' as const
      },
      tooltip: {
        callbacks: {
          title: function(context: any) {
            return `Date: ${context[0].label}`;
          }
        }
      }
    },
    scales: {
      x: {
        display: true,
        title: {
          display: true,
          text: 'Date'
        },
        ticks: {
          maxRotation: 45,
          minRotation: 45
        }
      },
      y: {
        display: true,
        title: {
          display: true,
          text: 'Vulnerability Count'
        },
        beginAtZero: true
      }
    }
  };

  return (
    <div className="card">
      <div className="card-header d-flex justify-content-between align-items-center">
        <h5 className="mb-0">
          <i className="bi bi-graph-up me-2"></i>
          Temporal Trends
        </h5>
        <div className="btn-group btn-group-sm" role="group">
          <button
            type="button"
            className={`btn ${selectedRange === 30 ? 'btn-primary' : 'btn-outline-primary'}`}
            onClick={() => handleRangeChange(30)}
          >
            30 Days
          </button>
          <button
            type="button"
            className={`btn ${selectedRange === 60 ? 'btn-primary' : 'btn-outline-primary'}`}
            onClick={() => handleRangeChange(60)}
          >
            60 Days
          </button>
          <button
            type="button"
            className={`btn ${selectedRange === 90 ? 'btn-primary' : 'btn-outline-primary'}`}
            onClick={() => handleRangeChange(90)}
          >
            90 Days
          </button>
        </div>
      </div>
      <div className="card-body">
        <div style={{ height: '350px' }}>
          <Line data={chartData} options={chartOptions} />
        </div>
      </div>
      <div className="card-footer text-muted small">
        <i className="bi bi-info-circle me-1"></i>
        Showing vulnerability counts for the last {selectedRange} days. Select a different time range above.
      </div>
    </div>
  );
}
