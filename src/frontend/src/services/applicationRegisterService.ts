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
  processCluster?: string | null;
  processArea?: string | null;
  applicationChampion?: string | null;
  applicationTechnology?: string | null;
  applicationArchitecture?: string | null;
  lastQualityCheck?: string | null;
  informationClassification?: string | null;
  processingOfPersonalData?: string | null;
  icsRelevant?: string | null;
  legalRegulatory?: string | null;
  legalRegulatoryRationaleImpact?: string | null;
  dataExportControlRelevant?: string | null;
  applicationExportControlRelevant?: string | null;
  operationModel?: string | null;
  productionOperatingHours?: string | null;
  serviceOperatingHours?: string | null;
  sslCertificatesUsed?: string | null;
  allMachineUsers?: string | null;
  recoveryPlanUrl?: string | null;
  authorizationConceptUrl?: string | null;
  passwordStorageTool?: string | null;
  availabilitySupportUrl?: string | null;
  recurringTasksResponsibilitiesUrl?: string | null;
  backupRecoveryUrl?: string | null;
  monitoringEscalationUrl?: string | null;
  toolsUsedForMonitoringUrl?: string | null;
  licenseManagementUrl?: string | null;
  communicationChannelsUrl?: string | null;
  incidentAssignmentGroup?: string | null;
  solverGroupC?: string | null;
  changeApprovalGroup?: string | null;
  cabApprovalGroup?: string | null;
  changeFulfillmentGroup?: string | null;
  runAndChange?: string | null;
  managedServiceRun?: string | null;
  managedServiceChange?: string | null;
  extendedWorkbenchChange?: string | null;
  extendedWorkbenchRun?: string | null;
  managedInternally?: string | null;
  notes?: string | null;
  cmdbWorkspaceUrl?: string | null;
  createdAt?: string | null;
  createdBy?: string | null;
  updatedBy?: string | null;
  assets: ApplicationAssetSummary[];
}

export type ApplicationRegisterRequest = Omit<ApplicationRegisterDetail, 'id' | 'assets' | 'createdAt' | 'updatedAt' | 'createdBy' | 'updatedBy'>;

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

export async function createApplication(request: ApplicationRegisterRequest): Promise<ApplicationRegisterDetail> {
  return parseOrThrow(await authenticatedPost('/api/applications', request), 'Failed to create application');
}

export async function updateApplication(id: number, request: ApplicationRegisterRequest): Promise<ApplicationRegisterDetail> {
  return parseOrThrow(await authenticatedPut(`/api/applications/${id}`, request), 'Failed to update application');
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
