// The User create form (F-11). One submit sends one `POST /users`. The form never resets after a
// failure, so the same request can be submitted again (design, `SyncFailedNotice`, "Resubmitting");
// on success the page leaves it.
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Controller, useForm } from 'react-hook-form';
import type { CreateUserRequest, Role } from '../../api/contract';
import { createUserSchema, type CreateUserFormValues as Values } from '../../schemas/userSchemas';
import type { DescribedError } from '../../utils/describeError';
import { applyServerErrors, roleErrorMessages } from '../../utils/formErrors';
import { FormErrorSummary } from '../shared/FormErrorSummary';
import { InlineError } from '../shared/InlineError';
import { Field } from '../roles/RoleCreateForm';
import { RoleCheckboxList } from './RoleCheckboxList';

const FORM_FIELDS = ['username', 'email', 'firstName', 'lastName', 'facilityScope'] as const;
const CODE_TO_FIELD = { USERNAME_IN_USE: 'username', EMAIL_IN_USE: 'email' } as const;
const ROLE_CODES: ReadonlySet<string> = new Set(['ROLE_NOT_SYNCED', 'ROLE_NOT_ASSIGNABLE']);
/** Kinds shown on fields or at the top of the form; syncFailed is shown by the page. */
const NOT_INLINE_KINDS: ReadonlySet<string> = new Set(['validation', 'conflict', 'syncFailed']);
const INPUT = 'w-full rounded border border-gray-300 px-2 py-1';

// DESIGN-GAP: item 84 says the page builds the request field by field, but item 69 gives the
// form `onSubmit(body: CreateUserRequest)`, so the form builds it (as RoleCreateForm does) and
// the page sends it unchanged.
/** The request body, built from named fields; an empty facility scope is left out. */
function toRequest(values: Values): CreateUserRequest {
  const body: CreateUserRequest = {
    username: values.username,
    email: values.email,
    firstName: values.firstName,
    lastName: values.lastName,
    roleIds: values.roleIds,
  };
  if (values.facilityScope !== '') body.facilityScope = values.facilityScope;
  return body;
}

// DESIGN-GAP: item 69's props have no place for the state of the role list itself, but a
// `GET /roles` failure is shown "at the role list" (test table, UserCreatePage). The optional
// prop `rolesNotice` (a spinner or an InlineError built by the page) is shown in place of the
// checkboxes.
export function UserCreateForm({
  roles,
  truncated,
  onSubmit,
  pending,
  serverError,
  rolesNotice,
}: {
  roles: Role[];
  truncated: boolean;
  onSubmit: (body: CreateUserRequest) => Promise<void>;
  pending: boolean;
  serverError?: DescribedError;
  rolesNotice?: ReactNode;
}) {
  const {
    control,
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<Values>({
    resolver: zodResolver(createUserSchema),
    defaultValues: {
      username: '',
      email: '',
      firstName: '',
      lastName: '',
      facilityScope: '',
      roleIds: [],
    },
  });
  const sentRoleIds = useRef<string[]>([]);
  const [topMessages, setTopMessages] = useState<string[]>([]);
  const [roleMessages, setRoleMessages] = useState<string[]>([]);

  useEffect(() => {
    if (serverError && ROLE_CODES.has(serverError.code)) {
      setTopMessages([]);
      setRoleMessages(roleErrorMessages(serverError, sentRoleIds.current, roles));
      return;
    }
    setRoleMessages([]);
    setTopMessages(
      serverError ? applyServerErrors(setError, serverError, FORM_FIELDS, CODE_TO_FIELD) : [],
    );
  }, [serverError, setError, roles]);

  async function submit(values: Values) {
    const body = toRequest(values);
    sentRoleIds.current = body.roleIds;
    await onSubmit(body);
  }

  const roleIdsError = errors.roleIds?.message ?? errors.roleIds?.root?.message;
  return (
    <form
      aria-label="New user"
      noValidate
      onSubmit={handleSubmit(submit)}
      className="space-y-4 rounded border border-gray-200 bg-white p-4"
    >
      {/* DESIGN-GAP: the design does not name the user form's labels or its submit button;
          "Username", "Email", "First name", "Last name", "Facility scope" and "Create user" are
          used. */}
      <div className="grid gap-3 md:grid-cols-2">
        <Field label="Username" error={errors.username?.message}>
          {(props) => (
            <input
              {...props}
              {...register('username')}
              autoComplete="off"
              className={`${INPUT} font-mono`}
            />
          )}
        </Field>
        <Field label="Email" error={errors.email?.message}>
          {(props) => <input {...props} {...register('email')} type="email" className={INPUT} />}
        </Field>
        <Field label="First name" error={errors.firstName?.message}>
          {(props) => <input {...props} {...register('firstName')} className={INPUT} />}
        </Field>
        <Field label="Last name" error={errors.lastName?.message}>
          {(props) => <input {...props} {...register('lastName')} className={INPUT} />}
        </Field>
        <Field label="Facility scope" error={errors.facilityScope?.message}>
          {(props) => <input {...props} {...register('facilityScope')} className={INPUT} />}
        </Field>
      </div>
      {rolesNotice ?? (
        <Controller
          control={control}
          name="roleIds"
          render={({ field }) => (
            <RoleCheckboxList
              roles={roles}
              selectedIds={field.value}
              onChange={field.onChange}
              roleErrors={roleIdsError ? [roleIdsError, ...roleMessages] : roleMessages}
              truncated={truncated}
            />
          )}
        />
      )}
      <FormErrorSummary messages={topMessages} />
      {serverError && !NOT_INLINE_KINDS.has(serverError.kind) && (
        <InlineError error={serverError} mutation />
      )}
      <button
        type="submit"
        disabled={pending}
        className="rounded bg-blue-700 px-3 py-1 text-sm text-white hover:bg-blue-800 disabled:opacity-50"
      >
        Create user
      </button>
    </form>
  );
}
