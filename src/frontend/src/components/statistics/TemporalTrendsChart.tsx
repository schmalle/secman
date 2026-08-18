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

import React, { useState } from 'react';
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
import { THEME_HEX, severityHex, severityHexAlpha } from '../../utils/severityColors';
import { ChartCardEmpty, ChartCardError, ChartCardLoading } from './ChartCardStates';
import { useChartData } from './useChartData';

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
  const [selectedRange, setSelectedRange] = useState<TimeRange>(30);
  const { data, loading, error } = useChartData<TemporalTrendsDto>(
    () => vulnerabilityStatisticsApi.getTemporalTrends(selectedRange),
    [selectedRange],
    'Failed to load temporal trends. Please try again later.',
    'temporal trends',
  );

  const handleRangeChange = (range: TimeRange) => {
    setSelectedRange(range);
  };

  if (loading) return <ChartCardLoading label="Loading temporal trends..." />;
  if (error) return <ChartCardError message={error} />;
  if (!data || !data.dataPoints || data.dataPoints.length === 0) {
    return (
      <ChartCardEmpty
        heading={{ icon: 'bi-graph-up', title: 'Temporal Trends' }}
        message="No temporal trend data available."
        hint="Import vulnerability scans with scan timestamps to view trends over time."
      />
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
        borderColor: THEME_HEX.primary, // canvas can't resolve CSS var(); THEME_HEX is drift-tested
        backgroundColor: THEME_HEX.primaryLight,
        tension: 0.4,
        fill: true
      },
      {
        label: 'Critical',
        data: data.dataPoints.map(dp => dp.criticalCount),
        borderColor: severityHex('CRITICAL'),
        backgroundColor: severityHexAlpha('CRITICAL', 0.1),
        tension: 0.4
      },
      {
        label: 'High',
        data: data.dataPoints.map(dp => dp.highCount),
        borderColor: severityHex('HIGH'),
        backgroundColor: severityHexAlpha('HIGH', 0.1),
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
