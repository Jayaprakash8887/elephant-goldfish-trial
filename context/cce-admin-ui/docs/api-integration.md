# API Integration

How `cce-admin-ui` authenticates and talks to `cce-admin-service`. Endpoint contracts,
request/response shapes, and error codes are not repeated here — see
[cce-admin-service/api-reference.md](../../cce-admin-service/docs/api-reference.md); this
document covers only the frontend-side wiring.

## 1. Authentication

Same library and flow as `cce-insights-ui` (`keycloak-js`, Authorization Code + PKCE),
against a **separate Keycloak client**:

| Setting | `cce-insights-ui` | `cce-admin-ui` |
|---|---|---|
| Keycloak client id | `cce-insights-ui` | `cce-admin-ui` |
| Realm | Platform realm (env-configured; see [cce-admin-service/security-model.md §7](../../cce-admin-service/docs/security-model.md#7-secrets)) | Same realm |
| `onLoad` | `login-required` | Same |
| PKCE method | `S256` | Same |
| Token refresh | `updateToken(70)` on a 60s interval | Same |

A separate client id (rather than reusing `cce-insights-ui`'s) keeps the two consoles'
redirect URIs, session lifetimes, and Keycloak client-level role mappings independent —
they're administered by different people for different purposes even though they share
a realm.

`initAuth()` runs before the app renders, identical in shape to `cce-insights-ui`'s
implementation. Once authenticated, the live access token is read via a `getToken()`
accessor and attached as `Authorization: Bearer <token>` on every request — there is no
`VITE_AUTH_ENABLED`-style bypass flag here (unlike `cce-insights-ui`, this app has no
useful gateway-less/no-auth mode, since every screen mutates data that must be
attributable to a real signed-in admin for `audit_log`).

## 2. HTTP client

Same pattern as `cce-insights-ui`: a thin typed wrapper over native `fetch` in
`src/api/client.ts`, not a generated client or axios — this app's endpoint count is
small enough that hand-written typed wrappers per resource (`api/users.ts`,
`api/roles.ts`, `api/menus.ts`, `api/me.ts`) stay easier to read than generated code.

- Base URL: `${VITE_API_BASE_URL}/admin/v1` (see [developer-setup.md](developer-setup.md)
  for the env var; the `/admin/v1` path matches `cce-admin-service`'s base path as
  reached through `gateway-service`'s `/admin/**` route, per
  [cce-admin-service/api-reference.md](../../cce-admin-service/docs/api-reference.md)).
- Every request sends `Authorization: Bearer <token>` and `Accept: application/json`.
- Response envelope: unwraps `{ data, pagination? }` on success; on error, throws a
  typed `ApiError` carrying `.status` and the backend's `{ error: { code, message } }`
  body, so calling code can branch on `error.code` (e.g.
  `DUPLICATE_ROLE_NAME`, `ROLE_IN_USE`, `KEYCLOAK_SYNC_FAILED` — full list in
  [cce-admin-service/api-reference.md §Common error codes](../../cce-admin-service/docs/api-reference.md#common-error-codes)).
- Mutating hooks (`PUT /roles/:id/menus`, `PUT /users/:id/roles`, etc.) always send the
  **full replacement set**, matching the backend's replace-not-patch semantics for
  those endpoints — the frontend diffs against the last-fetched state only to decide
  whether the Save button is enabled, not to compute a partial payload.

## 3. Gateway routing

Requests go to `gateway-service`, which proxies `/admin/**` to `cce-admin-service` after
validating the JWT and matching the caller's role against `api_permissions` — this app
never calls `cce-admin-service` directly in any non-local environment, same rule as
`cce-insights-ui` (see
[cce-admin-service/architecture-overview.md §3](../../cce-admin-service/docs/architecture-overview.md#3-system-context)).
A `403 FORBIDDEN` here means either the gateway or `cce-admin-service` itself rejected
the call because the signed-in admin's role has no matching `api_permissions` row — the
UI shows this inline on the screen that made the call rather than a global error page,
since it's a per-action authorization gap, not an app-level failure.

## 4. Local development

Same three patterns `cce-insights-ui` documents, applied to `cce-admin-service` instead
of the insights service:

1. **Vite dev proxy** (default for local dev): `/admin/v1/*` proxied to a local
   `cce-admin-service` instance, avoiding CORS.
2. **Direct CORS**: `cce-admin-service` allows this app's dev origin explicitly.
3. **Caddy reverse proxy** (production, see [deployment-guide.md](deployment-guide.md)):
   same-origin, no CORS involved.
