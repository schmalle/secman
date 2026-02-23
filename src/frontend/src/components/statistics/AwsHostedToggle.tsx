/**
 * React component for AWS Hosted toggle filter
 *
 * Bootstrap form-switch toggle that filters vulnerability statistics
 * to only show assets with a non-null cloudAccountId (AWS-hosted assets).
 * Combines with domain filter using AND logic.
 *
 * Feature: aws-hosted-filter
 */

import React from 'react';

interface AwsHostedToggleProps {
  /** Whether the AWS Hosted filter is enabled */
  awsHosted: boolean;
  /** Callback when toggle changes */
  onToggle: (enabled: boolean) => void;
}

export default function AwsHostedToggle({ awsHosted, onToggle }: AwsHostedToggleProps) {
  return (
    <div className="form-check form-switch mb-0">
      <input
        className="form-check-input"
        type="checkbox"
        role="switch"
        id="awsHostedToggle"
        checked={awsHosted}
        onChange={(e) => onToggle(e.target.checked)}
        aria-label="Filter to AWS hosted assets only"
      />
      <label className="form-check-label text-muted text-nowrap" htmlFor="awsHostedToggle">
        <i className="bi bi-cloud me-1"></i>
        AWS Hosted
      </label>
    </div>
  );
}
