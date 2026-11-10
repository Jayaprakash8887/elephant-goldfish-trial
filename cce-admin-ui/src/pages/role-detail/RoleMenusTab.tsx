// The Role detail Menus tab (F-13): a checkbox tree of every menu; Save sends the complete set.
// The selection is read once from the fetched `menuIds` and set again from the saved set after a
// successful save; it never copies refetched data.
import { useMemo, useState } from 'react';
import type { Menu, Role, Warning } from '../../api/contract';
import { MenuCheckboxTree } from '../../components/roles/MenuCheckboxTree';
import { ConfirmDialog } from '../../components/shared/ConfirmDialog';
import { InlineError } from '../../components/shared/InlineError';
import { LoadingSpinner } from '../../components/shared/LoadingSpinner';
import { SuccessNotice } from '../../components/shared/SuccessNotice';
import { WarningList } from '../../components/shared/WarningList';
import { useMenuTree } from '../../hooks/useMenus';
import { useReplaceRoleMenus, useRoleMenus } from '../../hooks/useRoleMenus';
import { useUnsavedChangesGuard } from '../../hooks/useUnsavedChangesGuard';
import { describeError } from '../../utils/describeError';
import { detailsWithoutParent, menuById } from '../../utils/menuTree';

function warningText(detail: Pick<Menu, 'label'>, parent: Pick<Menu, 'label'>): string {
  return `${detail.label} is shown only when ${parent.label} is also granted.`;
}

/** The response's DETAIL_WITHOUT_PARENT warnings, with labels looked up in the tree. */
function serverWarningTexts(warnings: readonly Warning[], tree: readonly Menu[]): string[] {
  const byId = menuById(tree);
  return warnings.flatMap((warning) => {
    if (warning.code !== 'DETAIL_WITHOUT_PARENT') return [];
    const detail = byId.get(warning.menuId);
    const parent = byId.get(warning.parentMenuId);
    return [detail && parent ? warningText(detail, parent) : warning.message];
  });
}

function sameSet(a: ReadonlySet<string>, b: ReadonlySet<string>): boolean {
  return a.size === b.size && [...a].every((id) => b.has(id));
}

export function RoleMenusTab({ role }: { role: Role }) {
  const tree = useMenuTree();
  const roleMenus = useRoleMenus(role.id);

  if (tree.isPending || roleMenus.isPending) return <LoadingSpinner />;
  if (tree.isError || roleMenus.isError) {
    return (
      <div className="space-y-2">
        {tree.isError && (
          <InlineError error={describeError(tree.error)} onRetry={() => void tree.refetch()} />
        )}
        {roleMenus.isError && (
          <InlineError
            error={describeError(roleMenus.error)}
            onRetry={() => void roleMenus.refetch()}
          />
        )}
      </div>
    );
  }
  return <MenusEditor role={role} tree={tree.data} initialIds={roleMenus.data.menuIds} />;
}

function MenusEditor({
  role,
  tree,
  initialIds,
}: {
  role: Role;
  tree: Menu[];
  initialIds: string[];
}) {
  const replaceMenus = useReplaceRoleMenus();
  // The last fetched (or saved) set, and the administrator's selection; both read once.
  const [fetched, setFetched] = useState(() => new Set(initialIds));
  const [selected, setSelected] = useState(() => new Set(initialIds));
  const [serverWarnings, setServerWarnings] = useState<string[]>();
  const [saved, setSaved] = useState(false);

  const dirty = !sameSet(selected, fetched);
  const guard = useUnsavedChangesGuard(dirty);
  const saveError = useMemo(
    () => (replaceMenus.error ? describeError(replaceMenus.error) : undefined),
    [replaceMenus.error],
  );

  const clientWarnings = detailsWithoutParent(tree, selected).map(({ menu, parent }) =>
    warningText(menu, parent),
  );

  function toggle(id: string) {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
    setServerWarnings(undefined);
    setSaved(false);
  }

  function save() {
    setSaved(false);
    replaceMenus.mutate(
      { id: role.id, body: { menuIds: [...selected] } },
      {
        // DESIGN-GAP: item 88 says the selection is "reinitialized after a successful save" but
        // not from what; it is set from the saved set the 200 returns (`data.menuIds`), which is
        // also the new baseline for enabling Save, so no refetched data is copied into it.
        onSuccess: ({ data, warnings }) => {
          const savedIds = new Set(data.menuIds);
          setFetched(savedIds);
          setSelected(savedIds);
          setServerWarnings(serverWarningTexts(warnings, tree));
          setSaved(true);
        },
      },
    );
  }

  return (
    <div className="space-y-3">
      <MenuCheckboxTree
        tree={tree}
        selectedIds={selected}
        onToggle={toggle}
        disabled={role.isSystem}
      />
      <WarningList messages={serverWarnings ?? clientWarnings} />
      {saveError && <InlineError error={saveError} mutation />}
      {saved && <SuccessNotice message="Menus saved." />}
      <button
        type="button"
        onClick={save}
        disabled={role.isSystem || !dirty || replaceMenus.isPending}
        className="rounded bg-blue-700 px-3 py-1 text-sm text-white hover:bg-blue-800 disabled:opacity-50"
      >
        Save
      </button>
      {guard.blocked && (
        <ConfirmDialog
          title="Unsaved changes"
          body="You have unsaved changes. Leave without saving?"
          confirmLabel="Leave"
          cancelLabel="Stay"
          onConfirm={guard.proceed}
          onCancel={guard.reset}
        />
      )}
    </div>
  );
}
