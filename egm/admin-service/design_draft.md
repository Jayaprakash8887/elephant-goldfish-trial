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
([PRD §2, §4, §7](../../context/prd/CCE_Admin_Module_PRD.md)). Every endpoint is under the path
prefix `/admin/v1`. Where this document writes an endpoint without the prefix, as in
`GET /users`, the prefix is implied.

The service sits behind the existing `gateway-service`. The gateway checks the caller's JWT and
looks up the caller's roles in the `api_permissions` table, which this service writes. Then it
forwards `/admin/**` requests to the service on port 8085. The service checks the JWT again on its
own and refuses callers whose local account is missing or not `ACTIVE` (PRD §5). Two consoles
call it: the new `cce-admin-ui`, which has its own
[design](../../docs/designs/cce-admin-ui.md), and the existing `cce-insights-ui`.

This document covers PRD milestones M0 (local environment) and B1 to B5 (backend requirements
B-01 to B-50), plus API-level end-to-end checks of the PRD's six scenarios S1 to S6 (milestone
E2E). PRD labels are used as written: B-nn are backend requirements, F-nn console requirements,
S1 to S6 scenarios, and M0, B1 to B5, F1 to F5 and E2E milestones. The team design documents the
PRD refers to (a data dictionary, an API reference and flow diagrams) are not among this
design's inputs. This document therefore defines that content itself: every table, column,
index and seed row; every endpoint's request and response shape and error codes; and the order
of operations in each flow that touches Keycloak or the gateway. Under author decision D-01,
this document is the API contract that `cce-admin-ui` builds against. Under D-03, it is the
single definition of the local environment
([author decisions](../../docs/designs/author-decisions.md)).

### Inputs

| Input | What this design takes from it |
|---|---|
| [PRD](../../context/prd/CCE_Admin_Module_PRD.md) (Markdown copy of the authoritative PDF) | Requirements B-01 to B-50, scenarios S1 to S6, milestones M0, B1 to B5 and E2E, the stack (§6), the error codes (Appendix B), the endpoints (Appendix A). |
| [Gateway migration doc](../../context/gateway-service/DATABASE_PERMISSIONS_MIGRATION.md) | The fixed shape of `api_permissions` and how the gateway loads it. |
| [insights-ui architecture](../../context/cce-insights-ui/docs/architecture-overview.md), [developer setup](../../context/cce-insights-ui/docs/developer-setup.md), [deployment guide](../../context/cce-insights-ui/docs/deployment-guide.md) | The 12 routes and 7 sidebar links the menu seed matches, and the gateway audience `gateway-service`. |
| [Author decisions](../../docs/designs/author-decisions.md): D-01, D-03, D-05 | D-01: this design owns the API contract. D-03: this design defines the local environment. **D-05: the exact versions of every image and dependency**, used unchanged in section 9.1 and in the build files. |
| [Console design](../../docs/designs/cce-admin-ui.md) | Requirements AS-REQ-01 to AS-REQ-24 on this design, answered in section 4.6, and the contract point CQ-1. |

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
    cites are not inputs. A coding agent will implement this service from this document alone,
    so this document must leave that agent no design decisions.

## Goals

Each goal names the PRD requirement IDs and the milestone it covers, and how it is checked.

*   **G1. One-command local environment** (M0; D-03, B-13). `docker compose up` in
    `cce-admin-service/` starts Postgres 16, Keycloak, a mail catcher and a stand-in for the
    gateway's sync endpoint. Keycloak starts with a realm already imported that contains the
    `cce-admin-ui` public client (PKCE S256, redirect `http://localhost:3002/*`), a confidential
    service-account client for the backend, and a test administrator. The realm import file holds
    no credentials: secrets and the test administrator's password are placeholders that Keycloak
    fills from environment variables when the container starts. An example env file with
    placeholder values is committed, and the real env file is git-ignored (PRD §11).
    **Check:** the test administrator signs in to the Keycloak account console, and `git ls-files`
    lists no file that holds a real secret or password.
*   **G2. Schema and seed from an empty database** (B1; B-01, B-02). Flyway migrations create all
    seven tables, their indexes and the `pgcrypto` extension, and seed the 12 cce-insights-ui
    menus and the system roles. `api_permissions` keeps the gateway's column shape.
    **Check:** a migration test on a new database compares the result with section 6.
*   **G3. One written contract** (B1; B-03, B-04, B-06, D-01). Every endpoint in PRD Appendix A
    has its path, query parameters, request body, response body and error codes in section 4.
    Success bodies are `{data, pagination?, warnings?}` and error bodies
    `{error:{code,message,...}}`. The OpenAPI document lists every endpoint.
    **Check:** a test produces each error code in section 4.2 at least once, and a test compares
    the OpenAPI document with the list of endpoints.
*   **G4. The service checks every caller itself** (B1; B-05, B-10 to B-13). `GET /health` needs
    no token. Every other endpoint rejects a bad, expired or wrongly addressed token with 401
    `UNAUTHENTICATED`, a token whose `sub` has no `ACTIVE` local user with 403 `FORBIDDEN`, and a
    call that the caller's roles do not allow with 403 `FORBIDDEN`. Roles come from the claim
    path in `JWT_ROLES_CLAIM_PATH`. **Check:** web tests for each 401 and 403 case.
*   **G5. Users stay consistent with Keycloak, and failures are visible** (B3; B-30 to B-36). Each
    user flow runs its Keycloak and database steps in the order section 5 fixes. A Keycloak step
    that fails after an earlier step changed something returns 502 `KEYCLOAK_SYNC_FAILED` and
    writes an audit row with the Keycloak user id. Nothing is retried automatically, and
    submitting the same request again completes the flow (section 5). An existing Keycloak
    account with no local user is linked, not refused (AD-18). If only the set-password email
    fails, user creation still returns 201 with the warning `ACTION_EMAIL_NOT_SENT` (AD-08).
    **Check:** a service test makes the database insert fail after Keycloak succeeds and asserts
    the 502 and the audit row (PRD §10, B3 notes). Another test links an existing account.
*   **G6. Roles, menus and permissions are managed through the API, not SQL** (B2, B4; B-40 to
    B-44). Role, menu and permission changes go through the service and follow replace semantics
    where the PRD says so. A permission save is followed by a gateway sync. A failed sync after a
    permission save returns 502 and keeps the saved rows. **Check:** service tests per endpoint,
    and the Phase 5 exit check.
*   **G7. Role-aware menu tree** (B5; B-20, B-21). `GET /me/menus` returns the union of the active
    menus of all the caller's roles, with children nested under their parents and ordered by
    `sortOrder`. **Check:** two roles with overlapping menus produce the merged tree.
*   **G8. Every state change is audited** (B2 to B4; B-50, B-32). The service follows the single
    audit rule in section 8: one append-only row per state change, where each gateway sync
    attempt counts as its own change, and one row for each Keycloak failure that leaves a
    partial state. A permission save and a role create therefore write two rows. A call that
    fails before anything changes writes none. Each row holds actor, action, entity type and id,
    before and after JSON, outcome, and the client IP address. **Check:** a test asserts the
    exact rows for every mutating endpoint, on the success path and on each 502 path.
*   **G9. Scenarios S1 to S6 run end to end locally** (E2E). A scripted check runs all six PRD
    scenarios against the real service, Postgres and Keycloak in Docker, with the stand-in for
    the gateway's sync endpoint. The stand-in can be switched to fail, so S6 can be checked. The
    real gateway is not part of the local environment, so these runs cannot show that the
    *gateway* blocks a call a role may not make. The service's own permission check
    (section 3, step 5) is checked live instead: a console user's token gets 403 on `GET /users`.
    **Check:** `./mvnw -Pe2e verify` against the running stack.
*   **G10. Buildable from this document alone** (all milestones). `./mvnw verify` passes. It runs
    unit tests, service-layer tests with the Keycloak and gateway clients mocked, and integration
    tests with Testcontainers Postgres (PRD §9 Testability, §11). The service builds into a
    multi-stage Docker image that listens on port 8085. **Check:** the exit check of every phase
    in the Detailed Implementation Plan.

## Non-Goals

*   **The admin console.** `cce-admin-ui` (F-01 to F-15) has its own design. This document only
    provides the contract it uses (D-01) and the local values it runs against (D-03).
*   **Changes to other repositories.** The `cce-insights-ui` change that renders its sidebar from
    `/me/menus` (PRD §12) and any change to `gateway-service` are out of scope. The gateway is used
    as it is.
*   **Running the real gateway locally.** gateway-service is not part of the local environment.
    Local runs and the E2E checks use a stand-in for its sync call, and call the service directly
    on port 8085.
*   **Adopting `permission_name` values that no role here owns**, such as an existing
    `INSIGHTS_READ`. Those rows stay managed by the gateway owners. `POST /roles` refuses such a
    name (section 5.6), and GW-5 asks the gateway owners what should happen to them.
*   **Items the PRD puts outside CCE v2** (PRD §2): an audit log read endpoint or viewer, a
    cross-role `GET /permissions` catalog, enforcing `users.facility_scope` (it is stored and
    returned only), deriving permissions from menus, hard delete of users, and role-driven
    navigation in the admin console.
*   **Automatic repair of partial failures.** A partial state is recorded in `audit_log` for an
    operator, and the same request can be submitted again. The service does not retry, roll back
    Keycloak, or run a background reconciliation or drift detection job (PRD §9, §12).
*   **Shared and production environments.** This document defines the environment variables the
    service reads and the bootstrap steps a new environment needs (section 9.5). It does not
    provision shared Keycloak realms or databases, and it does not include a Jenkins pipeline
    file (DEV-2).
*   **Password handling.** No password ever passes through the service. Passwords are set
    through a Keycloak required action sent by email (B-36).

## Deviations from the PRD

These are places where this design does not follow the PRD as written. PRD §11 asks that each
deviation be raised with the team as a doc change.

| ID | PRD text | This design | Why |
|---|---|---|---|
| DEV-1 | B-01: all seven tables have UUID primary keys. | `api_permissions` keeps the gateway's shape, including `id BIGSERIAL`. The other six tables use UUID primary keys. | `api_permissions` is a fixed constraint read by gateway-service (PRD §5, §12). Changing its key type could break a service this team does not own. |
| DEV-2 | §11 definition of done: "`npm run build` / `npm run lint` / `mvn verify` pass in CI." | No CI pipeline file is delivered. `./mvnw verify` and the Docker image build run locally, and their results stand in for CI. | The inputs give no Jenkins conventions, and no Jenkins server is part of the local environment. |
| DEV-3 | §6: "PostgreSQL schema `admin_service`". | `admin_service` is read as the *database* name. All seven tables live in its `public` schema (AD-01). | The PRD contradicts itself: §5's text names a database `admin_service`, while its diagram says "schema admin_service". The gateway doc connects to the database `admin_service` without naming a schema, so with the default `search_path` (`"$user", public`) and no schema named after its user, it reads `public`. |
| DEV-4 | §2 (out of scope): "Deactivation (`INACTIVE`) is terminal, for audit continuity." | `INACTIVE` can be undone. A user moves freely between `ACTIVE`, `INACTIVE` and `LOCKED` (AD-04). | B-34, S5 and F-10 describe re-activation, and Appendix A lists `LOCKED`. |
| DEV-5 | B-03: "Success: `{data, pagination?}`." B-05: "Returns `{status:"UP"}`." | Success bodies may also carry `warnings`. `DELETE /roles/:id` and `DELETE /menus/:id` return 204 with no body. `GET /health` returns `{status, database}` without the envelope. | B-41 requires a warning on a successful save and gives it no place. A 204 has no body to wrap. The health shape is this design's choice: it extends B-05's `{status:"UP"}` with the database status B-05 asks for, and probes read it, not consoles. |
| DEV-6 | Appendix B lists the error codes. | Adds `USERNAME_IN_USE`, `ROLE_NOT_SYNCED`, `ROLE_NOT_ASSIGNABLE`, `LAST_SYSTEM_ADMIN`, `MENU_HAS_CHILDREN`, `NOT_FOUND`, `METHOD_NOT_ALLOWED` and `INTERNAL_ERROR`, and the 502 `partialState` object (section 4.2). | Each covers a case Appendix B leaves without a code: a separate username, the ordering rule of PRD §9, the service-account role, the last administrator, a menu with children, and framework errors. |
| DEV-7 | B-50: "A test asserts one row per mutation." | One row per *state change* (section 8). A gateway sync attempt is a state change of its own, so `POST /roles` and `PUT /roles/:id/permissions` write two rows each. | Recording each sync attempt is what lets `audit_log` alone show whether a saved permission reached the gateway (AD-06, PRD §9 Auditability). |
| DEV-8 | Appendix B: `KEYCLOAK_SYNC_FAILED` means "state partially applied". | It is also returned when a Keycloak or gateway call fails before anything changed, with `completedSteps: []`. | The caller cannot fix such a failure by changing the request, so no 4xx code fits, and Appendix B has no 5xx code for it. |
| DEV-9 | B-03: error body `{error:{code,message}}`. | The error body may also carry `details` (per-field errors) and `partialState` (section 4.1). | B-03 gives a validation error no place for the field it concerns, and B-32 asks for partial failures to be visible. |
| DEV-10 | B-21: "`NAV` and `DETAIL` both included; children nested under parent." | A granted `DETAIL` menu is left out of `/me/menus` when its parent `NAV` is not granted or not active (section 4.5). | PRD §4 says a `DETAIL` inherits its parent's visibility, and B-41's warning tells administrators exactly this. |

## Author Decisions in This Design

Decisions the author took for this design. The procedures they lead to are written once, in the
section named in the last column. Decisions that the console design also needs are copied to
[author-decisions.md](../../docs/designs/author-decisions.md) under a `D-` ID.

| ID | Question | Decision | Why | Where |
|---|---|---|---|---|
| AD-01 | Where do the seven tables live? | In the `public` schema of the database `admin_service` (DEV-3). | The gateway reads `public` and needs no change. A schema `admin_service` would depend on a gateway configuration change; if that were missing, the gateway would load no permissions. | §6 |
| AD-02 | Who creates `api_permissions`? | This service's V1 migration creates the table, with its constraint, indexes and trigger, only when the table is absent. It never alters an existing table. V2 checks the table's shape before V3 seeds rows. Rows no role owns are left in place. | B-01 holds on an empty database, an environment where the gateway's script already ran keeps working, and nothing touches a table another team owns. | §6 |
| AD-03 | How are the three routes outside today's sidebar seeded? | Practitioners, Intelligence and Exports are active `NAV` menus, ordered after today's seven links. No menu is granted to any role. | Under per-role menus, "granted means shown" is the one rule a console needs. An inactive or `ROUTE` menu would add a second meaning or a new type. | §6 |
| AD-04 | Which user statuses exist? | `ACTIVE`, `INACTIVE` and `LOCKED`, with every change allowed. Both blocked statuses disable the Keycloak account and end its sessions, and keep `user_roles` (DEV-4). | Offboarding and a temporary block are different events for operators, and the audit action keeps them apart. | §5.3 |
| AD-05 | How is `last_login_at` updated? | A conditional update in the local-user filter sets it to the token's `auth_time` when that is later. | One write per sign-in, with no polling and no Keycloak extension. | §3 |
| AD-06 | How is a gateway sync's result recorded? | Each attempt writes a `ROLE_SYNC_REQUESTED` row with its outcome, and sets `sync_status` and `last_sync_at` on the role that asked, and only that role. | `audit_log` then shows whether each permission change reached the gateway. Marking other roles would assume more about the sync than GW-1 states. | §5.10, §8 |
| AD-07 | What does `SYSTEM_ROLE_IMMUTABLE` block, and what protects the last administrator? | Edits, menu and permission changes and delete of a system role. A change that would leave no `ACTIVE` user holding `SUPER_ADMIN` gets 409 `LAST_SYSTEM_ADMIN`. | `SUPER_ADMIN`'s rows are what let administrators administer. Freezing them alone does not stop the last administrator from being removed. | §4.2, §5.3, §5.4 |
| AD-08 | How does a new user get a first password? | Keycloak's execute-actions email (`UPDATE_PASSWORD`), sent after the local row exists. If it fails, 201 with `ACTION_EMAIL_NOT_SENT`. | No password passes through the service (B-36). The email changes no access and can be sent again with reset-password. | §5.1 |
| AD-09 | What is `facility_scope`? | One optional free-text string of up to 50 characters, matched exactly by the filter. | Facility data is owned elsewhere, and the scope is not enforced (PRD §2). | §4.4 |
| AD-10 | What happens when a role being assigned has no realm role yet? | 409 `ROLE_NOT_SYNCED` before anything changes. A role being removed whose realm role is gone is skipped in Keycloak. | PRD §9 Ordering. Keycloak is the authoritative source, and `syncStatus` is only a display hint. | §5.1, §5.4 |
| AD-11 | What stands in for the gateway sync locally? | A small stand-in in its own container. It creates and deletes realm roles from `api_permissions` under the GW-1 and GW-4 assumptions, and has a runtime switch to fail. | Locally the service runs the same HTTP client code it runs against the real gateway, and the production artifact carries no failure switch. | §9.1 |
| AD-12 | Does role delete run a gateway sync? | No. Delete removes the role and its rows in one transaction and calls nothing else. | Delete then has one outcome and one audit row. A leftover realm role grants nothing, because the role was held by nobody, and the next sync of any role lets the gateway remove it (GW-4). | §5.9 |
| AD-13 | How does the service authenticate to the gateway's sync endpoint? | With a client-credentials token of its own client `cce-admin-service`, whose service account holds the realm role `CCE_ADMIN_SERVICE`. This chain is provisional until GW-6 is answered. | The sync does not depend on who called, and "allowed to save" cannot drift from "allowed to sync". | §7, §9.5 |
| AD-14 | How is `CCE_ADMIN_SERVICE` kept from being assigned to people? | `roles.is_assignable`, with no default, set to false for that role. Assigning it gets 409 `ROLE_NOT_ASSIGNABLE`. | The rule lives in data the console can read, and works for later machine roles. | §4.4, §6 |
| AD-15 | How is the first `SUPER_ADMIN` created in each environment? | A bootstrapper at startup links the Keycloak user named in `BOOTSTRAP_ADMIN_USERNAME` when no `ACTIVE` administrator exists. It never runs a sync. | Safe to run again, no ids copied by hand, and audited. | §5.13 |
| AD-16 | How does every console role get permission for `/me` and `/me/menus`? | Every non-system role always holds two implicit rows for them. Role create attempts a sync; if it fails, 201 with the warning `ROLE_SYNC_FAILED`. | Nobody can forget the rows (PRD §12), and every ordinary role gets a realm role after its first successful sync. | §4.4, §5.6, §5.7 |
| AD-17 | Does the service check `api_permissions` itself? | Yes, minimally: Spring's `AntPathMatcher` over one indexed query per request, behind the switch `PERMISSION_CHECK_ENABLED`. | Defence in depth on the API that grants access, at a small fixed cost. | §3 |
| AD-18 | What happens to existing Keycloak accounts that have no local user? | `POST /users` links an enabled account whose username and email both match exactly, instead of refusing it. The bootstrapper uses the same linking method. | Every current cce-insights-ui user is such an account, and a failed `POST /users` leaves one. Linking gives both one path, with no new endpoint. | §5.1 |
| AD-19 | Which client IP is audited? | Tomcat's own forwarded-header handling, trusting only the proxies listed in `TRUSTED_PROXIES`. | A forwarded header from an untrusted sender cannot set the audited address. | §3 |

## Open Questions for the gateway-service owners

Each question states the assumption this design uses until it is answered. GW-2 must be answered
before the first shared environment (go/no-go). Locally, the stand-in (AD-11) defines the
behaviour.

A different answer to some questions needs only configuration: the sync URL and path, the
timeouts, and turning off the service's own permission check (`PERMISSION_CHECK_ENABLED`). These
answers would need a code change: a request body or a per-role parameter on the sync call
(GW-1), a person's token instead of a service token (GW-6), and a matching rule that differs
from `AntPathMatcher` (GW-3).

*   **GW-1. Sync endpoint contract.** What does `POST /api/permissions/sync-to-keycloak` expect as
    its request and return as its response? Is it a full sync of every `permission_name`? Which
    status codes mean success? *Assumed until answered:* no body and no parameters; a full sync;
    any 2xx is success; any other status, a connection error or a timeout is failure; the body
    is not parsed, and its first 500 characters go into the audit row's error summary.
*   **GW-2. When permission changes take effect (go/no-go before the first shared
    environment).** The gateway doc contradicts itself. Line 8 lists "Update permissions without
    restarting the service". Line 187 says "Currently, you need to restart the gateway service to
    load new permissions." Does the sync endpoint also reload the gateway's permissions? *Assumed
    until answered:* no. `syncStatus = SYNCED` means only that the last sync call got a 2xx, not
    that the gateway enforces the new rows. Until the answer is yes, the gateway owners restart
    the gateway after permission changes (Risks & Mitigations).
*   **GW-3. URI pattern syntax and the path that is matched.** Which wildcard syntax does the
    gateway support, and does it match the incoming path, `/admin/v1/...`, before proxying?
    *Assumed until answered:* Spring `AntPathMatcher` semantics, against the path the service
    also receives. If not, `PERMISSION_CHECK_ENABLED=false` turns off the service's own check.
