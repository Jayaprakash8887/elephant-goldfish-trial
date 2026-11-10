# Pages & Wireframes

Every screen in `cce-admin-ui`, v1. Routes, purpose, and the `cce-admin-service`
endpoints each screen calls (full contracts in
[cce-admin-service/api-reference.md](../../cce-admin-service/docs/api-reference.md); the
sidebar itself is static — see
[architecture-overview.md §3](architecture-overview.md#3-this-apps-own-access-control)
for why).

## App Shell

Persistent sidebar (Users, Roles, Menus) + header showing the signed-in admin's name and
roles, from `GET /me`. Not a routed page itself; wraps every screen below.

## 1. Users list — `/users`

- Table of users: username, email, status (`ACTIVE`/`INACTIVE`/`LOCKED`), roles,
  facility scope, last login.
- Filters: status, role, facility scope, free-text search (name/email).
- Row actions: view/edit, activate/deactivate, reset password.
- "Create user" button → User Create form.
- Data: `GET /users` (paginated).

## 2. User create / edit — `/users/new`, `/users/:id`

- Form fields: username, email, first name, last name, facility scope (optional),
  role(s) (multi-select from the role catalog).
- Create mode also provisions the Keycloak account in the same submit (see
  [cce-admin-service/flow-diagrams.md §2](../../cce-admin-service/docs/flow-diagrams.md#2-admin-onboards-a-new-user)) —
  the form has no separate "sync to Keycloak" step, the backend does this atomically
  per-request.
- Edit mode: profile fields via `PUT /users/:id`; role changes via `PUT /users/:id/roles`
  (replaces the full role set — the form diffs and submits the complete new set, not a
  delta); status changes via `PATCH /users/:id/status`; a "Reset password" action calls
  `POST /users/:id/reset-password`.
- On `502 KEYCLOAK_SYNC_FAILED`, shows the dedicated error state described in
  [architecture-overview.md §6](architecture-overview.md#6-non-functional-notes) rather
  than a generic failure toast.

## 3. Roles list — `/roles`

- Table of roles: name, display name, system flag (`is_system` roles show a lock icon
  and can't be deleted/renamed), user count.
- "Create role" button → inline form (name, display name, description). `name` is
  permanent after creation — the form warns before submit, since it becomes the
  Keycloak realm role name and the `api_permissions.permission_name` value (see
  [cce-admin-service/security-model.md §4](../../cce-admin-service/docs/security-model.md#4-one-identifier-role--realm-role--permission_name)).
- Data: `GET /roles`.

## 4. Role detail — `/roles/:id`

Two tabs over the same role, because they're independent axes on the backend (see
[cce-admin-service/security-model.md §5](../../cce-admin-service/docs/security-model.md#5-menus-vs-permissions-two-axes-one-role))
— this screen is where an admin is expected to configure both together, per
[cce-admin-service/flow-diagrams.md §3](../../cce-admin-service/docs/flow-diagrams.md#3-admin-configures-a-role):

- **Menus tab**: checkbox tree of the `cce-insights-ui` menu catalog (`GET /menus` for
  the tree shape, `GET /roles/:id/menus` for current selection). Selecting a `DETAIL`
  entry without its parent `NAV` entry shows the same validation warning the backend
  raises. Save → `PUT /roles/:id/menus`.
- **Permissions tab**: table of `(HTTP method, URI pattern, description, resource
  category)` rules for this role, add/remove rows, save → `PUT /roles/:id/permissions`.
  Save also triggers the backend's Keycloak sync
  (`POST /roles/:id/sync`, called automatically per
  [cce-admin-service/api-reference.md](../../cce-admin-service/docs/api-reference.md#roles));
  the tab shows a "synced" / "sync failed, retry" indicator rather than assuming success.
  A manual "Re-sync" button is exposed for recovering from a gateway-side incident
  without re-saving permissions.

## 5. Menus catalog — `/menus`

- Read-mostly tree view of the full menu catalog (label, path, icon, type, active flag).
  Per [cce-admin-service/data-dictionary.md §3](../../cce-admin-service/docs/data-dictionary.md#3-menus),
  these rows change only when `cce-insights-ui` itself gains a screen, so this page is
  used rarely — add/edit/deactivate a menu entry, not a day-to-day workflow.
- Data: `GET /menus`, mutations via `POST /menus` / `PUT /menus/:id` / `DELETE /menus/:id`.

## 6. Profile (header, not a page)

`GET /me` result (username, roles, facility scope) shown in the app shell's header — no
dedicated route; it's context available on every screen, not a screen of its own.

## Not in v1

- **Audit log viewer** — `cce-admin-service` writes `audit_log` but exposes no read
  endpoint yet (see
  [architecture-overview.md §6](architecture-overview.md#6-non-functional-notes)); a
  screen here is blocked on that backend addition.
- **Global API-permission catalog** — permissions are only ever viewed/edited per-role
  (Role Detail's Permissions tab), matching the backend's API shape, which has no
  standalone `GET /permissions` listing across roles.
