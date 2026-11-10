// Every query key of the console (design, "Server state"), all created in step 2.
import type { UserListQuery } from '../api/contract';

export const queryKeys = {
  me: () => ['me'] as const,
  userList: (query: UserListQuery) => ['users', 'list', query] as const,
  userDetail: (id: string) => ['users', 'detail', id] as const,
  roleList: (params: { page: number; pageSize: number }) => ['roles', 'list', params] as const,
  allRoles: () => ['roles', 'all'] as const,
  roleDetail: (id: string) => ['roles', 'detail', id] as const,
  roleMenus: (id: string) => ['roles', id, 'menus'] as const,
  rolePermissions: (id: string) => ['roles', id, 'permissions'] as const,
  menuTree: () => ['menus', 'tree'] as const,
};