*   **GW-4. Removing realm roles.** Does a sync delete a realm role whose `permission_name` no
    longer has rows? *Assumed until answered:* yes, but only for realm roles the gateway itself
    created. The stand-in follows this by tagging the roles it creates.
*   **GW-5. Rows and names that no role here owns.** Where the gateway's own script already ran,
    `api_permissions` can hold rows whose `permission_name` matches no row in `roles`, such as
    the doc's samples `USER_READ` and `USER_CREATE_UPDATE`, or `INSIGHTS_READ`. The gateway keeps
    enforcing them, and no admin screen shows them. Are they still needed? Should any be adopted
    as roles managed here? Section 6 gives a read-only query that lists them. Also: the doc
    names the two indexes and a trigger in prose only, without DDL. This design's DDL for them
    is a reconstruction (section 6). Does it match the gateway's script?
*   **GW-6. Authentication on the sync endpoint.** Does the endpoint go through the gateway's
    normal JWT and `api_permissions` check, and does it accept a service-account token?
    *Assumed until answered:* yes (AD-13). Everything that depends on `CCE_ADMIN_SERVICE` is
    provisional until then. If a person's token is required instead, the client forwards the
    caller's `Authorization` header, which is a code change, and `SUPER_ADMIN` would also need a
    row for the sync endpoint.
*   **GW-7. Forwarded client addresses.** Which header does the gateway set with the client's
    address (`X-Forwarded-For` or `Forwarded`), and from which addresses does it connect to the
    service? The answer sets `TRUSTED_PROXIES` (AD-19).
*   **GW-8. The gateway's own 401 and 403 bodies.** What body does the gateway send when it
    refuses a call? *Assumed until answered:* not this service's error envelope. A client must
    treat a 401 or 403 without `error.code` as `UNAUTHENTICATED` or `FORBIDDEN` by its status.

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
*   **Then**: the Keycloak account (new, or an existing one that is linked), its realm roles, the
    local row, `user_roles` and one audit row all exist, and the user gets an email to set a
    password. If Keycloak fails part-way, the response is 502 with `partialState`, the audit row
    names the Keycloak user id, and submitting the same request again completes the onboarding.

### CUJ 3: An administrator creates and configures a role (S3, B-40 to B-42)

*   **Given**: the name is unused, in `roles` and as a Keycloak realm role.
*   **When**: the administrator creates the role, replaces its menus, then replaces its
    permissions.
*   **Then**: each call returns the saved state. A `DETAIL` without its parent returns a warning.
    Creation and each permission change are followed by a gateway sync, after which the role's
    realm role exists.

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
    returns 409 `ROLE_NOT_SYNCED` if the role has no realm role yet. After `POST /roles/:id/sync`
    succeeds, the assignment works.

--------------------------------------------------------------------------------

## Technical Plan (Architecture)

### 1. Components and data flow

```text
+---------------------------------------------+
| Browser                                     |
|   cce-admin-ui (:3002)  cce-insights-ui     |
|                         (:3001)             |
+-----+-------------------------+-------------+
      | (1)                     | (2)
      v                         v
+-------------+    (4)    +------------------+
| Keycloak    |<----------+ gateway-service  |
| realm cce   |           | (not changed)    |
+-------------+           +--+-----------+---+
      ^                      | (3)       ^
      | (6)                  v           | (5)
      |               +------------------+---+
      +---------------+ cce-admin-service    |
                      | (:8085)              |
                      +----------+-----------+
                                 | (7)
                                 v
                      +----------------------+
                      | PostgreSQL           |<-- (8) gateway reads
                      | db admin_service,    |        api_permissions
                      | schema public        |
                      +----------------------+
```

| Arrow | Meaning |
|---|---|
| (1) | Both consoles sign in to Keycloak with Authorization Code and PKCE. |
| (2) | Console API calls with the bearer token, to `/admin/v1/**`. |
| (3) | The gateway checks the token and the caller's roles against `api_permissions`, then forwards `/admin/**` to the service. |
| (4) | A gateway sync creates (and may delete) realm role definitions in Keycloak. |
| (5) | The service calls the gateway's sync endpoint with its own token. |
| (6) | The service calls the Keycloak Admin REST API: users, role mappings, action emails, role lookups. |
| (7) | The service reads and writes its seven tables through JPA. |
| (8) | The gateway reads `api_permissions` over R2DBC. |

*   **Request path.** In non-local environments, a console reaches the service only through
    gateway-service (PRD §9 Network). Locally there is no gateway: the console's Vite dev proxy
    forwards `/admin/v1/*` straight to `localhost:8085` (D-03), and the E2E checks call port 8085
    directly.
*   **What the service writes.** Its six own tables and `api_permissions`, through JPA, with one
    database transaction per database step of a flow (section 5). It also writes to Keycloak
    through the Keycloak Admin REST API, using a confidential client with a service account: it
    creates users, enables and disables them, adds and removes realm role mappings, ends
    sessions, and sends action emails.
*   **What the service does not do.** It never creates or deletes realm role *definitions*. After
    a role is created and after its permissions are saved, it calls the gateway's sync endpoint,
    and the gateway creates the realm roles (PRD §4).
*   **How a role reaches a token.** A role's `api_permissions` rows carry the role's `name` as
    `permission_name`. The gateway sync creates a Keycloak realm role with that name. Once the
    realm role is assigned to a user, the user's token lists it in `realm_access.roles`, and the
    gateway and the service's own check (section 3, step 5) use it to select that role's rows.

### 2. Service structure

The package layout is fixed by PRD §6: `controller/ service/ repository/ entity/ security/
client/ audit/ config/`, under the base package `org.cce.admin`.

| Package | Holds | Rule |
|---|---|---|
| `controller` | REST controllers, request and response records (`controller/dto`), the `@RestControllerAdvice` that maps exceptions to error bodies | Shapes HTTP only. Calls exactly one service method per endpoint. No repository or client access. |
| `service` | One service class per resource (users, roles, role menus, role permissions, menus, current user, health), the gateway sync procedure, account linking, the bootstrapper, the domain exceptions | Owns transactions, validation that needs the database, the order of operations in each flow, and the audit calls. |
| `repository` | Spring Data JPA repositories | Queries only. |
| `entity` | JPA entities for the seven tables | No business logic. |
| `security` | JWT decoder and validators, the roles reader, the local-user filter, the permission filter, the 401 and 403 writers | Runs before any controller. |
| `client` | `KeycloakGateway` (interface and its `keycloak-admin-client` implementation) and `GatewaySyncClient` (interface and its HTTP implementation) | The only code that talks to Keycloak or gateway-service. Service tests mock both interfaces (PRD §9 Testability). |
| `audit` | `AuditWriter`, `ActorProvider` and the action names | The only code that inserts into `audit_log`. |
| `config` | Typed configuration properties, Jackson, springdoc and Keycloak client beans | No logic. |

The service uses blocking Spring Web MVC (PRD §6). Every endpoint runs in one request thread.
The calls to Keycloak and the gateway are synchronous, with timeouts set in configuration.

### 3. Request pipeline and security (B-05, B-10 to B-13)

Every request passes these steps in order. A request that fails a step gets the response named
in that step, and later steps do not run.

1.  **Public paths.** `GET /admin/v1/health`, the OpenAPI document (`/admin/v1/api-docs/**`)
    and Swagger UI (`/admin/v1/swagger-ui/**`) need no token, and skip steps 2 to 5. Every other
    path needs one.
