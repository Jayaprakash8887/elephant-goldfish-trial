# Developer Setup

Same tooling as `cce-insights-ui` — see its
[developer-setup.md](https://github.com/openphc/cce-insights-ui/blob/demo-rw/docs/developer-setup.md)
for anything not specific to this app (editor setup, general Vite/Vitest usage). This
document covers only what differs: env vars and scripts specific to `cce-admin-ui`.

## Prerequisites

| Tool | Version |
|---|---|
| Node.js | 24 (CI standard); ≥20 compatible locally |
| npm | 10+ |

## Setup

```bash
npm install
cp .env.example .env.local   # fill in the values below
npm run dev                  # Vite dev server, http://localhost:3002
```

## Environment variables

Build-time only (Vite inlines `VITE_*` into the bundle — see
[api-integration.md](api-integration.md) and
[deployment-guide.md](deployment-guide.md#env-var-injection) for how this plays out in
Docker).

| Variable | Default | Notes |
|---|---|---|
| `VITE_KEYCLOAK_URL` | `${window.location.origin}/auth` | Keycloak base URL |
| `VITE_KEYCLOAK_REALM` | — (required) | Must match the realm `cce-admin-service` and `cce-insights-ui` use — see [cce-admin-service/security-model.md §7](../../cce-admin-service/docs/security-model.md#7-secrets) |
| `VITE_KEYCLOAK_CLIENT_ID` | `cce-admin-ui` | Own client, distinct from `cce-insights-ui`'s — see [api-integration.md §1](api-integration.md#1-authentication) |
| `VITE_API_BASE_URL` | `""` (relative) | Prefixed to `/admin/v1/*` calls — see [api-integration.md §2](api-integration.md#2-http-client) |

Unlike `cce-insights-ui`, there is no `VITE_AUTH_ENABLED` flag — see
[api-integration.md §1](api-integration.md#1-authentication) for why auth is always on
here.

## Scripts

| Script | Purpose |
|---|---|
| `npm run dev` | Vite dev server, port `3002` |
| `npm run build` | `tsc -b` type-check, then Vite production build |
| `npm run test` | Vitest, watch mode |
| `npm run test:ci` | Vitest, single run |
| `npm run lint` | ESLint |

## Testing

Same libraries as `cce-insights-ui`: Vitest + `@testing-library/react` +
`@testing-library/jest-dom`, `jsdom` environment, MSW for mocking
`cce-admin-service`'s API in component/hook tests. Form-heavy screens (User/Role
create-edit) are tested by simulating input and submit via Testing Library rather than
asserting on internal component state.
