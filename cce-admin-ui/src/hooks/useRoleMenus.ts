import { useMutation, useQuery } from '@tanstack/react-query';
import type { ReplaceRoleMenusRequest } from '../api/contract';
import { getRoleMenus, replaceRoleMenus } from '../api/roleMenus';
import { queryKeys } from './queryKeys';

export function useRoleMenus(id: string) {
  return useQuery({ queryKey: queryKeys.roleMenus(id), queryFn: () => getRoleMenus(id) });
}

export function useReplaceRoleMenus() {
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: ReplaceRoleMenusRequest }) =>
      replaceRoleMenus(id, body),
  });
}
