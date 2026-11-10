// The role checkboxes of User create and the User edit Roles section (F-11). Only assignable
// roles get a checkbox (admin-service AD-14). The sync badge is a display hint only; the service
// decides whether a role can be assigned (AD-10). Selected ids without a checkbox (not
// assignable, or beyond the first 500 roles) are listed read-only and kept in every onChange, so
// a save never removes them silently.
import type { Role } from '../../api/contract';
import { FormErrorSummary } from '../shared/FormErrorSummary';
import { SyncStatusBadge } from '../shared/SyncStatusBadge';

export function RoleCheckboxList({
  roles,
  selectedIds,
  onChange,
  disabled,
  roleErrors,
  truncated,
}: {
  roles: Role[];
  selectedIds: string[];
  onChange: (ids: string[]) => void;
  disabled?: boolean;
  roleErrors: string[];
  truncated: boolean;
}) {
  const assignable = roles.filter((role) => role.isAssignable);
  const withCheckbox = new Set(assignable.map((role) => role.id));
  const held = selectedIds.filter((id) => !withCheckbox.has(id));

  function toggle(id: string) {
    onChange(
      selectedIds.includes(id)
        ? selectedIds.filter((selected) => selected !== id)
        : [...selectedIds, id],
    );
  }

  return (
    <fieldset disabled={disabled} className="space-y-2 text-sm">
      {/* DESIGN-GAP: the design does not name the role list; the legend "Roles" is used. */}
      <legend className="mb-1 font-medium">Roles</legend>
      {truncated && <p className="text-gray-700">Showing the first 500 roles.</p>}
      <ul className="space-y-1">
        {assignable.map((role) => (
          <li key={role.id} className="flex flex-wrap items-center gap-2">
            <label className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={selectedIds.includes(role.id)}
                onChange={() => toggle(role.id)}
              />
              {role.displayName} ({role.name})
            </label>
            {role.syncStatus !== 'SYNCED' && (
              <SyncStatusBadge syncStatus={role.syncStatus} lastSyncAt={role.lastSyncAt} />
            )}
          </li>
        ))}
      </ul>
      {held.length > 0 && (
        <div>
          <p className="font-medium">Also held:</p>
          <ul className="list-inside list-disc">
            {held.map((id) => {
              const role = roles.find((candidate) => candidate.id === id);
              // DESIGN-GAP: the design does not say how a held role beyond the first 500 roles
              // is named, since its name is not loaded; "Role {id}" is used, as for the role
              // error messages (item 48).
              return <li key={id}>{role ? `${role.displayName} (${role.name})` : `Role ${id}`}</li>;
            })}
          </ul>
        </div>
      )}
      <FormErrorSummary messages={roleErrors} />
    </fieldset>
  );
}
