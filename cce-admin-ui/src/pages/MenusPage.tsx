// The Menus catalog (F-15): the tree table, the inline MenuForm panel for add and edit, and
// Deactivate / Activate behind a ConfirmDialog. Deactivating or activating reads the tree again
// and sends the entry's current values with `isActive` flipped (useToggleMenuActive).
import { useMemo, useState } from 'react';
import type { CreateMenuRequest, Menu, UpdateMenuRequest } from '../api/contract';
import { MenuForm, type MenuFormTarget } from '../components/menus/MenuForm';
import { MenuTreeTable } from '../components/menus/MenuTreeTable';
import { ConfirmDialog } from '../components/shared/ConfirmDialog';
import { EmptyState } from '../components/shared/EmptyState';
import { InlineError } from '../components/shared/InlineError';
import { LoadingSpinner } from '../components/shared/LoadingSpinner';
import { PageHeader } from '../components/shared/PageHeader';
import { SuccessNotice } from '../components/shared/SuccessNotice';
import { useCreateMenu, useMenuTree, useToggleMenuActive, useUpdateMenu } from '../hooks/useMenus';
import { describeError, type DescribedError } from '../utils/describeError';

const SAVED = 'Menu saved.';
const DEACTIVATE_BODY =
  'Roles keep the grant, but no console shows this menu while it is inactive.';
const ACTIVATE_BODY = 'Roles that were granted this menu show it again.';

export function MenusPage() {
  const tree = useMenuTree();
  const createMenu = useCreateMenu();
  const updateMenu = useUpdateMenu();
  const toggleMenu = useToggleMenuActive();
  /** The open form and a counter that remounts it for every entry opened. */
  const [form, setForm] = useState<{ target: MenuFormTarget; key: number }>();
  const [confirming, setConfirming] = useState<Menu>();
  const [result, setResult] = useState<{ success?: string; error?: DescribedError }>();

  const formError = useMemo(() => {
    const error = form?.target.mode === 'edit' ? updateMenu.error : createMenu.error;
    return error ? describeError(error) : undefined;
  }, [form, createMenu.error, updateMenu.error]);

  function open(target: MenuFormTarget) {
    createMenu.reset();
    updateMenu.reset();
    setResult(undefined);
    setForm((current) => ({ target, key: (current?.key ?? 0) + 1 }));
  }

  function saved() {
    setForm(undefined);
    setResult({ success: SAVED });
  }

  async function create(body: CreateMenuRequest) {
    await createMenu.mutateAsync(body);
    saved();
  }

  async function update(id: string, body: UpdateMenuRequest) {
    await updateMenu.mutateAsync({ id, body });
    saved();
  }

  function confirmToggle(menu: Menu) {
    setResult(undefined);
    toggleMenu.mutate(menu.id, {
      // DESIGN-GAP: the design gives "Menu saved." as the Menus catalog's success text without
      // saying whether it also follows a deactivate or activate; it is shown for both.
      onSuccess: () => setResult({ success: SAVED }),
      onError: (error) => setResult({ error: describeError(error) }),
      onSettled: () => setConfirming(undefined),
    });
  }

  return (
    <div>
      <PageHeader
        title="Menus"
        actions={
          <button
            type="button"
            onClick={() => open({ mode: 'create', parent: null })}
            disabled={!tree.isSuccess}
            className="rounded bg-blue-700 px-3 py-1 text-sm text-white hover:bg-blue-800 disabled:opacity-50"
          >
            Add menu
          </button>
        }
      />
      {form && tree.isSuccess && (
        <MenuForm
          key={form.key}
          tree={tree.data}
          target={form.target}
          onCreate={create}
          onUpdate={update}
          onCancel={() => setForm(undefined)}
          pending={createMenu.isPending || updateMenu.isPending}
          serverError={formError}
        />
      )}
      {result && (
        <div className="mb-3">
          {result.success && <SuccessNotice message={result.success} />}
          {result.error && <InlineError error={result.error} mutation />}
        </div>
      )}
      {tree.isPending ? (
        <LoadingSpinner />
      ) : tree.isError ? (
        <InlineError error={describeError(tree.error)} onRetry={() => void tree.refetch()} />
      ) : tree.data.length === 0 ? (
        // DESIGN-GAP: the design gives no text for an empty catalog; this one is used.
        <EmptyState message="No menus yet." />
      ) : (
        <MenuTreeTable
          tree={tree.data}
          onAddDetail={(nav) => open({ mode: 'create', parent: nav })}
          onEdit={(menu) => open({ mode: 'edit', menu })}
          onToggle={setConfirming}
          pendingMenuId={toggleMenu.isPending ? toggleMenu.variables : undefined}
        />
      )}
      {confirming && (
        <ConfirmDialog
          title={`${confirming.isActive ? 'Deactivate' : 'Activate'} ${confirming.label}?`}
          body={confirming.isActive ? DEACTIVATE_BODY : ACTIVATE_BODY}
          confirmLabel={confirming.isActive ? 'Deactivate' : 'Activate'}
          pending={toggleMenu.isPending}
          onConfirm={() => confirmToggle(confirming)}
          onCancel={() => setConfirming(undefined)}
        />
      )}
    </div>
  );
}
