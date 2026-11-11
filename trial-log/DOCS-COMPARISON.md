# Docs comparison: generated from the code vs the team's docs

**Date:** 2026-11-11.

This file compares two doc sets for each repository:

- **Generated docs:** `cce-admin-service/docs/` and `cce-admin-ui/docs/`. Fresh agents wrote them
  from the trial's code alone. They did not see the team's docs.
- **Team docs:** the `docs/` folders of `Jayaprakash8887/cce-admin-service` (commit `0c9c06f`)
  and `Jayaprakash8887/cce-admin-ui` (commit `8a6d49c`), last pushed 2026-09-29. They are a
  design written before any code existed.

Both sets use the same six file names in each repository, so they compare file by file.

## Summary

- **The console docs largely agree.** The stack, the Keycloak client, the env variables, the
  port, the static sidebar, the 502 error state and the Caddy set-up are the same. The generated
  set adds what only code can show: exact error handling, page states and wireframes, lint
  rules, and the Docker check.
- **The service docs differ in substance.** Several of the team docs' decisions would fail against
  the real gateway or Keycloak, and the trial's design changed each one. The largest:
  - The team docs put the tables in schema `admin_service`. The gateway reads `public`.
  - The team docs define `api_permissions` with UUID ids and other types. The gateway's own
    migration defines it differently.
  - No existing Keycloak user, which means every current `cce-insights-ui` user, could get past
    the team design's 403 or be onboarded.
  - A new role could not load its own sidebar unless an administrator also added the
    `/admin/v1/me/menus` permission by hand.
- **Each set has something the other lacks.** The team docs explain *why* (stack choices, the
  two-axes model, future extensions). The generated docs say *exactly what happens*:
  constraints, error codes, failure steps, locks. They also record defects the code has today.

## Size

| File | Team (lines) | Generated (lines) |
|---|---|---|
| **cce-admin-service** | | |
| README.md | 22 | 22 |
| architecture-overview.md | 172 | 231 |
| api-reference.md | 126 | 342 |
| data-dictionary.md | 183 | 261 |
| flow-diagrams.md | 153 (6 diagrams) | 443 (14 diagrams) |
| security-model.md | 135 | 218 |
| **cce-admin-ui** | | |
| README.md | 33 | 22 |
| architecture-overview.md | 162 | 318 |
| api-integration.md | 76 | 164 |
| pages-and-wireframes.md | 91 (no wireframes) | 446 (ASCII wireframes) |
| developer-setup.md | 57 | 193 |
| deployment-guide.md | 45 | 146 |
| **Total** | **1,255** | **2,806** |

## cce-admin-service

### Where they agree

The service's shape is the same in both sets:

- **Stack:** Java 21, Spring Boot 3 with Spring MVC, JPA, Flyway, the Keycloak admin client,
  springdoc, port 8085.
- **The service validates the JWT itself** (signature, issuer, audience), on top of the gateway.
- **Users:** a token whose `sub` has no local `ACTIVE` user gets 403.
- **One identifier:** the role name, the Keycloak realm role and `api_permissions.permission_name`
  are the same string.
- **Two axes:** menus and permissions are separate axes of a role.
- **Keycloak split:** the gateway creates realm roles through its sync endpoint, and this service
  creates users and assigns roles.
- **Shapes and base path:** the base path `/admin/v1`, the `{ data, pagination }` and
  `{ error: { code, message } }` envelopes, and 1-based paging with `pageSize` up to 100.
- **Endpoints:** the same 24 endpoints, with the same replace-the-whole-set semantics for user
  roles, role menus and role permissions, and `POST /roles/:id/sync` for a manual re-sync.
- **Seed menus:** the 12 seed menus, taken from the `cce-insights-ui` navigation, with Patients as
  its own top-level menu.

### Where they differ

