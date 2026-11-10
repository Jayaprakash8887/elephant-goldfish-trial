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
