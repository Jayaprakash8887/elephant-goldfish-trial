// The Menus tab's two-level checkbox tree (F-13). Each menu is granted on its own: checking a
// parent does not check its children, and checking a child does not check its parent.
import type { Menu } from '../../api/contract';

/** NAV menus at the top level, and DETAIL children under them, in `sortOrder`, then `label`. */
function ordered(menus: readonly Menu[]): Menu[] {
  return [...menus].sort((a, b) => a.sortOrder - b.sortOrder || a.label.localeCompare(b.label));
}

export function MenuCheckboxTree({
  tree,
  selectedIds,
  onToggle,
  disabled,
}: {
  tree: Menu[];
  selectedIds: Set<string>;
  onToggle: (id: string) => void;
  disabled?: boolean;
}) {
  return (
    <ul className="space-y-1">
      {ordered(tree).map((nav) => (
        <li key={nav.id}>
          <MenuRow menu={nav} selectedIds={selectedIds} onToggle={onToggle} disabled={disabled} />
          {nav.children.length > 0 && (
            <ul className="ml-6 space-y-1">
              {ordered(nav.children).map((child) => (
                <li key={child.id}>
                  <MenuRow
                    menu={child}
                    selectedIds={selectedIds}
                    onToggle={onToggle}
                    disabled={disabled}
                  />
                </li>
              ))}
            </ul>
          )}
        </li>
      ))}
    </ul>
  );
}

function MenuRow({
  menu,
  selectedIds,
  onToggle,
  disabled,
}: {
  menu: Menu;
  selectedIds: Set<string>;
  onToggle: (id: string) => void;
  disabled?: boolean;
}) {
  return (
    <div className="flex flex-wrap items-center gap-3 text-sm">
      <label className="flex items-center gap-2 font-medium">
        <input
          type="checkbox"
          checked={selectedIds.has(menu.id)}
          onChange={() => onToggle(menu.id)}
          disabled={disabled}
        />
        {menu.label}
      </label>
      <span className="font-mono text-xs text-gray-600">{menu.path}</span>
      <span className="text-xs text-gray-600">{menu.type}</span>
      {!menu.isActive && (
        <span className="rounded bg-gray-100 px-2 py-0.5 text-xs text-gray-700">Inactive</span>
      )}
    </div>
  );
}
