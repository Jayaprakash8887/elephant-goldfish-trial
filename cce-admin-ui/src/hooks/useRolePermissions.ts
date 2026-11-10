import { useMutation, useQuery } from '@tanstack/react-query';
import type { ReplaceRolePermissionsRequest } from '../api/contract';
import { getRolePermissions, replaceRolePermissions } from '../api/rolePermissions';
import { queryKeys } from './queryKeys';

export function useRolePermissions(id: string) {
  return useQuery({
    queryKey: queryKeys.rolePermissions(id),
    queryFn: () => getRolePermissions(id),
  });
}

export function useReplaceRolePermissions() {
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: ReplaceRolePermissionsRequest }) =>
      replaceRolePermissions(id, body),
  });
}
