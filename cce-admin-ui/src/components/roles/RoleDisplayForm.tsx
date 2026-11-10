// The Role detail header's display-name and description form (`PUT /roles/:id`). It reads its
// values once, when it mounts, and resets to the role the save returns.
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import type { Role, UpdateRoleRequest } from '../../api/contract';
import { roleDisplaySchema, type RoleDisplayFormValues as Values } from '../../schemas/roleSchemas';
import type { DescribedError } from '../../utils/describeError';
import { applyServerErrors } from '../../utils/formErrors';
import { FormErrorSummary } from '../shared/FormErrorSummary';
import { InlineError } from '../shared/InlineError';
import { SuccessNotice } from '../shared/SuccessNotice';
import { Field } from './RoleCreateForm';

const FORM_FIELDS = ['displayName', 'description'] as const;
const FIELD_KINDS: ReadonlySet<string> = new Set(['validation']);

function toValues(role: Role): Values {
  return { displayName: role.displayName, description: role.description ?? '' };
}

export function RoleDisplayForm({
  role,
  onSubmit,
  pending,
  error,
}: {
  role: Role;
  onSubmit: (body: UpdateRoleRequest) => Promise<Role>;
  pending: boolean;
  error?: DescribedError;
}) {
  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors },
  } = useForm<Values>({
    resolver: zodResolver(roleDisplaySchema),
    defaultValues: toValues(role),
  });
  const [saved, setSaved] = useState(false);
  const [topMessages, setTopMessages] = useState<string[]>([]);

  useEffect(() => {
    // Only validation details belong to fields; every other kind is shown by InlineError, the
    // conflict SYSTEM_ROLE_IMMUTABLE with its server message next to Save.
    setTopMessages(
      error?.kind === 'validation' ? applyServerErrors(setError, error, FORM_FIELDS, {}) : [],
    );
  }, [error, setError]);

  async function save(values: Values) {
    setSaved(false);
    // The body is built from named fields; an empty description is sent as null.
    const body: UpdateRoleRequest = {
      displayName: values.displayName,
      description: values.description === '' ? null : values.description,
    };
    try {
      const updated = await onSubmit(body);
      reset(toValues(updated));
      setSaved(true);
    } catch {
      // Shown through the `error` prop; the form keeps the values that were sent.
    }
  }

  const disabled = role.isSystem;
  return (
    <form
      aria-label="Role details"
      noValidate
      onSubmit={handleSubmit(save)}
      className="space-y-3 rounded border border-gray-200 bg-white p-4"
    >
      {/* DESIGN-GAP: the design disables the form and both tabs' editing "with the note" but
          does not say where; it is shown once, here in the header above the tabs. */}
      {disabled && <p className="text-sm text-gray-700">System roles cannot be changed.</p>}
      <fieldset disabled={disabled} className="grid gap-3 md:grid-cols-2">
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
      </fieldset>
      <FormErrorSummary messages={topMessages} />
      {error && !FIELD_KINDS.has(error.kind) && <InlineError error={error} mutation />}
      {saved && <SuccessNotice message="Saved." />}
      <button
        type="submit"
        disabled={disabled || pending}
        className="rounded bg-blue-700 px-3 py-1 text-sm text-white hover:bg-blue-800 disabled:opacity-50"
      >
        Save
      </button>
    </form>
  );
}
