// Role detail (F-13, F-14): the header with the display form, then the Menus and Permissions
// tabs. The tab lives in the URL (`?tab=menus`, the default, or `?tab=permissions`), so a tab can
// be linked and reloaded, and the unsaved-changes guard covers switching tab. No delete (F-12).
import { LockClosedIcon } from '@heroicons/react/24/outline';
import { useMemo } from 'react';
import { useParams, useSearchParams } from 'react-router';
import type { Role, UpdateRoleRequest } from '../api/contract';
import { RoleDisplayForm } from '../components/roles/RoleDisplayForm';
import { InlineError } from '../components/shared/InlineError';
import { LoadingSpinner } from '../components/shared/LoadingSpinner';
import { NotFoundPanel } from '../components/shared/NotFoundPanel';
import { SyncStatusBadge } from '../components/shared/SyncStatusBadge';
import { useRole, useUpdateRole } from '../hooks/useRoles';
import { describeError } from '../utils/describeError';
import { RoleMenusTab } from './role-detail/RoleMenusTab';
import { RolePermissionsTab } from './role-detail/RolePermissionsTab';

type Tab = 'menus' | 'permissions';

const TABS: { id: Tab; label: string }[] = [
  { id: 'menus', label: 'Menus' },
  { id: 'permissions', label: 'Permissions' },
];

export function RoleDetailPage() {
  const { id = '' } = useParams();
  const role = useRole(id);

  if (role.isPending) return <LoadingSpinner />;
  if (role.isError) {
    const error = describeError(role.error, { detailFetch: true });
    if (error.kind === 'notFound') {
      return (
        <NotFoundPanel
          message="This role does not exist."
          backTo="/roles"
          backLabel="Back to roles"
        />
      );
    }
    return <InlineError error={error} onRetry={() => void role.refetch()} />;
  }
  return <RoleDetail key={role.data.id} role={role.data} />;
}

function RoleDetail({ role }: { role: Role }) {
  const [searchParams, setSearchParams] = useSearchParams();
  const tab: Tab = searchParams.get('tab') === 'permissions' ? 'permissions' : 'menus';
  const updateRole = useUpdateRole();
  const updateError = useMemo(
    () => (updateRole.error ? describeError(updateRole.error) : undefined),
    [updateRole.error],
  );

  return (
    <div className="space-y-4">
      <div className="space-y-2">
        <h1 className="flex items-center gap-2 font-mono text-xl font-semibold">
          {role.name}
          {role.isSystem && (
            <span role="img" aria-label="System role">
              <LockClosedIcon aria-hidden="true" className="h-5 w-5" />
            </span>
          )}
        </h1>
        <div className="flex flex-wrap items-center gap-3 text-sm">
          <SyncStatusBadge syncStatus={role.syncStatus} lastSyncAt={role.lastSyncAt} />
          {/* DESIGN-GAP: the design shows "the user count" without its text; "Users: {n}". */}
          <span>Users: {role.userCount}</span>
        </div>
        <RoleDisplayForm
          role={role}
          onSubmit={(body: UpdateRoleRequest) => updateRole.mutateAsync({ id: role.id, body })}
          pending={updateRole.isPending}
          error={updateError}
        />
      </div>
      <div
        role="tablist"
        aria-label="Role sections"
        className="flex gap-2 border-b border-gray-200"
      >
        {TABS.map(({ id, label }) => (
          <button
            key={id}
            type="button"
            role="tab"
            aria-selected={tab === id}
            onClick={() => {
              if (tab !== id) void setSearchParams({ tab: id });
            }}
            className={`-mb-px border-b-2 px-3 py-1.5 text-sm ${
              tab === id ? 'border-blue-700 font-medium' : 'border-transparent'
            }`}
          >
            {label}
          </button>
        ))}
      </div>
      <div role="tabpanel" aria-label={tab === 'menus' ? 'Menus' : 'Permissions'}>
        {tab === 'menus' ? <RoleMenusTab role={role} /> : <RolePermissionsTab role={role} />}
      </div>
    </div>
  );
}
