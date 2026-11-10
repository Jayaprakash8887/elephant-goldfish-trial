import { apiRequest } from './client';
import type { Menu } from './contract';

/** The whole tree, inactive menus included (admin-service §4.5). No delete (UI-AD-07). */
export async function getMenuTree(): Promise<Menu[]> {
  return (await apiRequest<Menu[]>('GET', '/menus')).data;
}
