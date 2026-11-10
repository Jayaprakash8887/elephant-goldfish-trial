// The Permissions tab's rules (F-14): the role's implicit rows read-only, and its explicit rows in
// an editable field array. It reads `initialRows` once, when it mounts; the tab remounts it with a
// new `key` to reset it. Save sends every explicit row, and never the implicit ones (AD-16).
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useId, useMemo, useState, type ReactNode } from 'react';
import { useFieldArray, useForm, useWatch } from 'react-hook-form';
import type { PermissionRow, ReplaceRolePermissionsRequest } from '../../api/contract';
import {
  permissionsFormSchema,
  type PermissionFormRow,
  type PermissionsFormValues as Values,
} from '../../schemas/permissionSchemas';
import { PERMISSION_METHODS } from '../../schemas/rules';
import type { DescribedError } from '../../utils/describeError';
import { applyServerErrors } from '../../utils/formErrors';
import { sameRuleSet, toRequestRows } from '../../utils/permissions';
import { FormErrorSummary } from '../shared/FormErrorSummary';
import { InlineError } from '../shared/InlineError';

const FORM_FIELDS = ['permissions'] as const;

/** New rows start with GET and empty fields. */
const NEW_ROW: PermissionFormRow = {
  httpMethod: 'GET',
  uriPattern: '',
  description: '',
  resourceCategory: '',
};

function toFormRow(row: PermissionRow): PermissionFormRow {
  return {
    httpMethod: row.httpMethod,
    uriPattern: row.uriPattern,
    description: row.description ?? '',
    resourceCategory: row.resourceCategory ?? '',
  };
}

const INPUT = 'w-full rounded border border-gray-300 px-2 py-1';

