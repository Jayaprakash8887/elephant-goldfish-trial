# CCE v2 Admin Console (`cce-admin-ui`)

*   **Author:** jayaprakash8887
*   **Status:** Proposed
*   **Date:** 2026-11-10

--------------------------------------------------------------------------------

## Overview

`cce-admin-ui` is a new React single-page application that platform administrators use to
manage who can do what in CCE v2. CCE is the Care Coordination Engine, the platform whose
consoles and services this module controls access to. The console covers PRD milestones F1 to
F5: requirements F-01 to F-15 and the environment and scripts in §8.3 of the
[PRD](../../context/prd/CCE_Admin_Module_PRD.md).

The console has four areas: a signed-in shell, Users, Roles (with a Menus tab and a Permissions
tab), and the Menus catalog. It holds no data of its own. Every read and write goes to
`cce-admin-service` under `/admin/v1`, with a Keycloak access token. In shared environments the
calls pass through `gateway-service`. During local development the Vite dev server sends them
straight to the service on `localhost:8085` ([D-03](author-decisions.md)). Until that service
exists, the console is built and tested against MSW mocks.

**Identifiers used in this document.** From the PRD: B-nn (backend requirements), F-nn
(frontend requirements), Sn (scenarios). From the [admin-service design](cce-admin-service.md):
AD-nn (its author decisions), DEV-n (its deviations from the PRD), GW-n (its open questions for
the gateway owners). D-nn are decisions shared by both designs
([author-decisions.md](author-decisions.md)). AS-REQ-nn are this design's requirements on the
admin-service design, all answered in its
[§4.6](cce-admin-service.md#46-answers-to-the-console-designs-requirements). C-nn, UI-DEV-n and
UI-AD-nn are this design's conflicts, deviations and author decisions.

The stack follows the existing analytics console, `cce-insights-ui`
([architecture](../../context/cce-insights-ui/docs/architecture-overview.md),
[developer setup](../../context/cce-insights-ui/docs/developer-setup.md),
[deployment](../../context/cce-insights-ui/docs/deployment-guide.md)): React 18, TypeScript 5,
Vite 6, React Router 7, TanStack Query 5 and Table 8, Tailwind CSS 4, `keycloak-js` 26, React
Hook Form 7 with Zod 3, and Vitest with Testing Library and MSW (PRD §6). Every package and
image is pinned to the exact version the author checked and recorded as
[D-05](author-decisions.md#d-05-pinned-versions-checked-on-2026-11-10). Where this console
departs from that template, the departure is listed under
[Conflicts between Inputs](#conflicts-between-inputs) (when the PRD causes it) or under
[Author Decisions in This Design](#author-decisions-in-this-design) (when it is the author's own
choice, UI-AD-18).

The console uses the API contract. It does not define it. Under [D-01](author-decisions.md),
the admin-service design owns every request and response shape, error code, pagination block
and warning. That contract is defined in the admin-service design (Status: Proposed): its
Technical Plan [§3](cce-admin-service.md#3-request-pipeline-and-security-b-05-b-10-to-b-13)
(request pipeline), [§4](cce-admin-service.md#4-api-contract) (conventions, error codes,
pagination, resources, endpoints, and the answers to this design's requirements in §4.6) and
[§9.3](cce-admin-service.md#93-values-commands-and-reset) (local values). This document refers
to those sections by number ("admin-service §4.4"). Where it restates a rule from them, the
admin-service design wins if the two disagree.

**Three mechanisms outside the console.** The console's failure handling depends on them, so a
reader needs them first.

*   **The gateway sync.** After any change to a role's permission rows, the service calls the
    gateway's `POST /api/permissions/sync-to-keycloak`. The gateway reads `api_permissions` and
    creates a Keycloak realm role for each permission name. A role can be assigned to users only
    after its realm role exists (PRD §4, §9). The console triggers the sync indirectly, by
    saving permissions, or directly, with Re-sync. Details: admin-service
    [§5](cce-admin-service.md#5-flows-and-order-of-operations) (the gateway sync procedure).
*   **The stand-in gateway.** gateway-service does not run locally. A small local stand-in
    receives the service's sync call and creates the realm roles. It has an `ok` mode and a
    `fail` mode, switched at `http://localhost:8090/_stub/mode`. It is not in the console's
    request path: the console's calls still go from the Vite proxy to the service. Details:
    admin-service [AD-11](cce-admin-service.md#local-stand-in-for-the-gateway-sync-ad-11).
*   **Operator reconciliation.** When a write fails part-way, the service records the partial
    state in its `audit_log` table and returns a 502 that names the audit row. An operator, a
    person with access to the database and the Keycloak admin console, reads that row and repairs
    the state by hand. The console has no tool for this; it shows the ids the operator needs.
    Details: admin-service [§8](cce-admin-service.md#8-audit-b-50).

## The Problem

Today there is no screen for access control in CCE. `cce-insights-ui` shows every signed-in
user the same sidebar, and the gateway's permission rules live in a database table
(`api_permissions`) that someone has to edit with SQL. Giving a person access today means
changing two systems by hand: a Keycloak account and its realm roles, and the gateway's
permission rows. The admin service adds a third, the role-to-menu mapping. Each of these hand
edits can go wrong without anyone noticing (PRD §2):

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
    before the server confirms, hides that partial state from the admin, who must either act
    (Re-sync) or pass it to an operator.
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

Goals 1 to 5 are checked by the milestone exit checks in PRD §10
([How each milestone exit check is shown](#how-each-milestone-exit-check-is-shown)) and the
definition of done in PRD §11. Goals 6 to 9 name their own checks.

1.  **Every frontend requirement is met.** F-01 to F-15 and the §8.3 variables and scripts meet
    their PRD acceptance criteria.
2.  **An administrator can carry out scenarios S2 to S6 from the console alone, on the success
    paths, and can recover from a failed gateway sync with Re-sync (S6).** Onboarding a user,
    creating and configuring a role, changing a user's roles, and deactivating and re-activating
    a user need neither SQL nor the Keycloak admin console. Other partial failures are shown and
    handed to an operator (Goal 3).
3.  **No partial failure is hidden.** A `KEYCLOAK_SYNC_FAILED` response (HTTP 502) always shows
    one dedicated component, `SyncFailedNotice`, on the screen given for its operation in the
    [`SyncFailedNotice` table](#syncfailednotice). It says what the service recorded and what to
    do next. It is never a generic toast (F-05).
4.  **The screen only shows what the server confirmed.** There are no optimistic updates. Every
    write button shows a pending state until the server answers, and queries are refetched
    afterwards (F-06).
5.  **An authorization failure stays on the screen that caused it.** A `FORBIDDEN` (403) is
    shown inline where the call was made. It does not replace the page with a global error
    page (F-04).
6.  **Request and response shapes in the client and the MSW handlers cannot drift from
    `src/api/contract.ts` without a type error.** Check: `npm run build` (`tsc -b`) fails when
    a handler or an `api/` function disagrees with a type in `contract.ts`. Drift between
    `contract.ts` and the running service is a risk, caught by Goal 9
    ([Risks](#risks-accepted-by-the-author)).
7.  **Signing in cannot be bypassed.** No build of the console renders a route without a
    Keycloak session. There is no runtime flag and no static-token fallback (PRD §6, §9, F-01).
    Checks: `bootstrap.test.tsx` (nothing renders while sign-in is pending) and `config.test.ts`
    (the configuration reads only the four §8.3 variables).
8.  **It builds and passes its tests in both places it runs.** `npm run build`, `npm run lint`
    and `npm run test:ci` pass on the author's workstation (Node 22.22, npm 10.9) and inside the
    reference Docker build on `node:20-alpine`, pinned to `node:20.20.2-alpine` (PRD §6, §11,
    D-05). Check: the Verification Plan's two build runs.
9.  **Browser tests show scenarios S2 to S6 against the real service, on demand.** CUJ 2 to
    CUJ 6 run as Playwright tests with `npm run e2e` against the local stack of admin-service
    milestone M0 plus the service on `localhost:8085`. Sign-in goes through the real Keycloak
    login page. Why Playwright, and why not in `test:ci`: C-09.

## Non-Goals

*   **Anything the PRD defers from CCE v2** (PRD §2): an audit-log viewer, a global
    API-permission catalog across roles, enforcing facility scope, deriving permissions from
    menus, hard-deleting users, optimistic UI, and a sidebar in the admin console that changes
    with the caller's roles. Facility scope is a free-text label on a user, up to 50 characters,
    meant to limit the user to some facilities in a later release; the console stores and shows
    it only. The admin console's sidebar is static (Users, Roles, Menus). Hiding links the caller
    cannot use is a noted follow-up (PRD §12).
*   **The role-aware sidebar in `cce-insights-ui`** (scenario S1). This console edits the menu
    data that S1 reads, but the change that renders that sidebar from `GET /me/menus` is in the
    `cce-insights-ui` repo (PRD §12).
*   **The API contract.** Request and response shapes, error codes, pagination and warnings are
    defined by the admin-service design (D-01).
*   **The local environment.** The Keycloak realm and clients, Postgres, Mailpit, the stand-in
    gateway, ports and the test administrator are defined by admin-service milestone M0 (D-03,
    admin-service §9). This design only states which of those values the console reads.
*   **Detecting concurrent edits.** Last write wins; see
    [Risks](#risks-accepted-by-the-author) and UI-AD-12.
*   **Deleting menus.** F-15 asks for add, edit and deactivate (UI-AD-07).
*   **Translations.** The console is English only.
*   **Browser tests for S1, and running browser tests in CI.** S1 lives in `cce-insights-ui`, and
    the admin-service design checks `GET /me/menus` at API level. `npm run e2e` is not run by
    `test:ci`, the Docker build or the team's CI (Jenkins, PRD §6), because each would need the
    full local stack. Error paths that the local stack cannot produce on demand are covered by
    MSW tests only ([Risks](#risks-accepted-by-the-author)).

## Conflicts between Inputs

The inputs disagree in the places below. Each conflict is written down with the author's
decision, or with the design that owns it.

| ID | Conflict | Decision |
|---|---|---|
| C-01 | `cce-insights-ui` has a build-time `VITE_AUTH_ENABLED` flag and a `VITE_AUTH_TOKEN` static-token fallback ([developer setup §3](../../context/cce-insights-ui/docs/developer-setup.md)). The PRD says the admin console has "no auth-bypass flag" and "never bypasses auth" (§6, §9, F-01). | The PRD applies. The console has neither the flag nor the fallback. Tests replace the auth module instead (UI-AD-01). |
| C-02 | The template defaults the realm to `cce`. PRD §8.3 marks `VITE_KEYCLOAK_REALM` as required with no default. | The PRD applies. A missing value stops the production build with the message `VITE_KEYCLOAK_REALM is required for a build (PRD §8.3)` and stops start-up with a screen naming the variable. The local value is `cce` (admin-service §9.3). |
| C-03 | The PRD's Docker build uses `node:20-alpine` (§6). The template's CI uses Node 24 ([deployment guide](../../context/cce-insights-ui/docs/deployment-guide.md)). The author's workstation has Node 22.22 and npm 10.9. | The reference build is the PRD's Docker image, pinned to `node:20.20.2-alpine` (D-05). The code must also build and pass its tests on Node 22.22. Node 24 is not a target. |
| C-04 | The template names its one-shot test script `test:run`. PRD §8.3 names it `test:ci`. | The PRD names apply: `dev`, `build`, `test`, `test:ci`, `lint`, plus `e2e` (C-09). |
| C-05 | PRD §5 and §9 say the console reaches the service only through `gateway-service`. D-03 has the local Vite proxy forward `/admin/v1/*` straight to `localhost:8085`. | Both hold, in different environments. Locally no gateway is in the request path; the stand-in only receives the service's sync call. The service applies its own `api_permissions` check (admin-service AD-17), so locally a 403 comes from the service: a caller with no local user, a user who is not `ACTIVE` (B-12), or a role without a matching permission row. A 403 from the gateway is covered by MSW tests. |
| C-06 | PRD §2 says deactivation (`INACTIVE`) "is terminal". Scenario S5, B-34 and F-10 say an inactive user can be activated again. Appendix A also lists `LOCKED`. | Decided in the admin-service design (AD-04, DEV-4): `ACTIVE`, `INACTIVE` and `LOCKED`, and every change between them is allowed. The console offers each change. |
| C-07 | PRD §1 calls the team's design documents the source of truth. Those documents (data dictionary, API reference, flow diagrams) are not available as inputs ([PROVENANCE](../../context/PROVENANCE.md)). | This design and the admin-service design take their place. Where the PRD says "per the API reference", the admin-service design is meant. |
| C-08 | The gateway's sample permission rows use URI patterns such as `/admin/users/*` ([gateway migration doc](../../context/gateway-service/DATABASE_PERMISSIONS_MIGRATION.md)). The admin API's base path is `/admin/v1` (PRD Appendix A), and PRD §12 writes `/admin/v1/**`. | The admin-service contract has no prefix rule, because patterns for other services (for example `/v1/insights/**`) are valid (admin-service §4.4). The console's placeholder text and test data use `/admin/v1/...` and `/v1/insights/...`. |
| C-09 | PRD §6 lists the console's testing stack as Vitest, Testing Library and MSW, and PRD §10 describes the E2E milestone as running the scenarios against the real service, without saying how. | Playwright is added as a dev-only dependency with its own script, `npm run e2e`, for CUJ 2 to CUJ 6 (Goal 9). The E2E milestone needs a browser driver to show the console working against the real service; MSW tests show it only against mocks, and a manual run gives no regression coverage. The tests need the full local stack, so they run on demand and are not part of `test:ci`. S1 stays out of this console's tests. |

## Deviations from the PRD

These are places where this design does not follow the PRD as written. PRD §11 asks that each
deviation be raised with the team as a doc change, together with the admin-service design's
deviations.

| ID | PRD text | This design | Why |
|---|---|---|---|
| UI-DEV-1 | §6: "Folder layout: `pages/ components/ hooks/ api/ auth/ schemas/ context/`." §10, F1 notes: "Build `auth/` first (init, `getToken()`, context)". | No `src/context/` folder and no React auth context. `src/auth/` exposes `useAuth` (sign-out) next to `keycloak.ts`. A `src/utils/` folder, as in `cce-insights-ui`, holds the pure helpers: `describeError`, `formErrors`, `format`, `menuTree` and `permissions`. | The console has no cross-page client state beyond what TanStack Query already caches (`/me` included), so no module would live in `context/`. `auth/` exposing `useAuth` meets the F1 note's "context". The helpers are not hooks, schemas or API calls, so none of the PRD's folders describes them, and a helper used by components cannot live under `hooks/` because components never import `hooks/`. |

## Author Decisions in This Design

Decisions the author took for this design. Decisions both designs need are in
[author-decisions.md](author-decisions.md) under a D- ID.

| ID | Question | Decision | Why |
|---|---|---|---|
| UI-AD-01 | How do unit and component tests get a signed-in state without a bypass flag? | Tests replace `src/auth/keycloak.ts` with a typed fake (`satisfies typeof import(...)`). Only `keycloak.test.ts` runs the real module, with `keycloak-js` mocked. | No test-only seam in production code. Only one test file depends on the `keycloak-js` API. Rejected: Alternatives A and B. |
| UI-AD-02 | How is the console developed in a browser before the service exists? | It is not: MSW runs only inside Vitest. No mock mode, worker or mock server. | No mock code path can reach a production bundle, and the scripts stay the PRD's five plus `e2e`. Both tracks are built at the same time, so the period without a service ends when the service's milestone B1 runs, and the browser tests then cover the gap. Rejected: Alternatives C and D. |
| UI-AD-03 | How are the contract's types obtained? | One hand-written module, `src/api/contract.ts`, mirroring admin-service §4; the MSW handlers are typed only from it. | Under D-01 the contract is the admin-service design, not its code. Drift from the running service is caught by the Goal 9 browser tests. Rejected: Alternative F. |
| UI-AD-04 | How is the PRD's E2E milestone shown? | Playwright tests, `npm run e2e`, CUJ 2 to CUJ 6 (C-09). | Regression coverage against the real service. Rejected: Alternative E. |
| UI-AD-05 | Which HTTP methods does the Permissions tab offer? | Exactly `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, fixed in the contract; the admin-service design dropped its configurable setting. | A configurable list that no client can read would let the form offer a method the save rejects. |
| UI-AD-06 | What does "Synced" claim? | The help text says "Synced means the gateway's sync call succeeded." | Until the gateway owners answer GW-2, a successful sync call does not prove the gateway enforces the new rows. |
| UI-AD-07 | Does the console delete menus? | No. | F-15 asks for add, edit and deactivate. A delete also removes every role's grant of the menu, which re-activation cannot restore. |
| UI-AD-08 | Which icons can a menu entry use? | A select of the ten seed icons (admin-service §6) plus the entry's current value when it is not one of them. Unknown names render a fallback icon and the raw name. | Covers the seed and keeps any existing value on edit, without inventing an icon set. |
| UI-AD-09 | How do browser tests get their starting data and credentials? | Through the API, with a token from the local-only client `cce-e2e`; credentials only from the environment (`CCE_TEST_ADMIN_USERNAME`, `CCE_TEST_ADMIN_PASSWORD`). CUJ 2 does not check Mailpit. | The console's tests test the console; the set-up steps and the email are covered by the admin-service's own E2E. No path into another repository. |
| UI-AD-10 | Do browser tests run in parallel? | No: `workers: 1`. | CUJ 6 switches the shared stand-in gateway to `fail`. |
| UI-AD-11 | How is Goal 8 checked on Node 20? | A Dockerfile `verify` stage runs lint, tests and the bundle check. | Uses the pinned reference image, with no host Node 20. |
| UI-AD-12 | Two administrators saving the same role's full set at once. | Last write wins, accepted as a risk. No console pre-save diff dialog. An `If-Match`/412 follow-up is recorded on the admin-service design. | The race needs two administrators editing the same role within seconds; the audit log records both saves; only a server-side version check closes the race. Rejected: Alternative G. |
| UI-AD-13 | Guarding unsaved changes. | Kept on the Menus and Permissions tabs with React Router's `useBlocker`, which needs the data router (UI-AD-18). Not widened to other forms, and no further guard tests. | The two tabs hold the largest unsaved edits; the other forms are short. |
| UI-AD-14 | Runtime configuration file (`/config.json`). | Not used. | PRD §8.3 makes `VITE_*` build-time and asks for one image per environment. Rejected: Alternative H. |
| UI-AD-15 | Warn an administrator before they remove their own access. | No warning. | `LAST_SYSTEM_ADMIN` already refuses the change that would leave no active administrator. Rejected: Alternative I. |
| UI-AD-16 | Deployment origin. | The console needs an origin that also routes `/auth` to Keycloak, or an image built with `VITE_KEYCLOAK_URL` set. It is served at `/`. | Matches the PRD's `${origin}/auth` default. Rejected: Alternative J (a base path on a shared origin). |
| UI-AD-17 | A real-stack check of F-04 (403 shown inline). | Not added. | It needs a second, restricted user signed in; MSW tests cover the display. |
| UI-AD-18 | Template choices the console does not copy. | A data router (`createBrowserRouter`) instead of `BrowserRouter`; pages are not lazy-loaded; queries retry once only on status 0 or 5xx; no `VITE_ROUTER_BASE`. | `useBlocker` needs a data router; seven pages do not need code splitting; retrying a 4xx cannot succeed; the console is served at `/` (UI-AD-16). |
| UI-AD-19 | MSW mocks. | Canned typed fixtures and handlers that record each request, with per-test overrides for errors. No stateful store. | A store would re-implement the service's rules a second time, and could be wrong in ways the tests then trust. |
| UI-AD-20 | Upper bound on roles fetched for pickers and filters. | `useAllRoles` reads at most five pages of 100 (500 roles), dedupes by `id`, and shows "Showing the first 500 roles." when more exist. | Bounds the load; the deployment expects far fewer roles. |

The folder layout decision is recorded as UI-DEV-1.

## Open Questions

*   **GW-2 (gateway owners): when does a saved permission take effect at the gateway?** Until
    answered, "Synced" means only that the sync call succeeded (UI-AD-06).
*   **Gateway error bodies (gateway owners).** What do the gateway's own 401 and 403 responses
    contain? Until answered, the console classifies any response without the service's error
    envelope by its HTTP status ([Error presentation](#error-presentation)).

Follow-ups recorded on the admin-service design (not open here): `If-Match`/412 on the
full-set replace endpoints (UI-AD-12); a 409 message that says when a username or email belongs
to a Keycloak account with no local user; the trust placed in `X-Forwarded-For` for the audit
IP ([Risks](#risks-accepted-by-the-author)).

## Critical User Journeys (CUJs)

CUJ n matches scenario Sn where a scenario exists, as in the admin-service design.

### CUJ 1: Sign in and see the shell (F-01, F-03)

*   **Given**: The console is built with a valid realm and client id, and the administrator has
    a Keycloak account with an `ACTIVE` local user.
*   **When**: The administrator opens any console URL without a Keycloak session.
*   **Then**: The browser goes to Keycloak before any route renders, using Authorization Code
    with PKCE (S256). After sign-in it returns to the URL first requested. The header shows the
    username and role names from `GET /me`. The sidebar shows Users, Roles and Menus whatever
    `/me` returns. Every 60 seconds the console refreshes the token if it expires within 70
    seconds.

### CUJ 2: Onboard a user (S2, F-11, F-05)

*   **Given**: The administrator is on `/users/new`, and at least one assignable role with a
    realm role in Keycloak exists.
*   **When**: They fill in username, email, first and last name, optionally facility scope, pick
    one or more roles and submit once.
*   **Then**:
    *   Invalid fields are flagged before anything is sent.
    *   On 201 the console opens the new user and shows "User created. A set-password email was
        sent to {email}." A 201 is also the answer when the service links an existing, enabled
        Keycloak account that matches both username and email and has no local user.
    *   If the response carries `ACTION_EMAIL_NOT_SENT`, it shows "User created, but the
        set-password email could not be sent. Use Send set-password email on this page."
    *   `USERNAME_IN_USE` and `EMAIL_IN_USE` show on their fields. `ROLE_NOT_SYNCED` and
        `ROLE_NOT_ASSIGNABLE` name the roles concerned under the role list.
    *   On `KEYCLOAK_SYNC_FAILED` (`USER_CREATE`), `SyncFailedNotice` says what the service
        recorded and what to do next.
    *   The submit button is disabled while the request is pending.

### CUJ 3: Create and configure a role (S3, F-12, F-13, F-14)

*   **Given**: The administrator is on `/roles`.
*   **When**: They create a role with the inline form, open it, select menus on the Menus tab
    and save, then add permission rows on the Permissions tab and save.
*   **Then**:
    *   The create form rejects names that do not match the role-name rule and warns, before
        submit, that the name is permanent.
    *   After Confirm, the service creates the role and runs the gateway sync, and the console
        opens the role.
    *   The Permissions tab shows the role's two implicit rows read-only:
        `GET /admin/v1/me` and `GET /admin/v1/me/menus`.
    *   Each tab's Save is disabled until the selection differs from what was last fetched,
        and it sends the complete set.
    *   Granting a `DETAIL` menu without its parent shows the warning.
    *   A successful permission save shows "Saved." and the badge "Synced · {time}".

### CUJ 4: Change a user's roles (S4, F-11)

*   **Given**: The administrator is on `/users/:id`.
*   **When**: They change the role selection and save roles.
*   **Then**:
    *   The console sends the full new role set in one `PUT /users/:id/roles`, not a list of
        changes.
    *   Saving the profile, saving roles, changing status and sending the set-password email
        are separate actions with their own buttons and pending states.
    *   `LAST_SYSTEM_ADMIN` is shown next to the roles' Save button. The service refuses a
        change that would leave no `ACTIVE` user holding `SUPER_ADMIN`.

### CUJ 5: Deactivate and re-activate a user (S5, F-10)

*   **Given**: An `ACTIVE` user who is not the last active `SUPER_ADMIN` is listed on `/users`.
*   **When**: The administrator chooses "Deactivate" from the row actions and confirms, and
    later chooses "Activate" and confirms.
*   **Then**:
    *   The console sends `PATCH /users/:id/status` each time and, on success, refetches the
        list.
    *   The user shows as "Inactive" and keeps their roles, then shows as "Active" again.
    *   "Lock" works the same way and shows "Locked".
    *   `LAST_SYSTEM_ADMIN` is shown inline in the row's message area.

### CUJ 6: Recover from a failed gateway sync (S6, F-14, F-05)

*   **Given**: A role was created while the gateway sync was failing, so its realm role does not
    exist in Keycloak.
*   **When**: The administrator saves the role's permissions while the sync still fails, tries to
    assign the role to a user, presses Re-sync once the sync works again, and assigns the role.
*   **Then**:
    *   Creating the role shows "The role was created but not synced. Use Re-sync before
        assigning it."
    *   The permission save shows `SyncFailedNotice` (`ROLE_PERMISSIONS_SAVE`): the rows are
        saved, and the role must not be assigned until a sync succeeds. The badge shows
        "Sync failed · {time}".
    *   Assigning the role is refused with `ROLE_NOT_SYNCED`, naming the role.
    *   Re-sync calls `POST /roles/:id/sync`. Success changes the badge to "Synced · {time}",
        and the assignment then succeeds.
    *   A failed Re-sync keeps the "Sync failed" badge and shows the notice again.

### CUJ 7: A call the caller is not allowed to make (F-04)

*   **Given**: The signed-in administrator's role has no permission row for a call, for example
    `DELETE /admin/v1/roles/*`.
*   **When**: They try that action.
*   **Then**: The action's area shows "You do not have permission to do this." followed by the
    server message. The rest of the page and the shell keep working.

### CUJ 8: Maintain the menus catalog (F-15)

*   **Given**: The administrator is on `/menus`.
*   **When**: They add a `DETAIL` entry under a `NAV` entry, then deactivate an existing entry.
*   **Then**:
    *   The tree shows label, key, path, icon, type and active state.
    *   `DUPLICATE_MENU_KEY` shows on the key field.
    *   Deactivate sends `PUT /menus/:id` with `isActive: false` and the entry's other fields
        as just read from the server.
    *   A deactivated entry stays visible, marked "Inactive", and can be activated again the same
        way.

## Technical Plan (Architecture)

### Layers and the rules between them

The console follows the layer split of `cce-insights-ui`
([architecture §3](../../context/cce-insights-ui/docs/architecture-overview.md)) and the folder
layout in PRD §6, with the deviation UI-DEV-1. Each layer may only call the layer below it.

```text
 main.tsx ─▶ bootstrap(root)
               │ readConfig() ──fail──▶ StartupErrorScreen (no auth, no API calls)
               │ ok
               ▼
             initAuth() ── redirect to Keycloak until a session exists (PKCE S256)
               │ resolved          ──rejected──▶ StartupErrorScreen
               ▼
             render: QueryClientProvider ▸ RouterProvider(createBrowserRouter(routes))
                                                               │
 ┌─────────────────────────────────────────────────────────────┴──────────┐
 │ pages/        route components; compose hooks + components            │
 │   │  may import: hooks/, components/, schemas/, utils/, auth/         │
 │   ▼                                                                   │
 │ components/   rendering only; data and callbacks arrive as props      │
 │ hooks/        one TanStack Query hook per api/ function               │
 │   │                                                                   │
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

These four import rules fail `npm run lint` (configuration in item 9):

*   `src/pages/**` and `src/components/**` may not import values from `src/api/**`. They may
    import types (`import type`) from `src/api/contract.ts` only. Runtime constants they need
    are re-exported from `src/schemas/rules.ts`.
*   `src/components/**` may not import `src/hooks/**` or `@tanstack/react-query`.
*   Only `src/auth/**` and test files may import `keycloak-js`.
*   Only test files, `src/test/**`, `e2e/**` and `playwright.config.ts` may import `msw` or
    `@playwright/test`.

Two cases are left to code review: a lower layer importing a higher one (for example `api/`
importing `hooks/`), and a direct `fetch` call outside `client.ts`.

### Start-up: configuration, then sign-in, then render

`src/main.tsx` only calls `bootstrap(document.getElementById('root'))` from `src/bootstrap.tsx`.

1.  **Configuration check.** `src/config.ts` reads the four `VITE_*` values from PRD §8.3. Each
    is trimmed, and an empty value counts as unset. `VITE_KEYCLOAK_REALM` must be set (C-02).
    `VITE_KEYCLOAK_CLIENT_ID` defaults to `cce-admin-ui`. `VITE_KEYCLOAK_URL` defaults to
    `${window.location.origin}/auth`. `VITE_API_BASE_URL` defaults to an empty string, and a
    trailing `/` is removed. If the realm is missing, `bootstrap` renders `StartupErrorScreen`
    with "Configuration error: VITE_KEYCLOAK_REALM is not set." and does not call Keycloak or
    the API. The same check runs in `vite.config.ts` for `vite build` (C-02).
2.  **Sign-in.** `initAuth()` in `src/auth/keycloak.ts` creates the `keycloak-js` instance and
    calls `init` with `onLoad: 'login-required'`, `pkceMethod: 'S256'` and
    `checkLoginIframe: false`. Without a session, `keycloak-js` sends the browser to the Keycloak
    login page and the page unloads; nothing renders. Keycloak returns the browser to the URL
    first requested. `initAuth()` rejects if `init` throws or resolves without a session; then
    `bootstrap` renders `StartupErrorScreen` with "Sign-in did not complete with the sign-in
    service at {keycloakUrl}." After `init` resolves with a session, an interval of 60 seconds
    calls `updateToken(70)`. A failed refresh in the interval is ignored and tried again on the
    next tick (F-01).
3.  **Render.** `bootstrap` renders the providers and the router only after `initAuth()`
    resolves. There is no code path that renders a route without a session, and no flag or
    static token that skips `initAuth()` (C-01, Goal 7). The start-up error screen is not a
    route and makes no API call. The access token is held only in the `keycloak-js` instance in
    memory, never in `localStorage`, `sessionStorage` or a cookie.

**Test seam for sign-in (UI-AD-01).**

*   `src/test/setup.ts` calls `vi.mock` on `src/auth/keycloak.ts` for every test file. The
    replacement is `src/test/fakeAuth.ts`, which has the same named exports. A type-check
    statement in it, `({ initAuth, getToken, refreshToken, login, logout }) satisfies typeof
    import('../auth/keycloak')`, makes a change to the real module's exports fail `tsc -b`. The
    fake's functions are `vi.fn()` spies with default behaviour (resolve; token `'test-token'`;
    `refreshToken` resolves `true`), reinstalled after every test.
*   `src/auth/__tests__/keycloak.test.ts` is the only test file that depends on the `keycloak-js`
    API. It calls `vi.unmock` on the module, mocks the `keycloak-js` package and uses fake
    timers. It covers: `init` options; rejection without a session; the 60-second interval
    calling `updateToken(70)`; a failed interval refresh not calling `login()`; `refreshToken()`
    forcing a refresh; a failed `refreshToken()` calling `login()`.
*   `src/__tests__/bootstrap.test.tsx` covers the gate: nothing renders while `initAuth` is
    pending; the start-up screen on a rejection; with the realm missing, the screen names
    `VITE_KEYCLOAK_REALM` and `initAuth` is never called.

**Sign-out** calls `logout()`, which runs
`keycloak.logout({ redirectUri: window.location.origin + '/' })`.

### API client and the contract module

*   **`src/api/contract.ts`** is the only place where request and response shapes are written.
    It mirrors admin-service §4.1 to §4.5 with the same property names. Response fields follow
    one rule from the contract: only the optional envelope fields (`pagination`, `warnings`,
    `details`, `partialState`) are left out when absent; every other response field is always
    present and is `null` when it has no value. So a response field that can be empty is typed
    `field: T | null`, never `field?: T`. Request bodies follow the optional fields that
    admin-service §4.5 marks with `?`. Ids are `string` (UUID) except permission row ids, which
    are `number`. Timestamps are `string` (ISO-8601 UTC). The console does not call
    `GET /me/menus`, `GET /health` or `DELETE /menus/:id`, so their shapes are not in the module.
*   **`src/api/client.ts`** exposes one request function. It builds the URL as
    `${apiBaseUrl}/admin/v1${path}` and leaves out query parameters that are undefined, null or
    empty strings. It sends `Authorization: Bearer <token>` and `Accept: application/json`,
    plus `Content-Type: application/json` when there is a body (F-02). If `getToken()` returns
    no token, it calls `login()` and throws a 401 `ApiError`. It parses the envelope and returns
    it typed from `contract.ts`. A 204 response (`DELETE /roles/:id`) returns no data. Request
    bodies contain only the properties the contract defines, because the service rejects any
    other property with 400 `VALIDATION_ERROR` (admin-service §4.1); each page builds its body
    from named fields and never spreads form state into it.
*   **Errors.** Any non-2xx response becomes an `ApiError` with `status`, `code`, `message`,
    and `details` and `partialState` when the error body carries them. If the body is not the
    service's error envelope, `code` is `CLIENT_UNEXPECTED_RESPONSE` and `status` is the HTTP
    status. If `fetch` itself fails, `status` is `0` and `code` is `CLIENT_NETWORK_ERROR`. The
    service never sends a code starting with `CLIENT_`.
*   **401 handling.** On a 401 (with or without an envelope) the client calls `refreshToken()`
    once.
    *   If it returns `false`, `refreshToken()` has already called `login()`; the client throws
        the 401 `ApiError`.
    *   If it returns `true`, the client repeats the request once. Repeating a write is safe
        because the service rejects an invalid token before doing anything (admin-service §3,
        step 2); the gateway is assumed to behave the same way (its runtime flow in the
        [gateway migration doc](../../context/gateway-service/DATABASE_PERMISSIONS_MIGRATION.md)
        validates the token first). If the repeated request also returns 401, the client throws
        the 401 `ApiError` and does not call `login()`, so a session the service keeps rejecting
        cannot cause a redirect loop.
*   **Resource modules** (`api/me.ts`, `users.ts`, `roles.ts`, `roleMenus.ts`,
    `rolePermissions.ts`, `menus.ts`) each export one typed function per endpoint the console
    calls (items 30 to 35). They contain no React code.

### Server state: hooks, query keys and refetching

*   **One hook per `api/` function** (PRD §10, F1 notes). Query keys are built by
    `src/hooks/queryKeys.ts`: `['me']`, `['users', 'list', query]`, `['users', 'detail', id]`,
    `['roles', 'list', { page, pageSize }]`, `['roles', 'all']`, `['roles', 'detail', id]`,
    `['roles', id, 'menus']`, `['roles', id, 'permissions']`, `['menus', 'tree']`.
*   **Retries.** Queries retry once, and only for status 0 or a status of 500 or above. TanStack
    Query never retries mutations; `client.ts` repeats a request once only after a 401.
*   **No optimistic updates (F-06).** A mutation does not touch the cache before the server
    answers.
*   **Refetch rule.** After any mutation settles with a 2xx, a 502, a network error or an
    `unexpected` error, the query client calls `queryClient.invalidateQueries()`, which refetches
    every active query. After a 4xx with the service's envelope (validation, conflict,
    forbidden, not found), nothing changed, and nothing is refetched. The rule lives once, in the
    query client's mutation cache (item 37).
*   **Other defaults.** `staleTime` 30 seconds, `refetchOnWindowFocus` off, as in the template.
*   **Fetching every role.** `GET /roles` is paginated (admin-service §4.3). The role checkboxes
    and the Users role filter use `useAllRoles` (key `['roles', 'all']`). It requests
    `GET /roles?page=1&pageSize=100`, then further pages up to `totalPages` or five pages,
    whichever is lower, joins them in order and drops repeated ids. When `totalPages` is above
    five, the pickers show "Showing the first 500 roles." (UI-AD-20). The pickers and the filter
    show only roles with `isAssignable: true` (admin-service AD-14).

### Error presentation

`describeError(error, options?)` in `src/utils/describeError.ts` returns a `DescribedError`
(`{ kind, code, status, message, details, partialState }`). Screens choose how to show an error
by its `kind` and `code`, never by parsing `message`.

| Kind | When | Shown as |
|---|---|---|
| `forbidden` | code `FORBIDDEN`, or a 403 without the envelope | `InlineError`: "You do not have permission to do this." and the server message when there is one (F-04) |
| `syncFailed` | code `KEYCLOAK_SYNC_FAILED` | `SyncFailedNotice` (F-05) |
| `validation` | code `VALIDATION_ERROR` | Field messages from `details`; the rest at the top of the form |
| `conflict` | codes `EMAIL_IN_USE`, `USERNAME_IN_USE`, `DUPLICATE_ROLE_NAME`, `DUPLICATE_MENU_KEY`, `ROLE_IN_USE`, `SYSTEM_ROLE_IMMUTABLE`, `LAST_SYSTEM_ADMIN`, `ROLE_NOT_ASSIGNABLE`, `ROLE_NOT_SYNCED`, `MENU_HAS_CHILDREN` | On a field, or inline next to the action (rules below) |
| `notFound` | codes `USER_NOT_FOUND`, `ROLE_NOT_FOUND`, `MENU_NOT_FOUND`; also, only when called with `{ detailFetch: true }`, a `VALIDATION_ERROR` whose only detail has field `id` | `NotFoundPanel` with a link back to the list |
| `unauthenticated` | status 401 after the client's refresh-and-repeat | `InlineError`: "The admin service did not accept your sign-in. Sign out and sign in again." |
| `network` | code `CLIENT_NETWORK_ERROR` | `InlineError`: "Cannot reach the admin service." |
| `unexpected` | anything else: `INTERNAL_ERROR`, `NOT_FOUND`, `METHOD_NOT_ALLOWED`, any non-envelope response other than 401 and 403, any unknown code | `InlineError`: "Something went wrong (HTTP {status}, {code})." For `NOT_FOUND` and `METHOD_NOT_ALLOWED`: "The console and the admin service disagree about the API (HTTP {status}, {code})." |

*   **Queries and mutations.** For a failed query, `network` and `unexpected` errors show a Retry
    button that refetches it. For a failed mutation, they show "The result is unknown; the page
    was refreshed." and the refetch rule applies, because the write may have happened.
*   **Malformed ids.** A detail page passes `{ detailFetch: true }`, because the service
    validates path ids as UUIDs and answers a malformed one with 400 `VALIDATION_ERROR` on field
    `id` (admin-service §4.1); for a user it means the same as "not found".
*   **Field mapping.** `VALIDATION_ERROR` details are mapped to React Hook Form fields by
    `field`. The JSON path form (`permissions[2].uriPattern`) becomes React Hook Form's form
    (`permissions.2.uriPattern`). A detail whose field is not a field of that form (for example
    `body`, `id` or `pageSize`) is shown at the top of the form. Conflict codes map to fields:
    `USERNAME_IN_USE` to username, `EMAIL_IN_USE` to email, `DUPLICATE_ROLE_NAME` to name (also
    returned when a Keycloak realm role with that name already exists), `DUPLICATE_MENU_KEY` to
    key.
*   **Role errors.** `ROLE_NOT_SYNCED` and `ROLE_NOT_ASSIGNABLE` carry one detail per role, with
    `field` `roleIds[i]` (admin-service §4.1). The console keeps the `roleIds` array it sent,
    maps each index back to the role, and shows under the role checkboxes "Not synced yet:
    {displayName} ({name}). Open the role and use Re-sync." or "Cannot be assigned:
    {displayName} ({name})."
*   **Inline next to the action.** `ROLE_IN_USE`, `SYSTEM_ROLE_IMMUTABLE`, `LAST_SYSTEM_ADMIN`
    and `MENU_HAS_CHILDREN` show the server message next to the button that caused them.
*   **`GET /me` failing.** The header shows, in place of the username, the same text the kinds
    table gives (for example "You do not have permission to do this." for 403). Each page still
    shows its own errors as its own calls fail. There is no global error page.

#### `SyncFailedNotice`

It takes the `partialState`, the server `message`, and optional actions (a Re-sync button, a
link). It always shows the server message and, when present, the Keycloak user id and the audit
log id, labelled "For the operator:", so an operator can find the audit row.

*   **When `completedSteps` is empty**, for every operation except `ROLE_SYNC`, it shows "The
    service recorded no change. You can try again." The form keeps its input, and its own
    submit button stays enabled. This stays true after a timeout, because the service writes
    no audit row unless a step completed.
*   **When `completedSteps` is not empty**, it shows the operation's text from the table below.
*   **`ROLE_SYNC`** always shows "The sync failed. The saved permissions are unchanged. Use
    Re-sync."

The table lists the operations whose 502 can report completed steps. Operation names follow
admin-service §4.2.

| `operation` | Shown on | Text when steps completed | Action |
|---|---|---|---|
| `USER_CREATE` | User create page | "The sign-in account was created in Keycloak, but the user was not saved here. Submit the form again with the same username and email: the service links the existing account. If that fails again, pass the ids below to an operator." | Users list link |
| `USER_PROFILE_UPDATE` | User edit, Profile section | "Keycloak has the new values. This page shows the old values, which are still stored locally. Pass the ids below to an operator." | none |
| `USER_STATUS_CHANGE` | Users list row messages; User edit, Status section | "Keycloak may already have the new status, but it was not saved here. Pass the ids below to an operator." | none |
| `USER_ROLES_UPDATE` | User edit, Roles section | "Some Keycloak role mappings may have changed, but the roles were not saved here. Pass the ids below to an operator." | none |
| `USER_PASSWORD_RESET` | Users list row messages; User edit, Password section | (never has completed steps; the empty-steps rule applies) | none |
| `ROLE_PERMISSIONS_SAVE` | Role detail, Permissions tab | "The permissions were saved but not synced. Use Re-sync, and do not assign the role until a sync succeeds." | Re-sync |
| `ROLE_SYNC` | Role detail, Permissions tab | (see the rule above) | Re-sync |

On a `USER_CREATE` 502, `partialState.entityId` is always the id the user will have, but no
local user exists until the form is submitted again and the account is linked, so the notice
does not link to it.

### Shell and routing

The router is a data router, `createBrowserRouter`, rendered with `RouterProvider`, because
React Router's `useBlocker` works only with a data router (UI-AD-13, UI-AD-18). The route
objects live in `src/routes.tsx`, so tests build a `createMemoryRouter` from the same objects.
The console is served at `/` on port 3002 (UI-AD-16). One layout route renders the static
sidebar (Users, Roles, Menus, F-03) and the header (username and role names from `GET /me`, Sign
out), with an `<Outlet/>` for the page. The seven pages are not lazy-loaded (UI-AD-18).

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
Permissions tabs, unsaved changes are guarded with `useBlocker`: leaving the route or switching
tab opens a `ConfirmDialog` with "You have unsaved changes. Leave without saving?". Switching
tab changes the URL, so the same guard covers it.

### Screens

All timestamps are shown as `yyyy-MM-dd HH:mm` in the browser's local time (date-fns). Badge
texts: user status "Active" (green), "Inactive" (gray), "Locked" (amber); sync status "Synced ·
{time}" (green), "Never synced" (gray), "Sync failed · {time}" (red), where `{time}` is
`lastSyncAt`.

#### Users list (F-10)

Filters and paging live in the URL search parameters `status`, `roleId`, `facilityScope`, `q`,
`page` and `pageSize`, which map one-to-one to the `GET /users` query parameters
(admin-service §4.5).

*   **Text inputs.** `q` and `facilityScope` wait 300 ms after the last keystroke before
    updating the URL.
*   **Paging.** Changing any filter sets `page` to 1. TanStack Table runs in manual-pagination
    mode; its page count is `totalPages`, and the footer shows `totalItems`. Pages are 1-based,
    like the API. Page sizes are 20, 50 and 100; the default is 20 (B-04).
*   **Invalid paging in the URL.** A `page` that is not a whole number of at least 1, or a
    `pageSize` other than the three choices, is replaced by 1 or 20 before any request, because
    the service rejects out-of-range values with 400 instead of clamping them.
*   **Past the last page.** If a response has `data: []` while `page` is greater than
    `totalPages` and `totalPages` is at least 1, the console sets `page` to `totalPages`, which
    refetches.
*   **Filters:** status (All, Active, Inactive, Locked), role (All, then every assignable role
    by display name), facility scope (text, exact match, up to 50 characters), search (text, up
    to 100 characters, matching username, email, first name, last name or full name).
*   **Columns:** username, email, status badge, role display names, facility scope (or "—"),
    last login (or "Never" when `lastLoginAt` is null).
*   **Row actions:** "View / edit" (link to `/users/:id`); one status button for each status the
    user does not have ("Activate", "Deactivate", "Lock"); and "Send set-password email". Each
    opens a `ConfirmDialog` that names the user and the effect. The result of a row action
    (success, error or `SyncFailedNotice`) is shown in a message area above the table that names
    the user.

#### User create (F-11)

React Hook Form with a Zod schema from `src/schemas/`. Fields: username, email, first name, last
name, facility scope (optional), and roles (checkboxes of every assignable role from
`useAllRoles`, at least one and at most 20). Each role checkbox shows the role's display name and
name, and a sync badge when its `syncStatus` is not `SYNCED`. The badge is a display hint only;
the service decides whether the role can be assigned (admin-service AD-10).

One submit sends one `POST /users`; there is no separate sync step. On 201 the console goes to
`/users/:id` and passes the result in navigation state, so the edit page shows the created
message from CUJ 2. On 502 the form stays on screen with `SyncFailedNotice` (`USER_CREATE`), and
the form keeps its input and its submit button.

#### User edit (F-11)

A header with username (read-only), status badge and last login, then four independent sections.
Each has its own mutation, pending state and inline error area.

*   **Profile:** email, first name, last name, facility scope; Save sends `PUT /users/:id` with
    all four fields, and `facilityScope: null` when the field is empty.
*   **Roles:** the same checkbox list as on create (zero to 20 roles). Save is enabled only when
    the selection differs from the last fetched set, and sends the complete set with
    `PUT /users/:id/roles`.
*   **Status:** the same three status buttons as the row actions, behind a `ConfirmDialog`.
*   **Password:** "Send set-password email" behind a `ConfirmDialog`; on success it shows "Email
    sent to {email}." with the address from the response.

A user id that does not exist or is malformed shows `NotFoundPanel`.

#### Roles list (F-12)

A table with server-side paging like the Users list (`page` and `pageSize` in the URL, default
20, ordered by name by the service).

*   **Columns:** name (with a lock icon when `isSystem`), display name, description, users
    (`userCount`), sync badge, and actions. A role with `isAssignable: false` shows the badge
    "Service account, cannot be assigned".
*   **Actions:** "Open" (link to `/roles/:id`) and "Delete". Delete is not shown for system roles,
    and is disabled with the reason "Assigned to {n} users" when `userCount` is above 0. Delete
    opens a `ConfirmDialog`; `ROLE_IN_USE` and `SYSTEM_ROLE_IMMUTABLE` show inline in the
    message area above the table.
*   **Inline create form** above the table: name, display name and description. The name field
    validates the role-name rule as the user types. A fixed warning under it says: "The name
    cannot be changed later. It is also the Keycloak realm role name and the gateway permission
    name." Submitting opens a `ConfirmDialog` that repeats the exact name and the warning. The
    request is sent only after Confirm.
*   **After create.** On 201 without warnings, the console goes to `/roles/{id}`. On 201 with the
    warning `ROLE_SYNC_FAILED`, it goes to `/roles/{id}?tab=permissions` and the tab shows "The
    role was created but not synced. Use Re-sync before assigning it." `DUPLICATE_ROLE_NAME`
    shows on the name field.

#### Role detail (F-13, F-14)

A header with the name (read-only, with a lock for system roles), the sync badge, the user count,
and a display-name and description form (`PUT /roles/:id`). Delete is offered only on the Roles
list (F-12). A role id that does not exist or is malformed shows `NotFoundPanel`. For a system
role (`isSystem`), the form, the Menus tab's checkboxes and Save, and the Permissions tab's
editing and Save are disabled, with the note "System roles cannot be changed." Re-sync stays
available (admin-service AD-07). Below the header are two tabs.

##### Menus tab (F-13)

A checkbox tree built from `GET /menus`: each `NAV` menu at the top level in `sortOrder`, its
`DETAIL` children indented under it. Each row shows the label, path and type, and "Inactive"
when `isActive` is false. Inactive menus can be granted (AS-REQ-15, admin-service §4.5).

*   **Selection.** The current selection is `menuIds` from `GET /roles/:id/menus`. Checking a
    parent does not check its children, and checking a child does not check its parent, because
    each menu is granted on its own.
*   **Save** is enabled only when the set differs from the last fetched set. It sends
    `{ "menuIds": [...] }` with the complete set.
*   **Warnings.** While editing, the tab lists every selected `DETAIL` menu whose parent `NAV` is
    not selected: "{detail label} is shown only when {parent label} is also granted." After a
    save, the response's `DETAIL_WITHOUT_PARENT` warnings replace that list. Each names its menu
    and parent by `menuId` and `parentMenuId`; the tab looks both up in the tree to show their
    labels, and shows the server `message` if an id is not in the tree.

##### Permissions tab (F-14)

The tab reads `GET /roles/:id/permissions`, which returns the rows, `syncStatus` and
`lastSyncAt` (admin-service §4.5).

*   **Sync panel:** the sync badge, the Re-sync button (`POST /roles/:id/sync`, always shown,
    also for system roles), and the help text "Synced means the gateway's sync call succeeded."
    (UI-AD-06).
*   **Always included:** the implicit rows (`implicit: true`) in a read-only table, with the note
    "Every non-system role keeps these two rules so that its users can load their profile and
    menus." They are never sent (admin-service AD-16).
*   **Rules:** the explicit rows in an editable table, built with React Hook Form's field array.
    Each row has method (a select of the five methods of UI-AD-05), URI pattern, description and
    category. Rows can be added (new rows start with `GET` and empty fields) and removed. The
    schema applies the rules in [Forms and validation](#forms-and-validation).
*   **Save** is enabled only when `sameRuleSet(current, fetched)` is false. Both sides are
    normalized first (method upper case, strings trimmed, empty description or category treated
    as absent) and compared as sets of (method, pattern, description, category), which is how
    the service decides a no-op (admin-service §5). Save sends `{ "permissions": [...] }` with
    every explicit row, leaving out description and category when they are empty. A 200 shows
    "Saved."

The sync status is read from the server, never set by the console. Only the role whose sync ran
changes status.

```text
  NEVER  ── POST /roles 201 (sync ok) ──────────────────────▶ SYNCED
  NEVER  ── POST /roles 201 + ROLE_SYNC_FAILED ─────────────▶ FAILED
  SYNCED ── PUT permissions 502 / Re-sync 502 ──────────────▶ FAILED
  FAILED ── PUT permissions 200 / Re-sync 200 ──────────────▶ SYNCED
  FAILED ── PUT permissions 502 / Re-sync 502 ──────────────▶ FAILED (notice shown again)
```

After a save or a Re-sync, the refetch rule shows the status the service returns. On a
`ROLE_PERMISSIONS_SAVE` 502 with completed steps, the rows were saved, so the form resets to the
refetched rows. On a 502 with no completed steps, the edited rows stay in the form for another
try.

#### Menus catalog (F-15)

A tree table showing label, key, path, icon (the icon and its name), type, sort order and an
active badge ("Active" or "Inactive"). `NAV` rows are at the top level in `sortOrder`, and their
`DETAIL` children are indented under them.

*   **"Add menu"** above the table opens the menu form for a new `NAV` entry. **"Add detail"** on
    each `NAV` row opens it for a new `DETAIL` entry under that row.
*   **"Edit"** opens the form with the entry's values. Key and type are read-only, because they
    cannot change (admin-service §4.4). For a `DETAIL` entry, the parent can be changed.
*   **The form** is an inline panel above the table, not a dialog. Fields: key, label, path,
    icon, type, parent (only for `DETAIL`: a select of every `NAV` entry, inactive ones marked
    "(inactive)"), sort order and active. Defaults for a new entry: active; sort order 10 more
    than the highest sort order among its future siblings, or 10 when it has none, at most
    10000. Create sends `POST /menus`; edit sends `PUT /menus/:id` without key and type.
*   **"Deactivate"** (on an active entry) or **"Activate"** (on an inactive one) opens a
    `ConfirmDialog`. On Confirm, the console reads the menu tree again from the server, takes
    the entry's current label, path, icon, parent id and sort order, and sends `PUT /menus/:id`
    with those values and `isActive` flipped.
*   **Icons** (UI-AD-08). `src/components/menus/iconMap.ts` maps the ten seed icon names
    (`ChartBarIcon`, `BuildingOffice2Icon`, `ClipboardDocumentCheckIcon`,
    `ExclamationTriangleIcon`, `UserGroupIcon`, `SignalIcon`, `CogIcon`, `UserIcon`,
    `LightBulbIcon`, `ArrowDownTrayIcon`) to Heroicons 2 outline components. The icon field is a
    select of "No icon", those ten names, and the entry's current value when it is not one of
    them. An unknown name renders `QuestionMarkCircleIcon` and the raw name.

### Forms and validation

Each form has one Zod schema in `src/schemas/`. The field rules (patterns, lengths, required
fields) mirror admin-service [§4.4 and §4.5](cce-admin-service.md#44-resources-and-field-rules);
`src/schemas/rules.ts` holds them as constants with a link to those sections, and the
admin-service design wins if the two disagree. Every string is trimmed before it is checked. The
client check is for fast feedback only; server errors are still shown by `error.code`.

Rules that only the console applies:

*   Permission rows: no two explicit rows with the same method and pattern, and no explicit row
    equal to an implicit row ("Already included for every role"), both reported on
    `permissions.{i}.uriPattern`; at most 200 explicit rows.
*   User roles: at least one on create, zero or more on edit, at most 20.
*   Menu parent: required for `DETAIL` and one of the `NAV` entries; absent for `NAV`.
*   Page sizes 20, 50 and 100 only.

Save buttons on replace-semantics screens (user roles, role menus, role permissions) compare the
current value with the last fetched value only to enable or disable Save. The payload is always
the complete set (PRD §10).

### Development and test environments

| Environment | API | Keycloak | Used for |
|---|---|---|---|
| Unit and component tests (`npm test`, `npm run test:ci`) | MSW in Node, typed from `contract.ts` | none; auth module replaced by `fakeAuth.ts` (UI-AD-01) | every screen and hook |
| Before the service exists | none: screens are built test-first (UI-AD-02) | not used | building screens |
| Local development with the service | Vite proxy `/admin/v1` → `http://localhost:8085` | M0 Keycloak at `http://localhost:8180/auth`, realm `cce`, reached directly | manual checks |
| Browser tests (`npm run e2e`) | the real service through the Vite proxy | the same M0 Keycloak, real login page | CUJ 2 to CUJ 6 (Goal 9) |
| Docker image | Caddy → gateway-service | the environment's Keycloak (UI-AD-16) | shared environments |

**Local values** (admin-service §9.3). `.env.example` holds
`VITE_KEYCLOAK_URL=http://localhost:8180/auth`, `VITE_KEYCLOAK_REALM=cce`,
`VITE_KEYCLOAK_CLIENT_ID=cce-admin-ui` and an empty `VITE_API_BASE_URL`. None is a secret.
Keycloak is not proxied through Vite, because a proxied path would change the token issuer from
`http://localhost:8180/auth/realms/cce`, the value the service checks. Tokens from
`cce-admin-ui` carry the audience `gateway-service` that the service checks.

**MSW** (UI-AD-19). `src/test/mocks/` holds typed canned fixtures, one default handler per
endpoint the console calls, and a request recorder. Default handlers return the fixtures and
record each request (method, path, query, body), so tests assert on exactly what the console
sent. They do not implement the service's rules. A test that needs another answer (a 409, a 403,
a 502, a warning, an empty page) overrides one handler with `server.use(...)`, using
`errorResponse(...)` or `okResponse(...)`. The fixtures follow the admin-service seed
(admin-service §6) for menus and system roles, plus test data: a non-system role
`FACILITY_ADMIN` with its two implicit rows and two explicit rows, the test administrator, and
enough users for two pages.

**Browser tests** (UI-AD-04, UI-AD-09, UI-AD-10):

*   **Runner.** Playwright, Chromium only, one test at a time. The config starts `npm run dev` on
    port 3002 unless it is already running. The M0 stack and the service must already run.
*   **Pre-check.** A global setup stops with the admin-service §9.3 start commands if
    `GET http://localhost:8085/admin/v1/health` or `GET http://localhost:8090/_stub/mode` does
    not answer, then sets the stand-in to `ok`.
*   **Sign-in.** A setup project signs in once through the Keycloak login page with
    `CCE_TEST_ADMIN_USERNAME` and `CCE_TEST_ADMIN_PASSWORD`, read only from the environment, and
    saves the browser state to `e2e/.auth/admin.json`, which is git-ignored (item 14). Every CUJ
    test reuses that state and checks the test administrator's username in the header.
*   **Test data.** Each test creates its starting roles and users through the API, with a token
    from the local-only client `cce-e2e` (password grant, admin-service §9.2), and gives every
    name a per-run suffix. The steps under test go through the console.
*   **Stand-in switch.** CUJ 6 switches the stand-in with
    `PUT http://localhost:8090/_stub/mode` (plain-text body `fail`, then `ok`) and always sets
    `ok` again when it ends, pass or fail.
*   **Reset.** A full reset is the admin-service's `docker compose --profile app down -v`
    (admin-service §9.3).

### How each milestone exit check is shown

PRD §10 gives an exit check for each frontend milestone. Each part is shown in one or both of
two ways:

*   **MSW test**: a Vitest test under `npm run test:ci`, against MSW handlers.
*   **Real stack**: a Playwright test under `npm run e2e`, against M0 Keycloak and the service
    on 8085.

| Milestone | Exit check (PRD §10) | MSW test | Real stack |
|---|---|---|---|
| F1 | Login redirect works | `keycloak.test.ts`: `init` called with `login-required` and `S256`. `bootstrap.test.tsx`: nothing renders while sign-in is pending | The Playwright setup project signs in through the Keycloak login page |
| F1 | Header shows `/me` | `AppLayout.test.tsx`: header shows the username and role names from `GET /me`; the sidebar shows Users, Roles and Menus even when `/me` returns 403 | Every CUJ test checks the test administrator's username in the header |
| F1 | `ApiError` surfaces codes in a test | `client.test.ts`: error envelope to `ApiError` `status`, `code`, `details`, `partialState`; non-envelope body to `CLIENT_UNEXPECTED_RESPONSE`; failed fetch to `CLIENT_NETWORK_ERROR`; the 401 paths | not shown (test-only check) |
| F1 | 403 shown inline (F-04) | `UsersListPage.test.tsx` and `RolesListPage.test.tsx` with a 403 handler, one with and one without the envelope: inline message shown, shell still works | not shown (UI-AD-17) |
| F2 | Create role with name warning | `RolesListPage.test.tsx`: invalid names rejected; warning and confirmation dialog shown; `POST /roles` sent only after Confirm | CUJ 3 |
| F2 | Menus tree saves full set | `RoleMenusTab.test.tsx`: Save disabled until changed; recorded request body is the complete set | CUJ 3 |
| F2 | Warning on DETAIL-only | `RoleMenusTab.test.tsx`: client-side warning while editing; server warning shown after save | CUJ 3 grants one `DETAIL` menu without its parent and checks the server warning |
| F3 | Save shows "synced" | `RolePermissionsTab.test.tsx`: 200 shows "Saved." and "Synced · {time}"; implicit rows shown read-only and absent from the request | CUJ 3 |
| F3 | MSW 502 shows retry state | `RolePermissionsTab.test.tsx`: 502 with completed steps shows `SyncFailedNotice` and Re-sync, rows refetched, "Sync failed" badge; 502 with no steps keeps the edits | CUJ 6, using the stand-in switch |
| F3 | Re-sync works | `RolePermissionsTab.test.tsx`: Re-sync 200 shows "Synced"; Re-sync 502 shows the `ROLE_SYNC` text | CUJ 6 |
| F4 | Paginated, filtered list | `UsersListPage.test.tsx`: each filter and page change sends the matching query parameters; a filter change sets `page` to 1; page count from `totalPages`; invalid paging corrected | CUJ 2 finds the new user with the search box |
| F4 | Create and edit flows | `UserCreatePage.test.tsx`, `UserEditPage.test.tsx`: validation, one `POST`, `ACTION_EMAIL_NOT_SENT`, `ROLE_NOT_SYNCED`, `ROLE_NOT_ASSIGNABLE`, the four separate edit actions, `LAST_SYSTEM_ADMIN`, `EMAIL_IN_USE` on update | CUJ 2, CUJ 4, CUJ 5, CUJ 6 (`ROLE_NOT_SYNCED`) |
| F4 | 502 state on create | `UserCreatePage.test.tsx`: a 502 with completed steps shows the `USER_CREATE` text and the operator ids, with the form and its submit button still usable; a 502 with no completed steps shows "The service recorded no change. You can try again." | not shown: a Keycloak failure part-way through create cannot be caused on demand locally |
| F5 | Add and deactivate a menu entry | `MenusPage.test.tsx`: add a `DETAIL` under a `NAV`; `DUPLICATE_MENU_KEY` on the key field; deactivate reads the tree again and sends `PUT` with `isActive: false` and the other fields from that read | not shown: CUJ 8 is outside Goal 9, and changing the local menu seed would affect other tests |

### Build and deployment

*   **Node.** `package.json` declares `engines.node` as `>=20.19.0 <23`. The reference build is
    the Dockerfile on `node:20.20.2-alpine` (D-05); the author's workstation runs Node 22.22
    (C-03).
*   **Docker.** A three-stage Dockerfile.
    *   `build` (`node:20.20.2-alpine`, D-05) runs `npm ci` and `npm run build` with the four
        `VITE_*` values as build arguments.
    *   `verify` (from `build`) runs `npm run lint` and `npm run test:ci`, then fails if `dist/`
        contains the string `mockServiceWorker` or `setupServer`. It runs only when asked for
        with `--target verify` (UI-AD-11).
    *   The final stage (`caddy:2.11.9-alpine`, D-05) serves `dist/` on port 3002. Its command
        exits with "GATEWAY_UPSTREAM is required" when that variable is empty, before Caddy
        starts.
*   **Caddy.**
    *   `encode zstd gzip`.
    *   `/assets/*` gets `Cache-Control: public, max-age=31536000, immutable` (Vite's hashed
        file names).
    *   Every other path falls back to `index.html` (`try_files {path} /index.html`).
    *   Headers on every response: `X-Frame-Options: SAMEORIGIN`,
        `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`,
        and `Content-Security-Policy: default-src 'self'; script-src 'self'; connect-src 'self'
        {$KEYCLOAK_ORIGIN}; frame-ancestors 'none'; object-src 'none'; base-uri 'none'`. The
        optional runtime variable `KEYCLOAK_ORIGIN` (empty by default) names Keycloak's origin
        when it differs from the console's; the default `${origin}/auth` is already `'self'`.
        The policy fits the build: Vite emits no inline scripts, React sets styles through the
        DOM, and `keycloak-js` runs with `checkLoginIframe: false`, so it loads no frame.
    *   `/admin/v1/*` is reverse-proxied, path unchanged, to `{$GATEWAY_UPSTREAM}`, a runtime
        variable, not a `VITE_*` value. Its target is `gateway-service`, never the admin service
        directly (PRD §9).
*   **Origin.** The console needs an origin that also routes `/auth` to Keycloak, or an image
    built with `VITE_KEYCLOAK_URL` set (UI-AD-16). Caddy does not route `/auth`.
*   **Linting.** ESLint (flat config, typescript-eslint, react-hooks, the four import rules) and
    a Prettier check, both run by `npm run lint`.

## Alternatives Considered

Each alternative names the decision that rejected it.

### Alternative A: Pass an auth-client interface into the app

*   **Decided at:** [UI-AD-01](#author-decisions-in-this-design).
*   **Description:** `AuthProvider` and `client.ts` take an `AuthClient` interface (`getToken`,
    `refreshToken`, `login`, `logout`, `profile`). `main.tsx` passes the `keycloak-js`
    implementation, and a test helper passes a fake.
*   **Pros**: The fake is checked against the interface at compile time. No module mocking.
*   **Cons**: A test-only seam becomes part of production code. Nothing in the code stops a fake
    from being wired into `main.tsx`; only a lint rule or a review would.
*   **Reason for Rejection**: Rejected by author: the "no bypass" guarantee (Goal 7) would depend
    on a lint rule. The module swap keeps the seam out of production code, and the `satisfies`
    check on the fake gives the same compile-time protection.

### Alternative B: Mock `keycloak-js` in every test

*   **Decided at:** [UI-AD-01](#author-decisions-in-this-design).
*   **Description:** Every test replaces the `keycloak-js` package, so the real `initAuth()`,
    refresh interval and 401 handling run in every component test.
*   **Pros**: Every component test also exercises `initAuth()`, the refresh interval and the 401
    path.
*   **Cons**: Every component test depends on the `keycloak-js` API, and every render waits for
    an asynchronous sign-in. A library upgrade that changes behaviour the fake does not copy
    leaves the tests passing against a fake that no longer matches.
*   **Reason for Rejection**: Rejected by author: only one test file, `keycloak.test.ts`, should
    depend on `keycloak-js` details, and that file covers what this alternative would add.

### Alternative C: MSW in the browser in a `dev:mock` mode

*   **Decided at:** [UI-AD-02](#author-decisions-in-this-design).
*   **Description:** A `npm run dev:mock` script runs Vite in a mock mode that starts the MSW
    browser worker after sign-in, so every screen can be clicked through before the service
    exists.
*   **Pros**: Screens can be seen and demonstrated early, with the same handlers as the tests.
*   **Cons**: A mode branch in the start-up code, a worker file to keep out of `dist/`, and a
    sixth script. `/me` returns a fixed user whatever the token says.
*   **Reason for Rejection**: Rejected by author: no mock code path may reach a production
    bundle, and the scripts stay the PRD's five plus `e2e`. The period without a service ends
    when the service's milestone B1 runs, and the Goal 9 browser tests then cover it.

### Alternative D: A separate mock API server on port 8085

*   **Decided at:** [UI-AD-02](#author-decisions-in-this-design).
*   **Description:** A Node process serves the MSW handlers over HTTP on port 8085, and the
    normal Vite proxy forwards to it.
*   **Pros**: No mock code or mode in the console at all; the real proxy path is used.
*   **Cons**: An extra dependency, process and script. An HTTP adapter can treat some requests
    differently from MSW in Node, so the browser and the tests could see different mocks.
*   **Reason for Rejection**: Rejected by author, for the same reasons as Alternative C.

### Alternative E: A manual run for the E2E milestone

*   **Decided at:** [UI-AD-04](#author-decisions-in-this-design).
*   **Description:** The PRD's E2E milestone (§10) is shown by running the scenarios by hand
    once both tracks land, with no browser test framework.
*   **Pros**: No Playwright dependency, no browser download, no test data left behind by
    automated runs.
*   **Cons**: Shows the scenarios once. A later change that breaks a scenario against the real
    service is not caught.
*   **Reason for Rejection**: Rejected by author: a manual run gives no regression coverage
    (C-09).

### Alternative F: Generate the contract types from the service's OpenAPI spec

*   **Decided at:** [UI-AD-03](#author-decisions-in-this-design).
*   **Description:** The service publishes an OpenAPI document through springdoc (B-06). A
    generator such as `openapi-typescript` could produce `contract.ts` from it.
*   **Pros**: The types match the service's published spec.
*   **Cons**: The console cannot compile until the service runs, or until a spec file is copied
    into the console repo and kept current. Under D-01 the contract is the admin-service
    *design*, not its code. Types generated from the code follow the code when the code drifts
    from the design, which hides the drift instead of exposing it.
*   **Reason for Rejection**: Rejected by author: one hand-written module that mirrors the
    admin-service design's contract, with the MSW handlers built only from it. Drift from the
    running service is caught by the Goal 9 browser tests.

### Alternative G: A pre-save diff dialog on full-set saves

*   **Decided at:** [UI-AD-12](#author-decisions-in-this-design).
*   **Description:** Before a permissions or menus save, the console refetches the role's set and
    shows any difference from what the administrator started editing.
*   **Pros**: Narrows the window in which one administrator overwrites another's change.
*   **Cons**: Does not close the race; adds a dialog to every save of a hard control.
*   **Reason for Rejection**: Rejected by author: only a server-side version check closes the
    race, the race needs two administrators editing the same role within seconds, and the audit
    log records both saves.

### Alternative H: A runtime configuration file

*   **Decided at:** [UI-AD-14](#author-decisions-in-this-design).
*   **Description:** The image serves `/config.json`, filled at container start, so one image
    serves every environment.
*   **Pros**: One image for all environments.
*   **Cons**: Contradicts PRD §8.3, which makes `VITE_*` values build-time, one image per
    environment. Adds a fetch before sign-in.
*   **Reason for Rejection**: Rejected by author: no requirement asks for it, and it is a PRD
    deviation.

### Alternative I: Warn before an administrator removes their own access

*   **Decided at:** [UI-AD-15](#author-decisions-in-this-design).
*   **Description:** The console compares a status or role change with the signed-in user and
    warns before saving one that would remove the caller's own access.
*   **Pros**: Prevents an administrator from locking themselves out by mistake.
*   **Cons**: Duplicates a server rule in the console for the narrow case the server does not
    already refuse.
*   **Reason for Rejection**: Rejected by author: `LAST_SYSTEM_ADMIN` refuses the catastrophic
    case, and another administrator can restore the access.

### Alternative J: Serve the console under a base path on a shared origin

*   **Decided at:** [UI-AD-16](#author-decisions-in-this-design).
*   **Description:** As `cce-insights-ui` does with `VITE_ROUTER_BASE=/insights`, the console is
    served under a path such as `/admin-console` on an origin shared with other apps.
*   **Pros**: One origin for several consoles.
*   **Cons**: A base path in the router, in Vite's `base`, in the Keycloak redirect URI and in
    Caddy, all baked per image; the `/admin/v1` API path and the console path then share one
    origin's routing rules.
*   **Reason for Rejection**: Rejected by author: a dedicated origin keeps the PRD's
    `${origin}/auth` default and port 3002 without a base path.

### Superseded: Keep the Permissions tab's sync state in browser storage

*   **Status:** Superseded. The role resource carries `syncStatus` and `lastSyncAt` from the
    server (admin-service AD-06), so the tab reads those fields and keeps no state of its own.

### Options ruled out by the PRD (not weighed)

*   **A runtime auth flag or a static-token fallback**, as in `cce-insights-ui`: ruled out by
    PRD §6, §9 and F-01 (C-01).
*   **Optimistic updates**: ruled out by PRD §2 and F-06, because writes fan out to Keycloak and
    the gateway and can fail part-way.
*   **A global error page for 403**: ruled out by F-04.
*   **A role-driven sidebar in the admin console**: ruled out by PRD §2 for CCE v2 (F-03).

## Non-Functional Requirements

*   **Security.** No sign-in bypass (Goal 7). The access token lives only in memory in the
    `keycloak-js` instance. A Content-Security-Policy and three other security headers are set
    by Caddy ([Build and deployment](#build-and-deployment)). No credential, `.env` file or
    Keycloak export is committed (PRD §11): `.env` is git-ignored, `.env.example` holds only the
    four non-secret local values, and the browser tests read the test administrator's
    credentials from the environment. In shared environments the console reaches the service
    only through the gateway (Caddy's `GATEWAY_UPSTREAM`, PRD §9).
*   **Performance.** Lists are server-paged (20 rows per page by default). The only reads that
    are not paged are the menu tree (12 seed entries) and `useAllRoles`, bounded at five pages
    of 100 (UI-AD-20). The console does not poll.
*   **Rollout.** `VITE_*` values are inlined at build time, so each environment gets its own
    image (PRD §8.3). Rollback is redeploying the previous image. The console has no persistent
    state of its own.

### Risks accepted by the author

| Risk | Effect | Mitigation, or why accepted |
|---|---|---|
| GW-2: the gateway may load permissions only at start-up. | "Synced" can show while the gateway still enforces the old rows. | The help text says what "Synced" means (UI-AD-06). Open with the gateway owners. |
| Last write wins on full-set saves. | One administrator's save can silently replace another's made seconds earlier. | Accepted (UI-AD-12): rare, and the audit log records both saves. `If-Match`/412 follow-up recorded on the admin-service design. |
| `contract.ts` is a hand copy of the contract. | A change to the service's shapes is not caught by the console's build. | Caught by `npm run e2e` and by the service's own tests (UI-AD-03). |
| Some error paths are proved only against MSW. | A gateway 403, a Keycloak failure part-way through user create, and a network loss are never seen against the real stack. | The local stack cannot produce them on demand; the MSW tests use the contract's shapes. |
| Role count. | `useAllRoles` stops at 500 roles; pickers would not show more. | The pickers show "Showing the first 500 roles." (UI-AD-20). |
| Gateway error bodies are not the service's envelope. | A gateway 401 or 403 has an unknown body. | Classified by HTTP status ([Error presentation](#error-presentation)); open with the gateway owners. |
| Proxy timeouts are not tuned by the console. | A slow Keycloak call can surface as a non-envelope 502 or 504 from a proxy. | Shown as `unexpected`; a mutation then says the result is unknown and refetches. |
| Audit IP depends on the service trusting `X-Forwarded-For`. | Behind Caddy and an ingress, the recorded IP can be a proxy's, or one a client sets. | Raised on the admin-service design. |

## Detailed Implementation Plan

Every file below is created under `cce-admin-ui/` at the repository root, unless its path says
otherwise. No existing file is modified. Versions are exact
([D-05](author-decisions.md#d-05-pinned-versions-checked-on-2026-11-10)); `package-lock.json` is
committed.

### Order of work

Every step ends with `cp .env.example .env` (once, if `.env` does not exist) and
`npm run lint && npm run build && npm run test:ci`, plus its rows of the
[exit-check table](#how-each-milestone-exit-check-is-shown). The coverage thresholds apply at
every step, so each step's tests cover the files that step creates. `src/routes.tsx` gains each
page's route in the step that creates the page, and `schemas.test.ts` gains the cases for each
schema in the step that creates it.

1.  **Scaffold and tooling:** items 1 to 18, a placeholder `src/main.tsx` that renders nothing,
    and items 22 and 23. Check: lint and build pass; `test:ci` runs with no tests (Vitest
    `passWithNoTests`).
2.  **F1, shell and foundations:** items 19 to 30, 36 to 38, 44, 46 to 49, 52, 57 to 65, 81,
    82, 91 to 96; tests `config`, `bootstrap`, `keycloak`, `client`, `describeError`,
    `formErrors`, `lintRules`, `SyncFailedNotice`, `AppLayout`. Routes: layout, `/` redirect,
    `*`.
3.  **F2, roles and menus tab:** items 32, 33, 35, 40, 41, 43 (`useMenuTree` only), 45, 50, 54,
    71 to 74, 86 to 88; tests `useRoles`, `menuTree`, `schemas` (role cases), `RolesListPage`,
    `RoleDetailPage`, `RoleMenusTab`. Routes: `/roles`, `/roles/:id`. Until step 4,
    `RoleDetailPage` renders "Permissions are added in the next step." for
    `?tab=permissions`.
4.  **F3, permissions tab:** items 34, 42, 51, 55, 75, 76, 89; tests `permissions`, `schemas`
    (permission cases), `RolePermissionsTab`. `RoleDetailPage` now renders `RolePermissionsTab`.
5.  **F4, users:** items 31, 39, 53, 66 to 70, 83 to 85; tests `schemas` (user cases),
    `UsersListPage`, `UserCreatePage`, `UserEditPage`. Routes: `/users`, `/users/new`,
    `/users/:id`.
6.  **F5, menus catalog:** the rest of item 43, items 56, 77 to 80, 90; tests `schemas` (menu
    cases), `MenusPage`. Route: `/menus`.
7.  **Browser tests:** items 97 to 105, once the admin-service stack runs. Check: `npm run e2e`.

### Target Files to Create

#### Project and tooling

##### 1. `package.json`

*   **Type / Scope**: npm manifest.
*   **Proposed Changes**: `"name": "cce-admin-ui"`, `"private": true`, `"type": "module"`,
    `"engines": { "node": ">=20.19.0 <23" }`. Scripts, exactly:

    *   `dev`: `vite`
    *   `build`: `tsc -b && vite build`
    *   `test`: `vitest`
    *   `test:ci`: `vitest run --coverage`
    *   `lint`: `eslint . && prettier --check .`
    *   `e2e`: `playwright test`

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

    Install with `npm install --save-exact`, so `package.json` holds no ranges.

##### 2. `package-lock.json` (generated)

*   **Type / Scope**: lockfile, produced by `npm install`, so `npm ci` in Docker installs the
    same versions.

##### 3. `tsconfig.json`

*   **Proposed Changes**: `"files": []`, references to `tsconfig.app.json` and
    `tsconfig.node.json`, so `tsc -b` checks both.

##### 4. `tsconfig.app.json`

*   **Type / Scope**: compiler settings for `src/`.
*   **Proposed Changes**: target ES2022, lib `["ES2022", "DOM", "DOM.Iterable"]`, module
    ESNext, moduleResolution bundler, `jsx: react-jsx`, `strict: true`,
    `noUncheckedIndexedAccess: true`, `noUnusedLocals: true`, `noUnusedParameters: true`,
    `verbatimModuleSyntax: true`, `skipLibCheck: true`, `noEmit: true`,
    `tsBuildInfoFile: "./node_modules/.tmp/tsconfig.app.tsbuildinfo"`,
    `types: ["vite/client", "vitest/globals"]`, include `["src"]`. The jest-dom matcher types
    come from `src/test/setup.ts` importing `@testing-library/jest-dom/vitest`. Test files are
    included, so `tsc -b` type-checks the fake auth module (UI-AD-01).

##### 5. `tsconfig.node.json`

*   **Type / Scope**: compiler settings for config files and browser tests.
*   **Proposed Changes**: same strictness, `types: ["node"]`, `skipLibCheck: true`,
    `noEmit: true`, `allowJs: true`,
    `tsBuildInfoFile: "./node_modules/.tmp/tsconfig.node.tsbuildinfo"`; include
    `vite.config.ts`, `vitest.config.ts`, `playwright.config.ts`, `eslint.config.js` and
    `e2e/**/*.ts`.

##### 6. `vite.config.ts`

*   **Proposed Changes**: plugins `react()` and `tailwindcss()`. `server.port` 3002 with
    `strictPort: true`. `server.proxy` maps `/admin/v1` to `http://localhost:8085` (D-03) with
    `changeOrigin: false`, so the service sees the same `Host` a same-origin deployment would
    send. In the function form of the config, when `command` is `build`, load `VITE_*` values
    with `loadEnv(mode, process.cwd(), 'VITE_')` and throw
    `Error('VITE_KEYCLOAK_REALM is required for a build (PRD §8.3)')` if `VITE_KEYCLOAK_REALM`
    is empty after trimming (C-02).

##### 7. `vitest.config.ts`

*   **Proposed Changes**:
    *   Plugin `react()`. `test.environment` `jsdom`, `globals: true`,
        `setupFiles: ['./src/test/setup.ts']`, `include: ['src/**/*.test.{ts,tsx}']`,
        `passWithNoTests: true`.
    *   `test.env`: `VITE_KEYCLOAK_URL=http://keycloak.test/auth`, `VITE_KEYCLOAK_REALM=cce`,
        `VITE_KEYCLOAK_CLIENT_ID=cce-admin-ui`, `VITE_API_BASE_URL=http://localhost:3002`. The
        base URL is absolute in tests because Node's `fetch` rejects relative URLs; the MSW
        handlers match `*/admin/v1/...`.
    *   Coverage: provider `v8`, include `src/**`, exclude
        `[...coverageConfigDefaults.exclude, 'src/test/**', 'src/main.tsx',
        '**/__lint_fixtures__/**']`; thresholds lines 80, statements 80, functions 80,
        branches 70.

##### 8. `playwright.config.ts`

*   **Proposed Changes**: `testDir: 'e2e'`, `workers: 1`, `fullyParallel: false`, `retries: 0`,
    `timeout: 60_000`, `use: { baseURL: 'http://localhost:3002', trace: 'retain-on-failure' }`,
    `globalSetup: './e2e/global-setup.ts'`. Projects: `setup` (matches `auth.setup.ts`) and
    `chromium` (Desktop Chrome, `storageState: 'e2e/.auth/admin.json'`,
    `dependencies: ['setup']`). `webServer: { command: 'npm run dev', url:
    'http://localhost:3002', reuseExistingServer: true, timeout: 60_000 }`.

##### 9. `eslint.config.js`

*   **Type / Scope**: lint rules (the four import rules of the Technical Plan).
*   **Proposed Changes**:
    *   **Global ignores:** `dist`, `coverage`, `playwright-report`, `test-results`,
        `e2e/.auth`, `**/__lint_fixtures__/**`.
    *   **Base:** `@eslint/js` recommended; `typescript-eslint` `recommendedTypeChecked` for
        `**/*.{ts,tsx}` with `parserOptions.projectService: true` and
        `tsconfigRootDir: import.meta.dirname`; `reactHooks.configs['recommended-latest']`;
        browser globals for `src/**`, Node globals for config files and `e2e/**`;
        `eslint-config-prettier` last.
    *   **Import rules.** One `@typescript-eslint/no-restricted-imports` block per file group.
        The groups do not overlap (each uses `files` and `ignores`), so no block overrides
        another, and each carries the full set of its restrictions. Test files below means
        `**/*.test.{ts,tsx}` and `src/test/**`.
        *   `src/components/**/*.{ts,tsx}` (not tests): patterns
            `{ group: ['**/api/contract'], allowTypeImports: true }`,
            `{ group: ['**/api/*', '!**/api/contract'] }`, `{ group: ['**/hooks/*'] }`,
            `{ group: ['msw/*'] }`; paths `@tanstack/react-query`, `keycloak-js`, `msw`,
            `@playwright/test`.
        *   `src/pages/**/*.{ts,tsx}` (not tests): the same without the `hooks` and
            `@tanstack/react-query` entries.
        *   `src/auth/**/*.{ts,tsx}` (not tests): paths `msw`, `@playwright/test`; pattern
            `msw/*`.
        *   every other `src/**/*.{ts,tsx}` (not tests): paths `keycloak-js`, `msw`,
            `@playwright/test`; pattern `msw/*`.
        *   test files, `e2e/**` and `playwright.config.ts`: no import restrictions.

        Each entry carries a message naming the rule, for example "Pages never import api/
        modules (PRD §10); use a hook."

##### 10. `.prettierrc.json`

*   **Proposed Changes**: `{ "singleQuote": true, "semi": true, "printWidth": 100,
    "trailingComma": "all" }`.

##### 11. `.prettierignore`

*   **Proposed Changes**: `dist`, `coverage`, `playwright-report`, `test-results`, `e2e/.auth`,
    `package-lock.json`.

##### 12. `index.html`

*   **Proposed Changes**: `lang="en"`, title "CCE Admin", `<div id="root"></div>`, module
    script `/src/main.tsx`.

##### 13. `.env.example`

*   **Proposed Changes**: `VITE_KEYCLOAK_URL=http://localhost:8180/auth`,
    `VITE_KEYCLOAK_REALM=cce`, `VITE_KEYCLOAK_CLIENT_ID=cce-admin-ui`, `VITE_API_BASE_URL=`
    (empty, so the Vite proxy is used), each with a one-line comment. A comment says the browser
    tests read `CCE_TEST_ADMIN_USERNAME` and `CCE_TEST_ADMIN_PASSWORD` from the environment and
    that they must not be added here.

##### 14. `.gitignore`

*   **Proposed Changes**: `node_modules`, `dist`, `coverage`, `.env`, `.env.*` with
    `!.env.example`, `playwright-report`, `test-results`, `e2e/.auth`. Reason: PRD §11 (no
    `.env` files committed); `e2e/.auth` holds a Keycloak session.

##### 15. `.dockerignore`

*   **Proposed Changes**: `node_modules`, `dist`, `coverage`, `.env`, `.env.*`,
    `playwright-report`, `test-results`, `e2e/.auth`, `.git`.

##### 16. `Dockerfile`

*   **Type / Scope**: reference build (PRD §6, Goal 8).
*   **Proposed Changes**: three stages.
    *   `build`: `FROM node:20.20.2-alpine AS build`, `WORKDIR /app`, copy `package.json` and
        `package-lock.json`, `RUN npm ci --ignore-scripts`, copy the rest. `ARG
        VITE_API_BASE_URL=""`, `ARG VITE_KEYCLOAK_URL=""`, `ARG VITE_KEYCLOAK_REALM` (no
        default), `ARG VITE_KEYCLOAK_CLIENT_ID="cce-admin-ui"`, the matching `ENV` lines,
        `RUN npm run build`.
    *   `verify`: `FROM build AS verify`, `RUN npm run lint && npm run test:ci`, then
        `RUN ! grep -rqE 'mockServiceWorker|setupServer' dist`.
    *   final: `FROM caddy:2.11.9-alpine`, copy `/app/dist` to `/srv`, copy `Caddyfile` to
        `/etc/caddy/Caddyfile`, `EXPOSE 3002`, and
        `CMD ["sh", "-c", "[ -n \"$GATEWAY_UPSTREAM\" ] || { echo 'GATEWAY_UPSTREAM is required' >&2; exit 1; }; exec caddy run --config /etc/caddy/Caddyfile"]`.

##### 17. `Caddyfile`

*   **Proposed Changes**: site `:3002`. As in
    [Build and deployment](#build-and-deployment): `encode zstd gzip`; `handle /admin/v1/*`
    with `reverse_proxy {$GATEWAY_UPSTREAM}`; a default `handle` with `root * /srv`, the
    `/assets/*` `Cache-Control` header, `try_files {path} /index.html` and `file_server`; and a
    `header` block with the four security headers, the CSP using `{$KEYCLOAK_ORIGIN}`. The
    layout starts as a copy of the `cce-insights-ui` Caddyfile.

##### 18. `README.md`

*   **Proposed Changes**:
    *   What the console is, with links to this design and the admin-service design.
    *   Prerequisites: Node in the `engines` range `>=20.19.0 <23`, npm 10, Docker.
    *   Setup: `npm ci`, `cp .env.example .env`.
    *   The six scripts.
    *   Running against the real service: start the admin-service stack as in admin-service
        §9.3, then `npm run dev`, open `http://localhost:3002`.
    *   Browser tests: one-time `npx playwright install chromium`; the stack and service
        running; `export CCE_TEST_ADMIN_USERNAME=... CCE_TEST_ADMIN_PASSWORD=...` with the
        values from the admin-service `.env`; `npm run e2e`; reset with the admin-service's
        `docker compose --profile app down -v`.
    *   Docker: `docker build --build-arg VITE_KEYCLOAK_REALM=... .`, `docker build --target
        verify ...`, `docker run -e GATEWAY_UPSTREAM=... -p 3002:3002`, and the origin rule
        (UI-AD-16).
    *   The four import rules.

#### Application entry

##### 19. `src/main.tsx`

*   **Proposed Changes**: imports `./index.css` and calls
    `bootstrap(document.getElementById('root')!)`. In step 1 it is a placeholder that renders
    nothing.

##### 20. `src/bootstrap.tsx`

*   **Proposed Changes**: `bootstrap(root: HTMLElement): Promise<void>`. Calls `readConfig()`;
    on failure renders `StartupErrorScreen` with "Configuration error: VITE_KEYCLOAK_REALM is not
    set." (one line per missing variable) and returns. Otherwise awaits `initAuth(config)`; on
    rejection renders `StartupErrorScreen` with "Sign-in did not complete with the sign-in
    service at {keycloakUrl}."; on success renders `<StrictMode>`, `QueryClientProvider` with
    `createQueryClient()`, and `RouterProvider` with `createBrowserRouter(routes)`.

##### 21. `src/routes.tsx`

*   **Proposed Changes**: exports `routes`: one route with element `AppLayout` and children:
    index → `Navigate` to `/users`; `users` → `UsersListPage`; `users/new` → `UserCreatePage`;
    `users/:id` → `UserEditPage`; `roles` → `RolesListPage`; `roles/:id` → `RoleDetailPage`;
    `menus` → `MenusPage`; `*` → `NotFoundPage`.

##### 22. `src/index.css`

*   **Proposed Changes**: `@import "tailwindcss";`.

##### 23. `src/vite-env.d.ts`

*   **Proposed Changes**: `/// <reference types="vite/client" />` and an `ImportMetaEnv`
    interface with the four `VITE_*` variables as optional strings.

##### 24. `src/config.ts`

*   **Proposed Changes**: exports type `AppConfig { keycloakUrl; keycloakRealm;
    keycloakClientId; apiBaseUrl }`, `readConfig(): { ok: true; config: AppConfig } | { ok:
    false; missing: string[] }` with the defaults and trimming from the Technical Plan, and
    `getApiBaseUrl(): string` for `client.ts`. It reads only the four §8.3 variables.

#### Auth

##### 25. `src/auth/keycloak.ts`

*   **Type / Scope**: the only production module that uses `keycloak-js` (F-01).
*   **Proposed Changes**: a module-level `Keycloak` instance and refresh timer. Named exports:
    *   `initAuth(config: AppConfig): Promise<void>`: creates the instance; calls `init` with
        `onLoad: 'login-required'`, `pkceMethod: 'S256'`, `checkLoginIframe: false`; rejects if
        `init` throws or resolves without a session; starts an interval of 60 seconds that
        calls `updateToken(70)` and ignores a rejection.
    *   `getToken(): string | undefined`.
    *   `refreshToken(): Promise<boolean>`: `updateToken(-1)`; `true` on success; on failure
        calls `login()` and returns `false`.
    *   `login(): Promise<void>`.
    *   `logout(): Promise<void>`: redirects to `window.location.origin + '/'`.

##### 26. `src/auth/useAuth.ts`

*   **Proposed Changes**: `useAuth(): { logout }`, so `AppLayout` can pass a sign-out callback to
    the header without the header importing `auth/` (UI-DEV-1).

#### API layer

##### 27. `src/api/contract.ts`

*   **Type / Scope**: the single contract module (D-01, UI-AD-03, Goal 6).
*   **Proposed Changes**: types only, plus three constant arrays. Mirrors admin-service §4.1 to
    §4.5 with the same property names. Response fields that can be empty are `T | null`, never
    optional (see API client).
    *   **Envelopes:**
        *   `SuccessEnvelope<T> { data: T; pagination?: Pagination; warnings?: Warning[] }`.
        *   `Pagination { page; pageSize; totalItems; totalPages }`.
        *   `WarningCode = 'DETAIL_WITHOUT_PARENT' | 'ACTION_EMAIL_NOT_SENT' |
            'ROLE_SYNC_FAILED'`.
        *   `Warning { code: WarningCode; message: string; menuId: string | null; parentMenuId:
            string | null }`.
        *   `ErrorEnvelope { error: { code: string; message: string; details?: FieldError[];
            partialState?: PartialState } }`. `code` is `string`, not `ErrorCode`, because an
            unknown code must still parse and become `unexpected`.
        *   `FieldError { field; message }`.
        *   `PartialStateOperation`: the operations of admin-service §4.2.
        *   `PartialState { operation; entityId: string | null; failedStep: string;
            completedSteps: string[]; keycloakUserId: string | null; auditLogId: string | null
            }`.
        *   `ErrorCode`: every code in admin-service §4.2.
    *   **Users:**
        *   `UserStatus = 'ACTIVE' | 'INACTIVE' | 'LOCKED'`.
        *   `UserRoleRef { id; name; displayName }`.
        *   `User` (fields of §4.4; `facilityScope: string | null`,
            `lastLoginAt: string | null`).
        *   `UserListQuery { status?; roleId?; facilityScope?; q?; page?; pageSize? }`.
        *   `CreateUserRequest { username; email; firstName; lastName; facilityScope?; roleIds
            }`.
        *   `UpdateUserRequest { email; firstName; lastName; facilityScope: string | null }`.
        *   `ChangeUserStatusRequest { status }`.
        *   `ReplaceUserRolesRequest { roleIds }`.
        *   `ResetPasswordResult { userId; email }`.
    *   **Roles:**
        *   `SyncStatus = 'NEVER' | 'SYNCED' | 'FAILED'`.
        *   `Role` (fields of §4.4; `description: string | null`, `lastSyncAt: string | null`).
        *   `CreateRoleRequest { name; displayName; description? }`.
        *   `UpdateRoleRequest { displayName; description: string | null }`.
        *   `RoleMenus { roleId; menuIds }`.
        *   `ReplaceRoleMenusRequest { menuIds }`.
        *   `PermissionRow { id: number; httpMethod; uriPattern; description: string | null;
            resourceCategory: string | null; implicit: boolean }`.
        *   `PermissionRowInput { httpMethod; uriPattern; description?; resourceCategory? }`.
        *   `RolePermissions { roleId; syncStatus; lastSyncAt: string | null; permissions:
            PermissionRow[] }`.
        *   `ReplaceRolePermissionsRequest { permissions: PermissionRowInput[] }`.
        *   `RoleSyncResult { roleId; syncStatus; lastSyncAt: string | null }`.
    *   **Menus:**
        *   `MenuType = 'NAV' | 'DETAIL'`.
        *   `Menu` (fields of §4.4; `icon: string | null`, `parentId: string | null`,
            `children: Menu[]`).
        *   `CreateMenuRequest { key; label; path; icon?; type; parentId?; sortOrder; isActive?
            }`.
        *   `UpdateMenuRequest { label; path; icon: string | null; parentId: string | null;
            sortOrder; isActive }`.
    *   **Me:** `Me { id; username; roles: string[]; facilityScope: string | null }`.
    *   **Constants:** `USER_STATUSES`, `SYNC_STATUSES`, and `PERMISSION_METHODS = ['GET',
        'POST', 'PUT', 'PATCH', 'DELETE']` (UI-AD-05).

    The header comment links `../../../docs/designs/cce-admin-service.md` §4 (relative to
    `src/api/`) and states "mirrors the contract of 2026-11-10".

##### 28. `src/api/ApiError.ts`

*   **Proposed Changes**: `class ApiError extends Error` with `status: number`, `code: string`,
    `details?: FieldError[]`, `partialState?: PartialState`, `hasEnvelope: boolean`; exported
    constants `CLIENT_NETWORK_ERROR` and `CLIENT_UNEXPECTED_RESPONSE`.

##### 29. `src/api/client.ts`

*   **Type / Scope**: the typed `fetch` wrapper (F-02).
*   **Proposed Changes**: `apiRequest<T>(method: string, path: string, options?: { query?:
    Record<string, string | number | null | undefined>; body?: unknown; signal?: AbortSignal
    }): Promise<SuccessEnvelope<T> | undefined>`, with the behaviour in
    [API client and the contract module](#api-client-and-the-contract-module): URL building,
    headers, missing token, the 401 paths, 204 to `undefined`, JSON parse, error envelope to
    `ApiError`, the two `CLIENT_` codes. The sibling file `src/api/results.ts`, part of this
    item, exports the result types the resource modules return:
    `Page<T> = { items: T[]; pagination: Pagination }` and
    `WithWarnings<T> = { data: T; warnings: Warning[] }` (`warnings` is `[]` when the envelope
    has none).

##### 30. `src/api/me.ts`

*   **Proposed Changes**: `getMe(): Promise<Me>`.

##### 31. `src/api/users.ts`

*   **Proposed Changes**: using `Page` and `WithWarnings` from `src/api/results.ts` (item 29):
    *   `listUsers(query: UserListQuery): Promise<Page<User>>`
    *   `getUser(id: string): Promise<User>`
    *   `createUser(body: CreateUserRequest): Promise<WithWarnings<User>>`
    *   `updateUser(id: string, body: UpdateUserRequest): Promise<User>`
    *   `changeUserStatus(id: string, body: ChangeUserStatusRequest): Promise<User>`
    *   `replaceUserRoles(id: string, body: ReplaceUserRolesRequest): Promise<User>`
    *   `resetUserPassword(id: string): Promise<ResetPasswordResult>`

##### 32. `src/api/roles.ts`

*   **Proposed Changes**:
    *   `listRoles(params: { page: number; pageSize: number }): Promise<Page<Role>>`
    *   `listAllRoles(): Promise<{ items: Role[]; truncated: boolean }>` (UI-AD-20)
    *   `getRole(id: string): Promise<Role>`
    *   `createRole(body: CreateRoleRequest): Promise<WithWarnings<Role>>`
    *   `updateRole(id: string, body: UpdateRoleRequest): Promise<Role>`
    *   `deleteRole(id: string): Promise<void>`
    *   `syncRole(id: string): Promise<RoleSyncResult>`

##### 33. `src/api/roleMenus.ts`

*   **Proposed Changes**: `getRoleMenus(id: string): Promise<RoleMenus>`,
    `replaceRoleMenus(id: string, body: ReplaceRoleMenusRequest):
    Promise<WithWarnings<RoleMenus>>`.

##### 34. `src/api/rolePermissions.ts`

*   **Proposed Changes**: `getRolePermissions(id: string): Promise<RolePermissions>`,
    `replaceRolePermissions(id: string, body: ReplaceRolePermissionsRequest):
    Promise<RolePermissions>`.

##### 35. `src/api/menus.ts`

*   **Proposed Changes**: `getMenuTree(): Promise<Menu[]>`, `createMenu(body:
    CreateMenuRequest): Promise<Menu>`, `updateMenu(id: string, body: UpdateMenuRequest):
    Promise<Menu>`. No delete (UI-AD-07).

#### Hooks

##### 36. `src/hooks/queryKeys.ts`

*   **Proposed Changes**: one function per key in
    [Server state](#server-state-hooks-query-keys-and-refetching).

##### 37. `src/hooks/queryClient.ts`

*   **Proposed Changes**: `createQueryClient(options?: { retry?: boolean }): QueryClient`.
    Queries: `staleTime` 30 000 ms, `refetchOnWindowFocus: false`, retry once only for status 0
    or 500 and above (off when `options.retry === false`). Mutations: `retry: 0`. A
    `MutationCache` with `onSettled` applies the refetch rule: on success, or when the error's
    `describeError` kind is `syncFailed`, `network` or `unexpected`, it calls
    `queryClient.invalidateQueries()`.

##### 38. `src/hooks/useMe.ts`

*   **Proposed Changes**: `useMe()` wrapping `getMe`.

##### 39. `src/hooks/useUsers.ts`

*   **Proposed Changes**: `useUserList(query)` (with `placeholderData: keepPreviousData`),
    `useUser(id)`, `useCreateUser`, `useUpdateUser`, `useChangeUserStatus`,
    `useReplaceUserRoles`, `useResetUserPassword`.

##### 40. `src/hooks/useRoles.ts`

*   **Proposed Changes**: `useRoleList({ page, pageSize })`, `useAllRoles()`, `useRole(id)`,
    `useCreateRole`, `useUpdateRole`, `useDeleteRole`, `useSyncRole`.

##### 41. `src/hooks/useRoleMenus.ts`

*   **Proposed Changes**: `useRoleMenus(id)`, `useReplaceRoleMenus(id)`.

##### 42. `src/hooks/useRolePermissions.ts`

*   **Proposed Changes**: `useRolePermissions(id)`, `useReplaceRolePermissions(id)`.

##### 43. `src/hooks/useMenus.ts`

*   **Proposed Changes**: `useMenuTree()`; `useCreateMenu()`; `useUpdateMenu()`;
    `useToggleMenuActive()`, whose mutation function calls `getMenuTree()` afresh, finds the
    entry, builds an `UpdateMenuRequest` from its current values with `isActive` flipped, and
    calls `updateMenu` (UI-AD-12).

##### 44. `src/hooks/useDebouncedValue.ts`

*   **Proposed Changes**: `useDebouncedValue<T>(value: T, delayMs: number): T`, used with 300.

##### 45. `src/hooks/usePagingParams.ts`

*   **Proposed Changes**: `usePagingParams()` reads `page` and `pageSize` from the URL search
    parameters through `normalizePagingParams` (invalid values become 1 and 20) and returns them
    with `setPage`, `setPageSize` and `resetPage` (used on every filter change), all keeping the
    other search parameters. It also exports `clampPageToTotal(page, totalPages)` for the
    past-the-last-page correction.

##### 46. `src/hooks/useUnsavedChangesGuard.ts`

*   **Proposed Changes**: `useUnsavedChangesGuard(isDirty: boolean)` wraps `useBlocker` and
    returns `{ blocked, proceed, reset }` for a `ConfirmDialog` with "You have unsaved changes.
    Leave without saving?".

#### Utilities and schemas

##### 47. `src/utils/describeError.ts`

*   **Proposed Changes**: type `DescribedError { kind; code; status; message; details?;
    partialState? }` and `describeError(error: unknown, options?: { detailFetch?: boolean }):
    DescribedError`, following the kinds table. Non-`ApiError` values become `unexpected`.

##### 48. `src/utils/formErrors.ts`

*   **Proposed Changes**: `toFormPath(field)` (`a[2].b` → `a.2.b`);
    `applyServerErrors(setError, error, formFields, codeToField): string[]`, which sets field
    errors from `details` and conflict codes and returns the messages for the top of the form;
    `roleErrorMessages(error, sentRoleIds, roles): string[]` for `ROLE_NOT_SYNCED` and
    `ROLE_NOT_ASSIGNABLE`.

##### 49. `src/utils/format.ts`

*   **Proposed Changes**: `formatInstant(iso: string | null, empty = 'Never'): string` with
    date-fns `parseISO` and `format(..., 'yyyy-MM-dd HH:mm')`.

##### 50. `src/utils/menuTree.ts`

*   **Proposed Changes**: `flattenMenus(tree)`, `menuById(tree)`, `navMenus(tree)`,
    `nextSortOrder(siblings)` (highest + 10, or 10, capped at 10000), and
    `detailsWithoutParent(tree, selectedIds)` returning `{ menu, parent }` pairs.

##### 51. `src/utils/permissions.ts`

*   **Proposed Changes**: `normalizeRow(row)`, `explicitRows(rows)`, `sameRuleSet(a, b):
    boolean` (set comparison of normalized rows), `toRequestRows(formRows)` (drops empty
    optional fields).

##### 52. `src/schemas/rules.ts`

*   **Proposed Changes**: the patterns, lengths and limits of admin-service §4.4 and §4.5 as
    named constants, with a comment linking those sections; re-exports `USER_STATUSES` and
    `PERMISSION_METHODS` from `contract.ts` for components.

##### 53. `src/schemas/userSchemas.ts`

*   **Proposed Changes**: `createUserSchema`, `profileSchema`, `userRolesSchema`.

##### 54. `src/schemas/roleSchemas.ts`

*   **Proposed Changes**: `createRoleSchema`, `roleDisplaySchema`.

##### 55. `src/schemas/permissionSchemas.ts`

*   **Proposed Changes**: `permissionRowSchema` and `permissionsFormSchema(implicitRows)`; the
    duplicate and implicit-equal errors are placed on `permissions.{i}.uriPattern`.

##### 56. `src/schemas/menuSchemas.ts`

*   **Proposed Changes**: `menuFormSchema(navIds)`, with the parent rule.

#### Components

All components receive data and callbacks as props. Every input has a `<label>`, and every
button has a visible name, so tests can find them by role and label.

##### 57. `src/components/layout/Sidebar.tsx`

*   **Proposed Changes**: "CCE Admin" title and three `NavLink`s: Users (`UsersIcon`), Roles
    (`ShieldCheckIcon`), Menus (`Squares2X2Icon`). No props (F-03).

##### 58. `src/components/layout/Header.tsx`

*   **Proposed Changes**: props `{ username?: string; roles?: string[]; error?: DescribedError;
    onSignOut: () => void }`. Shows the username and roles joined by ", ", or the error's text
    from the kinds table, and a "Sign out" button.

##### 59. `src/components/shared/InlineError.tsx`

*   **Proposed Changes**: props `{ error: DescribedError; onRetry?: () => void; mutation?:
    boolean }`; `role="alert"`; text from the kinds table; the Retry button only when
    `onRetry` is set; the "result is unknown" text when `mutation` is true.

##### 60. `src/components/shared/SyncFailedNotice.tsx`

*   **Proposed Changes**: props `{ partialState: PartialState; message: string; onResync?: () =>
    void; resyncPending?: boolean; usersLink?: boolean }`; the rules and table of
    [`SyncFailedNotice`](#syncfailednotice); `role="alert"`.

##### 61. `src/components/shared/ConfirmDialog.tsx`

*   **Proposed Changes**: a `div` with `role="dialog"`, `aria-modal="true"`, a title, body,
    Confirm and Cancel buttons, and a pending state on Confirm. Not the native `<dialog>`
    element, because jsdom does not implement `showModal`.

##### 62. `src/components/shared/StatusBadge.tsx`

*   **Proposed Changes**: props `{ status: UserStatus }`; "Active" (green), "Inactive" (gray),
    "Locked" (amber).

##### 63. `src/components/shared/SyncStatusBadge.tsx`

*   **Proposed Changes**: props `{ syncStatus: SyncStatus; lastSyncAt: string | null }`;
    "Synced · {time}" (green), "Never synced" (gray), "Sync failed · {time}" (red), with
    `{time}` from `formatInstant`.

##### 64. `src/components/shared/Pagination.tsx`

*   **Proposed Changes**: props `{ page; totalPages; totalItems; pageSize; onPageChange;
    onPageSizeChange }`: "Previous", "Next", "Page {page} of {totalPages} · {totalItems} items",
    and a page-size select (20, 50, 100).

##### 65. `src/components/shared/PageHeader.tsx`, `LoadingSpinner.tsx`, `EmptyState.tsx`, `NotFoundPanel.tsx`, `FormErrorSummary.tsx`, `WarningList.tsx`, `SuccessNotice.tsx`

*   **Proposed Changes**: page title and actions; spinner with `role="status"` and "Loading…";
    empty-list message; "Not found" panel with a back link; list of form-level messages; list
    of warnings (`role="status"`); success message (`role="status"`).

##### 66. `src/components/users/UserFilters.tsx`

*   **Proposed Changes**: props `{ values; roles; truncated; onChange }`: status select, role
    select (assignable roles; "Showing the first 500 roles." when `truncated`), facility scope
    and search inputs.

##### 67. `src/components/users/UserTable.tsx`

*   **Proposed Changes**: TanStack Table with `manualPagination`; props `{ users; pagination;
    onAction(user, action); pendingUserId }`; the columns and row actions of the Users list.

##### 68. `src/components/users/RoleCheckboxList.tsx`

*   **Proposed Changes**: props `{ roles; selectedIds; onChange; disabled; roleErrors; truncated
    }`; one checkbox per role with display name and name and, when the role's `syncStatus` is
    not `SYNCED`, its `SyncStatusBadge`.

##### 69. `src/components/users/UserCreateForm.tsx`

*   **Proposed Changes**: React Hook Form with `createUserSchema`; props `{ roles; truncated;
    onSubmit; pending; serverError }`; uses `applyServerErrors` and `roleErrorMessages`.

##### 70. `src/components/users/UserProfileForm.tsx`, `UserRolesForm.tsx`, `UserStatusActions.tsx`, `UserPasswordAction.tsx`

*   **Proposed Changes**: the four User edit sections, each with its own submit callback,
    pending flag and error props.

##### 71. `src/components/roles/RoleTable.tsx`

*   **Proposed Changes**: TanStack Table with `manualPagination`; props `{ roles; pagination;
    onDelete(role); pendingRoleId }`; the Roles list columns and Delete rules.

##### 72. `src/components/roles/RoleCreateForm.tsx`

*   **Proposed Changes**: React Hook Form with `createRoleSchema`, the permanent-name warning,
    and a `ConfirmDialog` before `onSubmit` is called.

##### 73. `src/components/roles/RoleDisplayForm.tsx`

*   **Proposed Changes**: display name and description; disabled with the system-role note when
    `isSystem`.

##### 74. `src/components/roles/MenuCheckboxTree.tsx`

*   **Proposed Changes**: props `{ tree; selectedIds; onToggle; disabled }`; the two-level tree
    with one checkbox per menu labelled by its label, showing path, type and "Inactive".

##### 75. `src/components/roles/PermissionsEditor.tsx`

*   **Proposed Changes**: the read-only implicit table and the editable field-array table;
    props `{ implicitRows; explicitRows; disabled; onSubmit; pending; serverError }`; Save is
    enabled when `sameRuleSet(current, fetched)` is false.

##### 76. `src/components/roles/SyncStatusPanel.tsx`

*   **Proposed Changes**: props `{ syncStatus; lastSyncAt; onResync; pending }`; badge, help text
    (UI-AD-06), Re-sync button with pending state.

##### 77. `src/components/menus/iconMap.ts`

*   **Proposed Changes**: the ten seed icon names mapped to Heroicons 2 outline components
    (UI-AD-08), and `QuestionMarkCircleIcon` as the fallback.

##### 78. `src/components/menus/MenuIcon.tsx`

*   **Proposed Changes**: props `{ name: string | null }`; the mapped icon, the fallback with the
    raw name, or nothing.

##### 79. `src/components/menus/MenuTreeTable.tsx`

*   **Proposed Changes**: props `{ tree; onAddDetail(nav); onEdit(menu); onToggle(menu);
    pendingMenuId }`; the catalog table with indentation and row actions.

##### 80. `src/components/menus/MenuForm.tsx`

*   **Proposed Changes**: React Hook Form with `menuFormSchema`; create and edit modes; key and
    type read-only in edit mode; icon select as in UI-AD-08; parent select listing every `NAV`
    entry, inactive ones marked "(inactive)"; defaults from the Menus catalog section. Rendered
    as an inline panel by `MenusPage`.

#### Pages

##### 81. `src/pages/AppLayout.tsx`

*   **Proposed Changes**: uses `useMe` and `useAuth`; renders `Sidebar`, `Header` and
    `<Outlet/>`.

##### 82. `src/pages/StartupErrorScreen.tsx`

*   **Proposed Changes**: props `{ title: string; lines: string[] }`; a static page with no
    hooks from `hooks/`, rendered by `bootstrap` before the router exists.

##### 83. `src/pages/UsersListPage.tsx`

*   **Proposed Changes**: URL search parameters, `usePagingParams` (with `resetPage` on every
    filter change and `clampPageToTotal`), debounced text filters, `useUserList`,
    `useAllRoles`, the status and set-password mutations with `ConfirmDialog`, and the
    row-message area.

##### 84. `src/pages/UserCreatePage.tsx`

*   **Proposed Changes**: `useAllRoles`, `useCreateUser`; builds `CreateUserRequest` field by
    field (leaves out `facilityScope` when empty); on 201 navigates to `/users/{id}` with state
    `{ created: true, email, warnings }`; on 502 shows `SyncFailedNotice`, keeping the form
    usable.

##### 85. `src/pages/UserEditPage.tsx`

*   **Proposed Changes**: `useUser` (with `describeError(..., { detailFetch: true })`),
    `useAllRoles`, the four mutations; shows the created message from navigation state;
    `NotFoundPanel` on `notFound`.

##### 86. `src/pages/RolesListPage.tsx`

*   **Proposed Changes**: `usePagingParams`, `useRoleList`, `Pagination`, `useCreateRole`,
    `useDeleteRole`; after create, navigation as in the Roles list section, passing
    `{ createdWithSyncWarning: true }` in navigation state when the 201 carries
    `ROLE_SYNC_FAILED`.

##### 87. `src/pages/RoleDetailPage.tsx`

*   **Proposed Changes**: `useRole` (with `{ detailFetch: true }`), `useUpdateRole`; the header;
    `NotFoundPanel` on `notFound`; tabs from the `tab` search parameter (`menus` default,
    `permissions`); renders `RoleMenusTab` or `RolePermissionsTab`. No delete.

##### 88. `src/pages/role-detail/RoleMenusTab.tsx`

*   **Proposed Changes**: `useMenuTree`, `useRoleMenus`, `useReplaceRoleMenus`; selection state;
    client warnings from `detailsWithoutParent`; server warnings after save;
    `useUnsavedChangesGuard`.

##### 89. `src/pages/role-detail/RolePermissionsTab.tsx`

*   **Proposed Changes**: `useRolePermissions`, `useReplaceRolePermissions`, `useSyncRole`;
    `SyncStatusPanel`, `PermissionsEditor`, `SyncFailedNotice`; the created-with-sync-warning
    notice from navigation state; form reset rules after a 502; `useUnsavedChangesGuard`.

##### 90. `src/pages/MenusPage.tsx`

*   **Proposed Changes**: `useMenuTree`, `useCreateMenu`, `useUpdateMenu`,
    `useToggleMenuActive`; the inline `MenuForm` panel for add and edit; `ConfirmDialog` for
    deactivate and activate only.

##### 91. `src/pages/NotFoundPage.tsx`

*   **Proposed Changes**: "Page not found" with a link to `/users`.

#### Test support

##### 92. `src/test/setup.ts`

*   **Proposed Changes**: imports `@testing-library/jest-dom/vitest`;
    `vi.mock('../auth/keycloak', () => import('./fakeAuth'))`; starts the MSW server with
    `onUnhandledRequest: 'error'` before all tests; after each test resets handlers, clears the
    request recorder and calls `resetFakeAuth()`; closes the server after all.

##### 93. `src/test/fakeAuth.ts`

*   **Proposed Changes**: named exports `initAuth`, `getToken`, `refreshToken`, `login`,
    `logout` as `vi.fn()`s with the default behaviour of the Technical Plan; the `satisfies`
    statement (UI-AD-01); `resetFakeAuth()`, which resets every spy and reinstalls the defaults.

##### 94. `src/test/render.tsx`

*   **Proposed Changes**: `renderRoute(path: string)`: a fresh `createQueryClient({ retry: false
    })`, `createMemoryRouter(routes, { initialEntries: [path] })`, and `userEvent.setup()`;
    returns the render result, the user-event instance and the router. `createWrapper()`:
    a provider wrapper with a fresh query client, for `renderHook`.

##### 95. `src/test/mocks/fixtures.ts`, `handlers.ts`, `requests.ts`, `server.ts`

*   **Proposed Changes**:
    *   `fixtures.ts`: typed canned data (UI-AD-19), with fixed UUIDs.
    *   `requests.ts`: a recorder: `recordRequest(req)`, `recordedRequests()`, `clearRequests()`.
    *   `handlers.ts`: one `http.*` handler per endpoint the console calls, under
        `*/admin/v1/...`, typed with the contract's request and response types; each records the
        request and returns the fixture. Helpers `okResponse(data, extra?)` and
        `errorResponse(status, code, message, extra?)` build typed envelopes for overrides.
    *   `server.ts`: `setupServer(...handlers)`.

##### 96. Lint fixtures (`__lint_fixtures__` folders)

*   **Proposed Changes**: one file per import rule, in the folder of the group it tests, each
    using its import so `tsc` reports no unused import:
    *   `src/components/__lint_fixtures__/importsHook.tsx` (imports `../../hooks/useMe`),
    *   `src/components/__lint_fixtures__/importsQuery.tsx` (`@tanstack/react-query`),
    *   `src/pages/__lint_fixtures__/importsApi.tsx` (the value `getMe` from `../../api/me`),
    *   `src/pages/__lint_fixtures__/importsContractType.tsx` (`import type` from
        `../../api/contract`; must pass),
    *   `src/utils/__lint_fixtures__/importsKeycloak.ts` (`keycloak-js`),
    *   `src/utils/__lint_fixtures__/importsMsw.ts` (`msw`).

    They are globally ignored by `npm run lint` and excluded from coverage, and are checked by
    `src/test/__tests__/lintRules.test.ts`.

#### Unit and component tests

Each screen's test file covers the happy path and at least one error code for each endpoint it
calls (PRD §11).

| File | Cases |
|---|---|
| `src/__tests__/config.test.ts` | defaults applied; empty realm reported missing; trimming; trailing `/` removed from the API base URL; only the four variables read |
| `src/__tests__/bootstrap.test.tsx` | nothing renders while `initAuth` is pending; start-up screen on rejection with the sign-in text; realm missing: screen names `VITE_KEYCLOAK_REALM` and `initAuth` is never called |
| `src/auth/__tests__/keycloak.test.ts` | `init` options; no session → reject; the 60-second interval calls `updateToken(70)`; a failed interval refresh does not call `login`; `refreshToken` true; `refreshToken` false calls `login` |
| `src/api/__tests__/client.test.ts` | URL and query building; headers; envelope unwrap with `pagination` and `warnings`; 204; error envelope with `details` and `partialState`; non-envelope body → `CLIENT_UNEXPECTED_RESPONSE` with the status; `CLIENT_NETWORK_ERROR`; no token → `login` and 401; 401 then 200; 401 twice → 401 thrown, `login` not called by the client; `refreshToken` false → 401 thrown |
| `src/utils/__tests__/describeError.test.ts` | every row of the kinds table, including a non-envelope 403 → `forbidden` and a non-envelope 500 → `unexpected`; the `id` detail → `notFound` only with `{ detailFetch: true }` |
| `src/utils/__tests__/formErrors.test.ts` | path conversion; field and top-level split; conflict codes to fields; role messages from `roleIds[i]` |
| `src/utils/__tests__/menuTree.test.ts` | flatten; `nextSortOrder`; `detailsWithoutParent` with and without the parent |
| `src/utils/__tests__/permissions.test.ts` | normalization; set comparison ignores order and method case; empty optional fields dropped |
| `src/schemas/__tests__/schemas.test.ts` | one pass and one fail case for each rule in `rules.ts` and each console-only rule |
| `src/hooks/__tests__/useRoles.test.tsx` | `useAllRoles`: three pages joined in order; repeated ids dropped; stops at five pages and reports `truncated` |
| `src/test/__tests__/lintRules.test.ts` | runs ESLint's Node API with `ignore: false` on the six lint fixtures (`// @vitest-environment node`); five report `@typescript-eslint/no-restricted-imports`, the contract type import reports nothing |
| `src/components/shared/__tests__/SyncFailedNotice.test.tsx` | each table row's text; the empty-steps rule; the `ROLE_SYNC` rule; operator ids shown when present; no user link for `USER_CREATE` |
| `src/pages/__tests__/AppLayout.test.tsx` | header shows username and roles; `/me` 403 shows the message and the sidebar still shows three links; Sign out calls `logout` |
| `src/pages/__tests__/UsersListPage.test.tsx` | filters and paging to recorded query parameters; a filter change sets `page` to 1; debounce; invalid paging corrected; past-last-page correction; status actions with confirm; set-password email shows the address; 502 `USER_PASSWORD_RESET` empty-steps text; `LAST_SYSTEM_ADMIN` inline; 403 with and without the envelope inline |
| `src/pages/__tests__/UserCreatePage.test.tsx` | client validation; one `POST` with the exact body; navigation and created message; `ACTION_EMAIL_NOT_SENT`; `USERNAME_IN_USE` and `EMAIL_IN_USE` on fields; `ROLE_NOT_SYNCED` and `ROLE_NOT_ASSIGNABLE` name the role; 502 with and without completed steps, form still usable; a mutation network error shows the "result is unknown" text |
| `src/pages/__tests__/UserEditPage.test.tsx` | profile `PUT` with `facilityScope: null` when empty; `EMAIL_IN_USE` on update; roles Save disabled until changed, sends the full set; status change; set-password email; `LAST_SYSTEM_ADMIN`; 404 and malformed id show `NotFoundPanel` |
| `src/pages/__tests__/RolesListPage.test.tsx` | paging; name rule; warning and confirm; `POST` only after Confirm; navigation to the new role; 201 with `ROLE_SYNC_FAILED` opens the Permissions tab with the notice; `DUPLICATE_ROLE_NAME` on the field; Delete hidden for system roles and disabled when assigned; `ROLE_IN_USE` inline; 403 inline |
| `src/pages/__tests__/RoleDetailPage.test.tsx` | header and tabs; `PUT /roles/:id` success; `SYSTEM_ROLE_IMMUTABLE` inline; form disabled for a system role; `ROLE_NOT_FOUND` shows `NotFoundPanel` |
| `src/pages/__tests__/RoleMenusTab.test.tsx` | tree and current selection with path and type; Save disabled until changed; full-set body; client warning; server warning with labels; 403 on save inline; system role read-only; unsaved-changes guard on tab switch |
| `src/pages/__tests__/RolePermissionsTab.test.tsx` | implicit rows read-only and not sent; add and remove rows; duplicate and implicit-equal rows rejected; Save disabled until changed; 200 shows "Saved." and "Synced"; 502 with steps refetches and shows "Sync failed"; 502 without steps keeps edits; Re-sync 200 and 502; system role read-only with Re-sync enabled |
| `src/pages/__tests__/MenusPage.test.tsx` | tree with indentation and icons; unknown icon fallback; add `NAV` with default sort order; add `DETAIL` under a `NAV`; `DUPLICATE_MENU_KEY` on key; edit without key and type in the body; `VALIDATION_ERROR` on `PUT` mapped to its field; deactivate and activate read the tree again and send the full body with `isActive` flipped |

#### Browser tests

`e2e/.auth/` is created at run time by item 99 and is git-ignored (item 14).

##### 97. `e2e/env.ts`

*   **Proposed Changes**: `adminCredentials()`: reads `CCE_TEST_ADMIN_USERNAME` and
    `CCE_TEST_ADMIN_PASSWORD` from `process.env` and throws a message naming both if either is
    missing. Exports the fixed URLs: console `http://localhost:3002`, service
    `http://localhost:8085/admin/v1`, Keycloak token endpoint
    `http://localhost:8180/auth/realms/cce/protocol/openid-connect/token`, stand-in
    `http://localhost:8090/_stub/mode`.

##### 98. `e2e/global-setup.ts`

*   **Proposed Changes**: checks the service health and the stand-in mode endpoint; sets the
    stand-in to `ok`; fails with the admin-service §9.3 start commands if either check fails.

##### 99. `e2e/auth.setup.ts`

*   **Proposed Changes**: opens `/`, waits for the Keycloak login page at `localhost:8180`,
    fills `#username` and `#password`, clicks `#kc-login`, waits for `/users`, checks the header
    shows the username, saves the storage state to `e2e/.auth/admin.json`.

##### 100. `e2e/fixtures.ts`

*   **Proposed Changes**:
    *   Fixture `api`: gets a token with `POST` to the token endpoint, form fields
        `grant_type=password`, `client_id=cce-e2e`, `username`, `password`; offers
        `createRole(stem)`, `setRolePermissions(id, rows)`, `createUser(stem, roleIds)`,
        `getUser(id)`.
    *   Fixture `suffix`: digits only, `Date.now()` followed by three random digits.
    *   Names: roles `E2E_{STEM}_{suffix}` with `STEM` in capitals; usernames `e2e-{stem}-{suffix}`;
        emails `e2e-{stem}-{suffix}@cce.local`.
    *   Plain export `setStubMode(mode: 'ok' | 'fail')`: `PUT` to the stand-in URL with
        `Content-Type: text/plain` and the mode as the body.
    *   Helper `expectHeaderUser(page)`: checks the test administrator's username in the header.

##### 101. `e2e/cuj2-onboard-user.spec.ts`

*   **Proposed Changes**: creates a role through the API; in the console fills `/users/new`,
    picks the role, submits; expects `/users/{id}` and "User created. A set-password email was
    sent to {email}."; searches the Users list for the username and sees the row with the
    role; checks the header.

##### 102. `e2e/cuj3-configure-role.spec.ts`

*   **Proposed Changes**: creates a role in the console with the name warning and Confirm; on
    the Menus tab grants Compliance and Patient detail (whose parent Patients is not granted),
    sees the client warning, saves, sees the server warning naming Patient detail and Patients;
    on the Permissions tab sees the two implicit rows, adds `GET /v1/insights/**`, saves, sees
    "Saved." and "Synced"; checks the header.

##### 103. `e2e/cuj4-change-roles.spec.ts`

*   **Proposed Changes**: creates roles A and B and a user holding A through the API; in the
    console unchecks A and checks B, saves; captures the request and checks its body is
    `{ "roleIds": [B] }`; checks the page and the API both show only B; checks the header.

##### 104. `e2e/cuj5-deactivate-user.spec.ts`

*   **Proposed Changes**: creates a role and a user holding it through the API; on the Users
    list searches with `q` for the username; deactivates (confirm), sees "Inactive" and the role
    kept; locks, sees "Locked"; activates, sees "Active"; checks the header.

##### 105. `e2e/cuj6-sync-failure.spec.ts`

*   **Proposed Changes**:
    1.  Creates role A and a user holding A through the API while the stand-in is `ok`.
    2.  Sets the stand-in to `fail`.
    3.  Creates role B in the console and sees "The role was created but not synced. Use
        Re-sync before assigning it." on its Permissions tab, with "Sync failed".
    4.  Adds a rule and saves; sees the `ROLE_PERMISSIONS_SAVE` text, the refetched row and
        "Sync failed".
    5.  Opens the user, adds B, saves roles; sees "Not synced yet: … (B's name)".
    6.  Sets the stand-in to `ok`; presses Re-sync on B; sees "Synced".
    7.  Saves the user's roles again and sees success; checks the header.

    A `finally` block sets the stand-in to `ok`.

#### Files outside `cce-admin-ui/`

None are changed by the implementation.

### Verification Plan

*   **Compilation/Build**: in `cce-admin-ui/`, on Node 22.22: `npm ci`, `cp .env.example .env`,
    `npm run build`. Then:
    *   `docker build --target verify --build-arg VITE_KEYCLOAK_REALM=cce .` (Node 20: lint,
        tests, and no `mockServiceWorker` or `setupServer` in `dist/`);
    *   `docker build --build-arg VITE_KEYCLOAK_REALM=cce --build-arg
        VITE_KEYCLOAK_URL=http://localhost:8180/auth -t cce-admin-ui:local .`;
    *   `docker build .` without the realm fails with `VITE_KEYCLOAK_REALM is required for a
        build (PRD §8.3)`.
*   **Unit Tests**: `npm run test:ci` on Node 22.22 (and in the `verify` stage on Node 20).
*   **Static Analysis & Linting**: `npm run lint` (ESLint with the four import rules, and
    Prettier); `lintRules.test.ts` proves each rule fires.
*   **Test Coverage Requirement**: the thresholds in `vitest.config.ts` (80% lines, statements
    and functions, 70% branches), enforced by `test:ci`.
*   **E2E/Integration**:
    *   Start the admin-service stack and service (admin-service §9.3), run
        `npx playwright install chromium` once, export the two credential variables, then
        `npm run e2e`; all five CUJ specs pass.
    *   `docker run --rm cce-admin-ui:local` without `GATEWAY_UPSTREAM` exits with a non-zero
        status and prints "GATEWAY_UPSTREAM is required".
    *   No gateway runs locally (C-05), so this check points `GATEWAY_UPSTREAM` at the service
        itself: `docker run --rm -d -p 3002:3002
        -e GATEWAY_UPSTREAM=http://host.docker.internal:8085
        --add-host=host.docker.internal:host-gateway cce-admin-ui:local`. Then
        `curl -s -o /dev/null -w '%{http_code}' http://localhost:3002/users` prints `200` and
        `curl -s http://localhost:3002/users` contains `<div id="root"></div>`;
        `curl -s http://localhost:3002/admin/v1/health` contains `"status":"UP"`; and
        `curl -sI http://localhost:3002/` shows the `Content-Security-Policy` header.

--------------------------------------------------------------------------------

*Drafted with EGM*
