import { useQuery } from '@tanstack/react-query';
import { getMenuTree } from '../api/menus';
import { queryKeys } from './queryKeys';

export function useMenuTree() {
  return useQuery({ queryKey: queryKeys.menuTree(), queryFn: () => getMenuTree() });
}
