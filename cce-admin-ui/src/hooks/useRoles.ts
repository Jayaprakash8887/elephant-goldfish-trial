import { useMutation, useQuery } from '@tanstack/react-query';
import type { CreateRoleRequest, UpdateRoleRequest } from '../api/contract';
import { createRole, deleteRole, getRole, listAllRoles, listRoles, updateRole } from '../api/roles';
import { queryKeys } from './queryKeys';

export function useRoleList(params: { page: number; pageSize: number }) {
  return useQuery({ queryKey: queryKeys.roleList(params), queryFn: () => listRoles(params) });
}

/** Every role and `truncated` (UI-AD-20); callers keep only the assignable ones. */
export function useAllRoles() {
  return useQuery({ queryKey: queryKeys.allRoles(), queryFn: () => listAllRoles() });
}

export function useRole(id: string) {
  return useQuery({ queryKey: queryKeys.roleDetail(id), queryFn: () => getRole(id) });
}

export function useCreateRole() {
  return useMutation({ mutationFn: (body: CreateRoleRequest) => createRole(body) });
}

export function useUpdateRole() {
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: UpdateRoleRequest }) => updateRole(id, body),
  });
}

export function useDeleteRole() {
  return useMutation({ mutationFn: (id: string) => deleteRole(id) });
}