The first column says which version matches the fixed inputs (the PRD, the gateway's
`DATABASE_PERMISSIONS_MIGRATION.md`, and Keycloak's behaviour).

| Topic | Team docs | Generated docs (the code) | Which holds |
|---|---|---|---|
| **Schema** | Schema `admin_service` | Database `admin_service`, schema `public` | Generated. The gateway reads `api_permissions` from `public`. Tables in schema `admin_service` would be invisible to it. EGM's Elephant found this while drafting (AD-01). |
| **`api_permissions` shape** | `id` UUID; `permission_name` VARCHAR(150); `resource_category` VARCHAR(100); `TIMESTAMPTZ` | `id` BIGSERIAL; VARCHAR(100); VARCHAR(50); `timestamp` without time zone; the unique constraint and the gateway's trigger | Generated. It matches the gateway's own migration. The team doc itself says to "confirm exact types against `gateway-service`'s own `DATABASE_PERMISSIONS_MIGRATION.md`". |
| **Who creates `api_permissions`** | This service owns the table | V1 creates it only if it is missing, V2 checks its shape exactly, and V3 seeds with `ON CONFLICT DO NOTHING`. The Flyway history table has its own name. | Generated. Flyway refuses a non-empty schema without a baseline, and the gateway may have created the table already (AD-02). |
| **Existing Keycloak users** | Not covered. User creation always creates a new Keycloak account, and a token with no local row gets 403. | `POST /users` links an existing enabled Keycloak account when both the username and the email match. A retry after a 502 finds the half-created account and completes. `BOOTSTRAP_ADMIN_USERNAME` links the first `SUPER_ADMIN` at startup. | Generated. Under the team design, every existing `cce-insights-ui` user would get 403, and creating them fails with a Keycloak 409. This was the Goldfish review's top blocker (CA-01). |
| **Permission to load one's own menus** | Not covered. A role needs an `api_permissions` row for `/admin/v1/me/menus`, or the gateway refuses the sidebar call. | Every non-system role gets two implicit rows, `GET /admin/v1/me` and `GET /admin/v1/me/menus`, kept on every save | Generated. With the team design, a role made for `cce-insights-ui` users loads no sidebar until someone adds that row by hand. |
| **Locking out every administrator** | Not covered | 409 `LAST_SYSTEM_ADMIN` on any status or role change that would leave no active `SUPER_ADMIN`, with a row lock against races | Generated. The Elephant raised it while drafting. |
| **Assigning a role before its realm role exists** | Flow 4 notes the risk and leaves it to the administrator to retry the sync | `POST /roles` syncs at once. Assigning a role with no realm role is refused with 409 `ROLE_NOT_SYNCED`. | Generated. It enforces what the team doc only warns about. |
| **Service account role** | Not covered | Seeded `CCE_ADMIN_SERVICE` role (`is_assignable = false`), holding only the gateway sync permission | Generated. The service needs a realm role to call the sync endpoint before any sync has run. |
| **`INACTIVE` users** | "Terminal state", but flow 6 keeps role rows "so re-activation restores prior access" | `INACTIVE` and `LOCKED` can both be reactivated. Role rows are kept. | The team doc contradicts itself. The PRD has the same contradiction (B-34 and S5 reactivate), and the code follows B-34. |
| **Audit table** | `actor_user_id` FK, `entity_type` includes `API_PERMISSION`, `entity_id` nullable | No FK, plus `actor_keycloak_id` and `actor_username` snapshots, `outcome` (`SUCCESS`/`FAILED`), a `failure` JSON with the failed step, `entity_id` NOT NULL. Append-only is enforced by a trigger. | Generated is stricter. It records partial failures (B-32) and survives deleted entities. |
| **Who-did-what columns** | `users.created_by`, `roles.created_by`, `user_roles.assigned_by` | None. The actor is in `audit_log`. | A design choice; neither is wrong. The team version can answer "who assigned this" without reading the audit log. |
| **Join tables** | Composite primary keys, ON DELETE CASCADE | Surrogate UUID key plus a unique pair. `user_roles` uses ON DELETE RESTRICT. | A design choice. |
| **Menu tree** | Any depth (`parent_id`) | A table check plus service rules: at most two levels, `NAV` at the top and `DETAIL` under a `NAV` | Generated is stricter. |
| **Seed menu keys** | `compliance.patients`, `compliance.patients.detail`, "Protocol Detail" | `patients`, `patients.patient-detail`, "Protocol analytics", with sort orders and Heroicons names | Naming only. A console that reads these keys must use one set. |
| **Pagination block** | `{ page, pageSize, total }` | `{ page, pageSize, totalItems, totalPages }` | Naming only. Clients written against the team doc would break. |
| **Error codes** | 12 codes | 20 codes. New: `USERNAME_IN_USE`, `LAST_SYSTEM_ADMIN`, `ROLE_NOT_ASSIGNABLE`, `ROLE_NOT_SYNCED`, `MENU_HAS_CHILDREN`, `NOT_FOUND`, `METHOD_NOT_ALLOWED`, `INTERNAL_ERROR`. `KEYCLOAK_SYNC_FAILED` carries `partialState` (the operation, the failed step and the completed steps). | Generated. It is complete for the code. |
| **Success warnings** | A `DETAIL` without its parent is "flagged as a validation warning" | A `warnings` array in the envelope: `DETAIL_WITHOUT_PARENT`, `ROLE_SYNC_FAILED`, `ACTION_EMAIL_NOT_SENT` | Generated makes the team's idea concrete. |
| **Env variable names** | `KEYCLOAK_BASE_URL`, `GATEWAY_SERVICE_URL` | `KEYCLOAK_SERVER_URL`, `GATEWAY_BASE_URL`, plus `JWT_JWK_SET_URI`, `TRUSTED_PROXIES`, `PERMISSION_CHECK_ENABLED`, `BOOTSTRAP_ADMIN_USERNAME`, timeouts | Naming. Deployment configs written from the team docs need renaming. |
| **`/health`** | Reports DB connectivity, best effort | Always `UP` with HTTP 200, even when the database is down | Team intent. The code's health check cannot see a database outage (found while writing the generated docs). |
| **Login time** | `last_login_at` "updated by a lightweight callback" | Moved forward from the token's `auth_time` on each request | Generated. Note that Keycloak omits `auth_time` in password-grant tokens. |

### Only in the team docs

- **Reasons for the stack:** why Java rather than Node, and why Spring MVC rather than WebFlux.
- **Deployment and CI:** the route through the gateway's `/admin/**`, and Jenkins CI.
- **Facility scope:** `facility_scope` is a hook for future facility-level access control.
- **Two axes:** why menus and permissions are kept as separate axes, and when to derive one from
  the other (v2).
- **Gateway authorization:** the gateway's own check, as a diagram (flow 5).

The generated docs describe these points only where the code shows them. A merged doc set
should keep the team docs' reasoning.

### Only in the generated docs

- **Request pipeline:** filter order, current-user resolution, and the permission check with
  `AntPathMatcher`.
- **Transactions and locking:** row locks held across Keycloak calls.
- **Failure handling:** every failure step for each flow, with what a retry does.
- **Startup and the local stack:** the bootstrap of the first administrator, the Docker stack
  and the gateway stand-in.
- **Code links:** line-level links into the code, and every place the code differs from the
  approved design.
- **Known weak points:**
  - Two simultaneous creates with the same name give 500, not 409.
  - The generated OpenAPI lists 200 where the API returns 201 or 204.
  - Roles come from the token, so a removed role works until the token expires (300 s locally).
  - Nothing stops a permission row for `/admin/v1/**`, so whoever can edit permissions can make
    another administrator role.

## cce-admin-ui

### Where they agree

The team's console docs and the code match closely. Every item below is the same in both:

- **Stack:** React 18, TypeScript, Vite 6, React Router 7, TanStack Query 5 and Table 8, React
  Hook Form and Zod, Tailwind 4, Heroicons 2, Vitest, Testing Library and MSW.
- **Sign-in:** `keycloak-js` with `login-required` and PKCE `S256`, using its own client
  `cce-admin-ui`. It calls `updateToken(70)` every 60 seconds and has no auth bypass flag.
- **Env variables:** the four `VITE_*` variables have the same defaults. They are set at build
  time, so changing one means a new image.
- **Ports:** the dev server and the image both use 3002. Vite proxies `/admin/v1` to the service.
- **Runtime:** a multi-stage Docker build ends on Caddy, with an SPA fallback, and proxies to
  `gateway-service`, never straight to the service.
- **Sidebar:** it is static. `GET /me` shows the admin's name and roles in the header. Nothing is
  hidden by role, and a 403 shows inline.
- **Writes:** updates are not optimistic. Role and menu edits send the full replacement set.
- **Errors:** `502 KEYCLOAK_SYNC_FAILED` has its own error state, not a generic error.
- **Screens:** Users, User create and edit, Roles (inline create form with a warning that the
  name is permanent, a lock icon and a user count), Role detail with Menus and Permissions tabs
  (a sync indicator and Re-sync), and the Menus catalog.
- **Not in v1:** an audit log viewer and a global permission catalog.

### Where they differ

| Topic | Team docs | Generated docs (the code) | Note |
|---|---|---|---|
| **Node** | Node 24 in CI, at least 20 locally | `engines` `>=20.19.0 <23`. The Docker build uses `node:20.20.2-alpine`. | Node 24 is outside the allowed range. The Elephant flagged the Node 20 / 22 / 24 conflict, and the author pinned the `node:20.20.2-alpine` image (D-05). CI on Node 24 would need the range widened. |
| **Ways to reach the API locally** | Vite proxy, direct CORS, or Caddy | Vite proxy or Caddy. The image's CSP allows only its own origin and `KEYCLOAK_ORIGIN`, so a cross-origin API would be blocked. | The direct-CORS option is gone. |
| **Runtime image settings** | None. Everything is set at build time. | `GATEWAY_UPSTREAM` (required) and `KEYCLOAK_ORIGIN` are read by Caddy at run time. Security headers and CSP are added. | Generated adds hardening. The two settings must be added to deploy manifests. |
| **Folders** | `pages, components, hooks, api, auth, schemas, context` | `pages, components, hooks, api, auth, schemas, utils, test`. No `context/` (`GET /me` goes through `useMe`). Layer rules are enforced by ESLint and tested. | Close. The layer rules are new. |
| **Users list actions** | View or edit, activate or deactivate, reset password | View or edit, one button per other status (Activate, Deactivate, Lock), "Send set-password email". Each opens a confirm dialog. | Generated is more precise. |
| **Partial failures** | A distinct error state | That state, plus a block "For the operator" with the Keycloak user id and the audit log id from `partialState` | Generated is more useful for support. |
| **Tests** | Vitest, Testing Library and MSW. Scripts: `dev`, `build`, `test`, `test:ci`, `lint`. | The same, plus coverage thresholds in `test:ci`, Prettier in `lint`, tests of the lint rules, Playwright browser tests of CUJ 2 to 6 against the real stack, and a Docker `verify` stage | Generated is broader. |
| **Wireframes** | None, despite the file name | ASCII wireframes for every main screen | |

### Only in the generated docs

The generated docs record behaviour the team docs could not know, including today's defects:

- **Lost edits on refetch:** a failed background refetch unmounts Role detail, the Menus tab or
  the Menus form, and unsaved edits are lost.
- **Retry on every error:** every failed query offers Retry, including 401 and 403.
- **Filters after Back:** the text filters keep their old text after the browser's Back button.
- **A cache header problem:** Caddy caches a missing `/assets/*` file's `index.html` for a year.
- **A header conflict:** `X-Frame-Options` and the CSP's `frame-ancestors` disagree.
- **Contract kept by hand:** `contract.ts` is copied from the service design by hand. Only the
  Playwright tests would catch drift from the real service.

## What this shows for the trial

- **The team's console plan was close to what was built.** The team's service plan was
  not. On the service side, the PRD's fixed constraints (the gateway's table and schema, and
  Keycloak's existing users) are where a plan written before reading the gateway's real
  migration goes wrong. EGM's Elephant and Goldfish reviewers caught each of these points before
  any code.
- **Docs written from code are exact, but they don't explain why.** Use the team docs'
  rationale sections as the introduction and the generated docs as the reference. Then fix the
  defects that the generated docs and the drift report list.
- **Doc formatting is checked.** `npm run lint` in `cce-admin-ui` checks `docs/` with Prettier.
  The generated console docs pass it. Five of the six team console docs fail it as written, so
  copying them in unchanged would fail the Docker `verify` stage.
