// The Menus catalog tree table (F-15): NAV rows at the top level in `sortOrder`, their DETAIL
// children indented under them, with the row actions.
import type { Menu } from '../../api/contract';
import { isKnownIcon } from './iconMap';
import { MenuIcon } from './MenuIcon';

/** In `sortOrder`, then `label`, as the service orders siblings (admin-service §4.4). */
function ordered(menus: readonly Menu[]): Menu[] {
  return [...menus].sort((a, b) => a.sortOrder - b.sortOrder || a.label.localeCompare(b.label));
}

const BUTTON =
  'rounded border border-gray-300 px-2 py-0.5 hover:bg-gray-100 disabled:opacity-50 whitespace-nowrap';

export function MenuTreeTable({
  tree,
  onAddDetail,
  onEdit,
  onToggle,
  pendingMenuId,
}: {
  tree: Menu[];
  onAddDetail: (nav: Menu) => void;
  onEdit: (menu: Menu) => void;
  onToggle: (menu: Menu) => void;
  pendingMenuId?: string;
}) {
  const rows = ordered(tree).flatMap((nav) => [nav, ...ordered(nav.children)]);
  return (
    <table className="w-full border-collapse bg-white text-left text-sm">
      <thead>
        <tr className="border-b border-gray-200">
          <th className="px-3 py-2 font-medium">Label</th>
          <th className="px-3 py-2 font-medium">Key</th>
          <th className="px-3 py-2 font-medium">Path</th>
          <th className="px-3 py-2 font-medium">Icon</th>
          <th className="px-3 py-2 font-medium">Type</th>
          <th className="px-3 py-2 font-medium">Sort order</th>
          <th className="px-3 py-2 font-medium">Active</th>
          <th className="px-3 py-2 font-medium">Actions</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((menu) => {
          const isDetail = menu.type === 'DETAIL';
          return (
            <tr key={menu.id} className="border-b border-gray-100">
              <td className={`px-3 py-2 align-top ${isDetail ? 'pl-10' : 'font-medium'}`}>
                {menu.label}
              </td>
              <td className="px-3 py-2 align-top font-mono">{menu.key}</td>
              <td className="px-3 py-2 align-top font-mono">{menu.path}</td>
              <td className="px-3 py-2 align-top">
                <span className="inline-flex items-center gap-1">
                  <MenuIcon name={menu.icon} />
                  {menu.icon !== null && isKnownIcon(menu.icon) && (
                    <span className="font-mono text-xs">{menu.icon}</span>
                  )}
                </span>
              </td>
              <td className="px-3 py-2 align-top">{menu.type}</td>
              <td className="px-3 py-2 align-top">{menu.sortOrder}</td>
              <td className="px-3 py-2 align-top">
                {menu.isActive ? (
                  <span className="rounded bg-green-100 px-2 py-0.5 text-xs text-green-800">
                    Active
                  </span>
                ) : (
                  <span className="rounded bg-gray-100 px-2 py-0.5 text-xs text-gray-700">
                    Inactive
                  </span>
                )}
              </td>
              <td className="px-3 py-2 align-top">
                <span className="flex flex-wrap items-center gap-2">
                  {!isDetail && (
                    <button type="button" onClick={() => onAddDetail(menu)} className={BUTTON}>
                      Add detail
                    </button>
                  )}
                  <button type="button" onClick={() => onEdit(menu)} className={BUTTON}>
                    Edit
                  </button>
                  <button
                    type="button"
                    onClick={() => onToggle(menu)}
                    disabled={pendingMenuId === menu.id}
                    className={BUTTON}
                  >
                    {menu.isActive ? 'Deactivate' : 'Activate'}
                  </button>
                </span>
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
