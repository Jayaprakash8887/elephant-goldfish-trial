// The User edit Profile section (F-11): `PUT /users/:id` with all four fields. It reads its values
// once, when it mounts; after a successful save it resets to the user the save returns; after a
// failure it keeps the values that were sent, so the same request can be sent again (design,
// `SyncFailedNotice`, "Resubmitting"). It never copies refetched data into its fields.
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import type { UpdateUserRequest, User } from '../../api/contract';
import { profileSchema, type ProfileFormValues as Values } from '../../schemas/userSchemas';
import type { DescribedError } from '../../utils/describeError';
import { applyServerErrors } from '../../utils/formErrors';
import { FormErrorSummary } from '../shared/FormErrorSummary';
import { SuccessNotice } from '../shared/SuccessNotice';
import { Field } from '../roles/RoleCreateForm';
import { UserActionError } from './UserStatusActions';

const FORM_FIELDS = ['email', 'firstName', 'lastName', 'facilityScope'] as const;
const CODE_TO_FIELD = { EMAIL_IN_USE: 'email' } as const;
const FIELD_KINDS: ReadonlySet<string> = new Set(['validation', 'conflict']);
const INPUT = 'w-full rounded border border-gray-300 px-2 py-1';

function toValues(user: User): Values {
  return {
    email: user.email,
    firstName: user.firstName,
    lastName: user.lastName,
    facilityScope: user.facilityScope ?? '',
  };
}

export function UserProfileForm({
  user,
  onSubmit,
  pending,
  error,
}: {
  user: User;
  onSubmit: (body: UpdateUserRequest) => Promise<User>;
  pending: boolean;
  error?: DescribedError;
}) {
  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors },
  } = useForm<Values>({ resolver: zodResolver(profileSchema), defaultValues: toValues(user) });
  const [saved, setSaved] = useState(false);
  const [topMessages, setTopMessages] = useState<string[]>([]);

  useEffect(() => {
    setTopMessages(
      error && FIELD_KINDS.has(error.kind)
        ? applyServerErrors(setError, error, FORM_FIELDS, CODE_TO_FIELD)
        : [],
    );
  }, [error, setError]);

  async function save(values: Values) {
    setSaved(false);
    // The body is built from named fields; an empty facility scope is sent as null (it clears it).
    const body: UpdateUserRequest = {
      email: values.email,
      firstName: values.firstName,
      lastName: values.lastName,
      facilityScope: values.facilityScope === '' ? null : values.facilityScope,
    };
    try {
      const updated = await onSubmit(body);
      reset(toValues(updated));
      setSaved(true);
    } catch {
      // Shown through the `error` prop; the form keeps the values that were sent.
    }
  }

  return (
    <form aria-label="Profile" noValidate onSubmit={handleSubmit(save)} className="space-y-3">
      <div className="grid gap-3 md:grid-cols-2">
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
      <FormErrorSummary messages={topMessages} />
      {error && !FIELD_KINDS.has(error.kind) && <UserActionError error={error} />}
      {saved && <SuccessNotice message="Profile saved." />}
      {/* DESIGN-GAP: the design names the action "Save" for each section without exact button
          texts; "Save profile" and "Save roles" tell the two Save buttons apart. */}
      <button
        type="submit"
        disabled={pending}
        className="rounded bg-blue-700 px-3 py-1 text-sm text-white hover:bg-blue-800 disabled:opacity-50"
      >
        Save profile
      </button>
    </form>
  );
}
