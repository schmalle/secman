import { csrfPost, csrfDelete } from '../utils/csrf';
import { authenticatedFetch } from '../utils/auth';

export interface UserMapping {
  id: number;
  email: string;
  awsAccountId: string | null;
  domain: string | null;
  ipAddress?: string | null;
  ipRangeType?: 'SINGLE' | 'CIDR' | 'DASH_RANGE' | null;
  ipCount?: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateMappingRequest {
  awsAccountId: string | null;
  domain: string | null;
  ipAddress?: string | null;
}

export interface UpdateMappingRequest {
  awsAccountId: string | null;
  domain: string | null;
  ipAddress?: string | null;
}

const API_BASE = 'http://localhost:8080/api';

export async function getUserMappings(userId: number): Promise<UserMapping[]> {
  const response = await authenticatedFetch(`${API_BASE}/users/${userId}/mappings`);
  if (!response.ok) {
    throw new Error('Failed to fetch mappings');
  }
  return response.json();
}

export async function createMapping(userId: number, data: CreateMappingRequest): Promise<UserMapping> {
  try {
    const response = await csrfPost(`${API_BASE}/users/${userId}/mappings`, data);
    return response.data;
  } catch (error: any) {
    if (error.response?.data?.error) {
      throw new Error(error.response.data.error);
    }
    throw new Error('Failed to create mapping');
  }
}

export async function updateMapping(
  userId: number,
  mappingId: number,
  data: UpdateMappingRequest
): Promise<UserMapping> {
  const response = await fetch(`${API_BASE}/users/${userId}/mappings/${mappingId}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${localStorage.getItem('authToken')}`
    },
    body: JSON.stringify(data)
  });
  if (!response.ok) {
    const errorData = await response.json();
    throw new Error(errorData.error || 'Failed to update mapping');
  }
  return response.json();
}

export async function deleteMapping(userId: number, mappingId: number): Promise<void> {
  try {
    await csrfDelete(`${API_BASE}/users/${userId}/mappings/${mappingId}`);
  } catch (error: any) {
    throw new Error('Failed to delete mapping');
  }
}
