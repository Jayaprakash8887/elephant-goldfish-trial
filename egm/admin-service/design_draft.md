# cce-admin-service and its local environment (CCE v2 admin module)

*   **Author:** jayaprakash8887
*   **Status:** Proposed
*   **Date:** 2026-11-10

--------------------------------------------------------------------------------

## Overview

`cce-admin-service` is the new Spring Boot backend of the CCE v2 admin module. It owns four
things: users, roles, menus and API permissions. It keeps its PostgreSQL tables and Keycloak in
step, writes an audit row for every change, and serves `GET /admin/v1/me/menus` so that a console
can build its sidebar from the caller's roles
([PRD §2, §4, §7](../../context/prd/CCE_Admin_Module_PRD.md)).

The service sits behind the existing `gateway-service`. The gateway checks the caller's JWT and
looks up the caller's roles in the `api_permissions` table, which this service writes. Then it
forwards `/admin/**` requests to the service on port 8085. The service checks the JWT again on its
own and refuses callers whose local account is missing or not `ACTIVE` (PRD §5). Two consoles
call it: the new `cce-admin-ui` (designed separately in `egm/admin-ui/`) and the existing
`cce-insights-ui`.

This document covers PRD milestones M0 (local environment) and B1 to B5 (requirements B-01 to
B-50), plus API-level end-to-end checks of scenarios S1 to S6 (milestone E2E). The team design
documents the PRD refers to (the data dictionary, the API reference and the flow diagrams) are not
inputs to this trial. This document therefore defines that content itself: every table, column,
index and seed row; every endpoint's request and response shape and error codes; and the order of
operations in each flow that touches Keycloak or the gateway. Under author decision D-01, this
document is the API contract that `cce-admin-ui` builds against. Under D-03, it is the single
definition of the local environment ([author decisions](../../docs/designs/author-decisions.md)).

### Inputs

| Input | What this design takes from it |
|---|---|
| [PRD](../../context/prd/CCE_Admin_Module_PRD.md) (Markdown copy of the authoritative PDF) | Requirements B-01 to B-50, scenarios S1 to S6, milestones M0, B1 to B5 and E2E, the stack (§6), the error codes (Appendix B), the endpoints (Appendix A). |
| [Gateway migration doc](../../context/gateway-service/DATABASE_PERMISSIONS_MIGRATION.md) | The fixed shape of `api_permissions` and how the gateway loads it. |
| [insights-ui architecture](../../context/cce-insights-ui/docs/architecture-overview.md), [developer setup](../../context/cce-insights-ui/docs/developer-setup.md), [deployment guide](../../context/cce-insights-ui/docs/deployment-guide.md) | The 12 routes and 7 sidebar links the menu seed matches, and the gateway audience `gateway-service`. |
| [Author decisions](../../docs/designs/author-decisions.md): D-01, D-03, D-05 | D-01: this design owns the API contract. D-03: this design defines the local environment. **D-05: the exact versions of every image and dependency**, used unchanged in section 9.1 and in the build files. |
| [Console design draft](../../egm/admin-ui/design_draft.md) | Requirements AS-REQ-01 to AS-REQ-24 on this design, answered in section 4.6. |

## The Problem

Today `cce-insights-ui` shows the same fixed sidebar of 7 links to every signed-in user and never
reads roles from the token
([insights-ui architecture §7](../../context/cce-insights-ui/docs/architecture-overview.md)). The
gateway can already block an API call by role, because it reads `api_permissions`
([gateway migration doc](../../context/gateway-service/DATABASE_PERMISSIONS_MIGRATION.md)). But
nothing manages who has which role, which screens a role should see, or which APIs a role may
call. Changing any of these today means editing Keycloak by hand and running SQL against a table
that a service owned by another team depends on. Nothing records who made such a change, and if
Keycloak and the database disagree, nobody notices.

This is a compliance platform, so "who gave this user access, and when" has to be answerable from
data, not memory (PRD §9, Auditability).

The service has to solve this under four constraints:

1.  **Fixed gateway contract.** `gateway-service` already reads a table named `api_permissions`
    with a fixed column shape, in a Postgres database named `admin_service`. This team does not
    own the gateway and cannot change it (PRD §5, §12).
2.  **Two systems of record per write.** Creating a user or changing a user's roles must change
    both Keycloak and the local database. Keycloak calls can fail after the database work has
    started, or the other way round, and the two cannot share a transaction. The PRD asks for
    these partial failures to be shown to the caller and recorded for manual reconciliation, not
    retried blindly (PRD §9, Consistency; B-32).
3.  **Split ownership of Keycloak.** The gateway, not this service, creates and deletes realm role
    definitions, through its `POST /api/permissions/sync-to-keycloak` endpoint. This service
    creates user accounts and assigns realm roles to them. A role therefore cannot be assigned to
    a user until a gateway sync has created its realm role (PRD §4, §9 Ordering).
4.  **Missing source documents.** The data dictionary, API reference and flow diagrams the PRD
    cites are not available. A coding agent will implement this service from this document
    alone, so this document must leave that agent no design decisions.

## Goals

Each goal names the PRD requirement IDs it covers and how it is checked.

*   **G1. One-command local environment (M0, D-03).** `docker compose up` in `cce-admin-service/`
    starts Postgres 16 and Keycloak. Keycloak starts with a realm already imported that contains
    the `cce-admin-ui` public client (PKCE S256, redirect `http://localhost:3002/*`), a
    confidential service-account client for the backend, and a test administrator. The realm
    import file holds no credentials. The confidential client's secret and the test
    administrator's password appear in it only as placeholders, which Keycloak fills from
    environment variables when the container starts. An example env file with placeholder values
    is committed. The real env file is git-ignored (PRD §11, B-13). Check: the test administrator
    can sign in to the Keycloak account console, and `git ls-files` lists no file that holds a
    real secret or password.
*   **G2. Schema and seed from an empty database (B-01, B-02).** Flyway migrations create all seven
    tables, their indexes and the `pgcrypto` extension on an empty database without errors. They
    seed the 12 cce-insights-ui menus and the system `SUPER_ADMIN` role. `api_permissions` matches
    the gateway's column shape column for column. Check: an integration test migrates a new
    Postgres container and compares the result against the data dictionary in this document.
*   **G3. One written contract (B-03, B-04, B-06, D-01).** Every endpoint in PRD Appendix A has its
    path, query parameters, request body, response body and possible error codes written in this
    document. The service returns `{data, pagination?}` on success and `{error:{code,message}}` on
    failure. The OpenAPI spec generated by springdoc lists every endpoint. Check: a test
    produces each error code in PRD Appendix B at least once.
*   **G4. The service checks every caller itself (B-05, B-10 to B-13).** `GET /health` needs no
    token. Every other endpoint rejects a bad, expired or wrongly addressed token with 401
    `UNAUTHENTICATED`. It rejects a valid token whose `sub` has no local user, or whose user is
    not `ACTIVE`, with 403 `FORBIDDEN`. Roles are read from the claim path set in
    `JWT_ROLES_CLAIM_PATH`. No credential is stored in the repository.
*   **G5. Users stay consistent with Keycloak, and failures are visible (B-30 to B-36).** Each user
    flow runs its Keycloak and database steps in the order this document fixes. Any step that
    fails after an earlier step has changed something returns 502 `KEYCLOAK_SYNC_FAILED` and
    writes an audit row with the Keycloak user id. Nothing is retried automatically. There is
    one exception. If the Keycloak action email that lets a new user set a password cannot be
    sent after the account and the local row exist, user creation still returns 201, with the
    warning `ACTION_EMAIL_NOT_SENT` (AD-08). The email changes no access state, and
    reset-password can send it again. Check: a
    service test makes the database insert fail after Keycloak succeeds and asserts both the 502
    and the audit row (PRD §10, B3 notes).
*   **G6. Roles, menus and permissions managed through the API, not SQL (B-40 to B-44).** Role,
    menu and permission changes go through the service, follow replace semantics where the PRD
    says so, and trigger the gateway sync after permission changes. A failed sync returns 502 and
    keeps the database change.
*   **G7. Role-aware menu tree (B-20, B-21).** `GET /me/menus` returns the union of the active
    menus of all the caller's roles, with children nested under their parents and ordered by
    `sort_order`. Check: two roles with overlapping menus produce the merged tree (B5 demo).
*   **G8. Every state change is audited, including partial ones (B-50, B-32).** The service
    writes one append-only `audit_log` row per state change. Each row holds actor, action,
    entity type and id, before and after JSON, and the caller's IP address. For most mutating
    endpoints a successful call is one state change and writes one row. A permission save is two
    state changes: the saved rows, and the gateway sync attempt that follows. It therefore
    writes two rows: `ROLE_PERMISSIONS_UPDATED` in the same transaction as the rows, then
    `ROLE_SYNC_REQUESTED` after the sync call, whose after JSON records the outcome (`SUCCESS`
    or `FAILED`, with an error summary). Every gateway sync attempt, automatic or manual
    (`POST /roles/:id/sync`), writes its own `ROLE_SYNC_REQUESTED` row. This way `audit_log`
    alone shows whether every permission change reached the gateway (AD-06). A call that fails
    part-way, after Keycloak or the database has already changed, and returns 502
    `KEYCLOAK_SYNC_FAILED` writes one row that records which steps completed, which step failed,
    and the Keycloak user id when one exists, so that an operator can reconcile by hand. For a
    failed sync that row is the `ROLE_SYNC_REQUESTED` row. A call that fails before anything
    changes (for example 400, 404 or 409) writes no row. Check: a test asserts the number of
    rows and their contents for every mutating endpoint, on both the success path and the 502
    path. That is two rows for a permission save and one row for a manual re-sync, whatever the
    sync outcome.
*   **G9. Scenarios S1 to S6 run end to end on this machine.** A scripted check runs all six PRD
    scenarios against the real service, the real Postgres and the real Keycloak in Docker, with a
    local stand-in for gateway-service. The stand-in can be made to fail on purpose, so that S6
    can be checked. Because the real gateway is absent, these runs cannot show that the gateway
    blocks a call a role may not make (the hard control, PRD §4). An offline check covers part of
    that gap. For every `api_permissions` row the scenarios write, a test takes the (HTTP method,
    path) calls the row is meant to allow and matches them against the stored rows, using the
    matching rules assumed under GW-3. It also checks one call that the rows must not allow. A
    pattern that would not allow its intended call is caught on this machine. This test checks
    the stored rows against assumed rules. It does not check the gateway, and its result is only
    as good as the GW-3 assumption.
*   **G10. Undocumented gateway behaviour is a configuration change, not a code change.** The
    gateway sync URL and its timeouts, and how the service treats the sync response, come from
    configuration. Where the gateway owners' answers to the questions under "Open Questions"
    differ from this document's assumptions, adjusting configuration is enough, wherever that is
    reasonable.
*   **G11. Buildable from this document alone.** `mvn verify` passes, which covers compile, unit
    and service-layer tests with Keycloak and gateway clients mocked, and integration tests with
    Testcontainers Postgres (PRD §9 Testability, §11). The service builds into a multi-stage
    Docker image that listens on port 8085.

## Non-Goals

*   **The admin console.** `cce-admin-ui` (F-01 to F-15) has its own design. This document only
    provides the contract it uses (D-01) and the local values it runs against (D-03).
*   **Changes to other repositories.** The `cce-insights-ui` change that renders its sidebar from
    `/me/menus` (PRD §12) and any change to `gateway-service` are out of scope. The gateway is used
    as it is.
*   **Running the real gateway locally.** gateway-service is not available on this machine. Local
    runs and the E2E checks use a stand-in for its sync call, and call the service directly on
    port 8085.
*   **Items the PRD puts outside CCE v2** (PRD §2): an audit log read endpoint or viewer, a
    cross-role `GET /permissions` catalog, enforcing `users.facility_scope` (it is stored and
    returned only), deriving permissions from menus, hard delete of users, and role-driven
    navigation in the admin console.
*   **Automatic repair of partial failures.** A 502 partial state is recorded in `audit_log` for an
    operator to reconcile by hand. The service does not retry, roll back Keycloak, or run a
    background reconciliation job (PRD §9, §12).
*   **Shared and production environments.** This document defines the environment variables the
    service reads and the bootstrap steps a new environment needs. It does not provision shared
    Keycloak realms or databases, and it does not include a Jenkins pipeline file. Jenkins is not
    available here and its conventions are not among the inputs. CI only has to run `mvn verify`
    and build the Docker image (PRD §6, §11).
*   **Password handling.** No password ever passes through the service. Password resets go through
    a Keycloak required action sent by email (B-36).

## Deviations from the PRD

These are places where this design cannot follow the PRD as written. PRD §11 asks that each
deviation be raised with the team as a doc change.

| ID | PRD text | This design | Why |
|---|---|---|---|
| DEV-1 | B-01: all seven tables have UUID primary keys. | `api_permissions` keeps the gateway's exact shape, including `id BIGSERIAL`. The other six tables use UUID primary keys. | `api_permissions` is a fixed constraint read by gateway-service (PRD §5, §12; [gateway migration doc](../../context/gateway-service/DATABASE_PERMISSIONS_MIGRATION.md)). Changing the key type could break a service this team does not own. |
| DEV-2 | §11 definition of done: "`npm run build` / `npm run lint` / `mvn verify` pass in CI." | No CI pipeline file is delivered. `mvn verify` and the Docker image build are run locally instead, and their results stand in for CI. | Jenkins is not available on this machine, and the team's Jenkins conventions are not among the inputs (see Non-Goals). |
| DEV-3 | §6: "PostgreSQL schema `admin_service`". | `admin_service` is read as the *database* name. All seven tables live in its `public` schema (decision AD-01). | PRD §5 and the gateway doc name a database `admin_service`, and the gateway connects without naming a schema, so it reads `public`. Putting the tables in a schema `admin_service` would need a configuration change in a deployment this team does not own. |
| DEV-4 | §2 (out of scope): "Deactivation (`INACTIVE`) is terminal, for audit continuity." | `INACTIVE` can be undone. A user moves freely between `ACTIVE`, `INACTIVE` and `LOCKED` (decision AD-04). "Terminal" is read as "the last state, used instead of deletion". | B-34, S5 and F-10 describe re-activation, and Appendix A lists `LOCKED`. The sentence in §2 sits in the row that rules out hard delete. Users are still never deleted, so audit continuity holds. |
| DEV-5 | B-03: "Success: `{data, pagination?}`." | Success bodies may also carry `warnings` (needed by B-41 and AD-08). `DELETE /roles/:id` and `DELETE /menus/:id` return 204 with no body. `GET /health` returns B-05's bare `{status, database}` (section 4.1). | B-03 gives a warning (B-41) no place to go. B-05 itself writes the health body without the envelope. A 204 has no body to wrap. |
| DEV-6 | Appendix B lists the error codes. | Adds `USERNAME_IN_USE`, `ROLE_NOT_SYNCED`, `ROLE_NOT_ASSIGNABLE`, `LAST_SYSTEM_ADMIN`, `MENU_HAS_CHILDREN`, `NOT_FOUND`, `METHOD_NOT_ALLOWED` and `INTERNAL_ERROR`, and the 502 `partialState` object (section 4.2). | Each one covers a case Appendix B leaves without a code: a separate username, the ordering rule of PRD §9, the service-account role, the last administrator, a menu with children, and framework errors. |
## Author Decisions in This Design

Decisions the author took at this design's drafting gates. Decisions that the console design
also needs are copied to [author-decisions.md](../../docs/designs/author-decisions.md) under a
`D-` ID.

