import { apiRequest } from './client';
import type { ReplaceRolePermissionsRequest, RolePermissions } from './contract';

/** The role's rows (implicit rows first), `syncStatus` and `lastSyncAt` (admin-service §4.5). */
export async function getRolePermissions(id: string): Promise<RolePermissions> {
  return (await apiRequest<RolePermissions>('GET', `/roles/${encodeURIComponent(id)}/permissions`))
    .data;
}

/**
 * Sends the complete set of explicit rows. A 200 comes only after the gateway sync succeeded; a
 * 502 `ROLE_PERMISSIONS_SAVE` means the rows are saved but not synced (admin-service §5.7).
 */
export async function replaceRolePermissions(
  id: string,
  body: ReplaceRolePermissionsRequest,
): Promise<RolePermissions> {
  return (
    await apiRequest<RolePermissions>('PUT', `/roles/${encodeURIComponent(id)}/permissions`, {
      body,
    })
  ).data;
}
