// The Menus catalog form's schema. Every string is trimmed before it is checked (admin-service
// §4.1). The parent rule is the console's own (design, "Forms and validation"): required for
// DETAIL and one of the NAV entries; absent for NAV.
import { z } from 'zod';
import {
  MENU_ICON_PATTERN,
  MENU_KEY_MAX,
  MENU_KEY_PATTERN,
  MENU_LABEL_MAX,
  MENU_LABEL_MIN,
  MENU_PATH_CHARS,
  MENU_PATH_MAX,
  MENU_PATH_MIN,
  MENU_PATH_START,
  MENU_SORT_ORDER_MAX,
  MENU_SORT_ORDER_MIN,
} from './rules';

// DESIGN-GAP: the design gives the field rules but not the texts of the client-side validation
// messages. The messages below state each rule in plain words, as the role schemas do.
const SORT_ORDER_MESSAGE = `A whole number from ${MENU_SORT_ORDER_MIN} to ${MENU_SORT_ORDER_MAX}.`;
export const PARENT_REQUIRED_MESSAGE = 'Choose the NAV menu this entry belongs to.';
export const PARENT_NOT_ALLOWED_MESSAGE = 'A NAV menu has no parent.';

/**
 * `navIds` are the ids of the NAV entries a DETAIL entry may sit under. An empty `icon` means no
 * icon and an empty `parentId` means no parent.
 */
export function menuFormSchema(navIds: readonly string[]) {
  return z
    .object({
      key: z
        .string()
        .trim()
        .min(1, 'Enter a key.')
        .max(MENU_KEY_MAX, `At most ${MENU_KEY_MAX} characters.`)
        .regex(
          MENU_KEY_PATTERN,
          'Lower-case words of letters, digits and -, each starting with a letter, joined by dots, for example patients.patient-detail.',
        ),
      label: z
        .string()
        .trim()
        .min(MENU_LABEL_MIN, 'Enter a label.')
        .max(MENU_LABEL_MAX, `At most ${MENU_LABEL_MAX} characters.`),
      path: z
        .string()
        .trim()
        .min(MENU_PATH_MIN, 'Enter a path.')
        .max(MENU_PATH_MAX, `At most ${MENU_PATH_MAX} characters.`)
        .regex(MENU_PATH_START, 'Start with /.')
        .regex(MENU_PATH_CHARS, 'Use only letters, digits and / _ - . :.'),
      icon: z
        .string()
        .trim()
        .refine(
          (icon) => icon === '' || MENU_ICON_PATTERN.test(icon),
          'A Heroicons component name ending in Icon, for example ChartBarIcon.',
        ),
      type: z.enum(['NAV', 'DETAIL']),
      parentId: z.string().trim(),
      sortOrder: z
        .number({ invalid_type_error: SORT_ORDER_MESSAGE, required_error: SORT_ORDER_MESSAGE })
        .int(SORT_ORDER_MESSAGE)
        .min(MENU_SORT_ORDER_MIN, SORT_ORDER_MESSAGE)
        .max(MENU_SORT_ORDER_MAX, SORT_ORDER_MESSAGE),
      isActive: z.boolean(),
    })
    .superRefine((values, ctx) => {
      if (values.type === 'DETAIL' && !navIds.includes(values.parentId)) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ['parentId'],
          message: PARENT_REQUIRED_MESSAGE,
        });
      }
      if (values.type === 'NAV' && values.parentId !== '') {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ['parentId'],
          message: PARENT_NOT_ALLOWED_MESSAGE,
        });
      }
    });
}

export type MenuFormValues = z.infer<ReturnType<typeof menuFormSchema>>;