| ID | Question | Decision | Why |
|---|---|---|---|
| AD-01 | OQ-1: where the seven tables live. | All seven tables live in the `public` schema of the database `admin_service`. Recorded as DEV-3. | The gateway needs no change. Putting the tables in a schema `admin_service` would make the module depend on a configuration change in the gateway's deployment, which this team does not own, and if that change were missing the gateway would load no permissions and block every call. Splitting the tables across two schemas would only serve PRD §6's wording, which §5 and the gateway doc contradict. The `admin_service` database belongs to this module, so name clashes in `public` are unlikely. |
| AD-02 | OQ-2: who creates `api_permissions`. | This service's Flyway V1 migration creates the table, its two indexes and its unique constraint only if they are missing, using the gateway's exact DDL. A later migration then checks the table's shape and stops the migration if anything differs. The check covers the column names, types, nullability and defaults; the unique constraint on (`permission_name`, `http_method`, `uri_pattern`); and the two indexes. Rows already in the table that no role owns, such as the gateway doc's samples, are left in place. The bootstrap steps for a new environment include a read-only query that lists them for an operator to review (risk GW-5). Flyway runs with `baseline-on-migrate=true` and `baseline-version=0`. Otherwise it refuses a non-empty `public` schema that has no history table, which is exactly the state an environment is in after the gateway's script ran. With version 0, V1 and every later migration still run. Details are in section 6. | B-01 holds on an empty database. An environment where the gateway's script already ran keeps working, and no manual data migration is needed. Making the gateway's script the only creator would make every new environment depend on a manual step. Having Flyway own the table outright would mean dropping and recreating it in existing environments, which opens a window in which the gateway loads no permissions. Replace semantics and the duplicate-row error both depend on the unique constraint, so the check covers it too. |
| AD-03 | OQ-3: how the three routes outside the sidebar are seeded. | All 12 cce-insights-ui routes are seeded. Practitioners, Intelligence and Exports are active top-level `NAV` menus, like the other seven sidebar links. `sort_order` puts today's 7 sidebar links first, in today's order (Dashboard, Facilities, Compliance, Deviations, Patients, Events, Ingestion), then Practitioners, Intelligence and Exports. The two `DETAIL` menus sit under Compliance and Patients. The seed grants no menu to any role, `SUPER_ADMIN` included. | Under per-role menus, a menu only controls what a role sees (PRD §4, soft control), and the one rule a console needs is "granted means shown". Seeding the three as inactive would give `is_active` two meanings, "retired" and "routed but hidden". A `ROUTE` type is not defined in PRD §4, and every console would have to filter on it. Keeping these pages out of the sidebar was a decision for the old sidebar, which was the same for every user. Now an administrator decides it per role. `SUPER_ADMIN` is the admin console's role, and console roles are set up through the API. |
| AD-04 | OQ-4 (AS-REQ-07): user statuses. | There are three statuses: `ACTIVE`, `INACTIVE` and `LOCKED`. `PATCH /users/:id/status` allows every change between them. `INACTIVE` and `LOCKED` both disable the Keycloak account, end the user's Keycloak sessions and keep `user_roles`. A change to `ACTIVE` enables the account again. The audit action records which event happened: `USER_ACTIVATED`, `USER_DEACTIVATED` (to `INACTIVE`, offboarding) or `USER_LOCKED` (to `LOCKED`, a temporary block). A request for the status the user already has returns 200 and writes no audit row. `LOCKED` is set by an administrator only. It is not linked to Keycloak's own brute-force lockout. Recorded as DEV-4. | Follows Appendix A (which lists `LOCKED`) and B-34, S5 and F-10 (re-activation). Offboarding and a temporary block are different events for operators, and the audit action keeps them apart. |
| AD-05 | OQ-8 (AS-REQ-04): how `last_login_at` is updated. | The local-user filter (section 3, step 4) runs a conditional update: it sets `last_login_at` to the token's `auth_time` only where `last_login_at` is null or earlier than `auth_time`. It does not change `updated_at` and writes no audit row, because a sign-in is not an admin action. A token without `auth_time` leaves the value unchanged. The value therefore means "the last sign-in whose token reached this service". A sign-in to a console that never calls the service is not counted. M0 keeps Keycloak's `basic` client scope, which adds `auth_time` to access tokens, on both console clients. | One write per sign-in. No polling and no Keycloak extension. A refreshed token keeps its `auth_time`, so token refreshes do not count as sign-ins. The conditional update is safe when parallel requests carry the same token. |
| AD-06 | OQ-11: how the result of a gateway sync is recorded. | Every gateway sync attempt writes its own `ROLE_SYNC_REQUESTED` audit row for the role that asked. Its after JSON holds the outcome (`SUCCESS` or `FAILED`), an error summary on failure, and, under the `GLOBAL` scope (below), the ids of the other roles marked synced. This covers the automatic sync after `PUT /roles/:id/permissions` and the manual `POST /roles/:id/sync`. A permission save writes `ROLE_PERMISSIONS_UPDATED` in the same transaction as the rows, then `ROLE_SYNC_REQUESTED` after the sync call. When the sync fails, both endpoints return 502 `KEYCLOAK_SYNC_FAILED`, the rows stay saved, and `partialState.auditLogId` points to the `ROLE_SYNC_REQUESTED` row. For the console, `roles` also carries `sync_status` (`NEVER`, `SYNCED`, `FAILED`) and `last_sync_at` (null until the first attempt). Updating these two fields is not audited separately. The setting `GATEWAY_SYNC_SCOPE` (`GLOBAL` or `ROLE`, default `GLOBAL`) follows the answer to GW-1. Under `GLOBAL`, a successful sync marks `SYNCED` every role that has at least one permission row. Under `ROLE`, it marks only the role that asked. A failed sync marks only the role that asked. G8 is amended to match. | This is a compliance platform (PRD §9, Auditability). "Is the saved permission actually in effect?" is exactly what an auditor asks, and a role's latest status cannot answer that for past changes. The `GLOBAL` setting stops a role from showing `FAILED` when another role's sync has already created its realm role. |
| AD-07 | OQ-12 (AS-REQ-12): what `SYSTEM_ROLE_IMMUTABLE` blocks. Also pushback P1. | For a role with `is_system = true`, `PUT /roles/:id`, `PUT /roles/:id/menus`, `PUT /roles/:id/permissions` and `DELETE /roles/:id` return 409 `SYSTEM_ROLE_IMMUTABLE`. `POST /roles/:id/sync` is still allowed. A system role can be assigned to users unless its `is_assignable` is false (AD-14). Separately, a `PATCH /users/:id/status` or `PUT /users/:id/roles` that would leave no `ACTIVE` user holding `SUPER_ADMIN` returns 409 `LAST_SYSTEM_ADMIN` *(added)*, and nothing changes. This also covers an administrator locking themselves out. | `SUPER_ADMIN`'s `/admin/v1/**` rows are what let administrators administer, and making them editable invites a lock-out. Freezing the rows alone does not prevent one, because the last administrator could still be deactivated or lose the role. |
| AD-08 | OQ-13 (AS-REQ-05): how a new user gets a first password. | `POST /users` creates the Keycloak user with the required action `UPDATE_PASSWORD`. After the account, the role mappings, the local row and the audit row all exist, it sends Keycloak's execute-actions email for that action. If the email cannot be sent, the response is still 201, with the warning `ACTION_EMAIL_NOT_SENT`. The administrator can use reset-password later. The `USER_CREATED` row is already committed at that point, so `audit_log` does not record the failed email. Only the response does. `POST /users/:id/reset-password` sends the same email, and a failure there is a 502. Locally, M0 points Keycloak's SMTP at a Mailpit container, which captures mail and delivers none. G5 is amended to match. | No password passes through the service (B-36). The email changes no access state and can be sent again, so it does not justify a 502 that would hide a successful creation. A later reset-password is audited. |
| AD-09 | OQ-14 (AS-REQ-08): `facility_scope`. | One optional string, `VARCHAR(50)`, free text, trimmed. An empty value is stored as null, which means unscoped. The `facilityScope` filter on `GET /users` matches exactly and is case-sensitive. An empty filter value is ignored. No endpoint lists valid values, and an "unscoped users only" filter is out of scope. | Facility master data is owned elsewhere, and enforcing the scope is out of scope (PRD §2). F-10 asks for no filter on unscoped users. |
| AD-10 | OQ-15 (AS-REQ-10): assigning a role that has not been synced. Also pushback P2 and P4. | Before `POST /users` or `PUT /users/:id/roles` changes anything, the service looks up in Keycloak the realm role of each role being *added*. It needs that role representation to add the mapping anyway. If any are missing, the call returns 409 `ROLE_NOT_SYNCED` *(added)* with one `details` entry per missing role (`field` = `roleIds[i]`), and nothing changes. A role being *removed* whose realm role is already missing from Keycloak is skipped in Keycloak, because there is nothing to remove. Its `user_roles` row is still deleted. *Superseded part:* pushback P2 added a rule that `PUT /roles/:id/permissions` with an empty set, on an assigned role, returns 409 `ROLE_IN_USE`. AD-16 makes that rule unnecessary, so it is dropped. Every non-system role always keeps its two implicit rows, and a system role cannot be changed at all (AD-07). So no role's permission set can become empty through the API. | PRD §9 requires the realm role to exist before assignment. Keycloak is the authoritative source for that, and the role's `sync_status` is only a display hint. P2 guarded against an assigned role losing all its rows, which could make the next sync delete its realm role (GW-4). AD-16 now prevents that state from arising. |
| AD-11 | OQ-5 (AS-REQ-23): what stands in for the gateway sync locally. | A stand-in gateway runs in its own container, started by the M0 compose file. It is built from its own Maven module in `cce-admin-service/gateway-stub/`, in plain Java 21: the JDK's built-in HTTP server, a JDBC read of `api_permissions`, and Keycloak Admin REST calls through `java.net.http`. It uses no web framework. It implements only the assumptions written under GW-1 and GW-4: `POST /api/permissions/sync-to-keycloak` creates a realm role for every distinct `permission_name` that has none, and deletes realm roles that it created and that no longer have rows. It marks the roles it creates with the Keycloak role attribute `cce-managed=true`, so it never touches a role it did not create. It never creates, changes or deletes Keycloak's built-in roles (`default-roles-<realm>`, `offline_access`, `uma_authorization`), and it never deletes `SUPER_ADMIN`. A control endpoint, `PUT /_stub/mode` with body `ok` or `fail`, switches it at runtime. In `fail` mode the sync endpoint returns 503 and changes nothing. The control endpoint needs no token. Its port is published on `127.0.0.1` only, so it is reachable from the compose network and the host and from nowhere else. It holds no data a test could leak. Its README says plainly that it is a stand-in, not the gateway. The service reaches it only through `GATEWAY_BASE_URL`. | Locally the service runs exactly the code it runs against the real gateway: the URL, authentication, timeouts and status mapping in `GatewaySyncClient`. The production artifact carries no failure switch and no local-only endpoint. A local implementation inside the service would copy the same assumed gateway behaviour, so it would carry the same drift risk, plus those two drawbacks. |
| AD-12 | Role delete and the gateway sync (accepted from a proposal made during drafting). | `DELETE /roles/:id` deletes the role's `role_menus` and `api_permissions` rows and the role row, and writes `ROLE_DELETED`, in one transaction. If the role had at least one permission row, it then runs the gateway sync, so that the gateway can drop the realm role (GW-4), and audits the attempt as `ROLE_SYNC_REQUESTED`. If that sync fails, the response is 502 `KEYCLOAK_SYNC_FAILED` with `partialState.operation = ROLE_DELETE`. The message says that the role is deleted, and that its realm role may stay in Keycloak until the next successful sync of any role clears it. | The role is gone, so the console has nothing to re-sync. Under `GATEWAY_SYNC_SCOPE=GLOBAL` the next successful sync removes the leftover realm role. Because the role was not assigned to anyone (B-40), the leftover realm role grants nothing in the meantime. |
| AD-13 | OQ-6: how the service authenticates to the gateway's sync endpoint. | The service gets a token for its own confidential client, `cce-admin-service`, through the client-credentials grant, and sends it as `Bearer`. This is the same client it uses for the Keycloak Admin API. The token is cached until 30 seconds before it expires. An audience mapper on that client adds `gateway-service` to its tokens. The client's service-account user holds the realm role `CCE_ADMIN_SERVICE`. The seed inserts `CCE_ADMIN_SERVICE` into `roles` as a system role that cannot be assigned (AD-14), with one `api_permissions` row: `POST /api/permissions/sync-to-keycloak`. A role owns that row, so the unowned-rows query (GW-5) does not list it. `GatewaySyncClient` has exactly one authentication mode. **Bootstrap order.** The gateway creates realm roles only during a sync, but the service account needs the `CCE_ADMIN_SERVICE` realm role before its first sync is authorized. So the bootstrap steps for a new environment (section 9) include a step that runs before the service's first deploy. An operator either creates the realm role `CCE_ADMIN_SERVICE` and maps it to the user `service-account-cce-admin-service`, or has the gateway owners run one sync by their own means. Until that is done, every sync fails, and permission saves return 502 with `failedStep = GATEWAY_SYNC`, which is visible and recoverable. Locally, the realm import creates the role and the mapping. The stand-in gateway (AD-11) checks the token the way the gateway is assumed to: it verifies the RS256 signature against the realm's JWKS using JDK classes only, checks `iss`, `aud` and `exp`, and checks that a role in the token has an `api_permissions` row allowing `POST /api/permissions/sync-to-keycloak`. A misconfigured service account therefore fails locally too. Forwarding the caller's token is the fallback if the gateway owners say otherwise (GW-6). That fallback is a code change, not a setting. | The sync does not depend on who called or on the caller's token. There is no drift between "allowed to save" and "allowed to sync", which forwarding the administrator's token would create. It stays inside the JWT model the gateway uses everywhere else. A shared-secret header would need a check we have no evidence of. |
| AD-14 | Keeping `CCE_ADMIN_SERVICE` from being assigned to people. | `roles` gets the column `is_assignable BOOLEAN NOT NULL`, with **no default**. Every insert must set it, so the database rejects a seed or a later migration that leaves it out. The seed sets `true` for `SUPER_ADMIN` and `false` for `CCE_ADMIN_SERVICE`. The service sets `true` on every role it creates through the API, and no endpoint can change the value. The Role resource exposes it as `isAssignable`. `POST /users` and `PUT /users/:id/roles` return 409 `ROLE_NOT_ASSIGNABLE` *(added)* when a role being added has `is_assignable = false`, with one `details` entry per such role, and nothing changes. A migration test checks that the column has no default and that every `is_system` role has the seeded value. AD-07 is amended to match. | The rule lives in data the console can read: its role pickers and its Users filter hide roles where the flag is false. It works for later machine roles too. A role name in configuration would depend on a setting matching a row in every environment. Hiding the role from the API would hide the service account's rights from the API that exists to replace SQL. |
| AD-15 | OQ-7 (AS-REQ-22, AS-REQ-24): the first `SUPER_ADMIN` in each environment. | A bootstrapper runs once each time the service starts, after Flyway. **(1)** If any `ACTIVE` user holds `SUPER_ADMIN`, it does nothing. If `BOOTSTRAP_ADMIN_USERNAME` is also set, it logs a warning that the variable is no longer needed and should be removed. **(2)** Otherwise, if `BOOTSTRAP_ADMIN_USERNAME` is not set, it logs an error that there is no administrator, and the service still starts. **(3)** Otherwise it looks up that Keycloak user by exact username through the Admin API. It refuses, logs an error and changes nothing if: the user does not exist; the user is disabled; or the realm requires email verification and the user's email is not verified. **(4)** If the `SUPER_ADMIN` realm role is missing, it runs one gateway sync and audits it as `ROLE_SYNC_REQUESTED`. If the role is still missing, it logs an error and stops. **(5)** It adds the `SUPER_ADMIN` realm role mapping if missing. Then, in one transaction, it inserts the `users` row (or sets an existing row for that `keycloak_id` to `ACTIVE`) and its `SUPER_ADMIN` `user_roles` row, and writes one audit row, `USER_CREATED` or `USER_ROLES_UPDATED`, with actor `system:bootstrap`. It uses the same `KeycloakGateway` methods as `POST /users`. Any Keycloak or database failure is logged, and the service still starts. The bootstrap steps tell operators to unset the variable after the first successful start. **Locally:** the realm import creates the test administrator with a fixed id, the `SUPER_ADMIN` realm role and the mapping. The username and password come from the git-ignored `.env` through placeholders (`CCE_TEST_ADMIN_USERNAME`, `CCE_TEST_ADMIN_PASSWORD`), and the same two variables give the test administrator's credentials to tests (AS-REQ-24). `BOOTSTRAP_ADMIN_USERNAME` is set to the same username. The service reads `.env` whether it runs inside compose or on the host (section 9). | It is safe to run again. No ids are copied by hand. It reuses the Keycloak code of `POST /users`, and it is audited. A Flyway migration cannot be corrected by running it again when an id is wrong. A SQL runbook is the hand-edited SQL the PRD wants to replace, and it is easy to run without the audit row. The refusal rules and the warning cover the case of a stale username in the variable. Re-activating a former user's row is covered by the disabled-user refusal, because an `INACTIVE` or `LOCKED` user's Keycloak account is disabled. |
| AD-16 | OQ-9: permission for every console role to call `GET /admin/v1/me/menus` (PRD §12). | Every non-system role always holds two **implicit** `api_permissions` rows: `GET /admin/v1/me` (description "Read own profile", category `Self`) and `GET /admin/v1/me/menus` (description "Read own menu tree", category `Self`). There is no `implicit` column, because `api_permissions` keeps the gateway's fixed shape. A row is implicit when its (`http_method`, `uri_pattern`) equals one of these two. **Rules.** `POST /roles` inserts both rows with the role, and then runs the gateway sync, as every change to `api_permissions` does (operation `ROLE_CREATE`). `PUT /roles/:id/permissions` replaces the *explicit* rows only. The implicit rows are always kept. A body row whose method and pattern equal an implicit row is accepted and not duplicated, and the stored description and category stay fixed. `GET /roles/:id/permissions` returns every row with `"implicit": true` or `false`. The console shows implicit rows read-only and leaves them out of what it sends. The `ROLE_PERMISSIONS_UPDATED` audit row's before and after JSON list explicit and implicit rows separately. System roles get no implicit rows: `SUPER_ADMIN` is covered by `/admin/v1/**`, and `CCE_ADMIN_SERVICE` is not a console role. AD-10's empty-set rule is dropped (see AD-10). | Nobody can forget the row, which is the failure PRD §12 names. Every ordinary role has at least one row, so it gets a realm role after its first sync. That removes the trap where a role with no rows can never be assigned. A warning could be ignored, and a role with no rows would never get one. A shared `CONSOLE_USER` role would move the same failure from roles to users. `/me` returns only the caller's own profile, and the admin console's header needs it for any administrator role that is not `SUPER_ADMIN`. |
| AD-17 | OQ-10: whether the service checks `api_permissions` itself. | Yes. Pipeline step 5 (section 3) applies the same `api_permissions` rules as the gateway. **(1) One matcher.** A single class, `PermissionMatcher` (section 3, step 5), decides the step-5 check, G9's offline row check, and the stand-in gateway's token check (AD-11, AD-13). The stand-in compiles the same source file. So these checks cannot disagree with each other, and only the GW-3 assumption can be wrong. **(2) A switch.** `PERMISSION_CHECK_ENABLED` (default `true`) turns step 5 off without a code change, for an environment where the gateway's real matching turns out to differ (G10). Startup logs the setting. When it is off, startup logs a warning that only the gateway now enforces permissions. **(3) Roles from the token.** The caller's roles for step 5 come from the token claim at `JWT_ROLES_CLAIM_PATH`, as at the gateway, not from `user_roles`. Public paths skip the check. A refusal is 403 `FORBIDDEN` with the message "Your roles do not allow {METHOD} {path}." Clients branch on the code, not the message. | Real defence in depth (PRD §9). Locally the hard control is enforced and testable, which turns part of G9's gap into a live check. The 403 "role lacks permission" in Appendix B is then produced and tested in the service. A disagreement with the gateway fails closed and visibly. A coarse role list in configuration would drift from the data as soon as an administrator creates a narrower admin role. |

## Open Questions

### For the author (each one is decided at a drafting gate)

The console design's requirements on this design (AS-REQ-01 to AS-REQ-24, in
[the admin-ui draft](../../egm/admin-ui/design_draft.md)) are answered in the Technical Plan,
section 4. The AS-REQs that need an author decision are listed with the open question that
covers them.

All author questions raised so far are decided (AD-01 to AD-17).

