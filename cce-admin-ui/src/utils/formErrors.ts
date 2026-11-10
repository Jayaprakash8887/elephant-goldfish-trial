// Maps server errors onto React Hook Form fields (design, "Error presentation", field mapping).
import type { FieldValues, Path, UseFormSetError } from 'react-hook-form';
import type { Role } from '../api/contract';
import type { DescribedError } from './describeError';

/** The JSON path form `permissions[2].uriPattern` becomes `permissions.2.uriPattern`. */
export function toFormPath(field: string): string {
  return field.replace(/\[(\d+)\]/g, '.$1');
}

function isFormField(path: string, formFields: readonly string[]): boolean {
  return formFields.some((field) => path === field || path.startsWith(`${field}.`));
}

/**
 * Sets field errors with the server message for every `VALIDATION_ERROR` detail whose path is a
 * field of the form, and for a conflict code mapped in `codeToField`. Returns the other messages,
 * for the top of the form.
 */
export function applyServerErrors<T extends FieldValues>(
  setError: UseFormSetError<T>,
  error: DescribedError,
  formFields: readonly string[],
  codeToField: Readonly<Partial<Record<string, string>>>,
): string[] {
  if (error.kind === 'validation') {
    const details = error.details ?? [];
    if (details.length === 0) return [error.message];
    const rest: string[] = [];
    for (const detail of details) {
      const path = toFormPath(detail.field);
      if (isFormField(path, formFields)) {
        setError(path as Path<T>, { type: 'server', message: detail.message });
      } else {
        rest.push(detail.message);
      }
    }
    return rest;
  }
  if (error.kind === 'conflict') {
    const field = codeToField[error.code];
    if (field) {
      setError(field as Path<T>, { type: 'server', message: error.message });
      return [];
    }
    return [error.message];
  }
  // DESIGN-GAP: the design does not say what "the other messages" are for kinds other than
  // validation and conflict. Those kinds are shown by InlineError or SyncFailedNotice (kinds
  // table), so nothing is returned for them, to avoid showing the same message twice.
  return [];
}

const ROLE_INDEX = /^roleIds\[(\d+)\]$/;

/**
 * The messages under the role checkboxes for `ROLE_NOT_SYNCED` and `ROLE_NOT_ASSIGNABLE`. Each
 * detail's `roleIds[i]` is mapped back to the id that was sent, then to the role.
 */
export function roleErrorMessages(
  error: DescribedError,
  sentRoleIds: readonly string[],
  roles: readonly Role[],
): string[] {
  if (error.code !== 'ROLE_NOT_SYNCED' && error.code !== 'ROLE_NOT_ASSIGNABLE') return [];
  return (error.details ?? []).map((detail) => {
    const index = ROLE_INDEX.exec(detail.field)?.[1];
    const id = index === undefined ? undefined : sentRoleIds[Number(index)];
    if (id === undefined) {
      // DESIGN-GAP: the design does not cover a detail whose field is not `roleIds[i]` for an
      // index that was sent. The server's detail message is shown instead.
      return detail.message;
    }
    const role = roles.find((candidate) => candidate.id === id);
    const name = role ? `${role.displayName} (${role.name})` : `Role ${id}`;
    return error.code === 'ROLE_NOT_SYNCED'
      ? `Not synced yet: ${name}. Open the role and use Re-sync.`
      : `Cannot be assigned: ${name}.`;
  });
}
