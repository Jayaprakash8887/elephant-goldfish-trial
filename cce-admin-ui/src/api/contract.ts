// The API contract of cce-admin-service, mirrored by hand (D-01, UI-AD-03, Goal 6).
// Source: ../../../docs/designs/cce-admin-service.md §4 (sections 4.1 to 4.5); it mirrors the
// contract of 2026-11-10. The admin-service design wins if the two disagree.
//
// Response fields are always present and are `null` when empty; only the optional envelope fields
// (`pagination`, `warnings`, `details`, `partialState`) are left out when absent. Request bodies
// follow the optional fields that §4.5 marks with `?`.

// ---------------------------------------------------------------------------------------------
// Envelopes (§4.1, §4.2, §4.3)
// ---------------------------------------------------------------------------------------------

/** The contract's pagination block. */
export interface PageInfo {
  page: number;
  pageSize: number;
  totalItems: number;
  totalPages: number;
}

export type WarningCode = 'DETAIL_WITHOUT_PARENT' | 'ACTION_EMAIL_NOT_SENT' | 'ROLE_SYNC_FAILED';

/** A union on `code`: each warning carries only the reference fields its code defines. */
export type Warning =
  | { code: 'DETAIL_WITHOUT_PARENT'; message: string; menuId: string; parentMenuId: string }
  | { code: 'ACTION_EMAIL_NOT_SENT' | 'ROLE_SYNC_FAILED'; message: string };

export interface SuccessEnvelope<T> {
  data: T;
  pagination?: PageInfo;
  warnings?: Warning[];
}

export interface FieldError {
  field: string;
  message: string;
}

/** The seven operations of §4.2. */
export type PartialStateOperation =
  | 'USER_CREATE'
  | 'USER_PROFILE_UPDATE'
  | 'USER_STATUS_CHANGE'
  | 'USER_ROLES_UPDATE'
  | 'USER_PASSWORD_RESET'
  | 'ROLE_PERMISSIONS_SAVE'
  | 'ROLE_SYNC';

export interface PartialState {
  operation: PartialStateOperation;
  entityId: string;
  failedStep: string;
  completedSteps: string[];
  keycloakUserId: string | null;
  auditLogId: string | null;
}

/** `code` is a string, not ErrorCode, so an unknown code still parses (and becomes `unexpected`). */
export interface ErrorEnvelope {
  error: {
    code: string;
    message: string;
    details?: FieldError[];
    partialState?: PartialState;
  };
}

/** Every code of §4.2. */
export type ErrorCode =
  | 'VALIDATION_ERROR'
  | 'UNAUTHENTICATED'
  | 'FORBIDDEN'
  | 'USER_NOT_FOUND'
  | 'ROLE_NOT_FOUND'
  | 'MENU_NOT_FOUND'
  | 'NOT_FOUND'
  | 'METHOD_NOT_ALLOWED'
  | 'EMAIL_IN_USE'
  | 'USERNAME_IN_USE'
  | 'DUPLICATE_ROLE_NAME'
  | 'DUPLICATE_MENU_KEY'
  | 'ROLE_IN_USE'
  | 'SYSTEM_ROLE_IMMUTABLE'
  | 'LAST_SYSTEM_ADMIN'
  | 'ROLE_NOT_ASSIGNABLE'
  | 'ROLE_NOT_SYNCED'
  | 'MENU_HAS_CHILDREN'
  | 'KEYCLOAK_SYNC_FAILED'
  | 'INTERNAL_ERROR';

// ---------------------------------------------------------------------------------------------
// Users (§4.4, §4.5)
// ---------------------------------------------------------------------------------------------

export type UserStatus = 'ACTIVE' | 'INACTIVE' | 'LOCKED';

export interface UserRoleRef {
  id: string;
  name: string;
  displayName: string;
}

export interface User {
  id: string;
  keycloakId: string;
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  status: UserStatus;
  facilityScope: string | null;
  roles: UserRoleRef[];
  lastLoginAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface UserListQuery {
  status?: UserStatus;
  roleId?: string;
  facilityScope?: string;
  q?: string;
  page?: number;
  pageSize?: number;
}

export interface CreateUserRequest {
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  facilityScope?: string;
  roleIds: string[];
}

export interface UpdateUserRequest {
  email: string;
  firstName: string;
  lastName: string;
  facilityScope: string | null;
}

export interface ChangeUserStatusRequest {
  status: UserStatus;
}

export interface ReplaceUserRolesRequest {
  roleIds: string[];
}

export interface ResetPasswordResult {
  userId: string;
  email: string;
}

// ---------------------------------------------------------------------------------------------
// Roles (§4.4, §4.5)
// ---------------------------------------------------------------------------------------------

export type SyncStatus = 'NEVER' | 'SYNCED' | 'FAILED';

export interface Role {
  id: string;
  name: string;
  displayName: string;
  description: string | null;
  isSystem: boolean;
  isAssignable: boolean;
  syncStatus: SyncStatus;
  lastSyncAt: string | null;
  userCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface CreateRoleRequest {
  name: string;
  displayName: string;
  description?: string;
}

export interface UpdateRoleRequest {
  displayName: string;
  description: string | null;
}

export interface RoleMenus {
  roleId: string;
  menuIds: string[];
}

export interface ReplaceRoleMenusRequest {
  menuIds: string[];
}

export interface PermissionRow {
  id: number;
  httpMethod: string;
  uriPattern: string;
  description: string | null;
  resourceCategory: string | null;
  implicit: boolean;
}

export interface PermissionRowInput {
  httpMethod: string;
  uriPattern: string;
  description?: string;
  resourceCategory?: string;
}

/** §4.4 types `syncStatus` as a string; narrowed here to the three values of the Role resource. */
export interface RolePermissions {
  roleId: string;
  syncStatus: SyncStatus;
  lastSyncAt: string | null;
  permissions: PermissionRow[];
}

export interface ReplaceRolePermissionsRequest {
  permissions: PermissionRowInput[];
}

/** `lastSyncAt` is never null here: the sync has just run. */
export interface RoleSyncResult {
  roleId: string;
  syncStatus: SyncStatus;
  lastSyncAt: string;
}

// ---------------------------------------------------------------------------------------------
// Menus (§4.4, §4.5)
// ---------------------------------------------------------------------------------------------

export type MenuType = 'NAV' | 'DETAIL';

export interface Menu {
  id: string;
  key: string;
  label: string;
  path: string;
  icon: string | null;
  type: MenuType;
  parentId: string | null;
  sortOrder: number;
  isActive: boolean;
  children: Menu[];
}

export interface CreateMenuRequest {
  key: string;
  label: string;
  path: string;
  icon?: string;
  type: MenuType;
  parentId?: string;
  sortOrder: number;
  isActive?: boolean;
}

export interface UpdateMenuRequest {
  label: string;
  path: string;
  icon: string | null;
  parentId: string | null;
  sortOrder: number;
  isActive: boolean;
}

// ---------------------------------------------------------------------------------------------
// Me (§4.4)
// ---------------------------------------------------------------------------------------------

export interface Me {
  id: string;
  username: string;
  roles: string[];
  facilityScope: string | null;
}

// ---------------------------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------------------------

export const USER_STATUSES = [
  'ACTIVE',
  'INACTIVE',
  'LOCKED',
] as const satisfies readonly UserStatus[];

export const SYNC_STATUSES = ['NEVER', 'SYNCED', 'FAILED'] as const satisfies readonly SyncStatus[];

/** Exactly the five methods fixed by the contract (UI-AD-05). */
export const PERMISSION_METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'] as const;
