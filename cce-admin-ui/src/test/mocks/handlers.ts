// One default handler per endpoint the console calls, typed only from the contract (UI-AD-03,
// UI-AD-19). Each records the request and returns the fixture. They do not implement the
// service's rules; a test that needs another answer overrides one handler with
// `server.use(...)`, using okResponse or errorResponse.
//
// DESIGN-GAP: the design lists three files that grow step by step (routes, api/ and hook files,
// rules.ts); the handlers and fixtures are not among them, so they are created in full here.
import { http, HttpResponse, type PathParams } from 'msw';
import type {
  ChangeUserStatusRequest,
  CreateMenuRequest,
  CreateRoleRequest,
  CreateUserRequest,
  ErrorCode,
  ErrorEnvelope,
  FieldError,
  Me,
  Menu,
  PageInfo,
  PartialState,
  ReplaceRoleMenusRequest,
  ReplaceRolePermissionsRequest,
  ReplaceUserRolesRequest,
  ResetPasswordResult,
  Role,
  RoleMenus,
  RolePermissions,
  RoleSyncResult,
  SuccessEnvelope,
  UpdateMenuRequest,
  UpdateRoleRequest,
  UpdateUserRequest,
  User,
  Warning,
} from '../../api/contract';
import * as fixtures from './fixtures';
import { recordRequest } from './requests';

type Body<T> = SuccessEnvelope<T> | ErrorEnvelope;
type IdParams = { id: string };

/** Matches any origin, as the console's base URL differs between tests and builds. */
const api = (path: string) => `*/admin/v1${path}`;

export function okResponse<T>(
  data: T,
  extra: { status?: number; pagination?: PageInfo; warnings?: Warning[] } = {},
) {
  const { status = 200, ...rest } = extra;
  return HttpResponse.json<SuccessEnvelope<T>>({ data, ...rest }, { status });
}

export function errorResponse(
  status: number,
  code: ErrorCode,
  message: string,
  extra: { details?: FieldError[]; partialState?: PartialState } = {},
) {
  return HttpResponse.json<ErrorEnvelope>({ error: { code, message, ...extra } }, { status });
}

/** Slices a fixture list by the `page` and `pageSize` query parameters (defaults 1 and 20). */
function paged<T>(items: T[], request: Request) {
  const params = new URL(request.url).searchParams;
  const page = Number(params.get('page') ?? 1);
  const pageSize = Number(params.get('pageSize') ?? 20);
  const totalItems = items.length;
  const totalPages = Math.ceil(totalItems / pageSize);
  const data = items.slice((page - 1) * pageSize, page * pageSize);
  return okResponse(data, { pagination: { page, pageSize, totalItems, totalPages } });
}

const findUser = (id: string) => fixtures.users.find((user) => user.id === id);
const findRole = (id: string) => fixtures.roles.find((role) => role.id === id);
const findMenu = (id: string) =>
  fixtures.menuTree.flatMap((menu) => [menu, ...menu.children]).find((menu) => menu.id === id);

const userNotFound = () => errorResponse(404, 'USER_NOT_FOUND', 'The user does not exist.');
const roleNotFound = () => errorResponse(404, 'ROLE_NOT_FOUND', 'The role does not exist.');
const menuNotFound = () => errorResponse(404, 'MENU_NOT_FOUND', 'The menu does not exist.');

