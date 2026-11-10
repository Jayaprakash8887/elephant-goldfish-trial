// The Menus catalog form (F-15), an inline panel above the table. Create mode makes a new NAV
// entry ("Add menu") or a new DETAIL entry under a NAV ("Add detail"); edit mode shows key and
// type read-only, because they cannot change (admin-service §4.4). It reads its values once, when
// it mounts; MenusPage remounts it for each entry it opens.
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useMemo, useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import type { CreateMenuRequest, Menu, UpdateMenuRequest } from '../../api/contract';
import { menuFormSchema, type MenuFormValues as Values } from '../../schemas/menuSchemas';
import type { DescribedError } from '../../utils/describeError';
import { applyServerErrors } from '../../utils/formErrors';
import { navMenus, nextSortOrder } from '../../utils/menuTree';
import { Field } from '../roles/RoleCreateForm';
import { FormErrorSummary } from '../shared/FormErrorSummary';
import { InlineError } from '../shared/InlineError';
import { ICON_NAMES, isKnownIcon } from './iconMap';

/** A new NAV entry (`parent` null), a new DETAIL entry under `parent`, or an existing entry. */
export type MenuFormTarget = { mode: 'create'; parent: Menu | null } | { mode: 'edit'; menu: Menu };

const FORM_FIELDS = [
  'key',
  'label',
  'path',
  'icon',
  'type',
  'parentId',
  'sortOrder',
  'isActive',
] as const;
const CODE_TO_FIELD = { DUPLICATE_MENU_KEY: 'key' } as const;
const FIELD_KINDS: ReadonlySet<string> = new Set(['validation', 'conflict']);
const INPUT = 'w-full rounded border border-gray-300 px-2 py-1';

/**
 * Defaults for a new entry: active; sort order 10 more than the highest among its future
 * siblings, or 10 when it has none, at most 10000 (design, "Menus catalog").
 */
function initialValues(tree: readonly Menu[], target: MenuFormTarget): Values {
  if (target.mode === 'edit') {
    const { menu } = target;
    return {
      key: menu.key,
      label: menu.label,
      path: menu.path,
      icon: menu.icon ?? '',
      type: menu.type,
      parentId: menu.parentId ?? '',
      sortOrder: menu.sortOrder,
      isActive: menu.isActive,
    };
  }
  const { parent } = target;
  return {
    key: '',
    label: '',
    path: '',
    icon: '',
    type: parent ? 'DETAIL' : 'NAV',
    parentId: parent?.id ?? '',
    sortOrder: nextSortOrder(parent ? parent.children : navMenus(tree)),
    isActive: true,
  };
}

function heading(target: MenuFormTarget): string {
  // DESIGN-GAP: the design gives no heading for the form panel; these name what it does.
  if (target.mode === 'edit') return `Edit ${target.menu.label}`;
  return target.parent ? `Add detail under ${target.parent.label}` : 'Add menu';
}

