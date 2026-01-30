/**
 * CveLink Component
 *
 * Renders a CVE ID as a clickable link to NVD.
 * On hover (300ms delay), shows a popover with CVE description fetched from backend.
 * Non-CVE IDs render as plain text.
 *
 * Feature: 072-cve-link-lookup
 */

import React, { useState, useRef, useCallback, useEffect } from 'react';
import { lookupCve, type CveLookupResult } from '../services/cveLookupService';

interface CveLinkProps {
  cveId: string | null | undefined;
}

const CVE_PATTERN = /^CVE-\d{4}-\d{4,}$/;

const CveLink: React.FC<CveLinkProps> = ({ cveId }) => {
  const [popoverData, setPopoverData] = useState<CveLookupResult | null>(null);
  const [popoverVisible, setPopoverVisible] = useState(false);
  const [loading, setLoading] = useState(false);
  const [popoverPosition, setPopoverPosition] = useState<'bottom' | 'top'>('bottom');
  const hoverTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const hideTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const linkRef = useRef<HTMLAnchorElement>(null);
  const popoverRef = useRef<HTMLDivElement>(null);

  if (!cveId) {
    return <span className="text-muted">-</span>;
  }

  const isCve = CVE_PATTERN.test(cveId);

  if (!isCve) {
    return <code>{cveId}</code>;
  }

  const nvdUrl = `https://nvd.nist.gov/vuln/detail/${cveId}`;

  const showPopover = useCallback(async () => {
    setPopoverVisible(true);

    // Calculate position
    if (linkRef.current) {
      const rect = linkRef.current.getBoundingClientRect();
      const spaceBelow = window.innerHeight - rect.bottom;
      setPopoverPosition(spaceBelow < 200 ? 'top' : 'bottom');
    }

    if (popoverData) return; // Already loaded

    setLoading(true);
    const result = await lookupCve(cveId);
    setPopoverData(result);
    setLoading(false);
  }, [cveId, popoverData]);

  const handleMouseEnter = useCallback(() => {
    if (hideTimerRef.current) {
      clearTimeout(hideTimerRef.current);
      hideTimerRef.current = null;
    }
    hoverTimerRef.current = setTimeout(showPopover, 300);
  }, [showPopover]);

  const handleMouseLeave = useCallback(() => {
    if (hoverTimerRef.current) {
      clearTimeout(hoverTimerRef.current);
      hoverTimerRef.current = null;
    }
    hideTimerRef.current = setTimeout(() => {
      setPopoverVisible(false);
    }, 200);
  }, []);

  const handlePopoverMouseEnter = useCallback(() => {
    if (hideTimerRef.current) {
      clearTimeout(hideTimerRef.current);
      hideTimerRef.current = null;
    }
  }, []);

  const handlePopoverMouseLeave = useCallback(() => {
    hideTimerRef.current = setTimeout(() => {
      setPopoverVisible(false);
    }, 200);
  }, []);

  // Cleanup timers on unmount
  useEffect(() => {
    return () => {
      if (hoverTimerRef.current) clearTimeout(hoverTimerRef.current);
      if (hideTimerRef.current) clearTimeout(hideTimerRef.current);
    };
  }, []);

  const severityColor = (severity: string | null): string => {
    switch (severity?.toUpperCase()) {
      case 'CRITICAL': return '#dc3545';
      case 'HIGH': return '#fd7e14';
      case 'MEDIUM': return '#ffc107';
      case 'LOW': return '#28a745';
      default: return '#6c757d';
    }
  };

  return (
    <span style={{ position: 'relative', display: 'inline-block' }}>
      <a
        ref={linkRef}
        href={nvdUrl}
        target="_blank"
        rel="noopener noreferrer"
        onMouseEnter={handleMouseEnter}
        onMouseLeave={handleMouseLeave}
        onClick={(e) => e.stopPropagation()}
        style={{
          fontFamily: 'var(--bs-font-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace)',
          fontSize: '0.875em',
          textDecoration: 'none',
          color: 'var(--bs-link-color, #0d6efd)',
          cursor: 'pointer',
        }}
        title={`Open ${cveId} on NVD`}
      >
        {cveId}
        <i className="bi bi-box-arrow-up-right ms-1" style={{ fontSize: '0.7em', opacity: 0.6 }}></i>
      </a>

      {popoverVisible && (
        <div
          ref={popoverRef}
          onMouseEnter={handlePopoverMouseEnter}
          onMouseLeave={handlePopoverMouseLeave}
          style={{
            position: 'absolute',
            left: 0,
            [popoverPosition === 'bottom' ? 'top' : 'bottom']: '100%',
            marginTop: popoverPosition === 'bottom' ? '4px' : undefined,
            marginBottom: popoverPosition === 'top' ? '4px' : undefined,
            zIndex: 1050,
            width: '360px',
            maxWidth: '90vw',
            backgroundColor: 'var(--bs-body-bg, #fff)',
            border: '1px solid var(--bs-border-color, #dee2e6)',
            borderRadius: '0.375rem',
            boxShadow: '0 0.5rem 1rem rgba(0, 0, 0, 0.15)',
            padding: '0.75rem',
            fontSize: '0.85rem',
            lineHeight: '1.4',
          }}
        >
          {loading && (
            <div className="text-center py-2">
              <div className="spinner-border spinner-border-sm text-secondary" role="status">
                <span className="visually-hidden">Loading...</span>
              </div>
              <span className="ms-2 text-muted">Loading CVE details...</span>
            </div>
          )}

          {!loading && popoverData && (
            <div>
              <div className="d-flex justify-content-between align-items-center mb-2">
                <strong>{cveId}</strong>
                {popoverData.severity && (
                  <span
                    className="badge"
                    style={{
                      backgroundColor: severityColor(popoverData.severity),
                      color: '#fff',
                      fontSize: '0.75rem',
                    }}
                  >
                    {popoverData.severity}
                    {popoverData.cvssScore != null && ` (${popoverData.cvssScore})`}
                  </span>
                )}
              </div>

              {popoverData.description ? (
                <p className="mb-1" style={{ maxHeight: '120px', overflow: 'hidden', color: 'var(--bs-body-color)' }}>
                  {popoverData.description.length > 300
                    ? popoverData.description.substring(0, 300) + '...'
                    : popoverData.description}
                </p>
              ) : (
                <p className="text-muted mb-1">No description available.</p>
              )}

              {popoverData.publishedDate && (
                <small className="text-muted">
                  Published: {new Date(popoverData.publishedDate).toLocaleDateString()}
                </small>
              )}
            </div>
          )}

          {!loading && !popoverData && (
            <div className="text-muted">
              <i className="bi bi-info-circle me-1"></i>
              CVE details unavailable. Click the link to view on NVD.
            </div>
          )}
        </div>
      )}
    </span>
  );
};

export default CveLink;
