import { apiRequest } from './client';
import type { CreateMenuRequest, Menu, UpdateMenuRequest } from './contract';

/** The whole tree, inactive menus included (admin-service §4.5). No delete (UI-AD-07). */
export async function getMenuTree(): Promise<Menu[]> {
  return (await apiRequest<Menu[]>('GET', '/menus')).data;
}

export async function createMenu(body: CreateMenuRequest): Promise<Menu> {
  return (await apiRequest<Menu>('POST', '/menus', { body })).data;
}

/** `key` and `type` cannot change, so they are not in the body (admin-service §4.5). */
export async function updateMenu(id: string, body: UpdateMenuRequest): Promise<Menu> {
  return (await apiRequest<Menu>('PUT', `/menus/${encodeURIComponent(id)}`, { body })).data;
}