| Question or decision | Console requirements it answers |
|---|---|
| AD-04 | AS-REQ-07 |
| AD-05 | AS-REQ-04 (last login) |
| AD-07 | AS-REQ-12 |
| AD-08 | AS-REQ-05 (first password) |
| AD-09 | AS-REQ-08 |
| AD-10 | AS-REQ-10 |
| AD-11 | AS-REQ-23 (`PUT /_stub/mode` on the stand-in gateway; section 9 gives its URL) |
| AD-15 | AS-REQ-22 (test administrator), AS-REQ-24 (how the test administrator's credentials reach tests) |
| None (M0, section 9) | AS-REQ-22 (Keycloak URL, realm, audience), AS-REQ-24 (resetting the local database and realm) |

### For the gateway-service owners (assumptions are configurable, per G10)

*   **GW-1. Sync endpoint contract.** What does `POST /api/permissions/sync-to-keycloak` expect as
    its request (body, parameters) and return as its response? Is it a full sync of every
    `permission_name`, or can it be limited to one role? Which status codes mean success?
    *Assumed until answered:* the request has no body and no query parameters. It syncs every
    distinct `permission_name` (`GATEWAY_SYNC_SCOPE=GLOBAL`). Any 2xx status is success. Any
    other status, a connection error or a timeout is failure. The response body is not parsed.
    Its first 500 characters go into the audit row's error summary. Configurable without code:
    `GATEWAY_BASE_URL`, `GATEWAY_SYNC_PATH`, `GATEWAY_CONNECT_TIMEOUT`, `GATEWAY_READ_TIMEOUT`
    and `GATEWAY_SYNC_SCOPE`. A request body or a per-role parameter would need a code change.
*   **GW-2. When permission changes take effect.** The gateway doc says new permissions are loaded
    only at startup and the gateway must be restarted. Does the sync endpoint also reload the
    gateway's in-memory permissions? If not, a permission saved through the admin console has no
    effect on the gateway until it restarts.
*   **GW-3. URI pattern syntax and the path that is matched.** The gateway doc's examples use
    single-segment `*` patterns on `/admin/users/*`. PRD §12 uses `/admin/v1/**`. Which wildcard
    syntax does the gateway support (for example Spring `AntPathMatcher` or `PathPattern`), and is
    the pattern matched against the incoming path, `/admin/v1/...`, before the request is proxied?
    *Assumed until answered:* Ant-style matching, as written in section 3, step 5, against the
    path the service also receives (the gateway does not rewrite `/admin/v1/...`). If the
    gateway's matching differs, `PERMISSION_CHECK_ENABLED=false` turns off the service's own
    check without a code change (AD-17). The matcher itself would need a code change.
*   **GW-4. Removing realm roles.** PRD §4 says the gateway creates *and deletes* realm role
    definitions. Does a sync delete a realm role whose `permission_name` no longer has any rows in
    `api_permissions` (for example, after a role is deleted here)? *Assumed until answered:* yes,
    but only for realm roles the gateway itself created. The stand-in (AD-11) follows this
    assumption by tagging the roles it creates.
*   **GW-5. Rows that no role owns (risk, from AD-02).** In an environment where the gateway's
    own script already ran, `api_permissions` can hold rows whose `permission_name` matches no
    row in `roles`. The gateway doc's samples `USER_READ` and `USER_CREATE_UPDATE` are examples.
    This service never shows or changes those rows, but the gateway keeps enforcing them. A
    Keycloak user who holds such a realm role keeps those API rights, and no admin screen shows
    it. Are any of these rows still needed? Can they be deleted, or turned into roles managed
    here? Until the owners answer, the bootstrap steps (section 9) include a read-only query that
    lists the unowned `permission_name` values for an operator to review.
*   **GW-6. Authentication on the sync endpoint.** Does `POST /api/permissions/sync-to-keycloak`
    go through the gateway's normal JWT and `api_permissions` check, and does it accept a
    service-account token? *Assumed until answered:* yes. The service sends a client-credentials
    token whose roles include `CCE_ADMIN_SERVICE` (AD-13). If the endpoint expects a person's
    token instead, `GatewaySyncClient` changes to forward the caller's `Authorization` header.
    That is a code change, and `SUPER_ADMIN`'s seed rows would then also need
    `POST /api/permissions/sync-to-keycloak`.

## Critical User Journeys (CUJs)

These are PRD scenarios S1 to S6 seen from the API. Section 10 lists the automated check for
each one.

### CUJ 1: A console user gets a sidebar built from their roles (S1, B-21)

*   **Given**: a user holds two roles whose menus overlap, and both roles are synced.
*   **When**: cce-insights-ui calls `GET /admin/v1/me/menus` with the user's token.
*   **Then**: the response is the union of the active menus of both roles, as a tree ordered by
    `sortOrder`. `DETAIL` menus appear only under a granted parent. The call is allowed because
    every non-system role keeps the implicit `/me/menus` row (AD-16).

### CUJ 2: An administrator onboards a user in one call (S2, B-31, B-32)

*   **Given**: the roles to assign exist and their realm roles exist in Keycloak.
*   **When**: the administrator calls `POST /users` once.
*   **Then**: the Keycloak account, its realm roles, the local row, `user_roles` and one audit row
    all exist, and the user gets an email to set a password. If Keycloak fails part-way, the
    response is 502 with `partialState`, and the audit row names the Keycloak user id.

### CUJ 3: An administrator creates and configures a role (S3, B-40 to B-42)

*   **Given**: the name is unused.
*   **When**: the administrator creates the role, replaces its menus, then replaces its
    permissions.
*   **Then**: each call returns the saved state. A `DETAIL` without its parent returns a warning.
    Each permission change is followed by a gateway sync, and the role's realm role exists.

### CUJ 4: An administrator changes a user's roles (S4, B-35)

*   **Given**: a user holds some roles.
*   **When**: the administrator sends the complete new set with `PUT /users/:id/roles`.
*   **Then**: only the added and removed realm roles change in Keycloak, and one
    `USER_ROLES_UPDATED` row is written.

### CUJ 5: An administrator deactivates and re-activates a user (S5, B-34)

*   **Given**: an `ACTIVE` user who is not the last administrator.
*   **When**: the administrator sets `INACTIVE`, then later `ACTIVE`.
*   **Then**: the Keycloak account is disabled and its sessions end, the service refuses the
    user's token with 403, and `user_roles` stays. Re-activation enables the account, and the
    kept roles apply again.

### CUJ 6: A gateway sync fails after a permission save (S6, B-43)

*   **Given**: the gateway sync is failing.
*   **When**: the administrator saves permissions, then tries to assign the role.
*   **Then**: the save returns 502 with the rows kept and `syncStatus = FAILED`. The assignment
    returns 409 `ROLE_NOT_SYNCED`. After `POST /roles/:id/sync` succeeds, the assignment works.

--------------------------------------------------------------------------------

## Technical Plan (Architecture)

### 1. Components and data flow

```text
                         ┌──────────── Browser ────────────┐
                         │ cce-admin-ui      cce-insights-ui│
                         │ (:3002)           (:3001)        │
                         └──┬───────────────────────┬──────┘
               1. PKCE login│                       │ Bearer JWT
                            ▼                       ▼  /admin/v1/**
                    ┌──────────────┐        ┌──────────────────┐
                    │  Keycloak    │◄───────│ gateway-service  │ checks JWT, then the
                    │  realm cce   │ 3. sync│ (not changed)    │ caller's roles against
                    └──────▲───────┘ creates└───┬─────────▲────┘ api_permissions
                           │       realm roles  │ proxy   │ POST /api/permissions/
     Keycloak Admin REST   │                    ▼         │      sync-to-keycloak (2.)
     (users, role mapping, │            ┌──────────────────┴─┐
      action emails)       └────────────│ cce-admin-service   │ checks the JWT again,
                                        │ (:8085)             │ then the local user
                                        └─────────┬──────────┘
                                                  │ JPA read/write
                                                  ▼
                           ┌─────────────────────────────────────┐
                           │ PostgreSQL, database admin_service, │◄── gateway reads
                           │ schema public: 7 tables             │    api_permissions (R2DBC)
                           └─────────────────────────────────────┘
```

*   **Request path.** In shared environments, a console request goes to gateway-service, which
    checks the JWT, checks the caller's roles against `api_permissions`, and forwards
    `/admin/**` to the service. Locally there is no gateway: the console's Vite dev proxy
    forwards `/admin/v1/*` straight to `localhost:8085` (D-03), and the E2E checks call port 8085
    directly.
*   **What the service writes.** Its six own tables and `api_permissions`, through JPA in one
    database transaction per request. It also writes to Keycloak through the Keycloak Admin REST
    API, using a confidential client with a service account: it creates users, enables and
    disables them, adds and removes realm role mappings, and sends action emails.
*   **What the service does not do.** It never creates or deletes realm role *definitions*. After
    every change to `api_permissions` it calls the gateway's sync endpoint, and the gateway
    creates the realm roles (PRD §4).

### 2. Service structure

The package layout is fixed by PRD §6: `controller/ service/ repository/ entity/ security/
client/ audit/ config/`, under the base package `org.cce.admin`.

| Package | Holds | Rule |
|---|---|---|
| `controller` | REST controllers, request and response records (`controller/dto`), the `@RestControllerAdvice` that maps exceptions to error bodies | Shapes HTTP only. Calls exactly one service method per endpoint. No repository or client access. |
| `service` | One service class per resource (users, roles, role menus, role permissions, menus, current user), the domain exceptions | Owns transactions, validation that needs the database, the order of operations in each flow, and the audit call. |
| `repository` | Spring Data JPA repositories | Queries only. |
| `entity` | JPA entities for the seven tables | No business logic. |
| `security` | JWT decoder and validators, the roles converter, the filter that loads the local user, the 401 and 403 writers | Runs before any controller. |
| `client` | `KeycloakGateway` (interface and its `keycloak-admin-client` implementation) and `GatewaySyncClient` (interface and its HTTP implementation) | The only code that talks to Keycloak or gateway-service. Service tests mock both interfaces (PRD §9 Testability). |
| `audit` | `AuditWriter` and the action names | The only code that inserts into `audit_log`. |
| `config` | Typed configuration properties, Jackson, springdoc and Keycloak client beans | No logic. |

The service uses blocking Spring Web MVC (PRD §6). Every endpoint runs in one request thread.
The calls to Keycloak and the gateway are synchronous, with timeouts set in configuration.

### 3. Request pipeline and security (B-05, B-10 to B-13)

Every request passes these steps in order. A request that fails a step gets the response named
in that step, and later steps do not run.

1.  **Public paths.** `GET /admin/v1/health`, the OpenAPI document
    (`/admin/v1/api-docs/**`) and Swagger UI (`/admin/v1/swagger-ui/**`) need no token. Every
    other path needs one.
2.  **Token check (B-10).** Spring Security's OAuth2 resource server decodes the bearer token.
    It checks the signature against the realm's JWKS (`JWT_JWK_SET_URI`), the issuer against
    `JWT_ISSUER`, the audience against `JWT_AUDIENCE` (the token's `aud` must contain it), and
    expiry and not-before, with 30 seconds of allowed clock skew. A missing, malformed, wrongly
    signed, wrongly addressed or expired token gets 401 `UNAUTHENTICATED`, written in the error
    envelope by a custom entry point. Spring's default `WWW-Authenticate` header is kept.
3.  **Roles (B-11).** A converter reads the role names from the claim at
    `JWT_ROLES_CLAIM_PATH` (default `realm_access.roles`). The path is split on `.` and each part
    is looked up in the nested JSON objects of the token. If the claim is missing or is not a
    list of strings, the caller has no roles. The request does not fail.
4.  **Local user (B-12).** A filter that runs after the bearer-token filter looks up `users` by
    `keycloak_id` equal to the token's `sub`. If there is no row, or its `status` is not
    `ACTIVE`, the response is 403 `FORBIDDEN`. Otherwise the filter stores a request-scoped
    `CurrentUser`: the local user id, username, `keycloak_id`, the role names from step 3, and
    the client IP (see section 8).
5.  **Permission check (AD-17).** Skipped when `PERMISSION_CHECK_ENABLED` is `false`, and
    for the public paths of step 1. Otherwise the service takes the role names from step 3 and
    the rows of `api_permissions` whose `permission_name` is one of them. If no row matches the
    request's method and path, the response is 403 `FORBIDDEN` with the message
    "Your roles do not allow {METHOD} {path}." The rows come from an in-memory snapshot of the
    whole table. The snapshot is reloaded when it is older than `PERMISSION_CACHE_TTL`
    (default 30 seconds) and cleared after every transaction of this service that changes
    `api_permissions`.

    **`PermissionMatcher`** (package `org.cce.admin.security.matcher`, no framework imports)
    decides whether one row matches one request. It writes down the GW-3 assumption:
    *   The method matches when the row's `http_method` equals the request method. Both are
        upper case.
    *   The path is the request URI path without the query string, as received and not
        percent-decoded. One trailing `/` is removed, except from the root path `/`.
    *   The pattern and the path are split on `/` into segments, and matching is
        case-sensitive.
    *   A segment `**` matches zero or more whole path segments.
    *   Within any other segment, `*` matches zero or more characters, and every other
        character matches itself.
    *   A pattern without `*` must equal the path.
    *   Examples: `/admin/v1/**` matches `/admin/v1` and `/admin/v1/users/7/roles`.
        `/admin/v1/users/*` matches `/admin/v1/users/7`, but not `/admin/v1/users` or
        `/admin/v1/users/7/roles`.

    These rules agree with Spring's `AntPathMatcher` for every pattern that section 4.5 accepts.
    The same class is used by G9's offline row check and by the stand-in gateway.
6.  **Controller.** Request bodies and query parameters are validated with Bean Validation
    before the service runs (400 `VALIDATION_ERROR`).

**Configuration parity (PRD §9).** `JWT_ISSUER`, `JWT_AUDIENCE` and `JWT_ROLES_CLAIM_PATH` must
hold the same values as gateway-service's configuration for the same realm. Locally they are
`http://localhost:8180/auth/realms/cce`, `gateway-service` and `realm_access.roles` (section 9).
The audience `gateway-service` is the one the existing gateway checks for cce-insights-ui
([insights-ui architecture §3](../../context/cce-insights-ui/docs/architecture-overview.md)).

**No secrets in the repository (B-13).** Every credential and every environment-specific URL
comes from an environment variable. `application.yml` holds a default only where the default is
safe in any environment (for example the claim path). Where it is not safe, it holds no default,
and the service fails at startup if the variable is missing. Local values live in a git-ignored
`.env` file. A committed `.env.example` holds placeholders.

### 4. API contract

#### 4.1 Conventions (B-03, B-04)

*   **Base path and format.** Every endpoint is under `/admin/v1`. Requests and responses are
    `application/json` in UTF-8, with camelCase property names. A request body that contains a
    property the endpoint does not define is rejected with 400 `VALIDATION_ERROR`. This catches
    clients that drift from the contract.
*   **Identifiers.** User, role, menu and audit ids are UUID strings. Permission row ids are
    integers, because `api_permissions.id` is `BIGSERIAL` (DEV-1). An id in the path that is not
    a valid UUID gets 400 `VALIDATION_ERROR`, with the path parameter name as the field.
*   **Times.** Every timestamp is an ISO-8601 instant in UTC with a `Z` suffix, for example
    `2026-11-10T09:15:00.123Z`. Clients must accept from 0 to 6 fractional digits.
*   **Success envelope.** Every 200 or 201 response body is
    `{ "data": <payload>, "pagination"?: <Pagination>, "warnings"?: [<Warning>] }`.
    `pagination` appears only on paginated lists. `warnings` appears only when there is at least
    one warning. B-03 names `data` and `pagination` only. `warnings` is added because B-41
    requires a warning on a successful save and does not say where it goes.
*   **Warning.** `{ "code": string, "message": string, "menuId"?: uuid, "parentMenuId"?: uuid }`.
    A warning carries only the reference fields its code defines:

    | Warning code | Returned by | Reference fields |
    |---|---|---|
    | `DETAIL_WITHOUT_PARENT` | `PUT /roles/:id/menus`, one warning per `DETAIL` menu granted without its parent `NAV` (B-41) | `menuId`, `parentMenuId` |
    | `ACTION_EMAIL_NOT_SENT` | `POST /users`, when the account was created but the set-password email could not be sent (AD-08) | none (the user is in `data`) |
*   **Responses without the envelope.** `DELETE /roles/:id` and `DELETE /menus/:id` return 204
    with no body. `GET /admin/v1/health` returns `{ "status": "UP", "database": "UP" }` (or
    `"DOWN"`) without the envelope, as B-05 writes it, because infrastructure probes read it,
    not consoles. Every other success response uses the envelope.
*   **Error envelope.** Every error response body is
    `{ "error": { "code": string, "message": string, "details"?: [<FieldError>], "partialState"?: <PartialState> } }`.
    *   `message` is an English sentence for people. Clients branch on `code`, never on `message`.
    *   `details` appears with `VALIDATION_ERROR`, and with `ROLE_NOT_SYNCED` and
        `ROLE_NOT_ASSIGNABLE`, where there is one entry per role concerned
        (`field` = `roleIds[i]`). Each
        `FieldError` is
        `{ "field": string, "message": string }`. `field` is the path of the property in the
        request body, written as in the JSON (`email`, `roleIds[1]`, `permissions[2].uriPattern`),
        or the name of the query or path parameter (`pageSize`, `id`). A body that is not valid
        JSON gives one detail with `field` set to `body`.
    *   `partialState` appears only with `KEYCLOAK_SYNC_FAILED` (section 4.2).
*   **Concurrent edits (AS-REQ-21).** No resource has a version number or ETag, and no endpoint
    reads `If-Match`. When two administrators save the same resource, the later save wins. Every
    replace endpoint (`PUT`) writes the complete state it receives.

#### 4.2 Error codes

Every code in PRD Appendix B is produced. The codes marked *added* are not in Appendix B. They
cover cases that Appendix B leaves without a code, and they are raised with the team under PRD
§11 together with the deviations.

| HTTP | Code | When |
|---|---|---|
| 400 | `VALIDATION_ERROR` | A body, query or path value fails validation. Also: unknown body property, malformed JSON, unsupported content type, a body id (`roleIds`, `menuIds`, `parentId`) that names no existing row. |
| 401 | `UNAUTHENTICATED` | Missing, malformed, wrongly signed, wrongly addressed or expired token. |
| 403 | `FORBIDDEN` | No local user for `sub`, local user not `ACTIVE`, or the caller's token roles have no `api_permissions` row matching the method and path (section 3, step 5). |
| 404 | `USER_NOT_FOUND` | The user id in the path does not exist. |
| 404 | `ROLE_NOT_FOUND` | The role id in the path does not exist. |
| 404 | `MENU_NOT_FOUND` | The menu id in the path does not exist. |
| 404 | `NOT_FOUND` *(added)* | No endpoint matches the path. |
| 405 | `METHOD_NOT_ALLOWED` *(added)* | The path exists but not for this HTTP method. |
| 409 | `EMAIL_IN_USE` | Another user, locally or in Keycloak, has this email. |
| 409 | `USERNAME_IN_USE` *(added)* | Another user, locally or in Keycloak, has this username. Needed because username and email are separate fields (section 4.5). |
| 409 | `DUPLICATE_ROLE_NAME` | A role with this name exists. |
| 409 | `DUPLICATE_MENU_KEY` | A menu with this key exists. |
| 409 | `ROLE_IN_USE` | Delete of a role assigned to at least one user, whatever the user's status. |
| 409 | `SYSTEM_ROLE_IMMUTABLE` | `PUT /roles/:id`, `PUT /roles/:id/menus`, `PUT /roles/:id/permissions` or `DELETE /roles/:id` on an `is_system` role (AD-07). |
| 409 | `LAST_SYSTEM_ADMIN` *(added)* | A status change or role change that would leave no `ACTIVE` user holding `SUPER_ADMIN` (AD-07). |
| 409 | `ROLE_NOT_ASSIGNABLE` *(added)* | A role being assigned has `is_assignable = false`. `details` names the roles (AD-14). |
| 409 | `ROLE_NOT_SYNCED` *(added)* | A role being assigned has no realm role in Keycloak yet. `details` names the roles (AD-10). |
| 409 | `MENU_HAS_CHILDREN` *(added)* | Delete of a menu that still has child menus. |
| 502 | `KEYCLOAK_SYNC_FAILED` | A call to Keycloak or to the gateway sync failed after the request had already changed something, or the gateway sync failed. Carries `partialState`. |
| 500 | `INTERNAL_ERROR` *(added)* | Any other failure. The message is generic, and the stack trace is only logged. |

There is no code for an invalid status change, because every change between the three
statuses is allowed (AD-04). A status value outside the three gets 400 `VALIDATION_ERROR`.

**`PartialState` (AS-REQ-06, AS-REQ-18).**
`{ "operation": string, "entityId": uuid | null, "failedStep": string, "completedSteps": [string], "keycloakUserId": uuid | null, "auditLogId": uuid | null }`.
`entityId` is the id of the user or role concerned. A 502 on `POST /users` or `POST /roles` can
come after the local row is saved, so `entityId` is how the console finds the new resource.
`operation` names the flow that failed, so a console can choose its message and the screen to
show it on without parsing `message`:

| `operation` | Endpoint | Where the console shows it | What is saved, and the next step |
|---|---|---|---|
| `USER_CREATE` | `POST /users` | User create form | The Keycloak account may exist without a local row. An operator reconciles it from `audit_log`. |
| `USER_PROFILE_UPDATE` | `PUT /users/:id` | User edit form, profile section | See section 5 for which system holds the new values. |
| `USER_STATUS_CHANGE` | `PATCH /users/:id/status` | Users list row action, and user edit form | See section 5. |
| `USER_ROLES_UPDATE` | `PUT /users/:id/roles` | User edit form, roles section | Some realm role mappings may have changed. An operator reconciles them from `audit_log`. |
| `USER_PASSWORD_RESET` | `POST /users/:id/reset-password` | Users list row action, and user edit form | Nothing changed. Try again. |
| `ROLE_CREATE` | `POST /roles` | Roles list, inline create form | The role and its two implicit rows are saved but not synced (AD-16). Open the role and use Re-sync before assigning it. |
| `ROLE_PERMISSIONS_SAVE` | `PUT /roles/:id/permissions` | Role detail, Permissions tab | The rows are saved but not synced. Use Re-sync, and do not assign the role until a sync succeeds. |
| `ROLE_SYNC` | `POST /roles/:id/sync` | Role detail, Permissions tab | The rows were already saved. Try Re-sync again. |
| `ROLE_DELETE` | `DELETE /roles/:id` | Roles list, delete action | The role is deleted. Its realm role may stay in Keycloak until the next successful sync of any role clears it (AD-12). |

The step names for each flow are listed with the flow in section 5. `auditLogId` is the id of
the audit row that records the partial state for manual reconciliation (G8). For a failed
gateway sync, that row is the `ROLE_SYNC_REQUESTED` row, `failedStep` is `GATEWAY_SYNC`, and
`completedSteps` names the steps that saved the rows (AD-06). `keycloakUserId` is set when a
Keycloak user exists that the local database may not reflect.

A Keycloak or gateway call that fails *before* the request has changed anything is still
reported as 502 `KEYCLOAK_SYNC_FAILED`, because the caller cannot fix it by changing the
request. In that case `completedSteps` is empty, no audit row is written, and `auditLogId` is
`null`.

#### 4.3 Pagination (B-04, AS-REQ-02, AS-REQ-03)

*   **Query parameters.** `page` is 1-based, default 1, minimum 1. `pageSize` has a default of
    20, a minimum of 1 and a maximum of 100. A value outside these bounds, or one that is not an
    integer, gets 400 `VALIDATION_ERROR`. It is not clamped.
*   **Block.** `{ "page": int, "pageSize": int, "totalItems": int, "totalPages": int }`.
    `totalPages` is 0 when `totalItems` is 0.
*   **Past the last page.** The response is 200, with `data: []` and the true `totalItems` and
    `totalPages`.
*   **Which endpoints are paginated.** B-04 applies to the two top-level collections:
    `GET /users` and `GET /roles`. A client that needs every role calls
    `GET /roles?pageSize=100` and follows `page` up to `totalPages`. The sets that belong to one
    role (`GET /roles/:id/menus`, `GET /roles/:id/permissions`) are not paginated. They are the
    complete set that the matching `PUT` replaces, so a partial page would make replace
    semantics unsafe. `GET /menus` and `GET /me/menus` return the whole tree and are not
    paginated.
*   **Order.** `GET /users` is ordered by `username` ascending, and `GET /roles` by `name`
    ascending, with `id` as the tie-breaker, so pages are stable.

#### 4.4 Resources and field rules

All strings are trimmed before they are validated. Lengths are counted in characters after
trimming. An optional string that is empty after trimming is stored as null.

**User** (AS-REQ-04). The same shape is used in lists and in the detail view.

| Field | Type | Notes |
|---|---|---|
| `id` | uuid | |
| `keycloakId` | uuid | The Keycloak user id, equal to the token's `sub`. |
| `username` | string | 3 to 50 characters, pattern `^[a-z0-9][a-z0-9._-]*$`. Cannot be changed after creation. |
| `email` | string | Up to 254 characters, a valid address. Stored in lower case. Unique, ignoring case. |
| `firstName`, `lastName` | string | 1 to 100 characters each. |
| `status` | `ACTIVE`, `INACTIVE` or `LOCKED` | AD-04. |
| `facilityScope` | string or null | Up to 50 characters (AD-09). |
| `roles` | array of `{ "id": uuid, "name": string, "displayName": string }` | Ordered by `name`. |
| `lastLoginAt` | instant or null | Null until the user's first sign-in reaches the service (AD-05). |
| `createdAt`, `updatedAt` | instant | `updatedAt` changes on profile, status and role changes, not on sign-in. |

**Role** (AS-REQ-11).

| Field | Type | Notes |
|---|---|---|
| `id` | uuid | |
| `name` | string | 2 to 100 characters, pattern `^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$`. 100 is the limit of `api_permissions.permission_name`. Cannot be changed after creation (PRD §4). |
| `displayName` | string | 1 to 100 characters. |
| `description` | string or null | Up to 500 characters. |
| `isSystem` | boolean | AD-07. |
| `isAssignable` | boolean | AD-14. Consoles hide roles with `false` from role pickers and filters. |
| `syncStatus` | `NEVER`, `SYNCED` or `FAILED` | Outcome of the last gateway sync that marked this role (AD-06). A display hint only: the Keycloak check at assignment is authoritative (AD-10). |
| `lastSyncAt` | instant or null | |
| `userCount` | integer | Number of users holding the role, of any status. A role with `userCount > 0` cannot be deleted. |
| `createdAt`, `updatedAt` | instant | |

**Menu** (AS-REQ-13).

| Field | Type | Notes |
|---|---|---|
| `id` | uuid | |
| `key` | string | Up to 100 characters, pattern `^[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)*$`. Unique. Cannot be changed after creation. |
| `label` | string | 1 to 100 characters. |
| `path` | string | 1 to 255 characters, starts with `/`, made only of letters, digits and `/ _ - . :`. `:` marks a route parameter, as in `/compliance/patients/:id`. |
| `icon` | string or null | The name of a Heroicons 2 outline icon component, pattern `^[A-Z][A-Za-z0-9]*Icon$`, for example `ChartBarIcon`. The service checks the pattern only. A console maps the name to its component and shows a fallback icon for a name it does not know. |
| `type` | `NAV` or `DETAIL` | Cannot be changed after creation. |
| `parentId` | uuid or null | A `NAV` menu has no parent. A `DETAIL` menu must have a `NAV` parent. So the tree is at most two levels deep. |
| `sortOrder` | integer | 0 to 10000. Siblings are ordered by `sortOrder`, then `label`. |
| `isActive` | boolean | |
| `children` | array of Menu | Present in `GET /menus`, ordered as above. Empty for `DETAIL` menus. |

**Permission row** (AS-REQ-17).

| Field | Type | Notes |
|---|---|---|
| `id` | integer | `api_permissions.id`. Response only. Ids change on every save, because the save replaces the rows. |
| `httpMethod` | string | One of exactly `GET`, `POST`, `PUT`, `PATCH`, `DELETE`. This list is fixed by the contract and is not configurable, so a console can offer exactly these five (console review CQ-1). Accepted in any case and stored in upper case. No wildcard method. |
| `uriPattern` | string | 1 to 255 characters. Starts with `/`, has no empty segment (`//`), and is made only of letters, digits and `- . _ ~ / *`. `**` may only be a whole segment. Matching follows section 3, step 5. There is no prefix rule: patterns for other services, such as `/v1/insights/**`, are valid, because the gateway guards those too. |
| `description` | string or null | Up to 1000 characters. |
| `resourceCategory` | string or null | Up to 50 characters, free text. |
| `implicit` | boolean | Response only. `true` for the two rows every non-system role keeps (AD-16). |

**Me** (AS-REQ-20). `{ "id": uuid, "username": string, "roles": [string], "facilityScope": string | null }`.
`roles` holds the caller's token roles (section 3, step 3) that exist in `roles`, ordered by
name. Keycloak's own roles, such as `offline_access`, are left out. The token is the source,
as it is at the gateway and in step 5.

**Menu tree item** (for `GET /me/menus`).
`{ "id": uuid, "key": string, "label": string, "path": string, "icon": string | null, "type": "NAV" | "DETAIL", "sortOrder": int, "children": [item] }`.

#### 4.5 Endpoints

Every endpoint except health can also return 400 `VALIDATION_ERROR`, 401 `UNAUTHENTICATED`,
403 `FORBIDDEN` and 500 `INTERNAL_ERROR`. The table lists only the other codes. "No-op" means
the request asks for the state that already exists: the response is 200 with the current
resource, and nothing is written, audited or sent to Keycloak or the gateway.

| Method and path | Request | Success | Other errors |
|---|---|---|---|
| `GET /health` | None. No token. | 200 `{ "status": "UP", "database": "UP" or "DOWN" }`. `database` comes from `SELECT 1` with a 2-second timeout. B-05. | None. |
| `GET /me` | None. | 200, Me. B-20. | None. |
| `GET /me/menus` | None. | 200, `data`: array of menu tree items. B-21, rules below. | None. |
| `GET /users` | Query: `status`, `roleId` (uuid), `facilityScope` (exact, AD-09), `q`, `page`, `pageSize`. | 200, `data`: array of User, `pagination`. B-30. | None. |
| `POST /users` | `{ "username", "email", "firstName", "lastName", "facilityScope"?, "roleIds": [uuid] }`. `roleIds` has 1 to 20 distinct ids of existing roles. | 201, `Location: /admin/v1/users/{id}`, `data`: User. Maybe the warning `ACTION_EMAIL_NOT_SENT`. Flow 5.1. | 409 `USERNAME_IN_USE`, `EMAIL_IN_USE`, `ROLE_NOT_ASSIGNABLE`, `ROLE_NOT_SYNCED`. 502 (`USER_CREATE`). |
| `GET /users/:id` | None. | 200, User. | 404 `USER_NOT_FOUND`. |
| `PUT /users/:id` | `{ "email", "firstName", "lastName", "facilityScope" }`. All four are replaced, so a missing or null `facilityScope` clears it. | 200, User. No-op if nothing changed. Flow 5.2. B-33. | 404 `USER_NOT_FOUND`. 409 `EMAIL_IN_USE`. 502 (`USER_PROFILE_UPDATE`). |
| `PATCH /users/:id/status` | `{ "status": "ACTIVE" or "INACTIVE" or "LOCKED" }`. | 200, User. No-op if unchanged. Flow 5.3. B-34. | 404 `USER_NOT_FOUND`. 409 `LAST_SYSTEM_ADMIN`. 502 (`USER_STATUS_CHANGE`). |
| `PUT /users/:id/roles` | `{ "roleIds": [uuid] }`. 0 to 20 distinct ids of existing roles: the complete new set. | 200, User. No-op if the set is unchanged. Flow 5.4. B-35. | 404 `USER_NOT_FOUND`. 409 `ROLE_NOT_ASSIGNABLE`, `ROLE_NOT_SYNCED`, `LAST_SYSTEM_ADMIN`. 502 (`USER_ROLES_UPDATE`). |
| `POST /users/:id/reset-password` | No body. | 200, `data`: `{ "userId": uuid, "email": string }`, the address the email was sent to. Allowed for any status. An `INACTIVE` or `LOCKED` user can only use the link after being re-activated. Flow 5.5. B-36. | 404 `USER_NOT_FOUND`. 502 (`USER_PASSWORD_RESET`). |
| `GET /roles` | Query: `page`, `pageSize`. | 200, `data`: array of Role, `pagination`. | None. |
| `POST /roles` | `{ "name", "displayName", "description"? }`. | 201, `Location`, `data`: Role. Flow 5.6. B-40. | 409 `DUPLICATE_ROLE_NAME`, also when `api_permissions` already holds rows with that `permission_name` (GW-5), so a new role never takes over rows nobody owns. 502 (`ROLE_CREATE`). |
| `GET /roles/:id` | None. | 200, Role. | 404 `ROLE_NOT_FOUND`. |
| `PUT /roles/:id` | `{ "displayName", "description" }`. Sending `name` is an unknown property, so 400. | 200, Role. No-op if unchanged. B-40. | 404 `ROLE_NOT_FOUND`. 409 `SYSTEM_ROLE_IMMUTABLE`. |
| `DELETE /roles/:id` | None. | 204. Flow 5.9 (AD-12). | 404 `ROLE_NOT_FOUND`. 409 `SYSTEM_ROLE_IMMUTABLE`, `ROLE_IN_USE`. 502 (`ROLE_DELETE`). |
| `GET /roles/:id/menus` | None. | 200, `data`: `{ "roleId": uuid, "menuIds": [uuid] }`. Includes inactive menus that were granted (AS-REQ-15). | 404 `ROLE_NOT_FOUND`. |
| `PUT /roles/:id/menus` | `{ "menuIds": [uuid] }`. 0 to 500 distinct ids of existing menus. Inactive menus may be granted. | 200, same shape as GET. `warnings`: one `DETAIL_WITHOUT_PARENT` per `DETAIL` menu whose parent is not in the set, also returned on a no-op. Flow 5.11. B-41. | 404 `ROLE_NOT_FOUND`. 409 `SYSTEM_ROLE_IMMUTABLE`. |
| `GET /roles/:id/permissions` | None. | 200, `data`: `{ "roleId": uuid, "syncStatus", "lastSyncAt", "permissions": [Permission row] }`. Implicit rows come first, then explicit rows ordered by `uriPattern` and `httpMethod`. | 404 `ROLE_NOT_FOUND`. |
| `PUT /roles/:id/permissions` | `{ "permissions": [{ "httpMethod", "uriPattern", "description"?, "resourceCategory"? }] }`. 0 to 200 rows, which become the role's complete set of *explicit* rows. Rows equal in method and pattern to an implicit row are accepted and dropped (AD-16). Two rows with the same method and pattern give 400, with `field` `permissions[i].uriPattern` on the later one. | 200, same shape as GET, after the sync succeeded. No-op, with no sync, if the explicit rows are unchanged. Flow 5.7. B-42. | 404 `ROLE_NOT_FOUND`. 409 `SYSTEM_ROLE_IMMUTABLE`. 502 (`ROLE_PERMISSIONS_SAVE`): the rows are saved. The console calls GET again to show them (AS-REQ-18). |
| `POST /roles/:id/sync` | No body. Allowed for system roles. | 200, `data`: `{ "roleId": uuid, "syncStatus": "SYNCED", "lastSyncAt": instant }`. Flow 5.8. B-43. | 404 `ROLE_NOT_FOUND`. 502 (`ROLE_SYNC`). |
| `GET /menus` | None. | 200, `data`: array of top-level Menu with `children`. Includes inactive menus. B-44. | None. |
| `POST /menus` | `{ "key", "label", "path", "icon"?, "type", "parentId"?, "sortOrder", "isActive"? }`. `isActive` defaults to `true`. | 201, `Location`, `data`: Menu. | 409 `DUPLICATE_MENU_KEY`. A `NAV` with a parent, or a `DETAIL` without a `NAV` parent, gives 400. |
| `PUT /menus/:id` | `{ "label", "path", "icon", "parentId", "sortOrder", "isActive" }`. `key` and `type` are unknown properties here. Deactivating a menu is this call with `isActive: false` (AS-REQ-14). | 200, Menu. No-op if unchanged. | 404 `MENU_NOT_FOUND`. Parent rules as for POST give 400. |
| `DELETE /menus/:id` | None. | 204. Also deletes the menu's `role_menus` rows. B-44. | 404 `MENU_NOT_FOUND`. 409 `MENU_HAS_CHILDREN`. |

**Rules for `GET /me/menus` (B-21).**
1.  Take the caller's roles as in Me.
2.  Take the union of the menus granted to those roles, and keep only the active ones.
3.  Put the `NAV` menus at the top level. Put each `DETAIL` menu under its parent, but only when the parent is in the result too. A `DETAIL` whose parent is not granted, or not active, is left out. PRD §4 says a `DETAIL` "inherits its parent's visibility", and this is what the `DETAIL_WITHOUT_PARENT` warning tells administrators.
4.  Order siblings by `sortOrder`, then `label`.
5.  A caller with no matching roles gets `data: []`.

**Search on `GET /users` (B-30, AS-REQ-09).**
*   **What `q` matches:** a case-insensitive substring of `username`, `email`, `firstName`, `lastName`, or `firstName + " " + lastName`.
*   **Length:** 1 to 100 characters. An empty `q` is ignored, and there is no minimum length.
*   **Combining filters:** `status`, `roleId` and `facilityScope` must all match. A `roleId` that names no role returns an empty page, not 404. Unknown query parameters are ignored.

#### 4.6 Answers to the console design's requirements

| ID | Answer |
|---|---|
| AS-REQ-01 | `VALIDATION_ERROR` carries `details`: `[{ field, message }]`, with `field` written as the request body property path (4.1). Every code is in 4.2, including `USER_NOT_FOUND`, `ROLE_NOT_FOUND` and `MENU_NOT_FOUND`. |
| AS-REQ-02 | The block is `{ page, pageSize, totalItems, totalPages }`. `page` starts at 1. A page past the end returns 200 with `data: []` (4.3). |
| AS-REQ-03 | Only `GET /users` and `GET /roles` are paginated. For every role, call `GET /roles?pageSize=100` and follow `totalPages`. Role menus and role permissions are not paginated (4.3). |
| AS-REQ-04 | User shape in 4.4. `lastLoginAt` is a nullable ISO-8601 UTC instant (AD-05). Roles are `{ id, name, displayName }`. |
| AS-REQ-05 | `username` is separate from `email`. Required: `username`, `email`, `firstName`, `lastName`, and 1 to 20 `roleIds`. Optional: `facilityScope` (4.5). The new user receives a Keycloak email to set a password. If it could not be sent, the 201 carries `ACTION_EMAIL_NOT_SENT`, and the administrator can use reset-password (AD-08). |
| AS-REQ-06 | The 502 carries `partialState` with `operation` `USER_CREATE`, `entityId`, `failedStep`, `completedSteps`, `keycloakUserId` and `auditLogId` (4.2, flow 5.1). |
| AS-REQ-07 | The statuses are `ACTIVE`, `INACTIVE` and `LOCKED`, and every change between them is allowed. `LOCKED` is an administrator's temporary block. Both blocked statuses disable the Keycloak account and end its sessions. Asking for the current status is a no-op. There is no transition error. 409 `LAST_SYSTEM_ADMIN` protects the last administrator (AD-04, AD-07). |
| AS-REQ-08 | One optional free-text string of up to 50 characters. The filter matches exactly. There is no list endpoint (AD-09). |
| AS-REQ-09 | Search rules under 4.5. |
| AS-REQ-10 | `Role.syncStatus` and `Role.lastSyncAt` survive a reload (AD-06). Assigning a role whose realm role is missing gives 409 `ROLE_NOT_SYNCED`, with `details` naming the roles, and nothing changes (AD-10). |
| AS-REQ-11 | Role shape and name rule in 4.4. |
| AS-REQ-12 | For a system role, display fields, menus and permissions cannot be edited, and the role cannot be deleted. Sync and assignment are allowed, unless `isAssignable` is false (AD-07, AD-14). |
| AS-REQ-13 | Menu shape in 4.4. `icon` is a Heroicons 2 outline component name. `GET /menus` includes inactive menus. `DETAIL` menus sit only under a `NAV`, so the tree has at most two levels. |
| AS-REQ-14 | Deactivate with `PUT /menus/:id` and `isActive: false`. `DELETE` exists (B-44) and is refused while children exist. `key` and `type` cannot be changed. The create and update fields are in 4.5. |
| AS-REQ-15 | Inactive menus can be granted. `GET /roles/:id/menus` returns them. `GET /me/menus` leaves them out. |
| AS-REQ-16 | The request is `{ menuIds }`, and GET returns `{ roleId, menuIds }`. Warnings are in the top-level `warnings` array as `DETAIL_WITHOUT_PARENT` with `menuId` and `parentMenuId` (4.1). |
| AS-REQ-17 | Permission row shape and rules in 4.4 and 4.5. There are no wildcard methods. The pattern syntax follows section 3, step 5. Category is free text. A duplicate row is a 400 `VALIDATION_ERROR` on `permissions[i].uriPattern`. There is no prefix rule and no prefix warning. Implicit rows are flagged (AD-16). |
| AS-REQ-18 | Confirmed: 200 only when the save and the sync both succeed. 502 `KEYCLOAK_SYNC_FAILED` when the rows are saved but the sync failed. The 502 body does not include the rows, so the console refetches them (AD-06). |
| AS-REQ-19 | The success responses are in 4.5: sync returns 200 with `{ roleId, syncStatus, lastSyncAt }`, reset-password returns 200 with `{ userId, email }`, status returns 200 with the User, and role delete returns 204. |
| AS-REQ-20 | Me is `{ id, username, roles: [string], facilityScope }` (4.4). |
| AS-REQ-21 | There are no versions or ETags. The last write wins (4.1). |
| AS-REQ-22 | See section 9.3. In short: the Keycloak URL is `http://localhost:8180/auth`, set through `VITE_KEYCLOAK_URL` in the console's `.env` and not proxied; the realm is `cce`; the test administrator comes from AD-15; tokens from `cce-admin-ui` carry the audience `gateway-service`, which the service checks. |
| AS-REQ-23 | `PUT http://localhost:8090/_stub/mode` with body `fail` or `ok`, and `GET` on the same URL to read it. No restart is needed (AD-11, section 9.3). |
| AS-REQ-24 | Reset with `docker compose down -v`, then start again (9.3). The test administrator's credentials are in `CCE_TEST_ADMIN_USERNAME` and `CCE_TEST_ADMIN_PASSWORD`, from the git-ignored `.env` (AD-15). |

### 5. Flows and order of operations

These flows replace the PRD's flow diagrams. Each step that can leave a partial state has a
name, used in `partialState.failedStep` and `completedSteps`.

**Rules that apply to every flow:**
*   **No compensation.** No flow undoes an earlier Keycloak step after a later step fails (B-32, PRD §9).
*   **What a 502 means.** A Keycloak or gateway failure before the first named step completes gives 502 with `completedSteps: []` and no audit row. A failure after it gives 502 and one audit row with outcome `PARTIAL_FAILURE`, written in its own transaction. If that audit insert also fails, the service logs the full partial state at `ERROR` and returns `auditLogId: null`.
*   **Database writes.** Each database step is one transaction, and its audit row is written in the same transaction.
*   **Keycloak calls** use the timeouts `KEYCLOAK_CONNECT_TIMEOUT` (default 2 s) and `KEYCLOAK_READ_TIMEOUT` (default 10 s). A Keycloak 409 on create or update means a conflict. Any other non-2xx status, or a timeout, is a failure.
*   **The last-administrator lock.** `PATCH /users/:id/status` and `PUT /users/:id/roles` run inside one database transaction. It starts by locking the `SUPER_ADMIN` row in `roles` (`SELECT … FOR UPDATE`), so two administrators cannot remove each other at the same time. The Keycloak calls run while that transaction is open. Their local write commits at the end.

**5.1 Create user (`POST /users`, operation `USER_CREATE`).**
1.  Validate the body. Load the roles by id. An unknown id gives 400. A role with `is_assignable = false` gives 409 `ROLE_NOT_ASSIGNABLE`.
2.  Check that the username and the email (ignoring case) are free in `users`. Otherwise 409.
3.  Ask Keycloak whether the username or the email is already used by an account with no local row. Otherwise 409. Look up the realm role of every requested role. Any missing gives 409 `ROLE_NOT_SYNCED`.
4.  **`KEYCLOAK_USER_CREATED`**: create the Keycloak user, enabled, with `emailVerified = false` and the required action `UPDATE_PASSWORD`. A Keycloak 409 here (a race) is mapped to `USERNAME_IN_USE` or `EMAIL_IN_USE`, and nothing exists.
5.  **`KEYCLOAK_ROLES_ASSIGNED`**: add all the realm role mappings in one call.
6.  **`LOCAL_USER_SAVED`**: insert `users` (`ACTIVE`) and `user_roles`, and write `USER_CREATED`, in one transaction.
7.  Send the execute-actions email (`UPDATE_PASSWORD`, lifespan `KEYCLOAK_ACTION_EMAIL_LIFESPAN`, default 43200 s, with optional `KEYCLOAK_ACTION_EMAIL_CLIENT_ID` and `KEYCLOAK_ACTION_EMAIL_REDIRECT_URI`). A failure here adds the warning `ACTION_EMAIL_NOT_SENT` (AD-08).
8.  Return 201.

A failure in step 5 or 6 gives a 502 whose `keycloakUserId` is the new Keycloak user (B-32).

**5.2 Update profile (`PUT /users/:id`, `USER_PROFILE_UPDATE`).**
1.  Validate the body and load the user. A changed email must be free in `users`, ignoring case.
2.  If nothing changed, it is a no-op.
3.  **`KEYCLOAK_PROFILE_UPDATED`**, only when the email, first name or last name changed: update the Keycloak user. A changed email sets `emailVerified = false`. A Keycloak 409 gives `EMAIL_IN_USE`.
4.  **`LOCAL_PROFILE_SAVED`**: update `users` and write `USER_UPDATED`. If this fails after step 3, Keycloak holds the new values and the local row the old ones, and the audit row records both.

**5.3 Change status (`PATCH /users/:id/status`, `USER_STATUS_CHANGE`).**
1.  Load the user. If the status is unchanged, it is a no-op.
2.  Take the last-administrator lock. If the user is `ACTIVE`, holds `SUPER_ADMIN`, the new status is not `ACTIVE`, and no other `ACTIVE` user holds `SUPER_ADMIN`, return 409 `LAST_SYSTEM_ADMIN`.
3.  **`KEYCLOAK_ENABLED_CHANGED`**, only when Keycloak's `enabled` flag changes: set it to `true` for `ACTIVE` and `false` otherwise. A change between `INACTIVE` and `LOCKED` skips this step.
4.  **`KEYCLOAK_SESSIONS_ENDED`**, only when the new status is not `ACTIVE`: end all the user's Keycloak sessions.
5.  **`LOCAL_STATUS_SAVED`**: update `users.status` and write `USER_ACTIVATED`, `USER_DEACTIVATED` or `USER_LOCKED`. `user_roles` is never touched (B-34).

**5.4 Replace roles (`PUT /users/:id/roles`, `USER_ROLES_UPDATE`).**
1.  Load the user and the roles. Unknown ids give 400.
2.  Compute `added` and `removed` against the current `user_roles`. If both are empty, it is a no-op.
3.  For `added`, check `is_assignable` (409 `ROLE_NOT_ASSIGNABLE`) and look up each realm role (409 `ROLE_NOT_SYNCED`).
4.  Take the last-administrator lock. If `SUPER_ADMIN` is in `removed`, the user is `ACTIVE`, and no other `ACTIVE` user holds it, return 409 `LAST_SYSTEM_ADMIN`.
5.  **`KEYCLOAK_ROLES_ADDED`**, when `added` is not empty: add those mappings.
6.  **`KEYCLOAK_ROLES_REMOVED`**, when `removed` is not empty: remove those mappings. Skip any role whose realm role no longer exists (AD-10).
7.  **`LOCAL_ROLES_SAVED`**: replace the `user_roles` rows and write `USER_ROLES_UPDATED`, with the before and after role names and ids. Only changed realm roles are touched (B-35).

**5.5 Reset password (`POST /users/:id/reset-password`, `USER_PASSWORD_RESET`).**
1.  Load the user.
2.  **`KEYCLOAK_ACTION_EMAIL_SENT`**: send the execute-actions email (`UPDATE_PASSWORD`). A failure gives 502 with `completedSteps: []`.
3.  Write `USER_PASSWORD_RESET_REQUESTED`. No password passes through the service (B-36).

**5.6 Create role (`POST /roles`, `ROLE_CREATE`).**
1.  Validate the body. The name must be unused in `roles` and in `api_permissions.permission_name`. Otherwise 409 `DUPLICATE_ROLE_NAME`.
2.  **`ROLE_SAVED`**: in one transaction, insert the role (`is_system = false`, `is_assignable = true`, `sync_status = NEVER`) and its two implicit rows (AD-16), and write `ROLE_CREATED`.
3.  **`GATEWAY_SYNC`**: run the sync procedure (5.10).
4.  Return 201. If the sync failed, return 502 with `entityId` set to the new role.

**5.7 Replace permissions (`PUT /roles/:id/permissions`, `ROLE_PERMISSIONS_SAVE`).**
1.  Load the role. A system role gives 409.
2.  Upper-case the methods. Drop the rows that equal an implicit row. Reject duplicates.
3.  Compare with the stored explicit rows on (method, pattern, description, category). If they are unchanged, it is a no-op.
4.  **`PERMISSIONS_SAVED`**: in one transaction:
    *   delete the role's explicit rows,
    *   insert the new ones,
    *   insert either implicit row if it is missing,
    *   write `ROLE_PERMISSIONS_UPDATED`, with explicit and implicit rows listed separately.

    Then clear the permission snapshot (section 3, step 5).
5.  **`GATEWAY_SYNC`** (5.10).
6.  Return 200, or 502 with `completedSteps: ["PERMISSIONS_SAVED"]` (B-42).

**5.8 Manual sync (`POST /roles/:id/sync`, `ROLE_SYNC`).** Load the role, then run **`GATEWAY_SYNC`** (5.10). On failure the 502 has `completedSteps: []`, and `auditLogId` points to the `ROLE_SYNC_REQUESTED` row (B-43).

**5.9 Delete role (`DELETE /roles/:id`, `ROLE_DELETE`, AD-12).**
1.  Load the role. A system role gives 409 `SYSTEM_ROLE_IMMUTABLE`. A role in `user_roles` gives 409 `ROLE_IN_USE`. This check is in the service, and the foreign key is only a backstop (B-40).
2.  **`ROLE_DELETED`**: in one transaction, delete the role's `role_menus` and `api_permissions` rows and the role, and write `ROLE_DELETED`. Its before JSON holds the role, its menu ids and its permission rows. Then clear the permission snapshot.
3.  **`GATEWAY_SYNC`** (5.10). Every non-system role has implicit rows, so this always runs.
4.  Return 204, or 502 whose message says the realm role may remain until the next successful sync.

**5.10 Gateway sync procedure.** Used by 5.6 to 5.9 and by the bootstrapper.
1.  Get a service-account token (AD-13). It is cached until 30 seconds before it expires. Failing to get one counts as a failed sync.
2.  Send `POST {GATEWAY_BASE_URL}{GATEWAY_SYNC_PATH}` with no body (GW-1). Any 2xx is success. Anything else, or a timeout, is failure. The error summary is the HTTP status and the first 500 characters of the body, or the exception class and message.
3.  In one new transaction:
    *   On success, set `sync_status = SYNCED` and `last_sync_at = now()`. Under `GLOBAL` this applies to every role with at least one `api_permissions` row, and under `ROLE` only to the role that asked.
    *   On failure, set `FAILED` and `last_sync_at` on the role that asked, if it still exists.
    *   Write `ROLE_SYNC_REQUESTED` for the role that asked, with outcome `SUCCESS` or `FAILED`. Its after JSON holds `{ outcome, httpStatus, errorSummary, scope, markedSyncedRoleIds }` (AD-06).
4.  Return the outcome, and the audit row id for `partialState`.

**5.11 Replace menus (`PUT /roles/:id/menus`).**
1.  Load the role. A system role gives 409.
2.  Load the menus. Unknown ids give 400.
3.  Compute the `DETAIL_WITHOUT_PARENT` warnings.
4.  If the set is unchanged, it is a no-op, and the warnings are still returned.
5.  Otherwise, in one transaction, replace the `role_menus` rows and write `ROLE_MENUS_UPDATED`.

**5.12 Role display fields and the menu catalog.** `PUT /roles/:id`, `POST /menus`, `PUT /menus/:id` and `DELETE /menus/:id` are single database transactions that write `ROLE_UPDATED`, `MENU_CREATED`, `MENU_UPDATED` or `MENU_DELETED`. They make no Keycloak or gateway call. `MENU_DELETED`'s before JSON lists the ids of the roles that had the menu.

**5.13 Bootstrap.** Described in AD-15. It uses `KeycloakGateway` and 5.10, and writes its audit rows with actor `system:bootstrap`.

### 6. Data dictionary and seed (B-01, B-02)

Database `admin_service`, schema `public` (AD-01).
*   **Ids and timestamps.** Ids are `uuid DEFAULT gen_random_uuid()`. Timestamps are `timestamptz NOT NULL DEFAULT now()`, except in `api_permissions`, which keeps the gateway's `TIMESTAMP`.
*   **Who maintains `updated_at`.** JPA sets `updated_at` on every entity update. The only database trigger that does this is the one on `api_permissions`.
*   **Extension.** `pgcrypto` is enabled with `CREATE EXTENSION IF NOT EXISTS pgcrypto` (B-01).

**`users`**
| Column | Type | Rules |
|---|---|---|
| `id` | uuid | PK |
| `keycloak_id` | uuid | NOT NULL, unique `ux_users_keycloak_id` |
| `username` | varchar(50) | NOT NULL, unique `ux_users_username` |
| `email` | varchar(254) | NOT NULL, unique index `ux_users_email_lower` on `lower(email)` |
| `first_name`, `last_name` | varchar(100) | NOT NULL |
| `status` | varchar(10) | NOT NULL, CHECK in (`ACTIVE`, `INACTIVE`, `LOCKED`) |
| `facility_scope` | varchar(50) | NULL |
| `last_login_at` | timestamptz | NULL |
| `created_at`, `updated_at` | timestamptz | NOT NULL |

Other indexes: `ix_users_status`, `ix_users_facility_scope`.

**`roles`**
| Column | Type | Rules |
|---|---|---|
| `id` | uuid | PK |
| `name` | varchar(100) | NOT NULL, unique `ux_roles_name`, CHECK matches `^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$` |
| `display_name` | varchar(100) | NOT NULL |
| `description` | varchar(500) | NULL |
| `is_system` | boolean | NOT NULL DEFAULT false |
| `is_assignable` | boolean | NOT NULL, **no default** (AD-14) |
| `sync_status` | varchar(10) | NOT NULL DEFAULT `NEVER`, CHECK in (`NEVER`, `SYNCED`, `FAILED`) |
| `last_sync_at` | timestamptz | NULL |
| `created_at`, `updated_at` | timestamptz | NOT NULL |

**`menus`**
| Column | Type | Rules |
|---|---|---|
| `id` | uuid | PK |
| `key` | varchar(100) | NOT NULL, unique `ux_menus_key` |
| `label` | varchar(100) | NOT NULL |
| `path` | varchar(255) | NOT NULL |
| `icon` | varchar(100) | NULL |
| `type` | varchar(10) | NOT NULL, CHECK in (`NAV`, `DETAIL`) |
| `parent_id` | uuid | NULL, FK `menus(id)` ON DELETE RESTRICT, index `ix_menus_parent_id` |
| `sort_order` | integer | NOT NULL, CHECK between 0 and 10000 |
| `is_active` | boolean | NOT NULL DEFAULT true |
| `created_at`, `updated_at` | timestamptz | NOT NULL |

Table CHECK `ck_menus_parent`: (`type = 'NAV'` and `parent_id` is null) or (`type = 'DETAIL'` and `parent_id` is not null). That a `DETAIL`'s parent is itself a `NAV` is checked in the service.

**`role_menus`**: `id` uuid PK; `role_id` uuid NOT NULL FK `roles(id)` ON DELETE CASCADE; `menu_id` uuid NOT NULL FK `menus(id)` ON DELETE CASCADE; `created_at`. Unique `ux_role_menus_role_menu` (`role_id`, `menu_id`). Index `ix_role_menus_menu_id`. The two cascades are backstops. The service deletes these rows explicitly in 5.9 and on menu delete.

**`user_roles`**: `id` uuid PK; `user_id` uuid NOT NULL FK `users(id)` ON DELETE RESTRICT; `role_id` uuid NOT NULL FK `roles(id)` ON DELETE RESTRICT; `created_at`. Unique `ux_user_roles_user_role` (`user_id`, `role_id`). Index `ix_user_roles_role_id`. Join tables have their own UUID primary key, as B-01 asks for every table except `api_permissions` (DEV-1).

**`api_permissions`**: the gateway's DDL, exactly as in the [gateway migration doc](../../context/gateway-service/DATABASE_PERMISSIONS_MIGRATION.md):
*   **Columns:** `id BIGSERIAL PRIMARY KEY`, `permission_name VARCHAR(100) NOT NULL`, `http_method VARCHAR(10) NOT NULL`, `uri_pattern VARCHAR(255) NOT NULL`, `description TEXT`, `resource_category VARCHAR(50)`, `created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP`, `updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP`.
*   **Constraint:** `unique_permission_method_uri UNIQUE (permission_name, http_method, uri_pattern)`.
*   **Indexes:** `idx_permission_name` (`permission_name`) and `idx_resource_category` (`resource_category`).
*   **Trigger:** a `BEFORE UPDATE` trigger sets `updated_at`. Ours is `trg_api_permissions_updated_at`, calling the function `api_permissions_set_updated_at()`, and it is created only if the table has no `BEFORE UPDATE` trigger yet.

The gateway owns the shape, and this service creates it only if it is missing (AD-02).

**`audit_log`** (append-only)
| Column | Type | Rules |
|---|---|---|
| `id` | uuid | PK |
| `occurred_at` | timestamptz | NOT NULL DEFAULT now() |
| `actor_user_id` | uuid | NULL, no FK. Null for `system:bootstrap`. |
| `actor_keycloak_id` | varchar(64) | NULL. The token's `sub`. |
| `actor_username` | varchar(100) | NOT NULL. A snapshot of the username, or `system:bootstrap`. |
| `action` | varchar(50) | NOT NULL. One of the actions in section 8. |
| `entity_type` | varchar(20) | NOT NULL, CHECK in (`USER`, `ROLE`, `MENU`) |
| `entity_id` | uuid | NOT NULL |
| `outcome` | varchar(20) | NOT NULL, CHECK in (`SUCCESS`, `PARTIAL_FAILURE`, `FAILED`) |
| `before_state`, `after_state` | jsonb | NULL |
| `failure` | jsonb | NULL. On `PARTIAL_FAILURE`, `{ operation, failedStep, completedSteps, keycloakUserId, error }`. |
| `ip_address` | varchar(45) | NULL |

Indexes: `ix_audit_log_entity` (`entity_type`, `entity_id`, `occurred_at`), `ix_audit_log_occurred_at` and `ix_audit_log_actor_user_id`. The trigger `trg_audit_log_append_only` raises an exception on `UPDATE` or `DELETE`.

**Migrations** in `src/main/resources/db/migration` (PRD §6). Flyway runs with `baseline-on-migrate=true` and `baseline-version=0` (AD-02).
*   **`V1__schema.sql`**: `pgcrypto`, the six owned tables with their indexes and triggers, and `api_permissions` with `IF NOT EXISTS` on the table, the constraint (added in a `DO` block only if no unique constraint covers the three columns), both indexes and the trigger.
*   **`V2__seed.sql`**: the rows below. `api_permissions` rows are inserted with `ON CONFLICT ON CONSTRAINT unique_permission_method_uri DO NOTHING`.
*   **`V3__verify_api_permissions.sql`**: a `DO` block that reads `information_schema.columns`, `pg_constraint` and `pg_indexes`. It raises an exception, which stops the migration, unless all of these match the DDL above:
    *   each column's name, data type, length, nullability and default,
    *   a unique constraint on exactly (`permission_name`, `http_method`, `uri_pattern`), whatever its name,
    *   a non-unique index whose only column is `permission_name`, and one whose only column is `resource_category`.

**Seed menus** (B-02, AD-03). The ids are fixed UUIDs written in `V2__seed.sql`, and every row is active.

| key | label | path | icon | type | parent | sort |
|---|---|---|---|---|---|---|
| `dashboard` | Dashboard | `/` | `ChartBarIcon` | NAV | | 10 |
| `facilities` | Facilities | `/facilities` | `BuildingOffice2Icon` | NAV | | 20 |
| `compliance` | Compliance | `/compliance` | `ClipboardDocumentCheckIcon` | NAV | | 30 |
| `deviations` | Deviations | `/deviations` | `ExclamationTriangleIcon` | NAV | | 40 |
| `patients` | Patients | `/compliance/patients` | `UserGroupIcon` | NAV | | 50 |
| `events` | Events | `/events` | `SignalIcon` | NAV | | 60 |
| `ingestion` | Ingestion | `/ingestion` | `CogIcon` | NAV | | 70 |
| `practitioners` | Practitioners | `/practitioners` | `UserIcon` | NAV | | 80 |
| `intelligence` | Intelligence | `/intelligence` | `LightBulbIcon` | NAV | | 90 |
| `exports` | Exports | `/exports` | `ArrowDownTrayIcon` | NAV | | 100 |
| `compliance.protocol-detail` | Protocol analytics | `/compliance/protocols/:id` | | DETAIL | `compliance` | 10 |
| `patients.patient-detail` | Patient detail | `/compliance/patients/:id` | | DETAIL | `patients` | 10 |

The insights-ui docs list six icons for seven links and do not say which link uses which
([architecture §7](../../context/cce-insights-ui/docs/architecture-overview.md)). The icon
assignment above is the author's choice, because the inputs do not map icons to links. It is
easy to change: an administrator can change any icon with `PUT /menus/:id`, with no migration
and no release.

**Seed roles**:
*   **`SUPER_ADMIN`**: display "Super administrator", description "Platform administrator with full access to the admin API." `is_system = true`, `is_assignable = true`.
*   **`CCE_ADMIN_SERVICE`**: display "Admin service account", description "Service account of cce-admin-service. Calls the gateway sync." `is_system = true`, `is_assignable = false`.

Both start with `sync_status = NEVER` and are granted no menus.

**Seed `api_permissions`**:
*   `SUPER_ADMIN`: five rows, `GET`, `POST`, `PUT`, `PATCH` and `DELETE` on `/admin/v1/**`. Category `Admin`, description "Administer the CCE admin module".
*   `CCE_ADMIN_SERVICE`: one row, `POST` on `/api/permissions/sync-to-keycloak`. Category `Gateway`, description "Trigger the gateway's realm role sync".

**Unowned-rows review query** (GW-5, run read-only by an operator):
`SELECT DISTINCT p.permission_name FROM api_permissions p LEFT JOIN roles r ON r.name = p.permission_name WHERE r.id IS NULL ORDER BY 1;`

### 7. Clients

**`KeycloakGateway`** (`client/`). The service's only access to Keycloak. Its implementation uses
`keycloak-admin-client`. It authenticates with the client-credentials grant of
`KEYCLOAK_ADMIN_CLIENT_ID` / `KEYCLOAK_ADMIN_CLIENT_SECRET` against
`KEYCLOAK_SERVER_URL`, realm `KEYCLOAK_REALM`.

| Method | Purpose |
|---|---|
| `findRealmRole(name)` | Returns the role representation, or empty when the realm role does not exist. |
| `findUserIdByUsername(username)`, `findUserIdsByEmail(email)` | Exact lookups, for 5.1 step 3 and the bootstrapper. |
| `getUser(keycloakUserId)` | Returns `enabled`, `emailVerified` and the realm role names. Used by the bootstrapper. |
| `isEmailVerificationRequired()` | Reads the realm's `verifyEmail` setting. |
| `createUser(username, email, firstName, lastName, requiredActions)` | Returns the new id, read from the `Location` header. |
| `addRealmRoles(keycloakUserId, roles)`, `removeRealmRoles(keycloakUserId, roles)` | Add or remove realm role mappings. |
| `updateProfile(keycloakUserId, email, firstName, lastName, emailVerified)` | |
| `setEnabled(keycloakUserId, enabled)`, `logoutAllSessions(keycloakUserId)` | |
| `sendExecuteActionsEmail(keycloakUserId, actions)` | |

It raises two exceptions:
*   `KeycloakConflictException` for a 409 status.
*   `KeycloakCallFailedException` for any other failure. It carries the HTTP status if there was one, and a short message.

Least privilege: the service account holds only the `realm-management` client roles
`manage-users`, `view-users`, `query-users` and `view-realm`, plus the realm role
`CCE_ADMIN_SERVICE`. It cannot create or delete realm roles (PRD §4, §9).

**`GatewaySyncClient`** (`client/`). One method, `SyncResult sync()`, where `SyncResult` is
`{ boolean success, Integer httpStatus, String errorSummary }`. Its implementation is Spring's
`RestClient`, with the configured timeouts, a bearer token from `ServiceTokenProvider`, and no
request body. `ServiceTokenProvider` posts the client-credentials grant to
`{KEYCLOAK_SERVER_URL}/realms/{KEYCLOAK_REALM}/protocol/openid-connect/token` and caches the
token. There is one authentication mode (AD-13).

### 8. Audit (B-50)

`AuditWriter` (`audit/`) has two methods:
*   `record(entry)` joins the current transaction and fails if there is none.
*   `recordInNewTransaction(entry)` runs in its own transaction. It is used for `PARTIAL_FAILURE` rows and for `ROLE_SYNC_REQUESTED`.

The writer fills in the actor from `CurrentUser`. It takes the IP address from the first
`X-Forwarded-For` value when `AUDIT_TRUST_FORWARDED_FOR` is `true` (the default, because the
service is reached only through the gateway, PRD §9), and otherwise from the remote address.
The before and after JSON are the resource in its API shape (section 4.4). Audit rows never
hold tokens, secrets or passwords.

| Action | Entity | Written by | Before / after |
|---|---|---|---|
| `USER_CREATED` | USER | 5.1, bootstrap | null / User |
| `USER_UPDATED` | USER | 5.2 | User / User |
| `USER_ACTIVATED`, `USER_DEACTIVATED`, `USER_LOCKED` | USER | 5.3 | User / User |
| `USER_ROLES_UPDATED` | USER | 5.4, bootstrap | `{ roles }` / `{ roles }` |
| `USER_PASSWORD_RESET_REQUESTED` | USER | 5.5 | null / `{ email, actions }` |
| `ROLE_CREATED` | ROLE | 5.6 | null / Role and its implicit rows |
| `ROLE_UPDATED` | ROLE | 5.12 | Role / Role |
| `ROLE_DELETED` | ROLE | 5.9 | Role, `menuIds`, permission rows / null |
| `ROLE_MENUS_UPDATED` | ROLE | 5.11 | `{ menuIds }` / `{ menuIds }` |
| `ROLE_PERMISSIONS_UPDATED` | ROLE | 5.7 | `{ explicit, implicit }` / `{ explicit, implicit }` |
| `ROLE_SYNC_REQUESTED` | ROLE | 5.10 | null / `{ outcome, httpStatus, errorSummary, scope, markedSyncedRoleIds }` |
| `MENU_CREATED`, `MENU_UPDATED`, `MENU_DELETED` | MENU | 5.12 | Menu (and `roleIds` on delete) / Menu or null |

A partial failure writes the action of the flow it belongs to, with outcome `PARTIAL_FAILURE`
and the `failure` column filled. `ROLE_SYNC_REQUESTED` has outcome `SUCCESS` or `FAILED`.

### 9. Local environment (M0) and bootstrap

#### 9.1 Containers

`cce-admin-service/docker-compose.yml`. Every published port is bound to `127.0.0.1`. Every
image tag and dependency version comes from D-05 in
[author-decisions.md](../../docs/designs/author-decisions.md), and no tag floats.

| Service | Image or build | Host port | Notes |
|---|---|---|---|
| `postgres` | `postgres:16.15-alpine` | 5433 → 5432 | Database `admin_service`, user `admin_service`, password `${POSTGRES_PASSWORD}`. An init script creates a second database, `keycloak`. Volume `pgdata`. Health check `pg_isready`. Port 5433 avoids a Postgres already running on 5432. |
| `keycloak` | `quay.io/keycloak/keycloak:26.4.7` | 8180 → 8080 | Runs `start-dev --import-realm`. `KC_HTTP_RELATIVE_PATH=/auth`. `KC_HOSTNAME=http://localhost:8180/auth`, so the issuer is the same from the browser, the host and other containers. `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`, so containers can reach it at `http://keycloak:8080/auth`. Database `keycloak` on `postgres`. `KC_BOOTSTRAP_ADMIN_USERNAME=admin`, `KC_BOOTSTRAP_ADMIN_PASSWORD=${KEYCLOAK_ADMIN_PASSWORD}`. `KC_HEALTH_ENABLED=true`. Imports `keycloak/realm-cce.json`. |
| `mailpit` | `axllent/mailpit:v1.33.1` | 8025 (web UI and API) | SMTP on `mailpit:1025` inside the network. Captures mail, delivers none (AD-08). |
| `gateway-stub` | build `./gateway-stub` | 8090 | The stand-in gateway (AD-11). |
| `admin-service` | build `.` | 8085 | Only with `--profile app`. Uses the in-network URLs: `jdbc:postgresql://postgres:5432/admin_service`, `http://keycloak:8080/auth` and `http://gateway-stub:8090`. `JWT_ISSUER` stays `http://localhost:8180/auth/realms/cce`. |

#### 9.2 Realm `cce` (`keycloak/realm-cce.json`)

The file holds no credentials. Secrets and the test administrator's details are `${…}`
placeholders, which Keycloak fills from the container's environment when it imports the realm
(G1).

*   **Realm settings:**
    *   access token lifespan 300 seconds,
    *   `verifyEmail` false,
    *   `registrationAllowed` false,
    *   SMTP host `mailpit`, port 1025, from `noreply@cce.local`.

    The file defines no `clientScopes` list, so Keycloak creates its default scopes, including `basic` (for `auth_time`, AD-05) and `roles`.
*   **Realm roles:** `SUPER_ADMIN` and `CCE_ADMIN_SERVICE`, without the `cce-managed` attribute, so the stand-in never deletes them.
*   **Clients:** each client listed here as having the audience mapper gets an `oidc-audience-mapper` with `included.custom.audience = gateway-service`, added to access tokens. Every client's default client scopes are `basic`, `profile`, `email`, `roles`, `web-origins` and `acr`.

    | Client | Type | Settings |
    |---|---|---|
    | `cce-admin-ui` | Public | Standard flow, PKCE S256, redirect `http://localhost:3002/*`, web origin `http://localhost:3002`, post-logout redirect `http://localhost:3002/*`, audience mapper (D-03). |
    | `cce-insights-ui` | Public | As above, on port 3001. For S1 tokens. |
    | `cce-admin-service` | Confidential | Service account only, secret `${KEYCLOAK_ADMIN_CLIENT_SECRET}`, audience mapper. Service account roles as in section 7. |
    | `cce-gateway-stub` | Confidential | Service account only, secret `${GATEWAY_STUB_CLIENT_SECRET}`. `realm-management` roles `manage-realm` and `view-realm`, so it can create realm roles. Local realm only. |
    | `cce-e2e` | Public | Direct access grants only, audience mapper. Local realm only. Used by the API-level E2E checks to get tokens. |

*   **Users:**
    *   The test administrator: fixed id `00000000-0000-4000-8000-000000000001`, username `${CCE_TEST_ADMIN_USERNAME}`, email `test-admin@cce.local`, first name "Test", last name "Admin", enabled, email verified, password `${CCE_TEST_ADMIN_PASSWORD}` (not temporary), realm role `SUPER_ADMIN`.
    *   The service-account users of `cce-admin-service` (realm role `CCE_ADMIN_SERVICE`, AD-13) and `cce-gateway-stub`.

#### 9.3 Values, commands and reset

*   **`.env.example`** (committed, placeholders only). Copy it to `.env` (git-ignored) and fill it in.
    *   **Secrets and the test administrator:** `POSTGRES_PASSWORD`, `KEYCLOAK_ADMIN_PASSWORD`, `KEYCLOAK_ADMIN_CLIENT_SECRET`, `GATEWAY_STUB_CLIENT_SECRET`, `CCE_TEST_ADMIN_USERNAME`, `CCE_TEST_ADMIN_PASSWORD`, and `BOOTSTRAP_ADMIN_USERNAME=${CCE_TEST_ADMIN_USERNAME}`.
    *   **Host-run values for the service:** `DATABASE_URL=jdbc:postgresql://localhost:5433/admin_service`, `DATABASE_USERNAME=admin_service`, `DATABASE_PASSWORD=${POSTGRES_PASSWORD}`, `JWT_ISSUER=http://localhost:8180/auth/realms/cce`, `JWT_AUDIENCE=gateway-service`, `KEYCLOAK_SERVER_URL=http://localhost:8180/auth`, `KEYCLOAK_REALM=cce`, `GATEWAY_BASE_URL=http://localhost:8090`.

    The service imports `.env` with `spring.config.import=optional:file:.env[.properties]`, and compose reads it as its env file.
*   **Start:**
    *   `docker compose up -d` starts the infrastructure.
    *   Then either `mvn spring-boot:run` (service on the host) or `docker compose --profile app up -d --build` (service in a container).
    *   Swagger UI: `http://localhost:8085/admin/v1/swagger-ui`. Mail: `http://localhost:8025`. Keycloak account console (the M0 exit check): `http://localhost:8180/auth/realms/cce/account`.
*   **Console values (AS-REQ-22):**
    *   `VITE_KEYCLOAK_URL=http://localhost:8180/auth`, `VITE_KEYCLOAK_REALM=cce`, `VITE_KEYCLOAK_CLIENT_ID=cce-admin-ui` and `VITE_API_BASE_URL=` (empty), with the Vite proxy `/admin/v1` → `http://localhost:8085` (D-03).
    *   Keycloak is not proxied through Vite, because a proxied path would change the token issuer.
    *   Tokens from `cce-admin-ui` carry the audience `gateway-service`, which is the service's `JWT_AUDIENCE`.
*   **Stand-in switch (AS-REQ-23):** `curl -X PUT --data fail http://localhost:8090/_stub/mode`, and `--data ok` to restore it. `GET` on the same URL returns the current mode. The mode lives in memory and starts as `ok`.
*   **Reset (AS-REQ-24):** `docker compose --profile app down -v`, then start again. Removing the volume deletes both databases. On the next start, Keycloak imports the realm again, Flyway migrates again, and the bootstrapper creates the administrator's row again.

#### 9.4 Service configuration

| Variable | Default | Notes |
|---|---|---|
| `SERVER_PORT` | `8085` | |
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | none, required | |
| `JWT_ISSUER`, `JWT_AUDIENCE` | none, required | Must equal the gateway's values (PRD §9). |
| `JWT_JWK_SET_URI` | `${JWT_ISSUER}/protocol/openid-connect/certs` | Set to the in-network URL in compose. |
| `JWT_ROLES_CLAIM_PATH` | `realm_access.roles` | B-11. |
| `KEYCLOAK_SERVER_URL`, `KEYCLOAK_REALM`, `KEYCLOAK_ADMIN_CLIENT_SECRET` | none, required | |
| `KEYCLOAK_ADMIN_CLIENT_ID` | `cce-admin-service` | |
| `KEYCLOAK_CONNECT_TIMEOUT`, `KEYCLOAK_READ_TIMEOUT` | `2s`, `10s` | |
| `KEYCLOAK_ACTION_EMAIL_LIFESPAN` | `43200` | Seconds. |
| `KEYCLOAK_ACTION_EMAIL_CLIENT_ID`, `KEYCLOAK_ACTION_EMAIL_REDIRECT_URI` | empty | Where Keycloak sends the user after the action. |
| `GATEWAY_BASE_URL` | none, required | |
| `GATEWAY_SYNC_PATH` | `/api/permissions/sync-to-keycloak` | GW-1. |
| `GATEWAY_CONNECT_TIMEOUT`, `GATEWAY_READ_TIMEOUT` | `2s`, `10s` | |
| `GATEWAY_SYNC_SCOPE` | `GLOBAL` | `GLOBAL` or `ROLE` (AD-06). |
| `PERMISSION_CHECK_ENABLED` | `true` | AD-17. |
| `PERMISSION_CACHE_TTL` | `30s` | |
| `AUDIT_TRUST_FORWARDED_FOR` | `true` | |
| `BOOTSTRAP_ADMIN_USERNAME` | empty | AD-15. Unset it after the first start. |
| `SPRINGDOC_ENABLED` | `true` | Serves the OpenAPI document and Swagger UI under `/admin/v1`. |

#### 9.5 Bootstrap steps for a new shared environment

Do these in order before the first deploy, then unset the variable from step 7.
1.  Provide the database `admin_service`, and a database user that may create tables in `public` and run `CREATE EXTENSION pgcrypto`. Or have a DBA create the extension first.
2.  In the realm, create the confidential client `cce-admin-service`: service account on, `realm-management` roles `manage-users`, `view-users`, `query-users` and `view-realm`, and an audience mapper for `gateway-service`. Store its secret as `KEYCLOAK_ADMIN_CLIENT_SECRET`.
3.  Create the realm role `CCE_ADMIN_SERVICE` and map it to `service-account-cce-admin-service`, or have the gateway owners run one sync (AD-13).
4.  Create the public client `cce-admin-ui` (PKCE S256, the environment's redirect URI, audience mapper, default scope `basic`).
5.  Create the first administrator's Keycloak account: enabled, and with a verified email if the realm requires verification.
6.  Ask the gateway owners to route `/admin/**` to the service, and to restart or reload the gateway after the first migration has seeded `api_permissions` (GW-2).
7.  Deploy with every required variable and `BOOTSTRAP_ADMIN_USERNAME` set. Check the log for the bootstrap result. Unset the variable and redeploy.
8.  Run the unowned-rows query (section 6) and send the result to the gateway owners (GW-5).

### 10. Testing and end-to-end checks

| Layer | Tool | What it covers |
|---|---|---|
| Unit | JUnit 5, AssertJ | `PermissionMatcher` (a table of patterns and paths, including every example in section 3), the roles-claim reader, the `/me/menus` tree builder, the role diff in 5.4, the `DETAIL_WITHOUT_PARENT` rule, and the field validators. |
| Migration | Testcontainers Postgres 16 | **Empty database:** V1 to V3 apply, and every table, index, constraint and seed row from section 6 exists. **Gateway table already there:** apply the gateway's DDL and its sample rows first, then migrate. It succeeds and the samples stay. **Wrong shape:** an `api_permissions` with a different column type fails at V3. **`is_assignable`:** the column has no default. **Append-only:** `UPDATE` and `DELETE` on `audit_log` fail. |
| Service | Spring Boot test, Testcontainers Postgres, `KeycloakGateway` and `GatewaySyncClient` replaced by Mockito mocks (PRD §9) | Every flow in section 5, on the happy path and on each error code it can produce. Every named partial-failure step, including the PRD's case of a database insert failing after Keycloak succeeded (B3 notes). The exact `audit_log` rows for every mutating endpoint on the success and 502 paths: one per state change, two for a permission save, one for a manual sync (G8). No-ops write nothing. `LAST_SYSTEM_ADMIN` under two concurrent requests. The bootstrapper's five cases (AD-15). |
| Web | MockMvc with `spring-security-test` JWTs | 401 for each bad-token case (B-10). 403 for a missing local user, for a user who is not `ACTIVE` (B-12), and for no matching permission row. Step 5 turned off by its switch. The envelope, error and pagination shapes. 400 on unknown properties. `/health` without a token. The OpenAPI document lists every endpoint in 4.5 (B-06). |
| Offline row check (G9) | JUnit | For every row in the seed, the implicit rows, and the rows the E2E scenarios write: the calls it is meant to allow match through `PermissionMatcher`, and one call outside it does not. |
| Stand-in | JUnit, in `gateway-stub` | Token check (signature, `iss`, `aud`, `exp`, permission row), mode switch, and which roles are created or deleted, including that built-in roles, untagged roles and `SUPER_ADMIN` are never deleted. |
| E2E, API level (S1 to S6) | JUnit, tagged `e2e`, run by `mvn -Pe2e verify` against the running stack (`--profile app`) | See the list below. |

**E2E scenarios.** Names get a per-run suffix, so runs do not collide. The tests get tokens from
`cce-e2e` with the password grant. Before signing in as a user that a scenario created, the
test sets that user's password and clears its required actions through the Keycloak Admin API,
using the local master admin credentials from `.env`. Without that, Keycloak refuses the
password grant for an account that still has `UPDATE_PASSWORD` pending. This is test-only setup. The service itself never handles a password.
*   **S2:** create a user with two roles. Then check that the Keycloak account and its realm roles exist, the local row exists, `USER_CREATED` is written, and Mailpit holds the set-password email (`http://localhost:8025/api/v1/messages`).
*   **S3:** create a role. Its realm role now exists through the stand-in. Grant menus including a `DETAIL` without its parent, and check for the warning. Save permissions, and check for 200, `syncStatus = SYNCED` and two audit rows.
*   **S4:** replace a user's roles. Only the changed Keycloak mappings move.
*   **S5:** set a user `INACTIVE`. Keycloak shows the account disabled, `user_roles` is unchanged, and the user's token gets 403. Set `ACTIVE`, and access returns.
*   **S6:** set the stand-in to `fail`. Then:
    *   create a role and check for 502 `ROLE_CREATE`,
    *   save its permissions and check for 502 `ROLE_PERMISSIONS_SAVE`, with the rows saved and a `FAILED` audit row,
    *   assign the role and check for 409 `ROLE_NOT_SYNCED`.

    Set `ok`, re-sync and check for 200, then assign successfully.
*   **S1 and B5:** create two console roles with overlapping menus, and a user holding both. Sign in as that user, and check that `GET /me/menus` returns the merged tree.
*   **Live hard control:** the same console user calling `GET /users` gets 403 `FORBIDDEN`.

--------------------------------------------------------------------------------

## Alternatives Considered

There was no brainstorm phase, so these are the options weighed at this design's drafting
gates. Each one names the decision it lost to.

### Where the tables live (AD-01)

**Alternative A: all seven tables in a schema `admin_service`.**
*   **Pros:** follows PRD §6 to the letter, and keeps the module's tables apart from anything
    else in `public`.
*   **Cons:** the gateway reads `public` today. It would need a configuration change, such as
    the R2DBC schema option, in a deployment this team does not own.
*   **Reason for rejection:** Rejected by author: if the gateway change were missing, the
    gateway would load no permissions and block every call.

**Alternative B: six tables in `admin_service`, `api_permissions` in `public`.**
*   **Pros:** follows PRD §6 for the service's own tables, and leaves the gateway unchanged.
*   **Cons:** one module spread over two schemas, for its migrations, grants and backups.
*   **Reason for rejection:** Rejected by author: the split only serves PRD §6's wording, which
    PRD §5 and the gateway doc contradict.

### Who creates `api_permissions` (AD-02)

**Alternative A: the gateway's own script is the only creator, and the service only checks.**
*   **Pros:** the gateway team keeps sole ownership of its contract.
*   **Cons:** every new environment needs a manual psql step before the first deploy, and
    B-01 ("Flyway creates all seven tables") no longer holds.
*   **Reason for rejection:** Rejected by author: it makes every new environment depend on a
    manual step.

**Alternative B: Flyway owns the table outright.**
*   **Pros:** one owner and one history. B-01 holds as written.
*   **Cons:** existing environments must export, drop and re-enter their rows.
*   **Reason for rejection:** Rejected by author: between the drop and the re-entry, the gateway
    loads no permissions.

### The three routes outside today's sidebar (AD-03)

**Alternative A: seed them as inactive `NAV` menus.**
*   **Pros:** today's 7-link sidebar is unchanged, with no new type.
*   **Cons:** `is_active` gains two meanings, "retired" and "routed but hidden".
*   **Reason for rejection:** Rejected by author: under per-role menus, "granted means shown" is
    the one rule a console needs.

**Alternative B: a third menu type, `ROUTE`.**
*   **Pros:** matches today's behaviour exactly.
*   **Cons:** PRD §4 defines no such type, and every console must filter on it.
*   **Reason for rejection:** Rejected by author: hiding these pages belonged to the old,
    shared sidebar. An administrator now decides it per role.

### User statuses (AD-04)

**Alternative A: two statuses, `ACTIVE` and `INACTIVE`, in either direction.**
*   **Pros:** the smallest model.
*   **Cons:** drops Appendix A's `LOCKED`, and offboarding and a temporary block look the same.
*   **Reason for rejection:** Rejected by author: operators need to tell the two events apart.

**Alternative B: `INACTIVE` is terminal, and `LOCKED` is the reversible block.**
*   **Pros:** keeps PRD §2's wording.
*   **Cons:** contradicts B-34, S5 and F-10. An offboarding done by mistake cannot be undone.
*   **Reason for rejection:** Rejected by author: §2's "terminal" sits in the row that rules out
    hard delete, and is read as "used instead of deletion" (DEV-4).

### Recording the gateway sync result (AD-06)

**Alternative: the sync outcome only on the role (`sync_status`), with one audit row per
permission save.**
*   **Pros:** keeps "one audit row per call".
*   **Cons:** `audit_log` cannot show whether a past permission change ever reached the
    gateway.
*   **Reason for rejection:** Rejected by author: "is the saved permission actually in effect?"
    is exactly what an auditor asks (PRD §9, Auditability).

### When the set-password email fails (AD-08)

**Alternative: return 502 whenever the email fails, as for every other step after a change.**
*   **Pros:** one uniform rule (the original G5).
*   **Cons:** it reports a successful creation as a failure, for a step that changes no access
    and can be repeated.
*   **Reason for rejection:** Rejected by author: 201 with the warning `ACTION_EMAIL_NOT_SENT`,
    and reset-password sends it again.

### Undoing Keycloak steps after a later failure

**Alternative: compensate, for example by deleting the new Keycloak user when the database
insert fails.**
*   **Pros:** fewer partial states left for operators.
*   **Cons:** the compensation can fail too, and then leaves a partial state that is harder to
    explain.
*   **Reason for rejection:** The PRD decides this: partial failures are surfaced and recorded
    for reconciliation, not retried or undone (B-32, PRD §9 Consistency).

### Local stand-in for the gateway sync (AD-11)

**Alternative A: a local implementation inside the service, chosen by a Spring profile.**
*   **Pros:** no extra container.
*   **Cons:** the real HTTP client is never run locally, and the production artifact carries a
    failure switch.
*   **Reason for rejection:** Rejected by author: it copies the same assumed gateway behaviour
    as the stand-in, with these drawbacks added.

**Alternative B: WireMock, with realm roles created by the test setup.**
*   **Pros:** no custom server code.
*   **Cons:** S3's "sync creates the realm role" is never shown, and a role created in the
    console cannot be assigned without manual Keycloak steps.
*   **Reason for rejection:** Rejected by author: local demos and browser tests would hit
    `ROLE_NOT_SYNCED`.

### Authenticating to the sync endpoint (AD-13)

**Alternative A: forward the administrator's token.**
*   **Pros:** no extra role or credentials, and the gateway sees the person.
*   **Cons:** "allowed to save" and "allowed to sync" can drift apart between roles.
*   **Reason for rejection:** Rejected by author. Kept as the code-change fallback if the
    gateway owners require a person's token (GW-6).

**Alternative B: a shared secret in a header.**
*   **Pros:** the simplest client.
*   **Cons:** needs a gateway check we have no evidence of, plus a long-lived secret.
*   **Reason for rejection:** Rejected by author: it sits outside the JWT model the gateway uses.

### Keeping the service-account role from people (AD-14)

**Alternative A: the role name in configuration.**
*   **Pros:** no schema change.
*   **Cons:** depends on a setting matching a row in every environment.
*   **Reason for rejection:** Rejected by author.

**Alternative B: hide the role from the API.**
*   **Pros:** the console never sees it.
*   **Cons:** the service account's rights become visible only through SQL.
*   **Reason for rejection:** Rejected by author: the module exists to replace SQL.

### The first administrator (AD-15)

**Alternative A: a Flyway migration with placeholders.**
*   **Pros:** runs exactly once, with no startup code.
*   **Cons:** a wrong copied id cannot be fixed by running it again.
*   **Reason for rejection:** Rejected by author.

**Alternative B: an operator runbook with SQL.**
*   **Pros:** no service code.
*   **Cons:** hand-edited SQL, easy to run without the audit row.
*   **Reason for rejection:** Rejected by author: it is what the PRD replaces.

### The `/me/menus` permission for console roles (AD-16)

**Alternative A: explicit rows only, with a warning when the row is missing.**
*   **Pros:** exact replace semantics.
*   **Cons:** the warning can be ignored, and a role with no rows never gets one.
*   **Reason for rejection:** Rejected by author.

**Alternative B: a shared seeded role, `CONSOLE_USER`.**
*   **Pros:** one row in one place.
*   **Cons:** every user needs one more role, and nothing enforces it.
*   **Reason for rejection:** Rejected by author: it moves the same failure from roles to users.

### The service's own permission check (AD-17)

**Alternative A: no permission check in the service.**
*   **Pros:** matches B-10 to B-12 literally, and has one place for rules.
*   **Cons:** any active user can administer when the gateway is bypassed, which includes
    every local run.
*   **Reason for rejection:** Rejected by author: defence in depth (PRD §9).

**Alternative B: a coarse `ADMIN_ROLES` list in configuration.**
*   **Pros:** simple, and independent of GW-3.
*   **Cons:** drifts from the data as soon as an administrator creates a narrower admin role.
*   **Reason for rejection:** Rejected by author.

--------------------------------------------------------------------------------

## Non-Functional Requirements

*   **Security.**
    *   The service checks the JWT, the local user's standing and the caller's permission rows
        itself (section 3).
    *   The Keycloak service account has least privilege, and cannot create realm roles.
    *   No secret is in the repository: the realm import uses placeholders, and `.env` is
        git-ignored.
    *   Audit rows hold no tokens or passwords.
    *   Every local port is bound to `127.0.0.1`.
*   **Performance.** The service is low-throughput admin CRUD (PRD §6). The permission check
    reads an in-memory snapshot of `api_permissions`, so it adds no query per request. The
    `last_login_at` update adds one conditional `UPDATE` per request. It changes a row only on
    the first request of a sign-in.
*   **Rollout and rollback.**
    *   The migrations only add tables and rows, and `api_permissions` is created only if it is
        missing.
    *   Rolling the service back leaves the tables in place, and the gateway keeps reading
        `api_permissions` unchanged.
    *   `PERMISSION_CHECK_ENABLED=false` turns off the service's own permission check without a
        release.

### Risks accepted by the author

| Risk | Effect | Why accepted |
|---|---|---|
| **The last-administrator lock can stall user changes.** Status and role changes hold a database transaction, with the `SUPER_ADMIN` row locked, across their Keycloak calls (section 5). | With Keycloak slow or timing out (up to 10 s per call), every other status or role change waits behind the first one. | This is low-traffic admin work, and the lock is what makes `LAST_SYSTEM_ADMIN` safe when two requests arrive together. |
| **Permission caches on other instances lag.** Each instance holds its own snapshot of `api_permissions` (section 3, step 5). | With several instances, a saved permission takes up to `PERMISSION_CACHE_TTL` (default 30 s) to take effect on the instances that did not save it. | Fail-closed or fail-open lasts for at most 30 seconds, and the TTL can be lowered in configuration. The gateway's own reload behaviour (GW-2) is likely the slower of the two anyway. |
| **The E2E tests use the Keycloak master admin to set passwords.** This is a test-only shortcut (section 10). | The tests do not exercise the set-password email link. They only check that the email arrived in Mailpit. | The service itself never handles passwords (B-36). Following the email link would need browser automation, which these API-level checks do not have. |
| **Gateway behaviour is assumed** (GW-1 to GW-6). | Locally everything passes against the stand-in, and the first shared environment can still differ. | Each assumption is written down, sent to the gateway owners, and covered by configuration where that is reasonable (G10). |

--------------------------------------------------------------------------------

## Detailed Implementation Plan

### How to use this plan

*   **One phase per run.** A coding agent builds one phase at a time. Each phase starts from
    the previous phase's committed result and ends when its exit check passes.
*   **Every phase leaves the build green.** From phase 1 on, `./mvnw verify` passes at the end
    of every phase. Phase 8, the API-level E2E, runs last, against the full stack.
*   **No design decisions are left to the implementer.** If something here seems to need one,
    or the design looks wrong, stop and raise it as a change to this document. Do not choose
    on your own.
*   **Paths.** All paths are relative to `cce-admin-service/`, at the root of this folder.
    `{main}` is `src/main/java/org/cce/admin` and `{test}` is `src/test/java/org/cce/admin`.
    Every Java file is in the package that its folder names.
*   **Versions.** Every version and image tag comes from D-05
    ([author-decisions.md](../../docs/designs/author-decisions.md)). The service's
    dependencies take their versions from `spring-boot-starter-parent` 3.5.16, except
    springdoc 2.10.0 and `keycloak-admin-client` 26.0.12, which are written explicitly. The
    wrapper is generated with `maven-wrapper-plugin` 3.3.4 (D-05). The stand-in's `pom.xml` also
    uses `spring-boot-starter-parent` 3.5.16, but only for plugin and library versions. It has
    no Spring dependency at runtime.
*   **Fixed coordinates.** groupId `org.cce`, artifactId `cce-admin-service`, version
    `0.1.0-SNAPSHOT`. The stand-in gateway is `org.cce:cce-gateway-stub`, Java package
    `org.cce.gatewaystub`.
*   **Never commit** `.env`, real secrets, or Keycloak exports (PRD §11). The root
    `.gitignore` already ignores `.env` and `target/`.

### Phase 1: Local environment (M0) and project skeleton

**Closes:** M0, and B-13 for the local environment.

| File | Action | What and why |
|---|---|---|
| `.mvn/wrapper/maven-wrapper.properties`, `mvnw`, `mvnw.cmd` | Create | Generated with `mvn -N org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper -Dmaven=3.9.16 -Dtype=only-script`. The wrapper pins Maven 3.9.16 (D-05). The machine's Maven 3.8.7 is used only for this one command. |
| `pom.xml` | Create | Parent `spring-boot-starter-parent` 3.5.16, `java.version` 21. **Dependencies:** `spring-boot-starter-web`, `-validation`, `-data-jpa`, `-oauth2-resource-server`; `flyway-core`; `flyway-database-postgresql`; `postgresql` (runtime); `springdoc-openapi-starter-webmvc-ui` 2.10.0; `keycloak-admin-client` 26.0.12. **Test dependencies:** `spring-boot-starter-test`, `spring-security-test`, `spring-boot-testcontainers`, `org.testcontainers:postgresql`, `org.testcontainers:junit-jupiter`. **Build:** `spring-boot-maven-plugin`, and Surefire excluding `**/*E2E.java`. All dependencies are added now, so later phases do not edit the dependency list. |
| `{main}/CceAdminServiceApplication.java` | Create | `@SpringBootApplication` entry point. |
| `src/main/resources/application.yml` | Create | `spring.application.name`, `server.port: ${SERVER_PORT:8085}`, and `spring.config.import: optional:file:.env[.properties]` (section 9.3). Later phases extend it. |
| `.env.example` | Create | Every variable in section 9.3, with placeholder values such as `change-me`. Committed. |
| `docker-compose.yml` | Create | Services `postgres`, `keycloak` and `mailpit`, exactly as in section 9.1. Images from D-05, ports bound to `127.0.0.1`, `env_file: .env`, health checks (`pg_isready`, and Keycloak `GET /auth/health/ready` on management port 9000), volume `pgdata`. `gateway-stub` is added in phase 4, and `admin-service` in phase 7. |
| `docker/postgres/init/01-create-keycloak-db.sql` | Create | `CREATE DATABASE keycloak OWNER admin_service;`. Mounted into `/docker-entrypoint-initdb.d`. |
| `keycloak/realm-cce.json` | Create | The realm in section 9.2: realm settings, the realm roles `SUPER_ADMIN` and `CCE_ADMIN_SERVICE`, the five clients with their audience mappers and default scopes, the test administrator, and both service-account users with their roles. Secrets and the test administrator's details are `${…}` placeholders only. No `clientScopes` key. |
| `README.md` | Create | Local setup: prerequisites, copy `.env.example`, `docker compose up -d --wait`, the URLs in section 9.3, and the reset command. |

**Exit check:**
1.  `cp .env.example .env`, then set every placeholder to a non-empty local value.
2.  `docker compose up -d --wait` reports `postgres`, `keycloak` and `mailpit` healthy.
3.  Get a test administrator token:
    `curl -s -d grant_type=password -d client_id=cce-e2e -d "username=$CCE_TEST_ADMIN_USERNAME" -d "password=$CCE_TEST_ADMIN_PASSWORD" http://localhost:8180/auth/realms/cce/protocol/openid-connect/token`.
    It returns an `access_token` whose payload has all of:
    *   `iss` = `http://localhost:8180/auth/realms/cce`,
    *   `aud` containing `gateway-service`,
    *   `realm_access.roles` containing `SUPER_ADMIN`,
    *   an `auth_time`.
4.  A client-credentials token for `cce-admin-service` has `aud` containing `gateway-service`, and `realm_access.roles` containing `CCE_ADMIN_SERVICE`.
5.  The M0 demo: sign in to `http://localhost:8180/auth/realms/cce/account` as the test administrator.
6.  `./mvnw verify` passes.
7.  `git status --porcelain` lists no `.env`, and `git grep` finds none of the secret values from `.env`.

### Phase 2: Schema, seed and persistence (B-01, B-02)

**Closes:** B-01, B-02.

| File | Action | What and why |
|---|---|---|
| `src/main/resources/db/migration/V1__schema.sql` | Create | Everything in section 6 except the seed: `pgcrypto`, the six owned tables with their constraints, indexes and the append-only trigger, and `api_permissions` with `IF NOT EXISTS` plus the conditional constraint, indexes and trigger (AD-02). |
| `src/main/resources/db/migration/V2__seed.sql` | Create | The 12 menus with fixed UUIDs, the two system roles with explicit `is_assignable`, and the six `api_permissions` rows with `ON CONFLICT … DO NOTHING` (section 6). |
| `src/main/resources/db/migration/V3__verify_api_permissions.sql` | Create | The shape check in section 6. It raises an exception on any mismatch. |
| `src/main/resources/application.yml` | Modify | Datasource from `DATABASE_URL`, `DATABASE_USERNAME` and `DATABASE_PASSWORD`. `spring.flyway.baseline-on-migrate: true`, `baseline-version: 0`. `spring.jpa.hibernate.ddl-auto: validate`. `spring.jpa.open-in-view: false`. |
| `{main}/entity/UserEntity.java`, `RoleEntity.java`, `MenuEntity.java`, `RoleMenuEntity.java`, `UserRoleEntity.java`, `ApiPermissionEntity.java`, `AuditLogEntity.java` | Create | One JPA entity per table, with the columns in section 6. `@PreUpdate` sets `updated_at` on the owned tables. `ApiPermissionEntity` maps the `TIMESTAMP` columns as `LocalDateTime`, with `insertable = false, updatable = false`, so the database defaults and trigger apply. The `AuditLogEntity` JSON columns use `@JdbcTypeCode(SqlTypes.JSON)` with `JsonNode`. |
| `{main}/entity/UserStatus.java`, `MenuType.java`, `SyncStatus.java`, `AuditOutcome.java`, `AuditEntityType.java` | Create | The enums behind the CHECK constraints, stored as strings. |
| `{main}/repository/UserRepository.java` | Create | `findByKeycloakId`, `existsByUsername`, `existsByEmailIgnoreCase`, `JpaSpecificationExecutor` for search, `updateLastLoginIfLater(userId, authTime)` (a native conditional `UPDATE`, AD-05), and `countActiveUsersWithRoleExcluding(roleName, userId)`. |
| `{main}/repository/RoleRepository.java` | Create | `findByName`, `existsByName`, and `findByNameForUpdate` (`@Lock(PESSIMISTIC_WRITE)`, for the last-administrator lock). |
| `{main}/repository/MenuRepository.java`, `RoleMenuRepository.java`, `UserRoleRepository.java`, `ApiPermissionRepository.java`, `AuditLogRepository.java` | Create | The queries sections 5 and 6 need: menus by parent; role menus by role and by menu, plus the active menus for a set of role names; user roles by user, and a count by role; permission rows by `permission_name`, a delete by `permission_name`, the existence of a name, all rows (for the snapshot), and the names that have rows (for the `GLOBAL` scope); audit rows by entity. |
| `{test}/support/PostgresTestContainer.java` | Create | One shared `postgres:16.15-alpine` container with `@ServiceConnection`, reused by every database test. |
| `src/test/resources/gateway/gateway-api-permissions.sql` | Create | The gateway doc's DDL, with its trigger and its two sample `INSERT`s, copied from the gateway migration doc. |
| `src/test/resources/gateway/mismatched-api-permissions.sql` | Create | The same table with `uri_pattern VARCHAR(200)`, for the negative shape test. |
| `{test}/migration/MigrationEmptyDatabaseTest.java` | Create | Migrates an empty database. Asserts every table, column, index, constraint and trigger in section 6, the 12 menus, the two roles and the six permission rows (B-01, B-02). Asserts that `roles.is_assignable` has no default (AD-14). |
| `{test}/migration/MigrationExistingGatewayTableTest.java` | Create | On a new database, runs `gateway-api-permissions.sql` and then Flyway. Migration succeeds, the gateway's sample rows remain, and the seed rows are added (AD-02). |
| `{test}/migration/MigrationShapeMismatchTest.java` | Create | Runs `mismatched-api-permissions.sql`, then Flyway, and asserts that V3 fails. |
| `{test}/repository/RepositoryQueriesTest.java` | Create | The custom queries, including that `updateLastLoginIfLater` only moves forward and leaves `updated_at` alone, and that `UPDATE` and `DELETE` on `audit_log` fail. |

**Exit check:** `./mvnw verify` passes.

### Phase 3: Web foundation, security, audit writer, `/health` and `/me`

**Closes:** B-03, B-04, B-05, B-06 (for the endpoints so far), B-10, B-11, B-12, B-13, B-20.

| File | Action | What and why |
|---|---|---|
| `src/main/resources/application.yml` | Modify | Every variable in section 9.4, bound to `admin.*` properties. Springdoc paths `/admin/v1/api-docs` and `/admin/v1/swagger-ui`, switched by `SPRINGDOC_ENABLED`. Jackson writes instants in ISO-8601. |
| `{main}/config/AdminProperties.java` | Create | A `@ConfigurationProperties("admin")` record with nested `jwt`, `keycloak`, `gateway`, `permission`, `audit` and `bootstrap` groups, validated at startup. A missing required value stops startup (B-13). |
| `{main}/config/JacksonConfig.java` | Create | Turns on `FAIL_ON_UNKNOWN_PROPERTIES` and ISO-8601 dates (section 4.1). |
| `{main}/config/OpenApiConfig.java` | Create | The OpenAPI title and version, and the bearer security scheme (B-06). |
| `{main}/security/SecurityConfig.java` | Create | The filter chain in section 3: stateless, CSRF off, public paths, the resource server with a `JwtDecoder` built from `JWT_JWK_SET_URI` and validators for issuer, `AudienceValidator` and timestamps (30 s skew), then `CurrentUserFilter` and `PermissionCheckFilter` after the bearer filter. |
| `{main}/security/AudienceValidator.java` | Create | Rejects a token whose `aud` does not contain `JWT_AUDIENCE` (B-10). |
| `{main}/security/RolesClaimReader.java` | Create | Reads role names along the dotted `JWT_ROLES_CLAIM_PATH`. A missing or malformed claim gives no roles (B-11). |
| `{main}/security/CurrentUser.java`, `CurrentUserFilter.java` | Create | Step 4: load the user by `sub`, refuse with 403 if missing or not `ACTIVE` (B-12), run `updateLastLoginIfLater` from `auth_time` (AD-05), and store `CurrentUser` as a request attribute. |
| `{main}/security/PermissionSnapshot.java` | Create | The in-memory copy of `api_permissions` with a TTL, and `invalidate()` (step 5). |
| `{main}/security/matcher/PermissionMatcher.java` | Create | The rules in step 5. This package holds no other class and imports nothing outside the JDK, so the stand-in can compile it (AD-17). |
| `{main}/security/PermissionCheckFilter.java` | Create | Step 5. Skips public paths and does nothing when the check is turned off. Logs the setting at startup, with a warning when it is off (AD-17). |
| `{main}/security/SecurityErrorWriter.java` | Create | Writes the 401 `UNAUTHENTICATED` and 403 `FORBIDDEN` error envelopes from inside the filter chain (it serves as the authentication entry point and the access-denied handler). |
| `{main}/security/ClientIpResolver.java` | Create | Finds the client IP for audit, from `X-Forwarded-For` or the remote address (section 8). |
| `{main}/service/exception/ErrorCode.java` | Create | Every code in section 4.2, with its HTTP status. |
| `{main}/service/exception/ApiException.java`, `PartialState.java`, `KeycloakSyncFailedException.java` | Create | The base exception (code, message, optional `details`), the `PartialState` record (section 4.2), and the 502 exception that carries it. |
| `{main}/controller/ApiExceptionHandler.java` | Create | `@RestControllerAdvice`. Maps `ApiException`, bean-validation errors, unreadable JSON, unknown properties, bad path UUIDs, 404 and 405 and any other exception to the error envelope (sections 4.1, 4.2). |
| `{main}/controller/PageParams.java` | Create | Validates `page` and `pageSize` (section 4.3). |
| `{main}/controller/dto/CommonDtos.java` | Create | Nested records: `ApiResponse<T>` (with `data`, `pagination` and `warnings`), `Pagination`, `Warning`, `ErrorBody`, `FieldErrorDto` and `PartialStateDto`. Null fields are left out of the JSON. |
| `{main}/controller/dto/MeDtos.java` | Create | Nested records `Me` and `MenuTreeItem` (section 4.4). |
| `{main}/controller/HealthController.java` | Create | `GET /admin/v1/health` (B-05). Runs `SELECT 1` with a 2-second timeout and returns the bare shape in section 4.1. |
| `{main}/controller/MeController.java`, `{main}/service/CurrentUserService.java` | Create | `GET /admin/v1/me` (B-20). Roles are the token roles that exist in `roles`. `/me/menus` is added in phase 7. |
| `{main}/audit/AuditAction.java`, `AuditEntry.java`, `AuditWriter.java` | Create | The actions in section 8, an entry record, and `record` / `recordInNewTransaction`. |
| `Dockerfile` | Create | Multi-stage. Build on `maven:3.9.16-eclipse-temurin-21` with `mvn -B -DskipTests package`. Run on `eclipse-temurin:21.0.13_9-jre-alpine` as a non-root user, `EXPOSE 8085`, with a `HEALTHCHECK` that runs `wget -qO- http://localhost:8085/admin/v1/health`. |
| `.dockerignore` | Create | Leaves out `target/`, `.env`, `gateway-stub/target/` and `.git`. |
| `src/test/resources/contract/endpoints.txt` | Create | One line per endpoint from section 4.5, as `METHOD /admin/v1/path`. Each phase adds the endpoints it implements. |
| `{test}/security/matcher/PermissionMatcherTest.java` | Create | A table of cases covering every example in section 3, step 5, plus trailing slash, case sensitivity, `**` at the start, middle and end, and `*` inside a segment. |
| `{test}/security/RolesClaimReaderTest.java` | Create | Default path, a custom path, a missing claim, and a claim that is not a list. |
| `{test}/security/SecurityPipelineWebTest.java` | Create | MockMvc with `jwt()` tokens: 401 for missing, malformed, expired, wrong-issuer and wrong-audience tokens. 403 for no local user, for `INACTIVE` and for `LOCKED`. 403 when no permission row matches. 200 when the check is off. `/health` needs no token. Covers B-10 to B-12. |
| `{test}/controller/ApiErrorShapeWebTest.java` | Create | The envelope shape, an unknown property, malformed JSON, a bad UUID, 404 and 405 (B-03). |
| `{test}/controller/OpenApiDocumentTest.java` | Create | Every line of `endpoints.txt` appears in `/admin/v1/api-docs` (B-06). |
| `{test}/audit/AuditWriterTest.java` | Create | Actor and IP are filled in. `record` without a transaction fails. `recordInNewTransaction` survives the outer rollback. |

**Exit check:**
1.  `./mvnw verify` passes.
2.  `docker build -t cce-admin-service:dev .` succeeds.
3.  With the compose stack up, run `./mvnw spring-boot:run`, then:
    *   `curl -s localhost:8085/admin/v1/health` returns `{"status":"UP","database":"UP"}`;
    *   `curl -i -H 'Authorization: Bearer x' localhost:8085/admin/v1/me` returns 401 with code `UNAUTHENTICATED` (the B1 demo);
    *   the same call with the test administrator's token returns 403 `FORBIDDEN`, because no local user exists until phase 4 (B-12).

### Phase 4: Keycloak and gateway clients, bootstrap, stand-in gateway

**Closes:** AD-11, AD-13, AD-15 (the first administrator), and the gateway sync procedure in
section 5.10.

| File | Action | What and why |
|---|---|---|
| `{main}/client/KeycloakGateway.java`, `KeycloakUserInfo.java`, `KeycloakConflictException.java`, `KeycloakCallFailedException.java` | Create | The interface and its types in section 7. |
| `{main}/client/KeycloakAdminClientGateway.java` | Create | Implements the interface with `keycloak-admin-client`. Maps a 409 to `KeycloakConflictException` and every other failure to `KeycloakCallFailedException`. |
| `{main}/config/KeycloakClientConfig.java` | Create | The `Keycloak` admin-client bean: client-credentials grant, server URL, realm and timeouts from `AdminProperties`. |
| `{main}/client/GatewaySyncClient.java`, `SyncResult.java`, `HttpGatewaySyncClient.java`, `ServiceTokenProvider.java` | Create | The sync client and the token cache in section 7, using `RestClient` with the configured timeouts. |
| `{main}/config/GatewayClientConfig.java` | Create | The `RestClient` bean for the gateway. |
| `{main}/service/GatewaySyncService.java` | Create | The procedure in section 5.10: scope, status updates and the `ROLE_SYNC_REQUESTED` row, in a new transaction. |
| `{main}/service/BootstrapAdminRunner.java` | Create | An `ApplicationRunner` with the five steps of AD-15. It logs and never throws. |
| `docker-compose.yml` | Modify | Adds `gateway-stub`: built with context `.` and `dockerfile: gateway-stub/Dockerfile`, port `127.0.0.1:8090:8090`, in-network URLs, and a health check on `GET /_stub/mode`. |
| `gateway-stub/pom.xml` | Create | Parent `spring-boot-starter-parent` 3.5.16, used **only** for plugin and dependency version management. There is no Spring dependency, which keeps the stand-in free of any web framework. **Dependencies:** `jackson-databind` (managed), `org.postgresql:postgresql` 42.7.14 (explicit, D-05), and JUnit Jupiter for tests (managed). **Build:** `build-helper-maven-plugin` adds `../src/main/java/org/cce/admin/security/matcher` as a source folder, so the stand-in compiles the one `PermissionMatcher` (AD-17), and `maven-shade-plugin` builds a runnable jar with main class `org.cce.gatewaystub.GatewayStubMain`. |
| `gateway-stub/Dockerfile` | Create | Build on `maven:3.9.16-eclipse-temurin-21` with `mvn -B -f gateway-stub/pom.xml -DskipTests package`. Run on `eclipse-temurin:21.0.13_9-jre-alpine` as a non-root user. |
| `gateway-stub/README.md` | Create | States plainly that this is a stand-in, not gateway-service. Lists the GW-1, GW-4 and GW-6 assumptions it implements, its environment variables and the mode switch. |
| `gateway-stub/src/main/java/org/cce/gatewaystub/GatewayStubMain.java` | Create | Starts `com.sun.net.httpserver.HttpServer` on `STUB_PORT` (default 8090), with routes `POST /api/permissions/sync-to-keycloak` and `GET`/`PUT /_stub/mode`. |
| `gateway-stub/src/main/java/org/cce/gatewaystub/StubConfig.java` | Create | Reads the environment: database URL, user and password; Keycloak internal URL and realm; the stub client's id and secret; the expected issuer and audience; the JWKS URL. |
| `gateway-stub/src/main/java/org/cce/gatewaystub/ModeHandler.java` | Create | Holds `ok` or `fail` in memory, starting at `ok`. `PUT` with any other body gets 400. |
| `gateway-stub/src/main/java/org/cce/gatewaystub/TokenVerifier.java` | Create | Verifies RS256 against the realm's JWKS (cached, refetched for an unknown `kid`) with `java.security` only, then checks `iss`, `aud` and `exp` (AD-13). |
| `gateway-stub/src/main/java/org/cce/gatewaystub/PermissionStore.java` | Create | JDBC: reads `permission_name`, `http_method` and `uri_pattern` from `api_permissions`. |
| `gateway-stub/src/main/java/org/cce/gatewaystub/KeycloakRealmRoleClient.java` | Create | `java.net.http` calls to the Keycloak Admin REST API with its own client-credentials token: list realm roles with their attributes, create a role with `cce-managed=true`, and delete a role. |
| `gateway-stub/src/main/java/org/cce/gatewaystub/RealmRoleSyncPlanner.java` | Create | Pure logic. From the permission names and the existing roles, it decides which roles to create and which to delete. It deletes only roles that carry `cce-managed=true` and have no rows. It never touches `default-roles-*`, `offline_access` or `uma_authorization`, and never deletes `SUPER_ADMIN` (AD-11). |
| `gateway-stub/src/main/java/org/cce/gatewaystub/SyncHandler.java` | Create | In `fail` mode, returns 503. Otherwise: verify the token, require a token role whose rows allow `POST /api/permissions/sync-to-keycloak` through `PermissionMatcher` (401 or 403 otherwise), apply the plan, and return 200 with a JSON summary. |
| `gateway-stub/src/test/java/org/cce/gatewaystub/TokenVerifierTest.java`, `RealmRoleSyncPlannerTest.java`, `ModeHandlerTest.java` | Create | Tokens signed with a key pair generated in the test. Every rule of the planner. The mode switch. |
| `{test}/client/HttpGatewaySyncClientTest.java`, `ServiceTokenProviderTest.java` | Create | Against a JDK `HttpServer` fake: the bearer header, path and empty body; 2xx is success; 4xx, 5xx and timeouts are failures, with the summary limited to 500 characters; the token is cached and refreshed 30 s before expiry. |
| `{test}/service/GatewaySyncServiceTest.java` | Create | Under `GLOBAL` and under `ROLE`, success and failure each produce the right status updates and exactly one `ROLE_SYNC_REQUESTED` row (AD-06). |
| `{test}/service/BootstrapAdminRunnerTest.java` | Create | With a mocked `KeycloakGateway`: an admin exists (with and without the variable, and the warning), no variable, user missing, user disabled, email unverified while verification is required, realm role missing (runs a sync), the success path (one audit row by `system:bootstrap`), and re-activating an existing row. |

**Exit check:**
1.  `./mvnw verify` and `./mvnw -f gateway-stub/pom.xml verify` both pass.
2.  `docker compose up -d --build --wait` reports `gateway-stub` healthy.
3.  `./mvnw spring-boot:run`. The log shows the bootstrap creating the administrator, and `GET /admin/v1/me` with the test administrator's token returns 200 with `roles: ["SUPER_ADMIN"]`. After a restart, the log shows the warning that `BOOTSTRAP_ADMIN_USERNAME` is no longer needed.
4.  On the stand-in:
    *   `POST localhost:8090/api/permissions/sync-to-keycloak` without a token returns 401;
    *   with a `cce-admin-service` client-credentials token it returns 200;
    *   after `curl -X PUT --data fail localhost:8090/_stub/mode` it returns 503;
    *   after `--data ok` it returns 200 again.

### Phase 5: Roles, menus, permissions and sync

**Closes:** B-40, B-41, B-42, B-43, B-44, and B-50 for these endpoints. This is milestones B2
and B4.

| File | Action | What and why |
|---|---|---|
| `{main}/controller/dto/RoleDtos.java`, `MenuDtos.java` | Create | The Role, Permission row and Menu records, with their request records and the bean-validation rules in section 4.4. |
| `{main}/controller/RoleController.java` | Create | The nine `/roles` endpoints in section 4.5: 201 with `Location` on create, 204 on delete. |
| `{main}/controller/MenuController.java` | Create | The four `/menus` endpoints in section 4.5. |
| `{main}/service/ImplicitPermissions.java` | Create | The two implicit rows, with their fixed description and category, and the test for "is this row implicit" (AD-16). |
| `{main}/service/PermissionRowRules.java` | Create | Upper-cases methods, checks them against the fixed list `GET`, `POST`, `PUT`, `PATCH`, `DELETE` (section 4.4), checks the `uriPattern` syntax (section 4.4), drops implicit duplicates, and rejects duplicates with `details`. |
| `{main}/service/RoleService.java` | Create | List, get, create (5.6), update display fields (5.12) and delete (5.9). It owns the `DUPLICATE_ROLE_NAME` rule that includes `api_permissions` names, the system-role rule (AD-07) and the `ROLE_IN_USE` check. |
| `{main}/service/RolePermissionService.java` | Create | Get and replace permissions (5.7) and manual sync (5.8). Clears `PermissionSnapshot` after each commit that changes rows. |
| `{main}/service/RoleMenuService.java` | Create | Get and replace menus, with the `DETAIL_WITHOUT_PARENT` warnings (5.11). |
| `{main}/service/MenuService.java` | Create | The menu tree for `GET /menus`, plus create, update and delete (5.12), with the parent rules and `MENU_HAS_CHILDREN`. |
| `src/test/resources/contract/endpoints.txt` | Modify | Adds the 14 role and menu endpoints. |
| `{test}/service/RoleServiceTest.java`, `RolePermissionServiceTest.java`, `RoleMenuServiceTest.java`, `MenuServiceTest.java` | Create | Spring Boot tests on Testcontainers with `@MockitoBean` `KeycloakGateway` and `GatewaySyncClient`. For every endpoint: the happy path, each error code it can return, no-ops, and the exact audit rows. That includes two rows for a permission save on both outcomes, one for a manual sync, and two for a create and for a delete (G8). Also the 502 `partialState` for `ROLE_CREATE`, `ROLE_PERMISSIONS_SAVE`, `ROLE_SYNC` and `ROLE_DELETE`. |
| `{test}/controller/RoleAndMenuContractWebTest.java` | Create | JSON shapes as in section 4.4: `warnings` placement, the `implicit` flag, 204 bodies, and unknown properties such as `name` on `PUT /roles/:id`. |

**Exit check:**
1.  `./mvnw verify` passes.
2.  With the stack and the service running, use the test administrator's token through Swagger UI or curl:
    *   create a role: 201, `syncStatus: SYNCED`, and the realm role now exists in Keycloak;
    *   save menus that include a `DETAIL` without its parent: 200 with the `DETAIL_WITHOUT_PARENT` warning;
    *   switch the stand-in to `fail` and save permissions: 502 with `operation: ROLE_PERMISSIONS_SAVE`;
    *   switch back to `ok` and re-sync: 200.
3.  In `psql`, `SELECT action, outcome FROM audit_log` shows the matching rows.

### Phase 6: Users and Keycloak provisioning

**Closes:** B-30, B-31, B-32, B-33, B-34, B-35, B-36, and B-50 for these endpoints. This is
milestone B3.

| File | Action | What and why |
|---|---|---|
| `{main}/controller/dto/UserDtos.java` | Create | The User record and the four request records, with the bean-validation rules in section 4.4. |
| `{main}/controller/UserController.java` | Create | The seven `/users` endpoints in section 4.5. |
| `{main}/service/UserSearchSpecifications.java` | Create | The JPA specifications for `status`, `roleId`, `facilityScope` and `q` (section 4.5). |
| `{main}/service/LastAdminGuard.java` | Create | Takes the `SUPER_ADMIN` row lock and runs the `LAST_SYSTEM_ADMIN` check (section 5, AD-07). |
| `{main}/service/UserService.java` | Create | List and get, plus flows 5.1 to 5.5 in the stated order and with the stated step names. Assignability and `ROLE_NOT_SYNCED` are checked before any change. Partial-failure audit rows use `recordInNewTransaction`. There is no compensation. |
| `src/test/resources/contract/endpoints.txt` | Modify | Adds the seven user endpoints. |
| `{test}/service/UserCreateFlowTest.java` | Create | Every step of 5.1, including the PRD's case: the database insert fails after Keycloak succeeded, giving 502 plus a `PARTIAL_FAILURE` audit row with the Keycloak id (B-32). Also `EMAIL_IN_USE` and `USERNAME_IN_USE` (locally and in Keycloak), `ROLE_NOT_ASSIGNABLE`, `ROLE_NOT_SYNCED`, and the email warning. |
| `{test}/service/UserProfileFlowTest.java`, `UserStatusFlowTest.java`, `UserRolesFlowTest.java`, `UserResetPasswordTest.java` | Create | Flows 5.2 to 5.5: each step, the happy path, every error code, no-ops, the exact audit rows, and that only the added and removed realm roles are touched (B-35). |
| `{test}/service/LastSystemAdminConcurrencyTest.java` | Create | Two administrators deactivate each other at the same time. Exactly one succeeds, and the other gets `LAST_SYSTEM_ADMIN`. |
| `{test}/controller/UserContractWebTest.java` | Create | Pagination, filters, search, the `Location` header, and the 201 warning shape. |

**Exit check:**
1.  `./mvnw verify` passes.
2.  With the stack and the service running (the B3 demo):
    *   `POST /users` creates a Keycloak user holding the realm role;
    *   Mailpit at `http://localhost:8025` shows the set-password email;
    *   `PATCH` to `INACTIVE` shows the user disabled in the Keycloak admin console;
    *   `PATCH` back to `ACTIVE` enables them again.

### Phase 7: Role-aware menu tree, service container, contract completeness

**Closes:** B-21 (milestone B5), B-06 for every endpoint, and the B-50 guard.

| File | Action | What and why |
|---|---|---|
| `{main}/service/MenuTreeBuilder.java` | Create | The `/me/menus` rules in section 4.5. |
| `{main}/service/CurrentUserService.java`, `{main}/controller/MeController.java` | Modify | Add `GET /admin/v1/me/menus`. |
| `docker-compose.yml` | Modify | Adds `admin-service` under `profiles: [app]`: built from `.`, port `127.0.0.1:8085:8085`, `env_file: .env`, the in-network overrides in section 9.1, and `depends_on` `postgres`, `keycloak` and `gateway-stub` being healthy. |
| `README.md` | Modify | Complete it: running on the host and in a container, the configuration table (section 9.4), the bootstrap steps for a new environment (section 9.5) with the unowned-rows query, the stand-in switch, and the reset. |
| `src/test/resources/contract/endpoints.txt` | Modify | Adds `GET /admin/v1/me/menus`. The file now lists all 24 endpoints in section 4.5. |
| `src/test/resources/permissions/scenario-rows.json` | Create | The explicit permission rows the E2E scenarios save. For each row it gives the calls the row must allow and one call it must not. Phase 8 reads the same file. |
| `{test}/service/MenuTreeBuilderTest.java`, `CurrentUserMenusTest.java` | Create | Union across roles, inactive menus left out, a `DETAIL` only under a granted parent, ordering, and the B5 case of two overlapping roles. |
| `{test}/permissions/OfflinePermissionRowCheckTest.java` | Create | G9. For the seed rows, the implicit rows and `scenario-rows.json`, each intended call matches through `PermissionMatcher`, and each "must not" call does not. |
| `{test}/audit/MutatingEndpointsAuditGuardTest.java` | Create | Reads every non-GET mapping from Spring's `RequestMappingHandlerMapping` and asserts each is listed in a fixed table of audit-tested endpoints, so that a new mutating endpoint cannot be added without an audit test (B-50). |

**Exit check:**
1.  `./mvnw verify` passes, and `OpenApiDocumentTest` covers all 24 endpoints.
2.  `docker compose --profile app up -d --build --wait` reports all five services healthy.
3.  `curl localhost:8085/admin/v1/health` returns `UP`.

### Phase 8: API-level end-to-end checks (E2E, S1 to S6)

**Closes:** milestone E2E, scenarios S1 to S6, and G9's live hard-control check.

| File | Action | What and why |
|---|---|---|
| `pom.xml` | Modify | Adds profile `e2e`. In it, Surefire is skipped and Failsafe runs `**/*E2E.java`. The default build still excludes them. |
| `{test}/e2e/E2eEnvironment.java` | Create | Reads `.env` (`KEY=VALUE`, resolving `${…}` from earlier keys), with process environment variables taking precedence. Provides the URLs in section 9.3 and the per-run name suffix. |
| `{test}/e2e/E2eHttp.java` | Create | A small JSON client over `java.net.http` and Jackson that returns status, headers and body. |
| `{test}/e2e/TokenClient.java` | Create | Gets password-grant tokens from `cce-e2e`. |
| `{test}/e2e/KeycloakTestAdmin.java` | Create | Test-only Keycloak Admin REST calls as the master `admin`, using `KEYCLOAK_ADMIN_PASSWORD`: set a password and clear required actions, read a user's `enabled` flag and realm roles, and check that a realm role exists (see the accepted risk in Non-Functional Requirements). |
| `{test}/e2e/MailpitClient.java`, `StubModeClient.java` | Create | Read messages from `http://localhost:8025/api/v1/messages`, and flip `/_stub/mode`. Every test resets the mode to `ok` in `@AfterEach`. |
| `{test}/e2e/ScenarioS1MenusE2E.java` | Create | S1 and B5: two roles with overlapping menus, a user holding both, then that user's `GET /me/menus` returns the merged tree. |
| `{test}/e2e/ScenarioS2OnboardUserE2E.java` | Create | S2, as in section 10. |
| `{test}/e2e/ScenarioS3ConfigureRoleE2E.java` | Create | S3, using rows from `scenario-rows.json`. |
| `{test}/e2e/ScenarioS4ChangeRolesE2E.java` | Create | S4. |
| `{test}/e2e/ScenarioS5DeactivateE2E.java` | Create | S5. |
| `{test}/e2e/ScenarioS6SyncFailureE2E.java` | Create | S6. |
| `{test}/e2e/HardControlE2E.java` | Create | A console user's token on `GET /users` gets 403 `FORBIDDEN`, and on `GET /me/menus` gets 200. |
| `README.md` | Modify | Adds how to run the E2E checks. |

**Exit check:**
1.  `docker compose --profile app down -v`, then `docker compose --profile app up -d --build --wait`.
2.  `./mvnw -Pe2e verify` passes every scenario.
3.  `./mvnw verify` still passes and runs no E2E test.
4.  `./mvnw -f gateway-stub/pom.xml verify` passes.

### Verification Plan

*   **Build:** `./mvnw verify` (service) and `./mvnw -f gateway-stub/pom.xml verify` (stand-in).
    `docker build -t cce-admin-service:dev .` builds the image.
*   **Unit, migration, service and web tests:** run by `./mvnw verify`. They need Docker for
    Testcontainers, and no running stack.
*   **E2E:** `./mvnw -Pe2e verify` against `docker compose --profile app up -d --build --wait`.
*   **Static analysis and coverage:** none is required beyond compilation. The PRD sets no
    coverage figure. Its definition of done ("the happy path and at least one error code per
    endpoint", PRD §11) is enforced by the service and web tests listed per phase, and by
    `MutatingEndpointsAuditGuardTest`.
*   **CI:** no pipeline file is delivered (DEV-2). Running the commands above locally stands in
    for it.

--------------------------------------------------------------------------------

*Drafted with EGM*
