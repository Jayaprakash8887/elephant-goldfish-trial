# API reference

Taken from the controllers in `../src/main/java/org/cce/admin/controller/`, the DTOs in
`controller/dto/`, [`ApiExceptionHandler`](../src/main/java/org/cce/admin/controller/ApiExceptionHandler.java)
and [`SecurityErrorWriter`](../src/main/java/org/cce/admin/security/SecurityErrorWriter.java).
Cross-checked against the springdoc document served at `GET /admin/v1/api-docs` (see
[OpenAPI document](#openapi-document)).

## Conventions

- **Base path** `/admin/v1`. JSON in UTF-8, camelCase.
- **Request bodies**: every string is trimmed, and a string that is empty after trimming becomes
  `null`, so lengths are counted after trimming. An unknown property is a 400. Query and path
  strings are trimmed too.
- **Ids**: users, roles, menus and audit rows use UUID strings. Permission row ids are integers
  (`api_permissions.id` is `BIGSERIAL`). A path id that is not a UUID gives 400 with `field` `id`.
- **Times**: `Instant` values serialised by Jackson as ISO-8601 UTC (`…Z`). `User`, `Role`,
  `RolePermissions` and `RoleSyncResult` times are truncated to microseconds.
- **Success envelope** ([`CommonDtos.ApiResponse`](../src/main/java/org/cce/admin/controller/dto/CommonDtos.java#L19-L39)):
  `{ "data": …, "pagination"?: {…}, "warnings"?: [ … ] }`. `pagination` and `warnings` are left
  out when absent. Every other field is always written, as `null` when empty.
- **Warning**: `{ "code", "message" }`; `DETAIL_WITHOUT_PARENT` also has `menuId` and
  `parentMenuId`.

  | Code | Returned by |
  |---|---|
  | `ACTION_EMAIL_NOT_SENT` | `POST /users`, when the set-password email failed. |
  | `ROLE_SYNC_FAILED` | `POST /roles`, when the first gateway sync failed. |
  | `DETAIL_WITHOUT_PARENT` | `PUT /roles/:id/menus`, one per `DETAIL` menu granted without its parent (also on a no-op). |

- **Error envelope**: `{ "error": { "code", "message", "details"?: [{ "field", "message" }], "partialState"?: {…} } }`.
  Clients branch on `code`. `details` is present for `VALIDATION_ERROR`, `ROLE_NOT_ASSIGNABLE` and
  `ROLE_NOT_SYNCED`. `partialState` is present only for `KEYCLOAK_SYNC_FAILED`.
- **Pagination** ([`PageParams`](../src/main/java/org/cce/admin/controller/PageParams.java)):
  `page` 1-based (default 1, min 1), `pageSize` default 20, 1 to 100. Out of range or not an
  integer is 400, never clamped. Block: `{ page, pageSize, totalItems, totalPages }`;
  `totalPages` is 0 when `totalItems` is 0. A page past the end returns `data: []`.
- **No-op**: a write that asks for the state that already exists returns 200 with the current
  resource and writes nothing (no audit row, no Keycloak or gateway call).
- **No concurrency control**: no ETags or versions; last write wins.

## Who may call what

Every endpoint except `GET /health` needs:

1. a valid bearer token (else 401 `UNAUTHENTICATED`),
2. a local `users` row whose `keycloak_id` equals the token `sub`, with `status = ACTIVE` (else
   403 `FORBIDDEN`),
3. when `PERMISSION_CHECK_ENABLED=true`, an `api_permissions` row whose `permission_name` is one
   of the token's roles, whose `http_method` equals the request method, and whose `uri_pattern`
   matches the path with `AntPathMatcher` (else 403 `FORBIDDEN`).

With the seed data, `SUPER_ADMIN` holds `GET/POST/PUT/PATCH/DELETE /admin/v1/**`, so it may call
everything. Every role created through the API gets the two implicit rows `GET /admin/v1/me` and
`GET /admin/v1/me/menus`, so a console user can call those two and nothing else unless the role
is given more rows. The "Permission (seed)" column below shows the row that grants each call in a
fresh database. See [security-model.md](security-model.md).

## Endpoint summary

Every endpoint can also return 401, 403, 400 `VALIDATION_ERROR` and 500 `INTERNAL_ERROR`
(health: only 500 in theory). The "Errors" column lists the others.

| Method | Path | Permission (seed) | Success | Errors |
|---|---|---|---|---|
| GET | `/health` | public | 200 `Health` (no envelope) | |
| GET | `/me` | `SUPER_ADMIN` or implicit row | 200 `Me` | |
| GET | `/me/menus` | `SUPER_ADMIN` or implicit row | 200 `MenuTreeItem[]` | |
| GET | `/users` | `SUPER_ADMIN` | 200 `User[]` + pagination | |
| POST | `/users` | `SUPER_ADMIN` | 201 `User`, `Location`, warning? | 409 `USERNAME_IN_USE`, `EMAIL_IN_USE`, `ROLE_NOT_ASSIGNABLE`, `ROLE_NOT_SYNCED`; 502 `USER_CREATE` |
| GET | `/users/{id}` | `SUPER_ADMIN` | 200 `User` | 404 `USER_NOT_FOUND` |
| PUT | `/users/{id}` | `SUPER_ADMIN` | 200 `User` | 404 `USER_NOT_FOUND`; 409 `EMAIL_IN_USE`; 502 `USER_PROFILE_UPDATE` |
| PATCH | `/users/{id}/status` | `SUPER_ADMIN` | 200 `User` | 404 `USER_NOT_FOUND`; 409 `LAST_SYSTEM_ADMIN`; 502 `USER_STATUS_CHANGE` |
| PUT | `/users/{id}/roles` | `SUPER_ADMIN` | 200 `User` | 404 `USER_NOT_FOUND`; 409 `ROLE_NOT_ASSIGNABLE`, `ROLE_NOT_SYNCED`, `LAST_SYSTEM_ADMIN`; 502 `USER_ROLES_UPDATE` |
| POST | `/users/{id}/reset-password` | `SUPER_ADMIN` | 200 `ResetPasswordResult` | 404 `USER_NOT_FOUND`; 502 `USER_PASSWORD_RESET` |
| GET | `/roles` | `SUPER_ADMIN` | 200 `Role[]` + pagination | |
| POST | `/roles` | `SUPER_ADMIN` | 201 `Role`, `Location`, warning? | 409 `DUPLICATE_ROLE_NAME`; 502 `ROLE_CREATE` |
| GET | `/roles/{id}` | `SUPER_ADMIN` | 200 `Role` | 404 `ROLE_NOT_FOUND` |
| PUT | `/roles/{id}` | `SUPER_ADMIN` | 200 `Role` | 404 `ROLE_NOT_FOUND`; 409 `SYSTEM_ROLE_IMMUTABLE` |
| DELETE | `/roles/{id}` | `SUPER_ADMIN` | 204, no body | 404 `ROLE_NOT_FOUND`; 409 `SYSTEM_ROLE_IMMUTABLE`, `ROLE_IN_USE`; 502 `ROLE_DELETE` |
| GET | `/roles/{id}/menus` | `SUPER_ADMIN` | 200 `RoleMenus` | 404 `ROLE_NOT_FOUND` |
| PUT | `/roles/{id}/menus` | `SUPER_ADMIN` | 200 `RoleMenus`, warnings? | 404 `ROLE_NOT_FOUND`; 409 `SYSTEM_ROLE_IMMUTABLE` |
| GET | `/roles/{id}/permissions` | `SUPER_ADMIN` | 200 `RolePermissions` | 404 `ROLE_NOT_FOUND` |
| PUT | `/roles/{id}/permissions` | `SUPER_ADMIN` | 200 `RolePermissions` | 404 `ROLE_NOT_FOUND`; 409 `SYSTEM_ROLE_IMMUTABLE`; 502 `ROLE_PERMISSIONS_SAVE` |
| POST | `/roles/{id}/sync` | `SUPER_ADMIN` | 200 `RoleSyncResult` | 404 `ROLE_NOT_FOUND`; 502 `ROLE_SYNC` |
| GET | `/menus` | `SUPER_ADMIN` | 200 `Menu[]` (tree) | |
| POST | `/menus` | `SUPER_ADMIN` | 201 `Menu`, `Location` | 409 `DUPLICATE_MENU_KEY` |
| PUT | `/menus/{id}` | `SUPER_ADMIN` | 200 `Menu` | 404 `MENU_NOT_FOUND` |
| DELETE | `/menus/{id}` | `SUPER_ADMIN` | 204, no body | 404 `MENU_NOT_FOUND`; 409 `MENU_HAS_CHILDREN` |

The 502 column shows `partialState.operation`. Unknown paths give 404 `NOT_FOUND`, and a known
path with the wrong method gives 405 `METHOD_NOT_ALLOWED`, but only after the security filters:
without a token they give 401, and a caller with no matching permission row gets 403.

## Resource shapes

**User** ([`UserDtos.User`](../src/main/java/org/cce/admin/controller/dto/UserDtos.java#L25-L38))

| Field | Type | Notes |
|---|---|---|
| `id` | uuid | |
| `keycloakId` | uuid | Keycloak user id = token `sub`. |
| `username` | string | Never changes. |
| `email` | string | Stored lower case. |
| `firstName`, `lastName` | string | |
| `status` | `ACTIVE` \| `INACTIVE` \| `LOCKED` | |
| `facilityScope` | string \| null | |
| `roles` | `[{ id, name, displayName }]` | From `user_roles`, ordered by `name`. |
| `lastLoginAt` | instant \| null | Set from the token's `auth_time` on requests. |
| `createdAt`, `updatedAt` | instant | `updatedAt` changes on profile, status and role changes. |

**Role** ([`RoleDtos.Role`](../src/main/java/org/cce/admin/controller/dto/RoleDtos.java#L27-L39)):
`id`, `name`, `displayName`, `description` (nullable), `isSystem`, `isAssignable`, `syncStatus`
(`NEVER` \| `SYNCED` \| `FAILED`), `lastSyncAt` (nullable), `userCount` (local users of any status
holding it), `createdAt`, `updatedAt`. A sync does not change `updatedAt`.

**Menu** ([`MenuDtos.Menu`](../src/main/java/org/cce/admin/controller/dto/MenuDtos.java#L32-L43)):
`id`, `key`, `label`, `path`, `icon` (nullable), `type` (`NAV` \| `DETAIL`), `parentId`
(nullable), `sortOrder`, `isActive`, `children` (`Menu[]`, ordered by `sortOrder` then `label`;
empty for `DETAIL`).

**Permission row** ([`RoleDtos.PermissionRow`](../src/main/java/org/cce/admin/controller/dto/RoleDtos.java#L42-L49)):
`id` (integer), `httpMethod`, `uriPattern`, `description`, `resourceCategory`, `implicit`
(`true` for `GET /admin/v1/me` and `GET /admin/v1/me/menus`).

**Named results**: `Me` `{ id, username, roles: [string], facilityScope }`;
`MenuTreeItem` `{ id, key, label, path, icon, type, sortOrder, children }`;
`RoleMenus` `{ roleId, menuIds }` (ids sorted by their string form);
`RolePermissions` `{ roleId, syncStatus, lastSyncAt, permissions }` (implicit rows first, then
explicit rows by `uriPattern`, `httpMethod`); `RoleSyncResult` `{ roleId, syncStatus, lastSyncAt }`;
`ResetPasswordResult` `{ userId, email }`; `Health` `{ status: "UP", database: "UP" | "DOWN" }`.

**PartialState** (in a 502): `{ operation, entityId, failedStep, completedSteps: [string], keycloakUserId: uuid|null, auditLogId: uuid|null }`.
Every field is always written.

## Endpoints

### Health and current user

**`GET /health`** — public. Runs `SELECT 1` with a 2 s query timeout
([`HealthService`](../src/main/java/org/cce/admin/service/HealthService.java)). Always HTTP 200
with `status: "UP"`; `database` is `"DOWN"` when the probe fails. No envelope. A bad
`Authorization` header is ignored. Only `GET` is public: other methods need a token.

**`GET /me`** — `data`: `Me`. `roles` holds the token's role names that exist in `roles`,
ordered by name (Keycloak roles such as `offline_access` drop out)
([`CurrentUserService.me`](../src/main/java/org/cce/admin/service/CurrentUserService.java#L33-L41)).

**`GET /me/menus`** — `data`: `MenuTreeItem[]`. The union of the active menus granted to the
token's roles (joined to `roles` by name). `NAV` menus at the top; a `DETAIL` appears under its
parent only if the parent is also in the set; siblings ordered by `sortOrder`, then `label`. A
caller with no matching roles gets `[]`
([`MenuTreeBuilder`](../src/main/java/org/cce/admin/service/MenuTreeBuilder.java)).
The system roles have no menus and their menus cannot be changed, so a caller holding only
`SUPER_ADMIN` always gets `[]`.

### Users

**`GET /users`** — query parameters (all optional, all must match):

| Param | Type | Rule |
|---|---|---|
| `status` | `ACTIVE`/`INACTIVE`/`LOCKED` | Other value → 400 `status`. |
| `roleId` | uuid | Users holding the role. Unknown id → empty page. |
| `facilityScope` | string | Exact match. |
| `q` | string, max 100 | Case-insensitive substring of username, email, first name, last name or "first last". `%`, `_` and `\` are matched literally. Empty is ignored. |
| `page`, `pageSize` | int | See pagination. |

Ordered by `username`, then `id`. Unknown parameters are ignored.

**`POST /users`** — body ([`CreateUserRequest`](../src/main/java/org/cce/admin/controller/dto/UserDtos.java#L52-L59)):

| Field | Rule |
|---|---|
| `username` | required, 3 to 50, `^[a-z0-9][a-z0-9._-]*$` |
| `email` | required, max 254, `@Email` |
| `firstName`, `lastName` | required, 1 to 100 |
| `facilityScope` | optional, max 50 |
| `roleIds` | required, 1 to 20 non-null UUIDs; each must exist and be listed once (400 on `roleIds[i]`) |

201 with `Location: /admin/v1/users/{id}` and the `User`. Creates the Keycloak account, or links
an existing enabled Keycloak account with the same username and email (case-insensitive) that no
local user has. Adds the warning `ACTION_EMAIL_NOT_SENT` if the set-password email failed.
409 cases: `USERNAME_IN_USE` / `EMAIL_IN_USE` (local user, or a Keycloak account that cannot be
linked, with the reason in `message`); `ROLE_NOT_ASSIGNABLE` (a role with `isAssignable=false`,
`details` per `roleIds[i]`); `ROLE_NOT_SYNCED` (no realm role yet, `details` per `roleIds[i]`).
Flow: [flow-diagrams.md](flow-diagrams.md#create-user).

**`GET /users/{id}`** — `User`.

**`PUT /users/{id}`** — body `{ email, firstName, lastName, facilityScope? }`, same rules as
create. All four are replaced, so a missing `facilityScope` clears it. No-op if unchanged.

**`PATCH /users/{id}/status`** — body `{ "status": "ACTIVE" | "INACTIVE" | "LOCKED" }`. Any
transition is allowed. No-op if unchanged.

**`PUT /users/{id}/roles`** — body `{ "roleIds": [uuid] }`, 0 to 20, the complete new set. Only
added roles are checked for `isAssignable` and for a realm role. No-op if the set is unchanged.

**`POST /users/{id}/reset-password`** — no body. Asks Keycloak to email an `UPDATE_PASSWORD` link.
Allowed for any status. `data`: `{ userId, email }`.

### Roles

**`GET /roles`** — `page`, `pageSize`. Ordered by `name`, then `id`.

**`POST /roles`** — body ([`CreateRoleRequest`](../src/main/java/org/cce/admin/controller/dto/RoleDtos.java#L68-L72)):
`name` required, 2 to 100, `^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$`; `displayName` required, 1 to 100;
`description` optional, max 500. 201 with `Location`. 409 `DUPLICATE_ROLE_NAME` if the name is
used in `roles`, in `api_permissions.permission_name`, or as a Keycloak realm role. The response
carries `ROLE_SYNC_FAILED` if the first sync failed (`syncStatus: FAILED`).

**`GET /roles/{id}`** — `Role`.

**`PUT /roles/{id}`** — body `{ displayName, description? }` (`name` is an unknown property → 400).
No-op if unchanged. 409 for system roles.

**`DELETE /roles/{id}`** — 204. 409 `SYSTEM_ROLE_IMMUTABLE` for system roles; 409 `ROLE_IN_USE`
if any local user (any status) holds it, or any Keycloak user is a direct member of the realm
role. Deletes its `role_menus` and all its `api_permissions` rows. No gateway sync.

**`GET /roles/{id}/menus`** — `RoleMenus`, including inactive granted menus.

**`PUT /roles/{id}/menus`** — body `{ "menuIds": [uuid] }`, 0 to 500, each existing and listed
once (400 on `menuIds[i]`). Inactive menus may be granted. 409 for system roles.

**`GET /roles/{id}/permissions`** — `RolePermissions`.

**`PUT /roles/{id}/permissions`** — body `{ "permissions": [ { httpMethod, uriPattern, description?, resourceCategory? } ] }`,
0 to 200 rows, the role's complete set of explicit rows. Rules
([`PermissionRowRules`](../src/main/java/org/cce/admin/service/PermissionRowRules.java)):

| Field | Rule (400 on `permissions[i].<field>`) |
|---|---|
| `httpMethod` | required; any case; must be `GET`, `POST`, `PUT`, `PATCH` or `DELETE`; stored upper case |
| `uriPattern` | required, 1 to 255; starts with `/`; no `//`; only letters, digits and `- . _ ~ / *`; `**` only as the whole last segment |
| `description` | optional, max 1000 |
| `resourceCategory` | optional, max 50 |

Rows equal to an implicit row are dropped silently. A second row with the same method and
pattern gives 400 on the later row's `uriPattern`. No-op (and no sync) if the explicit rows are
unchanged on all four fields. Otherwise the rows are saved, then the gateway sync runs: 200 on
success, 502 `ROLE_PERMISSIONS_SAVE` (rows kept) on failure. 409 for system roles.

**`POST /roles/{id}/sync`** — no body. Runs one gateway sync for the role. Allowed for system
roles. 200 `RoleSyncResult`, or 502 `ROLE_SYNC`.

### Menus

**`GET /menus`** — top-level menus with `children`, including inactive menus.

**`POST /menus`** — body ([`CreateMenuRequest`](../src/main/java/org/cce/admin/controller/dto/MenuDtos.java#L46-L57)):

| Field | Rule |
|---|---|
| `key` | required, max 100, `^[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)*$`, unique (409 `DUPLICATE_MENU_KEY`) |
| `label` | required, 1 to 100 |
| `path` | required, 1 to 255, `^/[A-Za-z0-9/_.:-]*$` |
| `icon` | optional, max 100, `^[A-Z][A-Za-z0-9]*Icon$` |
| `type` | required, `NAV` or `DETAIL` |
| `parentId` | `NAV`: must be absent. `DETAIL`: required, must be an existing `NAV` menu. Else 400 on `parentId`. |
| `sortOrder` | required, 0 to 10000 |
| `isActive` | optional, default `true` |

**`PUT /menus/{id}`** — body `{ label, path, icon?, parentId?, sortOrder, isActive }` (`isActive`
required). `key` and `type` are unknown properties (400). A missing `icon` or `parentId` clears
it. Parent rules as for create, plus "cannot be its own parent". No-op if unchanged.

**`DELETE /menus/{id}`** — 204. 409 `MENU_HAS_CHILDREN` while any menu has it as parent. Deletes
the menu's `role_menus` rows.

## Error codes

From [`ErrorCode.java`](../src/main/java/org/cce/admin/service/exception/ErrorCode.java).

| HTTP | Code | Produced when |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Bean Validation on body, query or path; unknown body property (`Unknown property.`); wrong JSON type or bad enum (`Invalid value.`); body not JSON or missing (`field: "body"`); unsupported `Content-Type` (`field: "body"`); unknown/duplicate `roleIds[i]` or `menuIds[i]`; bad `parentId`; permission row rules. |
| 401 | `UNAUTHENTICATED` | Missing, malformed, wrongly signed, expired, wrong issuer or wrong audience token. `WWW-Authenticate` is kept. |
| 403 | `FORBIDDEN` | No local user for `sub`, or not `ACTIVE`; `sub` not a UUID; no matching permission row (`Your roles do not allow {METHOD} {path}.`). |
| 404 | `USER_NOT_FOUND`, `ROLE_NOT_FOUND`, `MENU_NOT_FOUND` | Id in the path does not exist. |
| 404 | `NOT_FOUND` | No endpoint for the path. |
| 405 | `METHOD_NOT_ALLOWED` | Path exists, method does not. |
| 409 | `USERNAME_IN_USE`, `EMAIL_IN_USE` | Taken locally, or by a Keycloak account that cannot be linked, or a Keycloak 409 race. `EMAIL_IN_USE` also on a Keycloak 409 during profile update. |
| 409 | `DUPLICATE_ROLE_NAME` | Name in `roles`, in `api_permissions`, or as a realm role. |
| 409 | `DUPLICATE_MENU_KEY` | Menu key exists. |
| 409 | `ROLE_IN_USE` | Delete of a role held locally or in Keycloak. |
| 409 | `SYSTEM_ROLE_IMMUTABLE` | `PUT /roles/{id}`, `PUT …/menus`, `PUT …/permissions`, `DELETE` on an `is_system` role. |
| 409 | `LAST_SYSTEM_ADMIN` | Status or role change that would leave no `ACTIVE` `SUPER_ADMIN`. |
| 409 | `ROLE_NOT_ASSIGNABLE` | Assigning a role with `is_assignable = false` (`CCE_ADMIN_SERVICE`). |
| 409 | `ROLE_NOT_SYNCED` | Assigning a role with no Keycloak realm role. |
| 409 | `MENU_HAS_CHILDREN` | Delete of a menu with children. |
| 502 | `KEYCLOAK_SYNC_FAILED` | A Keycloak call or the gateway sync failed. Carries `partialState`. |
| 500 | `INTERNAL_ERROR` | Anything else. Generic message; stack trace logged only. |

Differs from the design (§4.2), in a race only: the `DUPLICATE_*`, `USERNAME_IN_USE` and
`EMAIL_IN_USE` checks are "check, then insert". Two concurrent creates with the same menu key or
role name pass the check, and the loser hits a unique constraint. There is no handler for
`DataIntegrityViolationException`, so that request gets 500 `INTERNAL_ERROR`, not 409. For
`POST /users`, Keycloak's own 409 on create usually catches the race first (→ 409); when it does
not (for example two requests linking the same existing account), the loser fails at
`LOCAL_USER_SAVED` with a 502.

### `partialState` operations and steps

| `operation` | Endpoint | Step names (`failedStep`, `completedSteps`) |
|---|---|---|
| `USER_CREATE` | `POST /users` | `KEYCLOAK_ACCOUNTS_CHECKED`*, `KEYCLOAK_REALM_ROLES_CHECKED`*, `KEYCLOAK_USER_CREATED`, `KEYCLOAK_ROLES_ASSIGNED`, `LOCAL_USER_SAVED` |
| `USER_PROFILE_UPDATE` | `PUT /users/{id}` | `KEYCLOAK_PROFILE_UPDATED`, `LOCAL_PROFILE_SAVED` |
| `USER_STATUS_CHANGE` | `PATCH /users/{id}/status` | `KEYCLOAK_ENABLED_CHANGED`, `KEYCLOAK_SESSIONS_ENDED`, `LOCAL_STATUS_SAVED` |
| `USER_ROLES_UPDATE` | `PUT /users/{id}/roles` | `KEYCLOAK_REALM_ROLES_CHECKED`*, `KEYCLOAK_ROLES_ADDED`, `KEYCLOAK_ROLES_REMOVED`, `LOCAL_ROLES_SAVED` |
| `USER_PASSWORD_RESET` | `POST /users/{id}/reset-password` | `KEYCLOAK_ACTION_EMAIL_SENT` |
| `ROLE_CREATE`* | `POST /roles` | `KEYCLOAK_REALM_ROLE_CHECKED`* |
| `ROLE_DELETE`* | `DELETE /roles/{id}` | `KEYCLOAK_ROLE_MEMBERS_CHECKED`* |
| `ROLE_PERMISSIONS_SAVE` | `PUT /roles/{id}/permissions` | `PERMISSIONS_SAVED`, `GATEWAY_SYNC` |
| `ROLE_SYNC` | `POST /roles/{id}/sync` | `GATEWAY_SYNC` |

\* Not named by the design (§4.2, §5); added in code for a Keycloak lookup that fails before
anything changed. Those 502s have `completedSteps: []`, `keycloakUserId: null` and
`auditLogId: null`.

`keycloakUserId` is the Keycloak account on every 502 after the account exists (create steps 5
and 6; every Keycloak write in profile, status and roles flows). `auditLogId` is the failure row
or the `ROLE_SYNC_REQUESTED` row, or `null` when none was written. Each 502 is logged at `ERROR`
as `event=keycloak_sync_failed operation=… entityId=… failedStep=… completedSteps=… keycloakUserId=… auditLogId=…`
([`ApiExceptionHandler.java:55-66`](../src/main/java/org/cce/admin/controller/ApiExceptionHandler.java#L55-L66)).

## OpenAPI document

`GET /admin/v1/api-docs` (JSON) and `/admin/v1/swagger-ui` are generated by springdoc 2.10.0
with the title `cce-admin-service`, version `v1` and a global `bearerAuth` (HTTP bearer, JWT)
requirement; `/health` overrides it with no security. All 24 endpoints above appear, with the
request schemas and their validation limits. The test `OpenApiDocumentTest` checks the list
against `src/test/resources/contract/endpoints.txt`.

Gaps in the generated document (the code is right, the document is not):

- `POST /users`, `POST /roles` and `POST /menus` are documented as `200`; they return `201`.
- `DELETE /roles/{id}` and `DELETE /menus/{id}` are documented as `200`; they return `204`.
- No error responses and no error-envelope schema are documented.
- `Warning` shows `menuId`/`parentMenuId` as plain optional fields; it does not say they appear
  only for `DETAIL_WITHOUT_PARENT`.
