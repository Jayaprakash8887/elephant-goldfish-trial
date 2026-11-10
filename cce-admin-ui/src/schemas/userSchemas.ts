// The user forms' schemas. Every string is trimmed before it is checked (admin-service §4.1).
// The role-count rules are the console's own (design, "Forms and validation"): at least one role
// on create, zero or more on edit, at most 20.
import { z } from 'zod';
import {
  EMAIL_MAX,
  FACILITY_SCOPE_MAX,
  PERSON_NAME_MAX,
  PERSON_NAME_MIN,
  USER_ROLES_MAX,
  USER_ROLES_MIN_ON_CREATE,
  USERNAME_MAX,
  USERNAME_MIN,
  USERNAME_PATTERN,
} from './rules';

// DESIGN-GAP: the design gives the field rules but not the texts of the client-side validation
// messages. The messages below state each rule in plain words, as the role schemas do.
const email = z
  .string()
  .trim()
  .min(1, 'Enter an email address.')
  .max(EMAIL_MAX, `At most ${EMAIL_MAX} characters.`)
  .email('Enter a valid email address.');

const personName = (what: string) =>
  z
    .string()
    .trim()
    .min(PERSON_NAME_MIN, `Enter a ${what}.`)
    .max(PERSON_NAME_MAX, `At most ${PERSON_NAME_MAX} characters.`);

/** Empty means no facility scope. */
const facilityScope = z
  .string()
  .trim()
  .max(FACILITY_SCOPE_MAX, `At most ${FACILITY_SCOPE_MAX} characters.`);

const tooManyRoles = `At most ${USER_ROLES_MAX} roles.`;

export const createUserSchema = z.object({
  username: z
    .string()
    .trim()
    .min(USERNAME_MIN, `${USERNAME_MIN} to ${USERNAME_MAX} characters.`)
    .max(USERNAME_MAX, `${USERNAME_MIN} to ${USERNAME_MAX} characters.`)
    .regex(
      USERNAME_PATTERN,
      'Lower-case letters, digits and . _ -, starting with a letter or digit, for example jane.doe.',
    ),
  email,
  firstName: personName('first name'),
  lastName: personName('last name'),
  /** Empty means no facility scope; the request then leaves it out. */
  facilityScope,
  roleIds: z
    .array(z.string())
    .min(USER_ROLES_MIN_ON_CREATE, 'Choose at least one role.')
    .max(USER_ROLES_MAX, tooManyRoles),
});

export type CreateUserFormValues = z.infer<typeof createUserSchema>;

export const profileSchema = z.object({
  email,
  firstName: personName('first name'),
  lastName: personName('last name'),
  /** Empty means no facility scope; the request then sends `null`. */
  facilityScope,
});

export type ProfileFormValues = z.infer<typeof profileSchema>;

export const userRolesSchema = z.object({
  roleIds: z.array(z.string()).max(USER_ROLES_MAX, tooManyRoles),
});

export type UserRolesFormValues = z.infer<typeof userRolesSchema>;
