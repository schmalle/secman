/**
 * Shared severity color palette for chart libraries (Chart.js/canvas) that cannot resolve
 * CSS custom properties at paint time. These hex values are the literal equivalents of the
 * --scand-danger/-warning/-info/-muted tokens in styles/theme.css, kept in sync manually so
 * every chart, badge, and status lamp renders the same editorial severity palette instead
 * of each component picking its own Bootstrap red/orange/yellow/cyan.
 *
 * `severityColors.test.ts` parses theme.css and fails when these drift, so retuning a
 * token in CSS forces the matching edit here rather than leaving charts on old colours.
 */

export const SEVERITY_HEX = {
  critical: '#a11d3d', // --scand-danger
  high: '#9a6b18',     // --scand-warning
  medium: '#2a6f9e',   // --scand-info
  low: '#6e6e68',      // --scand-muted
  unknown: '#6E6E68',  // --scand-text-secondary
} as const;

/**
 * Non-severity tokens that charts also need as literals. Same reason as above —
 * a canvas cannot resolve var() — and same guard: severityColors.test.ts parses
 * theme.css and fails when either drifts. Two chart files previously kept private
 * copies of these with a comment claiming they matched the tokens; they silently
 * did not after a retune, which is what this export exists to prevent.
 */
export const THEME_HEX = {
  primary: '#2A6F9E',      // --scand-primary
  primaryLight: '#E7F2F9', // --scand-primary-light
  success: '#2E6B4F',      // --scand-success
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
