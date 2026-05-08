import axios from 'axios';

const API_BASE = import.meta.env.PUBLIC_API_URL || '/api';
const ROOT = `${API_BASE}/admin/email-broadcast`;

export type EmailBroadcastStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';

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
}

export interface BroadcastRequest {
  subject: string;
  htmlContent: string;
}

export async function getRecipientCount(): Promise<number> {
  const res = await axios.get<{ count: number }>(`${ROOT}/recipients`);
  return res.data.count;
}

export async function createBroadcast(payload: BroadcastRequest): Promise<EmailBroadcastJob> {
  const res = await axios.post<EmailBroadcastJob>(ROOT, payload);
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
