import type { ExceptionSubject, ExceptionKind } from './vulnerabilityManagementService';

/**
 * Renders WHAT an exception request excepts.
 *
 * Feature 196 replaced the one-request-one-vulnerability model with the two-axis
 * subject x scope vocabulary, and with it the identity of a request moved out of the
 * `vulnerability` association. `vulnerabilityCve` is null BY DESIGN for a PRODUCT or
 * ALL_VULNS request and for any CVE request naming more than one CVE — the backend
 * only denormalizes `cveId` when the request resolves to exactly one. Reading
 * `vulnerabilityCve` alone therefore reports "no CVE" for rows that are perfectly
 * well identified by `subjectValue`.
 *
 * Callers render `label` as the field caption, so a product request is not captioned
 * "CVE ID". `exceptionRequestScopeFormatter` is the WHERE half of the same pair.
 */

export interface ExceptionRequestSubjectTarget {
  subject: ExceptionSubject;
  subjectValue?: string | null;
  vulnerabilityCve?: string | null;
  /** Absent is treated as 'VULNERABILITY' — the meaning every row had before this axis existed. */
  kind?: ExceptionKind | null;
}

export interface ExceptionRequestSubjectDisplay {
  /** Field caption: 'CVE ID', 'CVE IDs', 'Product' or 'Subject'. */
  label: string;
  value: string;
  /** True when `value` is a machine identifier and should render monospaced. */
  isCode: boolean;
  /** True when `value` states an absence rather than an identity. */
  muted: boolean;
  title: string;
}

function splitCves(subjectValue: string | null | undefined): string[] {
  return (subjectValue ?? '')
    .split(',')
    .map(cve => cve.trim())
    .filter(cve => cve.length > 0);
}

export function formatExceptionRequestSubject(
  target: ExceptionRequestSubjectTarget
): ExceptionRequestSubjectDisplay {
  // A NO_EDR row is stored as ALL_VULNS x ASSET, so the subject switch below would
  // announce "All vulnerabilities" — a request to waive every finding on the box, which
  // is not what was asked for. Short-circuit, exactly as the scope formatter does.
  if (target.kind === 'NO_EDR') {
    return {
      label: 'Subject',
      value: 'No EDR agent possible',
      isCode: false,
      muted: false,
      title: 'This asset cannot run an EDR agent; no findings are suppressed'
    };
  }

  switch (target.subject) {
    case 'ALL_VULNS':
      return {
        label: 'Subject',
        value: 'All vulnerabilities',
        isCode: false,
        muted: false,
        title: 'Every finding within the scope below'
      };

    case 'PRODUCT': {
      const product = (target.subjectValue ?? '').trim();
      return product
        ? { label: 'Product', value: product, isCode: true, muted: false, title: `Findings for product ${product}` }
        : { label: 'Product', value: 'Any product', isCode: false, muted: true, title: 'No product pattern was recorded' };
    }

    case 'CVE': {
      const cves = splitCves(target.subjectValue);

      if (cves.length > 1) {
        return {
          label: 'CVE IDs',
          value: cves.join(', '),
          isCode: true,
          muted: false,
          title: `${cves.length} CVEs`
        };
      }

      const cve = cves[0] ?? target.vulnerabilityCve?.trim();
      return cve
        ? { label: 'CVE ID', value: cve, isCode: true, muted: false, title: cve }
        : {
            label: 'CVE ID',
            value: 'Remediated',
            isCode: false,
            muted: true,
            title: 'Vulnerability has been remediated or removed'
          };
    }
  }
}
