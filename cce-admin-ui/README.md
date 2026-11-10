# cce-admin-ui

The CCE v2 admin console: a React single-page application that platform administrators use to
manage users, roles, role menus, role API permissions and the menus catalog. It holds no data of
its own; every read and write goes to `cce-admin-service` under `/admin/v1` with a Keycloak access
token.

- Design: [CCE v2 Admin Console](../docs/designs/cce-admin-ui.md)
- API contract and local environment: [cce-admin-service design](../docs/designs/cce-admin-service.md)

## Prerequisites

- Node in the `engines` range `>=20.19.0 <23`
- npm 10
- Docker

## Setup

```sh
npm ci
cp .env.example .env
```

`.env` is git-ignored. `.env.example` holds the four local `VITE_*` values, none of which is a
secret.

## Scripts

| Script            | What it does                                                       |
| ----------------- | ------------------------------------------------------------------ |
| `npm run dev`     | Vite dev server on `http://localhost:3002`                         |
| `npm run build`   | Type-check (`tsc -b`) and production build to `dist/`              |
| `npm test`        | Vitest in watch mode                                               |
| `npm run test:ci` | Vitest once, with coverage thresholds                              |
| `npm run lint`    | ESLint and a Prettier check                                        |
| `npm run e2e`     | Playwright browser tests against the real service (not part of CI) |

## Running against the real service

1. Start the admin-service stack and the service as in admin-service
   [§9.3](../docs/designs/cce-admin-service.md#93-values-commands-and-reset).
2. Run `npm run dev`.
3. Open `http://localhost:3002`. The dev server forwards `/admin/v1/*` to `localhost:8085`; sign-in
   goes to the M0 Keycloak at `http://localhost:8180/auth`.

## Browser tests

1. Once: `npx playwright install chromium`.
2. Start the admin-service stack and the service (admin-service §9.3).
3. Export the test administrator's credentials, with the values from the admin-service `.env`:

   ```sh
   export CCE_TEST_ADMIN_USERNAME=... CCE_TEST_ADMIN_PASSWORD=...
   ```

   They are read only from the environment; never add them to `.env` or `.env.example`.

4. Run `npm run e2e`.

Each run leaves about seven `E2E_*` roles behind. When the global setup asks for it, reset the
local stack with the admin-service's `docker compose --profile app down -v`.

## Docker

```sh
# Production image (the realm is required; the build fails without it)
docker build --build-arg VITE_KEYCLOAK_REALM=... -t cce-admin-ui .

# Lint, tests and the bundle check on the reference Node 20 image
docker build --target verify --build-arg VITE_KEYCLOAK_REALM=... .

# Run; GATEWAY_UPSTREAM is required and points at gateway-service
docker run -e GATEWAY_UPSTREAM=... -p 3002:3002 cce-admin-ui
```

When the image was built with a `VITE_KEYCLOAK_URL` on another origin, also pass
`-e KEYCLOAK_ORIGIN=<that origin>`, or the Content-Security-Policy blocks sign-in.

**Origin rule (UI-AD-16).** The console is served at `/` and needs an origin that also routes
`/auth` to Keycloak, or an image built with `VITE_KEYCLOAK_URL` set. Caddy does not route `/auth`.

## Import rules

`npm run lint` enforces four import rules:

1. `src/pages/**` and `src/components/**` may not import values from `src/api/**`. They may
   import types (`import type`) from `src/api/contract.ts` only. Runtime constants they need are
   re-exported from `src/schemas/rules.ts`.
2. `src/components/**` may not import `src/hooks/**` or `@tanstack/react-query`.
3. Only `src/auth/**` and test files may import `keycloak-js`.
4. Only test files, `src/test/**`, `e2e/**` and `playwright.config.ts` may import `msw` or
   `@playwright/test`.
