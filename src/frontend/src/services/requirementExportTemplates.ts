/**
 * Client-side logic for the requirement export template admin surface.
 *
 * Kept in a plain `.ts` module rather than inside the `.tsx` component so the frontend unit tier
 * can exercise it; see docs/TESTING.md §Frontend.
 *
 * None of this is a security control. The backend re-validates every uploaded template through
 * `RequirementExportTemplateValidationService` and enforces ADMIN/REQADMIN at the controller — the
 * checks here exist to give immediate feedback in the form instead of an opaque 400.
 */

export type RequirementExportTemplateStatus = 'ACTIVE' | 'INACTIVE' | 'RETIRED' | 'REJECTED';

export interface RequirementExportTemplateSummary {
  id: number;
  name: string;
  description: string | null;
  versionLabel: string | null;
  status: RequirementExportTemplateStatus;
  originalFilename: string;
  fileSizeBytes: number;
  sha256: string;
  uploadedBy: string;
  createdAt: string;
  activatedAt: string | null;
  deactivatedAt: string | null;
  lastUsedAt: string | null;
  usageCount?: number | null;
}

export interface ValidationReport {
  valid: boolean;
  errors?: string[];
  warnings?: string[];
  placeholders?: string[];
  sha256: string;
  fileSizeBytes: number;
  uncompressedSizeBytes?: number;
  entryCount?: number;
}

export const TEMPLATES_ENDPOINT = '/api/requirement-export-templates';

/** Mirrors `secman.requirement-export-templates.max-file-size-bytes`. */
export const MAX_TEMPLATE_BYTES = 5 * 1024 * 1024;

/**
 * Every placeholder the backend substitutes, with what it renders. Mirrors
 * `RequirementExportTemplateValidationService.ALLOWED_PLACEHOLDERS`; anything else in a template is
 * left untouched rather than blanked, which is why unknown names are a warning and not an error.
 */
export const SUPPORTED_PLACEHOLDERS: ReadonlyArray<{ name: string; description: string }> = [
  { name: 'requirements', description: 'Insertion point — requirement content is rendered here' },
  { name: 'documentTitle', description: 'Title of the exported document' },
  { name: 'exportDate', description: 'Timestamp the export was generated' },
  { name: 'releaseName', description: 'Name of the exported release' },
  { name: 'releaseVersion', description: 'Version of the exported release' },
  { name: 'releaseDate', description: 'Date of the exported release' },
  { name: 'releaseStatus', description: 'PREPARATION, ALIGNMENT, ACTIVE or ARCHIVED' },
  { name: 'releaseDescription', description: 'Description of the exported release' },
  { name: 'useCaseName', description: 'Use case the export was narrowed to' },
  { name: 'exportedBy', description: 'Username that triggered the export' },
  { name: 'language', description: 'Export language' },
  { name: 'requirementCount', description: 'Number of requirements in the document' },
  { name: 'classification', description: 'Classification label chosen at export time' },
];

/**
 * @returns null when the file is plausibly a Word template, otherwise a message to show
 *   under the field. The backend applies the authoritative version of these checks.
 */
export function validateTemplateFile(file: File | null): string | null {
  if (!file) {
    return 'Choose a .docx template file.';
  }
  if (!file.name.toLowerCase().endsWith('.docx')) {
    // .docm / .dotm are rejected outright by the backend: macros in an export template would
    // execute on every reader's machine.
    return 'Only .docx Word templates are supported (macro-enabled templates are rejected).';
  }
  if (file.size === 0) {
    return 'The selected file is empty.';
  }
  if (file.size > MAX_TEMPLATE_BYTES) {
    return `Template exceeds the ${formatFileSize(MAX_TEMPLATE_BYTES)} limit.`;
  }
  return null;
}

/** Human-readable byte count for the template list. */
export function formatFileSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return '-';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** Short sha256 for display; the full digest stays available as a title attribute. */
export function shortSha256(sha256: string | null | undefined): string {
  if (!sha256) return '-';
  return sha256.slice(0, 12);
}

/**
 * Flattens a validation report into lines for display.
 *
 * A rejected upload returns the report as the 400 body, so the same shape has to render for both
 * the "validate" preview and the failure path.
 */
export function describeValidationReport(report: ValidationReport | null | undefined): {
  valid: boolean;
  errors: string[];
  warnings: string[];
  placeholders: string[];
} {
  if (!report) {
    return { valid: false, errors: ['No validation report was returned.'], warnings: [], placeholders: [] };
  }
  return {
    valid: Boolean(report.valid),
    errors: report.errors ?? [],
    warnings: report.warnings ?? [],
    placeholders: report.placeholders ?? [],
  };
}

/** Placeholders present in a template that the backend does not substitute. */
export function unsupportedPlaceholders(placeholders: string[]): string[] {
  const supported = new Set(SUPPORTED_PLACEHOLDERS.map((p) => p.name));
  return placeholders.filter((p) => !supported.has(p)).sort();
}

/**
 * Whether a template can be used for an export. RETIRED and REJECTED are terminal — a retired
 * template is kept only so its usage history keeps resolving.
 */
export function isUsable(status: RequirementExportTemplateStatus): boolean {
  return status === 'ACTIVE';
}

/** Bootstrap badge class per status, so the list reads at a glance. */
export function statusBadgeClass(status: RequirementExportTemplateStatus): string {
  switch (status) {
    case 'ACTIVE':
      return 'badge bg-success';
    case 'INACTIVE':
      return 'badge bg-secondary';
    case 'RETIRED':
      return 'badge bg-warning text-dark';
    case 'REJECTED':
      return 'badge bg-danger';
    default:
      return 'badge bg-secondary';
  }
}

/** Multipart body for an upload, matching the `@Part` names on the controller. */
export function buildUploadFormData(input: {
  file: File;
  name: string;
  description: string;
  versionLabel: string;
  activate: boolean;
  requireRequirementsPlaceholder: boolean;
}): FormData {
  const formData = new FormData();
  formData.append('templateFile', input.file);
  formData.append('name', input.name.trim() || input.file.name.replace(/\.docx$/i, ''));
  formData.append('description', input.description);
  formData.append('versionLabel', input.versionLabel);
  formData.append('activate', String(input.activate));
  formData.append('requireRequirementsPlaceholder', String(input.requireRequirementsPlaceholder));
  return formData;
}

/** Sort order for the list: active templates first, newest first within a status. */
export function sortTemplates(
  templates: RequirementExportTemplateSummary[],
): RequirementExportTemplateSummary[] {
  return [...templates].sort((a, b) => {
    if (a.status !== b.status) {
      if (a.status === 'ACTIVE') return -1;
      if (b.status === 'ACTIVE') return 1;
    }
    return (b.createdAt ?? '').localeCompare(a.createdAt ?? '');
  });
}
