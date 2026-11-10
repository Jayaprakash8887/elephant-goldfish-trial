// The field rules of the admin-service contract as named constants, added per step with the
// schema that uses them. Source: §4.4 and §4.5 of
// ../../../docs/designs/cce-admin-service.md#44-resources-and-field-rules (relative to
// src/schemas/). The admin-service design wins if the two disagree.

// Runtime constants of the contract that pages and components need (they may not import values
// from api/).
export { PERMISSION_METHODS, USER_STATUSES } from '../api/contract';

// ---------------------------------------------------------------------------------------------
// Paging (console-only rule: page sizes 20, 50 and 100 only; admin-service §4.3 allows 1 to 100)
// ---------------------------------------------------------------------------------------------

// DESIGN-GAP: the design lists "Page sizes 20, 50 and 100 only" among the console-only rules but
// does not say which module holds the list; both usePagingParams (hooks/) and Pager
// (components/) need it, and components may not import hooks/, so it lives here.
export const PAGE_SIZES = [20, 50, 100] as const;
export type PageSize = (typeof PAGE_SIZES)[number];

// ---------------------------------------------------------------------------------------------
// Role (admin-service §4.4, "Role")
// ---------------------------------------------------------------------------------------------

/** Upper-case words of letters and digits joined by single underscores, starting with a letter. */
export const ROLE_NAME_PATTERN = /^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$/;
export const ROLE_NAME_MIN = 2;
/** The limit of `api_permissions.permission_name`. */
export const ROLE_NAME_MAX = 100;
export const ROLE_DISPLAY_NAME_MIN = 1;
export const ROLE_DISPLAY_NAME_MAX = 100;
export const ROLE_DESCRIPTION_MAX = 500;

// ---------------------------------------------------------------------------------------------
// Permission row (admin-service §4.4, "Permission row"; §4.5, `PUT /roles/:id/permissions`)
// ---------------------------------------------------------------------------------------------

/** 1 to 255 characters. */
export const PERMISSION_URI_MIN = 1;
export const PERMISSION_URI_MAX = 255;
/** Starts with `/`. */
export const PERMISSION_URI_START = /^\//;
/** Made only of letters, digits and `- . _ ~ / *`. */
export const PERMISSION_URI_CHARS = /^[A-Za-z0-9\-._~/*]*$/;
/** Has no empty segment. */
export const PERMISSION_URI_EMPTY_SEGMENT = /\/\//;
/** `**` may only be the whole last segment. */
export const PERMISSION_URI_DOUBLE_STAR = '**';
export const PERMISSION_DESCRIPTION_MAX = 1000;
export const PERMISSION_CATEGORY_MAX = 50;
/** 0 to 200 explicit rows per save. */
export const PERMISSION_ROWS_MAX = 200;

// ---------------------------------------------------------------------------------------------
// User (admin-service §4.4, "User"; §4.5, `POST /users`, `PUT /users/:id/roles`, search on
// `GET /users`)
// ---------------------------------------------------------------------------------------------

/** Lower-case letters, digits and `. _ -`, starting with a letter or digit. */
export const USERNAME_PATTERN = /^[a-z0-9][a-z0-9._-]*$/;
export const USERNAME_MIN = 3;
export const USERNAME_MAX = 50;
/** Up to 254 characters, a valid address. */
export const EMAIL_MAX = 254;
/** First and last name: 1 to 100 characters each. */
export const PERSON_NAME_MIN = 1;
export const PERSON_NAME_MAX = 100;
/** Free text, up to 50 characters (AD-09); the list filter matches it exactly. */
export const FACILITY_SCOPE_MAX = 50;
/** `POST /users` takes 1 to 20 role ids; `PUT /users/:id/roles` takes 0 to 20. */
export const USER_ROLES_MIN_ON_CREATE = 1;
export const USER_ROLES_MAX = 20;
/** The Users list search `q`: 1 to 100 characters, an empty one is ignored. */
export const USER_SEARCH_MAX = 100;
