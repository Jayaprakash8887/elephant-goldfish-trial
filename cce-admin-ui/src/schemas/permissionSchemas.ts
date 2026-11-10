// The Permissions tab's rules form. Every string is trimmed before it is checked (admin-service
// §4.1). The duplicate and implicit-equal rules are the console's own (design, "Forms and
// validation"); both are reported on `permissions.{i}.uriPattern`.
import { z } from 'zod';
import { normalizeRow, type RuleLike } from '../utils/permissions';
import {
  PERMISSION_CATEGORY_MAX,
  PERMISSION_DESCRIPTION_MAX,
  PERMISSION_METHODS,
  PERMISSION_ROWS_MAX,
  PERMISSION_URI_CHARS,
  PERMISSION_URI_DOUBLE_STAR,
  PERMISSION_URI_EMPTY_SEGMENT,
  PERMISSION_URI_MAX,
  PERMISSION_URI_MIN,
  PERMISSION_URI_START,
} from './rules';

const METHODS: readonly string[] = PERMISSION_METHODS;

/** `**` only as the whole last segment: no earlier segment contains it, the last one equals it. */
function doubleStarOnlyLast(pattern: string): boolean {
  const segments = pattern.split('/');
  return segments.every(
    (segment, index) =>
      !segment.includes(PERMISSION_URI_DOUBLE_STAR) ||
      (index === segments.length - 1 && segment === PERMISSION_URI_DOUBLE_STAR),
  );
}

// DESIGN-GAP: the design gives the field rules but not the texts of the client-side validation
// messages, except "Already included for every role". The messages below state each rule in
// plain words, as the role schemas do.
export const permissionRowSchema = z.object({
  httpMethod: z
    .string()
    .trim()
    .toUpperCase()
    .refine((method) => METHODS.includes(method), `One of ${METHODS.join(', ')}.`),
  uriPattern: z
    .string()
    .trim()
    .min(PERMISSION_URI_MIN, 'Enter a URI pattern.')
    .max(PERMISSION_URI_MAX, `At most ${PERMISSION_URI_MAX} characters.`)
    .regex(PERMISSION_URI_START, 'Start with /.')
    .regex(PERMISSION_URI_CHARS, 'Use only letters, digits and - . _ ~ / *.')
    .refine((pattern) => !PERMISSION_URI_EMPTY_SEGMENT.test(pattern), 'No empty segment (//).')
    .refine(doubleStarOnlyLast, '** may only be the whole last segment.'),
  /** Empty means no description; the request then leaves it out. */
  description: z
    .string()
    .trim()
    .max(PERMISSION_DESCRIPTION_MAX, `At most ${PERMISSION_DESCRIPTION_MAX} characters.`),
  /** Empty means no category; the request then leaves it out. */
  resourceCategory: z
    .string()
    .trim()
    .max(PERMISSION_CATEGORY_MAX, `At most ${PERMISSION_CATEGORY_MAX} characters.`),
});

export type PermissionFormRow = z.infer<typeof permissionRowSchema>;

export const IMPLICIT_EQUAL_MESSAGE = 'Already included for every role';
// DESIGN-GAP: the design gives no text for the duplicate-row error; this one says what is wrong.
export const DUPLICATE_RULE_MESSAGE = 'Another rule has the same method and URI pattern.';

function methodAndPattern(row: RuleLike): string {
  const { httpMethod, uriPattern } = normalizeRow(row);
  return `${httpMethod} ${uriPattern}`;
}

/**
 * The rules form: at most 200 explicit rows, no two with the same method and pattern (reported on
 * the later one, as the service does), and none equal to one of the role's implicit rows.
 */
export function permissionsFormSchema(implicitRows: readonly RuleLike[]) {
  const implicit = new Set(implicitRows.map(methodAndPattern));
  return z
    .object({
      permissions: z
        .array(permissionRowSchema)
        .max(PERMISSION_ROWS_MAX, `At most ${PERMISSION_ROWS_MAX} rules.`),
    })
    .superRefine(({ permissions }, ctx) => {
      const seen = new Set<string>();
      permissions.forEach((row, index) => {
        const key = methodAndPattern(row);
        const path = ['permissions', index, 'uriPattern'];
        if (implicit.has(key)) {
          ctx.addIssue({ code: z.ZodIssueCode.custom, path, message: IMPLICIT_EQUAL_MESSAGE });
        } else if (seen.has(key)) {
          ctx.addIssue({ code: z.ZodIssueCode.custom, path, message: DUPLICATE_RULE_MESSAGE });
        }
        seen.add(key);
      });
    });
}

export type PermissionsFormValues = z.infer<ReturnType<typeof permissionsFormSchema>>;
