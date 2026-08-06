import axios from 'axios';

const API_BASE = import.meta.env.PUBLIC_API_URL || '/api';
const ROOT = `${API_BASE}/admin/email-broadcast`;

export type EmailBroadcastStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';

export type EmailBroadcastTargetGroup =
  | 'ALL_USERS'
  | 'ADMINS_ONLY'
  | 'ADMINS_AND_SECCHAMPIONS'
  | 'SELF'
  | 'PRODUCT_USERS';

export interface EmailBroadcastJob {
  id: number;
  status: EmailBroadcastStatus;
  subject: string;
  htmlContent: string;
  totalRecipients: number;
  sentCount: number;
  failedCount: number;
  errorMessage: string | null;
  createdBy: string;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  targetGroup: EmailBroadcastTargetGroup;
  targetProduct: string | null;
}

export interface BroadcastRequest {
  subject: string;
  htmlContent: string;
  targetGroup: EmailBroadcastTargetGroup;
}

export interface ProductBroadcastRequest {
  productName: string;
  subject: string;
  htmlContent: string;
}

export async function getRecipientCount(
  targetGroup: EmailBroadcastTargetGroup = 'ALL_USERS',
): Promise<number> {
  const res = await axios.get<{ count: number; targetGroup: EmailBroadcastTargetGroup }>(
    `${ROOT}/recipients`,
    { params: { targetGroup } },
  );
  return res.data.count;
}

export async function createBroadcast(payload: BroadcastRequest): Promise<EmailBroadcastJob> {
  const res = await axios.post<EmailBroadcastJob>(ROOT, payload);
  return res.data;
}

export async function getProductRecipientCount(productName: string): Promise<number> {
  const res = await axios.get<{ count: number; productName: string }>(
    `${ROOT}/product-recipients`,
    { params: { productName } },
  );
  return res.data.count;
}

export async function createProductBroadcast(payload: ProductBroadcastRequest): Promise<EmailBroadcastJob> {
  const res = await axios.post<EmailBroadcastJob>(`${ROOT}/product`, payload);
  return res.data;
}

export async function listJobs(): Promise<EmailBroadcastJob[]> {
  const res = await axios.get<EmailBroadcastJob[]>(`${ROOT}/jobs`);
  return res.data;
}

export async function getJob(id: number): Promise<EmailBroadcastJob> {
  const res = await axios.get<EmailBroadcastJob>(`${ROOT}/jobs/${id}`);
  return res.data;
}
