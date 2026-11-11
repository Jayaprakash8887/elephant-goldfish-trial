# Security model

In shared environments, callers reach the service through gateway-service, which checks the token
and the caller's roles against `api_permissions` before forwarding `/admin/**`. The service does
not trust that: it validates the token itself, requires an active local user, and runs its own
permission check against the same table. Locally there is no gateway; callers hit port 8085
directly.

## Authentication

Spring Security OAuth2 resource server, stateless, CSRF disabled
([`SecurityConfig.java:46-72`](../src/main/java/org/cce/admin/security/SecurityConfig.java#L46-L72)).

| Check | How | Config |
|---|---|---|
| Signature | `NimbusJwtDecoder.withJwkSetUri(...)` | `JWT_JWK_SET_URI` (default `${JWT_ISSUER}/protocol/openid-connect/certs`) |
| Expiry and not-before | `JwtTimestampValidator` with 30 s clock skew (`SecurityConfig.CLOCK_SKEW`) | fixed |
| Issuer | `JwtIssuerValidator` | `JWT_ISSUER` |
| Audience | `JwtClaimValidator` on `aud`: must contain the value | `JWT_AUDIENCE` (locally `gateway-service`) |

Validators: [`SecurityConfig.jwtValidator`, lines 91-97](../src/main/java/org/cce/admin/security/SecurityConfig.java#L91-L97).
Tests use the same validators with a test decoder.

Any failure, or no token on a protected path, gives 401 `UNAUTHENTICATED`
(`"A valid bearer token is required."`). [`SecurityErrorWriter.commence`](../src/main/java/org/cce/admin/security/SecurityErrorWriter.java#L33-L39)
first lets Spring's `BearerTokenAuthenticationEntryPoint` set the status and the
`WWW-Authenticate` header (which carries the decoder's error description), then writes the JSON
envelope.

`JWT_ISSUER`, `JWT_AUDIENCE` and `JWT_ROLES_CLAIM_PATH` must hold the same values as the
gateway's configuration for the realm. Locally the issuer is
`http://localhost:8180/auth/realms/cce` in every token, because Keycloak runs with
`KC_HOSTNAME=http://localhost:8180/auth`; the container fetches keys from the in-network JWKS URL.

## Current user

[`CurrentUserFilter`](../src/main/java/org/cce/admin/security/CurrentUserFilter.java#L45-L98),
after `BearerTokenAuthenticationFilter`. It runs only when the authentication is a
`JwtAuthenticationToken`.

1. Parse `sub` as a UUID. If it is not one, the caller has no local user (403, no query).
2. `users` by `keycloak_id = sub`. No row, or `status` not `ACTIVE` → 403 `FORBIDDEN`
   (`"Your account has no active user in the admin service."`).
3. If the token has `auth_time`, run
   `UPDATE users SET last_login_at = :t WHERE id = :id AND (last_login_at IS NULL OR last_login_at < :t)`
   in its own transaction. It does not touch `updated_at` and writes no audit row.
4. Read role names from the claim at `JWT_ROLES_CLAIM_PATH` (default `realm_access.roles`), split
   on `.` ([`RolesClaimReader`](../src/main/java/org/cce/admin/security/RolesClaimReader.java)).
   A missing claim, or one that is not a list of strings, means no roles; it never fails.
5. Store [`CurrentUser`](../src/main/java/org/cce/admin/security/CurrentUser.java)
   `(id, username, keycloakId, roleNames)` as a request attribute.
   `CurrentUser.current()` reads it anywhere in the request thread.

Any exception inside the filter (for example the database is down) gives 500 `INTERNAL_ERROR`.

Differs from the design (§3 step 4): a `sub` that is not a UUID is refused without a lookup. The
design does not mention this case.

## Authorization

### The service's own permission check

[`PermissionCheckFilter`](../src/main/java/org/cce/admin/security/PermissionCheckFilter.java#L44-L80),
after `CurrentUserFilter`. Skipped entirely when `PERMISSION_CHECK_ENABLED=false` (startup then
logs a warning that only the gateway enforces permissions).

1. No role names in the token → 403, no query.
2. One query: `api_permissions` rows where `permission_name IN (token roles)` and
   `http_method = request method`
   ([`ApiPermissionRepository.findByPermissionNameInAndHttpMethod`](../src/main/java/org/cce/admin/repository/ApiPermissionRepository.java#L12-L13)).
3. Match `request.getRequestURI()` against each row's `uri_pattern` with a default
   `AntPathMatcher` (`*` within a segment, `**` across segments, case-sensitive). `/admin/v1/**`
   also matches `/admin/v1`.
4. No match → 403 `FORBIDDEN` with `"Your roles do not allow {METHOD} {path}."`.

There is no cache: a saved permission takes effect at the service on the next request. An
exception in the filter gives 500.

Differs from the design (§3 step 5): the design matches "the URI path without the query string";
the code uses `getRequestURI()`, which is not percent-decoded and includes any servlet context
path. The two differ for encoded characters.

### What the check means in practice

- **Roles come from the token.** Neither filter reads `user_roles`. After an administrator removes
  a role, the change reaches Keycloak at once, but a token issued earlier keeps the role until it
  expires (300 s in the local realm). `GET /me` and `GET /me/menus` are also built from token roles.
- **`SUPER_ADMIN`** holds `GET/POST/PUT/PATCH/DELETE /admin/v1/**` from the seed, so it can call
  every endpoint.
- **Console roles** (any role created through the API) get the implicit rows
  `GET /admin/v1/me` and `GET /admin/v1/me/menus`, so a console user can read their own profile and
  sidebar. Anything more needs explicit rows. There is no prefix rule: an explicit row can grant
  any path, including `/admin/v1/**`, so whoever can edit permissions can create another
  administrator role.
- **Gateway vs service.** The gateway does its own check, from the same table, for the same token
  roles; that check is outside this repo. The service's check is the only one that runs locally,
  and `HardControlE2E` shows a console user getting 403 on `GET /users` and 200 on
  `GET /me/menus`.

## System roles and `is_assignable`

| Role | `is_system` | `is_assignable` | Purpose |
|---|---|---|---|
| `SUPER_ADMIN` | true | true | Platform administrator. |
| `CCE_ADMIN_SERVICE` | true | false | Held by the `cce-admin-service` service-account user; grants `POST /api/permissions/sync-to-keycloak` at the gateway. |

- A system role cannot be edited, have its menus or permissions changed, or be deleted: 409
  `SYSTEM_ROLE_IMMUTABLE` ([`RoleService.requireNotSystem`](../src/main/java/org/cce/admin/service/RoleService.java#L240-L245)).
  `POST /roles/{id}/sync` is allowed.
- A role with `is_assignable = false` cannot be given to a user: 409 `ROLE_NOT_ASSIGNABLE`. Only
  roles being added are checked.
- Roles created through the API are always `is_system = false`, `is_assignable = true`.
- A role cannot be assigned until its Keycloak realm role exists: 409 `ROLE_NOT_SYNCED`. The
  service never creates realm role definitions; the gateway sync does.

## Last-SUPER_ADMIN protection

Two changes can lower the number of `ACTIVE` users holding `SUPER_ADMIN`:

- `PATCH /users/{id}/status` moving an `ACTIVE` `SUPER_ADMIN` holder to `INACTIVE` or `LOCKED`;
- `PUT /users/{id}/roles` removing `SUPER_ADMIN` from an `ACTIVE` user.

In both, inside the flow's transaction and after the `users` row lock,
[`UserChangeLocks.requireAnotherActiveAdmin`](../src/main/java/org/cce/admin/service/UserChangeLocks.java#L42-L50)
takes `SELECT … FOR UPDATE` on the `SUPER_ADMIN` row of `roles`, then counts other `ACTIVE`
holders. Zero → 409 `LAST_SYSTEM_ADMIN`. Because the count runs after the lock, two
administrators cannot remove each other at the same time (`LastSystemAdminConcurrencyTest`).

Other protections: `SUPER_ADMIN` is a system role, so it cannot be deleted or edited; no endpoint
deletes a user; the gateway stub never deletes the `SUPER_ADMIN` realm role. If no active
`SUPER_ADMIN` exists at startup, `BootstrapAdminRunner` links the Keycloak user named in
`BOOTSTRAP_ADMIN_USERNAME` (see [flow-diagrams.md](flow-diagrams.md#bootstrap-of-the-first-super_admin)).

The check counts local rows only. A `SUPER_ADMIN` realm role mapping held in Keycloak by a user
with no local row does not count, and cannot call the service (no local user → 403).

## Keycloak service account

The service authenticates to the Keycloak Admin API with the client-credentials grant of
`cce-admin-service` ([`KeycloakClientConfig`](../src/main/java/org/cce/admin/config/KeycloakClientConfig.java)).
In the local realm that client's service account has the `realm-management` roles
`manage-users`, `view-users`, `query-users` and `view-realm`, and the realm role
`CCE_ADMIN_SERVICE`. It cannot create or delete realm roles, but it can change any user in the
realm, including any realm role mapping. The client secret is therefore a high-value credential.

The same token (from the admin client's token manager) is sent as the bearer token to the gateway
sync endpoint.

## Audit

[`AuditWriter`](../src/main/java/org/cce/admin/audit/AuditWriter.java) writes one `audit_log` row
per successful change, per gateway sync attempt, and per partial failure (rules in
[flow-diagrams.md](flow-diagrams.md#audit-writing); columns in
[data-dictionary.md](data-dictionary.md#audit_log-append-only)).

- **Actor** ([`ActorProvider`](../src/main/java/org/cce/admin/audit/ActorProvider.java)): the
  request's `CurrentUser` (`actor_user_id`, `actor_keycloak_id`, `actor_username`), or
  `system:bootstrap` with null ids when there is no request (the bootstrapper, and service tests).
- **Content**: before/after in the API shapes; a `failure` JSON on failure rows with the error as
  `exception class: message`. Rows hold usernames, emails and IP addresses, never tokens,
  passwords or secrets.
- **IP address**: `HttpServletRequest.getRemoteAddr()` after Tomcat's own forwarded-header
  handling (`server.forward-headers-strategy: native`). Tomcat replaces the remote address from
  `X-Forwarded-For` only when the direct peer matches `TRUSTED_PROXIES`
  (`server.tomcat.remoteip.internal-proxies`). The default `(?!)` matches nothing, so by default
  the audited IP is the direct caller (in production, the gateway's address unless
  `TRUSTED_PROXIES` is set to it). Null when there is no request.
- **Append-only**: the trigger `trg_audit_log_append_only` rejects `UPDATE` and `DELETE`, and the
  entity is `@Immutable`. There is no endpoint that reads the audit log; operators query the
  table.

Not in the design (§8): the `@Immutable` annotation. Its comment says it stops Hibernate's dirty
check from issuing an `UPDATE` when a re-read JSON node compares unequal
([`AuditLogEntity.java:18-25`](../src/main/java/org/cce/admin/entity/AuditLogEntity.java#L18-L25)).

## Secrets and configuration

- Every credential and environment URL comes from an environment variable or the git-ignored
  `.env`, imported with `spring.config.import: optional:file:.env[.properties]` (not in profile
  `test`). `.env.example` holds placeholders only.
- Required `admin.*` values fail startup with `set <VARIABLE>`: `JWT_ISSUER`, `JWT_AUDIENCE`,
  `KEYCLOAK_SERVER_URL`, `KEYCLOAK_REALM`, `KEYCLOAK_ADMIN_CLIENT_SECRET`, `GATEWAY_BASE_URL`
  ([`AdminProperties`](../src/main/java/org/cce/admin/config/AdminProperties.java)).
  `DATABASE_*` are not checked this way (see [architecture-overview.md](architecture-overview.md#configuration)).
- The realm import `keycloak/realm-cce.json` holds `${…}` placeholders for client secrets and the
  test administrator; Keycloak fills them from the container environment.
- The service never sees a user password: new users and resets get a Keycloak `UPDATE_PASSWORD`
  email.
- The runtime image runs as a non-root `app` user ([`Dockerfile`](../Dockerfile)).

## Public surface

| Path | Auth | Notes |
|---|---|---|
| `GET /admin/v1/health` | none | Only `GET`. Returns DB status. |
| `/admin/v1/api-docs/**` | none | OpenAPI JSON, when `SPRINGDOC_ENABLED=true` (default). |
| `/admin/v1/swagger-ui/**` | none | Swagger UI, same switch. |
| Error dispatches (`/error`) | permitted | So container error pages are not blocked. |
| everything else | token + active local user + permission row | |

A custom `BearerTokenResolver` returns no token for public paths, so an `Authorization` header
sent to them is not decoded: `GET /health` with a bad token returns 200
([`SecurityConfig.java:103-106`](../src/main/java/org/cce/admin/security/SecurityConfig.java#L103-L106)).

Not in the design (§3 step 1): the design says public paths "skip steps 2 to 5"; ignoring the
header is how the code does it.

Locally, all compose ports are bound to `127.0.0.1`. The gateway stub accepts any bearer value
without validating it, and its `PUT /_stub/mode` needs no token; it is a local-only stand-in.

## Things to know

- The health check always answers HTTP 200 with `status: "UP"`, even when `database` is `DOWN`.
  The image's `HEALTHCHECK` (`wget`) therefore reports the container healthy while the database
  is unreachable.
- `hasRealmRoleMembers` (role delete) checks direct user members of the realm role only
  (`getUserMembers(0, 1)`); a user who gets the role through a group or a composite role is not
  seen. Unclear from the code whether that matters for this realm.