export function MenuForm({
  tree,
  target,
  onCreate,
  onUpdate,
  onCancel,
  pending,
  serverError,
}: {
  tree: Menu[];
  target: MenuFormTarget;
  onCreate: (body: CreateMenuRequest) => Promise<void>;
  onUpdate: (id: string, body: UpdateMenuRequest) => Promise<void>;
  onCancel: () => void;
  pending: boolean;
  serverError?: DescribedError;
}) {
  const navs = useMemo(() => navMenus(tree), [tree]);
  const schema = useMemo(() => menuFormSchema(navs.map((nav) => nav.id)), [navs]);
  const [defaults] = useState(() => initialValues(tree, target));
  const {
    register,
    handleSubmit,
    setError,
    setValue,
    control,
    formState: { errors },
  } = useForm<Values>({ resolver: zodResolver(schema), defaultValues: defaults });
  const type = useWatch({ control, name: 'type' });
  const [topMessages, setTopMessages] = useState<string[]>([]);
  const editing = target.mode === 'edit';

  useEffect(() => {
    setTopMessages(
      serverError && FIELD_KINDS.has(serverError.kind)
        ? applyServerErrors(setError, serverError, FORM_FIELDS, CODE_TO_FIELD)
        : [],
    );
  }, [serverError, setError]);

  // The icon select: "No icon", the ten seed names, and the entry's current value when it is
  // not one of them (UI-AD-08).
  const iconOptions =
    defaults.icon !== '' && !isKnownIcon(defaults.icon)
      ? [...ICON_NAMES, defaults.icon]
      : ICON_NAMES;

  async function save(values: Values) {
    // Bodies are built from named fields (design, "API client"); empty icon and parent mean none.
    const parentId = values.type === 'DETAIL' ? values.parentId : null;
    try {
      if (target.mode === 'edit') {
        await onUpdate(target.menu.id, {
          label: values.label,
          path: values.path,
          icon: values.icon === '' ? null : values.icon,
          parentId,
          sortOrder: values.sortOrder,
          isActive: values.isActive,
        });
      } else {
        const body: CreateMenuRequest = {
          key: values.key,
          label: values.label,
          path: values.path,
          type: values.type,
          sortOrder: values.sortOrder,
          isActive: values.isActive,
        };
        if (values.icon !== '') body.icon = values.icon;
        if (parentId !== null) body.parentId = parentId;
        await onCreate(body);
      }
    } catch {
      // Shown through `serverError`; the form keeps its values.
    }
  }

  return (
    <form
      aria-label="Menu entry"
      noValidate
      onSubmit={handleSubmit(save)}
      className="mb-6 space-y-3 rounded border border-gray-200 bg-white p-4"
    >
      <h2 className="font-semibold">{heading(target)}</h2>
      <div className="grid gap-3 md:grid-cols-2">
        <Field label="Key" error={errors.key?.message}>
          {(props) => (
            <input
              {...props}
              {...register('key')}
              readOnly={editing}
              className={`${INPUT} font-mono read-only:bg-gray-100`}
            />
          )}
        </Field>
        <Field label="Label" error={errors.label?.message}>
          {(props) => <input {...props} {...register('label')} className={INPUT} />}
        </Field>
        <Field label="Path" error={errors.path?.message}>
          {(props) => <input {...props} {...register('path')} className={`${INPUT} font-mono`} />}
        </Field>
        <Field label="Icon" error={errors.icon?.message}>
          {(props) => (
            <select {...props} {...register('icon')} className={INPUT}>
              <option value="">No icon</option>
              {iconOptions.map((name) => (
                <option key={name} value={name}>
                  {name}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Type" error={errors.type?.message}>
          {(props) =>
            editing ? (
              <input
                {...props}
                {...register('type')}
                readOnly
                className={`${INPUT} read-only:bg-gray-100`}
              />
            ) : (
              // DESIGN-GAP: the design makes type read-only only in edit mode and lets the button
              // choose it for a new entry, so it is a select here, defaulting to the button's
              // type. Switching to NAV clears the parent; the default sort order is not
              // recomputed when type or parent change.
              <select
                {...props}
                {...register('type', {
                  onChange: (event: { target: { value: string } }) => {
                    if (event.target.value === 'NAV') setValue('parentId', '');
                  },
                })}
                className={INPUT}
              >
                <option value="NAV">NAV</option>
                <option value="DETAIL">DETAIL</option>
              </select>
            )
          }
        </Field>
        {type === 'DETAIL' && (
          <Field label="Parent" error={errors.parentId?.message}>
            {(props) => (
              <select {...props} {...register('parentId')} className={INPUT}>
                <option value="">Choose a parent</option>
                {navs.map((nav) => (
                  <option key={nav.id} value={nav.id}>
                    {nav.isActive ? nav.label : `${nav.label} (inactive)`}
                  </option>
                ))}
              </select>
            )}
          </Field>
        )}
        <Field label="Sort order" error={errors.sortOrder?.message}>
          {(props) => (
            <input
              {...props}
              {...register('sortOrder', { valueAsNumber: true })}
              type="number"
              className={INPUT}
            />
          )}
        </Field>
        <label className="flex items-center gap-2 self-end text-sm font-medium">
          <input type="checkbox" {...register('isActive')} />
          Active
        </label>
      </div>
      <FormErrorSummary messages={topMessages} />
      {serverError && !FIELD_KINDS.has(serverError.kind) && (
        <InlineError error={serverError} mutation />
      )}
      {/* DESIGN-GAP: the design does not name the form's buttons; "Save" and "Cancel" are used. */}
      <div className="flex gap-2">
        <button
          type="submit"
          disabled={pending}
          className="rounded bg-blue-700 px-3 py-1 text-sm text-white hover:bg-blue-800 disabled:opacity-50"
        >
          Save
        </button>
        <button
          type="button"
          onClick={onCancel}
          className="rounded border border-gray-300 px-3 py-1 text-sm hover:bg-gray-100"
        >
          Cancel
        </button>
      </div>
    </form>
  );
}
