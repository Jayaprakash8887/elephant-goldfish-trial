# cce-admin-service and its local environment (CCE v2 admin module)

*   **Author:** jayaprakash8887
*   **Status:** Draft
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
    writes an audit row with the Keycloak user id. Nothing is retried automatically. Check: a
    service test makes the database insert fail after Keycloak succeeds and asserts both the 502
    and the audit row (PRD §10, B3 notes).
*   **G6. Roles, menus and permissions managed through the API, not SQL (B-40 to B-44).** Role,
    menu and permission changes go through the service, follow replace semantics where the PRD
    says so, and trigger the gateway sync after permission changes. A failed sync returns 502 and
    keeps the database change.
*   **G7. Role-aware menu tree (B-20, B-21).** `GET /me/menus` returns the union of the active
    menus of all the caller's roles, with children nested under their parents and ordered by
    `sort_order`. Check: two roles with overlapping menus produce the merged tree (B5 demo).
*   **G8. Every change is audited, including partial ones (B-50, B-32).** Every successful call
    to a mutating endpoint writes exactly one append-only `audit_log` row with actor, action,
    entity type and id, before and after JSON, and the caller's IP address. A call that fails
    part-way, after Keycloak or the database has already changed, and returns 502
    `KEYCLOAK_SYNC_FAILED` also writes exactly one audit row. That row records which steps
    completed, which step failed, and the Keycloak user id when one exists, so that an operator
    can reconcile by hand. A call that fails before anything changes (for example 400, 404 or
    409) writes no row. The Technical Plan decides how the result of the gateway sync after a
    permission change is recorded, so that the one-row-per-mutation rule still holds and can be
    tested. Check: a test asserts the number of rows and their contents for every mutating
    endpoint, on both the success path and the 502 path.
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
| DEV-3 | §6: "PostgreSQL schema `admin_service`". | `admin_service` is read as the *database* name. All seven tables live in its `public` schema (decision AD-01). | PRD §5 and the gateway doc name a database `admin_service`, and the gateway connects without naming a schema, so it reads `public`. Putting the tables in a schema `admin_service` would need a configuration change in a deployment this team does not own. |
| DEV-2 | §11 definition of done: "`npm run build` / `npm run lint` / `mvn verify` pass in CI." | No CI pipeline file is delivered. `mvn verify` and the Docker image build are run locally instead, and their results stand in for CI. | Jenkins is not available on this machine, and the team's Jenkins conventions are not among the inputs (see Non-Goals). |

## Author Decisions in This Design

Decisions the author took at this design's drafting gates. Decisions that the console design
also needs are copied to [author-decisions.md](../../docs/designs/author-decisions.md) under a
`D-` ID.

| ID | Question | Decision | Why |
|---|---|---|---|
| AD-01 | OQ-1: where the seven tables live. | All seven tables live in the `public` schema of the database `admin_service`. Recorded as DEV-3. | The gateway needs no change. Putting the tables in a schema `admin_service` would make the module depend on a configuration change in the gateway's deployment, which this team does not own, and if that change were missing the gateway would load no permissions and block every call. Splitting the tables across two schemas would only serve PRD §6's wording, which §5 and the gateway doc contradict. The `admin_service` database belongs to this module, so name clashes in `public` are unlikely. |
| AD-02 | OQ-2: who creates `api_permissions`. | This service's Flyway V1 migration creates the table, its two indexes and its unique constraint only if they are missing, using the gateway's exact DDL. A later migration then checks the table's shape and stops the migration if anything differs. The check covers the column names, types, nullability and defaults; the unique constraint on (`permission_name`, `http_method`, `uri_pattern`); and the two indexes. Rows already in the table that no role owns, such as the gateway doc's samples, are left in place. The bootstrap steps for a new environment include a read-only query that lists them for an operator to review (risk GW-5). Flyway runs with `baseline-on-migrate=true` and `baseline-version=0`. Otherwise it refuses a non-empty `public` schema that has no history table, which is exactly the state an environment is in after the gateway's script ran. With version 0, V1 and every later migration still run. Details are in section 6. | B-01 holds on an empty database. An environment where the gateway's script already ran keeps working, and no manual data migration is needed. Making the gateway's script the only creator would make every new environment depend on a manual step. Having Flyway own the table outright would mean dropping and recreating it in existing environments, which opens a window in which the gateway loads no permissions. Replace semantics and the duplicate-row error both depend on the unique constraint, so the check covers it too. |
| AD-03 | OQ-3: how the three routes outside the sidebar are seeded. | All 12 cce-insights-ui routes are seeded. Practitioners, Intelligence and Exports are active top-level `NAV` menus, like the other seven sidebar links. `sort_order` puts today's 7 sidebar links first, in today's order (Dashboard, Facilities, Compliance, Deviations, Patients, Events, Ingestion), then Practitioners, Intelligence and Exports. The two `DETAIL` menus sit under Compliance and Patients. The seed grants no menu to any role, `SUPER_ADMIN` included. | Under per-role menus, a menu only controls what a role sees (PRD §4, soft control), and the one rule a console needs is "granted means shown". Seeding the three as inactive would give `is_active` two meanings, "retired" and "routed but hidden". A `ROUTE` type is not defined in PRD §4, and every console would have to filter on it. Keeping these pages out of the sidebar was a decision for the old sidebar, which was the same for every user. Now an administrator decides it per role. `SUPER_ADMIN` is the admin console's role, and console roles are set up through the API. |

