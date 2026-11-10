// The Role detail Permissions tab (F-14). It reads `GET /roles/:id/permissions` (rows, syncStatus
// and lastSyncAt); the sync status is the server's, never set by the console. The rules editor
// reads its rows once, when it mounts, and is mounted with `resetKey`: after a 2xx save or a
// ROLE_PERMISSIONS_SAVE 502 (the rows are saved either way, admin-service §5.7) the tab refetches
// the permissions and then increments `resetKey`. Nothing else resets the editor; Re-sync keeps
// unsaved edits.
import { useCallback, useMemo, useState } from 'react';
import { useLocation } from 'react-router';
import type { PartialState, ReplaceRolePermissionsRequest, Role } from '../../api/contract';
import { PermissionsEditor } from '../../components/roles/PermissionsEditor';
import { SyncStatusPanel } from '../../components/roles/SyncStatusPanel';
import { ConfirmDialog } from '../../components/shared/ConfirmDialog';
import { InlineError } from '../../components/shared/InlineError';
import { LoadingSpinner } from '../../components/shared/LoadingSpinner';
import { SuccessNotice } from '../../components/shared/SuccessNotice';
import { SyncFailedNotice } from '../../components/shared/SyncFailedNotice';
import { WarningList } from '../../components/shared/WarningList';
import { useReplaceRolePermissions, useRolePermissions } from '../../hooks/useRolePermissions';
import { useSyncRole } from '../../hooks/useRoles';
import { useUnsavedChangesGuard } from '../../hooks/useUnsavedChangesGuard';
import { describeError, type DescribedError } from '../../utils/describeError';
import { explicitRows } from '../../utils/permissions';

const CREATED_WITH_SYNC_WARNING =
  'The role was created but not synced. Use Re-sync before assigning it.';

type Notice = { partialState: PartialState; message: string };

/** A KEYCLOAK_SYNC_FAILED with its partial state is shown by SyncFailedNotice (F-05). */
function isSyncFailure(error: DescribedError): error is DescribedError & Notice {
  return error.kind === 'syncFailed' && error.partialState !== undefined;
}

/** The navigation state the Roles list sets after a 201 with ROLE_SYNC_FAILED. */
function hasCreatedWithSyncWarning(state: unknown): boolean {
  return (
    typeof state === 'object' &&
    state !== null &&
    (state as { createdWithSyncWarning?: unknown }).createdWithSyncWarning === true
  );
}

/** The error to show inline, unless SyncFailedNotice shows it. */
function inlineError(error: unknown): DescribedError | undefined {
  if (!error) return undefined;
  const described = describeError(error);
  return isSyncFailure(described) ? undefined : described;
}

export function RolePermissionsTab({ role }: { role: Role }) {
  const location = useLocation();
  const permissions = useRolePermissions(role.id);
  const replacePermissions = useReplaceRolePermissions();
  const syncRole = useSyncRole();
  const [resetKey, setResetKey] = useState(0);
  const [resetting, setResetting] = useState(false);
  const [saved, setSaved] = useState(false);
  const [notice, setNotice] = useState<Notice>();
  const [dirty, setDirty] = useState(false);
  const guard = useUnsavedChangesGuard(dirty);
  const { refetch } = permissions;

  const rows = permissions.data?.permissions;
  const implicit = useMemo(() => (rows ?? []).filter((row) => row.implicit), [rows]);
  const explicit = useMemo(() => explicitRows(rows ?? []), [rows]);
  const saveError = useMemo(
    () => inlineError(replacePermissions.error),
    [replacePermissions.error],
  );
  const syncError = useMemo(() => inlineError(syncRole.error), [syncRole.error]);

  const onDirtyChange = useCallback((next: boolean) => {
    setDirty(next);
    if (next) setSaved(false);
  }, []);

  /** Refetches the permissions, then remounts the editor with the saved rows. */
  async function resetFromServer() {
    setResetting(true);
    // `cancelRefetch: false` joins the refetch the refetch rule has just started.
    const result = await refetch({ cancelRefetch: false });
    setResetting(false);
    // DESIGN-GAP: the design does not cover the refetch failing. The editor is then not
    // remounted, because it would show the rows from before the save; the edits it keeps are the
    // rows that were saved, and the failed read is shown with Retry.
    if (result.isSuccess) setResetKey((key) => key + 1);
  }

  async function save(body: ReplaceRolePermissionsRequest) {
    setSaved(false);
    setNotice(undefined);
    try {
      await replacePermissions.mutateAsync({ id: role.id, body });
    } catch (error) {
      const described = describeError(error);
      if (isSyncFailure(described)) {
        setNotice({ partialState: described.partialState, message: described.message });
        if (described.partialState.operation === 'ROLE_PERMISSIONS_SAVE') await resetFromServer();
      }
      return;
    }
    await resetFromServer();
    setSaved(true);
  }

  function resync() {
    setNotice(undefined);
    syncRole.mutate(role.id, {
      onError: (error) => {
        const described = describeError(error);
        if (isSyncFailure(described)) {
          setNotice({ partialState: described.partialState, message: described.message });
        }
      },
    });
  }

  if (permissions.isPending) return <LoadingSpinner />;
  if (permissions.isLoadingError) {
    return <InlineError error={describeError(permissions.error)} onRetry={() => void refetch()} />;
  }
  const { data } = permissions;
  return (
    <div className="space-y-4">
      {hasCreatedWithSyncWarning(location.state) && (
        <WarningList messages={[CREATED_WITH_SYNC_WARNING]} />
      )}
      <SyncStatusPanel
        syncStatus={data.syncStatus}
        lastSyncAt={data.lastSyncAt}
        onResync={resync}
        pending={syncRole.isPending}
      />
      {syncError && <InlineError error={syncError} mutation />}
      {permissions.isRefetchError && (
        <InlineError error={describeError(permissions.error)} onRetry={() => void refetch()} />
      )}
      {notice && (
        <SyncFailedNotice
          partialState={notice.partialState}
          message={notice.message}
          onResync={resync}
          resyncPending={syncRole.isPending}
        />
      )}
      <PermissionsEditor
        key={resetKey}
        implicitRows={implicit}
        initialRows={explicit}
        disabled={role.isSystem}
        onSubmit={save}
        pending={replacePermissions.isPending || resetting}
        serverError={saveError}
        onDirtyChange={onDirtyChange}
      />
      {saved && <SuccessNotice message="Saved." />}
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
