import { apiRequest } from './client';
import type { Me } from './contract';

export async function getMe(): Promise<Me> {
  return (await apiRequest<Me>('GET', '/me')).data;
}