## Open Questions

### For the author (each one is decided at a drafting gate)

The console design's requirements on this design (AS-REQ-01 to AS-REQ-24, in
[the admin-ui draft](../admin-ui/design_draft.md)) are answered in the Technical Plan,
section 4. The AS-REQs that need an author decision are listed with the open question that
covers them.

*   **OQ-4. Whether `INACTIVE` can be undone.** PRD §2 says deactivation is terminal. B-34 and S5
    describe re-activation restoring access. Appendix A also lists a third status, `LOCKED`, which
    no requirement describes.
*   **OQ-5. Local stand-in for the gateway sync.** If the stand-in does nothing, no realm role is
    created for a new role, and assigning that role to a user (S2, S3, S4) fails locally. S6 also
    needs a sync failure on demand.
*   **OQ-6. How the service authenticates to the gateway's sync endpoint.**
*   **OQ-7. Bootstrap administrator in each environment** (PRD §12). The first `SUPER_ADMIN` must
    exist in Keycloak and in the local `users` table, with `api_permissions` rows for
    `/admin/v1/**`.
*   **OQ-8. How `last_login_at` is updated** (PRD §12 says the mechanism is not specified).
*   **OQ-9. The `/me/menus` permission for every console role** (PRD §12). Every role used in
    cce-insights-ui needs a permission row for `GET /admin/v1/me/menus`, or the console cannot
    load its sidebar.
*   **OQ-10. Whether the service checks `api_permissions` itself.** The gateway enforces role
    permissions. B-10 to B-12 only require the service to check the JWT and the local user's
    standing. Without a check of its own, any `ACTIVE` user with any role can call every admin
    endpoint when the service is reached directly, which is how it is reached locally.
*   **OQ-11. How the gateway sync result after a permission save is recorded** (G8, B-42, B-50).
*   **OQ-12. What `SYSTEM_ROLE_IMMUTABLE` blocks** (AS-REQ-12): display fields, the menu set and
    the permission set of a system role, as well as delete.
*   **OQ-13. How a new user gets a password** (AS-REQ-05, B-36): whether user creation sends a
    Keycloak action email, or the administrator triggers a reset separately.
*   **OQ-14. The type of `facility_scope`** (AS-REQ-08): one string or a list, and how the
    filter matches.
*   **OQ-15. Assigning a role that has not been synced** (AS-REQ-10, PRD §9 Ordering): whether
    the role carries its last sync outcome, and what `POST /users` and `PUT /users/:id/roles`
    return when the role's realm role does not exist in Keycloak.

