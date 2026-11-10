// The User edit Roles section (F-11): the role checkboxes, zero to 20 roles. Save is enabled only
// when the selection differs from the last fetched set, and sends the complete set with
// `PUT /users/:id/roles`. Held roles without a checkbox stay in the request (RoleCheckboxList).
// It reads its values once, when it mounts; after a successful save it resets to the user the
// save returns; after a failure it keeps the selection that was sent.
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import type { ReplaceUserRolesRequest, Role, User } from '../../api/contract';
import { userRolesSchema, type UserRolesFormValues as Values } from '../../schemas/userSchemas';
import type { DescribedError } from '../../utils/describeError';
import { roleErrorMessages } from '../../utils/formErrors';
import { FormErrorSummary } from '../shared/FormErrorSummary';
import { SuccessNotice } from '../shared/SuccessNotice';
import { RoleCheckboxList } from './RoleCheckboxList';
import { UserActionError } from './UserStatusActions';

const ROLE_CODES: ReadonlySet<string> = new Set(['ROLE_NOT_SYNCED', 'ROLE_NOT_ASSIGNABLE']);

function roleIdsOf(user: User): string[] {
  return user.roles.map((role) => role.id);
}

function validationMessages(error: DescribedError): string[] {
  const details = error.details ?? [];
  return details.length > 0 ? details.map((detail) => detail.message) : [error.message];
}

function sameSet(a: readonly string[], b: readonly string[]): boolean {
  const set = new Set(a);
  return set.size === new Set(b).size && b.every((id) => set.has(id));
}

// DESIGN-GAP: as in UserCreateForm, the optional prop `rolesNotice` shows a `GET /roles` failure
// (or the loading state) in place of the checkboxes, "at the role list".
export function UserRolesForm({
  user,
  roles,
  truncated,
  onSubmit,
  pending,
  error,
  rolesNotice,
}: {
  user: User;
  roles: Role[];
  truncated: boolean;
  onSubmit: (body: ReplaceUserRolesRequest) => Promise<User>;
  pending: boolean;
  error?: DescribedError;
  rolesNotice?: ReactNode;
}) {
  // The last fetched (or saved) set; read once, when the form mounts.
  const [fetched, setFetched] = useState(() => roleIdsOf(user));
  const {
    control,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<Values>({
    resolver: zodResolver(userRolesSchema),
    defaultValues: { roleIds: fetched },
  });
  const current = useWatch({ control, name: 'roleIds' });
  const dirty = !sameSet(current, fetched);
  const sentRoleIds = useRef<string[]>([]);
  const [saved, setSaved] = useState(false);
  const [topMessages, setTopMessages] = useState<string[]>([]);
  const [roleMessages, setRoleMessages] = useState<string[]>([]);

  useEffect(() => {
    setRoleMessages(
      error && ROLE_CODES.has(error.code)
        ? roleErrorMessages(error, sentRoleIds.current, roles)
        : [],
    );
    // A validation detail (for example `roleIds[3]`) names no field of this form, so it is shown
    // at the top.
    setTopMessages(error?.kind === 'validation' ? validationMessages(error) : []);
  }, [error, roles]);

  async function save(values: Values) {
    setSaved(false);
    sentRoleIds.current = values.roleIds;
    try {
      const updated = await onSubmit({ roleIds: values.roleIds });
      const ids = roleIdsOf(updated);
      setFetched(ids);
      reset({ roleIds: ids });
      setSaved(true);
    } catch {
      // Shown through the `error` prop; the form keeps the selection that was sent.
    }
  }

  const showActionError =
    error !== undefined && error.kind !== 'validation' && !ROLE_CODES.has(error.code);
  const roleIdsError = errors.roleIds?.message ?? errors.roleIds?.root?.message;
  return (
    <form aria-label="Roles" noValidate onSubmit={handleSubmit(save)} className="space-y-3">
      {rolesNotice ?? (
        <Controller
          control={control}
          name="roleIds"
          render={({ field }) => (
            <RoleCheckboxList
              roles={roles}
              selectedIds={field.value}
              onChange={(ids) => {
                setSaved(false);
                field.onChange(ids);
              }}
              roleErrors={roleIdsError ? [roleIdsError, ...roleMessages] : roleMessages}
              truncated={truncated}
            />
          )}
        />
      )}
      <FormErrorSummary messages={topMessages} />
      {saved && <SuccessNotice message="Roles saved." />}
      <div className="space-y-2">
        <button
          type="submit"
          disabled={!dirty || pending}
          className="rounded bg-blue-700 px-3 py-1 text-sm text-white hover:bg-blue-800 disabled:opacity-50"
        >
          Save roles
        </button>
        {/* LAST_SYSTEM_ADMIN and the other action errors sit next to Save (CUJ 4). */}
        {showActionError && <UserActionError error={error} />}
      </div>
    </form>
  );
}
