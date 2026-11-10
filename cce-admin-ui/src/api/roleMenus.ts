import { apiRequest } from './client';
import type { ReplaceRoleMenusRequest, RoleMenus } from './contract';
import type { WithWarnings } from './results';

export async function getRoleMenus(id: string): Promise<RoleMenus> {
  return (await apiRequest<RoleMenus>('GET', `/roles/${encodeURIComponent(id)}/menus`)).data;
}

/** Sends the complete set; the response carries one DETAIL_WITHOUT_PARENT warning per orphan. */
export async function replaceRoleMenus(
  id: string,
  body: ReplaceRoleMenusRequest,
): Promise<WithWarnings<RoleMenus>> {
  const envelope = await apiRequest<RoleMenus>('PUT', `/roles/${encodeURIComponent(id)}/menus`, {
    body,
  });
  return { data: envelope.data, warnings: envelope.warnings ?? [] };
}
