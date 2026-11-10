// Pure helpers over the `GET /menus` tree: NAV menus at the top level, DETAIL menus as their
// children (admin-service §4.4).
import type { Menu } from '../api/contract';

/** Highest sort order a menu may have (admin-service §4.4). */
const MAX_SORT_ORDER = 10000;

/** Every menu, each NAV followed by its DETAIL children, in tree order. */
export function flattenMenus(tree: readonly Menu[]): Menu[] {
  return tree.flatMap((menu) => [menu, ...menu.children]);
}

export function menuById(tree: readonly Menu[]): Map<string, Menu> {
  return new Map(flattenMenus(tree).map((menu) => [menu.id, menu]));
}

/** The NAV menus, the top level of the tree. */
export function navMenus(tree: readonly Menu[]): Menu[] {
  return tree.filter((menu) => menu.type === 'NAV');
}

/** Highest sibling sort order + 10; 10 when there are no siblings; at most 10000. */
export function nextSortOrder(siblings: readonly Menu[]): number {
  if (siblings.length === 0) return 10;
  const highest = Math.max(...siblings.map((menu) => menu.sortOrder));
  return Math.min(highest + 10, MAX_SORT_ORDER);
}

/** Every selected DETAIL menu whose parent NAV is not selected, with that parent. */
export function detailsWithoutParent(
  tree: readonly Menu[],
  selectedIds: ReadonlySet<string>,
): { menu: Menu; parent: Menu }[] {
  return tree.flatMap((parent) =>
    selectedIds.has(parent.id)
      ? []
      : parent.children
          .filter((menu) => menu.type === 'DETAIL' && selectedIds.has(menu.id))
          .map((menu) => ({ menu, parent })),
  );
}
