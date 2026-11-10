import { useMutation, useQuery } from '@tanstack/react-query';
import { ApiError } from '../api/ApiError';
import type { CreateMenuRequest, UpdateMenuRequest } from '../api/contract';
import { createMenu, getMenuTree, updateMenu } from '../api/menus';
import { flattenMenus } from '../utils/menuTree';
import { queryKeys } from './queryKeys';

export function useMenuTree() {
  return useQuery({ queryKey: queryKeys.menuTree(), queryFn: () => getMenuTree() });
}

export function useCreateMenu() {
  return useMutation({ mutationFn: (body: CreateMenuRequest) => createMenu(body) });
}

export function useUpdateMenu() {
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: UpdateMenuRequest }) => updateMenu(id, body),
  });
}

/**
 * Deactivate or activate: reads the tree again from the server, takes the entry's current
 * label, path, icon, parent id and sort order, and sends them with `isActive` flipped
 * (UI-AD-12), so a change made since the page loaded is not overwritten.
 */
export function useToggleMenuActive() {
  return useMutation({
    mutationFn: async (id: string) => {
      const menu = flattenMenus(await getMenuTree()).find((candidate) => candidate.id === id);
      if (!menu) {
        // DESIGN-GAP: the design does not say what happens when the entry is no longer in the
        // tree read again. It is reported as the service would report a missing menu id,
        // MENU_NOT_FOUND, with a console message, and no PUT is sent.
        throw new ApiError({
          status: 404,
          code: 'MENU_NOT_FOUND',
          message: 'This menu no longer exists.',
          hasEnvelope: true,
        });
      }
      const body: UpdateMenuRequest = {
        label: menu.label,
        path: menu.path,
        icon: menu.icon,
        parentId: menu.parentId,
        sortOrder: menu.sortOrder,
        isActive: !menu.isActive,
      };
      return updateMenu(id, body);
    },
  });
}
