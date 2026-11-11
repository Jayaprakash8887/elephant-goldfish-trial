# Developer setup

## Prerequisites

| Tool                    | Version                                           | Needed for                                        |
| ----------------------- | ------------------------------------------------- | ------------------------------------------------- |
| Node                    | `>=20.19.0 <23` (`engines` in `package.json`)     | everything                                        |
| npm                     | 10 (per the repo README)                          | installing from `package-lock.json`               |
| Docker with compose     | any recent                                        | the local service stack in `../cce-admin-service` |
| JDK 21                  | only to run the service on the host with `./mvnw` | the service                                       |
| Chromium for Playwright | `npx playwright install chromium` (once)          | `npm run e2e`                                     |

## Install

```sh
npm ci
cp .env.example .env
```

`.env` is git-ignored. Never commit it, and never put credentials in it.

## Environment variables

The app reads exactly four build-time variables, all in [`src/config.ts`](../src/config.ts). A
test fails if any other file reads `import.meta.env`. Vite inlines them at build time, so a
built image cannot change them at runtime.

| Variable                  | Required | Default in code                               | `.env.example` value         | Purpose                                                                                                                        |
| ------------------------- | -------- | --------------------------------------------- | ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| `VITE_KEYCLOAK_REALM`     | yes      | none                                          | `cce`                        | Keycloak realm. If it is missing, the app shows "Configuration error: VITE_KEYCLOAK_REALM is not set." and `vite build` fails. |
| `VITE_KEYCLOAK_URL`       | no       | `${window.location.origin}/auth`              | `http://localhost:8180/auth` | Keycloak base URL, reached directly by the browser                                                                             |
| `VITE_KEYCLOAK_CLIENT_ID` | no       | `cce-admin-ui`                                | `cce-admin-ui`               | Public Keycloak client (PKCE S256)                                                                                             |
| `VITE_API_BASE_URL`       | no       | `''` (same origin); a trailing `/` is removed | empty                        | Prefix for `/admin/v1` calls                                                                                                   |

Values are trimmed, and an empty value counts as unset.

Other variables:

| Variable                                             | Read by                       | Purpose                                                                                                                           |
| ---------------------------------------------------- | ----------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| `CCE_TEST_ADMIN_USERNAME`, `CCE_TEST_ADMIN_PASSWORD` | [`e2e/env.ts`](../e2e/env.ts) | The test administrator for the browser tests. Read only from the process environment. Never add them to `.env` or `.env.example`. |
| `GATEWAY_UPSTREAM`, `KEYCLOAK_ORIGIN`                | Caddy in the Docker image     | Runtime settings of the deployed image. See [deployment-guide.md](deployment-guide.md).                                           |

[`vitest.config.ts`](../vitest.config.ts) sets its own test values for the four `VITE_*`
variables (realm `cce`, API base `http://localhost:3002`). Unit tests do not need `.env`.

## Running against the local stack

The dev server serves the console on `http://localhost:3002` (`strictPort`: it fails if the
port is taken). It proxies `/admin/v1/*` to `http://localhost:8085` with `changeOrigin: false`.
Locally there is no gateway: the console calls the service directly through the proxy. Keycloak
is not proxied. The browser goes to `http://localhost:8180/auth`, because a proxied path would
change the token issuer the service checks.

