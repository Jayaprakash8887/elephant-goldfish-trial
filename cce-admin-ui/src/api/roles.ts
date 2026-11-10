import { ApiError, CLIENT_UNEXPECTED_RESPONSE } from './ApiError';
import { apiDelete, apiRequest } from './client';
import type { CreateRoleRequest, Role, UpdateRoleRequest } from './contract';
import type { Page, WithWarnings } from './results';

/** `useAllRoles` reads at most five pages of 100 roles (UI-AD-20). */
const ALL_ROLES_PAGE_SIZE = 100;
const ALL_ROLES_MAX_PAGES = 5;

export async function listRoles(params: { page: number; pageSize: number }): Promise<Page<Role>> {
  const envelope = await apiRequest<Role[]>('GET', '/roles', { query: params });
  if (!envelope.pagination) {
    // DESIGN-GAP: the design does not say what the console does when a paginated list arrives
    // without its `pagination` block (the contract always sends it, admin-service §4.3). It is
    // treated like any other 2xx without the expected envelope: CLIENT_UNEXPECTED_RESPONSE.
    throw new ApiError({
      status: 200,
      code: CLIENT_UNEXPECTED_RESPONSE,
      message: 'The admin service answered a paginated list without its pagination block.',
      hasEnvelope: false,
    });
  }
  return { items: envelope.data, pagination: envelope.pagination };
}

/**
 * Every role, assignable or not, up to five pages of 100 (UI-AD-20): pages joined in order and
 * repeated ids dropped. `truncated` is true when the service has more than five pages.
 */
export async function listAllRoles(): Promise<{ items: Role[]; truncated: boolean }> {
  const first = await listRoles({ page: 1, pageSize: ALL_ROLES_PAGE_SIZE });
  const { totalPages } = first.pagination;
  const lastPage = Math.min(totalPages, ALL_ROLES_MAX_PAGES);
  const pages = [first];
  for (let page = 2; page <= lastPage; page += 1) {
    pages.push(await listRoles({ page, pageSize: ALL_ROLES_PAGE_SIZE }));
  }
  const seen = new Set<string>();
  const items: Role[] = [];
  for (const role of pages.flatMap((p) => p.items)) {
    if (seen.has(role.id)) continue;
    seen.add(role.id);
    items.push(role);
  }
  return { items, truncated: totalPages > ALL_ROLES_MAX_PAGES };
}

export async function getRole(id: string): Promise<Role> {
  return (await apiRequest<Role>('GET', `/roles/${encodeURIComponent(id)}`)).data;
}

export async function createRole(body: CreateRoleRequest): Promise<WithWarnings<Role>> {
  const envelope = await apiRequest<Role>('POST', '/roles', { body });
  return { data: envelope.data, warnings: envelope.warnings ?? [] };
}

export async function updateRole(id: string, body: UpdateRoleRequest): Promise<Role> {
  return (await apiRequest<Role>('PUT', `/roles/${encodeURIComponent(id)}`, { body })).data;
}

export async function deleteRole(id: string): Promise<void> {
  await apiDelete(`/roles/${encodeURIComponent(id)}`);
}
