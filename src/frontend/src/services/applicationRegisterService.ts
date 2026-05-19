import { authenticatedDelete, authenticatedGet, authenticatedPost, authenticatedPut } from '../utils/auth';

export interface ApplicationAssetSummary {
  id: number;
  name: string;
  type: string;
  owner: string;
  ip?: string | null;
}

export interface ApplicationRegisterSummary {
  id: number;
  carId: string;
  name: string;
  criticality?: string | null;
  operationalStatus?: string | null;
  businessOwner: string;
  applicationManager: string;
  updatedAt?: string | null;
}

export interface ApplicationRegisterDetail extends ApplicationRegisterSummary {
  applicationTechnology?: string | null;
  applicationArchitecture?: string | null;
  lastQualityCheck?: string | null;
  informationClassification?: string | null;
  processingOfPersonalData?: string | null;
  icsRelevant?: string | null;
  applicationExportControlRelevant?: string | null;
  operationModel?: string | null;
  productionOperatingHours?: string | null;
  serviceOperatingHours?: string | null;
  backupRecoveryUrl?: string | null;
  incidentAssignmentGroup?: string | null;
  notes?: string | null;
  cmdbWorkspaceUrl?: string | null;
  createdAt?: string | null;
  createdBy?: string | null;
  updatedBy?: string | null;
  assets: ApplicationAssetSummary[];
}

export type ApplicationRegisterRequest = Omit<ApplicationRegisterDetail, 'id' | 'assets' | 'createdAt' | 'updatedAt' | 'createdBy' | 'updatedBy'>;
export type ApplicationRegisterSaveRequest = Omit<ApplicationRegisterRequest, 'carId'> & { carId?: string | null };

const optionalApplicationFields: Array<keyof ApplicationRegisterSaveRequest> = [
  'criticality',
  'operationalStatus',
  'applicationTechnology',
  'applicationArchitecture',
  'lastQualityCheck',
  'informationClassification',
  'processingOfPersonalData',
  'icsRelevant',
  'applicationExportControlRelevant',
  'operationModel',
  'productionOperatingHours',
  'serviceOperatingHours',
  'backupRecoveryUrl',
  'incidentAssignmentGroup',
  'notes',
  'cmdbWorkspaceUrl',
];

function sanitizeRequest(request: ApplicationRegisterSaveRequest): ApplicationRegisterSaveRequest {
  const sanitized = { ...request };
  optionalApplicationFields.forEach((field) => {
    if (sanitized[field] === '') {
      sanitized[field] = undefined as never;
    }
  });
  return sanitized;
}

async function parseOrThrow<T>(response: Response, fallback: string): Promise<T> {
  if (response.ok) {
    return await response.json();
  }
  const body = await response.json().catch(() => ({}));
  throw new Error(body.error || body.message || `${fallback}: ${response.status}`);
}

export async function listApplications(search = ''): Promise<ApplicationRegisterSummary[]> {
  const params = new URLSearchParams();
  if (search.trim()) params.set('search', search.trim());
  const suffix = params.toString() ? `?${params.toString()}` : '';
  return parseOrThrow(await authenticatedGet(`/api/applications${suffix}`), 'Failed to load applications');
}

export async function getApplication(id: number): Promise<ApplicationRegisterDetail> {
  return parseOrThrow(await authenticatedGet(`/api/applications/${id}`), 'Failed to load application');
}

export async function createApplication(request: ApplicationRegisterSaveRequest): Promise<ApplicationRegisterDetail> {
  return parseOrThrow(await authenticatedPost('/api/applications', sanitizeRequest(request)), 'Failed to create application');
}

export async function updateApplication(id: number, request: ApplicationRegisterSaveRequest): Promise<ApplicationRegisterDetail> {
  return parseOrThrow(await authenticatedPut(`/api/applications/${id}`, sanitizeRequest(request)), 'Failed to update application');
}

export async function deleteApplication(id: number): Promise<void> {
  const response = await authenticatedDelete(`/api/applications/${id}`);
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.error || body.message || `Failed to delete application: ${response.status}`);
  }
}

export async function replaceApplicationAssets(id: number, assetIds: number[]): Promise<ApplicationRegisterDetail> {
  return parseOrThrow(
    await authenticatedPut(`/api/applications/${id}/assets`, { assetIds }),
    'Failed to update linked assets'
  );
}