1. In `../cce-admin-service`, create its local env file and fill in its placeholders (see that
   folder's README):

   ```sh
   cd ../cce-admin-service
   cp .env.example .env      # replace every change-me
   ```

2. Start the infrastructure (Postgres 5433, Keycloak 8180, Mailpit 8025, gateway stand-in 8090):

   ```sh
   docker compose up -d --wait
   ```

3. Start the service on 8085, either on the host or in a container:

   ```sh
   ./mvnw spring-boot:run
   # or
   docker compose --profile app up -d --build --wait
   ```

   Check it with `curl -s localhost:8085/admin/v1/health`.

4. Back in `cce-admin-ui`, run `npm run dev` and open `http://localhost:3002`. You are sent to
   the Keycloak login page.

5. Sign in as the test administrator (`CCE_TEST_ADMIN_USERNAME` / `CCE_TEST_ADMIN_PASSWORD`
   from the service's `.env`). On first start, the service links that Keycloak user as the
   first `SUPER_ADMIN`. A Keycloak user with no active local user gets 403 `FORBIDDEN` on every
   call, which the console shows inline.

Useful local URLs:

| URL                                         | What                                                                                    |
| ------------------------------------------- | --------------------------------------------------------------------------------------- |
| `http://localhost:8025`                     | Mailpit: set-password emails sent by Keycloak                                           |
| `http://localhost:8085/admin/v1/swagger-ui` | The service's Swagger UI                                                                |
| `http://localhost:8090/_stub/mode`          | Gateway stand-in mode. `PUT` `fail` or `ok` (plain text) to make syncs fail or succeed. |

To reset the stack, run `docker compose --profile app down -v` in `../cce-admin-service`. This
deletes both databases. Keycloak re-imports the realm on the next start.

## MSW mocks

MSW is used only in Vitest (Node, `msw/node`). There is no browser mock mode and no
`mockServiceWorker.js`. To run the UI without the service, write a test.

- [`src/test/mocks/handlers.ts`](../src/test/mocks/handlers.ts): one default handler per
  endpoint the console calls. Each matches `*/admin/v1/...`, records the request and returns a
  fixture. They do not implement the service's rules. Unknown ids get the matching 404 code.
- [`src/test/mocks/fixtures.ts`](../src/test/mocks/fixtures.ts): typed data with fixed UUIDs. It
  has the twelve seed menus, the system roles `SUPER_ADMIN` and `CCE_ADMIN_SERVICE`, a non-system
  `FACILITY_ADMIN` with implicit and explicit permission rows, the test administrator, and enough
  users for two pages of 20.
- [`src/test/mocks/requests.ts`](../src/test/mocks/requests.ts): `recordedRequests()` returns
  `{ method, path, query, body }` for each call, so tests assert on exactly what was sent.
- To return something else in one test, override a handler:

  ```ts
  server.use(
    http.put('*/admin/v1/roles/:id/permissions', () =>
      errorResponse(502, 'KEYCLOAK_SYNC_FAILED', 'Sync failed', { partialState }),
    ),
  );
  ```

  `okResponse(data, { status, pagination, warnings })` and
  `errorResponse(status, code, message, { details, partialState })` build the envelopes.

- The server runs with `onUnhandledRequest: 'error'`, so a call without a handler fails the
  test. Handlers, the recorder and the fake auth are reset after each test
  ([`src/test/setup.ts`](../src/test/setup.ts)).
- Render a page through the real routes with `renderRoute('/users')` from
  [`src/test/render.tsx`](../src/test/render.tsx).

## Scripts

| Script            | Command                          | What it does                                                                                                 |
| ----------------- | -------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| `npm run dev`     | `vite`                           | Dev server on port 3002 with the `/admin/v1` proxy                                                           |
| `npm run build`   | `tsc -b && vite build`           | Type-check app and tooling configs, then build to `dist/`. Fails without `VITE_KEYCLOAK_REALM`.              |
| `npm run lint`    | `eslint . && prettier --check .` | ESLint (type-aware, import rules) and the Prettier check                                                     |
| `npm test`        | `vitest`                         | Vitest in watch mode                                                                                         |
| `npm run test:ci` | `vitest run --coverage`          | One run with v8 coverage into `coverage/`. Fails below lines, statements or functions 80 % or branches 70 %. |
| `npm run e2e`     | `playwright test`                | Browser tests against the real local stack                                                                   |

There is no separate `coverage` script. Coverage is part of `test:ci`. Run one file with
`npx vitest run src/pages/__tests__/UsersListPage.test.tsx`.

## Browser tests (E2E)

The tests are in [`e2e/`](../e2e/) and configured in
[`playwright.config.ts`](../playwright.config.ts). They use Chromium only, one worker, no
retries and a 60 s timeout. Playwright starts `npm run dev`, or reuses a dev server already on 3002. The service stack must already be running (steps 1 to 3 above), and `.env` must exist for
the dev server.

```sh
export CCE_TEST_ADMIN_USERNAME=... CCE_TEST_ADMIN_PASSWORD=...   # values from ../cce-admin-service/.env
npm run e2e
```

How the tests get credentials and data:

1. **Global setup** ([`global-setup.ts`](../e2e/global-setup.ts)) checks that
   `http://localhost:8085/admin/v1/health` and `http://localhost:8090/_stub/mode` answer.
   Otherwise it stops and prints the start commands. It sets the stand-in to `ok`, then stops if
   the realm has more than 400 roles, and asks you to reset the stack.
2. **Sign-in** ([`auth.setup.ts`](../e2e/auth.setup.ts)) opens the console, fills the real
   Keycloak login form with `CCE_TEST_ADMIN_USERNAME` / `CCE_TEST_ADMIN_PASSWORD`, waits for
   `/users`, and saves the browser state to `e2e/.auth/admin.json`. Every CUJ test starts from
   that state; Keycloak's session cookie lets the console's login redirect finish without the
   form. The file holds a live session. It is git-ignored and Docker-ignored. Do not share it.
3. **Test data** ([`fixtures.ts`](../e2e/fixtures.ts)) is created through the service API, not
   the UI. The token comes from the local-only public client `cce-e2e` with the password grant
   and the same two variables. Every name has a per-test numeric suffix (`E2E_{STEM}_{suffix}`
   roles, `e2e-{stem}-{suffix}` users, `@cce.local` emails).
4. **CUJ 6** switches the stand-in to `fail` and always back to `ok` in a `finally` block.

| Spec                           | Journey                                                                                                |
| ------------------------------ | ------------------------------------------------------------------------------------------------------ |
| `cuj2-onboard-user.spec.ts`    | Create a user with one role in the UI, then find them with Search                                      |
| `cuj3-configure-role.spec.ts`  | Create a role, grant a DETAIL menu without its parent, save one permission rule                        |
| `cuj4-change-roles.spec.ts`    | Replace a user's roles; checks the `PUT` body is the full new set                                      |
| `cuj5-deactivate-user.spec.ts` | Deactivate, lock and activate from the Users list row actions                                          |
| `cuj6-sync-failure.spec.ts`    | Role created while sync fails, 502 on save, `ROLE_NOT_SYNCED` on assign, Re-sync, then assign succeeds |

Each run leaves its `E2E_*` roles and users behind. Reset the stack when the global setup asks
for it.

## Troubleshooting

| Symptom                                                                             | Likely cause                                                                                                                                           |
| ----------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| "The console cannot start" / "Configuration error: VITE_KEYCLOAK_REALM is not set." | No `.env`, or the realm is empty.                                                                                                                      |
| "Sign-in failed" screen                                                             | `keycloak-js` init threw or ended without a session. Check Keycloak at `VITE_KEYCLOAK_URL` and the `cce-admin-ui` client.                              |
| "Something went wrong (HTTP 500, CLIENT_UNEXPECTED_RESPONSE)." on every call        | The service is not running on 8085. The Vite proxy answers 500 with an empty body, so the console sees a non-envelope 500 rather than a network error. |
| "You do not have permission to do this." everywhere, also in the header             | The signed-in Keycloak user has no active local user in the service.                                                                                   |
| `npm run dev` exits at once                                                         | Port 3002 is already in use (`strictPort`).                                                                                                            |
