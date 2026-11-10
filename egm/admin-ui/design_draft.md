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
Hook Form 7 with Zod 3, and Vitest with Testing Library and MSW (PRD §6). Where this console
departs from that template, the departure comes from the PRD and is listed under
[Conflicts between inputs](#conflicts-between-inputs).

The console uses the API contract. It does not define it. Under
[D-01](../../docs/designs/author-decisions.md), the
[admin-service design](../admin-service/design_draft.md) owns every request and response shape,
error code, pagination block and warning. Where the console needs something the PRD does not
settle, this design lists it as a numbered requirement on the admin-service design
([Requirements on the admin-service design](#requirements-on-the-admin-service-design)). The
conventions that design has already written (envelope, warnings, error body and codes,
pagination, timestamps, identifiers, concurrent edits: its §4.1 to §4.3) are used as written.
Every behaviour that depends on a part not yet written there is marked
*(pending admin-service contract)* and names the AS-REQ item it waits for.

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
    who does not see that a sync failed will assign a role that Keycloak does not have yet.
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
    their PRD acceptance criteria. The exit checks for milestones F1 to F5 can be shown against
    MSW mocks, and later against the real service.
2.  **An administrator can carry out scenarios S2 to S6 from the console alone.** Onboarding a
    user, creating and configuring a role, changing a user's roles, deactivating a user, and
    recovering from a failed gateway sync need neither SQL nor the Keycloak admin console.
3.  **No partial failure is hidden.** A `KEYCLOAK_SYNC_FAILED` response (HTTP 502) always shows
    one dedicated component. It says what was saved, what was not, and what to do next. It is
    never a generic toast (F-05). The same component is used on the user-create form and on the
    Permissions tab.
4.  **The screen only shows what the server confirmed.** There are no optimistic updates. Every
    write button shows a pending state until the server answers, and the affected queries are
    refetched afterwards (F-06).
5.  **An authorization failure stays on the screen that caused it.** A `FORBIDDEN` (403) is
    shown inline where the call was made. It does not replace the page with a global error
    page (F-04).
6.  **The console and its mocks cannot drift apart from the contract without a type error.**
    All request and response shapes live in one TypeScript module that mirrors the
    admin-service contract section. The API client and every MSW handler are typed from that
    module, so a change to a shape breaks `npm run build` until both are updated.
7.  **Signing in cannot be bypassed.** No build of the console renders a page without a
    Keycloak session. There is no runtime flag and no static-token fallback (PRD §6, §9, F-01).
8.  **It builds and passes its tests in both places it runs.** `npm run build`, `npm run lint`
    and `npm run test:ci` pass on Node 22.22 (this machine) and inside the reference Docker
    build on `node:20-alpine` (PRD §6, §11).
9.  **Scenarios S2 to S6 are covered by automated browser tests against the real service.**
    CUJ 2 to CUJ 6 run as Playwright tests against the real local stack: the admin-service
    design's M0 (Keycloak in Docker, Postgres) plus the service on `localhost:8085`, with the
    console on the Vite dev server at `localhost:3002`. Each test signs in through the real
    Keycloak login page. The tests run with `npm run e2e`. They are not part of `test:ci`,
    because they need the stack running. This is how the PRD's E2E milestone (§10) is shown,
    and it gives the scenarios regression coverage that a manual run does not.

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
*   **The local environment.** The Keycloak realm and clients, Postgres, ports and the test admin
    user are defined by the admin-service design's milestone M0 (D-03). This design only states
    which of those values the console reads.
*   **Detecting concurrent edits.** The PRD defines no version or ETag on any resource. If two
    administrators edit the same role, the last save wins. This is confirmed as a requirement
    on the admin-service design (AS-REQ-21), not handled in the console.
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
| C-01 | `cce-insights-ui` has a build-time `VITE_AUTH_ENABLED` flag and a `VITE_AUTH_TOKEN` static-token fallback ([developer setup §3](../../context/cce-insights-ui/docs/developer-setup.md)). The PRD says the admin console has "no auth-bypass flag" and "never bypasses auth" (§6, §9, F-01). | The PRD applies. The console has neither the flag nor the fallback. How tests get past sign-in is decided in the Technical Plan. |
| C-02 | The template defaults the realm to `cce`. PRD §8.3 marks `VITE_KEYCLOAK_REALM` as required with no default. | The PRD applies. A missing value stops start-up with a visible error. There is no fallback. The local value comes from the admin-service design's M0. |
| C-03 | The PRD's Docker build uses `node:20-alpine` (§6). The template's CI uses Node 24 ([deployment guide](../../context/cce-insights-ui/docs/deployment-guide.md)). This machine has Node 22.22 and npm 10.9. | The reference build is the PRD's Docker image on `node:20-alpine`. The code must also build and pass its tests on Node 22.22. Node 24 is not a target. |
| C-04 | The template names its one-shot test script `test:run`. PRD §8.3 names it `test:ci`. | The PRD names apply: `dev`, `build`, `test`, `test:ci`, `lint`. |
| C-05 | PRD §5 and §9 say the console reaches the service only through `gateway-service`. D-03 has the local Vite proxy forward `/admin/v1/*` straight to `localhost:8085`. | Both hold, in different environments. Locally there is no gateway, so a 403 can only come from the service itself (for example, a caller with no local user or a user who is not `ACTIVE`, B-12). A 403 from the gateway is covered by MSW tests. |
| C-06 | PRD §2 says deactivation (`INACTIVE`) "is terminal". Scenario S5, B-34 ("re-activating restores access") and F-10 (row action "activate/deactivate") say an inactive user can be activated again. Appendix A also lists a third status, `LOCKED`, which no screen requirement mentions. | Status rules belong to the service. Raised as AS-REQ-07. The console offers only the transitions that requirement allows. |
| C-07 | PRD §1 calls the team's design documents the source of truth. In this trial those documents are not inputs, and the approved designs in `docs/designs/` take their place ([PROVENANCE](../../context/PROVENANCE.md)). | This design and the admin-service design replace the team documents for the trial. Where the PRD says "per the API reference", the admin-service design is meant. |
| C-08 | The gateway's sample permission rows use URI patterns such as `/admin/users/*` ([gateway migration doc](../../context/gateway-service/DATABASE_PERMISSIONS_MIGRATION.md)). The admin API's base path is `/admin/v1` (PRD Appendix A), and PRD §12 writes `/admin/v1/**`. A pattern copied from the samples would not match any admin API path. | The console's placeholder text and test data use `/admin/v1/...`. Whether the service should reject or warn on patterns outside known prefixes is raised in AS-REQ-17. |
| C-09 | PRD §6 lists the console's testing stack as Vitest, Testing Library and MSW, and PRD §10 describes the E2E milestone as running the scenarios against the real service, without saying how. | The author adds Playwright as a dev-only dependency with its own script, `npm run e2e`, for CUJ 2 to CUJ 6 (Goal 9). A manual run gives no regression coverage. S1 stays out of this console's tests. |

## Open Questions

### Requirements on the admin-service design

These are things the console needs that the PRD does not settle. Each one is a requirement on
the [admin-service design](../admin-service/design_draft.md), which answers it in its contract
section. The admin-service session receives this list. Until an item is answered, the console
behaviour that depends on it is marked *(pending admin-service contract)*.

**Status against the admin-service draft of 2026-11-10** (its Technical Plan §3 and §4.1–4.3
are written; §4.4 onwards and §9 are not). "Answered" means this design now uses the shape
written there. The shapes are not copied here: the linked section is the source.

| Item | Status | Where answered, or what it waits for |
|---|---|---|
| AS-REQ-01 | Answered, except the codes still to be added for OQ-4 and OQ-15 | [§4.1, §4.2](../admin-service/design_draft.md#41-conventions-b-03-b-04) |
| AS-REQ-02 | Answered | [§4.3](../admin-service/design_draft.md#43-pagination-b-04-as-req-02-as-req-03) |
| AS-REQ-03 | Answered | [§4.3](../admin-service/design_draft.md#43-pagination-b-04-as-req-02-as-req-03) |
| AS-REQ-04 | Partly: timestamp format (§4.1). Field names and last login wait for §4.4+ and OQ-8 | §4.4+, OQ-8 |
| AS-REQ-05 | Partly: username is separate from email, `USERNAME_IN_USE` exists (§4.2). Fields and the first password wait for §4.5 and OQ-13 | §4.5, OQ-13 |
| AS-REQ-06 | Answered: `partialState` (§4.2). The step names wait for §5 and are shown as sent | [§4.2](../admin-service/design_draft.md#42-error-codes), §5 |
| AS-REQ-07 | Open | OQ-4 |
| AS-REQ-08 | Open | OQ-14 |
| AS-REQ-09 | Open | §4.5 |
| AS-REQ-10 | Open | OQ-15 |
| AS-REQ-11 | Open | §4.4+ |
| AS-REQ-12 | Open | OQ-12 |
| AS-REQ-13 | Open | §4.4+ |
| AS-REQ-14 | Partly: delete returns 204, and a menu with children cannot be deleted (`MENU_HAS_CHILDREN`). Which call deactivates, and the fields, wait for §4.4+ | §4.4+ |
| AS-REQ-15 | Open | §4.6 |
| AS-REQ-16 | Partly: the warning shape and where it sits (§4.1). The request body of `PUT /roles/:id/menus` and the shape of `GET /roles/:id/menus` wait for §4.6 | [§4.1](../admin-service/design_draft.md#41-conventions-b-03-b-04), §4.6 |
| AS-REQ-17 | Open; the pattern syntax also depends on the gateway owners (GW-3) | §4.4+, GW-3 |
| AS-REQ-18 | Answered: a sync failure after the save is a 502 with `partialState`, and the error body carries no rows, so the console refetches | [§4.2](../admin-service/design_draft.md#42-error-codes) |
| AS-REQ-19 | Partly: `DELETE` returns 204 with no body (§4.1). The other three wait for §4.4+ | §4.4+ |
| AS-REQ-20 | Open | §4.4+ |
| AS-REQ-21 | Answered | [§4.1](../admin-service/design_draft.md#41-conventions-b-03-b-04) |
| AS-REQ-22 | Partly: Keycloak is at `http://localhost:8180/auth`, realm `cce`, not proxied by Vite; the service checks audience `gateway-service` (§3). Whether `cce-admin-ui` tokens carry that audience, and the test administrator, wait for §9 and OQ-7 | [§3](../admin-service/design_draft.md#3-request-pipeline-and-security-b-05-b-10-to-b-13), §9, OQ-7 |
| AS-REQ-23 | Open | OQ-5 |
| AS-REQ-24 | Open | OQ-7, §9 |

**Envelope, errors and paging**

*   **AS-REQ-01: Validation error details.** B-03 defines the error body as
    `{error:{code,message}}`. The console needs to know whether `VALIDATION_ERROR` also carries
    per-field details, and if so their shape and whether the field names match the request body
    property names. Without per-field details, the console shows the server message at the top
    of the form. The full list of error code strings is also needed, including the three
    not-found codes (`USER_NOT_FOUND`, `ROLE_NOT_FOUND`, `MENU_NOT_FOUND`).
*   **AS-REQ-02: Pagination block.** B-04 says list endpoints return a `pagination` block "with
    total". The console needs the field names, whether `page` starts at 0 or 1, and what happens
    when `page` is past the last page.
*   **AS-REQ-03: Which lists are paginated.** The role pickers on the user form and the role
    filter on the Users list need every role. The console needs to know whether `GET /roles`,
    `GET /roles/:id/permissions` and `GET /roles/:id/menus` are paginated (B-04 says "list
    endpoints", maximum 100 per page). If `GET /roles` is paginated, it should state how a client
    gets every role.
*   **AS-REQ-21: Concurrent edits.** Confirm that no resource carries a version or ETag and that
    the last write wins, so the console needs no conflict handling.

**Users**

*   **AS-REQ-04: User resource.** Field names and types for the list item and the detail view.
    F-10 needs username, email, status, roles, facility scope and last login. The edit form
    needs first name and last name (B-33). Roles need both an id (for `PUT /users/:id/roles`) and
    a display name. The console needs to know whether last login is nullable and its format.
    PRD §12 says the mechanism that updates `last_login_at` is not specified.
*   **AS-REQ-05: Create-user request.** Which fields are required. In particular: is `username`
    separate from `email`? How are the initial roles sent? B-36 says no password ever passes
    through the service, so the console needs to know how a new user gets a password (for
    example, a Keycloak required action set on create). The console's success message has to
    tell the admin what happens next.
*   **AS-REQ-06: What a 502 on user create contains.** B-32 records `keycloak_id` in `audit_log`
    on partial failure. The console needs to know whether the 502 body identifies which step
    failed or what now exists. Without that, the console shows the server message and a fixed
    next-step text (contact an operator to reconcile from `audit_log`, PRD §12).
*   **AS-REQ-07: Status transitions** (conflict C-06). Which values `PATCH /users/:id/status`
    accepts, which transitions are allowed (in particular `INACTIVE` to `ACTIVE`), what `LOCKED`
    means in Keycloak and whether an admin may set it, and the error code for a transition that
    is not allowed.
*   **AS-REQ-08: Facility scope.** The type and format of `facilityScope` (one string or a
    list), how the `facilityScope` filter on `GET /users` matches (exact or prefix), and whether
    any endpoint lists valid values. Without such an endpoint, the console uses a free-text
    input for both the filter and the form field.
*   **AS-REQ-09: Search.** Which fields `q` matches (B-30 says "name or email"; does name include
    username?) and whether there is a minimum length.
*   **AS-REQ-10: Assigning a role that has not been synced.** PRD §9 requires a role's realm role
    to exist in Keycloak before it is assigned. The console needs to know what `POST /users` and
    `PUT /users/:id/roles` return when it does not. It also needs to know whether the role
    resource carries the outcome of its last gateway sync. Without such a field, the
    "sync failed, retry" state on the Permissions tab is lost when the page is reloaded, and the
    user form cannot warn before assigning an unsynced role.

**Roles**

*   **AS-REQ-11: Role resource and name rules.** Field names (id, name, display name,
    description, system flag). The exact rule for `name`: the UPPER_SNAKE_CASE pattern and a
    maximum length no greater than 100, because the name is stored as
    `api_permissions.permission_name VARCHAR(100)`. Maximum lengths for display name and
    description. The console's Zod schema mirrors these.
*   **AS-REQ-12: What `SYSTEM_ROLE_IMMUTABLE` blocks.** Appendix B says "modify or delete of an
    `is_system` role". The console needs to know whether that covers display name and
    description edits, the menu set and the permission set of a system role, so it can disable
    exactly those controls.
*   **AS-REQ-16: Menu set request and warning.** The request body of `PUT /roles/:id/menus`
    (for example, a list of menu ids). The shape of the warning returned when a `DETAIL` menu is
    granted without its parent `NAV` (B-41): where it sits in the success envelope (B-03 only
    names `data` and `pagination`), and how it identifies the menus concerned. Also: does
    `GET /roles/:id/menus` return ids or menu objects?
*   **AS-REQ-17: Permission rows.** Field names for method, URI pattern, description and
    category (F-14). The allowed method values (the gateway column is `VARCHAR(10)`; is a
    wildcard allowed?). The URI pattern syntax the gateway accepts (`*`, `**`). Maximum lengths
    (255 for the pattern and 50 for the category, from the gateway table). Whether category is
    free text or a fixed list. The error code for two rows with the same method and pattern in
    one role, which the gateway table's unique constraint forbids. Whether patterns outside
    `/admin/v1/` and other known prefixes are rejected or warned (conflict C-08).
*   **AS-REQ-18: Permission save outcomes.** Confirm that `PUT /roles/:id/permissions` returns
    success only when both the save and the gateway sync succeeded, and returns 502
    `KEYCLOAK_SYNC_FAILED` when the rows were saved but the sync failed (B-42, B-43, milestone B4
    notes). Say whether the 502 body includes the saved rows or the console must refetch them.
*   **AS-REQ-19: Action endpoints.** Success status and body for `POST /roles/:id/sync`,
    `POST /users/:id/reset-password`, `PATCH /users/:id/status` and `DELETE /roles/:id`.

**Menus**

*   **AS-REQ-13: Menu resource.** Field names (id, key, label, path, icon, type, parent id, sort
    order, active flag, children). The format of `icon` and how a client turns it into an icon
    (for example, a Heroicons component name). Whether `GET /menus` includes inactive entries.
    Whether a `DETAIL` menu may only sit under a `NAV` menu, and the maximum tree depth.
*   **AS-REQ-14: Deactivating a menu** (F-15 says "deactivate"; Appendix A has
    `DELETE /menus/:id`, which also removes `role_menus` rows). Which call deactivates a menu,
    whether the console should offer delete at all, the create and update request fields, and
    which fields cannot change after creation (for example, `key`).
*   **AS-REQ-15: Inactive menus in a role's menu set.** Whether a role can be granted an inactive
    menu, and whether `GET /roles/:id/menus` returns inactive menus that were granted before they
    were deactivated.

**Current user and local environment**

*   **AS-REQ-20: `GET /me` fields.** Field names for id, username, roles and facility scope
    (B-20). Roles are names only.
*   **AS-REQ-22: Values from milestone M0.** The console needs these from M0: the Keycloak base
    URL as the browser reaches it on this machine, the realm name, a test admin user who has
    `SUPER_ADMIN` in Keycloak and an `ACTIVE` local row, and confirmation that a token issued
    to the public client `cce-admin-ui` carries the audience the service checks (B-10 validates
    `aud`). PRD §8.3 defaults `VITE_KEYCLOAK_URL` to `${origin}/auth`. On `localhost:3002` that
    path does not reach Keycloak unless the dev server proxies it or `.env` sets the URL. M0 has
    to say which. *Partly answered (§3):* Keycloak is reached directly at
    `http://localhost:8180/auth`, realm `cce`, and is not proxied, so the token's issuer
    (`http://localhost:8180/auth/realms/cce`) matches what the service checks. Locally the
    console therefore sets `VITE_KEYCLOAK_URL` in `.env`. The default `${origin}/auth` applies
    only where an ingress serves Keycloak on the console's origin.
*   **AS-REQ-23: A way to make the gateway sync fail locally.** CUJ 4 runs as a browser test
    against the real stack (Goal 9). The PRD's local setup puts the gateway sync behind an
    interface that is a no-op or a mock server locally (§10). The browser test needs a way to
    make that sync fail and then succeed again without restarting the service, for example a
    setting on the local mock server. M0 has to define the switch and how a test flips it.
*   **AS-REQ-24: Test data and credentials for browser tests.** Users cannot be hard-deleted
    and role names cannot be reused, so every `npm run e2e` run leaves users and roles behind.
    The browser tests make names unique per run. M0 has to say how to reset the local database
    and Keycloak realm to a clean state. The test admin's username and password must reach the
    tests through environment variables, never a committed file (PRD §11, B-13). M0 has to name
    those variables or say where their local values come from.

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

*   **Given**: The administrator is on `/users/new`, and at least one role exists.
*   **When**: They fill in the form, pick one or more roles and submit once.
*   **Then**: Invalid fields are flagged before anything is sent. On success the console shows
    the new user. On `EMAIL_IN_USE` the email field shows the error. On `KEYCLOAK_SYNC_FAILED`
    the sync-failure component explains that the account may exist in Keycloak without a local
    row, and what to do next. The submit button is disabled while the request is pending.

### CUJ 3: Create and configure a role (S3, F-12, F-13, F-14)

*   **Given**: The administrator is on `/roles`.
*   **When**: They create a role with the inline form, open it, select menus on the Menus tab
    and save, then add permission rows on the Permissions tab and save.
*   **Then**: The create form rejects names that are not UPPER_SNAKE_CASE and warns, before
    submit, that the name is permanent. Each tab's Save is disabled until the selection differs
    from what was last fetched, and it sends the complete set. Granting a `DETAIL` menu without
    its parent shows the warning. A successful permission save shows "synced".

### CUJ 4: Recover from a failed gateway sync (S6, F-14, F-05)

*   **Given**: A permission save returned 502 `KEYCLOAK_SYNC_FAILED`.
*   **When**: The administrator reads the message and presses Re-sync.
*   **Then**: The tab shows that the rows are saved but not synced, and that the role should not
    be assigned until a sync succeeds. Re-sync calls `POST /roles/:id/sync`. Success changes the
    state to "synced". Another failure keeps the retry state.

### CUJ 5: Change a user's roles (S4, F-11)

*   **Given**: The administrator is on `/users/:id`.
*   **When**: They change the role selection and save roles.
*   **Then**: The console sends the full new role set in one `PUT /users/:id/roles`. It does not
    send a list of changes. Saving the profile, saving roles, changing status and resetting the
    password are separate actions with their own buttons and pending states.

### CUJ 6: Deactivate a user (S5, F-10)

*   **Given**: An `ACTIVE` user is listed on `/users`.
*   **When**: The administrator chooses "Deactivate" from the row actions and confirms.
*   **Then**: The console sends `PATCH /users/:id/status` and, on success, refetches the list.
    The user shows as `INACTIVE` and keeps their roles. Re-activation is offered only if
    AS-REQ-07 allows it.

### CUJ 7: A call the caller is not allowed to make (F-04)

*   **Given**: The signed-in administrator's role lacks a permission, for example
    `DELETE /admin/v1/roles/*`.
*   **When**: They try that action.
*   **Then**: The action's area shows an inline "You do not have permission to do this" message
    with the server message. The rest of the page and the shell keep working.

### CUJ 8: Maintain the menus catalog (F-15)

*   **Given**: The administrator is on `/menus`.
*   **When**: They add a menu entry under a parent, then deactivate an existing one.
*   **Then**: The tree shows label, path, icon, type and active state. `DUPLICATE_MENU_KEY` shows
    on the key field. A deactivated entry stays visible and is marked inactive.

## Technical Plan (Architecture)

### Layers and the rules between them

The console follows the layer split of `cce-insights-ui`
([architecture §3](../../context/cce-insights-ui/docs/architecture-overview.md)) and the folder
layout in PRD §6 (`pages/ components/ hooks/ api/ auth/ schemas/ context/`). Each layer may
only call the layer below it.

```text
 main.tsx ── readConfig() ──fail──▶ ConfigErrorScreen (no auth, no API calls)
    │ ok
    ▼
 initAuth()  ── redirect to Keycloak until a session exists (PKCE S256)
    │ resolved
    ▼
 render: AuthProvider ▸ QueryClientProvider ▸ BrowserRouter ▸ AppLayout
                                                               │
 ┌─────────────────────────────────────────────────────────────┴──────────┐
 │ pages/        route components; compose hooks + components            │
 │   │  may import: hooks/, components/, schemas/, auth/ (useAuth)       │
 │   ▼                                                                   │
 │ components/   rendering only; data arrives as props; no hooks/ api/   │
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

The rules "pages never import `api/`" and "components never import `hooks/` or `api/`"
(PRD §10, F1 notes) are enforced by ESLint `no-restricted-imports` rules, so a breach fails
`npm run lint`. A third rule stops any file outside `src/test/` and `e2e/` from importing `msw`
or `@playwright/test`.

### Start-up: configuration, then sign-in, then render

1.  **Configuration check.** `src/config.ts` reads the four `VITE_*` values from PRD §8.3.
    `VITE_KEYCLOAK_REALM` must be non-empty (C-02). `VITE_KEYCLOAK_CLIENT_ID` defaults to
    `cce-admin-ui`. `VITE_KEYCLOAK_URL` defaults to `${window.location.origin}/auth`.
    `VITE_API_BASE_URL` defaults to an empty string. If the realm is missing, `main.tsx` renders
    a static configuration-error screen that names the missing variable. It does not call
    Keycloak or the API. The same check also runs in `vite.config.ts` for production builds,
    so `npm run build` and the Docker build fail early when the realm is empty, instead of
    producing an image that cannot start.
2.  **Sign-in.** `initAuth()` in `src/auth/keycloak.ts` creates the `keycloak-js` instance and
    calls `init` with `onLoad: 'login-required'`, `pkceMethod: 'S256'` and
    `checkLoginIframe: false`. Until a session exists, the browser is sent to the Keycloak login
    page, and nothing renders. After it resolves, a 60-second interval calls `updateToken(70)`
    (F-01). If a refresh fails because the session has ended, the console calls `login()` again,
    which sends the browser back to Keycloak.
3.  **Render.** `main.tsx` renders the providers and the router only after `initAuth()`
    resolves. There is no code path that renders a route without a session, and no flag or
    static token that skips `initAuth()` (C-01, Goal 7).

**Test seam for sign-in (decided at gate TP-1).** Unit and component tests cannot run a real
Keycloak redirect, and the console has no bypass flag. Tests replace the auth module instead:

*   `src/test/setup.ts` calls `vi.mock` on `src/auth/keycloak.ts` for every test file. The
    replacement is a fake in `src/test/fakeAuth.ts`. It is written with
    `satisfies typeof import('../auth/keycloak')`, so a change to the real module's exports
    breaks the test type-check (`tsc -b`, part of `npm run build`) instead of passing silently.
    The fake returns a fixed token and profile. Its `refreshToken`, `login` and `logout` are
    `vi.fn()` spies, so tests can assert on them, and a test can make `refreshToken` fail.
*   `src/auth/__tests__/keycloak.test.ts` is the only test that runs the real module. It calls
    `vi.unmock` on it, mocks the `keycloak-js` package and uses fake timers. It covers what
    component tests cannot: `initAuth()` calls `init` with `login-required` and PKCE `S256`
    and does not resolve without a session; the 60-second interval calls `updateToken(70)`;
    `refreshToken()` forces a refresh; and a failed refresh calls `login()`. This is the only
    file that depends on the `keycloak-js` API.
*   The other half of the 401 path, "repeat the request once, then call `login()`", lives in
    `client.ts`. It is tested in `src/api/__tests__/client.test.ts` against the fake: one 401
    then a 200 calls `refreshToken` once and returns the data; two 401s call `login`.
*   Rejected at the gate: passing an auth-client interface into the app, because the "no
    bypass" guarantee would then depend on a lint rule stopping a fake from being wired into
    `main.tsx`; and mocking `keycloak-js` in every test, because every component test would then
    depend on that library's API. See Alternatives.

**Sign-out** calls `keycloak.logout({ redirectUri: window.location.origin })` from the header.

### API client and the contract module

*   **`src/api/contract.ts`** is the only place where request and response shapes are written:
    the success envelope, the error body, the pagination block, the warning shape, every
    resource and request body, and the union of error code strings from PRD Appendix B. It
    mirrors the contract section of the
    [admin-service design](../admin-service/design_draft.md). The file begins with a comment
    that links that section and gives the date of the version it mirrors. The types from the
    admin-service draft's
    [§4.1 to §4.3](../admin-service/design_draft.md#4-api-contract) are written as defined
    there: the success envelope with optional `pagination` and `warnings`, the warning, the
    error envelope with optional `details` and `partialState`, the field error, the partial
    state, the pagination block, and the error codes of §4.2 (including the codes it marks as
    added). Ids are `string` (UUID) except permission row ids, which are `number`. Timestamps
    are `string` (ISO-8601 UTC). Until the admin-service design is approved, every type that
    depends on an open AS-REQ item carries a `PENDING AS-REQ-nn` comment, and the build plan
    treats those types as provisional.
*   **`src/api/client.ts`** exposes one request function. It builds the URL as
    `${VITE_API_BASE_URL}/admin/v1${path}` and leaves out query parameters that are undefined
    or empty strings. It sends `Authorization: Bearer <token>` and `Accept: application/json`,
    plus `Content-Type: application/json` when there is a body (F-02). It parses the envelope
    and returns it typed from `contract.ts`, so callers can read `data`, `pagination` and
    `warnings`. A 204 response (the two `DELETE` endpoints) returns no data. Request bodies
    contain only the properties the contract defines, because the service rejects any other
    property with 400 `VALIDATION_ERROR` (§4.1). The form layer therefore builds each body
    explicitly from named fields and never spreads form state into it.
*   **Errors.** Any non-2xx response becomes an `ApiError` with `status`, `code`, `message`,
    and `details` and `partialState` when the error body carries them. If the body is not a
    valid error envelope, `code` is `CLIENT_UNEXPECTED_RESPONSE`. If `fetch` itself fails (no
    connection), `status` is `0` and `code` is `CLIENT_NETWORK_ERROR`. These two codes are made
    by the console and never sent by the server. The `CLIENT_` prefix keeps them apart from the
    contract's codes.
*   **401 handling.** On a 401 the client calls `refreshToken()` (a forced `updateToken`) once
    and repeats the request once. This is safe for writes too, because the service and the
    gateway both reject an invalid token before doing anything (B-10). If the refresh fails or
    the repeated request also returns 401, the client calls `login()`.
*   **Resource modules** (`api/me.ts`, `users.ts`, `roles.ts`, `roleMenus.ts`,
    `rolePermissions.ts`, `menus.ts`) each export one typed function per endpoint in PRD
    Appendix A. They contain no React code.

### Server state: hooks, query keys and invalidation

*   **One hook per `api/` function** (PRD §10, F1 notes). Query keys are defined in one file,
    `src/hooks/queryKeys.ts`: `['me']`, `['users', 'list', filters]`, `['users', 'detail', id]`,
    `['roles', 'list', params]`, `['roles', 'all']`, `['roles', 'detail', id]`,
    `['roles', id, 'menus']`,
    `['roles', id, 'permissions']`, `['roles', id, 'syncState']` (console-only, see below),
    `['menus', 'tree']`.
*   **QueryClient defaults.** Queries: `staleTime` 30 seconds, `refetchOnWindowFocus` off (as in
    the template), and a retry only once and only for status 0 or 5xx, never for 4xx. Mutations
    are never retried, because a repeated write can repeat a Keycloak side effect.
*   **No optimistic updates (F-06).** A mutation hook does not touch the cache before the server
    answers. On success it invalidates the queries in the table below, and the screen shows
    only what the server returns on the refetch.

| Mutation | Invalidated on success |
|---|---|
| `POST /users`, `PUT /users/:id`, `PATCH /users/:id/status`, `PUT /users/:id/roles` | `['users']` (list and that user's detail) |
| `POST /users/:id/reset-password` | nothing (no visible data changes) |
| `POST /roles`, `PUT /roles/:id`, `DELETE /roles/:id` | `['roles']` (list and detail); `['users']` after delete, because role filters and role names on users change |
| `PUT /roles/:id/menus` | `['roles', id, 'menus']` |
| `PUT /roles/:id/permissions` | `['roles', id, 'permissions']`; sets `['roles', id, 'syncState']` |
| `POST /roles/:id/sync` | sets `['roles', id, 'syncState']` |
| `POST /menus`, `PUT /menus/:id`, `DELETE /menus/:id` | `['menus']` and every `['roles', *, 'menus']` (a menu change can change what a role's tree shows) |

`PUT /roles/:id/permissions` invalidates its query on a 502 as well as on success. The rows were
saved, and the 502 error body carries no rows, so the tab refetches them (AS-REQ-18). The same
applies to any mutation whose 502 has a non-empty `partialState.completedSteps`: the console
invalidates the same queries as on success, because something changed.

**Fetching every role.** `GET /roles` is paginated (admin-service §4.3). The role checkboxes on
the user forms and the role filter on the Users list need every role. A separate query,
`useAllRoles` (key `['roles', 'all']`), requests `GET /roles?pageSize=100&page=1`, then each
further page up to `totalPages`, and joins the results in order.

### Error presentation

One function, `describeError(error)` in `src/api/errors.ts`, sorts every error into a kind:
`forbidden`, `syncFailed`, `validation`, `conflict`, `notFound`, `unauthenticated`, `network`
or `unexpected`. Screens choose how to show an error by its kind, never by parsing messages.

*   **`forbidden` (F-04).** The `InlineError` component, rendered where the failing query's
    data or the failing button would be. It shows "You do not have permission to do this" and
    the server message. The shell, the sidebar and the rest of the page keep working. If
    `GET /me` itself returns 403 (no local user, or not `ACTIVE`, B-12), the header shows that
    message in place of the username, and each page shows its own 403 as its calls fail. There
    is no global error page.
*   **`syncFailed` (F-05).** The `SyncFailedNotice` component, used on the user-create form,
    the Permissions tab and the Re-sync action. It takes a `context` prop (`userCreate`,
    `permissionsSave` or `resync`) that selects a fixed explanation of what was saved, what was
    not and what to do next. It also shows the server message and the `partialState` fields as
    sent: the failed step, the completed steps (or "nothing was changed" when the list is empty),
    the Keycloak user id when present, and the audit log id when present, so that an operator
    can find the audit row (admin-service §4.2). Step names are shown as the server sends them,
    without translation. It is never a toast.
*   **`validation` and `conflict`.** `VALIDATION_ERROR` details are mapped to form fields by
    `field`. The JSON path form (`permissions[2].uriPattern`) is turned into React Hook Form's
    form (`permissions.2.uriPattern`). A detail whose field is not a form field (for example
    `body`, `id` or `pageSize`) is shown at the top of the form. Conflict codes map to fields:
    `EMAIL_IN_USE` to email, `USERNAME_IN_USE` to username, `DUPLICATE_ROLE_NAME` to name,
    `DUPLICATE_MENU_KEY` to key. `ROLE_IN_USE`, `SYSTEM_ROLE_IMMUTABLE` and `MENU_HAS_CHILDREN`
    are shown inline next to the action that caused them.
*   **`notFound`.** A detail page whose resource returns 404 (`USER_NOT_FOUND`,
    `ROLE_NOT_FOUND`, `MENU_NOT_FOUND`) shows a "not found" panel with a link back to its list.
    A detail page whose fetch returns 400 `VALIDATION_ERROR` with a detail for field `id` (the
    URL holds something that is not a UUID) shows the same panel.
*   **`network` and `unexpected`.** `InlineError` with a Retry button that refetches the query.
    `INTERNAL_ERROR`, `NOT_FOUND`, `METHOD_NOT_ALLOWED` and the two `CLIENT_` codes are in this
    kind. A route-level `NOT_FOUND` or `METHOD_NOT_ALLOWED` means the console and the contract
    disagree, so the message says so.

### Shell and routing

`BrowserRouter` with no base path; the console is served at `/` on port 3002. One layout route
renders the static sidebar (Users, Roles, Menus, F-03) and the header (username and role names
from `GET /me`, Sign out), with an `<Outlet/>` for the page. Pages are not lazy-loaded: there
are seven, and the bundle stays small.

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
Permissions tabs, unsaved changes are guarded with React Router's `useBlocker`. Leaving the
route or switching tab asks for confirmation. Switching tab changes the URL, so the same guard
covers it.

### Screens

**Users list (F-10).** Filters and paging live in the URL search parameters `status`, `roleId`,
`facilityScope`, `q`, `page` and `pageSize`, which map one-to-one to the `GET /users` query
parameters (B-30, B-04). The `q` and `facilityScope` text inputs wait 300 ms after the last
keystroke before updating the URL. Changing a filter resets `page` to 1. TanStack Table runs in
manual-pagination mode. Its page count is `totalPages` from the pagination block, and the
footer shows `totalItems` (admin-service §4.3). Pages in the URL are 1-based, like the API. The
page size choices are 20, 50 and 100. The default is 20, the service's default (B-04). A `page`
or `pageSize` in the URL that is not a whole number in range (page at least 1, page size one of
the three choices) is replaced by the default before any request, because the service rejects
out-of-range values with 400 instead of clamping them. If a response comes back with
`data: []` while `page` is greater than `totalPages` and `totalPages` is at least 1 (for
example, after a filter change on another tab), the console replaces `page` in the URL with
`totalPages`, which refetches. Columns: username, email, status badge, role names, facility scope, last login
(formatted with date-fns as `yyyy-MM-dd HH:mm` in local time, or "Never" when empty,
AS-REQ-04). Row actions: View/Edit (link), Deactivate or Activate (with a confirmation dialog;
which actions appear depends on AS-REQ-07), and Reset password (with a confirmation dialog).
The role filter options come from `useAllRoles`. The facility filter is a text input until
AS-REQ-08 says otherwise *(pending admin-service contract)*.

**User create (F-11).** React Hook Form with a Zod schema from `src/schemas/`. The fields
follow the create request; username and email are separate fields (AS-REQ-05, *pending
admin-service contract* for the full field list). Roles are a checkbox list of every role, from
`useAllRoles`. One submit sends one `POST /users`; there is no separate sync step. On 201 the
console goes to `/users/:id` and shows the next-step message about the password (AS-REQ-05,
*pending*). On 502 `KEYCLOAK_SYNC_FAILED` the form stays on screen with `SyncFailedNotice`
(`userCreate`), and what happens next depends on `partialState.completedSteps`:

*   **Empty:** nothing was changed (admin-service §4.2). The notice says so, and the admin may
    submit again.
*   **Not empty:** something was created. The submit button stays disabled for the rest of that
    page visit. The notice says not to submit again, because the Keycloak account may already
    exist, and that an operator reconciles it using the audit log id and Keycloak user id shown
    (PRD §9, §12). The notice links back to the Users list, which is refetched.

**User edit (F-11).** Four independent sections, each with its own mutation, pending state and
inline error: Profile (first name, last name, email, facility scope, `PUT /users/:id`; username
is shown read-only), Roles (checkbox list; Save is enabled only when the selection differs from
the last fetched set; sends the complete set with `PUT /users/:id/roles`), Status
(`PATCH /users/:id/status` behind a confirmation dialog) and Reset password
(`POST /users/:id/reset-password` behind a confirmation dialog).

**Roles list (F-12).** A table of name, display name, description and a lock icon for system
roles. `GET /roles` is paginated, so the table uses server-side pagination in the same way as the
Users list: `page` and `pageSize` in the URL, 1-based, default page size 20, ordered by name
by the service (admin-service §4.3). The inline create form above the table has name, display name and description. The name
field validates UPPER_SNAKE_CASE as the user types (AS-REQ-11). A fixed warning under it says
that the name is permanent and is also used as the Keycloak realm role and the gateway
permission name. Submitting opens a confirmation dialog that repeats the exact name and the
warning. The request is sent only after Confirm. Non-system rows have a Delete action behind a
confirmation dialog. System rows show the lock and have no Delete action.

**Role detail (F-13, F-14).** A header with the name (read-only, with a lock for system roles),
a display-name and description form (`PUT /roles/:id`), and Delete (not shown for system
roles). Which controls are disabled for a system role follows AS-REQ-12. Then the two tabs.

**Menus tab (F-13).** A checkbox tree built from `GET /menus`, with the current selection from
`GET /roles/:id/menus`. The selection is a set of menu ids. Checking a parent does not check its
children, and the reverse, because each menu is granted on its own. Save is enabled only when
the set differs from the last fetched set, and it sends the complete set. While editing, the
tab shows a warning listing every selected `DETAIL` menu whose parent `NAV` is not selected.
After a save, any warning the server returns is shown in the same place (AS-REQ-16). How
inactive menus appear follows AS-REQ-15.

**Permissions tab (F-14).** An editable table built with React Hook Form's field array and a
Zod schema. Each row has method (a select), URI pattern, description and category. Rows can be
added and removed. The schema rejects two rows with the same method and pattern, and applies the
length and syntax rules from AS-REQ-17. Save is enabled only when the rows differ from the last
fetched rows, and it sends the complete set. The tab shows one of three sync states:

```text
           PUT permissions 200 / POST sync 200
  unknown ───────────────────────────────────▶ synced ("Synced at HH:mm")
     │                                           │  ▲
     │ PUT permissions 502 / POST sync 502       │  │ Re-sync 200
     ▼                                           ▼  │
  syncFailed  ◀───────────────── any 502 ────────────┘
  (SyncFailedNotice: rows saved, role not usable in Keycloak until a sync succeeds; Re-sync)
```

The Re-sync button (`POST /roles/:id/sync`) is always shown, whatever the state. The state is
kept in the query cache under `['roles', id, 'syncState']`, so it survives tab and page changes
within one browser session but not a reload. If AS-REQ-10 adds a sync outcome to the role
resource, the tab reads that field instead and the cache entry is removed.

**Menus catalog (F-15).** A tree table (children indented under their parent) showing label,
key, path, icon, type and an active badge. Add and Edit open the same form (key, label, path,
icon, type, parent, sort order), with the rules from AS-REQ-13 and AS-REQ-14. Deactivate uses
the call that AS-REQ-14 names. Icons are rendered through a fixed map from the contract's icon
value to a Heroicons component. An unknown value shows a neutral placeholder icon and the raw
value, so a seed mismatch is visible instead of breaking the page.

### Forms and validation

Each form has one Zod schema in `src/schemas/` that mirrors the service's validation rules
(PRD §10, F2–F4 notes). The client check is for fast feedback only. Server errors are still
shown by `error.code`. Save buttons on replace-semantics screens (user roles, role menus, role
permissions) compare the current value with the last fetched value only to enable or disable
Save. The payload is always the complete set (PRD §10).

### Development and test environments

| Environment | API | Keycloak | Used for |
|---|---|---|---|
| Unit and component tests (`npm test`, `npm run test:ci`) | MSW in Node, typed from `contract.ts` | none; auth module replaced by `fakeAuth.ts` (TP-1) | every screen and hook; Goal 1 exit checks |
| Local development before the service exists | none: screens are built test-first and seen through component tests only (TP-2) | not used | building screens |
| Local development with the service | Vite proxy `/admin/v1/*` → `localhost:8085` | M0 Keycloak in Docker | manual checks |
| Browser tests (`npm run e2e`) | real service on 8085 through the Vite proxy | M0 Keycloak, real login page | CUJ 2 to CUJ 6 (Goal 9) |
| Docker image | Caddy → gateway-service | the environment's Keycloak | shared environments |

**No mocks in the browser (decided at gate TP-2).** MSW runs only inside Vitest. The console
has no mock mode, no MSW browser worker and no mock HTTP server, so no mock code path can reach
a production bundle. The scripts are the PRD's five (`dev`, `build`, `test`, `test:ci`, `lint`)
plus `e2e`. Before the service runs, the console is not clicked through in a browser. In this
trial both tracks are built at the same time, and the gap is closed by the browser tests of
Goal 9 once the service runs, not by a mock demo. Rejected at the gate: an MSW browser worker in
a `dev:mock` mode, and a separate mock server on port 8085. See Alternatives.

**MSW.** `src/test/mocks/` holds an in-memory fixture store and handlers for every endpoint in
PRD Appendix A, all typed from `contract.ts`. The default handlers implement the happy path,
with replace semantics, pagination and filters. A test that needs an error (403, 409, 502)
overrides one handler with `server.use(...)`. The menu fixtures are the twelve
`cce-insights-ui` screens: seven `NAV` entries in the sidebar order from the
[architecture doc §7](../../context/cce-insights-ui/docs/architecture-overview.md), three
URL-only pages, and two `DETAIL` routes. They mirror the admin-service seed (B-02) once that
design defines it.

**Browser tests.** `e2e/` holds Playwright tests that run in Chromium only. The Playwright
config starts `npm run dev` on port 3002 if it is not already running. It expects the M0 stack
and the service to be running already. A setup step signs in once through the real Keycloak
login page, using credentials from environment variables (AS-REQ-24), and saves the browser
state. The CUJ tests reuse it. Every test creates its own users and roles with a per-run suffix,
because neither can be deleted and reused. CUJ 4 uses the switch from AS-REQ-23 to make the
gateway sync fail and then succeed.

### How each milestone exit check is shown

PRD §10 gives an exit check for each frontend milestone. Each part is shown in one or both of
two ways:
*   **MSW test**: a Vitest test under `npm run test:ci`, against MSW handlers.
*   **Real stack**: a Playwright test under `npm run e2e`, against M0 Keycloak and the service
    on 8085.

| Milestone | Exit check (PRD §10) | MSW test | Real stack |
|---|---|---|---|
| F1 | Login redirect works | `keycloak.test.ts`: `init` called with `login-required` and `S256`; nothing renders before it resolves | The Playwright setup step signs in through the Keycloak login page |
| F1 | Header shows `/me` | App-shell test: header shows the username and role names from the `GET /me` handler; the sidebar shows Users, Roles and Menus even when `/me` returns 403 | Every CUJ test checks the test admin's username in the header after sign-in |
| F1 | `ApiError` surfaces codes in a test | `client.test.ts`: error envelope to `ApiError` `status` and `code`; non-envelope body to `CLIENT_UNEXPECTED_RESPONSE`; failed fetch to `CLIENT_NETWORK_ERROR`; 401 refresh-once path | not shown (test-only check) |
| F1 | 403 shown inline (F-04) | A page test with a 403 handler: inline message shown, shell still works | not shown: no gateway locally (C-05) |
| F2 | Create role with name warning | Roles-list test: invalid names rejected; warning and confirmation dialog shown; `POST /roles` sent only after Confirm | CUJ 3 |
| F2 | Menus tree saves full set | Menus-tab test: Save disabled until changed; request body is the complete set | CUJ 3 |
| F2 | Warning on DETAIL-only | Menus-tab test: client-side warning while editing; server warning shown after save | CUJ 3 grants one `DETAIL` menu without its parent and checks the server warning |
| F3 | Save shows "synced" | Permissions-tab test: 200 to "Synced at HH:mm" | CUJ 3 |
| F3 | MSW 502 shows retry state | Permissions-tab test: 502 to `SyncFailedNotice` with Re-sync; rows refetched | CUJ 4, using the AS-REQ-23 switch |
| F3 | Re-sync works | Permissions-tab test: Re-sync 200 to "synced"; Re-sync 502 keeps the retry state | CUJ 4 |
| F4 | Paginated, filtered list | Users-list test: each filter and page change sends the matching query parameters; page count from the pagination block | CUJ 2 finds the new user with the search box |
| F4 | Create and edit flows | User-create and user-edit tests: validation, one `POST`, the four separate edit actions | CUJ 2, CUJ 5, CUJ 6 |
| F4 | 502 state on create | User-create test: `SyncFailedNotice` (`userCreate`), submit stays disabled | not shown: a Keycloak failure part-way through create cannot be caused on demand locally |
| F5 | Add and deactivate a menu entry | Menus-catalog test: add under a parent; `DUPLICATE_MENU_KEY` on the key field; deactivate marks the entry inactive | not shown: CUJ 8 is outside Goal 9, and changing the shared local menu seed would affect other tests |

### Build and deployment

*   **Node.** `package.json` declares `engines.node` as `>=20.19 <23`. The reference build is
    the Dockerfile on `node:20-alpine`; this machine runs Node 22.22 (C-03).
*   **Docker.** A two-stage Dockerfile: `node:20-alpine` runs `npm ci` and `npm run build` with
    the four `VITE_*` values as build arguments, then `caddy:2-alpine` serves `dist/` on port
    3002. `VITE_KEYCLOAK_REALM` has no default, so the build fails without it (see Start-up).
*   **Caddy.** As in the template: compression, long cache headers for `/assets/*`, a
    `try_files` fallback to `index.html`, and the same three security headers. `/admin/v1/*` is
    reverse-proxied to the upstream given at container start in the `GATEWAY_UPSTREAM`
    environment variable. This is runtime Caddy configuration, not a `VITE_*` value. Its
    target is `gateway-service`, never the admin service directly (PRD §9). Caddy does not route
    `/auth`. Either the environment's ingress sends `${origin}/auth` to Keycloak, or the image is
    built with `VITE_KEYCLOAK_URL` set.
*   **Linting.** ESLint (flat config, typescript-eslint, react-hooks, the import rules above)
    and a Prettier check, both run by `npm run lint`.

--------------------------------------------------------------------------------

*Drafted with EGM*
