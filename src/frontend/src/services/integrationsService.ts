import { authenticatedGet, authenticatedPost, authenticatedPut } from '../utils/auth';
import { findingQuery, type FindingFilters } from './integrationPresentation';

const ROOT = '/api/integrations/v1';
export interface IntegrationPage<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}
export interface Scanner {
  id: number;
  name: string;
  source: string;
  serviceUserId: number;
  staleAfterHours: number;
  enabled: boolean;
}
export interface IntegrationSummary {
  scanners: number;
  totalSubjects: number;
  healthySubjects: number;
  failedSubjects: number;
  unscannedSubjects: number;
  staleSubjects: number;
  openFindings: number;
}
export interface IntegrationSubject {
  id: number;
  scannerId: number;
  assetId: number;
  githubRepositoryId: number | null;
  name: string;
  uri: string | null;
  owner: string | null;
  lastStatus: string | null;
  lastScanAt: string | null;
  lastSuccessfulScanAt: string | null;
  stale: boolean;
  openFindings: number;
}
export interface IntegrationRun {
  id: number;
  scannerId: number;
  subjectId: number;
  scannerName: string;
  subjectName: string;
  status: string;
  completeCoverage: boolean;
  startedAt: string;
  completedAt: string;
  accepted: number;
  resolved: number;
  metadataJson: string;
}
export interface IntegrationFinding {
  id: number;
  scannerId: number;
  scannerName: string;
  source: string;
  subjectId: number;
  subjectName: string;
  assetId: number;
  githubRepositoryId: number | null;
  owner: string | null;
  externalId: string;
  severity: string;
  state: string;
  title: string;
  description: string | null;
  recommendation: string | null;
  evidence: string | null;
  filePath: string | null;
  lineRange: string | null;
  url: string | null;
  confidence: number | null;
  engine: string | null;
  model: string | null;
  commitSha: string | null;
  issueUrl: string | null;
  fixPrUrl: string | null;
  firstSeenAt: string;
  lastSeenAt: string;
  resolvedAt: string | null;
  vulnerabilityId: number | null;
  excepted: boolean;
  attachments: { id: number; fileName: string; contentType: string }[];
}

async function read<T>(path: string): Promise<T> {
  const response = await authenticatedGet(ROOT + path);
  if (!response.ok) throw new Error(`Could not load integration results (${response.status}).`);
  return response.json();
}

export function normalizeIntegrationPage<T>(page: Omit<IntegrationPage<T>, 'content'> & { content?: T[] }): IntegrationPage<T> {
  return { ...page, content: page.content ?? [] };
}

async function readPage<T>(path: string): Promise<IntegrationPage<T>> {
  return normalizeIntegrationPage(await read<Omit<IntegrationPage<T>, 'content'> & { content?: T[] }>(path));
}

export const getIntegrationSummary = () => read<IntegrationSummary>('/summary');
export const getScanners = () => read<Scanner[]>('/scanners');
export const getIntegrationSubjects = (scannerId: number, page = 0) =>
  readPage<IntegrationSubject>(`/scanners/${scannerId}/subjects?page=${page}&size=25`);
export const getIntegrationRuns = (scannerId?: number, page = 0) =>
  readPage<IntegrationRun>(`/runs?${findingQuery({ scannerId }, page)}`);
export const getIntegrationFindings = (filters: FindingFilters, page = 0) =>
  readPage<IntegrationFinding>(`/findings?${findingQuery(filters, page)}`);
export const getIntegrationFinding = (id: number) => read<IntegrationFinding>(`/findings/${id}`);
export interface IntegrationRunDetail {
  run: IntegrationRun;
  findings: Pick<IntegrationFinding, 'externalId' | 'severity' | 'title' | 'description' | 'recommendation' | 'evidence' | 'filePath' | 'lineRange' | 'url' | 'confidence' | 'engine' | 'model' | 'commitSha' | 'issueUrl' | 'fixPrUrl'>[];
  attachments: { id: number; findingId: number; fileName: string; contentType: string }[];
}
export const getIntegrationRun = (id: number) => read<IntegrationRunDetail>(`/runs/${id}`);

export async function saveScanner(scanner: Omit<Scanner, 'id'>, id?: number): Promise<Scanner> {
  const response = id
    ? await authenticatedPut(`${ROOT}/scanners/${id}`, scanner)
    : await authenticatedPost(`${ROOT}/scanners`, scanner);
  if (!response.ok) throw new Error(`Could not save scanner (${response.status}). Check its service user and settings.`);
  return response.json();
}

export async function bindIntegrationSubject(scannerId: number, subject: { assetId?: number; githubRepositoryId?: number }): Promise<void> {
  const response = await authenticatedPost(`${ROOT}/scanners/${scannerId}/subjects`, subject);
  if (!response.ok) throw new Error(`Could not register target (${response.status}). Check its inventory entry.`);
}

export async function downloadIntegrationAttachment(findingId: number, attachmentId: number, fileName: string): Promise<void> {
  const response = await authenticatedGet(`${ROOT}/findings/${findingId}/attachments/${attachmentId}`);
  if (!response.ok) throw new Error(`Could not download evidence (${response.status}).`);
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
