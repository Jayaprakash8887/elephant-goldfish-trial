// The role forms' schemas. Every string is trimmed before it is checked (admin-service §4.1).
import { z } from 'zod';
import {
  ROLE_DESCRIPTION_MAX,
  ROLE_DISPLAY_NAME_MAX,
  ROLE_DISPLAY_NAME_MIN,
  ROLE_NAME_MAX,
  ROLE_NAME_MIN,
  ROLE_NAME_PATTERN,
} from './rules';

// DESIGN-GAP: the design gives the field rules but not the texts of the client-side validation
// messages. The messages below state each rule in plain words.
const displayName = z
  .string()
  .trim()
  .min(ROLE_DISPLAY_NAME_MIN, 'Enter a display name.')
  .max(ROLE_DISPLAY_NAME_MAX, `At most ${ROLE_DISPLAY_NAME_MAX} characters.`);

const description = z
  .string()
  .trim()
  .max(ROLE_DESCRIPTION_MAX, `At most ${ROLE_DESCRIPTION_MAX} characters.`);

export const createRoleSchema = z.object({
  name: z
    .string()
    .trim()
    .min(ROLE_NAME_MIN, `${ROLE_NAME_MIN} to ${ROLE_NAME_MAX} characters.`)
    .max(ROLE_NAME_MAX, `${ROLE_NAME_MIN} to ${ROLE_NAME_MAX} characters.`)
    .regex(
      ROLE_NAME_PATTERN,
      'Upper-case letters and digits in words joined by single underscores, starting with a letter, for example FACILITY_ADMIN.',
    ),
  displayName,
  /** Empty means no description; the request then leaves it out. */
  description,
});

export type CreateRoleForm = z.infer<typeof createRoleSchema>;

export const roleDisplaySchema = z.object({
  displayName,
  /** Empty means no description; the request then sends `null`. */
  description,
});

export type RoleDisplayFormValues = z.infer<typeof roleDisplaySchema>;
