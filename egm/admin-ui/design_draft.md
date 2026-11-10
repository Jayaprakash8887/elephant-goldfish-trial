# CCE v2 Admin Console (`cce-admin-ui`)

*   **Author:** jayaprakash8887
*   **Status:** Draft
*   **Date:** 2026-11-10

--------------------------------------------------------------------------------

## Overview

`cce-admin-ui` is a new React single-page application that platform administrators use to
manage who can do what in CCE v2. It covers PRD milestones F1 to F5: requirements F-01 to F-15
and the environment and scripts in §8.3 of the
[PRD](../../context/prd/CCE_Admin_Module_PRD.md).

The console has four areas: a signed-in shell, Users, Roles (with a Menus tab and a Permissions
tab), and the Menus catalog. It holds no data of its own. Every read and write goes to
`cce-admin-service` under `/admin/v1`, with a Keycloak access token. In shared environments the
calls pass through `gateway-service`. During local development the Vite dev server sends them
straight to the service on `localhost:8085` ([D-03](../../docs/designs/author-decisions.md)).
Until that service exists, the console is built and tested against MSW mocks.

The stack follows the existing analytics console, `cce-insights-ui`
([architecture](../../context/cce-insights-ui/docs/architecture-overview.md),
[developer setup](../../context/cce-insights-ui/docs/developer-setup.md),
[deployment](../../context/cce-insights-ui/docs/deployment-guide.md)): React 18, TypeScript 5,
Vite 6, React Router 7, TanStack Query 5 and Table 8, Tailwind CSS 4, `keycloak-js` 26, React
Hook Form 7 with Zod 3, and Vitest with Testing Library and MSW (PRD §6). Every package and
image is pinned to the exact version the author checked and recorded as
[D-05](../../docs/designs/author-decisions.md#d-05-pinned-versions-checked-on-2026-11-10).
Where this console departs from that template, the departure comes from the PRD and is listed
under [Conflicts between inputs](#conflicts-between-inputs).

The console uses the API contract. It does not define it. Under
[D-01](../../docs/designs/author-decisions.md), the
[admin-service design](../admin-service/design_draft.md) owns every request and response shape,
error code, pagination block and warning. That contract is complete and approved by the author:
the admin-service design's Technical Plan
[§3](../admin-service/design_draft.md#3-request-pipeline-and-security-b-05-b-10-to-b-13)
(request pipeline), [§4](../admin-service/design_draft.md#4-api-contract) (conventions, error
codes, pagination, resources, endpoints, and its answers to this design's requirements in §4.6)
and [§9.3](../admin-service/design_draft.md#93-values-commands-and-reset) (local values). This
document refers to those sections by number ("admin-service §4.4") and does not copy the shapes,
except where a screen needs one field named.

## The Problem

Today there is no screen for access control in CCE. `cce-insights-ui` shows every signed-in
user the same sidebar, and the gateway's permission rules live in a database table
(`api_permissions`) that someone has to edit with SQL. Giving a person access means changing
three systems by hand: a Keycloak account and its realm roles, the gateway's permission rows,
and (once the admin service exists) the role-to-menu mapping. Each of these hand edits can go
wrong without anyone noticing (PRD §2):

*   **Permissions are a security control.** A wrong `api_permissions` row lets a role call an
    API it should not, or blocks one it needs. Writing these as SQL gives no validation and no
    record of who changed what.
*   **One name is used in three places.** A role's name is also its Keycloak realm role name and
    its `permission_name` in `api_permissions` (PRD §4). A typo or rename breaks the link between
    them, so the name can never change after creation, and the admin has to be told that before
    submitting.
*   **Writes can half-succeed.** Creating a user or saving permissions touches the database,
    Keycloak and the gateway in sequence. If a later step fails, earlier steps stay applied
    (PRD §9). A console that shows a generic "something went wrong", or that updates the screen
    before the server confirms, hides that partial state from the one person who can fix it.
*   **The order of operations matters.** A role's realm role has to exist in Keycloak (created by
    the gateway sync) before the role can be assigned to a user (PRD §9, scenario S6). An admin
    who does not see that a sync failed will try to assign a role that Keycloak does not have
    yet.
*   **Menus and permissions are separate.** Showing a role the Compliance screen does not let it
    call the compliance APIs, and the reverse (PRD §4). If they are edited in different places,
    admins configure one and forget the other.

The proposed solution is a console that puts each of these steps behind a form that validates
input before it is sent, sends the complete intended state on every save, waits for the server
before showing a change, and shows partial failures with a specific message and a specific next
step.

## Goals

Each goal is checked by the milestone exit checks in PRD §10 and the definition of done in
PRD §11.

1.  **Every frontend requirement is met.** F-01 to F-15 and the §8.3 variables and scripts meet
    their PRD acceptance criteria. The exit checks for milestones F1 to F5 are shown as listed in
    [How each milestone exit check is shown](#how-each-milestone-exit-check-is-shown).
2.  **An administrator can carry out scenarios S2 to S6 from the console alone.** Onboarding a
    user, creating and configuring a role, changing a user's roles, deactivating and
    re-activating a user, and recovering from a failed gateway sync need neither SQL nor the
    Keycloak admin console.
3.  **No partial failure is hidden.** A `KEYCLOAK_SYNC_FAILED` response (HTTP 502) always shows
    one dedicated component, `SyncFailedNotice`, on the screen the admin-service contract names
    for its operation. It says what was saved, what was not, and what to do next. It is never a
    generic toast (F-05).
4.  **The screen only shows what the server confirmed.** There are no optimistic updates. Every
    write button shows a pending state until the server answers, and the affected queries are
    refetched afterwards (F-06).
5.  **An authorization failure stays on the screen that caused it.** A `FORBIDDEN` (403) is
    shown inline where the call was made. It does not replace the page with a global error
    page (F-04).
6.  **The console and its mocks cannot drift apart from the contract without a type error.**
    All request and response shapes live in one TypeScript module that mirrors the
    admin-service contract. The API client and every MSW handler are typed from that module, so
    a change to a shape breaks `npm run build` until both are updated.
7.  **Signing in cannot be bypassed.** No build of the console renders a page without a
    Keycloak session. There is no runtime flag and no static-token fallback (PRD §6, §9, F-01).
8.  **It builds and passes its tests in both places it runs.** `npm run build`, `npm run lint`
    and `npm run test:ci` pass on Node 22.22 (this machine) and inside the reference Docker
    build on `node:20-alpine`, pinned to `node:20.20.2-alpine` (PRD §6, §11, D-05).
9.  **Scenarios S2 to S6 are covered by automated browser tests against the real service.**
    CUJ 2 to CUJ 6 run as Playwright tests against the real local stack: the admin-service
    design's M0 (Keycloak, Postgres, Mailpit and the stand-in gateway in Docker) plus the
    service on `localhost:8085`, with the console on the Vite dev server at `localhost:3002`.
    Sign-in goes through the real Keycloak login page. The tests run with `npm run e2e`. They
    are not part of `test:ci`, because they need the stack running. This is how the PRD's E2E
    milestone (§10) is shown, and it gives the scenarios regression coverage that a manual run
    does not.

    Playwright is an addition to the PRD's testing stack (PRD §6 lists Vitest, Testing Library
    and MSW). It is a dev-only dependency. Reason: the E2E milestone needs a browser driver to
    show that the console works against the real service, and MSW tests only show that it
    works against the mocks. See conflict C-09.

## Non-Goals

*   **Anything the PRD defers from CCE v2** (PRD §2): an audit-log viewer, a global
    API-permission catalog across roles, enforcing `facility_scope` (the console stores and shows
    it only), deriving permissions from menus, hard-deleting users, optimistic UI, and a sidebar
    in the admin console that changes with the caller's roles. The admin console's sidebar is
    static (Users, Roles, Menus). Hiding links the caller cannot use is a noted follow-up
    (PRD §12).
*   **The role-aware sidebar in `cce-insights-ui`** (scenario S1). This console edits the menu
    data that S1 reads, but the change that renders that sidebar from `GET /me/menus` is in the
    `cce-insights-ui` repo (PRD §12).
*   **The API contract.** Request and response shapes, error codes, pagination and warnings are
    defined by the admin-service design (D-01). This design does not invent them.
*   **The local environment.** The Keycloak realm and clients, Postgres, Mailpit, the stand-in
    gateway, ports and the test administrator are defined by the admin-service design's
    milestone M0 (D-03, admin-service §9). This design only states which of those values the
    console reads.
*   **Detecting concurrent edits.** No resource has a version or ETag, and the last save wins
    (admin-service §4.1). The console does not detect conflicting edits.
*   **Deleting menus.** The service offers `DELETE /menus/:id` (B-44), but F-15 asks only for
    add, edit and deactivate. Deleting a menu also deletes every role's grant of it, which a
    later re-activation cannot restore. The console does not offer it.
*   **Translations.** The console is English only.
*   **Browser tests for S1, or run in CI.** S1 lives in `cce-insights-ui` (see above), and the
    admin-service design checks `GET /me/menus` at API level. `npm run e2e` is not run by
    `test:ci`, the Docker build or Jenkins, because each of those would need the full local
    stack. Error paths that the real stack cannot produce on demand (for example, a 403 from the
    gateway, which is absent locally, or a Keycloak failure part-way through user creation) are
    covered by MSW tests only.

## Conflicts between inputs

The inputs disagree in the places below. Each conflict is written down here with the author's
decision, or with the design that owns it. None was resolved silently.

| ID | Conflict | Decision |
|---|---|---|
| C-01 | `cce-insights-ui` has a build-time `VITE_AUTH_ENABLED` flag and a `VITE_AUTH_TOKEN` static-token fallback ([developer setup §3](../../context/cce-insights-ui/docs/developer-setup.md)). The PRD says the admin console has "no auth-bypass flag" and "never bypasses auth" (§6, §9, F-01). | The PRD applies. The console has neither the flag nor the fallback. Tests replace the auth module instead (gate TP-1, Technical Plan). |
| C-02 | The template defaults the realm to `cce`. PRD §8.3 marks `VITE_KEYCLOAK_REALM` as required with no default. | The PRD applies. A missing value stops the production build and start-up with a visible error. There is no fallback. The local value is `cce` (admin-service §9.3). |
| C-03 | The PRD's Docker build uses `node:20-alpine` (§6). The template's CI uses Node 24 ([deployment guide](../../context/cce-insights-ui/docs/deployment-guide.md)). This machine has Node 22.22 and npm 10.9. | The reference build is the PRD's Docker image on `node:20-alpine`, pinned to `node:20.20.2-alpine` (D-05). The code must also build and pass its tests on Node 22.22. Node 24 is not a target. |
| C-04 | The template names its one-shot test script `test:run`. PRD §8.3 names it `test:ci`. | The PRD names apply: `dev`, `build`, `test`, `test:ci`, `lint`, plus `e2e` (C-09). |
| C-05 | PRD §5 and §9 say the console reaches the service only through `gateway-service`. D-03 has the local Vite proxy forward `/admin/v1/*` straight to `localhost:8085`. | Both hold, in different environments. Locally there is no gateway. The service applies the same `api_permissions` check itself (admin-service AD-17), so locally a 403 comes from the service: a caller with no local user, a user who is not `ACTIVE` (B-12), or a role without a matching permission row. A 403 from the gateway is covered by MSW tests. |
| C-06 | PRD §2 says deactivation (`INACTIVE`) "is terminal". Scenario S5, B-34 and F-10 say an inactive user can be activated again. Appendix A also lists `LOCKED`. | Decided in the admin-service design (AD-04, DEV-4): `ACTIVE`, `INACTIVE` and `LOCKED`, and every change between them is allowed. The console offers each change. |
| C-07 | PRD §1 calls the team's design documents the source of truth. In this trial those documents are not inputs, and the approved designs in `docs/designs/` take their place ([PROVENANCE](../../context/PROVENANCE.md)). | This design and the admin-service design replace the team documents for the trial. Where the PRD says "per the API reference", the admin-service design is meant. |
| C-08 | The gateway's sample permission rows use URI patterns such as `/admin/users/*` ([gateway migration doc](../../context/gateway-service/DATABASE_PERMISSIONS_MIGRATION.md)). The admin API's base path is `/admin/v1` (PRD Appendix A), and PRD §12 writes `/admin/v1/**`. | The admin-service contract has no prefix rule, because patterns for other services (for example `/v1/insights/**`) are valid (admin-service §4.4). The console's placeholder text and test data use `/admin/v1/...` and `/v1/insights/...`. |
| C-09 | PRD §6 lists the console's testing stack as Vitest, Testing Library and MSW, and PRD §10 describes the E2E milestone as running the scenarios against the real service, without saying how. | The author adds Playwright as a dev-only dependency with its own script, `npm run e2e`, for CUJ 2 to CUJ 6 (Goal 9). A manual run gives no regression coverage. S1 stays out of this console's tests. |

## Deviations from the PRD

These are places where this design does not follow the PRD as written. PRD §11 asks that each
deviation be raised with the team as a doc change, together with the admin-service design's
deviations.

| ID | PRD text | This design | Why |
|---|---|---|---|
| UI-DEV-1 | §6: "Folder layout: `pages/ components/ hooks/ api/ auth/ schemas/ context/`." §10, F1 notes: "Build `auth/` first (init, `getToken()`, context)". | No `src/context/` folder and no React auth context. `src/auth/` exposes `useAuth` (sign-out) next to `keycloak.ts`. A `src/utils/` folder, as in `cce-insights-ui`, holds the pure helpers: `describeError`, `formErrors`, `format`, `menuTree` and `permissions`. Decided by the author at finalization. | The console has no cross-page client state beyond what TanStack Query already caches (`/me` included), so a `context/` folder would be empty or ceremonial. `auth/` exposing `useAuth` meets the F1 note's "context". Moving the pure helpers into PRD-named folders would put them where their names mislead, and placing a helper used by components under `hooks/` would break the rule that components never import `hooks/`. |

## Open Questions

### Requirements on the admin-service design: all answered

This design put 24 requirements on the admin-service design. All are answered in its
[§4.6](../admin-service/design_draft.md#46-answers-to-the-console-designs-requirements), and
this design uses those answers. The table records what was asked and what the console does as
a result.

| Item | What the console asked | Answer (admin-service) | What the console does |
|---|---|---|---|
| AS-REQ-01 | Per-field validation details; every error code | §4.1, §4.2 | Maps `details[].field` to form fields. Handles every code in §4.2. |
| AS-REQ-02 | Pagination block | §4.3 | Pages are 1-based; uses `totalPages` and `totalItems`. |
| AS-REQ-03 | Which lists are paginated | §4.3 | Server-side paging for Users and Roles; `useAllRoles` walks every role page. |
| AS-REQ-04 | User fields and last login | §4.4, AD-05 | Users list columns; "Never" when `lastLoginAt` is null. |
| AS-REQ-05 | Create-user fields and first password | §4.5, AD-08 | One `POST`; tells the admin a set-password email was sent, or shows `ACTION_EMAIL_NOT_SENT`. |
| AS-REQ-06 | What a 502 on create contains | §4.2 | `SyncFailedNotice` shows `partialState`; locks resubmission when steps completed. |
| AS-REQ-07 | Status values and transitions | AD-04 | Offers Activate, Deactivate and Lock; shows `LAST_SYSTEM_ADMIN` inline. |
| AS-REQ-08 | Facility scope type and filter | AD-09 | Free-text input, up to 50 characters; exact-match filter. |
| AS-REQ-09 | What search matches | §4.5 | One search box, sent as `q`. |
| AS-REQ-10 | Assigning an unsynced role; persistent sync state | AD-06, AD-10 | Shows `syncStatus` and `lastSyncAt`; shows `ROLE_NOT_SYNCED` per role. |
| AS-REQ-11 | Role fields and name rule | §4.4 | Zod mirrors the name pattern and lengths. |
| AS-REQ-12 | What `SYSTEM_ROLE_IMMUTABLE` blocks | AD-07 | System roles are read-only except Re-sync. |
| AS-REQ-13 | Menu fields, icon format, tree depth | §4.4 | Two-level tree; icon names mapped to Heroicons components. |
| AS-REQ-14 | How a menu is deactivated | §4.5 | `PUT /menus/:id` with `isActive: false`. |
| AS-REQ-15 | Inactive menus in a role's set | §4.5 | Inactive menus are shown, marked, and can be granted. |
| AS-REQ-16 | Menu-set request and warning shape | §4.1, §4.5 | Sends `{ menuIds }`; shows `DETAIL_WITHOUT_PARENT` warnings. |
| AS-REQ-17 | Permission row fields and rules | §4.4, §4.5, AD-16 | Zod mirrors the rules; implicit rows shown read-only and never sent. |
| AS-REQ-18 | Permission save outcomes | §4.5, AD-06 | Refetches rows after a 502. |
| AS-REQ-19 | Action endpoint responses | §4.5 | Shows the reset-password email address; 204 on role delete. |
| AS-REQ-20 | `GET /me` fields | §4.4 | Header shows `username` and `roles`. |
| AS-REQ-21 | Concurrent edits | §4.1 | Last write wins; no conflict handling. |
| AS-REQ-22 | Local Keycloak URL, realm, audience, test administrator | §9.3, AD-15 | `.env.example` holds the four local values. |
| AS-REQ-23 | Making the local sync fail on demand | AD-11, §9.3 | CUJ 4 flips `http://localhost:8090/_stub/mode`. |
| AS-REQ-24 | Reset and test credentials | §9.3, AD-15 | Browser tests read `CCE_TEST_ADMIN_USERNAME` and `CCE_TEST_ADMIN_PASSWORD`. |

### Points about the contract raised by this design (decided)

*   **CQ-1: Allowed HTTP methods (decided by the author).** The admin-service draft read the
    allowed permission methods from a setting, `PERMISSION_ALLOWED_METHODS`, that no client
    could read, so the console could offer a method that a save would reject. The author
    decided that the allowed methods are fixed in the contract as `GET`, `POST`, `PUT`, `PATCH`
    and `DELETE`, not configurable. The admin-service design drops the setting. The console
    offers exactly those five.
*   **CQ-2: What "synced" means at the gateway (accepted by the author).** The gateway doc says
    permissions are loaded only at gateway start-up. Until the gateway owners answer
    admin-service GW-2, a `SYNCED` status means "the sync call succeeded", not "the gateway
    enforces the new rows". The Permissions tab's help text says exactly that: "Synced means
    the gateway's sync call succeeded."

### Decisions taken during drafting and accepted by the author

These were decided during drafting without a gate and then accepted by the author:
*   The console does not delete menus (F-15 asks for add, edit and deactivate; see Non-Goals).
*   The menu icon field is a select of 15 named icons, with a fallback for unknown names.
*   CUJ 2's browser test checks Mailpit for the set-password email.
*   Browser tests create their starting data through the API with a `cce-e2e` token.
*   Browser tests run one at a time.
*   The Dockerfile has a `verify` stage that runs lint and tests on Node 20.

## Critical User Journeys (CUJs)

### CUJ 1: Sign in and see the shell (F-01, F-03)

*   **Given**: The console is built with a valid realm and client id, and the administrator has
    a Keycloak account with an `ACTIVE` local user.
*   **When**: The administrator opens any console URL without a Keycloak session.
*   **Then**: The browser goes to Keycloak before any page renders, using Authorization Code
    with PKCE (S256). After sign-in it returns to the URL first requested. The header shows the
    username and role names from `GET /me`. The sidebar shows Users, Roles and Menus whatever
    `/me` returns. The token is refreshed every 60 seconds if it expires within 70.

### CUJ 2: Onboard a user (S2, F-11, F-05)

*   **Given**: The administrator is on `/users/new`, and at least one assignable, synced role
    exists.
*   **When**: They fill in username, email, first and last name, optionally facility scope, pick
    one or more roles and submit once.
*   **Then**: Invalid fields are flagged before anything is sent. On 201 the console opens the
    new user and says a set-password email was sent to the user's address. If the response
    carries `ACTION_EMAIL_NOT_SENT`, it says the email was not sent and to use Reset password.
    `USERNAME_IN_USE` and `EMAIL_IN_USE` show on their fields. `ROLE_NOT_SYNCED` and
    `ROLE_NOT_ASSIGNABLE` name the roles concerned under the role list. On
    `KEYCLOAK_SYNC_FAILED` (`USER_CREATE`), `SyncFailedNotice` says what was created and that an
    operator reconciles it; if any step completed, submit stays disabled. The submit button is
    disabled while the request is pending.

### CUJ 3: Create and configure a role (S3, F-12, F-13, F-14)

*   **Given**: The administrator is on `/roles`.
*   **When**: They create a role with the inline form, open it, select menus on the Menus tab
    and save, then add permission rows on the Permissions tab and save.
*   **Then**: The create form rejects names that do not match the role-name rule and warns,
    before submit, that the name is permanent. After Confirm, the role is created and synced,
    and the console opens it. The Permissions tab shows the role's two implicit rows read-only.
    Each tab's Save is disabled until the selection differs from what was last fetched, and it
    sends the complete set. Granting a `DETAIL` menu without its parent shows the warning. A
    successful permission save shows the role as synced, with the time.

### CUJ 4: Recover from a failed gateway sync (S6, F-14, F-05)

*   **Given**: The gateway sync is failing.
*   **When**: The administrator saves a role's permissions, tries to assign the role to a user,
    then presses Re-sync once the sync works again, and assigns the role.
*   **Then**: The save shows `SyncFailedNotice` (`ROLE_PERMISSIONS_SAVE`): the rows are saved,
    the role is not synced, and it must not be assigned until a sync succeeds. The role shows
    `FAILED`. Assigning it is refused with `ROLE_NOT_SYNCED`, naming the role. Re-sync calls
    `POST /roles/:id/sync`. Success changes the status to `SYNCED`, and the assignment then
    succeeds. A failed Re-sync keeps the retry state.

### CUJ 5: Change a user's roles (S4, F-11)

*   **Given**: The administrator is on `/users/:id`.
*   **When**: They change the role selection and save roles.
*   **Then**: The console sends the full new role set in one `PUT /users/:id/roles`. It does not
    send a list of changes. Saving the profile, saving roles, changing status and resetting the
    password are separate actions with their own buttons and pending states.
    `LAST_SYSTEM_ADMIN` is shown next to the roles' Save button.

### CUJ 6: Deactivate and re-activate a user (S5, F-10)

*   **Given**: An `ACTIVE` user who is not the last active `SUPER_ADMIN` is listed on `/users`.
*   **When**: The administrator chooses "Deactivate" from the row actions and confirms, and
    later chooses "Activate" and confirms.
*   **Then**: The console sends `PATCH /users/:id/status` each time and, on success, refetches
    the list. The user shows as `INACTIVE` and keeps their roles, then shows as `ACTIVE` again.
    "Lock" works the same way and shows `LOCKED`. `LAST_SYSTEM_ADMIN` is shown inline in the
    row's message area.

### CUJ 7: A call the caller is not allowed to make (F-04)

*   **Given**: The signed-in administrator's role has no permission row for a call, for example
    `DELETE /admin/v1/roles/*`.
*   **When**: They try that action.
*   **Then**: The action's area shows an inline "You do not have permission to do this" message
    with the server message. The rest of the page and the shell keep working.

### CUJ 8: Maintain the menus catalog (F-15)

*   **Given**: The administrator is on `/menus`.
*   **When**: They add a `DETAIL` entry under a `NAV` entry, then deactivate an existing entry.
*   **Then**: The tree shows label, key, path, icon, type and active state. `DUPLICATE_MENU_KEY`
    shows on the key field. Deactivate sends `PUT /menus/:id` with `isActive: false` and the
    entry's other fields unchanged. A deactivated entry stays visible, marked inactive, and can
    be activated again the same way.

## Technical Plan (Architecture)

### Layers and the rules between them

The console follows the layer split of `cce-insights-ui`
([architecture §3](../../context/cce-insights-ui/docs/architecture-overview.md)) and the folder
layout in PRD §6 (`pages/ components/ hooks/ api/ auth/ schemas/ context/`), plus `utils/` as in
the template. It has no global client state, so `context/` is not created. Each layer may only
call the layer below it.

```text
 main.tsx ── readConfig() ──fail──▶ StartupErrorScreen (no auth, no API calls)
    │ ok
    ▼
 initAuth()  ── redirect to Keycloak until a session exists (PKCE S256)
    │ resolved              ──fail (Keycloak unreachable)──▶ StartupErrorScreen
    ▼
 render: QueryClientProvider ▸ RouterProvider(createBrowserRouter(routes))
                                                               │
 ┌─────────────────────────────────────────────────────────────┴──────────┐
 │ pages/        route components; compose hooks + components            │
 │   │  may import: hooks/, components/, schemas/, utils/, auth/         │
 │   ▼                                                                   │
 │ components/   rendering only; data and callbacks arrive as props      │
 │ hooks/        one TanStack Query hook per api/ function; query keys,  │
 │   │           invalidation after mutations                            │
 │   ▼                                                                   │
 │ api/          resource modules (me, users, roles, roleMenus,          │
 │   │           rolePermissions, menus) → client.ts                     │
 │   │           contract.ts: every request/response type (D-01 mirror)  │
 │   ▼                                                                   │
 │ client.ts     URL, Bearer header, envelope unwrap, ApiError           │
 │   │                                 ▲                                 │
 │   └── getToken() / refreshToken() ──┘ auth/keycloak.ts                │
 └───────────────────────────────────────────────────────────────────────┘
          │ fetch  ${VITE_API_BASE_URL}/admin/v1/...
          ▼
   local:    Vite dev proxy ─▶ cce-admin-service :8085           (D-03)
   tests:    MSW handlers (typed from api/contract.ts)
   deployed: Caddy :3002 ─▶ gateway-service ─▶ cce-admin-service  (PRD §5, §9)
```

ESLint `no-restricted-imports` rules enforce the layers, so a breach fails `npm run lint`:
*   `src/pages/**` and `src/components/**` may not import values from `src/api/**`. Type-only
    imports (`import type`) from `src/api/contract.ts` are allowed, so screens can name the
    contract's types. Pages never call `fetch` or an `api/` function (PRD §10, F1 notes).
*   `src/components/**` may not import `src/hooks/**` or `@tanstack/react-query`.
*   Only `src/auth/**` may import `keycloak-js`.
*   Only `src/test/**`, files ending in `.test.ts` or `.test.tsx`, and `e2e/**` may import `msw`
    or `@playwright/test`.

### Start-up: configuration, then sign-in, then render

1.  **Configuration check.** `src/config.ts` reads the four `VITE_*` values from PRD §8.3. Each
    is trimmed, and an empty value counts as unset. `VITE_KEYCLOAK_REALM` must be set (C-02).
    `VITE_KEYCLOAK_CLIENT_ID` defaults to `cce-admin-ui`. `VITE_KEYCLOAK_URL` defaults to
    `${window.location.origin}/auth`. `VITE_API_BASE_URL` defaults to an empty string, and a
    trailing `/` is removed. If the realm is missing, `main.tsx` renders a static start-up error
    screen that names the missing variable. It does not call Keycloak or the API. The same check
    runs in `vite.config.ts` for `vite build`, so `npm run build` and the Docker build fail early
    when the realm is empty, instead of producing an image that cannot start.
2.  **Sign-in.** `initAuth()` in `src/auth/keycloak.ts` creates the `keycloak-js` instance and
    calls `init` with `onLoad: 'login-required'`, `pkceMethod: 'S256'` and
    `checkLoginIframe: false`. Until a session exists, the browser is sent to the Keycloak login
    page, and nothing renders. Keycloak returns the browser to the URL first requested. After
    `init` resolves with a session, a 60-second interval calls `updateToken(70)` (F-01). If that
    refresh fails, the console calls `login()`, which sends the browser back to Keycloak. If
    `init` rejects (for example, Keycloak cannot be reached), `main.tsx` renders the start-up
    error screen with the Keycloak URL it tried.
3.  **Render.** `main.tsx` renders the providers and the router only after `initAuth()`
    resolves. There is no code path that renders a route without a session, and no flag or
    static token that skips `initAuth()` (C-01, Goal 7). The access token is held only in the
    `keycloak-js` instance in memory. The console never writes it to `localStorage`,
    `sessionStorage` or a cookie.

**Test seam for sign-in (decided at gate TP-1).** Unit and component tests cannot run a real
Keycloak redirect, and the console has no bypass flag. Tests replace the auth module instead:

*   `src/test/setup.ts` calls `vi.mock` on `src/auth/keycloak.ts` for every test file. The
    replacement is a fake in `src/test/fakeAuth.ts`. It is written with
    `satisfies typeof import('../auth/keycloak')`, so a change to the real module's exports
    breaks the test type-check (`tsc -b`, part of `npm run build`) instead of passing silently.
    The fake returns a fixed token. Its `refreshToken`, `login` and `logout` are `vi.fn()` spies,
    so tests can assert on them, and a test can make `refreshToken` resolve `false`.
*   `src/auth/__tests__/keycloak.test.ts` is the only test that runs the real module. It calls
    `vi.unmock` on it, mocks the `keycloak-js` package and uses fake timers. It covers what
    component tests cannot: `initAuth()` calls `init` with `login-required` and PKCE `S256`
    and does not resolve without a session; the 60-second interval calls `updateToken(70)`;
    `refreshToken()` forces a refresh; and a failed refresh calls `login()`. This is the only
    file that depends on the `keycloak-js` API.
*   The other half of the 401 path, "repeat the request once, then call `login()`", lives in
    `client.ts`. It is tested in `src/api/__tests__/client.test.ts` against the fake: one 401
    then a 200 calls `refreshToken` once and returns the data; two 401s call `login`.
*   Rejected at the gate: passing an auth-client interface into the app, and mocking
    `keycloak-js` in every test. See Alternatives A and B.

**Sign-out** calls `keycloak.logout({ redirectUri: window.location.origin + '/' })` from the
header.

### API client and the contract module

*   **`src/api/contract.ts`** is the only place where request and response shapes are written.
    It mirrors admin-service §4.1 to §4.5 exactly: the success envelope with optional
    `pagination` and `warnings`; the warning and its two codes; the error envelope with optional
    `details` and `partialState`; the field error; the partial state and its nine operations;
    the pagination block; every error code of §4.2; the resources of §4.4 (User, Role, Menu,
    Permission row, Me); and every request and response body in the §4.5 table that the console
    uses. Ids are `string` (UUID) except permission row ids, which are `number`. Timestamps are
    `string` (ISO-8601 UTC). The file begins with a comment that links admin-service §4 and
    gives the date of the version it mirrors (2026-11-10). The console does not call
    `GET /me/menus`, `GET /health` or `DELETE /menus/:id`, so their shapes are not in the module.
*   **`src/api/client.ts`** exposes one request function. It builds the URL as
    `${apiBaseUrl}/admin/v1${path}` and leaves out query parameters that are undefined, null or
    empty strings. It sends `Authorization: Bearer <token>` and `Accept: application/json`,
    plus `Content-Type: application/json` when there is a body (F-02). It parses the envelope
    and returns it typed from `contract.ts`, so callers can read `data`, `pagination` and
    `warnings`. A 204 response (`DELETE /roles/:id`) returns no data. Request bodies contain
    only the properties the contract defines, because the service rejects any other property
    with 400 `VALIDATION_ERROR` (admin-service §4.1). Each `api/` function and each page builds
    its body explicitly from named fields and never spreads form state into it.
*   **Errors.** Any non-2xx response becomes an `ApiError` with `status`, `code`, `message`,
    and `details` and `partialState` when the error body carries them. If the body is not a
    valid error envelope, `code` is `CLIENT_UNEXPECTED_RESPONSE`. If `fetch` itself fails (no
    connection), `status` is `0` and `code` is `CLIENT_NETWORK_ERROR`. These two codes are made
    by the console and never sent by the server. The `CLIENT_` prefix keeps them apart from the
    contract's codes.
*   **401 handling.** On a 401 the client calls `refreshToken()` once. If it returns `true`, the
    client repeats the request once. This is safe for writes too, because the service and the
    gateway both reject an invalid token before doing anything (admin-service §3, step 2). If
    the repeated request also returns 401, the client calls `login()`. If `refreshToken()`
    returns `false`, it has already called `login()`, and the client throws the 401 `ApiError`.
*   **Resource modules** (`api/me.ts`, `users.ts`, `roles.ts`, `roleMenus.ts`,
    `rolePermissions.ts`, `menus.ts`) each export one typed function per endpoint the console
    calls. They contain no React code.

### Server state: hooks, query keys and invalidation

*   **One hook per `api/` function** (PRD §10, F1 notes). Query keys are built by one module,
    `src/hooks/queryKeys.ts`: `['me']`, `['users', 'list', query]`, `['users', 'detail', id]`,
    `['roles', 'list', { page, pageSize }]`, `['roles', 'all']`, `['roles', 'detail', id]`,
    `['roles', id, 'menus']`, `['roles', id, 'permissions']`, `['menus', 'tree']`.
*   **QueryClient defaults.** Queries: `staleTime` 30 seconds, `refetchOnWindowFocus` off (as in
    the template), and one retry, only for status 0 or a status of 500 or above, never for 4xx.
    Mutations are never retried, because a repeated write can repeat a Keycloak side effect.
*   **No optimistic updates (F-06).** A mutation hook does not touch the cache before the server
    answers. On success it invalidates the queries in the table below, and the screen shows
    only what the server returns on the refetch.

| Mutation | Invalidated on success, and on a 502 whose `partialState.completedSteps` is not empty |
|---|---|
| `POST /users`, `PUT /users/:id`, `PATCH /users/:id/status`, `PUT /users/:id/roles` | `['users']`; `['roles']` (a role's `userCount` changes) |
| `POST /users/:id/reset-password` | nothing (no visible data changes) |
| `POST /roles`, `PUT /roles/:id`, `DELETE /roles/:id` | `['roles']`; also `['users']` after delete |
| `PUT /roles/:id/menus` | `['roles', id, 'menus']` |
| `PUT /roles/:id/permissions` | `['roles']` (all role queries: permissions, detail, list, all) |
| `POST /roles/:id/sync` | `['roles']`, also on any 502 (the status changed to `FAILED`) |
| `POST /menus`, `PUT /menus/:id` | `['menus']` and `['roles']` (a role's menu tree shows menu labels and states) |

Under the default `GATEWAY_SYNC_SCOPE=GLOBAL`, a successful sync can mark other roles `SYNCED`
too (admin-service AD-06), so a sync invalidates every role query, not only the one role.

**Fetching every role.** `GET /roles` is paginated (admin-service §4.3). The role checkboxes on
the user forms and the role filter on the Users list need every role. The query `useAllRoles`
(key `['roles', 'all']`) requests `GET /roles?page=1&pageSize=100`, then each further page up
to `totalPages`, and joins the results in order. The pickers and the filter show only roles
with `isAssignable: true` (admin-service AD-14).

### Error presentation

One function, `describeError(error)` in `src/utils/describeError.ts`, sorts every error into a
kind. Screens choose how to show an error by its kind and its `code`, never by parsing
`message`.

| Kind | Codes | Shown as |
|---|---|---|
| `forbidden` | `FORBIDDEN` | `InlineError`: "You do not have permission to do this." and the server message (F-04) |
| `syncFailed` | `KEYCLOAK_SYNC_FAILED` | `SyncFailedNotice` (F-05) |
| `validation` | `VALIDATION_ERROR` | Field messages from `details`; the rest at the top of the form |
| `conflict` | `EMAIL_IN_USE`, `USERNAME_IN_USE`, `DUPLICATE_ROLE_NAME`, `DUPLICATE_MENU_KEY`, `ROLE_IN_USE`, `SYSTEM_ROLE_IMMUTABLE`, `LAST_SYSTEM_ADMIN`, `ROLE_NOT_ASSIGNABLE`, `ROLE_NOT_SYNCED`, `MENU_HAS_CHILDREN` | On a field, or inline next to the action (rules below) |
| `notFound` | `USER_NOT_FOUND`, `ROLE_NOT_FOUND`, `MENU_NOT_FOUND`; also `VALIDATION_ERROR` whose only detail has field `id`, on a detail page's fetch | `NotFoundPanel` with a link back to the list |
| `unauthenticated` | `UNAUTHENTICATED` | Nothing extra: `client.ts` has already sent the browser to Keycloak |
| `network` | `CLIENT_NETWORK_ERROR` | `InlineError`: "Cannot reach the admin service." with Retry |
| `unexpected` | `INTERNAL_ERROR`, `NOT_FOUND`, `METHOD_NOT_ALLOWED`, `CLIENT_UNEXPECTED_RESPONSE`, any other code | `InlineError` with Retry. For `NOT_FOUND` and `METHOD_NOT_ALLOWED` the text says the console and the service disagree about the API. |

*   **Field mapping.** `VALIDATION_ERROR` details are mapped to React Hook Form fields by
    `field`. The JSON path form (`permissions[2].uriPattern`) becomes React Hook Form's form
    (`permissions.2.uriPattern`). A detail whose field is not a field of that form (for example
    `body`, `id` or `pageSize`) is shown at the top of the form. Conflict codes map to fields:
    `USERNAME_IN_USE` to username, `EMAIL_IN_USE` to email, `DUPLICATE_ROLE_NAME` to name,
    `DUPLICATE_MENU_KEY` to key.
*   **Role errors.** `ROLE_NOT_SYNCED` and `ROLE_NOT_ASSIGNABLE` carry one detail per role, with
    `field` `roleIds[i]` (admin-service §4.1). The console keeps the `roleIds` array it sent,
    maps each index back to the role, and shows "Not synced yet: {displayName} ({name}). Open the
    role and use Re-sync." or "Cannot be assigned: {displayName} ({name})." under the role
    checkboxes.
*   **Inline next to the action.** `ROLE_IN_USE`, `SYSTEM_ROLE_IMMUTABLE`, `LAST_SYSTEM_ADMIN`
    and `MENU_HAS_CHILDREN` show the server message next to the button that caused them.
*   **`GET /me` failing.** The header shows the error in place of the username (for example
    "Your account cannot use the admin API." for 403). Each page still shows its own errors as
    its own calls fail. There is no global error page.

**`SyncFailedNotice`.** It takes the `partialState`, the server `message`, and optional actions
(a Retry button, a link). It chooses its text by `partialState.operation`, following the table
in admin-service §4.2. Under that text it lists the failed step, the completed steps (or
"Nothing was changed." when the list is empty), the Keycloak user id when present, and the audit
log id when present, so an operator can find the audit row. Step names are shown as the server
sends them.

| `operation` | Shown on | Text (first sentence fixed; rest from completed steps) | Action |
|---|---|---|---|
| `USER_CREATE` | User create page | "The user was not fully created." Steps completed: "The Keycloak account may exist without a local user. Do not submit again. An operator reconciles it from the audit log." No steps: "Nothing was created. You can submit again." | Link to Users list; link to the user if `entityId` is set |
| `USER_PROFILE_UPDATE` | User edit, Profile section | "The profile was not fully saved." Steps completed: "Keycloak has the new values, the console shows the saved ones. An operator reconciles them from the audit log." | none |
| `USER_STATUS_CHANGE` | Users list row messages; User edit, Status section | "The status change did not finish." Steps completed: "Keycloak may already have the new state. An operator reconciles it from the audit log." | none |
| `USER_ROLES_UPDATE` | User edit, Roles section | "The role change did not finish. Some Keycloak role mappings may have changed. An operator reconciles them from the audit log." | none |
| `USER_PASSWORD_RESET` | Users list row messages; User edit, Password section | "The set-password email was not sent. Nothing changed. Try again." | none |
| `ROLE_CREATE` | Roles list, create form | "The role was created but not synced. Open the role and use Re-sync before assigning it." | Link to `/roles/{entityId}?tab=permissions` |
| `ROLE_PERMISSIONS_SAVE` | Role detail, Permissions tab | Steps completed: "The permissions were saved but not synced. Use Re-sync, and do not assign the role until a sync succeeds." No steps: "Nothing was saved. You can save again." | Re-sync |
| `ROLE_SYNC` | Role detail, Permissions tab | "The sync failed. The permissions were already saved. Try Re-sync again." | Re-sync |
| `ROLE_DELETE` | Roles list | "The role was deleted. Its realm role may stay in Keycloak until the next successful sync of any role." | none |

### Shell and routing

The router is a data router, `createBrowserRouter`, rendered with `RouterProvider`, because
React Router's `useBlocker` (used for unsaved changes) works only with a data router. The route
objects live in `src/routes.tsx`, so tests build a `createMemoryRouter` from the same objects.
There is no base path; the console is served at `/` on port 3002. One layout route renders the
static sidebar (Users, Roles, Menus, F-03) and the header (username and role names from
`GET /me`, Sign out), with an `<Outlet/>` for the page. Pages are not lazy-loaded: there are
seven, and the bundle stays small.

| Path | Page | Requirement |
|---|---|---|
| `/` | redirect to `/users` | F-03 |
| `/users` | Users list | F-10 |
| `/users/new` | User create | F-11 |
| `/users/:id` | User edit | F-11 |
| `/roles` | Roles list with inline create | F-12 |
| `/roles/:id?tab=menus` (default) or `?tab=permissions` | Role detail | F-13, F-14 |
| `/menus` | Menus catalog | F-15 |
| `*` | Not-found page | none |

The Role detail tab lives in the URL so that a tab can be linked and reloaded. On the Menus and
Permissions tabs, unsaved changes are guarded with `useBlocker`. Leaving the route or switching
tab asks for confirmation in a `ConfirmDialog`. Switching tab changes the URL, so the same guard
covers it.

### Screens

**Users list (F-10).** Filters and paging live in the URL search parameters `status`, `roleId`,
`facilityScope`, `q`, `page` and `pageSize`, which map one-to-one to the `GET /users` query
parameters (admin-service §4.5). The `q` and `facilityScope` text inputs wait 300 ms after the
last keystroke before updating the URL. Changing a filter resets `page` to 1. TanStack Table runs
in manual-pagination mode. Its page count is `totalPages`, and the footer shows `totalItems`
(admin-service §4.3). Pages in the URL are 1-based, like the API. The page size choices are 20,
50 and 100, and the default is 20 (B-04). A `page` or `pageSize` in the URL that is not a whole
number in range (page at least 1, page size one of the three choices) is replaced by the default
before any request, because the service rejects out-of-range values with 400 instead of
clamping them. If a response has `data: []` while `page` is greater than `totalPages` and
`totalPages` is at least 1, the console replaces `page` in the URL with `totalPages`, which
refetches.

*   **Filters:** status (All, Active, Inactive, Locked), role (All, then every assignable role
    by display name), facility scope (text, exact match, up to 50 characters), and search (text,
    up to 100 characters, matching username, email, first name, last name or full name).
*   **Columns:** username, email, status badge (green `ACTIVE`, gray `INACTIVE`, amber
    `LOCKED`), role display names, facility scope (or "—"), last login (`yyyy-MM-dd HH:mm` in
    local time with date-fns, or "Never" when `lastLoginAt` is null).
*   **Row actions:** "View / edit" (link to `/users/:id`); one status button for each status the
    user does not have ("Activate" to `ACTIVE`, "Deactivate" to `INACTIVE`, "Lock" to `LOCKED`);
    and "Reset password". Each opens a `ConfirmDialog` that names the user and the effect. The
    result of a row action (success, error or `SyncFailedNotice`) is shown in a message area
    above the table that names the user; the table itself is refetched on success.

**User create (F-11).** React Hook Form with a Zod schema from `src/schemas/`. Fields: username,
email, first name, last name, facility scope (optional), and roles (checkboxes of every
assignable role from `useAllRoles`, at least one and at most 20). Each role checkbox shows the
role's display name and name, and a `SyncStatusBadge` when its `syncStatus` is not `SYNCED`,
with the hint "Not synced yet: assigning it will fail until it is synced." The hint does not
block the choice; the service decides (admin-service AD-10). One submit sends one `POST /users`;
there is no separate sync step. On 201 the console goes to `/users/:id` and passes the result in
navigation state, so the edit page shows "User created. A set-password email was sent to
{email}." or, when the response carries `ACTION_EMAIL_NOT_SENT`, "User created, but the
set-password email could not be sent. Use Reset password to send it." On 502, the form stays on
screen with `SyncFailedNotice` (`USER_CREATE`). If `completedSteps` is not empty, the submit
button stays disabled for the rest of that page visit.

**User edit (F-11).** A header with username (read-only), status badge and last login, then four
independent sections. Each has its own mutation, pending state and inline error area:
*   **Profile:** email, first name, last name, facility scope; Save sends `PUT /users/:id` with
    all four fields, and `facilityScope: null` when the field is empty.
*   **Roles:** the same checkbox list as on create (zero to 20 roles). Save is enabled only when
    the selection differs from the last fetched set, and sends the complete set with
    `PUT /users/:id/roles`.
*   **Status:** the same three status buttons as the row actions, behind a `ConfirmDialog`.
*   **Password:** "Send set-password email" behind a `ConfirmDialog`; on success it shows "Email
    sent to {email}." from the response.

**Roles list (F-12).** A table with server-side paging like the Users list (`page` and
`pageSize` in the URL, default 20). Columns: name (with a lock icon when `isSystem`), display
name, description, users (`userCount`), sync (`SyncStatusBadge`: green `SYNCED` with
`lastSyncAt`, gray `NEVER`, red `FAILED` with `lastSyncAt`), and actions. A role with
`isAssignable: false` shows the badge "Service account, cannot be assigned". Actions: "Open"
(link to `/roles/:id`) and "Delete". Delete is not shown for system roles, and is disabled with
the reason "Assigned to {n} users" when `userCount` is above 0. Delete opens a `ConfirmDialog`.
A 502 on delete shows `SyncFailedNotice` (`ROLE_DELETE`) above the table.

The inline create form above the table has name, display name and description. The name field
validates the role-name rule as the user types. A fixed warning under it says: "The name cannot
be changed later. It is also the Keycloak realm role name and the gateway permission name."
Submitting opens a `ConfirmDialog` that repeats the exact name and the warning. The request is
sent only after Confirm. On 201 the console goes to `/roles/{id}`. On 502 it shows
`SyncFailedNotice` (`ROLE_CREATE`) under the form, with the link to the role's Permissions tab.

**Role detail (F-13, F-14).** A header with the name (read-only, with a lock for system roles),
the sync badge, the user count, a display-name and description form (`PUT /roles/:id`), and
Delete (same rules as the list). For a system role (`isSystem`), the form, the Menus tab's
checkboxes and Save, and the Permissions tab's editing and Save are all disabled, with the note
"System roles cannot be changed." Re-sync stays available (admin-service AD-07). Then the two
tabs.

**Menus tab (F-13).** A checkbox tree built from `GET /menus`: each `NAV` menu at the top level
in `sortOrder`, its `DETAIL` children indented under it. Each row shows the label, path and
type, and "inactive" when `isActive` is false. Inactive menus can be granted
(admin-service AD-03, §4.5). The current selection is `menuIds` from `GET /roles/:id/menus`, a
set of menu ids. Checking a parent does not check its children, and the reverse, because each
menu is granted on its own. Save is enabled only when the set differs from the last fetched set,
and it sends `{ "menuIds": [...] }` with the complete set. While editing, the tab lists every
selected `DETAIL` menu whose parent `NAV` is not selected, as "{detail label} is shown only when
{parent label} is also granted." After a save, the response's `DETAIL_WITHOUT_PARENT` warnings
replace that list. Each names its menu and parent by `menuId` and `parentMenuId`; the tab looks
both up in the tree to show their labels, and shows the server `message` if an id is not in the
tree.

**Permissions tab (F-14).** The tab reads `GET /roles/:id/permissions`, which returns the rows,
`syncStatus` and `lastSyncAt` (admin-service §4.5).

*   **Sync panel:** the `SyncStatusBadge`, "Last sync: {lastSyncAt}" or "Never synced", the
    Re-sync button (`POST /roles/:id/sync`, always shown, also for system roles), and the help
    text "Synced means the gateway's sync call succeeded." (CQ-2).
*   **Always included:** the implicit rows (`implicit: true`) in a read-only table, with the note
    "Every role keeps these two rules so that its users can load their profile and menus." They
    are never sent (admin-service AD-16).
*   **Rules:** the explicit rows in an editable table, built with React Hook Form's field array
    and a Zod schema. Each row has method (a select of `GET`, `POST`, `PUT`, `PATCH`, `DELETE`;
    CQ-1), URI pattern, description and category. Rows can be added (new rows start with `GET`
    and empty fields) and removed. The five methods are fixed by the contract (CQ-1). The schema
    applies the rules in
    [Forms and validation](#forms-and-validation), rejects two rows with the same method and
    pattern, and rejects a row equal to an implicit row ("Already included for every role").
*   **Save** is enabled only when the explicit rows differ from the last fetched explicit rows.
    Both sides are normalized first (method upper case, strings trimmed, empty description or
    category treated as absent) and compared as sets of (method, pattern, description,
    category), which is how the service decides a no-op (admin-service §5.7). Save sends
    `{ "permissions": [...] }` with every explicit row, leaving out description and category
    when they are empty.

The sync state is read from the server, never set by the console:

```text
            PUT permissions 200 / POST sync 200 / POST roles 201
  NEVER ─────────────────────────────────────────────────────▶ SYNCED ("Synced at HH:mm")
    │                                                          │  ▲
    │ 502 ROLE_CREATE / ROLE_PERMISSIONS_SAVE / ROLE_SYNC      │  │ Re-sync 200
    ▼                                                          ▼  │
  FAILED  ◀──────────────────────────── any of those 502s ────────┘
  (SyncFailedNotice: do not assign until a sync succeeds; Re-sync)
```

After a save or a Re-sync, success or 502, the console refetches the role queries and shows the
status the service returns. On a `ROLE_PERMISSIONS_SAVE` 502 with completed steps, the rows were
saved, so the form resets to the refetched rows. On a 502 with no completed steps, nothing was
saved, so the edited rows stay in the form for another try. A 200 shows "Saved and synced at
{lastSyncAt}."

**Menus catalog (F-15).** A tree table showing label, key, path, icon (the icon itself and its
name), type, sort order and an active badge. `NAV` rows are at the top level in `sortOrder`, and
their `DETAIL` children are indented under them. Actions:
*   **"Add menu"** above the table opens the menu form for a new `NAV` entry. **"Add detail"** on
    each `NAV` row opens it for a new `DETAIL` entry under that row.
*   **"Edit"** opens the form with the entry's values. Key and type are shown read-only, because
    they cannot change (admin-service §4.4). For a `DETAIL` entry, the parent can be changed to
    another `NAV` entry.
*   **"Deactivate"** (on an active entry) or **"Activate"** (on an inactive one) opens a
    `ConfirmDialog`, then sends `PUT /menus/:id` with the entry's current label, path, icon,
    parent id and sort order, and `isActive` flipped.

The form has key, label, path, icon, type, parent (only for `DETAIL`, a select of `NAV`
entries), sort order and active. Create sends `POST /menus`; edit sends `PUT /menus/:id` without
key and type. Icons are rendered through a fixed map in `src/components/menus/iconMap.ts` from
an icon name to a Heroicons 2 outline component. The map holds the ten icons of the
admin-service seed (admin-service §6) plus `HomeIcon`, `UsersIcon`, `ShieldCheckIcon`,
`DocumentTextIcon` and `Squares2X2Icon`. The icon field is a select of "No icon" and the map's
names; when an entry's current icon is not in the map, that name is added to the select so an
edit keeps it. An unknown name is shown with `QuestionMarkCircleIcon` and the raw name, so a
mismatch is visible instead of breaking the page.

### Forms and validation

Each form has one Zod schema in `src/schemas/`. The rules mirror admin-service §4.4 and §4.5;
`src/schemas/rules.ts` holds the patterns and limits in one place. Every string is trimmed
before it is checked. The client check is for fast feedback only. Server errors are still shown
by `error.code`.

| Field | Rule |
|---|---|
| Username | 3 to 50 characters, `^[a-z0-9][a-z0-9._-]*$` |
| Email | a valid address, up to 254 characters |
| First name, last name | 1 to 100 characters |
| Facility scope | optional, up to 50 characters |
| Roles on create / on edit | 1 to 20 / 0 to 20 distinct ids |
| Role name | 2 to 100 characters, `^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$` |
| Role display name / description | 1 to 100 / optional, up to 500 characters |
| Menu key | up to 100 characters, `^[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)*$` |
| Menu label | 1 to 100 characters |
| Menu path | 1 to 255 characters, `^/[A-Za-z0-9/_\-.:]*$` |
| Menu icon | absent, or `^[A-Z][A-Za-z0-9]*Icon$` |
| Menu sort order | whole number, 0 to 10000 |
| Menu parent | required for `DETAIL` (a `NAV` entry), absent for `NAV` |
| Permission method | one of `GET`, `POST`, `PUT`, `PATCH`, `DELETE` |
| Permission URI pattern | 1 to 255 characters, starts with `/`, no `//`, only letters, digits and `- . _ ~ / *`, and a segment containing `**` must be exactly `**` |
| Permission description / category | optional, up to 1000 / optional, up to 50 characters |
| Permission rows | up to 200 explicit rows; no two with the same method and pattern; none equal to an implicit row |

Save buttons on replace-semantics screens (user roles, role menus, role permissions) compare
the current value with the last fetched value only to enable or disable Save. The payload is
always the complete set (PRD §10).

### Development and test environments

| Environment | API | Keycloak | Used for |
|---|---|---|---|
| Unit and component tests (`npm test`, `npm run test:ci`) | MSW in Node, typed from `contract.ts` | none; auth module replaced by `fakeAuth.ts` (TP-1) | every screen and hook; Goal 1 exit checks |
| Local development before the service exists | none: screens are built test-first and seen through component tests only (TP-2) | not used | building screens |
| Local development with the service | Vite proxy `/admin/v1` → `http://localhost:8085` | M0 Keycloak at `http://localhost:8180/auth`, realm `cce`, reached directly | manual checks |
| Browser tests (`npm run e2e`) | the real service through the Vite proxy | the same M0 Keycloak, real login page | CUJ 2 to CUJ 6 (Goal 9) |
| Docker image | Caddy → gateway-service | the environment's Keycloak | shared environments |

**Local values** (admin-service §9.3). `.env.example` holds
`VITE_KEYCLOAK_URL=http://localhost:8180/auth`, `VITE_KEYCLOAK_REALM=cce`,
`VITE_KEYCLOAK_CLIENT_ID=cce-admin-ui` and an empty `VITE_API_BASE_URL`. None is a secret.
Keycloak is not proxied through Vite, because a proxied path would change the token issuer from
`http://localhost:8180/auth/realms/cce`, the value the service checks. Tokens from
`cce-admin-ui` carry the audience `gateway-service` that the service checks.

**No mocks in the browser (decided at gate TP-2).** MSW runs only inside Vitest. The console
has no mock mode, no MSW browser worker and no mock HTTP server, so no mock code path can reach
a production bundle. The scripts are the PRD's five (`dev`, `build`, `test`, `test:ci`, `lint`)
plus `e2e`. See Alternatives C and D.

**MSW.** `src/test/mocks/` holds an in-memory store and handlers for every endpoint the console
calls, all typed from `contract.ts`. The store is seeded like the admin-service seed
(admin-service §6): the twelve menus with their keys, labels, paths, icons, types, parents and
sort orders; `SUPER_ADMIN` (system, assignable) and `CCE_ADMIN_SERVICE` (system, not
assignable); plus test data: one non-system role `FACILITY_ADMIN` (synced, with its two implicit
rows and two explicit rows), the test administrator holding `SUPER_ADMIN`, and 24 more users for
paging. The handlers follow the contract's behaviour, not just its shapes: filters and search,
pagination and its 400s, 400 on an unknown body property, replace semantics, no-ops, implicit
rows, warnings, and the 409s of admin-service §4.5. They use the role's `syncStatus` as the
stand-in for "the realm role exists in Keycloak" when deciding `ROLE_NOT_SYNCED`. A test that
needs a failure the store cannot produce (403, 502, a network error) overrides one handler with
`server.use(...)`. The store is reset after every test.

**Browser tests.** `e2e/` holds Playwright tests that run in Chromium only, one at a time
(`workers: 1`), because CUJ 4 switches the shared stand-in gateway to failing. The config starts
`npm run dev` on port 3002 unless it is already running. It expects the M0 stack and the
service to be running already, and its global setup stops with a message naming the
admin-service §9.3 start commands if `GET http://localhost:8085/admin/v1/health` or
`GET http://localhost:8090/_stub/mode` does not answer. A setup project signs in once through
the real Keycloak login page with `CCE_TEST_ADMIN_USERNAME` and `CCE_TEST_ADMIN_PASSWORD` and
saves the browser state; the CUJ tests reuse it. Those two variables come from the process
environment, or else from `../cce-admin-service/.env`, read with Node's `process.loadEnvFile`.
They are never written to a file in `cce-admin-ui`. Each test creates the data it starts from
(roles, users) through the API, with a token from the local-only public client `cce-e2e`
(password grant, admin-service §9.2), and gives every name a per-run suffix. The steps under
test go through the console. CUJ 4 switches the stand-in with
`PUT http://localhost:8090/_stub/mode` (`fail`, then `ok`) and always sets `ok` again when it
ends, pass or fail. A full reset is the admin-service's `docker compose --profile app down -v`
(admin-service §9.3).

### How each milestone exit check is shown

PRD §10 gives an exit check for each frontend milestone. Each part is shown in one or both of
two ways:
*   **MSW test**: a Vitest test under `npm run test:ci`, against MSW handlers.
*   **Real stack**: a Playwright test under `npm run e2e`, against M0 Keycloak and the service
    on 8085.

| Milestone | Exit check (PRD §10) | MSW test | Real stack |
|---|---|---|---|
| F1 | Login redirect works | `keycloak.test.ts`: `init` called with `login-required` and `S256`; nothing renders before it resolves | The Playwright setup project signs in through the Keycloak login page |
| F1 | Header shows `/me` | `AppLayout.test.tsx`: header shows the username and role names from `GET /me`; the sidebar shows Users, Roles and Menus even when `/me` returns 403 | Every CUJ test checks the test administrator's username in the header |
| F1 | `ApiError` surfaces codes in a test | `client.test.ts`: error envelope to `ApiError` `status`, `code`, `details`, `partialState`; non-envelope body to `CLIENT_UNEXPECTED_RESPONSE`; failed fetch to `CLIENT_NETWORK_ERROR`; 401 refresh-once path | not shown (test-only check) |
| F1 | 403 shown inline (F-04) | `UsersListPage.test.tsx` and `RolesListPage.test.tsx` with a 403 handler: inline message shown, shell still works | not shown: Goal 9 covers CUJ 2 to 6, and a local 403 would need signing in as a second, restricted user |
| F2 | Create role with name warning | `RolesListPage.test.tsx`: invalid names rejected; warning and confirmation dialog shown; `POST /roles` sent only after Confirm | CUJ 3 |
| F2 | Menus tree saves full set | `RoleMenusTab.test.tsx`: Save disabled until changed; request body is the complete set | CUJ 3 |
| F2 | Warning on DETAIL-only | `RoleMenusTab.test.tsx`: client-side warning while editing; server warning shown after save | CUJ 3 grants one `DETAIL` menu without its parent and checks the server warning |
| F3 | Save shows "synced" | `RolePermissionsTab.test.tsx`: 200 shows "Saved and synced" and the `SYNCED` badge; implicit rows shown read-only and absent from the request | CUJ 3 |
| F3 | MSW 502 shows retry state | `RolePermissionsTab.test.tsx`: 502 with completed steps shows `SyncFailedNotice` and Re-sync, rows refetched, `FAILED` badge; 502 with no steps keeps the edits | CUJ 4, using the stand-in switch |
| F3 | Re-sync works | `RolePermissionsTab.test.tsx`: Re-sync 200 shows `SYNCED`; Re-sync 502 keeps the retry state | CUJ 4 |
| F4 | Paginated, filtered list | `UsersListPage.test.tsx`: each filter and page change sends the matching query parameters; page count from `totalPages`; out-of-range `page` corrected | CUJ 2 finds the new user with the search box |
| F4 | Create and edit flows | `UserCreatePage.test.tsx`, `UserEditPage.test.tsx`: validation, one `POST`, `ACTION_EMAIL_NOT_SENT`, `ROLE_NOT_SYNCED`, the four separate edit actions, `LAST_SYSTEM_ADMIN` | CUJ 2, CUJ 4 (`ROLE_NOT_SYNCED`), CUJ 5, CUJ 6 |
| F4 | 502 state on create | `UserCreatePage.test.tsx`: a 502 with completed steps shows `SyncFailedNotice` with the audit log id and Keycloak user id, and submit stays disabled; a 502 with no completed steps says nothing was created, and submit stays enabled | not shown: a Keycloak failure part-way through create cannot be caused on demand locally |
| F5 | Add and deactivate a menu entry | `MenusPage.test.tsx`: add a `DETAIL` under a `NAV`; `DUPLICATE_MENU_KEY` on the key field; deactivate sends `PUT` with `isActive: false` and the other fields unchanged | not shown: CUJ 8 is outside Goal 9, and changing the shared local menu seed would affect other tests |

### Build and deployment

*   **Node.** `package.json` declares `engines.node` as `>=20.19.0 <23`. The reference build is
    the Dockerfile on `node:20.20.2-alpine` (D-05); this machine runs Node 22.22 (C-03).
*   **Docker.** A three-stage Dockerfile. `build` (`node:20.20.2-alpine`, D-05) runs `npm ci` and
    `npm run build` with the four `VITE_*` values as build arguments. `verify` (from `build`)
    runs `npm run lint` and `npm run test:ci` and checks that `dist/` holds no MSW code; it runs
    only when asked for with `--target verify`, and it is how Goal 8 is checked on Node 20. The
    final stage (`caddy:2.11.9-alpine`, D-05) serves `dist/` on port 3002. `VITE_KEYCLOAK_REALM` has no
    default, so the build fails without it (see Start-up).
*   **Caddy.** As in the template: compression, long cache headers for `/assets/*`, a
    `try_files` fallback to `index.html`, and the same three security headers. `/admin/v1/*` is
    reverse-proxied to the upstream given at container start in the `GATEWAY_UPSTREAM`
    environment variable, with the path unchanged. This is runtime Caddy configuration, not a
    `VITE_*` value. Its target is `gateway-service`, never the admin service directly (PRD §9).
    Caddy refuses to start if the variable is empty. Caddy does not route `/auth`. Either the
    environment's ingress sends `${origin}/auth` to Keycloak, or the image is built with
    `VITE_KEYCLOAK_URL` set.
*   **Linting.** ESLint (flat config, typescript-eslint, react-hooks, the import rules above)
    and a Prettier check, both run by `npm run lint`.

## Alternatives Considered

The first six alternatives were put to the author at a gate, or raised by the author, during
this drafting session. Alternative G became moot. Section H lists options the PRD rules out,
which were not weighed.

### Alternative A: Pass an auth-client interface into the app (gate TP-1)

`AuthProvider` and `client.ts` take an `AuthClient` interface (`getToken`, `refreshToken`,
`login`, `logout`, `profile`). `main.tsx` passes the `keycloak-js` implementation, and a test
helper passes a fake.

*   **Pros**: The fake is checked against the interface at compile time. No module mocking.
*   **Cons**: A test-only seam becomes part of production code. Nothing in the code stops a fake
    from being wired into `main.tsx`; only a lint rule or a review would.
*   **Reason for Rejection**: Rejected by author: the "no bypass" guarantee (Goal 7) would depend
    on a lint rule. The chosen module swap keeps the seam out of production code, and the
    `satisfies` check on the fake gives the same compile-time protection.

### Alternative B: Mock `keycloak-js` in every test (gate TP-1)

Every test replaces the `keycloak-js` package, so the real `initAuth()`, refresh interval and
401 handling run in every component test.

*   **Pros**: The most real code runs in every test.
*   **Cons**: Every component test depends on the `keycloak-js` API, and every render waits for
    an asynchronous sign-in. A library upgrade that changes behaviour the fake does not copy
    leaves the tests passing against a fake that no longer matches.
*   **Reason for Rejection**: Rejected by author: only one file,
    `src/auth/__tests__/keycloak.test.ts`, should depend on `keycloak-js` details. That file
    covers what this alternative would have added.

### Alternative C: MSW in the browser in a `dev:mock` mode (gate TP-2)

A `npm run dev:mock` script runs Vite in a mock mode that starts the MSW browser worker after
sign-in, so every screen can be clicked through before the service exists.

*   **Pros**: Screens can be seen and demonstrated early, with the same handlers as the tests.
*   **Cons**: A mode branch in `main.tsx`, a worker file to keep out of `dist/`, and a sixth
    script. `/me` returns a fixed user whatever the token says.
*   **Reason for Rejection**: Rejected by author: no mock code path may reach a production
    bundle, and the scripts stay the PRD's five plus `e2e`. Both tracks are built at the same
    time in this trial, so the period without a service is short, and the browser tests of
    Goal 9 close it with automated checks instead of a demo.

### Alternative D: A separate mock API server on port 8085 (gate TP-2)

A Node process serves the MSW handlers over HTTP on port 8085, and the normal Vite proxy
forwards to it.

*   **Pros**: No mock code or mode in the console at all; the real proxy path is used.
*   **Cons**: An extra dependency and an extra process and script. It cannot run at the same time
    as the real service, which needs the same port. An HTTP adapter can treat some requests
    differently from MSW in Node, so the browser and the tests could see different mocks.
*   **Reason for Rejection**: Rejected by author, at the same gate and for the same reasons as
    Alternative C: the period without a service is short, and the scripts stay the PRD's five
    plus `e2e`.

### Alternative E: A manual run for the E2E milestone

The PRD's E2E milestone (§10) is shown by running the scenarios by hand once both tracks land,
with no browser test framework.

*   **Pros**: No Playwright dependency, no browser download, no test data left behind by
    automated runs.
*   **Cons**: Shows the scenarios once. A later change that breaks a scenario against the real
    service is not caught.
*   **Reason for Rejection**: Rejected by author: a manual run gives no regression coverage.
    CUJ 2 to CUJ 6 run as Playwright tests under `npm run e2e` (Goal 9, C-09).

### Alternative F: Generate the contract types from the service's OpenAPI spec

The service publishes an OpenAPI document through springdoc (B-06). A generator such as
`openapi-typescript` could produce `contract.ts` from it instead of writing it by hand.

*   **Pros**: The types match what the running service actually returns.
*   **Cons**: The console cannot compile until the service runs, or until a spec file is copied
    into the console repo and kept current. Under D-01 the contract is the admin-service
    *design*, not its code. Types generated from the code follow the code when the code drifts
    from the design, which hides the drift instead of exposing it.
*   **Reason for Rejection**: Rejected by author (Step 1, item 7): one hand-written module that
    mirrors the admin-service design's contract section, with the MSW handlers built only from
    it. Drift between the running service and the console is caught by the Goal 9 browser tests
    against the real service, not by generated types.

### Alternative G: Keep the Permissions tab's sync state in browser storage

Store the last sync outcome per role in `localStorage`, so that "sync failed, retry" survives a
reload.

*   **Pros**: An admin who reloads after a failed sync still sees the failure.
*   **Cons**: The stored value is one browser's view. If another administrator re-syncs the role,
    or this one does it from another browser, the stored "failed" stays wrong until the role is
    opened and synced again here. A wrong "synced" can lead an admin to assign a role whose
    realm role does not exist (PRD §9 Ordering).
*   **Reason for Rejection**: Moot. At the admin-service drafting gates the role resource gained
    server-side sync fields, `syncStatus` (`NEVER`, `SYNCED`, `FAILED`) and `lastSyncAt`, which
    answers AS-REQ-10. The Permissions tab reads those fields, so neither browser storage nor a
    cache-only state is needed.

### H: Options the PRD rules out

These were not weighed; the PRD decides them, and the design follows it.

*   **A runtime auth flag or a static-token fallback**, as in `cce-insights-ui`: ruled out by
    PRD §6, §9 and F-01 (C-01).
*   **Optimistic updates**: ruled out by PRD §2 and F-06, because writes fan out to Keycloak and
    the gateway and can fail part-way.
*   **A global error page for 403**: ruled out by F-04.
*   **A role-driven sidebar in the admin console**: ruled out by PRD §2 for CCE v2 (F-03).

--------------------------------------------------------------------------------

## Non-Functional Requirements

*   **Security.** No sign-in bypass (Goal 7). The access token lives only in memory in the
    `keycloak-js` instance. No credential, `.env` file or Keycloak export is committed (PRD §11):
    `.env` is git-ignored, `.env.example` holds only the four non-secret local values, and the
    browser tests read the test administrator's credentials from the environment or from the
    admin-service's git-ignored `.env`. In shared environments the console reaches the service
    only through the gateway (Caddy's `GATEWAY_UPSTREAM`, PRD §9).
*   **Rollout.** `VITE_*` values are inlined at build time, so each environment gets its own
    image (PRD §8.3). Rollback is redeploying the previous image. The console has no persistent
    state of its own.

--------------------------------------------------------------------------------

## Detailed Implementation Plan

Every file below is created under `cce-admin-ui/` at the root of this folder, unless its path
says otherwise. No existing file is modified. Versions are exact
([D-05](../../docs/designs/author-decisions.md#d-05-pinned-versions-checked-on-2026-11-10));
`package-lock.json` is committed.

### Order of work

1.  Scaffold and tooling (files 1 to 18). Check: `npm run lint` and `npm run build` pass on an
    empty app.
2.  Milestone F1: config, auth, contract, client, hooks base, shell, shared components, MSW
    (files 19 to 55 that F1 needs, and their tests).
3.  Milestone F2: roles list, role detail, Menus tab.
4.  Milestone F3: Permissions tab.
5.  Milestone F4: users list, create, edit.
6.  Milestone F5: menus catalog.
7.  Browser tests (files 95 to 104), once the admin-service stack runs.

### Target Files to Create/Modify

#### Project and tooling

##### 1. `package.json` (Create)

*   **Type / Scope**: npm manifest.
*   **Proposed Changes**: `"name": "cce-admin-ui"`, `"private": true`, `"type": "module"`,
    `"engines": { "node": ">=20.19.0 <23" }`. Scripts, exactly:
    *   `dev`: `vite`
    *   `build`: `tsc -b && vite build`
    *   `test`: `vitest`
    *   `test:ci`: `vitest run --coverage`
    *   `lint`: `eslint . && prettier --check .`
    *   `e2e`: `playwright test`

    Every version is written exactly as below, with no `^` or `~` (D-05).

    `dependencies`:

    | Package | Version |
    |---|---|
    | `react` | 18.3.1 |
    | `react-dom` | 18.3.1 |
    | `react-router` | 7.18.5 |
    | `@tanstack/react-query` | 5.107.1 |
    | `@tanstack/react-table` | 8.21.3 |
    | `keycloak-js` | 26.2.5 |
    | `react-hook-form` | 7.91.0 |
    | `zod` | 3.25.76 |
    | `@hookform/resolvers` | 3.10.0 |
    | `date-fns` | 4.4.0 |
    | `@heroicons/react` | 2.2.0 |

    `devDependencies`:

    | Package | Version |
    |---|---|
    | `typescript` | 5.9.3 |
    | `vite` | 6.4.6 |
    | `@vitejs/plugin-react` | 4.7.0 |
    | `tailwindcss` | 4.3.5 |
    | `@tailwindcss/vite` | 4.3.5 |
    | `vitest` | 3.2.7 |
    | `@vitest/coverage-v8` | 3.2.7 |
    | `jsdom` | 26.1.0 |
    | `@testing-library/react` | 16.3.3 |
    | `@testing-library/dom` | 10.4.2 |
    | `@testing-library/user-event` | 14.6.9 |
    | `@testing-library/jest-dom` | 6.10.0 |
    | `msw` | 2.15.0 |
    | `@playwright/test` | 1.65.0 |
    | `eslint` | 9.39.5 |
    | `@eslint/js` | 9.39.5 |
    | `typescript-eslint` | 8.72.2 |
    | `eslint-plugin-react-hooks` | 5.2.0 |
    | `globals` | 16.5.0 |
    | `prettier` | 3.9.10 |
    | `eslint-config-prettier` | 10.1.8 |
    | `@types/react` | 18.3.31 |
    | `@types/react-dom` | 18.3.7 |
    | `@types/node` | 22.20.5 |

    Install with `npm install --save-exact` (or write the versions by hand and run
    `npm install`), so `package.json` holds no ranges.
    Reason: PRD §6 and §8.3 stack and scripts (C-04, C-09); exact versions from D-05.

##### 2. `package-lock.json` (Create, generated)

*   **Type / Scope**: lockfile, produced by `npm install`. Committed so that `npm ci` in Docker
    installs the same versions.

##### 3. `tsconfig.json` (Create)

*   **Type / Scope**: TypeScript project references.
*   **Proposed Changes**: `"files": []`, references to `tsconfig.app.json` and
    `tsconfig.node.json`, so `tsc -b` checks both.

##### 4. `tsconfig.app.json` (Create)

*   **Type / Scope**: compiler settings for `src/`.
*   **Proposed Changes**: target ES2022, lib `["ES2022", "DOM", "DOM.Iterable"]`, module
    ESNext, moduleResolution bundler, `jsx: react-jsx`, `strict: true`,
    `noUncheckedIndexedAccess: true`, `noUnusedLocals: true`, `noUnusedParameters: true`,
    `verbatimModuleSyntax: true`, `noEmit: true`, `types: ["vite/client", "vitest/globals",
    "@testing-library/jest-dom"]`, include `["src"]`. Test files are included, so `tsc -b`
    type-checks the fake auth module (TP-1).

##### 5. `tsconfig.node.json` (Create)

*   **Type / Scope**: compiler settings for config files and browser tests.
*   **Proposed Changes**: same strictness, `types: ["node"]`, include `vite.config.ts`,
    `vitest.config.ts`, `playwright.config.ts`, `eslint.config.js` (with `allowJs`) and
    `e2e/**/*.ts`.

##### 6. `vite.config.ts` (Create)

*   **Type / Scope**: Vite configuration.
*   **Proposed Changes**: plugins `react()` and `tailwindcss()`. `server.port` 3002 with
    `strictPort: true`. `server.proxy` maps `/admin/v1` to `http://localhost:8085` with
    `changeOrigin: false` (D-03). In the function form of the config, when `command` is
    `build`, load `VITE_*` values with `loadEnv(mode, process.cwd(), 'VITE_')` and throw
    `Error('VITE_KEYCLOAK_REALM is required for a build (PRD §8.3)')` if
    `VITE_KEYCLOAK_REALM` is empty after trimming (C-02).

##### 7. `vitest.config.ts` (Create)

*   **Type / Scope**: test runner configuration.
*   **Proposed Changes**: plugin `react()`. `test.environment` `jsdom`, `globals: true`,
    `setupFiles: ['./src/test/setup.ts']`, `include: ['src/**/*.test.{ts,tsx}']`.
    `test.env`: `VITE_KEYCLOAK_URL=http://keycloak.test/auth`, `VITE_KEYCLOAK_REALM=cce`,
    `VITE_KEYCLOAK_CLIENT_ID=cce-admin-ui`, `VITE_API_BASE_URL=http://localhost:3002`. The base
    URL is absolute in tests because Node's `fetch` rejects relative URLs; the MSW handlers
    match `*/admin/v1/...`. Coverage: provider `v8`, include `src/**`, exclude `src/test/**`,
    `src/main.tsx`, `**/*.d.ts`; thresholds lines 80, statements 80, functions 80, branches 70.

##### 8. `playwright.config.ts` (Create)

*   **Type / Scope**: browser test configuration (Goal 9).
*   **Proposed Changes**: `testDir: 'e2e'`, `workers: 1`, `fullyParallel: false`,
    `retries: 0`, `timeout: 60_000`, `use: { baseURL: 'http://localhost:3002', trace:
    'retain-on-failure' }`, `globalSetup: './e2e/global-setup.ts'`. Projects: `setup` (matches
    `auth.setup.ts`) and `chromium` (Desktop Chrome, `storageState: 'e2e/.auth/admin.json'`,
    `dependencies: ['setup']`). `webServer: { command: 'npm run dev', url:
    'http://localhost:3002', reuseExistingServer: true, timeout: 60_000 }`.

##### 9. `eslint.config.js` (Create)

*   **Type / Scope**: lint rules.
*   **Proposed Changes**: flat config. Ignores `dist`, `coverage`, `playwright-report`,
    `test-results`, `e2e/.auth`. `@eslint/js` recommended, `typescript-eslint` recommended
    type-checked for `src/**` and `e2e/**`, `eslint-plugin-react-hooks` recommended,
    `eslint-config-prettier` last. Browser globals for `src/**`, Node globals for config files
    and `e2e/**`. The layer rules from the Technical Plan with
    `@typescript-eslint/no-restricted-imports`:
    *   for `src/pages/**` and `src/components/**`: pattern `**/api/*` with
        `allowTypeImports: true`;
    *   for `src/components/**`: also `**/hooks/*` and `@tanstack/react-query`;
    *   for `src/**` except `src/auth/**`: `keycloak-js`;
    *   for every file except `src/test/**`, `**/*.test.ts`, `**/*.test.tsx` and `e2e/**`:
        `msw`, `msw/*` and `@playwright/test`.

##### 10. `.prettierrc.json` (Create)

*   **Type / Scope**: formatting.
*   **Proposed Changes**: `{ "singleQuote": true, "semi": true, "printWidth": 100,
    "trailingComma": "all" }`.

##### 11. `.prettierignore` (Create)

*   **Proposed Changes**: `dist`, `coverage`, `playwright-report`, `test-results`, `e2e/.auth`,
    `package-lock.json`.

##### 12. `index.html` (Create)

*   **Type / Scope**: Vite entry page.
*   **Proposed Changes**: `lang="en"`, title "CCE Admin", `<div id="root">`, module script
    `/src/main.tsx`.

##### 13. `.env.example` (Create)

*   **Type / Scope**: local configuration template (PRD §8.3, admin-service §9.3).
*   **Proposed Changes**: the four variables with the local values
    `VITE_KEYCLOAK_URL=http://localhost:8180/auth`, `VITE_KEYCLOAK_REALM=cce`,
    `VITE_KEYCLOAK_CLIENT_ID=cce-admin-ui`, `VITE_API_BASE_URL=` (empty, so the Vite proxy is
    used), each with a one-line comment. A comment says the browser tests read
    `CCE_TEST_ADMIN_USERNAME` and `CCE_TEST_ADMIN_PASSWORD` from the environment or from
    `../cce-admin-service/.env`, and that they must not be added here.

##### 14. `.gitignore` (Create)

*   **Proposed Changes**: `node_modules`, `dist`, `coverage`, `.env`, `.env.*` with
    `!.env.example`, `playwright-report`, `test-results`, `e2e/.auth`. Reason: PRD §11 (no
    `.env` files committed); `e2e/.auth` holds a Keycloak session.

##### 15. `.dockerignore` (Create)

*   **Proposed Changes**: `node_modules`, `dist`, `coverage`, `.env`, `.env.*`,
    `playwright-report`, `test-results`, `e2e/.auth`, `.git`.

##### 16. `Dockerfile` (Create)

*   **Type / Scope**: reference build (PRD §6, Goal 8).
*   **Proposed Changes**: three stages.
    *   `build`: `FROM node:20.20.2-alpine` (D-05), `WORKDIR /app`, copy `package.json` and
        `package-lock.json`, `RUN npm ci --ignore-scripts`, copy the rest. `ARG
        VITE_API_BASE_URL=""`, `ARG VITE_KEYCLOAK_URL=""`, `ARG VITE_KEYCLOAK_REALM` (no
        default), `ARG VITE_KEYCLOAK_CLIENT_ID="cce-admin-ui"`, the matching `ENV` lines,
        `RUN npm run build`.
    *   `verify`: `FROM build`, `RUN npm run lint && npm run test:ci`, then a `RUN` that fails
        if `grep -rqE 'mockServiceWorker|setupServer' dist` finds a match.
    *   final: `FROM caddy:2.11.9-alpine` (D-05), copy `/app/dist` to `/srv`, copy `Caddyfile` to
        `/etc/caddy/Caddyfile`, `EXPOSE 3002`, `CMD ["caddy", "run", "--config",
        "/etc/caddy/Caddyfile"]`.

##### 17. `Caddyfile` (Create)

*   **Type / Scope**: static serving and API proxy.
*   **Proposed Changes**: site `:3002`; `encode zstd gzip`; `handle /admin/v1/*` with
    `reverse_proxy {$GATEWAY_UPSTREAM}`; default `handle` with `root * /srv`, a
    `Cache-Control "public, max-age=31536000, immutable"` header on `/assets/*`,
    `try_files {path} /index.html`, `file_server`; headers `X-Frame-Options "SAMEORIGIN"`,
    `X-Content-Type-Options "nosniff"`, `Referrer-Policy "strict-origin-when-cross-origin"`.

##### 18. `README.md` (Create)

*   **Type / Scope**: developer guide.
*   **Proposed Changes**: what the console is, with links to this design and the admin-service
    design; prerequisites (Node 22.22 or 20.19+, npm 10, Docker); setup (`npm ci`,
    `cp .env.example .env`); the six scripts; running against the real service (start the
    admin-service stack as in admin-service §9.3, then `npm run dev`, open
    `http://localhost:3002`); browser tests (one-time `npx playwright install chromium`, the
    stack and service running, credentials from the environment or
    `../cce-admin-service/.env`, `npm run e2e`, reset with the admin-service's
    `docker compose --profile app down -v`); Docker (`docker build --build-arg
    VITE_KEYCLOAK_REALM=... .`, `docker build --target verify ...`, `docker run -e
    GATEWAY_UPSTREAM=... -p 3002:3002`); the layer rules.

#### Application entry

##### 19. `src/main.tsx` (Create)

*   **Type / Scope**: entry point.
*   **Proposed Changes**: import `index.css`. Call `readConfig()`. On failure, render
    `StartupErrorScreen` with the missing variable names and stop. Otherwise call
    `initAuth(config)`; when it resolves, render `<StrictMode>`, `QueryClientProvider` with
    `createQueryClient()`, and `RouterProvider` with `createBrowserRouter(routes)`; when it
    rejects, render `StartupErrorScreen` with "Cannot reach the sign-in service at {url}."

##### 20. `src/routes.tsx` (Create)

*   **Type / Scope**: route objects (shared by the app and tests).
*   **Proposed Changes**: exports `routes`: one route with element `AppLayout` and children:
    index → `Navigate` to `/users`; `users` → `UsersListPage`; `users/new` → `UserCreatePage`;
    `users/:id` → `UserEditPage`; `roles` → `RolesListPage`; `roles/:id` → `RoleDetailPage`;
    `menus` → `MenusPage`; `*` → `NotFoundPage`.

##### 21. `src/index.css` (Create)

*   **Proposed Changes**: `@import "tailwindcss";`.

##### 22. `src/vite-env.d.ts` (Create)

*   **Proposed Changes**: `/// <reference types="vite/client" />` and an `ImportMetaEnv`
    interface with the four `VITE_*` variables as optional strings.

##### 23. `src/config.ts` (Create)

*   **Type / Scope**: configuration reader (PRD §8.3, C-02).
*   **Proposed Changes**: exports type `AppConfig { keycloakUrl, keycloakRealm,
    keycloakClientId, apiBaseUrl }` and `readConfig(): { ok: true; config: AppConfig } | { ok:
    false; missing: string[] }`, with the defaults and trimming from the Technical Plan. Also
    exports `getApiBaseUrl()` for `client.ts`, which reads the same value.

#### Auth

##### 24. `src/auth/keycloak.ts` (Create)

*   **Type / Scope**: the only module that uses `keycloak-js` (F-01).
*   **Proposed Changes**: a module-level `Keycloak` instance and refresh timer. Exports:
    *   `initAuth(config: AppConfig): Promise<void>`: creates the instance, calls `init` with
        `onLoad: 'login-required'`, `pkceMethod: 'S256'`, `checkLoginIframe: false`; rejects if
        `init` resolves without a session; starts a 60 000 ms interval that calls
        `updateToken(70)` and calls `login()` if it rejects.
    *   `getToken(): string | undefined`.
    *   `refreshToken(): Promise<boolean>`: `updateToken(-1)`; `true` on success; on failure
        calls `login()` and returns `false`.
    *   `login(): Promise<void>` and `logout(): Promise<void>` (redirect to
        `window.location.origin + '/'`).

##### 25. `src/auth/useAuth.ts` (Create)

*   **Type / Scope**: hook for pages.
*   **Proposed Changes**: returns `{ logout }` from `keycloak.ts`, so `AppLayout` can pass a
    sign-out callback to the header without the header importing auth.

#### API layer

##### 26. `src/api/contract.ts` (Create)

*   **Type / Scope**: the single contract module (D-01, Goal 6).
*   **Proposed Changes**: types only, plus three constant arrays. Mirrors admin-service §4.1 to
    §4.5 with the same property names:
    *   Envelopes: `SuccessEnvelope<T> { data; pagination?; warnings? }`, `Pagination { page;
        pageSize; totalItems; totalPages }`, `WarningCode = 'DETAIL_WITHOUT_PARENT' |
        'ACTION_EMAIL_NOT_SENT'`, `Warning { code; message; menuId?; parentMenuId? }`,
        `ErrorEnvelope { error: { code: string; message; details?: FieldError[];
        partialState?: PartialState } }`, `FieldError { field; message }`,
        `PartialStateOperation` (the nine operations of §4.2), `PartialState { operation;
        entityId: string | null; failedStep; completedSteps: string[]; keycloakUserId: string
        | null; auditLogId: string | null }`, `ErrorCode` (every code in §4.2).
    *   Users: `UserStatus = 'ACTIVE' | 'INACTIVE' | 'LOCKED'`, `UserRoleRef { id; name;
        displayName }`, `User` (fields of §4.4), `UserListQuery { status?; roleId?;
        facilityScope?; q?; page?; pageSize? }`, `CreateUserRequest { username; email;
        firstName; lastName; facilityScope?; roleIds }`, `UpdateUserRequest { email;
        firstName; lastName; facilityScope: string | null }`, `ChangeUserStatusRequest {
        status }`, `ReplaceUserRolesRequest { roleIds }`, `ResetPasswordResult { userId; email
        }`.
    *   Roles: `SyncStatus = 'NEVER' | 'SYNCED' | 'FAILED'`, `Role` (fields of §4.4),
        `CreateRoleRequest { name; displayName; description? }`, `UpdateRoleRequest {
        displayName; description: string | null }`, `RoleMenus { roleId; menuIds }`,
        `ReplaceRoleMenusRequest { menuIds }`, `PermissionRow { id: number; httpMethod;
        uriPattern; description: string | null; resourceCategory: string | null; implicit }`,
        `PermissionRowInput { httpMethod; uriPattern; description?; resourceCategory? }`,
        `RolePermissions { roleId; syncStatus; lastSyncAt: string | null; permissions }`,
        `ReplaceRolePermissionsRequest { permissions: PermissionRowInput[] }`, `RoleSyncResult
        { roleId; syncStatus; lastSyncAt }`.
    *   Menus: `MenuType = 'NAV' | 'DETAIL'`, `Menu` (fields of §4.4, with `children: Menu[]`),
        `CreateMenuRequest { key; label; path; icon?; type; parentId?; sortOrder; isActive? }`,
        `UpdateMenuRequest { label; path; icon: string | null; parentId: string | null;
        sortOrder; isActive }`.
    *   `Me { id; username; roles: string[]; facilityScope: string | null }`.
    *   Constants: `USER_STATUSES`, `SYNC_STATUSES`, `PERMISSION_METHODS = ['GET', 'POST',
        'PUT', 'PATCH', 'DELETE']`, the fixed list of the contract (CQ-1).

    The header comment links `../../egm/admin-service/design_draft.md` §4 (and, after
    approval, the saved design in `docs/designs/`) and states "mirrors the contract of
    2026-11-10".

##### 27. `src/api/ApiError.ts` (Create)

*   **Proposed Changes**: `class ApiError extends Error` with `status: number`, `code: string`,
    `details?: FieldError[]`, `partialState?: PartialState`; and the two console codes
    `CLIENT_NETWORK_ERROR`, `CLIENT_UNEXPECTED_RESPONSE` as exported constants.

##### 28. `src/api/client.ts` (Create)

*   **Type / Scope**: the typed `fetch` wrapper (F-02).
*   **Proposed Changes**: `apiRequest<T>(method, path, options?: { query?: Record<string,
    string | number | undefined | null>; body?: unknown; signal?: AbortSignal }):
    Promise<SuccessEnvelope<T> | undefined>`. Behaviour as in the Technical Plan: URL building,
    headers, `getToken()` (calls `login()` and throws a 401 `ApiError` if there is no token),
    the 401 refresh-once path, 204 to `undefined`, JSON parse, error envelope to `ApiError`,
    the two `CLIENT_` codes.

##### 29. `src/api/me.ts` (Create)

*   **Proposed Changes**: `getMe(): Promise<Me>` (`GET /me`).

##### 30. `src/api/users.ts` (Create)

*   **Proposed Changes**: `listUsers(query): Promise<{ items: User[]; pagination: Pagination
    }>`, `getUser(id)`, `createUser(body): Promise<{ user: User; warnings: Warning[] }>`,
    `updateUser(id, body)`, `changeUserStatus(id, body)`, `replaceUserRoles(id, body)`,
    `resetUserPassword(id): Promise<ResetPasswordResult>`. Paths and methods from admin-service
    §4.5.

##### 31. `src/api/roles.ts` (Create)

*   **Proposed Changes**: `listRoles({ page, pageSize })`, `listAllRoles()` (pages of 100 until
    `totalPages`), `getRole(id)`, `createRole(body)`, `updateRole(id, body)`,
    `deleteRole(id): Promise<void>`, `syncRole(id): Promise<RoleSyncResult>`.

##### 32. `src/api/roleMenus.ts` (Create)

*   **Proposed Changes**: `getRoleMenus(id): Promise<RoleMenus>`, `replaceRoleMenus(id, body):
    Promise<{ data: RoleMenus; warnings: Warning[] }>`.

##### 33. `src/api/rolePermissions.ts` (Create)

*   **Proposed Changes**: `getRolePermissions(id): Promise<RolePermissions>`,
    `replaceRolePermissions(id, body): Promise<RolePermissions>`.

##### 34. `src/api/menus.ts` (Create)

*   **Proposed Changes**: `getMenuTree(): Promise<Menu[]>`, `createMenu(body)`,
    `updateMenu(id, body)`. No delete (Non-Goals).

#### Hooks

##### 35. `src/hooks/queryKeys.ts` (Create)

*   **Proposed Changes**: one function per key in the Technical Plan, so no key is written by
    hand elsewhere.

##### 36. `src/hooks/queryClient.ts` (Create)

*   **Proposed Changes**: `createQueryClient()` with the defaults from the Technical Plan (stale
    time, no refetch on focus, retry rule, mutations not retried). Used by `main.tsx` and the
    test render helper.

##### 37. `src/hooks/useMe.ts` (Create)

*   **Proposed Changes**: `useMe()` wrapping `getMe`.

##### 38. `src/hooks/useUsers.ts` (Create)

*   **Proposed Changes**: `useUserList(query)` (with `placeholderData: keepPreviousData`),
    `useUser(id)`, and one mutation hook per write in `api/users.ts`. Each mutation invalidates
    as in the invalidation table, on success and on a 502 whose `completedSteps` is not empty.

##### 39. `src/hooks/useRoles.ts` (Create)

*   **Proposed Changes**: `useRoleList({ page, pageSize })`, `useAllRoles()`, `useRole(id)`,
    `useCreateRole`, `useUpdateRole`, `useDeleteRole`, `useSyncRole` (invalidates `['roles']`
    on success and on every 502).

##### 40. `src/hooks/useRoleMenus.ts` (Create)

*   **Proposed Changes**: `useRoleMenus(id)`, `useReplaceRoleMenus(id)`.

##### 41. `src/hooks/useRolePermissions.ts` (Create)

*   **Proposed Changes**: `useRolePermissions(id)`, `useReplaceRolePermissions(id)`.

##### 42. `src/hooks/useMenus.ts` (Create)

*   **Proposed Changes**: `useMenuTree()`, `useCreateMenu()`, `useUpdateMenu()`.

##### 43. `src/hooks/useDebouncedValue.ts` (Create)

*   **Proposed Changes**: `useDebouncedValue(value, 300)` for the two text filters.

##### 44. `src/hooks/usePagingParams.ts` (Create)

*   **Proposed Changes**: reads `page` and `pageSize` from the URL search parameters, replaces
    invalid values with 1 and 20 (page sizes 20, 50, 100), and returns setters that keep the
    other search parameters.

##### 45. `src/hooks/useUnsavedChangesGuard.ts` (Create)

*   **Proposed Changes**: `useUnsavedChangesGuard(isDirty)` wraps `useBlocker` and returns `{
    blocked, proceed, reset }` for a `ConfirmDialog` ("You have unsaved changes. Leave without
    saving?").

#### Utilities and schemas

##### 46. `src/utils/describeError.ts` (Create)

*   **Proposed Changes**: `describeError(error: unknown): { kind; code; message; details?;
    partialState? }` following the kinds table. Non-`ApiError` values become `unexpected`.

##### 47. `src/utils/formErrors.ts` (Create)

*   **Proposed Changes**: `toFormPath(field)` (`a[2].b` → `a.2.b`) and
    `applyServerErrors(setError, error, formFields, codeToField)`: sets field errors from
    `details` and from conflict codes, and returns the messages that belong at the top of the
    form. `roleErrorMessages(error, sentRoleIds, roles)` builds the per-role messages for
    `ROLE_NOT_SYNCED` and `ROLE_NOT_ASSIGNABLE`.

##### 48. `src/utils/format.ts` (Create)

*   **Proposed Changes**: `formatInstant(iso: string | null, empty = 'Never')` with date-fns
    `parseISO` and `format(..., 'yyyy-MM-dd HH:mm')`.

##### 49. `src/utils/menuTree.ts` (Create)

*   **Proposed Changes**: `flattenMenus(tree)`, `menuById(tree)`, `navMenus(tree)`, and
    `detailsWithoutParent(tree, selectedIds)` returning `{ menu, parent }` pairs for the client
    warning.

##### 50. `src/utils/permissions.ts` (Create)

*   **Proposed Changes**: `normalizeRow(row)`, `explicitRows(rows)`, `sameRuleSet(a, b)`
    (set comparison of normalized rows), and `toRequestRows(formRows)` (drops empty optional
    fields).

##### 51. `src/schemas/rules.ts` (Create)

*   **Proposed Changes**: the patterns and limits of the Forms and validation table as named
    constants, with a comment linking admin-service §4.4.

##### 52. `src/schemas/userSchemas.ts` (Create)

*   **Proposed Changes**: `createUserSchema`, `profileSchema`, `userRolesSchema`.

##### 53. `src/schemas/roleSchemas.ts` (Create)

*   **Proposed Changes**: `createRoleSchema`, `roleDisplaySchema`.

##### 54. `src/schemas/permissionSchemas.ts` (Create)

*   **Proposed Changes**: `permissionRowSchema` and `permissionsFormSchema(implicitRows)`, a
    factory so the "equal to an implicit row" check knows the implicit rows; duplicate and
    implicit errors are placed on `permissions.{i}.uriPattern`.

##### 55. `src/schemas/menuSchemas.ts` (Create)

*   **Proposed Changes**: `menuFormSchema(navIds)`, with the parent rule (required for `DETAIL`
    and one of `navIds`, absent for `NAV`).

#### Components

All components receive data and callbacks as props. Every input has a `<label>`, and every
button has a visible name, so tests can find them by role and label.

##### 56. `src/components/layout/Sidebar.tsx` (Create)

*   **Proposed Changes**: "CCE Admin" title and three `NavLink`s: Users (`UsersIcon`), Roles
    (`ShieldCheckIcon`), Menus (`Squares2X2Icon`). No props (F-03).

##### 57. `src/components/layout/Header.tsx` (Create)

*   **Proposed Changes**: props `{ username?; roles?; error?; onSignOut }`. Shows the username
    and roles joined by ", ", or the described error, and a "Sign out" button.

##### 58. `src/components/shared/InlineError.tsx` (Create)

*   **Proposed Changes**: props `{ error: DescribedError; onRetry? }`; `role="alert"`; text by
    kind as in the kinds table.

##### 59. `src/components/shared/SyncFailedNotice.tsx` (Create)

*   **Proposed Changes**: props `{ partialState; message; onRetry?; retryLabel?; links? }`; the
    per-operation text table from the Technical Plan; lists failed step, completed steps,
    Keycloak user id and audit log id; `role="alert"`.

##### 60. `src/components/shared/ConfirmDialog.tsx` (Create)

*   **Proposed Changes**: a `div` with `role="dialog"`, `aria-modal="true"`, a title, body,
    Confirm and Cancel buttons, and a pending state on Confirm. Not the native `<dialog>`
    element, because jsdom does not implement `showModal`.

##### 61. `src/components/shared/StatusBadge.tsx` (Create)

*   **Proposed Changes**: user status badge (green `ACTIVE`, gray `INACTIVE`, amber `LOCKED`).

##### 62. `src/components/shared/SyncStatusBadge.tsx` (Create)

*   **Proposed Changes**: props `{ syncStatus; lastSyncAt }`: green "Synced", gray "Never
    synced", red "Sync failed", with the formatted time when present.

##### 63. `src/components/shared/Pagination.tsx` (Create)

*   **Proposed Changes**: props `{ page; totalPages; totalItems; pageSize; onPageChange;
    onPageSizeChange }`: Previous, Next, "Page {page} of {totalPages} · {totalItems} items",
    page-size select (20, 50, 100).

##### 64. `src/components/shared/PageHeader.tsx`, `LoadingSpinner.tsx`, `EmptyState.tsx`, `NotFoundPanel.tsx`, `FormErrorSummary.tsx`, `WarningList.tsx`, `SuccessNotice.tsx` (Create)

*   **Proposed Changes**: small presentational components: page title and actions; spinner with
    `role="status"`; empty-list message; "not found" panel with a back link; list of form-level
    error messages; list of warnings (`role="status"`); success message (`role="status"`).

##### 65. `src/components/users/UserFilters.tsx` (Create)

*   **Proposed Changes**: status select, role select (props: assignable roles), facility scope
    and search inputs; reports changes through callbacks.

##### 66. `src/components/users/UserTable.tsx` (Create)

*   **Proposed Changes**: TanStack Table with `manualPagination`, the columns and row actions
    of the Users list; props: users, pagination, action callbacks, pending action id.

##### 67. `src/components/users/RoleCheckboxList.tsx` (Create)

*   **Proposed Changes**: props `{ roles; selectedIds; onChange; disabled; roleErrors }`; one
    checkbox per role with display name, name and `SyncStatusBadge` plus hint when not
    `SYNCED`.

##### 68. `src/components/users/UserCreateForm.tsx` (Create)

*   **Proposed Changes**: React Hook Form with `createUserSchema`; props `{ roles; onSubmit;
    pending; locked; serverError }`; uses `applyServerErrors`.

##### 69. `src/components/users/UserProfileForm.tsx`, `UserRolesForm.tsx`, `UserStatusActions.tsx`, `UserPasswordAction.tsx` (Create)

*   **Proposed Changes**: the four User edit sections, each with its own submit callback,
    pending flag and error props.

##### 70. `src/components/roles/RoleTable.tsx` (Create)

*   **Proposed Changes**: TanStack Table of roles with the Roles list columns and the Delete
    rules.

##### 71. `src/components/roles/RoleCreateForm.tsx` (Create)

*   **Proposed Changes**: React Hook Form with `createRoleSchema`, the permanent-name warning,
    and a `ConfirmDialog` before `onSubmit` is called.

##### 72. `src/components/roles/RoleDisplayForm.tsx` (Create)

*   **Proposed Changes**: display name and description; disabled with the system-role note when
    `isSystem`.

##### 73. `src/components/roles/MenuCheckboxTree.tsx` (Create)

*   **Proposed Changes**: props `{ tree; selectedIds; onToggle; disabled }`; renders the
    two-level tree with checkboxes labelled by menu label, and "inactive" marks.

##### 74. `src/components/roles/PermissionsEditor.tsx` (Create)

*   **Proposed Changes**: the read-only implicit table and the editable field-array table;
    props `{ implicitRows; explicitRows; disabled; onSubmit; pending; serverError }`; Save
    enabled by `sameRuleSet`.

##### 75. `src/components/roles/SyncStatusPanel.tsx` (Create)

*   **Proposed Changes**: badge, last sync, help text (CQ-2), Re-sync button with pending
    state.

##### 76. `src/components/menus/iconMap.ts` (Create)

*   **Proposed Changes**: the fixed map of 15 icon names to Heroicons 2 outline components
    (Technical Plan), and `QuestionMarkCircleIcon` as the fallback.

##### 77. `src/components/menus/MenuIcon.tsx` (Create)

*   **Proposed Changes**: props `{ name: string | null }`; renders the mapped icon, the fallback
    with the raw name, or nothing.

##### 78. `src/components/menus/MenuTreeTable.tsx` (Create)

*   **Proposed Changes**: the catalog table with indentation and the row actions of the Menus
    catalog.

##### 79. `src/components/menus/MenuForm.tsx` (Create)

*   **Proposed Changes**: React Hook Form with `menuFormSchema`; create and edit modes; key and
    type read-only in edit mode; icon select as described.

#### Pages

##### 80. `src/pages/AppLayout.tsx` (Create)

*   **Proposed Changes**: uses `useMe` and `useAuth`; renders `Sidebar`, `Header` and
    `<Outlet/>`.

##### 81. `src/pages/StartupErrorScreen.tsx` (Create)

*   **Proposed Changes**: props `{ title; lines }`; static page with no hooks from `hooks/`,
    used by `main.tsx` before the router exists.

##### 82. `src/pages/UsersListPage.tsx` (Create)

*   **Proposed Changes**: URL search parameters, `usePagingParams`, debounced filters,
    `useUserList`, `useAllRoles`, the status and reset-password mutations with
    `ConfirmDialog`, the row-message area, and the past-last-page correction.

##### 83. `src/pages/UserCreatePage.tsx` (Create)

*   **Proposed Changes**: `useAllRoles`, `useCreateUser`; builds `CreateUserRequest` field by
    field (omits `facilityScope` when empty); on 201 navigates to `/users/{id}` with state `{
    created: true, email, warnings }`; on 502 shows `SyncFailedNotice` and sets `locked` when
    `completedSteps` is not empty.

##### 84. `src/pages/UserEditPage.tsx` (Create)

*   **Proposed Changes**: `useUser`, `useAllRoles`, the four mutations; shows the created
    message from navigation state; `NotFoundPanel` on `notFound`.

##### 85. `src/pages/RolesListPage.tsx` (Create)

*   **Proposed Changes**: `useRoleList`, `useCreateRole`, `useDeleteRole`; navigates to the new
    role on 201; `SyncFailedNotice` for `ROLE_CREATE` and `ROLE_DELETE`.

##### 86. `src/pages/RoleDetailPage.tsx` (Create)

*   **Proposed Changes**: `useRole`, `useUpdateRole`, `useDeleteRole`; the header; tabs from the
    `tab` search parameter (`menus` default, `permissions`); renders `RoleMenusTab` or
    `RolePermissionsTab`.

##### 87. `src/pages/role-detail/RoleMenusTab.tsx` (Create)

*   **Proposed Changes**: `useMenuTree`, `useRoleMenus`, `useReplaceRoleMenus`; selection
    state; client warnings from `detailsWithoutParent`; server warnings after save;
    `useUnsavedChangesGuard`.

##### 88. `src/pages/role-detail/RolePermissionsTab.tsx` (Create)

*   **Proposed Changes**: `useRolePermissions`, `useReplaceRolePermissions`, `useSyncRole`;
    `SyncStatusPanel`, `PermissionsEditor`, `SyncFailedNotice`; form reset rules after a 502;
    `useUnsavedChangesGuard`.

##### 89. `src/pages/MenusPage.tsx` (Create)

*   **Proposed Changes**: `useMenuTree`, `useCreateMenu`, `useUpdateMenu`; add, edit,
    deactivate and activate flows with `ConfirmDialog`.

##### 90. `src/pages/NotFoundPage.tsx` (Create)

*   **Proposed Changes**: "Page not found" with a link to `/users`.

#### Test support

##### 91. `src/test/setup.ts` (Create)

*   **Proposed Changes**: imports `@testing-library/jest-dom/vitest`;
    `vi.mock('../auth/keycloak', () => import('./fakeAuth'))`; starts the MSW server with
    `onUnhandledRequest: 'error'` before all tests, resets handlers and the store after each,
    closes after all.

##### 92. `src/test/fakeAuth.ts` (Create)

*   **Proposed Changes**: `initAuth`, `getToken` (returns `'test-token'`), `refreshToken`,
    `login`, `logout` as `vi.fn()`s with default behaviour, the whole object written with
    `satisfies typeof import('../auth/keycloak')` (TP-1).

##### 93. `src/test/renderWithProviders.tsx` (Create)

*   **Proposed Changes**: `renderRoute(path)`: a fresh `createQueryClient()` with retries off,
    `createMemoryRouter(routes, { initialEntries: [path] })`, and `userEvent.setup()`; returns
    the render result, the user-event instance and the router.

##### 94. `src/test/mocks/fixtures.ts`, `src/test/mocks/db.ts`, `src/test/mocks/handlers.ts`, `src/test/mocks/server.ts` (Create)

*   **Proposed Changes**:
    *   `fixtures.ts`: the seed and test data described under MSW, with fixed UUIDs, typed from
        `contract.ts`.
    *   `db.ts`: an in-memory store built from the fixtures, with `reset()`, and the service
        rules the handlers need (filters, search, pagination, replace, no-op, implicit rows,
        warnings, 409s, `LAST_SYSTEM_ADMIN`).
    *   `handlers.ts`: one `http.*` handler per endpoint the console calls, under
        `*/admin/v1/...`, typed with the contract's request and response types; 400
        `VALIDATION_ERROR` on unknown body properties and bad paging; a helper
        `errorResponse(status, code, message, extra?)` for test overrides.
    *   `server.ts`: `setupServer(...handlers)`.

#### Unit and component tests

Each test file covers the happy path and at least one error code for its screen or module
(PRD §11).

| File | Cases |
|---|---|
| `src/__tests__/config.test.ts` | defaults applied; empty realm reported missing; trimming; trailing `/` removed from the API base URL |
| `src/auth/__tests__/keycloak.test.ts` | TP-1 cases: `init` options; no session → reject; 60 s interval calls `updateToken(70)`; refresh failure calls `login`; `refreshToken` true and false |
| `src/api/__tests__/client.test.ts` | URL and query building; headers; envelope unwrap with `pagination` and `warnings`; 204; error envelope with `details` and `partialState`; `CLIENT_UNEXPECTED_RESPONSE`; `CLIENT_NETWORK_ERROR`; 401 then 200; 401 twice; `refreshToken` false |
| `src/utils/__tests__/describeError.test.ts` | every code in the kinds table maps to its kind; the `id`-detail case maps to `notFound` |
| `src/utils/__tests__/formErrors.test.ts` | path conversion; field and top-level split; conflict codes to fields; role messages from `roleIds[i]` |
| `src/utils/__tests__/menuTree.test.ts` | flatten; `detailsWithoutParent` with and without the parent |
| `src/utils/__tests__/permissions.test.ts` | normalization; set comparison ignores order and case of method; empty optional fields dropped |
| `src/schemas/__tests__/schemas.test.ts` | each rule in the Forms and validation table, one pass and one fail case each; duplicate and implicit-row errors on the right path |
| `src/hooks/__tests__/useAllRoles.test.tsx` | three pages of roles joined in order; non-assignable roles still returned (filtered by callers) |
| `src/components/shared/__tests__/SyncFailedNotice.test.tsx` | each of the nine operations shows its text; empty `completedSteps` shows "Nothing was changed."; ids shown when present |
| `src/pages/__tests__/AppLayout.test.tsx` | header shows username and roles; `/me` 403 shows the message and the sidebar still shows three links; Sign out calls `logout` |
| `src/pages/__tests__/UsersListPage.test.tsx` | filters and paging to query parameters; debounce; out-of-range page corrected; past-last-page correction; status actions with confirm; reset password shows the email; `LAST_SYSTEM_ADMIN` inline; 403 inline |
| `src/pages/__tests__/UserCreatePage.test.tsx` | client validation; one `POST` with exact body; navigation and created message; `ACTION_EMAIL_NOT_SENT`; `USERNAME_IN_USE` and `EMAIL_IN_USE` on fields; `ROLE_NOT_SYNCED` names the role; 502 with and without completed steps |
| `src/pages/__tests__/UserEditPage.test.tsx` | profile `PUT` with `facilityScope: null` when empty; roles Save disabled until changed, sends the full set; status change; reset password; `LAST_SYSTEM_ADMIN`; 404 shows `NotFoundPanel` |
| `src/pages/__tests__/RolesListPage.test.tsx` | name rule; warning and confirm; `POST` only after Confirm; navigation to the new role; `DUPLICATE_ROLE_NAME` on the field; 502 `ROLE_CREATE` with link; Delete hidden for system roles and disabled when assigned; 403 inline |
| `src/pages/__tests__/RoleMenusTab.test.tsx` | tree and current selection; Save disabled until changed; full-set body; client warning; server warning with labels; system role read-only; unsaved-changes guard on tab switch |
| `src/pages/__tests__/RolePermissionsTab.test.tsx` | implicit rows read-only and not sent; add and remove rows; duplicate and implicit-equal rows rejected; Save disabled until changed; 200 shows synced; 502 with steps refetches and shows `FAILED`; 502 without steps keeps edits; Re-sync 200 and 502; system role read-only with Re-sync enabled |
| `src/pages/__tests__/MenusPage.test.tsx` | tree with indentation and icons; unknown icon fallback; add `NAV`; add `DETAIL` under a `NAV`; `DUPLICATE_MENU_KEY` on key; edit without key and type in the body; deactivate and activate send the full body with `isActive` flipped |

#### Browser tests

##### 95. `e2e/env.ts` (Create)

*   **Proposed Changes**: `loadAdminCredentials()`: reads `CCE_TEST_ADMIN_USERNAME` and
    `CCE_TEST_ADMIN_PASSWORD` from `process.env`; if either is missing and
    `../cce-admin-service/.env` exists, calls `process.loadEnvFile` on it and reads again; throws
    a message naming both variables if they are still missing. Also exports the fixed URLs:
    console `http://localhost:3002`, service `http://localhost:8085/admin/v1`, Keycloak token
    endpoint `http://localhost:8180/auth/realms/cce/protocol/openid-connect/token`, stand-in
    `http://localhost:8090/_stub/mode`, Mailpit `http://localhost:8025/api/v1/messages`.

##### 96. `e2e/global-setup.ts` (Create)

*   **Proposed Changes**: checks the service health and the stand-in mode endpoint; sets the
    stand-in to `ok`; fails with the admin-service §9.3 start commands if either check fails.

##### 97. `e2e/auth.setup.ts` (Create)

*   **Proposed Changes**: opens `/`, waits for the Keycloak login page at `localhost:8180`,
    fills `#username` and `#password`, clicks `#kc-login`, waits for `/users`, checks the
    header shows the username, saves the storage state to `e2e/.auth/admin.json`.

##### 98. `e2e/fixtures.ts` (Create)

*   **Proposed Changes**: a Playwright fixture `api` that gets a token from `cce-e2e` with the
    password grant and offers `createRole(nameStem)`, `setRolePermissions(id, rows)`,
    `createUser(stem, roleIds)`, `getUser(id)`; a `suffix` fixture (timestamp plus random
    digits, upper case for role names, lower case for usernames); `setStubMode('ok' |
    'fail')`.

##### 99. `e2e/cuj2-onboard-user.spec.ts` (Create)

*   **Proposed Changes**: creates a role through the API; in the console fills `/users/new`,
    picks the role, submits; expects `/users/{id}` and "A set-password email was sent to
    {email}"; searches the Users list for the username and sees the row with the role; checks
    Mailpit holds a message to that email.

##### 100. `e2e/cuj3-configure-role.spec.ts` (Create)

*   **Proposed Changes**: creates a role in the console with the name warning and Confirm;
    on the Menus tab grants Compliance and Patient detail (whose parent Patients is not
    granted), sees the client warning, saves, sees the server warning naming Patient detail and
    Patients; on the Permissions tab sees the two implicit rows, adds `GET /v1/insights/**`,
    saves, sees "Saved and synced" and the `SYNCED` badge.

##### 101. `e2e/cuj4-sync-failure.spec.ts` (Create)

*   **Proposed Changes**: creates a synced role A and a user holding A through the API; sets
    the stand-in to `fail`; creates role B in the console and sees `SyncFailedNotice`
    (`ROLE_CREATE`); opens B's Permissions tab, adds a rule, saves, sees the "saved but not
    synced" notice, the refetched row and `FAILED`; opens the user, adds B, saves roles, sees
    the `ROLE_NOT_SYNCED` message naming B; sets the stand-in to `ok`; presses Re-sync on B and
    sees `SYNCED`; saves the user's roles again and sees success. A `finally` block sets the
    stand-in to `ok`.

##### 102. `e2e/cuj5-change-roles.spec.ts` (Create)

*   **Proposed Changes**: creates roles A and B and a user holding A through the API; in the
    console unchecks A and checks B, saves; captures the request and checks its body is `{
    "roleIds": [B] }`; checks the page and the API both show only B.

##### 103. `e2e/cuj6-deactivate-user.spec.ts` (Create)

*   **Proposed Changes**: creates a user through the API; in the Users list deactivates them
    (confirm), sees `INACTIVE` and the roles kept; locks them, sees `LOCKED`; activates them,
    sees `ACTIVE`.

##### 104. `e2e/.auth/` (Created at run time, git-ignored)

*   **Proposed Changes**: holds `admin.json`, the saved Keycloak session. Not committed.

#### Files outside `cce-admin-ui/`

None are changed by the implementation. When this design is saved (EGM Step 6),
`docs/designs/SITEMAP.md` gets its row; that is part of the design step, not the
implementation.

### Verification Plan

*   **Compilation/Build**: in `cce-admin-ui/`, on Node 22.22: `npm ci`,
    `cp .env.example .env`, `npm run build`. Then `docker build --target verify
    --build-arg VITE_KEYCLOAK_REALM=cce .` (Node 20: lint, tests, no MSW in `dist/`) and
    `docker build --build-arg VITE_KEYCLOAK_REALM=cce --build-arg
    VITE_KEYCLOAK_URL=http://localhost:8180/auth -t cce-admin-ui:local .`. Also check that
    `docker build .` without the realm fails with the C-02 message.
*   **Unit Tests**: `npm run test:ci` on Node 22.22 (and in the `verify` stage on Node 20).
*   **Static Analysis & Linting**: `npm run lint` (ESLint with the layer rules, and Prettier).
*   **Test Coverage Requirement**: the thresholds in `vitest.config.ts` (80% lines, statements
    and functions, 70% branches), enforced by `test:ci`.
*   **E2E/Integration**: start the admin-service stack and service (admin-service §9.3), run
    `npx playwright install chromium` once, then `npm run e2e`; all five CUJ specs pass. Then
    `docker run --rm -p 3002:3002 -e GATEWAY_UPSTREAM=http://host.docker.internal:8085
    --add-host=host.docker.internal:host-gateway cce-admin-ui:local` and check that
    `curl -s http://localhost:3002/users` returns `index.html` (the SPA fallback) and that
    `curl -s http://localhost:3002/admin/v1/health` reaches the service.

--------------------------------------------------------------------------------

<!-- Provenance: Indicates initial structural drafting via EGM. -->

*Drafted with EGM*
