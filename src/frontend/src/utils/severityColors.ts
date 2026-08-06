/**
 * Shared severity color palette for chart libraries (Chart.js/canvas) that cannot resolve
 * CSS custom properties at paint time. These hex values are the literal equivalents of the
 * --scand-danger/-warning/-info/-muted tokens in styles/theme.css, kept in sync manually so
 * every chart, badge, and status lamp renders the same Scandinavian severity palette instead
 * of each component picking its own Bootstrap red/orange/yellow/cyan.
 */

export const SEVERITY_HEX = {
  critical: '#9B6B6B', // --scand-danger
  high: '#8B8B5E',     // --scand-warning
  medium: '#5E8B8B',   // --scand-info
  low: '#7B8B8B',      // --scand-muted
  unknown: '#636E72',  // --scand-text-secondary
} as const;

/** Same palette, keyed by uppercase severity string as returned by the backend. */
export function severityHex(severity: string | null | undefined): string {
  switch (severity?.toUpperCase()) {
    case 'CRITICAL': return SEVERITY_HEX.critical;
    case 'HIGH': return SEVERITY_HEX.high;
    case 'MEDIUM': return SEVERITY_HEX.medium;
    case 'LOW': return SEVERITY_HEX.low;
    default: return SEVERITY_HEX.unknown;
  }
}

/** severityHex() colour with an alpha channel, for chart fill/backgroundColor use. */
export function severityHexAlpha(severity: string | null | undefined, alpha: number): string {
  return hexToRgba(severityHex(severity), alpha);
}

export function hexToRgba(hex: string, alpha: number): string {
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}