| Open question | Console requirements it answers |
|---|---|
| OQ-4 | AS-REQ-07 |
| OQ-5 | AS-REQ-23 (making the local sync fail, then succeed, without a restart) |
| OQ-7 | AS-REQ-22 (test administrator), AS-REQ-24 (how the test administrator's credentials reach tests) |
| None (M0, section 9) | AS-REQ-22 (Keycloak URL, realm, audience), AS-REQ-24 (resetting the local database and realm) |
| OQ-8 | AS-REQ-04 (last login) |
| OQ-12 | AS-REQ-12 |
| OQ-13 | AS-REQ-05 |
| OQ-14 | AS-REQ-08 |
| OQ-15 | AS-REQ-10 |

### For the gateway-service owners (assumptions are configurable, per G10)

*   **GW-1. Sync endpoint contract.** What does `POST /api/permissions/sync-to-keycloak` expect as
    its request (body, parameters) and return as its response? Is it a full sync of every
    `permission_name`, or can it be limited to one role? Which status codes mean success?
*   **GW-2. When permission changes take effect.** The gateway doc says new permissions are loaded
    only at startup and the gateway must be restarted. Does the sync endpoint also reload the
    gateway's in-memory permissions? If not, a permission saved through the admin console has no
    effect on the gateway until it restarts.
*   **GW-3. URI pattern syntax and the path that is matched.** The gateway doc's examples use
    single-segment `*` patterns on `/admin/users/*`. PRD §12 uses `/admin/v1/**`. Which wildcard
    syntax does the gateway support (for example Spring `AntPathMatcher` or `PathPattern`), and is
    the pattern matched against the incoming path, `/admin/v1/...`, before the request is proxied?
*   **GW-4. Removing realm roles.** PRD §4 says the gateway creates *and deletes* realm role
    definitions. Does a sync delete a realm role whose `permission_name` no longer has any rows in
    `api_permissions` (for example, after a role is deleted here)?
*   **GW-5. Rows that no role owns (risk, from AD-02).** In an environment where the gateway's
    own script already ran, `api_permissions` can hold rows whose `permission_name` matches no
    row in `roles`. The gateway doc's samples `USER_READ` and `USER_CREATE_UPDATE` are examples.
    This service never shows or changes those rows, but the gateway keeps enforcing them. A
    Keycloak user who holds such a realm role keeps those API rights, and no admin screen shows
    it. Are any of these rows still needed? Can they be deleted, or turned into roles managed
    here? Until the owners answer, the bootstrap steps (section 9) include a read-only query that
    lists the unowned `permission_name` values for an operator to review.

--------------------------------------------------------------------------------

## Technical Plan (Architecture)

> **Drafting status.** Sections 1 to 3 are written. Sections 4 to 9 are written once the open
> author questions they depend on are decided. Text marked *(pending OQ-n)* depends on that
> question.

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
5.  **Permission check.** *(pending OQ-10)*
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
*   **Warning.** `{ "code": string, "message": string, "menuId": uuid, "parentMenuId": uuid }`.
    The only warning code is `DETAIL_WITHOUT_PARENT` (section 4.6), so the menu fields are always
    present.
*   **Delete responses.** `DELETE /roles/:id` and `DELETE /menus/:id` return 204 with no body.
    This is the only success response without the envelope.
*   **Error envelope.** Every error response body is
    `{ "error": { "code": string, "message": string, "details"?: [<FieldError>], "partialState"?: <PartialState> } }`.
    *   `message` is an English sentence for people. Clients branch on `code`, never on `message`.
    *   `details` appears only with `VALIDATION_ERROR`. Each `FieldError` is
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
| 403 | `FORBIDDEN` | No local user for `sub`, local user not `ACTIVE`, or *(pending OQ-10)* the caller's roles do not allow the call. |
| 404 | `USER_NOT_FOUND` | The user id in the path does not exist. |
| 404 | `ROLE_NOT_FOUND` | The role id in the path does not exist. |
| 404 | `MENU_NOT_FOUND` | The menu id in the path does not exist. |
| 404 | `NOT_FOUND` *(added)* | No endpoint matches the path. |
| 405 | `METHOD_NOT_ALLOWED` *(added)* | The path exists but not for this HTTP method. |
| 409 | `EMAIL_IN_USE` | Another user, locally or in Keycloak, has this email. |
| 409 | `USERNAME_IN_USE` *(added)* | Another user, locally or in Keycloak, has this username. Needed because username and email are separate fields (section 4.5). |
| 409 | `DUPLICATE_ROLE_NAME` | A role with this name exists. |
| 409 | `DUPLICATE_MENU_KEY` | A menu with this key exists. |
| 409 | `ROLE_IN_USE` | Delete of a role that is assigned to at least one user, whatever the user's status. |
| 409 | `SYSTEM_ROLE_IMMUTABLE` | A blocked change to an `is_system` role *(scope pending OQ-12)*. |
| 409 | `MENU_HAS_CHILDREN` *(added)* | Delete of a menu that still has child menus. |
| 502 | `KEYCLOAK_SYNC_FAILED` | A call to Keycloak or to the gateway sync failed after the request had already changed something, or the gateway sync failed. Carries `partialState`. |
| 500 | `INTERNAL_ERROR` *(added)* | Any other failure. The message is generic, and the stack trace is only logged. |

*(Codes for an invalid status change (OQ-4) and for assigning a role that has not been synced
(OQ-15) are added here once those questions are decided.)*

**`PartialState` (AS-REQ-06, AS-REQ-18).**
`{ "failedStep": string, "completedSteps": [string], "keycloakUserId": uuid | null, "auditLogId": uuid }`.
The step names for each flow are listed with the flow in section 5. `auditLogId` is the id of
the audit row that records the partial state for manual reconciliation (G8).
`keycloakUserId` is set when a Keycloak user exists that the local database may not reflect.

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

*(Sections 4.4 onwards (resource shapes and every endpoint), 5 (flows and their order of
operations), 6 (data dictionary and seed), 7 (gateway sync client), 8 (audit), 9 (local
environment, M0) and 10 (testing and E2E) follow as their open questions are decided.)*
