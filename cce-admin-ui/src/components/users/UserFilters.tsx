// The Users list filters (F-10). A controlled component that holds no state: UsersListPage keeps
// the text drafts and debounces them, and writes every filter to the URL.
import { useId } from 'react';
import type { Role, UserStatus } from '../../api/contract';
import { FACILITY_SCOPE_MAX, USER_SEARCH_MAX, USER_STATUSES } from '../../schemas/rules';
import { STATUS_TEXT } from '../shared/StatusBadge';

export type UserFilterName = 'status' | 'roleId' | 'facilityScope' | 'q';

const INPUT = 'w-full rounded border border-gray-300 px-2 py-1';

export function UserFilters({
  values,
  roles,
  truncated,
  onChange,
}: {
  values: { status: UserStatus | ''; roleId: string; facilityScope: string; q: string };
  roles: Role[];
  truncated: boolean;
  onChange: (name: UserFilterName, value: string) => void;
}) {
  const id = useId();
  // Every assignable role, by display name (admin-service AD-14).
  const assignable = roles
    .filter((role) => role.isAssignable)
    .sort((a, b) => a.displayName.localeCompare(b.displayName));
  // DESIGN-GAP: the design does not name the filter labels; "Status", "Role", "Facility scope"
  // and "Search" are used.
  return (
    <div className="mb-4 grid gap-3 text-sm md:grid-cols-4">
      <div>
        <label htmlFor={`${id}-status`} className="mb-1 block font-medium">
          Status
        </label>
        <select
          id={`${id}-status`}
          value={values.status}
          onChange={(event) => onChange('status', event.target.value)}
          className={INPUT}
        >
          <option value="">All</option>
          {USER_STATUSES.map((status) => (
            <option key={status} value={status}>
              {STATUS_TEXT[status]}
            </option>
          ))}
        </select>
      </div>
      <div>
        <label htmlFor={`${id}-role`} className="mb-1 block font-medium">
          Role
        </label>
        <select
          id={`${id}-role`}
          value={values.roleId}
          onChange={(event) => onChange('roleId', event.target.value)}
          className={INPUT}
        >
          <option value="">All</option>
          {assignable.map((role) => (
            <option key={role.id} value={role.id}>
              {role.displayName}
            </option>
          ))}
        </select>
        {truncated && <p className="mt-1 text-xs text-gray-700">Showing the first 500 roles.</p>}
      </div>
      <div>
        <label htmlFor={`${id}-scope`} className="mb-1 block font-medium">
          Facility scope
        </label>
        <input
          id={`${id}-scope`}
          value={values.facilityScope}
          maxLength={FACILITY_SCOPE_MAX}
          onChange={(event) => onChange('facilityScope', event.target.value)}
          className={INPUT}
        />
      </div>
      <div>
        <label htmlFor={`${id}-q`} className="mb-1 block font-medium">
          Search
        </label>
        <input
          id={`${id}-q`}
          type="search"
          value={values.q}
          maxLength={USER_SEARCH_MAX}
          onChange={(event) => onChange('q', event.target.value)}
          className={INPUT}
        />
      </div>
    </div>
  );
}