// DESIGN-GAP: item 75 lists the props without a way for the tab to learn whether the editor has
// unsaved edits, which the tab needs for `useUnsavedChangesGuard` (item 89); components may not
// import hooks/, so the editor reports it through the added prop `onDirtyChange`.
export function PermissionsEditor({
  implicitRows,
  initialRows,
  disabled,
  onSubmit,
  pending,
  serverError,
  onDirtyChange,
}: {
  implicitRows: PermissionRow[];
  initialRows: PermissionRow[];
  disabled?: boolean;
  onSubmit: (body: ReplaceRolePermissionsRequest) => Promise<void>;
  pending: boolean;
  serverError?: DescribedError;
  onDirtyChange: (dirty: boolean) => void;
}) {
  // Both read once, when the editor mounts.
  const [fetched] = useState(initialRows);
  const [defaultValues] = useState<Values>(() => ({ permissions: initialRows.map(toFormRow) }));
  const schema = useMemo(() => permissionsFormSchema(implicitRows), [implicitRows]);
  const {
    control,
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<Values>({ resolver: zodResolver(schema), defaultValues });
  const { fields, append, remove } = useFieldArray({ control, name: 'permissions' });
  const current = useWatch({ control, name: 'permissions' });
  const dirty = !sameRuleSet(current, fetched);
  const [topMessages, setTopMessages] = useState<string[]>([]);
  const rulesTitleId = useId();

  useEffect(() => onDirtyChange(dirty), [dirty, onDirtyChange]);

  useEffect(() => {
    // Validation details go to their rows (`permissions[2].uriPattern`); every other kind is
    // shown by InlineError next to Save.
    setTopMessages(
      serverError?.kind === 'validation'
        ? applyServerErrors(setError, serverError, FORM_FIELDS, {})
        : [],
    );
  }, [serverError, setError]);

  async function save(values: Values) {
    try {
      await onSubmit({ permissions: toRequestRows(values.permissions) });
    } catch {
      // Shown through `serverError`; the form keeps the rows that were sent.
    }
  }

  const listError = errors.permissions?.root?.message ?? errors.permissions?.message;
  return (
    <form
      aria-label="Permission rules"
      noValidate
      onSubmit={handleSubmit(save)}
      className="space-y-4"
    >
      {/* DESIGN-GAP: the design shows the implicit rows "with the note" for every non-system
          role; a system role has no implicit rows (admin-service §4.4), so the section is shown
          only when there are some. The section and column titles are not given; "Always
          included", "Rules", "Method", "URI pattern", "Description" and "Category" are used. */}
      {implicitRows.length > 0 && <ImplicitRows rows={implicitRows} />}
      <section aria-labelledby={rulesTitleId} className="space-y-2">
        <h3 id={rulesTitleId} className="font-semibold">
          Rules
        </h3>
        <fieldset disabled={disabled} className="space-y-2">
          <table aria-labelledby={rulesTitleId} className="w-full text-left text-sm">
            <thead>
              <tr className="border-b border-gray-200">
                <th className="py-1 pr-2 font-medium">Method</th>
                <th className="py-1 pr-2 font-medium">URI pattern</th>
                <th className="py-1 pr-2 font-medium">Description</th>
                <th className="py-1 pr-2 font-medium">Category</th>
                <th className="py-1">
                  <span className="sr-only">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {fields.map((field, index) => {
                const rowErrors = errors.permissions?.[index];
                // DESIGN-GAP: the design gives no labels for the per-row inputs and the remove
                // button; each label names its column and row, for example "URI pattern, row 2".
                const row = index + 1;
                return (
                  <tr key={field.id} className="border-b border-gray-100 align-top">
                    <td className="py-1 pr-2">
                      <Cell label={`Method, row ${row}`} error={rowErrors?.httpMethod?.message}>
                        {(props) => (
                          <select
                            {...props}
                            {...register(`permissions.${index}.httpMethod`)}
                            className={INPUT}
                          >
                            {PERMISSION_METHODS.map((method) => (
                              <option key={method} value={method}>
                                {method}
                              </option>
                            ))}
                          </select>
                        )}
                      </Cell>
                    </td>
                    <td className="py-1 pr-2">
                      <Cell
                        label={`URI pattern, row ${row}`}
                        error={rowErrors?.uriPattern?.message}
                      >
                        {(props) => (
                          <input
                            {...props}
                            {...register(`permissions.${index}.uriPattern`)}
                            placeholder="/admin/v1/users/**"
                            className={`${INPUT} font-mono`}
                          />
                        )}
                      </Cell>
                    </td>
                    <td className="py-1 pr-2">
                      <Cell
                        label={`Description, row ${row}`}
                        error={rowErrors?.description?.message}
                      >
                        {(props) => (
                          <input
                            {...props}
                            {...register(`permissions.${index}.description`)}
                            className={INPUT}
                          />
                        )}
                      </Cell>
                    </td>
                    <td className="py-1 pr-2">
                      <Cell
                        label={`Category, row ${row}`}
                        error={rowErrors?.resourceCategory?.message}
                      >
                        {(props) => (
                          <input
                            {...props}
                            {...register(`permissions.${index}.resourceCategory`)}
                            className={INPUT}
                          />
                        )}
                      </Cell>
                    </td>
                    <td className="py-1">
                      <button
                        type="button"
                        aria-label={`Remove row ${row}`}
                        onClick={() => remove(index)}
                        className="rounded border border-gray-300 px-2 py-1 hover:bg-gray-100 disabled:opacity-50"
                      >
                        Remove
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {/* DESIGN-GAP: the design does not name the add button; "Add rule" is used. */}
          <button
            type="button"
            onClick={() => append(NEW_ROW)}
            className="rounded border border-gray-300 px-3 py-1 text-sm hover:bg-gray-100 disabled:opacity-50"
          >
            Add rule
          </button>
        </fieldset>
      </section>
      <FormErrorSummary messages={listError ? [listError, ...topMessages] : topMessages} />
      {serverError && serverError.kind !== 'validation' && (
        <InlineError error={serverError} mutation />
      )}
      <button
        type="submit"
        disabled={disabled || !dirty || pending}
        className="rounded bg-blue-700 px-3 py-1 text-sm text-white hover:bg-blue-800 disabled:opacity-50"
      >
        Save
      </button>
    </form>
  );
}

function ImplicitRows({ rows }: { rows: PermissionRow[] }) {
  const titleId = useId();
  return (
    <section aria-labelledby={titleId} className="space-y-2">
      <h3 id={titleId} className="font-semibold">
        Always included
      </h3>
      <p className="text-sm text-gray-700">
        Every non-system role keeps these two rules so that its users can load their profile and
        menus.
      </p>
      <table aria-labelledby={titleId} className="w-full text-left text-sm">
        <thead>
          <tr className="border-b border-gray-200">
            <th className="py-1 pr-2 font-medium">Method</th>
            <th className="py-1 pr-2 font-medium">URI pattern</th>
            <th className="py-1 pr-2 font-medium">Description</th>
            <th className="py-1 pr-2 font-medium">Category</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.id} className="border-b border-gray-100">
              <td className="py-1 pr-2">{row.httpMethod}</td>
              <td className="py-1 pr-2 font-mono">{row.uriPattern}</td>
              <td className="py-1 pr-2">{row.description}</td>
              <td className="py-1 pr-2">{row.resourceCategory}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

/** One editable cell: a screen-reader label, the control and its error message. */
function Cell({
  label,
  error,
  children,
}: {
  label: string;
  error?: string;
  children: (props: {
    id: string;
    'aria-invalid': true | undefined;
    'aria-describedby': string;
  }) => ReactNode;
}) {
  const id = useId();
  return (
    <div>
      <label htmlFor={id} className="sr-only">
        {label}
      </label>
      {children({
        id,
        'aria-invalid': error ? true : undefined,
        'aria-describedby': `${id}-error`,
      })}
      {error && (
        <p id={`${id}-error`} className="mt-1 text-xs text-red-700">
          {error}
        </p>
      )}
    </div>
  );
}