export const handlers = [
  // Me
  http.get<PathParams, never, Body<Me>>(api('/me'), async ({ request }) => {
    await recordRequest(request);
    return okResponse(fixtures.me);
  }),

  // Users
  http.get<PathParams, never, Body<User[]>>(api('/users'), async ({ request }) => {
    await recordRequest(request);
    return paged(fixtures.users, request);
  }),
  http.post<PathParams, CreateUserRequest, Body<User>>(api('/users'), async ({ request }) => {
    await recordRequest(request);
    return okResponse(fixtures.createdUser, { status: 201 });
  }),
  http.get<IdParams, never, Body<User>>(api('/users/:id'), async ({ request, params }) => {
    await recordRequest(request);
    const user = findUser(params.id);
    return user ? okResponse(user) : userNotFound();
  }),
  http.put<IdParams, UpdateUserRequest, Body<User>>(
    api('/users/:id'),
    async ({ request, params }) => {
      await recordRequest(request);
      const user = findUser(params.id);
      return user ? okResponse(user) : userNotFound();
    },
  ),
  http.patch<IdParams, ChangeUserStatusRequest, Body<User>>(
    api('/users/:id/status'),
    async ({ request, params }) => {
      await recordRequest(request);
      const user = findUser(params.id);
      return user ? okResponse(user) : userNotFound();
    },
  ),
  http.put<IdParams, ReplaceUserRolesRequest, Body<User>>(
    api('/users/:id/roles'),
    async ({ request, params }) => {
      await recordRequest(request);
      const user = findUser(params.id);
      return user ? okResponse(user) : userNotFound();
    },
  ),
  http.post<IdParams, never, Body<ResetPasswordResult>>(
    api('/users/:id/reset-password'),
    async ({ request, params }) => {
      await recordRequest(request);
      const user = findUser(params.id);
      return user ? okResponse({ userId: user.id, email: user.email }) : userNotFound();
    },
  ),

  // Roles
  http.get<PathParams, never, Body<Role[]>>(api('/roles'), async ({ request }) => {
    await recordRequest(request);
    return paged(fixtures.roles, request);
  }),
  http.post<PathParams, CreateRoleRequest, Body<Role>>(api('/roles'), async ({ request }) => {
    await recordRequest(request);
    return okResponse(fixtures.createdRole, { status: 201 });
  }),
  http.get<IdParams, never, Body<Role>>(api('/roles/:id'), async ({ request, params }) => {
    await recordRequest(request);
    const role = findRole(params.id);
    return role ? okResponse(role) : roleNotFound();
  }),
  http.put<IdParams, UpdateRoleRequest, Body<Role>>(
    api('/roles/:id'),
    async ({ request, params }) => {
      await recordRequest(request);
      const role = findRole(params.id);
      return role ? okResponse(role) : roleNotFound();
    },
  ),
  http.delete<IdParams, never, ErrorEnvelope | null>(
    api('/roles/:id'),
    async ({ request, params }) => {
      await recordRequest(request);
      return findRole(params.id) ? new HttpResponse(null, { status: 204 }) : roleNotFound();
    },
  ),
  http.get<IdParams, never, Body<RoleMenus>>(
    api('/roles/:id/menus'),
    async ({ request, params }) => {
      await recordRequest(request);
      const menus = fixtures.roleMenus[params.id];
      return menus ? okResponse(menus) : roleNotFound();
    },
  ),
  http.put<IdParams, ReplaceRoleMenusRequest, Body<RoleMenus>>(
    api('/roles/:id/menus'),
    async ({ request, params }) => {
      await recordRequest(request);
      const menus = fixtures.roleMenus[params.id];
      return menus ? okResponse(menus) : roleNotFound();
    },
  ),
  http.get<IdParams, never, Body<RolePermissions>>(
    api('/roles/:id/permissions'),
    async ({ request, params }) => {
      await recordRequest(request);
      const permissions = fixtures.rolePermissions[params.id];
      return permissions ? okResponse(permissions) : roleNotFound();
    },
  ),
  http.put<IdParams, ReplaceRolePermissionsRequest, Body<RolePermissions>>(
    api('/roles/:id/permissions'),
    async ({ request, params }) => {
      await recordRequest(request);
      const permissions = fixtures.rolePermissions[params.id];
      return permissions ? okResponse(permissions) : roleNotFound();
    },
  ),
  http.post<IdParams, never, Body<RoleSyncResult>>(
    api('/roles/:id/sync'),
    async ({ request, params }) => {
      await recordRequest(request);
      return findRole(params.id)
        ? okResponse({ roleId: params.id, syncStatus: 'SYNCED', lastSyncAt: fixtures.LAST_SYNC_AT })
        : roleNotFound();
    },
  ),

  // Menus
  http.get<PathParams, never, Body<Menu[]>>(api('/menus'), async ({ request }) => {
    await recordRequest(request);
    return okResponse(fixtures.menuTree);
  }),
  http.post<PathParams, CreateMenuRequest, Body<Menu>>(api('/menus'), async ({ request }) => {
    await recordRequest(request);
    return okResponse(fixtures.createdMenu, { status: 201 });
  }),
  http.put<IdParams, UpdateMenuRequest, Body<Menu>>(
    api('/menus/:id'),
    async ({ request, params }) => {
      await recordRequest(request);
      const menu = findMenu(params.id);
      return menu ? okResponse(menu) : menuNotFound();
    },
  ),
];