2.  **Token check (B-10).** Spring Security's OAuth2 resource server decodes the bearer token.
    It checks the signature against the realm's JWKS (`JWT_JWK_SET_URI`), the issuer against
    `JWT_ISSUER`, the audience against `JWT_AUDIENCE` (the token's `aud` must contain it), and
    expiry and not-before, with 30 seconds of allowed clock skew. A missing, malformed, wrongly
    signed, wrongly addressed or expired token gets 401 `UNAUTHENTICATED`, written in the error
    envelope by a custom entry point. Spring's default `WWW-Authenticate` header is kept.
3.  **Roles (B-11).** The roles reader takes the role names from the claim at
    `JWT_ROLES_CLAIM_PATH` (default `realm_access.roles`). The path is split on `.` and each part
    is looked up in the nested JSON objects of the token. If the claim is missing or is not a
    list of strings, the caller has no roles. The request does not fail.
4.  **Local user (B-12).** A filter looks up `users` by `keycloak_id` equal to the token's `sub`.
    If there is no row, or its `status` is not `ACTIVE`, the response is 403 `FORBIDDEN`.
    Otherwise it runs the `last_login_at` update (AD-05): a conditional `UPDATE`, in its own
    short transaction, that sets `last_login_at` to the token's `auth_time` only where
    `last_login_at` is null or earlier. It does not change `updated_at`, writes no audit row, and
    does nothing for a token without `auth_time`. The filter then stores a request-scoped
    `CurrentUser`: the local user id, username, `keycloak_id` and the role names from step 3.
5.  **Permission check (AD-17).** Skipped when `PERMISSION_CHECK_ENABLED` is `false`. Otherwise
    the service runs one query, using the index `idx_permission_name`: the `api_permissions` rows
    whose `permission_name` is one of the caller's role names and whose `http_method` equals the
    request method. It matches the request path (the URI path without the query string) against
    each row's `uri_pattern` with Spring's `AntPathMatcher`, default settings: `*` matches within
    one segment, `**` matches any number of segments, and matching is case-sensitive. If no row
    matches, the response is 403 `FORBIDDEN` with the message
    "Your roles do not allow {METHOD} {path}." There is no cache, so a saved permission is in
    effect at the service immediately. Startup logs the switch's value, and logs a warning when
    it is off that only the gateway now enforces permissions.
6.  **Controller.** Request bodies and query parameters are validated with Bean Validation
    before the service runs (400 `VALIDATION_ERROR`).

**Rules for the two filters of steps 4 and 5.** Each filter passes the request on unchanged
unless its authentication is a `JwtAuthenticationToken`, so a request with no token reaches
Spring Security's own 401 handling (step 2). Step 5 returns 403 without running its query when
the caller has no role names. An unexpected exception inside either filter, such as a database
outage, is caught there and answered with 500 `INTERNAL_ERROR` in the error envelope, because
exceptions thrown in filters never reach the controller advice.

**Configuration parity (PRD §9).** `JWT_ISSUER`, `JWT_AUDIENCE` and `JWT_ROLES_CLAIM_PATH` must
hold the same values as gateway-service's configuration for the same realm. Locally they are
`http://localhost:8180/auth/realms/cce`, `gateway-service` and `realm_access.roles` (section 9).
The audience `gateway-service` is the one the existing gateway checks for cce-insights-ui
([insights-ui architecture §3](../../context/cce-insights-ui/docs/architecture-overview.md)).

**Client IP (AD-19).** `server.forward-headers-strategy` is `native`, so Tomcat's own
forwarded-header handling sets the request's remote address. It trusts `X-Forwarded-For` only
from the proxy addresses matched by `TRUSTED_PROXIES` (a regular expression, bound to
`server.tomcat.remoteip.internal-proxies`; the default matches no address). The audited IP is the
request's remote address after that handling. Locally no proxy is trusted, so the audited
address is the direct caller's.

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
*   **Strings.** Every request string is trimmed before it is validated, and lengths are counted
    after trimming. An optional string that is empty after trimming is treated as null.
*   **Null fields.** Only four optional fields are left out of a response when they have no
    value: `pagination`, `warnings`, `details` and `partialState`. Every other field is always
    written, as `null` when it has no value. The one exception is a warning's reference fields:
    each warning carries exactly the fields its `code` defines (see Warning below).
*   **Identifiers.** User, role, menu and audit ids are UUID strings. Permission row ids are
    integers, because `api_permissions.id` is `BIGSERIAL` (DEV-1). An id in the path that is not
    a valid UUID gets 400 `VALIDATION_ERROR`, with the path parameter name as the field.
*   **Times.** Every timestamp is an ISO-8601 instant in UTC with a `Z` suffix, for example
    `2026-11-10T09:15:00.123Z`. Clients must accept from 0 to 6 fractional digits.
*   **Success envelope.** Every 200 or 201 response body is
    `{ "data": <payload>, "pagination"?: <Pagination>, "warnings"?: [<Warning>] }` (DEV-5).
    `pagination` appears only on paginated lists, and `warnings` only when there is at least one
    warning.
*   **Warning.** A union on `code`. Every warning has `code` and `message`.
    `DETAIL_WITHOUT_PARENT` also always has `menuId` and `parentMenuId`, both UUIDs. The other two
    codes have neither field, not even as `null`:
    `{ "code": "DETAIL_WITHOUT_PARENT", "message": string, "menuId": uuid, "parentMenuId": uuid }`
    or `{ "code": "ACTION_EMAIL_NOT_SENT" | "ROLE_SYNC_FAILED", "message": string }`.

    | Warning code | Returned by | Reference fields |
    |---|---|---|
    | `DETAIL_WITHOUT_PARENT` | `PUT /roles/:id/menus`, one warning per `DETAIL` menu granted without its parent `NAV` (B-41) | `menuId`, `parentMenuId` |
    | `ACTION_EMAIL_NOT_SENT` | `POST /users`, when the user was created or linked but the set-password email could not be sent (AD-08) | none (the user is in `data`) |
    | `ROLE_SYNC_FAILED` | `POST /roles`, when the role was created but its first gateway sync failed (AD-16). The role's `syncStatus` is `FAILED`. Use Re-sync before assigning it. | none (the role is in `data`) |

*   **Responses without the envelope.** `DELETE /roles/:id` and `DELETE /menus/:id` return 204
    with no body. `GET /health` returns `{ "status": "UP", "database": "UP" }` (or
    `"DOWN"`) without the envelope (DEV-5). Every other success response uses the envelope.
*   **Error envelope.** Every error response body is
    `{ "error": { "code": string, "message": string, "details"?: [<FieldError>], "partialState"?: <PartialState> } }`
    (DEV-9).
    *   `message` is an English sentence for people. Clients branch on `code`, never on `message`.
    *   `details` appears with `VALIDATION_ERROR`, and with `ROLE_NOT_SYNCED` and
        `ROLE_NOT_ASSIGNABLE`, where there is one entry per role concerned
        (`field` = `roleIds[i]`). Each `FieldError` is `{ "field": string, "message": string }`.
        `field` is the path of the property in the request body, written as in the JSON
        (`email`, `roleIds[1]`, `permissions[2].uriPattern`), or the name of the query or path
        parameter (`pageSize`, `id`). A body that is not valid JSON gives one detail with
        `field` set to `body`.
    *   `partialState` appears only with `KEYCLOAK_SYNC_FAILED` (section 4.2).
*   **Concurrent edits (AS-REQ-21).** No resource has a version number or ETag, and no endpoint
    reads `If-Match`. When two administrators save the same resource, the later save wins. Every
    replace endpoint (`PUT`) writes the complete state it receives. Optimistic concurrency is a
    noted follow-up (Future Improvements).

#### 4.2 Error codes

Every code in PRD Appendix B is produced. The codes marked *added* are not in Appendix B
(DEV-6).

| HTTP | Code | When |
|---|---|---|
| 400 | `VALIDATION_ERROR` | A body, query or path value fails validation. Also: unknown body property, malformed JSON, unsupported content type, a body id (`roleIds`, `menuIds`, `parentId`) that names no existing row. |
| 401 | `UNAUTHENTICATED` | Missing, malformed, wrongly signed, wrongly addressed or expired token. |
| 403 | `FORBIDDEN` | No local user for `sub`, local user not `ACTIVE`, or no `api_permissions` row of the caller's token roles matches the method and path (section 3, step 5). |
| 404 | `USER_NOT_FOUND` | The user id in the path does not exist. |
| 404 | `ROLE_NOT_FOUND` | The role id in the path does not exist. |
| 404 | `MENU_NOT_FOUND` | The menu id in the path does not exist. |
| 404 | `NOT_FOUND` *(added)* | No endpoint matches the path. |
| 405 | `METHOD_NOT_ALLOWED` *(added)* | The path exists but not for this HTTP method. |
| 409 | `EMAIL_IN_USE` | Another local user has this email, or a Keycloak account that cannot be linked has it (section 5.1). In the Keycloak case the message says that the address belongs to a Keycloak account with no local user that cannot be linked, and why. |
| 409 | `USERNAME_IN_USE` *(added)* | As `EMAIL_IN_USE`, for the username. Needed because username and email are separate fields. |
| 409 | `DUPLICATE_ROLE_NAME` | A role with this name exists, or `api_permissions` already has rows with this `permission_name`, or a Keycloak realm role with this name exists (section 5.6). |
| 409 | `DUPLICATE_MENU_KEY` | A menu with this key exists. |
| 409 | `ROLE_IN_USE` | Delete of a role that a local user (of any status) or a Keycloak user holds. |
| 409 | `SYSTEM_ROLE_IMMUTABLE` | `PUT /roles/:id`, `PUT /roles/:id/menus`, `PUT /roles/:id/permissions` or `DELETE /roles/:id` on an `is_system` role (AD-07). |
| 409 | `LAST_SYSTEM_ADMIN` *(added)* | A status change or role change that would leave no `ACTIVE` user holding `SUPER_ADMIN` (AD-07). |
| 409 | `ROLE_NOT_ASSIGNABLE` *(added)* | A role being assigned has `is_assignable = false`. `details` names the roles (AD-14). |
| 409 | `ROLE_NOT_SYNCED` *(added)* | A role being assigned has no realm role in Keycloak yet. `details` names the roles (AD-10). |
| 409 | `MENU_HAS_CHILDREN` *(added)* | Delete of a menu that still has child menus. |
| 502 | `KEYCLOAK_SYNC_FAILED` | A call to Keycloak or to the gateway sync failed. The state may be partially applied. Carries `partialState`. Also used when the failure came before anything changed (DEV-8). |
| 500 | `INTERNAL_ERROR` *(added)* | Any other failure. The message is generic, and the stack trace is only logged. |

There is no code for an invalid status change, because every change between the three
statuses is allowed (AD-04). A status value outside the three gets 400 `VALIDATION_ERROR`.

**`PartialState` (AS-REQ-06, AS-REQ-18).**
`{ "operation": string, "entityId": uuid, "failedStep": string, "completedSteps": [string], "keycloakUserId": uuid | null, "auditLogId": uuid | null }`.

*   `operation` names the flow that failed, so a console can choose its message and the screen
    to show it on without parsing `message`.
*   `entityId` is the id of the user or role concerned. For `POST /users` it is the id the new
    user gets: the service assigns it before the first step, and a 502 on `POST /users` always
    comes before the local row exists.
*   `failedStep` and `completedSteps` use the step names of the flow in section 5.
*   `keycloakUserId` is set when a Keycloak user exists that the local database may not reflect.
*   `auditLogId` follows the single audit rule (section 8): it is the id of the row that records
    the failure, or `null` when nothing was written.

| `operation` | Endpoint | Where the console shows it | What is saved, and the next step |
|---|---|---|---|
| `USER_CREATE` | `POST /users` | User create form | The Keycloak account may exist without a local row. Submit the same request again: the account is linked and the flow completes (section 5.1). |
| `USER_PROFILE_UPDATE` | `PUT /users/:id` | User edit form, profile section | Keycloak may hold the new values and the local row the old ones. Submit the same request again. |
| `USER_STATUS_CHANGE` | `PATCH /users/:id/status` | Users list row action, and user edit form | Keycloak may already be enabled or disabled. Submit the same request again. |
| `USER_ROLES_UPDATE` | `PUT /users/:id/roles` | User edit form, roles section | Some realm role mappings may have changed. Submit the same request again. |
| `USER_PASSWORD_RESET` | `POST /users/:id/reset-password` | Users list row action, and user edit form | Nothing changed. Try again. |
| `ROLE_PERMISSIONS_SAVE` | `PUT /roles/:id/permissions` | Role detail, Permissions tab | The rows are saved but not synced. Use Re-sync, and do not assign the role until a sync succeeds. |
| `ROLE_SYNC` | `POST /roles/:id/sync` | Role detail, Permissions tab | The rows were already saved. Try Re-sync again. |

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
| `syncStatus` | `NEVER`, `SYNCED` or `FAILED` | The outcome of the last gateway sync attempt made for this role (AD-06). `SYNCED` means that call got a 2xx, not that the gateway enforces the rows (GW-2). A display hint only: the Keycloak check at assignment is authoritative (AD-10). |
| `lastSyncAt` | instant or null | Time of that attempt. |
| `userCount` | integer | Number of local users holding the role, of any status. |
| `createdAt`, `updatedAt` | instant | A sync does not change `updatedAt`. |

**Menu** (AS-REQ-13).

| Field | Type | Notes |
|---|---|---|
| `id` | uuid | |
| `key` | string | Up to 100 characters, pattern `^[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)*$`. Unique. Cannot be changed after creation. |
| `label` | string | 1 to 100 characters. |
| `path` | string | 1 to 255 characters, starts with `/`, made only of letters, digits and `/ _ - . :`. Relative to the console's router base. `:` marks a route parameter, as in `/compliance/patients/:id`. |
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
| `httpMethod` | string | One of exactly `GET`, `POST`, `PUT`, `PATCH`, `DELETE`. This list is fixed by the contract and is not configurable, so a console can offer exactly these five ([console design, UI-AD-05, formerly CQ-1](../../docs/designs/cce-admin-ui.md#author-decisions-in-this-design)). Accepted in any case and stored in upper case. No wildcard method. |
| `uriPattern` | string | 1 to 255 characters. Starts with `/`, has no empty segment (`//`), and is made only of letters, digits and `- . _ ~ / *`. `**` may only be the whole last segment. Matching follows section 3, step 5. There is no prefix rule: patterns for other services, such as `/v1/insights/**`, are valid, because the gateway guards those too. |
| `description` | string or null | Up to 1000 characters. |
| `resourceCategory` | string or null | Up to 50 characters, free text. |
| `implicit` | boolean | Response only. `true` for the two rows every non-system role keeps (AD-16). |

**Implicit rows (AD-16).** Every non-system role always holds two `api_permissions` rows:
`GET /admin/v1/me` (description "Read own profile", category `Self`) and
`GET /admin/v1/me/menus` (description "Read own menu tree", category `Self`). There is no
`implicit` column, because `api_permissions` keeps the gateway's shape. A row is implicit when
its `http_method` and `uri_pattern` equal one of these two. System roles have none:
`SUPER_ADMIN` is covered by `/admin/v1/**`, and `CCE_ADMIN_SERVICE` is not a console role.

**Me** (AS-REQ-20). `{ "id": uuid, "username": string, "roles": [string], "facilityScope": string | null }`.
`roles` holds the caller's token roles (section 3, step 3) that exist in `roles`, ordered by
name. Keycloak's own roles, such as `offline_access`, are left out. The token is the source,
as it is at the gateway and in step 5.

**Menu tree item** (for `GET /me/menus`).
`{ "id": uuid, "key": string, "label": string, "path": string, "icon": string | null, "type": "NAV" | "DETAIL", "sortOrder": int, "children": [item] }`.

**Named result records.** `ResetPasswordResult` is `{ "userId": uuid, "email": string }`.
`RoleSyncResult` is `{ "roleId": uuid, "syncStatus": "NEVER" | "SYNCED" | "FAILED", "lastSyncAt": instant }`.
`RolePermissions` is `{ "roleId": uuid, "syncStatus": "NEVER" | "SYNCED" | "FAILED", "lastSyncAt": instant | null, "permissions": [Permission row] }`.
`RoleMenus` is `{ "roleId": uuid, "menuIds": [uuid] }`.

#### 4.5 Endpoints

Paths are relative to `/admin/v1`. Every endpoint except health can also return 400
`VALIDATION_ERROR`, 401 `UNAUTHENTICATED`, 403 `FORBIDDEN` and 500 `INTERNAL_ERROR`. The table
lists only the other codes. "No-op" means the request asks for the state that already exists:
the response is 200 with the current resource, and nothing is written, audited or sent to
Keycloak or the gateway.

| Method and path | Request | Success | Other errors |
|---|---|---|---|
| `GET /health` | None. No token. | 200 `{ "status": "UP", "database": "UP" or "DOWN" }`. `database` comes from `SELECT 1`, with at most 2 seconds to get a connection and 2 seconds to run. B-05. | None. |
| `GET /me` | None. | 200, Me. B-20. | None. |
| `GET /me/menus` | None. | 200, `data`: array of menu tree items. B-21, rules below. | None. |
| `GET /users` | Query: `status`, `roleId` (uuid), `facilityScope` (exact, AD-09), `q`, `page`, `pageSize`. | 200, `data`: array of User, `pagination`. B-30. | None. |
| `POST /users` | `{ "username", "email", "firstName", "lastName", "facilityScope"?, "roleIds": [uuid] }`. `roleIds` has 1 to 20 distinct ids of existing roles. | 201, `Location: /admin/v1/users/{id}`, `data`: User. Creates a Keycloak account, or links an existing one (section 5.1). Maybe the warning `ACTION_EMAIL_NOT_SENT`. B-31. | 409 `USERNAME_IN_USE`, `EMAIL_IN_USE`, `ROLE_NOT_ASSIGNABLE`, `ROLE_NOT_SYNCED`. 502 (`USER_CREATE`). |
| `GET /users/:id` | None. | 200, User. | 404 `USER_NOT_FOUND`. |
| `PUT /users/:id` | `{ "email", "firstName", "lastName", "facilityScope" }`. All four are replaced, so a missing or null `facilityScope` clears it. | 200, User. No-op if nothing changed. Flow 5.2. B-33. | 404 `USER_NOT_FOUND`. 409 `EMAIL_IN_USE`. 502 (`USER_PROFILE_UPDATE`). |
| `PATCH /users/:id/status` | `{ "status": "ACTIVE" or "INACTIVE" or "LOCKED" }`. | 200, User. No-op if unchanged. Flow 5.3. B-34. | 404 `USER_NOT_FOUND`. 409 `LAST_SYSTEM_ADMIN`. 502 (`USER_STATUS_CHANGE`). |
| `PUT /users/:id/roles` | `{ "roleIds": [uuid] }`. 0 to 20 distinct ids of existing roles: the complete new set. | 200, User. No-op if the set is unchanged. Flow 5.4. B-35. | 404 `USER_NOT_FOUND`. 409 `ROLE_NOT_ASSIGNABLE`, `ROLE_NOT_SYNCED`, `LAST_SYSTEM_ADMIN`. 502 (`USER_ROLES_UPDATE`). |
| `POST /users/:id/reset-password` | No body. | 200, `data`: `ResetPasswordResult`, with the address the email was sent to. Allowed for any status. An `INACTIVE` or `LOCKED` user can only use the link after being re-activated. Flow 5.5. B-36. | 404 `USER_NOT_FOUND`. 502 (`USER_PASSWORD_RESET`). |
| `GET /roles` | Query: `page`, `pageSize`. | 200, `data`: array of Role, `pagination`. | None. |
| `POST /roles` | `{ "name", "displayName", "description"? }`. | 201, `Location`, `data`: Role. If the first sync failed, the warning `ROLE_SYNC_FAILED`. Flow 5.6. B-40. | 409 `DUPLICATE_ROLE_NAME` (section 4.2). |
| `GET /roles/:id` | None. | 200, Role. | 404 `ROLE_NOT_FOUND`. |
| `PUT /roles/:id` | `{ "displayName", "description" }`. Sending `name` is an unknown property, so 400. | 200, Role. No-op if unchanged. B-40. | 404 `ROLE_NOT_FOUND`. 409 `SYSTEM_ROLE_IMMUTABLE`. |
| `DELETE /roles/:id` | None. | 204. Flow 5.9. | 404 `ROLE_NOT_FOUND`. 409 `SYSTEM_ROLE_IMMUTABLE`, `ROLE_IN_USE`. |
| `GET /roles/:id/menus` | None. | 200, `data`: `RoleMenus`. Includes inactive menus that were granted (AS-REQ-15). | 404 `ROLE_NOT_FOUND`. |
| `PUT /roles/:id/menus` | `{ "menuIds": [uuid] }`. 0 to 500 distinct ids of existing menus. Inactive menus may be granted. | 200, `data`: `RoleMenus`. `warnings`: one `DETAIL_WITHOUT_PARENT` per `DETAIL` menu whose parent is not in the set, also returned on a no-op. Flow 5.11. B-41. | 404 `ROLE_NOT_FOUND`. 409 `SYSTEM_ROLE_IMMUTABLE`. |
| `GET /roles/:id/permissions` | None. | 200, `data`: `RolePermissions`. Implicit rows come first, then explicit rows ordered by `uriPattern` and `httpMethod`. | 404 `ROLE_NOT_FOUND`. |
| `PUT /roles/:id/permissions` | `{ "permissions": [{ "httpMethod", "uriPattern", "description"?, "resourceCategory"? }] }`. 0 to 200 rows, which become the role's complete set of *explicit* rows. Rows equal in method and pattern to an implicit row are accepted and dropped (AD-16). Two rows with the same method and pattern give 400, with `field` `permissions[i].uriPattern` on the later one. | 200, `data`: `RolePermissions`, after the sync succeeded. No-op, with no sync, if the explicit rows are unchanged. Flow 5.7. B-42. | 404 `ROLE_NOT_FOUND`. 409 `SYSTEM_ROLE_IMMUTABLE`. 502 (`ROLE_PERMISSIONS_SAVE`): the rows are saved. The console calls GET again to show them (AS-REQ-18). |
| `POST /roles/:id/sync` | No body. Allowed for system roles. | 200, `data`: `RoleSyncResult`. Flow 5.8. B-43. | 404 `ROLE_NOT_FOUND`. 502 (`ROLE_SYNC`). |
| `GET /menus` | None. | 200, `data`: array of top-level Menu with `children`. Includes inactive menus. B-44. | None. |
| `POST /menus` | `{ "key", "label", "path", "icon"?, "type", "parentId"?, "sortOrder", "isActive"? }`. `isActive` defaults to `true`. | 201, `Location`, `data`: Menu. | 409 `DUPLICATE_MENU_KEY`. A `NAV` with a parent, or a `DETAIL` without a `NAV` parent, gives 400. |
| `PUT /menus/:id` | `{ "label", "path", "icon", "parentId", "sortOrder", "isActive" }`. `key` and `type` are unknown properties here. Deactivating a menu is this call with `isActive: false` (AS-REQ-14). | 200, Menu. No-op if unchanged. | 404 `MENU_NOT_FOUND`. Parent rules as for POST give 400. |
| `DELETE /menus/:id` | None. | 204. Also deletes the menu's `role_menus` rows. B-44. | 404 `MENU_NOT_FOUND`. 409 `MENU_HAS_CHILDREN`. |

**Rules for `GET /me/menus` (B-21).**

1.  Take the caller's roles as in Me.
2.  Take the union of the menus granted to those roles, and keep only the active ones.
3.  Put the `NAV` menus at the top level. Put each `DETAIL` menu under its parent, but only
    when the parent is in the result too. A `DETAIL` whose parent is not granted, or not active,
    is left out (DEV-10). PRD §4 says a `DETAIL` inherits its parent's visibility, and this is
    what the `DETAIL_WITHOUT_PARENT` warning tells administrators.
4.  Order siblings by `sortOrder`, then `label`.
5.  A caller with no matching roles gets `data: []`.

**Search on `GET /users` (B-30, AS-REQ-09).**

*   **What `q` matches:** a case-insensitive substring of `username`, `email`, `firstName`,
    `lastName`, or `firstName + " " + lastName`.
*   **Length:** 1 to 100 characters. An empty `q` is ignored, and there is no minimum length.
*   **Combining filters:** `status`, `roleId` and `facilityScope` must all match. A `roleId` that
    names no role returns an empty page, not 404. Unknown query parameters are ignored.

#### 4.6 Answers to the console design's requirements

The requirements are listed in the [console design](../../docs/designs/cce-admin-ui.md).

| ID | Answer |
|---|---|
| AS-REQ-01 | `VALIDATION_ERROR` carries `details`: `[{ field, message }]`, with `field` written as the request body property path (4.1). Every code is in 4.2, including `USER_NOT_FOUND`, `ROLE_NOT_FOUND` and `MENU_NOT_FOUND`. |
| AS-REQ-02 | The block is `{ page, pageSize, totalItems, totalPages }`. `page` starts at 1. A page past the end returns 200 with `data: []` (4.3). |
| AS-REQ-03 | Only `GET /users` and `GET /roles` are paginated. For every role, call `GET /roles?pageSize=100` and follow `totalPages`. Role menus and role permissions are not paginated (4.3). |
| AS-REQ-04 | User shape in 4.4. `lastLoginAt` is a nullable ISO-8601 UTC instant (AD-05). Roles are `{ id, name, displayName }`. |
| AS-REQ-05 | `username` is separate from `email`. Required: `username`, `email`, `firstName`, `lastName`, and 1 to 20 `roleIds`. Optional: `facilityScope` (4.5). An existing Keycloak account with the same username and email is linked (AD-18). The user receives a Keycloak email to set a password. If it could not be sent, the 201 carries `ACTION_EMAIL_NOT_SENT`, and the administrator can use reset-password (AD-08). |
| AS-REQ-06 | The 502 carries `partialState` with `operation` `USER_CREATE`, `entityId`, `failedStep`, `completedSteps`, `keycloakUserId` and `auditLogId` (4.2, flow 5.1). Submitting the same request again completes the creation, because the Keycloak account is then linked. |
| AS-REQ-07 | The statuses are `ACTIVE`, `INACTIVE` and `LOCKED`, and every change between them is allowed. `LOCKED` is an administrator's temporary block. Both blocked statuses disable the Keycloak account and end its sessions. Asking for the current status is a no-op. There is no transition error. 409 `LAST_SYSTEM_ADMIN` protects the last administrator (AD-04, AD-07). |
| AS-REQ-08 | One optional free-text string of up to 50 characters. The filter matches exactly. There is no list endpoint (AD-09). |
| AS-REQ-09 | Search rules under 4.5. |
| AS-REQ-10 | `Role.syncStatus` and `Role.lastSyncAt` survive a reload (AD-06). Assigning a role whose realm role is missing gives 409 `ROLE_NOT_SYNCED`, with `details` naming the roles, and nothing changes (AD-10). |
| AS-REQ-11 | Role shape and name rule in 4.4. |
| AS-REQ-12 | For a system role, display fields, menus and permissions cannot be edited, and the role cannot be deleted. Sync and assignment are allowed, unless `isAssignable` is false (AD-07, AD-14). |
| AS-REQ-13 | Menu shape in 4.4. `icon` is a Heroicons 2 outline component name. `GET /menus` includes inactive menus. `DETAIL` menus sit only under a `NAV`, so the tree has at most two levels. |
| AS-REQ-14 | Deactivate with `PUT /menus/:id` and `isActive: false`. `DELETE` exists (B-44) and is refused while children exist. `key` and `type` cannot be changed. The create and update fields are in 4.5. |
| AS-REQ-15 | Inactive menus can be granted. `GET /roles/:id/menus` returns them. `GET /me/menus` leaves them out. |
| AS-REQ-16 | The request is `{ menuIds }`, and GET returns `RoleMenus`, `{ roleId, menuIds }`. Warnings are in the top-level `warnings` array as `DETAIL_WITHOUT_PARENT` with `menuId` and `parentMenuId` (4.1). |
| AS-REQ-17 | Permission row shape and rules in 4.4 and 4.5. The five methods are fixed. `**` may only be the last segment, and matching follows `AntPathMatcher` (section 3, step 5). Category is free text. A duplicate row is a 400 `VALIDATION_ERROR` on `permissions[i].uriPattern`. There is no prefix rule and no prefix warning. Implicit rows are flagged (AD-16). |
| AS-REQ-18 | Confirmed: 200 only when the save and the sync both succeed. 502 `KEYCLOAK_SYNC_FAILED` when the rows are saved but the sync failed. The 502 body does not include the rows, so the console refetches them (AD-06). |
| AS-REQ-19 | The success responses are in 4.5: sync returns 200 with `RoleSyncResult`, reset-password returns 200 with `ResetPasswordResult`, status returns 200 with the User, and role delete returns 204. Role create returns 201, with the warning `ROLE_SYNC_FAILED` when its first sync failed. |
| AS-REQ-20 | Me is `{ id, username, roles: [string], facilityScope }` (4.4). |
| AS-REQ-21 | There are no versions or ETags. The last write wins (4.1). |
| AS-REQ-22 | See section 9.3. In short: the Keycloak URL is `http://localhost:8180/auth`, set through `VITE_KEYCLOAK_URL` in the console's `.env` and not proxied; the realm is `cce`; the test administrator comes from AD-15; tokens from `cce-admin-ui` carry the audience `gateway-service`, which the service checks. |
| AS-REQ-23 | `PUT http://localhost:8090/_stub/mode` with body `fail` or `ok`, and `GET` on the same URL to read it. No restart is needed (AD-11, section 9.3). |
| AS-REQ-24 | Reset with `docker compose --profile app down -v`, then start again (9.3). The test administrator's credentials are in `CCE_TEST_ADMIN_USERNAME` and `CCE_TEST_ADMIN_PASSWORD`, from the git-ignored `.env` (AD-15). |

### 5. Flows and order of operations

These flows replace the PRD's flow diagrams. Each step that can leave a partial state has a
name, used in `partialState.failedStep` and `completedSteps`.

**Rules that apply to every flow:**

*   **No compensation.** No flow undoes an earlier Keycloak step after a later step fails (B-32,
    PRD §9).
*   **Recovery by resubmitting.** Flows 5.1 to 5.4 complete when the same request is submitted
    again after a 502. 5.1 links the account the first attempt created. In 5.2 to 5.4 every
    Keycloak step sets a target state (profile values, enabled flag, a mapping present or
    absent), so repeating it is harmless. The service itself never retries.
*   **Audit.** Rows follow the single audit rule in section 8.
*   **Transaction boundaries.** Each database step is one transaction, run through a
    `TransactionTemplate`, and its audit row is written in the same transaction.
*   **Keycloak calls** use the timeouts `KEYCLOAK_CONNECT_TIMEOUT` (default 2 s) and
    `KEYCLOAK_READ_TIMEOUT` (default 10 s). A Keycloak 409 on create or update means a conflict.
    A 404 on a lookup means "not found". Any other non-2xx status, or a timeout, is a failure.
*   **Locks.** Flows 5.2 to 5.4 run inside one database transaction that starts with
    `SELECT … FOR UPDATE` on the user's `users` row, so two requests for the same user run one
    after the other, and the flow's checks read the state after the lock. Only when a change can
    lower the number of `ACTIVE` users holding `SUPER_ADMIN` (a holder leaving `ACTIVE`, or
    `SUPER_ADMIN` being removed) does the flow then also lock the `SUPER_ADMIN` row in `roles`,
    so two administrators cannot remove each other at the same time. The Keycloak calls run while
    the transaction is open, and the local write commits at the end.
*   **Logging.** Every 502 writes one structured log event at `ERROR` with the `operation`,
    `entityId`, `failedStep`, `completedSteps`, `keycloakUserId` and `auditLogId`.

#### 5.1 Create user (`POST /users`, operation `USER_CREATE`)

1.  Validate the body. Load the roles by id. An unknown id gives 400. A role with
    `is_assignable = false` gives 409 `ROLE_NOT_ASSIGNABLE`. Assign the new user's id.
2.  Check that the username and the email (ignoring case) are free in `users`. Otherwise 409.
3.  Look up Keycloak accounts with this username or this email.
    *   If one enabled account has exactly this username and this email, and no local user has
        its id, it will be **linked**: step 4 is skipped.
    *   Otherwise, if any account has this username or this email, return 409 `USERNAME_IN_USE`
        or `EMAIL_IN_USE`, with the message saying why the account cannot be linked (it is
        disabled, or the other field differs).
    *   Then list the realm role names once. A requested role whose realm role is missing gives
        409 `ROLE_NOT_SYNCED`.
4.  **`KEYCLOAK_USER_CREATED`**: create the Keycloak user, enabled, with `emailVerified = false`
    and the required action `UPDATE_PASSWORD`. A Keycloak 409 here (a race) is resolved by
    looking up the username: if it exists the response is `USERNAME_IN_USE`, otherwise
    `EMAIL_IN_USE`. Nothing exists in that case.
5.  **`KEYCLOAK_ROLES_ASSIGNED`**: add all the realm role mappings in one call.
6.  **`LOCAL_USER_SAVED`**: insert `users` (`ACTIVE`) and `user_roles`, and write `USER_CREATED`,
    with `after.linked` set to `true` when the account was linked, in one transaction.
7.  Send the execute-actions email (`UPDATE_PASSWORD`, lifespan `KEYCLOAK_ACTION_EMAIL_LIFESPAN`,
    default 43200 s, with optional `KEYCLOAK_ACTION_EMAIL_CLIENT_ID` and
    `KEYCLOAK_ACTION_EMAIL_REDIRECT_URI`). A failure here adds the warning
    `ACTION_EMAIL_NOT_SENT` (AD-08).
8.  Return 201.

A failure in step 5 or 6 gives a 502 whose `keycloakUserId` is the Keycloak user (B-32).
Steps 5 and 6 are the **linking method**, `AccountLinker.link`, which the bootstrapper also uses
(5.13).

#### 5.2 Update profile (`PUT /users/:id`, `USER_PROFILE_UPDATE`)

1.  Validate the body. Lock and load the user. A changed email must be free in `users`,
    ignoring case.
2.  If nothing changed, it is a no-op.
3.  **`KEYCLOAK_PROFILE_UPDATED`**, only when the email, first name or last name changed:
    update the Keycloak user. A changed email sets `emailVerified = false`. A Keycloak 409 gives
    `EMAIL_IN_USE`.
4.  **`LOCAL_PROFILE_SAVED`**: update `users` and write `USER_UPDATED`. If this fails after
    step 3, Keycloak holds the new values and the local row the old ones, and the failure row
    records both.

#### 5.3 Change status (`PATCH /users/:id/status`, `USER_STATUS_CHANGE`)

1.  Lock and load the user. If the status is unchanged, it is a no-op.
2.  If the user is `ACTIVE`, holds `SUPER_ADMIN` and the new status is not `ACTIVE`, lock the
    `SUPER_ADMIN` row. If no other `ACTIVE` user holds `SUPER_ADMIN`, return 409
    `LAST_SYSTEM_ADMIN`.
3.  **`KEYCLOAK_ENABLED_CHANGED`**, only when Keycloak's `enabled` flag changes: set it to
    `true` for `ACTIVE` and `false` otherwise. A change between `INACTIVE` and `LOCKED` skips
    this step.
4.  **`KEYCLOAK_SESSIONS_ENDED`**, only when the new status is not `ACTIVE`: end all the user's
    Keycloak sessions.
5.  **`LOCAL_STATUS_SAVED`**: update `users.status` and write `USER_ACTIVATED`,
    `USER_DEACTIVATED` or `USER_LOCKED`. `user_roles` is never touched (B-34).

#### 5.4 Replace roles (`PUT /users/:id/roles`, `USER_ROLES_UPDATE`)

1.  Load the roles. Unknown ids give 400. Lock and load the user.
2.  Compute `added` and `removed` against the user's current `user_roles`. If both are empty, it
    is a no-op.
3.  For `added`, check `is_assignable` (409 `ROLE_NOT_ASSIGNABLE`). List the realm role names
    once, and check each added role against it (409 `ROLE_NOT_SYNCED`).
4.  If `SUPER_ADMIN` is in `removed` and the user is `ACTIVE`, lock the `SUPER_ADMIN` row. If no
    other `ACTIVE` user holds it, return 409 `LAST_SYSTEM_ADMIN`.
5.  **`KEYCLOAK_ROLES_ADDED`**, when `added` is not empty: add those mappings.
6.  **`KEYCLOAK_ROLES_REMOVED`**, when `removed` is not empty: remove those mappings. Skip any
    role whose realm role no longer exists (AD-10).
7.  **`LOCAL_ROLES_SAVED`**: replace the `user_roles` rows, set `users.updated_at`, and write
    `USER_ROLES_UPDATED` with the before and after role names and ids. Only changed realm roles
    are touched (B-35).

#### 5.5 Reset password (`POST /users/:id/reset-password`, `USER_PASSWORD_RESET`)

1.  Load the user.
2.  **`KEYCLOAK_ACTION_EMAIL_SENT`**: send the execute-actions email (`UPDATE_PASSWORD`). A
    failure gives 502 with `completedSteps: []` and no audit row.
3.  Write `USER_PASSWORD_RESET_REQUESTED`. No password passes through the service (B-36).

#### 5.6 Create role (`POST /roles`)

1.  Validate the body. The name must be unused in `roles`, in `api_permissions.permission_name`
    and as a Keycloak realm role (`findRealmRole`). Otherwise 409 `DUPLICATE_ROLE_NAME`. The
    Keycloak check stops a new role from taking over a realm role that users may already hold.
2.  In one transaction, insert the role (`is_system = false`, `is_assignable = true`,
    `sync_status = NEVER`) and its two implicit rows (AD-16), and write `ROLE_CREATED`.
3.  Run the sync procedure (5.10).
4.  Return 201. If the sync failed, add the warning `ROLE_SYNC_FAILED`. The role is saved with
    `syncStatus = FAILED`, and Re-sync (5.8) retries the sync.

#### 5.7 Replace permissions (`PUT /roles/:id/permissions`, `ROLE_PERMISSIONS_SAVE`)

1.  Load the role. A system role gives 409.
2.  Upper-case the methods. Drop the rows that equal an implicit row. Reject duplicates.
3.  Compare with the stored explicit rows on method, pattern, description and category. If they
    are unchanged, it is a no-op.
4.  **`PERMISSIONS_SAVED`**: in one transaction:
    *   delete the role's explicit rows, one bulk delete that leaves the implicit rows in place,
    *   insert the new explicit rows,
    *   insert either implicit row if it is missing,
    *   write `ROLE_PERMISSIONS_UPDATED`, with explicit and implicit rows listed separately.
5.  **`GATEWAY_SYNC`**: run the sync procedure (5.10).
6.  Return 200, or 502 with `completedSteps: ["PERMISSIONS_SAVED"]` and `auditLogId` pointing to
    the `ROLE_SYNC_REQUESTED` row (B-42).

#### 5.8 Manual sync (`POST /roles/:id/sync`, `ROLE_SYNC`)

Load the role, then run **`GATEWAY_SYNC`** (5.10). On failure the 502 has `completedSteps: []`,
and `auditLogId` points to the `ROLE_SYNC_REQUESTED` row (B-43).

#### 5.9 Delete role (`DELETE /roles/:id`)

1.  Load the role. A system role gives 409 `SYSTEM_ROLE_IMMUTABLE`.
2.  A role in `user_roles`, or whose realm role a Keycloak user holds, gives 409 `ROLE_IN_USE`.
    This check is in the service, and the foreign key is only a backstop (B-40).
3.  In one transaction, delete the role's `role_menus` and `api_permissions` rows and the role,
    and write `ROLE_DELETED`. Its before JSON holds the role, its menu ids and its permission
    rows.
4.  Return 204. No gateway sync runs (AD-12). The realm role stays in Keycloak until a later
    sync lets the gateway remove it (GW-4), and until then the role's name cannot be used again
    (5.6).

#### 5.10 Gateway sync procedure

Used by 5.6, 5.7 and 5.8.

1.  Get the service's access token from the Keycloak admin client's token manager (AD-13).
    Failing to get one counts as a failed sync.
2.  Send `POST {GATEWAY_BASE_URL}{GATEWAY_SYNC_PATH}` with the token and no body (GW-1). Any 2xx
    is success. Anything else, or a timeout, is failure. The error summary is the HTTP status
    and the first 500 characters of the body, or the exception class and message.
3.  In one new transaction, set `sync_status` (`SYNCED` or `FAILED`) and `last_sync_at = now()`
    on the role that asked, and only on it, without changing its `updated_at`. Write
    `ROLE_SYNC_REQUESTED` for that role, with outcome `SUCCESS` or `FAILED` and the after JSON
    `{ outcome, httpStatus, errorSummary }` (AD-06).
4.  Return the outcome and the audit row's id.

#### 5.11 Replace menus (`PUT /roles/:id/menus`)

1.  Load the role. A system role gives 409.
2.  Load the menus. Unknown ids give 400.
3.  Compute the `DETAIL_WITHOUT_PARENT` warnings.
4.  If the set is unchanged, it is a no-op, and the warnings are still returned.
5.  Otherwise, in one transaction, replace the `role_menus` rows and write `ROLE_MENUS_UPDATED`.

#### 5.12 Role display fields and the menu catalog

`PUT /roles/:id`, `POST /menus`, `PUT /menus/:id` and `DELETE /menus/:id` are single database
transactions that write `ROLE_UPDATED`, `MENU_CREATED`, `MENU_UPDATED` or `MENU_DELETED`. They
make no Keycloak or gateway call. `MENU_DELETED`'s before JSON lists the ids of the roles that
had the menu.

#### 5.13 Bootstrap (AD-15)

`BootstrapAdminRunner` runs once at each startup, after Flyway. It never throws: every failure
is logged at `ERROR`, and the service still starts.

1.  If any `ACTIVE` user holds `SUPER_ADMIN`, do nothing. If `BOOTSTRAP_ADMIN_USERNAME` is also
    set, log a warning that it is no longer needed and should be removed.
2.  Otherwise, if `BOOTSTRAP_ADMIN_USERNAME` is not set, log an error that there is no
    administrator, and stop.
3.  Look up that Keycloak user by exact username. Refuse, log an error and change nothing if the
    user does not exist, is disabled, or has an unverified email while the realm requires email
    verification.
4.  If the `SUPER_ADMIN` realm role does not exist in Keycloak, log an error and stop. Creating
    it is a bootstrap step (section 9.5), and the bootstrapper never runs a sync.
5.  If no local row has that `keycloak_id`, call `AccountLinker.link` (5.1 steps 5 and 6) with
    the Keycloak user's username, email and names and the role `SUPER_ADMIN`. If a local row
    exists, call `AccountLinker.reactivateAsAdmin`: it adds the `SUPER_ADMIN` realm role mapping
    when missing, sets the row `ACTIVE`, adds the `SUPER_ADMIN` `user_roles` row, and writes
    `USER_ROLES_UPDATED`. The actor is `system:bootstrap`.

### 6. Data dictionary and seed (B-01, B-02)

Database `admin_service`, schema `public` (AD-01).

*   **Ids.** The six owned tables have `uuid` primary keys with `DEFAULT gen_random_uuid()`, used
    by the seed. The service assigns ids itself in Java (`UUID.randomUUID()` in `@PrePersist`
    when the id is null), so 5.1 can know a user's id before the row exists.
*   **Timestamps.** `created_at` and `updated_at` are `timestamptz NOT NULL DEFAULT now()`. JPA
    sets both in `@PrePersist` and `updated_at` in `@PreUpdate`. `api_permissions` keeps the
    gateway's `TIMESTAMP` columns and its trigger.
*   **Keycloak ids.** The realm uses no user federation, so Keycloak user ids are UUIDs, and
    `keycloak_id` is typed `uuid`.
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
| `is_assignable` | boolean | NOT NULL, **no default** (AD-14), so every insert must set it |
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

Table CHECK `ck_menus_parent`: (`type = 'NAV'` and `parent_id` is null) or (`type = 'DETAIL'` and
`parent_id` is not null). That a `DETAIL`'s parent is itself a `NAV` is checked in the service.

**`role_menus`**: `id` uuid PK; `role_id` uuid NOT NULL FK `roles(id)` ON DELETE CASCADE;
`menu_id` uuid NOT NULL FK `menus(id)` ON DELETE CASCADE; `created_at`. Unique
`ux_role_menus_role_menu` (`role_id`, `menu_id`). Index `ix_role_menus_menu_id`. The cascades are
backstops: the service deletes these rows explicitly in 5.9 and on menu delete.

**`user_roles`**: `id` uuid PK; `user_id` uuid NOT NULL FK `users(id)` ON DELETE RESTRICT;
`role_id` uuid NOT NULL FK `roles(id)` ON DELETE RESTRICT; `created_at`. Unique
`ux_user_roles_user_role` (`user_id`, `role_id`). Index `ix_user_roles_role_id`. Join tables have
their own UUID primary key, as B-01 asks for every table except `api_permissions` (DEV-1).

**`api_permissions`**: the columns and the unique constraint are quoted from the
[gateway migration doc](../../context/gateway-service/DATABASE_PERMISSIONS_MIGRATION.md). That
doc names the two indexes and an `updated_at` trigger in prose only, so their DDL below is this
design's reconstruction (an assumption, GW-5).

*   **Columns (quoted):** `id BIGSERIAL PRIMARY KEY`, `permission_name VARCHAR(100) NOT NULL`,
    `http_method VARCHAR(10) NOT NULL`, `uri_pattern VARCHAR(255) NOT NULL`, `description TEXT`,
    `resource_category VARCHAR(50)`, `created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP`,
    `updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP`.
*   **Constraint (quoted):** `unique_permission_method_uri UNIQUE (permission_name, http_method, uri_pattern)`.
*   **Indexes (reconstructed):** `CREATE INDEX idx_permission_name ON api_permissions (permission_name)`
    and `CREATE INDEX idx_resource_category ON api_permissions (resource_category)`.
*   **Trigger (reconstructed):** function `update_api_permissions_updated_at()`, PL/pgSQL,
    `NEW.updated_at := CURRENT_TIMESTAMP; RETURN NEW;`, and trigger
    `trg_api_permissions_updated_at BEFORE UPDATE ON api_permissions FOR EACH ROW EXECUTE FUNCTION update_api_permissions_updated_at()`.

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
| `outcome` | varchar(10) | NOT NULL, CHECK in (`SUCCESS`, `FAILED`) |
| `before_state`, `after_state` | jsonb | NULL |
| `failure` | jsonb | NULL. On a failure row (section 8, rule 3), `{ operation, failedStep, completedSteps, keycloakUserId, error }`. |
| `ip_address` | varchar(45) | NULL |

Indexes: `ix_audit_log_entity` (`entity_type`, `entity_id`, `occurred_at`),
`ix_audit_log_occurred_at`, `ix_audit_log_actor_user_id` and `ix_audit_log_outcome`. The trigger
`trg_audit_log_append_only` raises an exception on `UPDATE` or `DELETE`.

**Migrations** in `src/main/resources/db/migration` (PRD §6). Flyway runs with
`baseline-on-migrate: true`, `baseline-version: 0` and `table: admin_service_flyway_history`.
The baseline lets Flyway migrate a `public` schema that already holds the gateway's table. The
table name keeps this service's history apart from any other Flyway history in the database.

*   **`V1__schema.sql`**: `pgcrypto`; the six owned tables with their constraints, indexes and
    the append-only trigger; and, inside a `DO` block that runs only when
    `to_regclass('public.api_permissions')` is null, `api_permissions` with its constraint, both
    indexes, the trigger function and the trigger. V1 never alters an existing `api_permissions`
    (AD-02).
*   **`V2__verify_api_permissions.sql`**: a `DO` block that reads `information_schema.columns`,
    `pg_constraint` and `pg_indexes`, and raises an exception, which stops the migration, unless
    all of these match the DDL above:
    *   each column's name, data type, length, nullability and default,
    *   a unique constraint on exactly (`permission_name`, `http_method`, `uri_pattern`),
        whatever its name,
    *   a non-unique index whose only column is `permission_name`, and one whose only column is
        `resource_category`.
*   **`V3__seed.sql`**: the rows below, after the shape is known to be right. `api_permissions`
    rows are inserted with `ON CONFLICT (permission_name, http_method, uri_pattern) DO NOTHING`,
    which works whatever the existing constraint is called.

**Seed menus** (B-02, AD-03). Every row is active. The ids are fixed: the menu in table row NN
(01 to 12, in the order below) has id `10000000-0000-4000-8000-0000000000NN`, so `dashboard` is
`10000000-0000-4000-8000-000000000001` and `patients.patient-detail` is
`10000000-0000-4000-8000-000000000012`.

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

**Seed roles.** Both start with `sync_status = NEVER` and are granted no menus. Their ids are
`20000000-0000-4000-8000-000000000001` (`SUPER_ADMIN`) and
`20000000-0000-4000-8000-000000000002` (`CCE_ADMIN_SERVICE`).

*   **`SUPER_ADMIN`**: display "Super administrator", description "Platform administrator with
    full access to the admin API." `is_system = true`, `is_assignable = true`.
*   **`CCE_ADMIN_SERVICE`**: display "Admin service account", description "Service account of
    cce-admin-service. Calls the gateway sync." `is_system = true`, `is_assignable = false`.

**Seed `api_permissions`.**

*   `SUPER_ADMIN`: five rows, `GET`, `POST`, `PUT`, `PATCH` and `DELETE` on `/admin/v1/**`.
    Category `Admin`, description "Administer the CCE admin module".
*   `CCE_ADMIN_SERVICE`: one row, `POST` on `/api/permissions/sync-to-keycloak`. Category
    `Gateway`, description "Trigger the gateway's realm role sync".

**Operator queries** (read-only):

*   **Unowned rows** (GW-5):
    `SELECT DISTINCT p.permission_name FROM api_permissions p LEFT JOIN roles r ON r.name = p.permission_name WHERE r.id IS NULL ORDER BY 1;`
*   **Failures to review** (section 8, rules 2 and 3):
    `SELECT occurred_at, action, entity_type, entity_id, failure, after_state FROM audit_log WHERE outcome = 'FAILED' ORDER BY occurred_at DESC;`

### 7. Clients

**`KeycloakGateway`** (`client/`). The service's only access to Keycloak. Its implementation uses
`keycloak-admin-client`, built with `KeycloakBuilder`. Its HTTP client is built the way the
library builds its own, plus timeouts:
`((ResteasyClientBuilder) ClientBuilder.newBuilder()).connectionPoolSize(10).connectTimeout(KEYCLOAK_CONNECT_TIMEOUT).readTimeout(KEYCLOAK_READ_TIMEOUT).register(new org.keycloak.admin.client.JacksonProvider(), 100).build()`,
passed to `KeycloakBuilder.resteasyClient(...)`. Registering the library's `JacksonProvider`
keeps its tolerance of unknown JSON fields, which matters because the server (26.4.7) is newer
than the client (26.0.12). `createUser` closes the `Response` of `users().create(...)` in a
`try` block and reads the id with `CreatedResponseUtil.getCreatedId`. It authenticates with the client-credentials grant of
`KEYCLOAK_ADMIN_CLIENT_ID` and `KEYCLOAK_ADMIN_CLIENT_SECRET` against `KEYCLOAK_SERVER_URL`,
realm `KEYCLOAK_REALM`. `KeycloakUserInfo` is
`{ id, username, email, firstName, lastName, enabled, emailVerified, realmRoles }`.

| Method | Purpose |
|---|---|
| `listRealmRoleNames()` | All realm role names, in one call. Used by 5.1 and 5.4. |
| `findRealmRole(name)` | The role representation, or empty when the realm role does not exist. |
| `hasRealmRoleMembers(name)` | Whether any Keycloak user holds the realm role. Used by 5.9. |
| `findUsersByUsernameOrEmail(username, email)` | Exact-match lookups by username and by email, merged, as `KeycloakUserInfo`. Used by 5.1. |
| `findUserByUsername(username)` | Exact lookup, as `KeycloakUserInfo`. Used by 5.1 after a 409, and by 5.13. |
| `getUser(keycloakUserId)` | `KeycloakUserInfo`, or empty. |
| `isEmailVerificationRequired()` | The realm's `verifyEmail` setting. |
| `createUser(username, email, firstName, lastName, requiredActions)` | Returns the new id, read from the `Location` header. |
| `addRealmRoles(keycloakUserId, roleNames)`, `removeRealmRoles(keycloakUserId, roleNames)` | Add or remove realm role mappings. |
| `updateProfile(keycloakUserId, email, firstName, lastName, emailVerified)` | |
| `setEnabled(keycloakUserId, enabled)`, `logoutAllSessions(keycloakUserId)` | |
| `sendExecuteActionsEmail(keycloakUserId, actions)` | |

A 404 on a lookup returns empty. Other failures raise `KeycloakConflictException` (409) or
`KeycloakCallFailedException` (anything else, with the HTTP status when there is one).

**Least privilege and blast radius.** The service account holds only the `realm-management`
client roles `manage-users`, `view-users`, `query-users` and `view-realm`, plus the realm role
`CCE_ADMIN_SERVICE`. It cannot create or delete realm roles (PRD §4, §9). It can, however, change
any user in the realm, including that user's realm role mappings for any realm role. A leaked
client secret therefore lets an attacker grant any realm role to any account. The secret is a
production credential and is stored and rotated as one (Risks & Mitigations).

**`GatewaySyncClient`** (`client/`). One method, `SyncResult sync()`, where `SyncResult` is
`{ boolean success, Integer httpStatus, String errorSummary }`. Its implementation uses Spring's
`RestClient` with the configured timeouts and no request body. Its bearer token is the Keycloak
admin client's own token, from `Keycloak.tokenManager().getAccessTokenString()`, so there is one
token cache and one authentication mode (AD-13).

### 8. Audit (B-50)

**The single audit rule.** Every flow follows these five rules.

1.  A successful state change writes one row, with outcome `SUCCESS`, in the same transaction as
    the change.
2.  A gateway sync attempt is a state change of its own. It writes one `ROLE_SYNC_REQUESTED`
    row, in its own transaction, with outcome `SUCCESS` or `FAILED` (DEV-7).
3.  A Keycloak step that fails after an earlier step of the same call has changed something
    writes one row, in its own transaction: the flow's own action, outcome `FAILED`, and the
    `failure` JSON.
4.  A failure before anything changed and before any sync attempt writes nothing.
5.  A 502's `auditLogId` is the row written under rule 2 or rule 3. It is `null` under rule 4,
    or when that row itself could not be written. In that case the structured `ERROR` log event
    (section 5) is the only record.

So `POST /roles` and a changed `PUT /roles/:id/permissions` write two rows each, `POST
/roles/:id/sync` writes one, every other successful mutation writes one, and a no-op writes none.

**Writer.** `AuditWriter` (`audit/`) has two methods. `record(entry)` joins the current
transaction and fails if there is none. `recordInNewTransaction(entry)` runs in its own
transaction, for rules 2 and 3. The actor comes from `ActorProvider`: the request's
`CurrentUser`, or the `system:bootstrap` actor when there is no request. The IP address is the
request's remote address after the forwarded-header handling of section 3. The before and after
JSON are the resource in its API shape (section 4.4).

**Personal data.** Rows hold usernames, emails and IP addresses, and never tokens, secrets or
passwords. Their retention follows the platform's audit policy, which is outside this design.

| Action | Entity | Written by | Before / after |
|---|---|---|---|
| `USER_CREATED` | USER | 5.1, 5.13 | null / User, plus `linked` |
| `USER_UPDATED` | USER | 5.2 | User / User |
| `USER_ACTIVATED`, `USER_DEACTIVATED`, `USER_LOCKED` | USER | 5.3 | User / User |
| `USER_ROLES_UPDATED` | USER | 5.4, 5.13 | `{ roles }` / `{ roles }` |
| `USER_PASSWORD_RESET_REQUESTED` | USER | 5.5 | null / `{ email, actions }` |
| `ROLE_CREATED` | ROLE | 5.6 | null / Role and its implicit rows |
| `ROLE_UPDATED` | ROLE | 5.12 | Role / Role |
| `ROLE_DELETED` | ROLE | 5.9 | Role, `menuIds`, permission rows / null |
| `ROLE_MENUS_UPDATED` | ROLE | 5.11 | `{ menuIds }` / `{ menuIds }` |
| `ROLE_PERMISSIONS_UPDATED` | ROLE | 5.7 | `{ explicit, implicit }` / `{ explicit, implicit }` |
| `ROLE_SYNC_REQUESTED` | ROLE | 5.10 | null / `{ outcome, httpStatus, errorSummary }` |
| `MENU_CREATED`, `MENU_UPDATED`, `MENU_DELETED` | MENU | 5.12 | Menu (and `roleIds` on delete) / Menu or null |

### 9. Local environment (M0) and bootstrap

#### 9.1 Containers

`cce-admin-service/docker-compose.yml`. Every published port is bound to `127.0.0.1`. Every
image tag and dependency version comes from D-05 in
[author-decisions.md](../../docs/designs/author-decisions.md), and no tag floats. Every service
reads `env_file: .env`.

| Service | Image or build | Host port | Notes |
|---|---|---|---|
| `postgres` | `postgres:16.15-alpine` | 5433 → 5432 | Database `admin_service`, user `admin_service`, password `${POSTGRES_PASSWORD}`. An init script creates a second database, `keycloak`. Volume `pgdata`. Health check `["CMD", "pg_isready", "-U", "admin_service", "-d", "admin_service"]`. Port 5433 avoids a Postgres already running on 5432. |
| `keycloak` | `quay.io/keycloak/keycloak:26.4.7` | 8180 → 8080 | Runs `start-dev --import-realm`. `KC_HTTP_RELATIVE_PATH=/auth`. `KC_HTTP_MANAGEMENT_RELATIVE_PATH=/`. `KC_HOSTNAME=http://localhost:8180/auth`, so the issuer is the same from the browser, the host and other containers. `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`, so containers can reach it at `http://keycloak:8080/auth`. Database `keycloak` on `postgres`. `KC_BOOTSTRAP_ADMIN_USERNAME=admin`, `KC_BOOTSTRAP_ADMIN_PASSWORD=${KEYCLOAK_ADMIN_PASSWORD}`. `KC_HEALTH_ENABLED=true`. Imports `keycloak/realm-cce.json`. The image has no curl or wget, so the health check uses bash: `["CMD", "bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/9000 && printf 'GET /health/ready HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q UP <&3"]`. `depends_on` `postgres` healthy. |
| `mailpit` | `axllent/mailpit:v1.33.1` | 8025 (web UI and API) | SMTP on `mailpit:1025` inside the network. Captures mail, delivers none (AD-08). Relies on the health check built into its image. |
| `gateway-stub` | build context `.`, `dockerfile: gateway-stub/Dockerfile` | 8090 | The stand-in (AD-11). Variables in the table below. Health check `GET /_stub/mode`. `depends_on` `postgres` and `keycloak` healthy. |
| `admin-service` | build context `.` | 8085 | Only with `--profile app`. The block is written out below. |

**The `admin-service` block:**

```yaml
admin-service:
  profiles: [app]
  build: .
  ports: ["127.0.0.1:8085:8085"]
  env_file: .env
  environment:
    DATABASE_URL: jdbc:postgresql://postgres:5432/admin_service
    KEYCLOAK_SERVER_URL: http://keycloak:8080/auth
    JWT_JWK_SET_URI: http://keycloak:8080/auth/realms/cce/protocol/openid-connect/certs
    GATEWAY_BASE_URL: http://gateway-stub:8090
  depends_on:
    postgres: { condition: service_healthy }
    keycloak: { condition: service_healthy }
    gateway-stub: { condition: service_healthy }
```

`JWT_ISSUER` is not overridden. It stays `http://localhost:8180/auth/realms/cce`, the issuer
Keycloak writes into every token because of `KC_HOSTNAME`.

**Stand-in variables** (set in the compose file):

| Variable | Value |
|---|---|
| `STUB_PORT` | `8090` |
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | `jdbc:postgresql://postgres:5432/admin_service`, `admin_service`, `${POSTGRES_PASSWORD}` |
| `KEYCLOAK_SERVER_URL`, `KEYCLOAK_REALM` | `http://keycloak:8080/auth`, `cce` |
| `STUB_CLIENT_ID`, `STUB_CLIENT_SECRET` | `cce-gateway-stub`, `${GATEWAY_STUB_CLIENT_SECRET}` |

**What the stand-in does.** `POST /api/permissions/sync-to-keycloak` checks, in this order: in
`fail` mode it returns 503 and changes nothing; without an `Authorization: Bearer` header it
returns 401, and it checks nothing else about the token. Otherwise it reads the distinct `permission_name` values,
lists the realm roles with their attributes (`?briefRepresentation=false`), creates a realm role
with the attribute `cce-managed=true` for every name that has none, deletes realm roles that
carry `cce-managed=true` and no longer have rows, and returns 200. It never creates, changes or
deletes Keycloak's built-in roles (`default-roles-<realm>`, `offline_access`,
`uma_authorization`) and never deletes `SUPER_ADMIN`. `PUT /_stub/mode` with body `ok` or `fail`
switches it at runtime, with no token. The mode lives in memory and starts as `ok`. Its README
says that it is a stand-in, not the gateway.

#### 9.2 Realm `cce` (`keycloak/realm-cce.json`)

The file holds no credentials. Secrets and the test administrator's details are `${…}`
placeholders, which Keycloak fills from the container's environment when it imports the realm
(G1).

*   **Realm settings:**
    *   access token lifespan 300 seconds,
    *   `verifyEmail` false,
    *   `registrationAllowed` false,
    *   no user federation,
    *   SMTP host `mailpit`, port 1025, from `noreply@cce.local`.

    The file defines no `clientScopes` list, so Keycloak creates its default scopes, including
    `basic` (for `auth_time`, AD-05) and `roles`.
*   **Realm roles:** `SUPER_ADMIN` and `CCE_ADMIN_SERVICE`, without the `cce-managed` attribute,
    so the stand-in never deletes them.
*   **Clients:** each client marked "audience mapper" gets an `oidc-audience-mapper` with
    `included.custom.audience = gateway-service`, added to access tokens. Every client's default
    client scopes are `basic`, `profile`, `email`, `roles`, `web-origins` and `acr`.

    | Client | Type | Settings |
    |---|---|---|
    | `cce-admin-ui` | Public | Standard flow, PKCE S256, redirect `http://localhost:3002/*`, web origin `http://localhost:3002`, post-logout redirect `http://localhost:3002/*`, audience mapper (D-03). |
    | `cce-insights-ui` | Public | As above, on port 3001. Lets a locally run cce-insights-ui sign in to this realm. The E2E checks use `cce-e2e`, not this client. |
    | `cce-admin-service` | Confidential | Service account only, secret `${KEYCLOAK_ADMIN_CLIENT_SECRET}`, audience mapper. Service account roles as in section 7. |
    | `cce-gateway-stub` | Confidential | Service account only, secret `${GATEWAY_STUB_CLIENT_SECRET}`. `realm-management` roles `manage-realm` and `view-realm`, so it can create realm roles. Local realm only. |
    | `cce-e2e` | Public | Direct access grants only, audience mapper. Local realm only. Used by the API-level E2E checks to get tokens. |

*   **Users:**
    *   The test administrator: fixed id `00000000-0000-4000-8000-000000000001`, username
        `${CCE_TEST_ADMIN_USERNAME}`, email `test-admin@cce.local`, first name "Test", last name
        "Admin", enabled, email verified, password `${CCE_TEST_ADMIN_PASSWORD}` (not temporary),
        realm role `SUPER_ADMIN`.
    *   The service-account users of `cce-admin-service` (realm role `CCE_ADMIN_SERVICE`, AD-13)
        and `cce-gateway-stub`.

#### 9.3 Values, commands and reset

*   **`.env.example`** (committed, placeholders only). Copy it to `.env` (git-ignored) and fill it in.
    *   **Secrets and the test administrator:** `POSTGRES_PASSWORD`, `KEYCLOAK_ADMIN_PASSWORD`, `KEYCLOAK_ADMIN_CLIENT_SECRET`, `GATEWAY_STUB_CLIENT_SECRET`, `CCE_TEST_ADMIN_USERNAME`, `CCE_TEST_ADMIN_PASSWORD`, and `BOOTSTRAP_ADMIN_USERNAME=${CCE_TEST_ADMIN_USERNAME}`.
    *   **Host-run values for the service:** `DATABASE_URL=jdbc:postgresql://localhost:5433/admin_service`, `DATABASE_USERNAME=admin_service`, `DATABASE_PASSWORD=${POSTGRES_PASSWORD}`, `JWT_ISSUER=http://localhost:8180/auth/realms/cce`, `JWT_AUDIENCE=gateway-service`, `KEYCLOAK_SERVER_URL=http://localhost:8180/auth`, `KEYCLOAK_REALM=cce`, `GATEWAY_BASE_URL=http://localhost:8090`.

    The service imports `.env` with `spring.config.import=optional:file:.env[.properties]`, and compose reads it as its env file.
*   **Start:**
    *   `docker compose up -d --wait` starts the infrastructure.
    *   Then either `./mvnw spring-boot:run` (service on the host) or `docker compose --profile app up -d --build --wait` (service in a container).
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
| `PERMISSION_CHECK_ENABLED` | `true` | AD-17. Must be `true` in shared environments unless GW-3 shows the gateway matches differently. |
| `TRUSTED_PROXIES` | a regular expression that matches no address | AD-19. In shared environments, the gateway's addresses (GW-7). |
| `BOOTSTRAP_ADMIN_USERNAME` | empty | AD-15. Unset it after the first start. |
| `SPRINGDOC_ENABLED` | `true` | Serves the OpenAPI document and Swagger UI under `/admin/v1`. Set by each environment's owner; the service is not internet-facing (PRD §9). |

The database pool waits at most 2 seconds for a connection
(`spring.datasource.hikari.connection-timeout: 2000`).

#### 9.5 Bootstrap steps for a new shared environment

Do these in order.

1.  Provide the database `admin_service`, and a database user that may create tables in
    `public`. Enable `pgcrypto`, or let that user run `CREATE EXTENSION pgcrypto`.
2.  If `api_permissions` already exists (the gateway's own script ran) and another user owns it,
    grant the service's user `SELECT, INSERT, UPDATE, DELETE` on it and `USAGE, SELECT` on its id
    sequence. This service's Flyway history is in its own table,
    `admin_service_flyway_history`.
3.  In the realm, create the confidential client `cce-admin-service`: service account on,
    `realm-management` roles `manage-users`, `view-users`, `query-users` and `view-realm`, and an
    audience mapper for `gateway-service`. Store its secret as `KEYCLOAK_ADMIN_CLIENT_SECRET`.
4.  Create the realm roles `CCE_ADMIN_SERVICE` and `SUPER_ADMIN` by hand. Map
    `CCE_ADMIN_SERVICE` to the user `service-account-cce-admin-service`. This step and the sync
    authentication it serves are provisional until GW-6 is answered (AD-13).
5.  Create the public client `cce-admin-ui`: PKCE S256, the environment's redirect URI, audience
    mapper, default scope `basic`.
6.  Create the first administrator's Keycloak account: enabled, and with a verified email if the
    realm requires verification.
7.  Deploy with every required variable, `TRUSTED_PROXIES` (GW-7) and
    `BOOTSTRAP_ADMIN_USERNAME`. Flyway migrates and seeds. Check the log for the bootstrap result
    (5.13), then unset the variable and redeploy.
8.  Ask the gateway owners to route `/admin/**` to the service and, now that `api_permissions`
    holds the seed rows, to restart or reload the gateway (GW-2, go/no-go).
9.  Run the unowned-rows query (section 6) and send the result to the gateway owners (GW-5).
10. If the environment separates a migration user from a runtime user, grant the runtime user
    only `SELECT, INSERT` on `audit_log`. The append-only trigger applies either way.

### 10. Testing and end-to-end checks

| Layer | Tool | What it covers |
|---|---|---|
| Unit | JUnit 5, AssertJ | The roles-claim reader, the `/me/menus` tree builder, the role diff in 5.4, the `DETAIL_WITHOUT_PARENT` rule, the `uriPattern` rules, and the field validators. |
| Migration | Testcontainers Postgres 16, Flyway run directly | Each test creates its own database. **Empty database:** V1 to V3 apply, and every table, index, constraint and seed row in section 6 exists. **Gateway table already there:** the fixture creates the gateway's table, indexes, trigger and sample rows, then Flyway runs. It succeeds, the table is not altered, and the samples stay. **Wrong shape:** a different column type fails at V2, before any seed row is written. **`is_assignable`:** the column has no default. **Append-only:** `UPDATE` and `DELETE` on `audit_log` fail. |
| Service | Spring Boot test, Testcontainers Postgres, `KeycloakGateway` and `GatewaySyncClient` replaced by Mockito mocks (PRD §9) | Every flow in section 5, on the happy path and on each error code it can produce. Every named partial-failure step, including the PRD's case of a database insert failing after Keycloak succeeded (B3 notes), and that resubmitting completes the flow. Linking an existing account. The exact `audit_log` rows for every mutating endpoint under the single audit rule. No-ops write nothing. `LAST_SYSTEM_ADMIN` under two concurrent requests. The bootstrapper's cases. |
| Web | MockMvc | 401 for each bad-token case, through a test decoder with the real validators (B-10). 403 for a missing local user, for a user who is not `ACTIVE` (B-12), and for no matching permission row. Step 5 turned off by its switch. The envelope, error and pagination shapes. 400 on unknown properties and on an unsupported content type. `/health` without a token. The OpenAPI document lists every endpoint (B-06). |
| Stand-in | JUnit, in `gateway-stub` | The bearer-header check, the mode switch, and which roles are created or deleted, including that built-in roles, untagged roles and `SUPER_ADMIN` are never deleted. |
| E2E, API level | JUnit, `*E2E.java`, run by `./mvnw -Pe2e verify` against the running stack (`--profile app`) | The scenarios below. |

**E2E scenarios.** Names get a per-run suffix of 8 digits, so runs do not collide. The tests get
tokens from `cce-e2e` with the password grant. Before signing in as a user that a scenario
created, the test sets that user's password and clears its required actions through the
Keycloak Admin API, using the local master admin credentials from `.env`. Without that, Keycloak
refuses the password grant for an account that still has `UPDATE_PASSWORD` pending. This is
test-only setup. The service itself never handles a password.

*   **S1 and B5:** create two console roles with overlapping menus, and a user holding both. Sign
    in as that user, and check that `GET /me/menus` returns the merged tree.
*   **S2:** create a user with two roles. Check that the Keycloak account and its realm roles
    exist, the local row exists, `USER_CREATED` is written, and Mailpit holds the set-password
    email (`http://localhost:8025/api/v1/messages`). Then create a Keycloak-only account through
    the Admin API, call `POST /users` with its username and email, and check that it is linked
    (`after.linked = true`).
*   **S3:** create a role. Its realm role now exists through the stand-in. Grant menus including a
    `DETAIL` without its parent, and check for the warning. Save permissions, and check for 200,
    `syncStatus = SYNCED` and the two audit rows.
*   **S4:** replace a user's roles. Only the changed Keycloak mappings move.
*   **S5:** set a user `INACTIVE`. Keycloak shows the account disabled, `user_roles` is
    unchanged, and the user's token gets 403. Set `ACTIVE`, and access returns.
*   **S6:** set the stand-in to `fail`. Then:
    *   create a role and check for 201 with the warning `ROLE_SYNC_FAILED`,
    *   save its permissions and check for 502 `ROLE_PERMISSIONS_SAVE`, with the rows saved and a
        `FAILED` `ROLE_SYNC_REQUESTED` row,
    *   assign the role and check for 409 `ROLE_NOT_SYNCED`.

    Set `ok`, re-sync and check for 200, then assign successfully.
*   **Live hard control:** the S1 console user calling `GET /users` gets 403 `FORBIDDEN`, and
    calling `GET /me/menus` gets 200. This is the only local evidence that the PRD's hard control
    works, and it checks the service's own check, not the gateway's.

--------------------------------------------------------------------------------

## Alternatives Considered

These are the options weighed for each decision in the Author Decisions table. Each entry links
the decision it lost to and gives its trade-off in one line.

### Where the tables live ([AD-01](#author-decisions-in-this-design))

*   **All seven tables in a schema `admin_service`.** Pros: follows PRD §6 to the letter. Cons:
    the gateway reads `public` and would need a configuration change in a deployment this team
    does not own. Rejected by author: if that change were missing, the gateway would load no
    permissions and block every call.
*   **Six tables in `admin_service`, `api_permissions` in `public`.** Pros: follows PRD §6 for the
    service's own tables. Cons: one module spread over two schemas for migrations, grants and
    backups. Rejected by author: it serves only PRD §6's wording, which §5 contradicts.

### Who creates `api_permissions` ([AD-02](#author-decisions-in-this-design))

*   **The gateway's script is the only creator.** Pros: the gateway team keeps sole ownership.
    Cons: every new environment needs a manual psql step, and B-01 no longer holds. Rejected by
    author: a manual step in every environment.
*   **Flyway owns the table outright.** Pros: one owner and one history. Cons: existing
    environments must export, drop and re-enter their rows. Rejected by author: between the drop
    and the re-entry, the gateway loads no permissions.
*   **V1 creates missing indexes and the trigger on an existing table.** Pros: every environment
    ends with the same table. Cons: fails on a table owned by another database user, and changes
    a table another team owns. Rejected by author: V1 now leaves an existing table alone.

### The three routes outside today's sidebar ([AD-03](#author-decisions-in-this-design))

*   **Inactive `NAV` menus.** Pros: today's sidebar is unchanged. Cons: `is_active` gains a second
    meaning, "routed but hidden". Rejected by author.
*   **A third menu type, `ROUTE`.** Pros: matches today's behaviour exactly. Cons: PRD §4 defines
    no such type, and every console must filter on it. Rejected by author.

### User statuses ([AD-04](#author-decisions-in-this-design))

*   **Two statuses, `ACTIVE` and `INACTIVE`.** Pros: the smallest model. Cons: offboarding and a
    temporary block look the same, and Appendix A's `LOCKED` is dropped. Rejected by author.
*   **`INACTIVE` terminal, `LOCKED` reversible.** Pros: keeps PRD §2's wording. Cons: contradicts
    B-34, S5 and F-10, and a mistaken offboarding cannot be undone. Rejected by author.

### Recording the gateway sync result ([AD-06](#author-decisions-in-this-design))

*   **The outcome only on the role, with one audit row per permission save.** Pros: one audit row
    per call. Cons: `audit_log` cannot show whether a past change reached the gateway. Rejected
    by author: that is what an auditor asks (PRD §9).
*   **Marking every role with rows `SYNCED` after a successful sync.** Pros: fewer stale `FAILED`
    statuses if the sync covers every role. Cons: assumes GW-1's answer, and races with
    concurrent saves. Rejected by author.

### When the set-password email fails ([AD-08](#author-decisions-in-this-design))

*   **502 whenever the email fails.** Pros: one uniform rule. Cons: reports a successful creation
    as a failure, for a step that changes no access and can be repeated. Rejected by author.

### Undoing Keycloak steps after a later failure

*   **Compensation**, for example deleting the new Keycloak user when the database insert fails.
    Pros: fewer partial states. Cons: the compensation can fail too, leaving a state that is
    harder to explain. Rejected by the PRD: partial failures are surfaced and recorded, not
    undone (B-32, PRD §9).

### Local stand-in for the gateway sync ([AD-11](#author-decisions-in-this-design))

*   **A profile-switched implementation inside the service.** Pros: no extra container. Cons: the
    real HTTP client never runs locally, and the production artifact carries a failure switch.
    Rejected by author.
*   **WireMock, with realm roles created by test setup.** Pros: no custom server code. Cons: a role
    created in the console cannot be assigned without manual Keycloak steps. Rejected by author.

### Role delete and the gateway sync ([AD-12](#author-decisions-in-this-design))

*   **Sync after delete.** Pros: the gateway can remove the realm role at once. Cons: a delete
    gains a second outcome (deleted but not synced) and a 502 for a role that no longer exists.
    Rejected by author: the leftover realm role grants nothing, and the next sync clears it.

### Authenticating to the sync endpoint ([AD-13](#author-decisions-in-this-design))

*   **Forward the administrator's token.** Pros: no extra role, and the gateway sees the person.
    Cons: "allowed to save" can drift from "allowed to sync". Rejected by author; kept as the
    code-change fallback (GW-6).
*   **A shared secret in a header.** Pros: the simplest client. Cons: needs a gateway check with
    no evidence that it exists, and a long-lived secret. Rejected by author.

### Keeping the service-account role from people ([AD-14](#author-decisions-in-this-design))

*   **The role name in configuration.** Pros: no schema change. Cons: a setting must match a row
    in every environment, and the console cannot read it. Rejected by author.
*   **Hide the role from the API.** Pros: the console never sees it. Cons: the service account's
    rights become visible only through SQL. Rejected by author.

### The first administrator ([AD-15](#author-decisions-in-this-design))

*   **A Flyway migration with placeholders.** Pros: runs once, with no startup code. Cons: a wrong
    copied id cannot be fixed by running it again. Rejected by author.
*   **An operator runbook with SQL.** Pros: no service code. Cons: hand-edited SQL, easily run
    without the audit row. Rejected by author.
*   **A bootstrapper that also runs a gateway sync.** Pros: no hand-made `SUPER_ADMIN` realm role.
    Cons: the sync fails until the gateway has loaded the seed rows, and "the role that asked"
    is undefined. Rejected by author.

### The `/me/menus` permission for console roles ([AD-16](#author-decisions-in-this-design))

*   **Explicit rows only, with a warning.** Pros: exact replace semantics. Cons: the warning can
    be ignored, and a role with no rows never gets one. Rejected by author.
*   **A shared seeded `CONSOLE_USER` role.** Pros: one row in one place. Cons: every user needs one
    more role, and nothing enforces it. Rejected by author.

### The service's own permission check ([AD-17](#author-decisions-in-this-design))

*   **No permission check in the service.** Pros: matches B-10 to B-12 literally. Cons: any
    active user can administer when the gateway is bypassed, including every local run. Rejected
    by author: defence in depth on the API that grants access.
*   **A hand-written matcher with a cached snapshot and an offline row check.** Pros: one matcher
    shared with the stand-in. Cons: differs from `AntPathMatcher`, a cache that can lag and
    cause intermittent 403s, and much code. Rejected by author.
*   **A coarse `ADMIN_ROLES` list.** Pros: simple. Cons: drifts from the data when an
    administrator creates a narrower admin role. Rejected by author.

### Existing Keycloak accounts ([AD-18](#author-decisions-in-this-design))

*   **Separate link and adopt endpoints, with account tagging.** Pros: explicit operator actions.
    Cons: two more endpoints in the contract and a tagging scheme in Keycloak. Rejected by
    author: linking inside `POST /users` covers both cases with no new contract surface.

### Locking ([section 5](#5-flows-and-order-of-operations))

*   **One global lock on the `SUPER_ADMIN` row for every status and role change.** Pros: one
    simple rule. Cons: every user change waits behind any other across Keycloak calls, and the
    role diff was computed before the lock. Rejected by author: a per-user lock, with the
    `SUPER_ADMIN` lock only when an administrator could be lost.

### Audited client IP ([AD-19](#author-decisions-in-this-design))

*   **Trust the first `X-Forwarded-For` value.** Pros: no configuration. Cons: any caller can
    set the audited address. Rejected by author.

--------------------------------------------------------------------------------

## Non-Functional Requirements

*   **Security.**
    *   The service checks the JWT, the local user's standing and the caller's permission rows
        itself (section 3).
    *   The Keycloak service account has the least privilege the flows need, and cannot create
        realm roles. Its blast radius is in section 7.
    *   No secret is in the repository: the realm import uses placeholders, and `.env` is
        git-ignored.
    *   Audit rows hold no tokens or passwords.
    *   Every local port is bound to `127.0.0.1`. The stand-in never runs outside the local
        environment.
*   **Performance.** The service is low-throughput admin CRUD (PRD §6), except `/me/menus`, which
    cce-insights-ui calls when it loads. Estimate: a few thousand console users loading the
    console a few times a day gives well under 10 requests per second at peak. Each
    authenticated request costs one `SELECT` for the local user, one indexed `SELECT` for the
    permission rows, and one conditional `UPDATE` that changes a row once per sign-in.
    `/me/menus` adds one query for the menus. If the service is down, cce-insights-ui cannot
    build its sidebar. What it shows then is decided in that repository (PRD §12).
*   **Rollout and rollback.**
    *   The migrations add tables and rows. They create `api_permissions` only when it is
        absent, and add seed rows to an existing one with `ON CONFLICT DO NOTHING`. They never
        alter an existing `api_permissions`.
    *   Rolling the service back leaves the tables and seed rows in place, and the gateway keeps
        reading `api_permissions` unchanged.
    *   `PERMISSION_CHECK_ENABLED=false` turns off the service's own permission check without a
        release.
    *   **Before cce-insights-ui switches its sidebar to `/me/menus`:** create the console roles
        with their menus and permissions, and onboard every existing console user through
        `POST /users`, which links their existing Keycloak accounts (AD-18). A user with no local
        row gets 403 from `/me/menus`. `INSIGHTS_READ` and other names no role here owns stay
        managed by the gateway owners.

## Risks & Mitigations

| Risk | Effect | Mitigation | Owner |
|---|---|---|---|
| **GW-1:** the sync contract differs from the assumption. | Every sync fails, and permission saves return 502. | URL, path and timeouts are configuration. A body or per-role parameter is a code change. Answer GW-1 before the first shared environment. | Gateway owners |
| **GW-2:** the gateway loads permissions only on restart. | A saved permission has no effect at the gateway, while `syncStatus` says `SYNCED`. | `SYNCED` is defined as "the sync call got a 2xx". Go/no-go before the first shared environment. Until answered yes, the gateway owners restart the gateway after permission changes. | Gateway owners |
| **GW-3:** the gateway matches patterns differently. | The service refuses calls the gateway allows, or allows calls the gateway refuses. | `PERMISSION_CHECK_ENABLED=false` without a release. A different matcher is a code change. | Gateway owners, then this team |
| **GW-4 and AD-12:** a sync does not delete realm roles. | A deleted role's realm role stays, and its name cannot be used again (5.6 refuses it). | The refusal is visible. An operator can delete the leftover realm role in Keycloak by hand. | Operators |
| **GW-5:** unowned rows, and the reconstructed index and trigger DDL. | Rights no admin screen shows. A new environment's indexes or trigger differ from the gateway's script. | The unowned-rows query. V1 never touches an existing table. The gateway owners confirm the DDL. | Gateway owners |
| **GW-6:** the sync endpoint does not accept a service token. | Every sync fails. | The `CCE_ADMIN_SERVICE` chain is marked provisional. The fallback (forward the caller's token) is a code change. | Gateway owners, then this team |
| **GW-7 and AD-19:** `TRUSTED_PROXIES` is wrong. | Too narrow: the audit records the gateway's address. Too wide: a caller can forge it. | Default trusts no proxy. Set it from GW-7's answer. | Environment owner |
| **GW-8:** the gateway's error bodies differ. | A console misreads a gateway 401 or 403. | Clients branch on the status when `error.code` is absent. | Console owners |
| **Security switches in shared environments.** | A wrong value weakens a control. | `PERMISSION_CHECK_ENABLED=true`. `TRUSTED_PROXIES` set from GW-7. The stand-in and its control endpoint are never deployed. `SPRINGDOC_ENABLED` is the environment owner's choice; the service is not internet-facing. | Environment owner |
| **Service account secret leaks.** | An attacker can grant any realm role to any account. | Secret store and rotation. Least-privilege roles. | Environment owner |
| **Locks held across Keycloak calls.** | Changes to the same user, and removals of `SUPER_ADMIN` holders, wait for each other, up to 10 s per Keycloak call, and hold a pool connection meanwhile. | The lock is per user, and the `SUPER_ADMIN` lock is taken only when an administrator could be lost. Admin traffic is low. | This team |
| **Crash window.** The process stops between a Keycloak step and its failure row. | No audit row records the partial state. | Accepted. Submitting the same request again completes the flow. | Operators |
| **Keycloak drift.** A change made directly in Keycloak, such as `SUPER_ADMIN` mapped by hand. | Token roles and `user_roles` disagree. The token decides at the gateway and in step 5. | Accepted. No drift detector. Changes go through the API. | Operators |
| **Token revocation window.** | A deactivated user's access token keeps working at the gateway until it expires (300 s locally, unknown elsewhere). The service refuses it at once (B-12). | Sessions are ended, so no new token is issued. | Accepted |
| **Three more sidebar links (AD-03).** | A role granted Practitioners, Intelligence or Exports shows up to three more links than today's sidebar. | Grant those menus only to roles that should see them. The seed keeps today's seven links first. | Administrators |
| **E2E uses the Keycloak master admin to set passwords.** | The E2E checks do not follow the set-password email link. | Test-only. The service never handles passwords (B-36). | This team |
| **Local checks can pass against the stand-in while a shared environment differs.** | Surprises in the first shared environment. | GW-1 to GW-8 are sent to the gateway owners, and the stand-in's README states its assumptions. | This team |

## Future Improvements

*   **Optimistic concurrency** on `PUT /roles/:id/menus`, `PUT /roles/:id/permissions` and
    `PUT /users/:id/roles`, with `If-Match` and 412, would stop one administrator's save from
    silently replacing another's.

--------------------------------------------------------------------------------

## Detailed Implementation Plan

### How to use this plan

*   **One phase per run.** A coding agent builds one phase at a time. Each phase starts from the
    previous phase's committed result and ends when its exit check passes. `./mvnw verify`
    passes at the end of every phase. It never runs the E2E tests, which Phase 8 adds under
    `-Pe2e`.
*   **Do not make design decisions.** If a step needs one, or the design looks wrong, stop and
    raise it as a change to this document.
*   **Paths.** All paths are relative to `cce-admin-service/`, which sits at the repository root.
    `{main}` is `src/main/java/org/cce/admin` and `{test}` is `src/test/java/org/cce/admin`. Every
    Java file is in the package its folder names.
*   **Versions.** Every image tag and Maven dependency version comes from D-05
    ([author-decisions.md](../../docs/designs/author-decisions.md)). The service's dependencies
    take their versions from `spring-boot-starter-parent` 3.5.16, except springdoc 2.10.0 and
    `keycloak-admin-client` 26.0.12, which are written explicitly. Two host tools are
    prerequisites outside D-05, named in the README: a JDK 21 for host builds, and Node 22 for
    `scripts/token-claims.mjs`.
*   **Coordinates.** groupId `org.cce`, artifactId `cce-admin-service`, version `0.1.0-SNAPSHOT`.
    The stand-in is `org.cce:cce-gateway-stub:0.1.0-SNAPSHOT`, package `org.cce.gatewaystub`.
*   **Spring tests never read `.env`.** Every Spring test runs with `@ActiveProfiles("test")`, and
    the `.env` import is active only outside that profile. Only the Phase 8 E2E tests read
    `.env`, through `E2eEnvironment`.
*   **Transactions.** The flow services (`UserService`, `RoleService`, `RolePermissionService`,
    `RoleMenuService`, `MenuService`, `GatewaySyncService`, `AccountLinker`) carry no class- or
    method-level `@Transactional`. Each database step of section 5 runs in its own
    `TransactionTemplate` and commits before the next Keycloak or gateway call. Services always
    continue with the entity instance that `save` returns.
*   **Never commit** `.env`, real secrets, or Keycloak exports (PRD §11). The repository's root
    `.gitignore` already ignores `.env` and `target/`.

### Phase 1: Local environment (M0) and project skeleton

**Closes:** M0, and B-13 for the local environment.

| File | Action | Contents |
|---|---|---|
| `.mvn/wrapper/maven-wrapper.properties`, `mvnw`, `mvnw.cmd` | Create | Generated with `mvn -N org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper -Dmaven=3.9.16 -Dtype=only-script`. Run this once with any installed Maven 3.x. Every later command uses `./mvnw`, which pins Maven 3.9.16 (D-05). |
| `pom.xml` | Create | Parent `spring-boot-starter-parent` 3.5.16, `java.version` 21. **Dependencies:** `spring-boot-starter-web`, `-validation`, `-data-jpa`, `-oauth2-resource-server`; `flyway-core`; `flyway-database-postgresql`; `postgresql` (runtime); `springdoc-openapi-starter-webmvc-ui` 2.10.0; `keycloak-admin-client` 26.0.12. **Test:** `spring-boot-starter-test`, `spring-security-test`, `spring-boot-testcontainers`, `org.testcontainers:postgresql`. **Build:** `spring-boot-maven-plugin`. Surefire's default includes never match `*E2E.java`, so no exclusion is needed. |
| `{main}/CceAdminServiceApplication.java` | Create | `@SpringBootApplication` and `@ConfigurationPropertiesScan`. |
| `src/main/resources/application.yml` | Create | Two documents. The first: `spring.application.name: cce-admin-service`, `server.port: ${SERVER_PORT:8085}`. The second, with `spring.config.activate.on-profile: "!test"`: `spring.config.import: optional:file:.env[.properties]` (section 9.3). Later phases extend the first document. |
| `.env.example` | Create | Every variable in section 9.3. Secrets and the test administrator's details are `change-me`. The host-run values and `BOOTSTRAP_ADMIN_USERNAME` are written exactly as in 9.3. |
| `docker-compose.yml` | Create | `postgres`, `keycloak` and `mailpit`, exactly as in section 9.1, with their health checks. `gateway-stub` is added in Phase 4, and `admin-service` in Phase 7. |
| `docker/postgres/init/01-create-keycloak-db.sql` | Create | `CREATE DATABASE keycloak OWNER admin_service;`, mounted into `/docker-entrypoint-initdb.d`. |
| `keycloak/realm-cce.json` | Create | The realm in section 9.2. Secrets and the test administrator's details are `${…}` placeholders only. No `clientScopes` key. |
| `scripts/token-claims.mjs` | Create | A Node 22 script with no dependencies. It reads `.env` (`KEY=VALUE`, resolving `${…}` from earlier keys) for `CCE_TEST_ADMIN_USERNAME`, `CCE_TEST_ADMIN_PASSWORD` and `KEYCLOAK_ADMIN_CLIENT_SECRET`, and uses the token endpoint `http://localhost:8180/auth/realms/cce/protocol/openid-connect/token`. `node scripts/token-claims.mjs admin` gets a password-grant token from `cce-e2e` for the test administrator; `node scripts/token-claims.mjs service` gets a client-credentials token for `cce-admin-service`. It prints the token's `iss`, `aud`, `realm_access.roles` and `auth_time`, or with `--token` only the raw token. |
| `README.md` | Create | Prerequisites (Docker with compose, a JDK 21, Node 22), copying `.env.example`, `docker compose up -d --wait`, the URLs in section 9.3, the reset command, and the token script. |

**Exit check:**
1.  `cp .env.example .env`, then replace every `change-me` with a local value.
2.  `docker compose up -d --wait` reports `postgres`, `keycloak` and `mailpit` healthy.
3.  `node scripts/token-claims.mjs admin` prints `iss` = `http://localhost:8180/auth/realms/cce`,
    an `aud` containing `gateway-service`, roles containing `SUPER_ADMIN`, and an `auth_time`.
4.  `node scripts/token-claims.mjs service` prints an `aud` containing `gateway-service` and roles
    containing `CCE_ADMIN_SERVICE`.
5.  The M0 demo: sign in to `http://localhost:8180/auth/realms/cce/account` as the test
    administrator.
6.  `./mvnw verify` passes.
7.  `git status --porcelain` lists no `.env`, and after `set -a; . ./.env; set +a`,
    `git grep -F -e "$POSTGRES_PASSWORD" -e "$KEYCLOAK_ADMIN_PASSWORD" -e "$KEYCLOAK_ADMIN_CLIENT_SECRET" -e "$GATEWAY_STUB_CLIENT_SECRET" -e "$CCE_TEST_ADMIN_PASSWORD"`
    finds nothing.

### Phase 2: Schema, seed and persistence

**Closes:** B-01, B-02.

| File | Action | Contents |
|---|---|---|
| `src/main/resources/db/migration/V1__schema.sql` | Create | Section 6: `pgcrypto`, the six owned tables with their constraints, indexes and the append-only trigger, and the block that creates `api_permissions` with its constraint, indexes, function and trigger only when the table is absent. The outer block is quoted `$do$`, and the function body inside it `$fn$`. |
| `src/main/resources/db/migration/V2__verify_api_permissions.sql` | Create | The shape check in section 6, before any seed row is written. |
| `src/main/resources/db/migration/V3__seed.sql` | Create | The 12 menus and the two system roles with the fixed ids of section 6 and explicit `is_assignable`, and the six `api_permissions` rows with `ON CONFLICT (permission_name, http_method, uri_pattern) DO NOTHING`. |
| `src/main/resources/application.yml` | Modify | `spring.datasource.url: ${DATABASE_URL:}`, `username: ${DATABASE_USERNAME:}`, `password: ${DATABASE_PASSWORD:}`, `hikari.connection-timeout: 2000`. `spring.flyway.baseline-on-migrate: true`, `baseline-version: 0`, `table: admin_service_flyway_history`. `spring.jpa.hibernate.ddl-auto: validate`. `spring.jpa.open-in-view: false`. |
| `src/test/resources/application-test.yml` | Create | The `test` profile. In this phase it holds only `spring.jpa.show-sql: false`; the datasource comes from `@ServiceConnection`. Phase 3 adds the `admin.*` values. |
| `{main}/entity/UserEntity.java`, `RoleEntity.java`, `MenuEntity.java` | Create | One entity per table, with the columns in section 6 (`MenuEntity.active` maps `is_active`). `@PrePersist` sets the id when it is null, and `createdAt` and `updatedAt`. `@PreUpdate` sets `updatedAt`. |
| `{main}/entity/RoleMenuEntity.java`, `UserRoleEntity.java` | Create | Foreign keys mapped as plain `UUID` columns (`roleId`, `menuId`, `userId`), not `@ManyToOne`. `@PrePersist` sets the id when it is null, and `createdAt`. |
| `{main}/entity/AuditLogEntity.java` | Create | `@PrePersist` sets the id and `occurredAt` in Java. JSON columns use `@JdbcTypeCode(SqlTypes.JSON)` with `JsonNode`. |
| `{main}/entity/ApiPermissionEntity.java` | Create | `@Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id`. The `TIMESTAMP` columns map as `LocalDateTime` with `insertable = false, updatable = false`, so the database defaults and trigger apply. |
| `{main}/entity/UserStatus.java`, `MenuType.java`, `SyncStatus.java`, `AuditOutcome.java`, `AuditEntityType.java` | Create | The enums behind the CHECK constraints, stored with `@Enumerated(EnumType.STRING)`. `AuditOutcome` is `SUCCESS` or `FAILED`. |
| `{main}/repository/*.java` | Create | The seven repositories, with exactly the methods in the table below. |
| `{test}/support/DatabaseTestBase.java` | Create | `@SpringBootTest` and `@ActiveProfiles("test")`. A static `PostgreSQLContainer` field on `postgres:16.15-alpine` with `@ServiceConnection`, started once in a `static {}` block (the singleton-container pattern; no `@Testcontainers` or `@Container`), so every test class shares one container and one Spring context. A `@BeforeEach` reset, in order: `TRUNCATE audit_log, user_roles, role_menus, users`; `DELETE FROM api_permissions WHERE permission_name NOT IN ('SUPER_ADMIN', 'CCE_ADMIN_SERVICE')`; `DELETE FROM roles WHERE is_system = false`; `UPDATE roles SET sync_status = 'NEVER', last_sync_at = NULL`; `DELETE FROM menus WHERE parent_id IS NOT NULL AND id::text NOT LIKE '10000000-0000-4000-8000-%'`; `DELETE FROM menus WHERE id::text NOT LIKE '10000000-0000-4000-8000-%'`. Tests never change seed rows; they create their own. Row triggers do not fire on `TRUNCATE`, so the append-only trigger allows the reset. |
| `{test}/support/FreshDatabase.java` | Create | Uses the same container. Creates a uniquely named database, `runSql(Resource)` sends a whole file in one JDBC `Statement.execute` (so `$$` bodies are not split), and `migrate()` runs Flyway through its API with the settings of `application.yml`. No Spring context. |
| `src/test/resources/gateway/gateway-api-permissions.sql` | Create | The fixture below. |
| `src/test/resources/gateway/mismatched-api-permissions.sql` | Create | The same table with `uri_pattern VARCHAR(200)`. |
| `{test}/migration/MigrationEmptyDatabaseTest.java` | Create | With `FreshDatabase`: every table, column, index, constraint and trigger in section 6, the 12 menus and two roles with their fixed ids, and the six permission rows (B-01, B-02). `roles.is_assignable` has no default (AD-14). The history table is `admin_service_flyway_history`. |
| `{test}/migration/MigrationExistingGatewayTableTest.java` | Create | `runSql` the fixture, then `migrate()`. Migration succeeds, the table's definition is unchanged, the five fixture rows remain unchanged, and the six seed rows are added (AD-02). |
| `{test}/migration/MigrationShapeMismatchTest.java` | Create | `runSql` the mismatched fixture, then `migrate()`. V2 fails, and no seed row exists. |
| `{test}/repository/RepositoryQueriesTest.java` | Create | Extends `DatabaseTestBase`. Every custom query in the table below, including that `updateLastLoginIfLater` only moves forward and leaves `updated_at` alone, that `deleteExplicitRows` keeps the implicit rows, that `updateSyncResult` leaves `updated_at` alone, and that `UPDATE` and `DELETE` on `audit_log` fail. |

**Repository methods.** Every bulk delete used by a replace operation (flows 5.4, 5.7 and 5.11)
carries `@Modifying(flushAutomatically = true, clearAutomatically = true)`. Every other
`@Modifying` query carries `@Modifying` alone.

| Repository | Method | Query |
|---|---|---|
| `UserRepository` (also `JpaSpecificationExecutor<UserEntity>`) | `Optional<UserEntity> findByKeycloakId(UUID keycloakId)` | derived |
| | `@Lock(PESSIMISTIC_WRITE) Optional<UserEntity> lockById(UUID id)` | `select u from UserEntity u where u.id = :id` |
| | `boolean existsByUsername(String username)` | derived |
| | `boolean existsByEmail(String email)`, `boolean existsByEmailAndIdNot(String email, UUID id)` | derived; called with `email.toLowerCase(Locale.ROOT)`, so `ux_users_email_lower` matches stored lower-case values |
| | `long countUsersWithRole(String roleName, UserStatus status)` | `select count(u) from UserEntity u, UserRoleEntity ur, RoleEntity r where ur.userId = u.id and ur.roleId = r.id and r.name = :roleName and u.status = :status` |
| | `long countUsersWithRoleExcluding(String roleName, UserStatus status, UUID excludedUserId)` | the same, `and u.id <> :excludedUserId` |
| | `@Modifying @Transactional int updateLastLoginIfLater(UUID id, Instant t)` | native: `UPDATE users SET last_login_at = :t WHERE id = :id AND (last_login_at IS NULL OR last_login_at < :t)` |
| | `@Modifying int touchUpdatedAt(UUID id, Instant at)` | `update UserEntity u set u.updatedAt = :at where u.id = :id` |
| `RoleRepository` | `Optional<RoleEntity> findByName(String name)`, `boolean existsByName(String name)`, `List<RoleEntity> findByNameIn(Collection<String> names)` | derived |
| | `Page<RoleEntity> findAll(Pageable pageable)` | inherited, called with `Sort.by("name", "id")` |
| | `@Lock(PESSIMISTIC_WRITE) Optional<RoleEntity> lockByName(String name)` | `select r from RoleEntity r where r.name = :name` |
| | `@Modifying int updateSyncResult(UUID id, SyncStatus status, Instant at)` | `update RoleEntity r set r.syncStatus = :status, r.lastSyncAt = :at where r.id = :id` (skips `@PreUpdate`, so `updated_at` is unchanged) |
| `MenuRepository` | `List<MenuEntity> findAll(Sort sort)` | inherited, called with `Sort.by("sortOrder", "label")` |
| | `boolean existsByKey(String key)`, `boolean existsByParentId(UUID parentId)` | derived |
| | `List<MenuEntity> findActiveGrantedToRoleNames(Collection<String> roleNames)` | `select distinct m from MenuEntity m, RoleMenuEntity rm, RoleEntity r where rm.menuId = m.id and rm.roleId = r.id and r.name in :roleNames and m.active = true` |
| `RoleMenuRepository` | `List<RoleMenuEntity> findByRoleId(UUID roleId)` | derived |
| | `List<UUID> findRoleIdsByMenuId(UUID menuId)` | `select rm.roleId from RoleMenuEntity rm where rm.menuId = :menuId` |
| | `int deleteByRoleId(UUID roleId)`, `int deleteByMenuId(UUID menuId)` | `delete from RoleMenuEntity rm where rm.roleId = :roleId` (and `rm.menuId = :menuId`) |
| `UserRoleRepository` | `List<UserRoleEntity> findByUserId(UUID userId)`, `boolean existsByRoleId(UUID roleId)` | derived |
| | `List<Object[]> countByRoleIds(Collection<UUID> roleIds)` | `select ur.roleId, count(ur) from UserRoleEntity ur where ur.roleId in :roleIds group by ur.roleId` (for `Role.userCount`, one query per page) |
| | `int deleteByUserIdAndRoleIdIn(UUID userId, Collection<UUID> roleIds)` | `delete from UserRoleEntity ur where ur.userId = :userId and ur.roleId in :roleIds` |
| `ApiPermissionRepository` | `List<ApiPermissionEntity> findByPermissionNameInAndHttpMethod(Collection<String> names, String method)` | derived (section 3, step 5) |
| | `List<ApiPermissionEntity> findByPermissionNameOrderByUriPatternAscHttpMethodAsc(String name)` | derived (a role's rows) |
| | `boolean existsByPermissionName(String name)` | derived |
| | `int deleteExplicitRows(String name, Collection<String> implicitUris)` | `delete from ApiPermissionEntity p where p.permissionName = :name and not (p.httpMethod = 'GET' and p.uriPattern in :implicitUris)`, called with the two implicit URIs |
| | `int deleteByPermissionName(String name)` | `delete from ApiPermissionEntity p where p.permissionName = :name` |
| `AuditLogRepository` | `List<AuditLogEntity> findByEntityTypeAndEntityIdOrderByOccurredAtAsc(AuditEntityType type, UUID id)` | derived |

**The gateway fixture**, `gateway-api-permissions.sql`. The columns, the constraint and the five
sample rows are quoted from the gateway doc. Only the two indexes and the trigger are this
design's reconstruction (section 6, GW-5):

```sql
CREATE TABLE api_permissions (
    id BIGSERIAL PRIMARY KEY,
    permission_name VARCHAR(100) NOT NULL,
    http_method VARCHAR(10) NOT NULL,
    uri_pattern VARCHAR(255) NOT NULL,
    description TEXT,
    resource_category VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_permission_method_uri UNIQUE (permission_name, http_method, uri_pattern)
);
CREATE INDEX idx_permission_name ON api_permissions (permission_name);
CREATE INDEX idx_resource_category ON api_permissions (resource_category);
CREATE FUNCTION update_api_permissions_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_api_permissions_updated_at BEFORE UPDATE ON api_permissions
    FOR EACH ROW EXECUTE FUNCTION update_api_permissions_updated_at();
INSERT INTO api_permissions (permission_name, http_method, uri_pattern, description, resource_category) VALUES
    ('USER_READ', 'GET', '/admin/users/*', 'View specific user details', 'Users'),
    ('USER_READ', 'GET', '/admin/users', 'List all users', 'Users'),
    ('USER_CREATE_UPDATE', 'POST', '/admin/users', 'Create new user', 'Users'),
    ('USER_CREATE_UPDATE', 'PUT', '/admin/users/*', 'Update user details', 'Users'),
    ('USER_CREATE_UPDATE', 'PATCH', '/admin/users/*', 'Partially update user', 'Users');
```

**Exit check:** `./mvnw verify` passes.

### Phase 3: Web foundation, security, audit writer, `/health` and `/me`

**Closes:** B-05, B-10, B-11, B-12, B-13, B-20. Also B-03 and B-04 for the envelope, the error
mapping and `PageParams`, completed in Phases 5 and 6, and B-06 for the endpoints built so far.

| File | Action | Contents |
|---|---|---|
| `src/main/resources/application.yml` | Modify | The `admin:` block below. Also: `server.forward-headers-strategy: native`; `server.tomcat.remoteip.internal-proxies: ${TRUSTED_PROXIES:(?!)}`, where `(?!)` matches no address (AD-19); `springdoc.api-docs.path: /admin/v1/api-docs`, `springdoc.swagger-ui.path: /admin/v1/swagger-ui`, and both `springdoc.api-docs.enabled` and `springdoc.swagger-ui.enabled` set to `${SPRINGDOC_ENABLED:true}`; `spring.jackson.deserialization.fail-on-unknown-properties: true`. |
| `src/test/resources/application-test.yml` | Modify | Fixed values for every `admin.*` property. The test issuer and audience equal the values `TestJwtFactory` signs with (`https://test-issuer.local/realms/cce` and `gateway-service`). The decoder itself comes from `TestSecurityConfig`. |
| `{main}/config/AdminProperties.java` | Create | A `@ConfigurationProperties("admin")` record with `@Validated`, registered by `@ConfigurationPropertiesScan`. Every value that 9.4 lists as required carries `@NotBlank(message = "set <VARIABLE>")`, naming its environment variable. Because every required value is written `${VAR:}`, a missing variable binds as an empty string and fails validation at startup (B-13). |
| `{main}/config/JacksonConfig.java`, `{main}/config/TrimmingStringDeserializer.java` | Create | A `Jackson2ObjectMapperBuilderCustomizer` that registers the deserializer, which trims every request string and turns an empty one into null (section 4.1). Boot's own mapper is kept. |
| `{main}/config/OpenApiConfig.java` | Create | The OpenAPI title and version, and the bearer security scheme (B-06). |
| `{main}/security/SecurityConfig.java` | Create | The chain in section 3: stateless, CSRF off, the public paths and `DispatcherType.ERROR` permitted, and the resource server with `.jwt(Customizer.withDefaults())`, which takes the `JwtDecoder` bean from the context. The `jwtDecoder` bean is built from `JWT_JWK_SET_URI`, with validators for issuer and timestamps (30 s skew) and a `JwtClaimValidator<List<String>>("aud", a -> a != null && a.contains(audience))`. `CurrentUserFilter` and `PermissionCheckFilter` are created here, not as `@Component`s, and added after `BearerTokenAuthenticationFilter`. |
| `{main}/security/RolesClaimReader.java` | Create | Reads role names along the dotted `JWT_ROLES_CLAIM_PATH` (B-11). |
| `{main}/security/CurrentUser.java`, `CurrentUserFilter.java` | Create | Section 3, step 4 (local user), under the filter rules of section 3: passes through unless the authentication is a `JwtAuthenticationToken`; catches unexpected exceptions and writes 500 `INTERNAL_ERROR`. |
| `{main}/security/PermissionCheckFilter.java` | Create | Section 3, step 5 (permission check), with `AntPathMatcher` and `findByPermissionNameInAndHttpMethod`, under the same filter rules, and 403 without a query when the role list is empty. Logs the value of `PERMISSION_CHECK_ENABLED` at startup, and a warning when it is `false`. |
| `{main}/security/SecurityErrorWriter.java` | Create | Writes the 401, 403 and 500 error envelopes from inside the filter chain. For 401 it first calls `BearerTokenAuthenticationEntryPoint` to set the `WWW-Authenticate` header, then writes the body. |
| `{main}/audit/ActorProvider.java` | Create | The actor for audit rows: the request's `CurrentUser`, or `system:bootstrap` when there is no request. Service tests that call services directly therefore record `system:bootstrap`. Tests that check the actor go through MockMvc or HTTP. |
| `{main}/audit/AuditAction.java`, `AuditEntry.java`, `AuditWriter.java` | Create | The actions in section 8, an entry record, `record` (`Propagation.MANDATORY`) and `recordInNewTransaction` (`Propagation.REQUIRES_NEW`). The IP address is `request.getRemoteAddr()`. |
| `{main}/service/exception/ErrorCode.java`, `ApiException.java`, `PartialState.java`, `KeycloakSyncFailedException.java` | Create | Every code in section 4.2 with its HTTP status; the base exception (code, message, optional `details`); the `PartialState` record; the 502 exception that carries it. |
| `{main}/controller/ApiExceptionHandler.java` | Create | `@RestControllerAdvice`. Maps `ApiException`; `MethodArgumentNotValidException`, `HandlerMethodValidationException`, `HttpMessageNotReadableException` (including unknown properties), `MethodArgumentTypeMismatchException` and `HttpMediaTypeNotSupportedException` to 400 `VALIDATION_ERROR`; `NoResourceFoundException` to 404 `NOT_FOUND`; `HttpRequestMethodNotSupportedException` to 405 `METHOD_NOT_ALLOWED`; anything else to 500 `INTERNAL_ERROR`. Its `KeycloakSyncFailedException` branch logs one `ERROR` line: `event=keycloak_sync_failed operation=<op> entityId=<id> failedStep=<step> completedSteps=<a,b> keycloakUserId=<id or none> auditLogId=<id or none>`. An `@InitBinder` registers `StringTrimmerEditor(true)`, so query parameters are trimmed too. |
| `{main}/controller/PageParams.java` | Create | Validates `page` and `pageSize` (section 4.3). |
| `{main}/controller/dto/CommonDtos.java` | Create | Nested records `ApiResponse<T>`, `Pagination`, `Warning`, `ErrorBody`, `FieldErrorDto` and `PartialStateDto`. Only `pagination`, `warnings`, `details`, `partialState`, and `Warning`'s `menuId` and `parentMenuId`, carry `@JsonInclude(NON_NULL)` (section 4.1). |
| `{main}/controller/dto/MeDtos.java` | Create | Nested records `Me` and `MenuTreeItem` (section 4.4). |
| `{main}/service/HealthService.java`, `{main}/controller/HealthController.java` | Create | `GET /admin/v1/health` (B-05). The service runs `SELECT 1` with a 2-second query timeout. The controller only shapes the response, as section 2 requires. |
| `{main}/controller/MeController.java`, `{main}/service/CurrentUserService.java` | Create | `GET /admin/v1/me` (B-20). `/me/menus` comes in Phase 7. |
| `Dockerfile` | Create | Multi-stage. Build on `maven:3.9.16-eclipse-temurin-21` with `mvn -B -DskipTests package`. Run on `eclipse-temurin:21.0.13_9-jre-alpine` as a non-root user, `EXPOSE 8085`, with a `HEALTHCHECK` that runs `wget -qO- http://localhost:8085/admin/v1/health`. |
| `.dockerignore` | Create | Leaves out `target/`, `.env`, `gateway-stub/target/` and `.git`. |
| `src/test/resources/contract/endpoints.txt` | Create | One line per endpoint, as `METHOD /admin/v1/path`, with path parameters written `{id}`. This phase lists `GET /admin/v1/health` and `GET /admin/v1/me`. |
| `{test}/support/DatabaseTestBase.java` | Modify | Adds `@AutoConfigureMockMvc` and `@Import(TestSecurityConfig.class)`, so web and service tests share the one context. |
| `{test}/support/TestJwtFactory.java` | Create | Generates an RSA key pair once per test run and signs tokens with any `iss`, `aud`, `exp`, `sub`, roles and `auth_time`. |
| `{test}/support/TestSecurityConfig.java` | Create | A `@TestConfiguration` with a `@Primary JwtDecoder` built from `TestJwtFactory`'s public key and `SecurityConfig`'s own validators, so the 401 cases run the real validation. |
| `{test}/config/AdminPropertiesValidationTest.java` | Create | `ApplicationContextRunner` with `@EnableConfigurationProperties(AdminProperties.class)`: a missing `JWT_ISSUER`, `KEYCLOAK_REALM` or `GATEWAY_BASE_URL` fails startup with a message naming the variable. |
| `{test}/security/RolesClaimReaderTest.java` | Create | Default path, a custom path, a missing claim, and a claim that is not a list. |
| `{test}/security/SecurityPipelineWebTest.java` | Create | With `TestJwtFactory` tokens: 401 (with `WWW-Authenticate`) for missing, malformed, wrongly signed, expired, wrong-issuer and wrong-audience tokens. 403 for no local user, `INACTIVE` and `LOCKED`. 403 for a token with no roles, and when no permission row matches. 200 when a row with pattern `/admin/v1/**` matches `GET /admin/v1/me`. `/health` needs no token. `last_login_at` is set from `auth_time`. Covers B-10 to B-12. |
| `{test}/security/PermissionCheckOffWebTest.java` | Create | Its own class with `@TestPropertySource(properties = "admin.permission.check-enabled=false")`: a caller with no matching row gets 200. |
| `{test}/security/FilterFailureWebTest.java` | Create | `@MockitoSpyBean UserRepository` throws on `findByKeycloakId`: the response is 500 with the `INTERNAL_ERROR` envelope. |
| `{test}/controller/ApiErrorShapeWebTest.java` | Create | The envelope and null-field rules, an unknown property, malformed JSON, an unsupported content type, a bad UUID, 404 and 405 (B-03). |
| `{test}/controller/InternalErrorWebTest.java` | Create | `@MockitoBean HealthService` throws: the response is 500 with the `INTERNAL_ERROR` envelope. |
| `{test}/controller/OpenApiDocumentTest.java` | Create | Every line of `endpoints.txt` appears in `/admin/v1/api-docs` (B-06). |
| `{test}/audit/AuditWriterTest.java` | Create | `record` without a transaction fails. `recordInNewTransaction` survives the outer rollback. The actor outside a request is `system:bootstrap`. |

**The `admin:` block** of `application.yml`. Variables in section 9.4 that are not listed here bind
elsewhere: `SERVER_PORT` to `server.port`, `DATABASE_*` to `spring.datasource.*`,
`SPRINGDOC_ENABLED` to `springdoc.*`, and `TRUSTED_PROXIES` to
`server.tomcat.remoteip.internal-proxies`.

```yaml
admin:
  jwt:
    issuer: ${JWT_ISSUER:}                                   # required
    audience: ${JWT_AUDIENCE:}                               # required
    jwk-set-uri: ${JWT_JWK_SET_URI:${JWT_ISSUER:}/protocol/openid-connect/certs}
    roles-claim-path: ${JWT_ROLES_CLAIM_PATH:realm_access.roles}
  keycloak:
    server-url: ${KEYCLOAK_SERVER_URL:}                      # required
    realm: ${KEYCLOAK_REALM:}                                # required
    client-id: ${KEYCLOAK_ADMIN_CLIENT_ID:cce-admin-service}
    client-secret: ${KEYCLOAK_ADMIN_CLIENT_SECRET:}          # required
    connect-timeout: ${KEYCLOAK_CONNECT_TIMEOUT:2s}
    read-timeout: ${KEYCLOAK_READ_TIMEOUT:10s}
    action-email-lifespan: ${KEYCLOAK_ACTION_EMAIL_LIFESPAN:43200}
    action-email-client-id: ${KEYCLOAK_ACTION_EMAIL_CLIENT_ID:}
    action-email-redirect-uri: ${KEYCLOAK_ACTION_EMAIL_REDIRECT_URI:}
  gateway:
    base-url: ${GATEWAY_BASE_URL:}                           # required
    sync-path: ${GATEWAY_SYNC_PATH:/api/permissions/sync-to-keycloak}
    connect-timeout: ${GATEWAY_CONNECT_TIMEOUT:2s}
    read-timeout: ${GATEWAY_READ_TIMEOUT:10s}
  permission:
    check-enabled: ${PERMISSION_CHECK_ENABLED:true}
  bootstrap:
    admin-username: ${BOOTSTRAP_ADMIN_USERNAME:}
```

**Exit check:**
1.  `./mvnw verify` passes.
2.  `docker build -t cce-admin-service:dev .` succeeds.
3.  With the compose stack up, run `./mvnw spring-boot:run`, then:
    *   `curl -s localhost:8085/admin/v1/health` returns `{"status":"UP","database":"UP"}`;
    *   `curl -i -H 'Authorization: Bearer x' localhost:8085/admin/v1/me` returns 401 with code
        `UNAUTHENTICATED` (the B1 demo);
    *   the same call with the token from `node scripts/token-claims.mjs admin --token` returns
        403 `FORBIDDEN`, because no local user exists until Phase 4 (B-12).

### Phase 4: Keycloak and gateway clients, account linking, bootstrap, stand-in

**Implements:** AD-11, AD-13, AD-15, the linking method of AD-18, and the sync procedure in 5.10.

| File | Action | Contents |
|---|---|---|
| `{main}/client/KeycloakGateway.java`, `KeycloakUserInfo.java`, `KeycloakConflictException.java`, `KeycloakCallFailedException.java` | Create | The interface and its types in section 7. |
| `{main}/client/KeycloakAdminClientGateway.java` | Create | Implements the interface with `keycloak-admin-client`. Maps a 404 on a lookup to empty, a 409 to `KeycloakConflictException`, and every other failure to `KeycloakCallFailedException`. `createUser` closes the create `Response` with `try (Response r = …)` and reads the id with `CreatedResponseUtil.getCreatedId(r)`. |
| `{main}/config/KeycloakClientConfig.java` | Create | The `Keycloak` bean: `KeycloakBuilder` with the client-credentials grant and `.resteasyClient(...)` set to the client built exactly as in section 7 (`connectionPoolSize(10)`, the configured connect and read timeouts, and the library's `JacksonProvider`). Also a `Supplier<String>` bean, `serviceAccessToken`, returning `keycloak.tokenManager().getAccessTokenString()`. |
| `{main}/client/GatewaySyncClient.java`, `SyncResult.java`, `HttpGatewaySyncClient.java` | Create | The sync client in section 7, using the `serviceAccessToken` supplier. |
| `{main}/config/GatewayClientConfig.java` | Create | The `RestClient` bean for the gateway, with request factory `ClientHttpRequestFactoryBuilder.jdk().build(ClientHttpRequestFactorySettings.defaults().withConnectTimeout(connectTimeout).withReadTimeout(readTimeout))`. |
| `{main}/service/GatewaySyncService.java` | Create | The procedure in 5.10. Step 3 runs in a new `TransactionTemplate` transaction and records the result with `RoleRepository.updateSyncResult`, never by saving the entity. |
| `{main}/controller/dto/UserDtos.java` | Create | The `User` response record (section 4.4). Phase 6 adds the request records. |
| `{main}/service/UserViews.java` | Create | Maps a `UserEntity` and its roles to `User`. Used for response bodies and for audit JSON. |
| `{main}/service/AccountLinker.java` | Create | `link(KeycloakUserInfo account, UUID userId, List<RoleEntity> roles, String facilityScope, boolean linkedExisting)`: 5.1 steps 5 (`KEYCLOAK_ROLES_ASSIGNED`) and 6 (`LOCAL_USER_SAVED`, writing `USER_CREATED` with `linked`), with the failure rows of section 8. `reactivateAsAdmin(UserEntity existing, KeycloakUserInfo account)`: adds the `SUPER_ADMIN` realm role mapping with `addRealmRoles` when it is missing, sets the row `ACTIVE`, adds the `SUPER_ADMIN` `user_roles` row, and writes `USER_ROLES_UPDATED`. Both are used by `BootstrapAdminRunner`. `link` is also used by `UserService` (Phase 6). |
| `{main}/service/BootstrapAdminRunner.java` | Create | An `ApplicationRunner` with `@Profile("!test")` that runs 5.13 through `AccountLinker`. It never throws. |
| `{test}/support/DatabaseTestBase.java` | Modify | Adds `@MockitoBean KeycloakGateway` and `@MockitoBean GatewaySyncClient` to the base class, so every database test shares one context. |
| `docker-compose.yml` | Modify | Adds `gateway-stub` as in section 9.1. |
| `gateway-stub/pom.xml` | Create | `org.cce:cce-gateway-stub:0.1.0-SNAPSHOT`. Parent `spring-boot-starter-parent` 3.5.16, used only for plugin and library versions; there is no Spring dependency at runtime. **Dependencies:** `jackson-databind` (managed); `org.postgresql:postgresql` 42.7.14, an explicit override of the parent's managed version, as D-05 pins it; JUnit Jupiter for tests (managed). **Build:** the property `<start-class>org.cce.gatewaystub.GatewayStubMain</start-class>` and a `maven-shade-plugin` declaration with no configuration, which uses the parent's shade setup to build a runnable jar. |
| `gateway-stub/Dockerfile` | Create | Build context `.`. Build on `maven:3.9.16-eclipse-temurin-21` with `mvn -B -f gateway-stub/pom.xml -DskipTests package`. Run on `eclipse-temurin:21.0.13_9-jre-alpine` as a non-root user. |
| `gateway-stub/README.md` | Create | States that this is a stand-in, not gateway-service. Lists the GW-1 and GW-4 assumptions it implements, that it checks only for a bearer header, its variables, and the mode switch. |
| `gateway-stub/src/main/java/org/cce/gatewaystub/GatewayStubMain.java` | Create | Starts `com.sun.net.httpserver.HttpServer` on `STUB_PORT`, with routes `POST /api/permissions/sync-to-keycloak` and `GET`/`PUT /_stub/mode`, wiring the JDBC and HTTP implementations below. |
| `gateway-stub/src/main/java/org/cce/gatewaystub/StubConfig.java` | Create | Reads the variables in section 9.1. |
| `gateway-stub/src/main/java/org/cce/gatewaystub/ModeHandler.java` | Create | Holds `ok` or `fail` in memory, starting at `ok`. A `PUT` with any other body gets 400. |
| `gateway-stub/src/main/java/org/cce/gatewaystub/PermissionStore.java`, `JdbcPermissionStore.java` | Create | An interface returning the distinct `permission_name` values of `api_permissions`, and its JDBC implementation. |
| `gateway-stub/src/main/java/org/cce/gatewaystub/RealmRoles.java`, `HttpRealmRoles.java` | Create | An interface to list (with attributes), create and delete realm roles, and its `java.net.http` implementation against the Keycloak Admin REST API with the stub client's own client-credentials token. It lists with `?briefRepresentation=false`, and creates a role with `cce-managed=true`; if a following `GET` shows the attribute missing, it sets it with `PUT`. |
| `gateway-stub/src/main/java/org/cce/gatewaystub/RealmRoleSyncPlanner.java` | Create | Pure logic: from the permission names and the existing roles, which roles to create and which to delete, under the rules in section 9.1. |
| `gateway-stub/src/main/java/org/cce/gatewaystub/SyncHandler.java` | Create | Checks in this order: in `fail` mode, 503; without a bearer header, 401; otherwise it applies the plan and returns 200 with a JSON summary. |
| `gateway-stub/src/test/java/org/cce/gatewaystub/RealmRoleSyncPlannerTest.java`, `ModeHandlerTest.java`, `SyncHandlerTest.java` | Create | Every planner rule, including that built-in roles, untagged roles and `SUPER_ADMIN` are never deleted. The mode switch. 503, 401 and 200 from the sync handler, with hand-written in-memory `PermissionStore` and `RealmRoles` implementations. |
| `{test}/client/HttpGatewaySyncClientTest.java` | Create | Against a JDK `HttpServer` fake: the bearer header, path and empty body. 2xx is success. 4xx, 5xx and a timeout are failures, with the summary cut to 500 characters. A failing token supplier is a failed sync. |
| `{test}/service/GatewaySyncServiceTest.java` | Create | Success and failure each set only the asking role's status, leave its `updated_at` unchanged, and write exactly one `ROLE_SYNC_REQUESTED` row (AD-06). |
| `{test}/service/AccountLinkerTest.java` | Create | `link` writes the mappings, the rows and `USER_CREATED` with `linked`. A failure at each step writes the failure row of section 8. `reactivateAsAdmin` adds a missing mapping and writes `USER_ROLES_UPDATED`. |
| `{test}/service/BootstrapAdminRunnerTest.java` | Create | Constructed directly, with a mocked `KeycloakGateway` and a real `AccountLinker`: an administrator exists (with and without the variable, and the warning); no variable; user missing; user disabled; email unverified while verification is required; the `SUPER_ADMIN` realm role missing (error, no sync, no change); the success path (one row by `system:bootstrap`); re-activating an existing row. |

**Exit check:**
1.  `./mvnw verify` and `./mvnw -f gateway-stub/pom.xml verify` both pass.
2.  `docker compose up -d --build --wait` reports `gateway-stub` healthy.
3.  `./mvnw spring-boot:run`. The log shows the bootstrap linking the administrator.
    `GET /admin/v1/me` with the test administrator's token returns 200 with
    `roles: ["SUPER_ADMIN"]`. After a restart, the log shows the warning that
    `BOOTSTRAP_ADMIN_USERNAME` is no longer needed.
4.  On the stand-in:
    *   `curl -i -X POST localhost:8090/api/permissions/sync-to-keycloak` returns 401;
    *   with `-H "Authorization: Bearer $(node scripts/token-claims.mjs service --token)"` it
        returns 200;
    *   after `curl -X PUT --data fail localhost:8090/_stub/mode` it returns 503;
    *   after `--data ok` it returns 200 again.

### Phase 5: Roles, menus, permissions and sync

**Closes:** B-40, B-41, B-42, B-43, B-44, and B-50 for these endpoints (milestones B2 and B4).

| File | Action | Contents |
|---|---|---|
| `{main}/controller/dto/RoleDtos.java`, `MenuDtos.java` | Create | Role, Permission row, Menu, `RoleMenus`, `RolePermissions` and `RoleSyncResult`, and the request records, with the bean-validation rules in section 4.4. |
| `{main}/controller/RoleController.java` | Create | The ten `/roles` endpoints in section 4.5: 201 with `Location` on create, 204 on delete. |
| `{main}/controller/MenuController.java` | Create | The four `/menus` endpoints in section 4.5. |
| `{main}/service/ImplicitPermissions.java` | Create | The two implicit rows of section 4.4, their URIs for `deleteExplicitRows`, and a predicate `isImplicit(method, uriPattern)`. |
| `{main}/service/PermissionRowRules.java` | Create | Upper-cases methods, checks them against the fixed list, checks the `uriPattern` rules (section 4.4), drops implicit duplicates, and rejects duplicates with `details`. |
| `{main}/service/RoleService.java` | Create | List (with `countByRoleIds` for `userCount`), get, create (5.6), update display fields (5.12) and delete (5.9), including the three `DUPLICATE_ROLE_NAME` checks, the system-role rule and both `ROLE_IN_USE` checks. The create transaction commits before `GatewaySyncService` is called. |
| `{main}/service/RolePermissionService.java` | Create | Get and replace permissions (5.7) and manual sync (5.8). The `PERMISSIONS_SAVED` transaction commits before `GatewaySyncService` is called. |
| `{main}/service/RoleMenuService.java` | Create | Get and replace menus, with the `DETAIL_WITHOUT_PARENT` warnings (5.11). |
| `{main}/service/MenuService.java` | Create | The tree for `GET /menus`, plus create, update and delete (5.12), with the parent rules and `MENU_HAS_CHILDREN`. |
| `src/test/resources/contract/endpoints.txt` | Modify | Adds the 14 role and menu endpoints (ten role, four menu). |
| `{test}/service/RoleServiceTest.java`, `RolePermissionServiceTest.java`, `RoleMenuServiceTest.java`, `MenuServiceTest.java` | Create | On `DatabaseTestBase`, with its two client mocks. For every endpoint: the happy path, each error code, no-ops, and the exact audit rows under section 8. That includes create with a failed sync (201, `ROLE_SYNC_FAILED`, two rows), the permission save on both outcomes (two rows), a manual sync (one row), delete (one row, no sync call), and the 502 `partialState` for `ROLE_PERMISSIONS_SAVE` and `ROLE_SYNC`. **Commit before sync:** the mocked `sync()` reads the role's rows in a separate `REQUIRES_NEW` transaction and finds them, for create and for replace. **502 log:** with `OutputCaptureExtension`, one failed permission save logs the `event=keycloak_sync_failed` line with its fields. |
| `{test}/controller/RoleAndMenuContractWebTest.java` | Create | JSON shapes as in section 4.4: the warning union, the `implicit` flag, 204 bodies, and unknown properties such as `name` on `PUT /roles/:id`. |
| `{test}/security/ClientIpDefaultWebTest.java`, `ClientIpTrustedProxyWebTest.java` | Create | `@SpringBootTest(webEnvironment = RANDOM_PORT)`, through a real HTTP client and Tomcat. Each sends `POST /admin/v1/roles` with `X-Forwarded-For: 203.0.113.7` and reads the `ROLE_CREATED` row's `ip_address`. With the default `TRUSTED_PROXIES` it is the loopback address. The second class sets `@TestPropertySource(properties = "server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1|0:0:0:0:0:0:0:1")`, and it is `203.0.113.7`. |

**Exit check:**
1.  `./mvnw verify` passes.
2.  With the stack and the service running, and `TOKEN=$(node scripts/token-claims.mjs admin --token)`,
    use curl or Swagger UI to:
    1.  create a role `PHASE5_ROLE`: 201, `syncStatus: SYNCED`;
    2.  check the realm role and its tag:
        `docker compose exec postgres psql -U admin_service -d keycloak -c "SELECT r.name, a.name, a.value FROM keycloak_role r JOIN role_attribute a ON a.role_id = r.id WHERE r.name = 'PHASE5_ROLE'"`
        shows `cce-managed | true`;
    3.  save menus that include a `DETAIL` without its parent: 200 with `DETAIL_WITHOUT_PARENT`;
    4.  switch the stand-in to `fail` and save permissions: 502 with
        `operation: ROLE_PERMISSIONS_SAVE`;
    5.  switch back to `ok` and re-sync: 200.
3.  `docker compose exec postgres psql -U admin_service -d admin_service -c "SELECT action, outcome FROM audit_log WHERE entity_type = 'ROLE' AND entity_id = (SELECT id FROM roles WHERE name = 'PHASE5_ROLE') ORDER BY occurred_at"`
    shows, in this order: `ROLE_CREATED SUCCESS`, `ROLE_SYNC_REQUESTED SUCCESS`,
    `ROLE_MENUS_UPDATED SUCCESS`, `ROLE_PERMISSIONS_UPDATED SUCCESS`,
    `ROLE_SYNC_REQUESTED FAILED`, `ROLE_SYNC_REQUESTED SUCCESS`.

### Phase 6: Users and Keycloak provisioning

**Closes:** B-30, B-31, B-32, B-33, B-34, B-35, B-36, and B-50 for these endpoints (milestone
B3).

| File | Action | Contents |
|---|---|---|
| `{main}/controller/dto/UserDtos.java` | Modify | Adds `ResetPasswordResult` and the four request records, with the bean-validation rules in section 4.4. |
| `{main}/controller/UserController.java` | Create | The seven `/users` endpoints in section 4.5. |
| `{main}/service/UserSearchSpecifications.java` | Create | JPA specifications for `status`, `roleId`, `facilityScope` and `q`. |
| `{main}/service/UserChangeLocks.java` | Create | The locks of section 5: `UserRepository.lockById`, and `RoleRepository.lockByName("SUPER_ADMIN")` with the `LAST_SYSTEM_ADMIN` check, taken only when an administrator could be lost. |
| `{main}/service/UserService.java` | Create | List and get, and flows 5.1 to 5.5 with their step names. 5.1 assigns the id first, decides between creating and linking, and calls `AccountLinker.link` for steps 5 and 6. In 5.4, `users.updated_at` is set with `UserRepository.touchUpdatedAt` after the `user_roles` bulk delete, not through the detached entity. There is no compensation. |
| `src/test/resources/contract/endpoints.txt` | Modify | Adds the seven user endpoints. |
| `{test}/service/UserCreateFlowTest.java` | Create | Every step of 5.1. The PRD's case: `@MockitoSpyBean UserRepository` throws on `save` after Keycloak succeeded, giving 502 and a `FAILED` row with the Keycloak id (B-32); resubmitting then links and completes. Linking an existing enabled account. 409 for a disabled match and for a match on one field only, with the message. `ROLE_NOT_ASSIGNABLE`, `ROLE_NOT_SYNCED`, the email warning, and the Keycloak-409 race. **502 log:** with `OutputCaptureExtension`, the 502 case logs the `event=keycloak_sync_failed` line. |
| `{test}/service/UserProfileFlowTest.java`, `UserStatusFlowTest.java`, `UserRolesFlowTest.java`, `UserResetPasswordTest.java` | Create | Flows 5.2 to 5.5: each step, the happy path, every error code, no-ops, the exact audit rows, resubmission after a 502, and that only the added and removed realm roles are touched (B-35). `UserRolesFlowTest` also asserts that `updatedAt` changed. |
| `{test}/service/LastSystemAdminConcurrencyTest.java` | Create | Two `ACTIVE` administrators A and B. Thread 1 deactivates A; the mocked `KeycloakGateway.setEnabled` blocks on a latch, so thread 1 holds the `SUPER_ADMIN` lock. Thread 2 deactivates B. The test thread polls `pg_stat_activity` until a backend shows `wait_event_type = 'Lock'`, then releases the latch. Each wait has a 10-second timeout. Exactly one request returns 200 and the other 409 `LAST_SYSTEM_ADMIN`. |
| `{test}/controller/UserContractWebTest.java` | Create | Pagination, filters, search, the `Location` header, and the 201 warning shape. |

**Exit check:**
1.  `./mvnw verify` passes.
2.  With the stack and the service running, `TOKEN` as in Phase 5, and the role `PHASE5_ROLE`
    from Phase 5 synced (the B3 demo):
    *   `POST /admin/v1/users` with `{"username":"phase6-user","email":"phase6-user@cce.local","firstName":"Phase","lastName":"Six","roleIds":["<PHASE5_ROLE id>"]}`
        returns 201, and the Keycloak admin console shows the user holding the realm role
        `PHASE5_ROLE`;
    *   Mailpit at `http://localhost:8025` shows the set-password email to
        `phase6-user@cce.local`;
    *   `PATCH /admin/v1/users/<id>/status` with `{"status":"INACTIVE"}` returns 200, and the
        Keycloak admin console shows the user disabled;
    *   the same call with `{"status":"ACTIVE"}` returns 200, and the user is enabled again.

### Phase 7: Role-aware menu tree, service container, contract completeness

**Closes:** B-21 (milestone B5), and B-06 for every endpoint. Adds
`MutatingEndpointsAuditGuardTest`.

| File | Action | Contents |
|---|---|---|
| `{main}/service/MenuTreeBuilder.java` | Create | The `/me/menus` rules in section 4.5. |
| `{main}/service/CurrentUserService.java`, `{main}/controller/MeController.java` | Modify | Add `GET /admin/v1/me/menus`, using `MenuRepository.findActiveGrantedToRoleNames`. |
| `docker-compose.yml` | Modify | Adds the `admin-service` block exactly as written in section 9.1. |
| `README.md` | Modify | Running on the host and in a container, the configuration table (section 9.4), the bootstrap steps (section 9.5) with both operator queries, the rollout steps from the "Rollout and rollback" item of Non-Functional Requirements, the stand-in switch, and the reset. |
| `src/test/resources/contract/endpoints.txt` | Modify | Adds `GET /admin/v1/me/menus`. The file now lists all 24 endpoints in section 4.5. |
| `{test}/service/MenuTreeBuilderTest.java`, `CurrentUserMenusTest.java` | Create | Union across roles, inactive menus left out, a `DETAIL` only under a granted parent, ordering, and the B5 case of two overlapping roles. |
| `{test}/audit/MutatingEndpointsAuditGuardTest.java` | Create | Injects `RequestMappingHandlerMapping` by the bean name `requestMappingHandlerMapping` and reads every non-GET mapping in package `org.cce.admin.controller`. It fails the build when one is missing from a fixed list in the test, so a new mutating endpoint forces a review. It does not verify that an audit test exists. |

**Exit check:**
1.  `./mvnw verify` passes, and `OpenApiDocumentTest` covers all 24 endpoints.
2.  `docker compose --profile app up -d --build --wait` reports all five services healthy.
3.  `curl -s localhost:8085/admin/v1/health` returns `{"status":"UP","database":"UP"}`.
4.  `curl -s -H "Authorization: Bearer $(node scripts/token-claims.mjs admin --token)" localhost:8085/admin/v1/me`
    returns 200 from the container, which shows that its in-network `JWT_JWK_SET_URI` works.

### Phase 8: API-level end-to-end checks (S1 to S6)

**Closes:** milestone E2E, and scenarios S1 to S6 at API level.

| File | Action | Contents |
|---|---|---|
| `pom.xml` | Modify | Adds profile `e2e`. In it, Surefire gets `<configuration><skipTests>true</skipTests></configuration>` (not the `skipTests` property, which would also skip Failsafe), and Failsafe runs with `<includes><include>**/*E2E.java</include></includes>` and its `integration-test` and `verify` goals. |
| `{test}/e2e/E2eEnvironment.java` | Create | Reads `.env` (`KEY=VALUE`, resolving `${…}` from earlier keys), with process environment variables taking precedence. Provides the URLs in section 9.3 and the per-run suffix: the last 8 digits of the epoch seconds. Created names: roles `E2E_<SCENARIO>_<suffix>` (for example `E2E_S3_12345678`), users `e2e-<scenario>-<suffix>` with email `e2e-<scenario>-<suffix>@cce.local`, menus with key `e2e-<scenario>-<suffix>`. |
| `{test}/e2e/E2eClients.java` | Create | One `RestClient` for each of the service, the stand-in and Mailpit, and a `keycloak-admin-client` instance for the local master `admin` (`KEYCLOAK_ADMIN_PASSWORD`), used for test-only setup: setting a password and clearing required actions, creating a Keycloak-only account, and reading a user's `enabled` flag and realm roles. |
| `{test}/e2e/TokenClient.java` | Create | Gets password-grant tokens from `cce-e2e`. |
| `{test}/e2e/StubMode.java` | Create | Flips `/_stub/mode`. Every test resets it to `ok` in `@AfterEach`. |
| `{test}/e2e/Scenario*E2E.java`, `HardControlE2E.java` | Create | One class per row of the table below. |
| `README.md` | Modify | How to run the E2E checks. |

Each scenario class implements the matching bullet of section 10, with these specifics:

| Class | Setup | Checks |
|---|---|---|
| `ScenarioS1MenusE2E` | Two roles with overlapping menus, synced; a user holding both, with a password set through `E2eClients`. | The user's `GET /me/menus` is the merged tree (S1, B5). |
| `ScenarioS2OnboardUserE2E` | A synced role; a Keycloak-only account created through `E2eClients`. | A new user is created with Keycloak roles, a local row, `USER_CREATED` and the Mailpit email; the Keycloak-only account is linked (`after.linked = true`). |
| `ScenarioS3ConfigureRoleE2E` | None. | Create gives `SYNCED` and a realm role; a `DETAIL`-only grant gives the warning; a permission save gives 200 and two audit rows. |
| `ScenarioS4ChangeRolesE2E` | Two synced roles and a user holding one. | Replacing the set moves only the changed Keycloak mappings. |
| `ScenarioS5DeactivateE2E` | A user with a synced role and a password. | `INACTIVE` disables the Keycloak account, keeps `user_roles`, and the user's token gets 403; `ACTIVE` restores access. |
| `ScenarioS6SyncFailureE2E` | A user holding one already-synced role, created **before** the stand-in is switched to `fail`. | With the stand-in failing: a new role is created with 201 and `ROLE_SYNC_FAILED`; its permission save gives 502 `ROLE_PERMISSIONS_SAVE` with the rows saved and a `FAILED` `ROLE_SYNC_REQUESTED` row; assigning it to the user gives 409 `ROLE_NOT_SYNCED`. After switching to `ok`, a re-sync gives 200 and the assignment succeeds. |
| `HardControlE2E` | A role `E2E_HC_<suffix>` with no explicit permission rows (only the implicit ones), synced; a user holding only that role, with a password set. | The user's token gets 403 `FORBIDDEN` on `GET /users` and 200 on `GET /me/menus`. |

**Exit check:**
1.  `docker compose --profile app down -v`, then `docker compose --profile app up -d --build --wait`.
2.  `./mvnw -Pe2e verify` runs and passes every scenario class.
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
    endpoint", PRD §11) is covered by the service and web tests listed per phase.
    `MutatingEndpointsAuditGuardTest` fails the build when a non-GET mapping is missing from its
    list, so a new mutating endpoint forces a review. It does not verify that an audit test
    exists.
*   **CI:** no pipeline file is delivered (DEV-2). Running the commands above locally stands in
    for it.

--------------------------------------------------------------------------------

*Drafted with EGM*

