// The Roles list's inline create form (F-12). The name is permanent, so the form warns before
// submit and asks for confirmation; the request is sent only after Confirm.
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useId, useState, type ReactNode } from 'react';
import { useForm } from 'react-hook-form';
import type { CreateRoleRequest } from '../../api/contract';
import { createRoleSchema, type CreateRoleForm as Values } from '../../schemas/roleSchemas';
import type { DescribedError } from '../../utils/describeError';
import { applyServerErrors } from '../../utils/formErrors';
import { ConfirmDialog } from '../shared/ConfirmDialog';
import { FormErrorSummary } from '../shared/FormErrorSummary';
import { InlineError } from '../shared/InlineError';

const NAME_WARNING =
  'The name cannot be changed later. It is also the Keycloak realm role name and the gateway permission name.';

const FORM_FIELDS = ['name', 'displayName', 'description'] as const;
const CODE_TO_FIELD = { DUPLICATE_ROLE_NAME: 'name' } as const;
const FIELD_KINDS: ReadonlySet<string> = new Set(['validation', 'conflict']);

/** The request body, built from named fields; an empty description is left out. */
function toRequest(values: Values): CreateRoleRequest {
  const body: CreateRoleRequest = { name: values.name, displayName: values.displayName };
  if (values.description !== '') body.description = values.description;
  return body;
}

// DESIGN-GAP: item 72 lists only `onSubmit` among the props. The form also needs the pending
// state for the dialog's Confirm button and the server error for DUPLICATE_ROLE_NAME on the name
// field, so `pending` and `serverError` are added as in UserCreateForm (item 69).
export function RoleCreateForm({
  onSubmit,
  pending,
  serverError,
}: {
  onSubmit: (body: CreateRoleRequest) => Promise<void>;
  pending: boolean;
  serverError?: DescribedError;
}) {
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<Values>({
    resolver: zodResolver(createRoleSchema),
    mode: 'onChange',
    defaultValues: { name: '', displayName: '', description: '' },
  });
  const [confirming, setConfirming] = useState<CreateRoleRequest>();
  const [topMessages, setTopMessages] = useState<string[]>([]);

  useEffect(() => {
    setTopMessages(
      serverError ? applyServerErrors(setError, serverError, FORM_FIELDS, CODE_TO_FIELD) : [],
    );
  }, [serverError, setError]);

  async function confirm(body: CreateRoleRequest) {
    try {
      await onSubmit(body);
    } finally {
      setConfirming(undefined);
    }
  }

  return (
    <form
      aria-label="Create role"
      noValidate
      onSubmit={handleSubmit((values) => setConfirming(toRequest(values)))}
      className="mb-6 space-y-3 rounded border border-gray-200 bg-white p-4"
    >
      <h2 className="font-semibold">Create role</h2>
      <div className="grid gap-3 md:grid-cols-3">
        <Field label="Name" error={errors.name?.message} hint={NAME_WARNING}>
          {(props) => (
            <input
              {...props}
              {...register('name')}
              className="w-full rounded border border-gray-300 px-2 py-1 font-mono"
            />
          )}
        </Field>
        <Field label="Display name" error={errors.displayName?.message}>
          {(props) => (
            <input
              {...props}
              {...register('displayName')}
              className="w-full rounded border border-gray-300 px-2 py-1"
            />
          )}
        </Field>
        <Field label="Description" error={errors.description?.message}>
          {(props) => (
            <input
              {...props}
              {...register('description')}
              className="w-full rounded border border-gray-300 px-2 py-1"
            />
          )}
        </Field>
      </div>
      <FormErrorSummary messages={topMessages} />
      {serverError && !FIELD_KINDS.has(serverError.kind) && (
        <InlineError error={serverError} mutation />
      )}
      {/* DESIGN-GAP: the design does not name the form's submit button; "Create role" is used,
          so it differs from the dialog's "Create". */}
      <button
        type="submit"
        disabled={pending}
        className="rounded bg-blue-700 px-3 py-1 text-sm text-white hover:bg-blue-800 disabled:opacity-50"
      >
        Create role
      </button>
      {confirming && (
        <ConfirmDialog
          title={`Create ${confirming.name}?`}
          body={NAME_WARNING}
          confirmLabel="Create"
          pending={pending}
          onConfirm={() => void confirm(confirming)}
          onCancel={() => setConfirming(undefined)}
        />
      )}
    </form>
  );
}

/**
 * A labelled input with its hint and its error message, linked by `aria-describedby`. Also used
 * by RoleDisplayForm.
 */
export function Field({
  label,
  error,
  hint,
  children,
}: {
  label: string;
  error?: string;
  hint?: string;
  children: (props: {
    id: string;
    'aria-invalid': true | undefined;
    'aria-describedby': string;
  }) => ReactNode;
}) {
  const id = useId();
  return (
    <div className="text-sm">
      <label htmlFor={id} className="mb-1 block font-medium">
        {label}
      </label>
      {children({
        id,
        'aria-invalid': error ? true : undefined,
        'aria-describedby': `${id}-hint ${id}-error`,
      })}
      {hint && (
        <p id={`${id}-hint`} className="mt-1 text-xs text-gray-600">
          {hint}
        </p>
      )}
      {error && (
        <p id={`${id}-error`} className="mt-1 text-xs text-red-700">
          {error}
        </p>
      )}
    </div>
  );
}
