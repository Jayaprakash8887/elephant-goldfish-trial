# cce-admin-service

The Spring Boot backend of the CCE v2 admin module: users, roles, menus and API permissions,
kept in step with Keycloak. The design is
[docs/designs/cce-admin-service.md](../docs/designs/cce-admin-service.md). This README covers the
local environment (milestone M0), running the service on the host or in a container, its
configuration, and the steps for a new shared environment.

## Prerequisites

- Docker with the compose plugin (`docker compose`).
- A JDK 21, for host builds. Maven itself comes from the Maven Wrapper (`./mvnw`, Maven 3.9.16).
- Node 22, for `scripts/token-claims.mjs`.

## Local values

Copy the example env file and replace every `change-me` with a local value:

```sh
cp .env.example .env
```

`.env` is git-ignored and must never be committed. `.env.example` holds placeholders only. The
realm import `keycloak/realm-cce.json` holds no credentials: Keycloak fills its `${…}`
placeholders from `.env` when the container starts.

## Start the infrastructure

```sh
docker compose up -d --wait
```

This starts, with every port bound to `127.0.0.1`:

| Service | Host port | What it is |
|---|---|---|
| `postgres` | 5433 | Postgres 16, databases `admin_service` and `keycloak` |
| `keycloak` | 8180 | Keycloak 26 with the realm `cce` imported |
| `mailpit` | 8025 | Mail catcher (web UI and API) |
| `gateway-stub` | 8090 | The stand-in for the gateway's sync endpoint (see `gateway-stub/README.md`) |

## Run the service

Start the infrastructure first (above). Then run the service in one of two ways, both on port
8085:

- **On the host:** `./mvnw spring-boot:run`. The service reads `.env` itself
  (`spring.config.import=optional:file:.env[.properties]`), with the host-run values of
  `.env.example` (`localhost:5433`, `localhost:8180`, `localhost:8090`).
- **In a container:** `docker compose --profile app up -d --build --wait`. This builds the image
  from `Dockerfile` and starts `admin-service` with the other four services. Compose passes `.env`
  to it and overrides the addresses with in-network ones: `DATABASE_URL`
  (`postgres:5432`), `KEYCLOAK_SERVER_URL` and `JWT_JWK_SET_URI` (`keycloak:8080`) and
  `GATEWAY_BASE_URL` (`gateway-stub:8090`). `JWT_ISSUER` stays
  `http://localhost:8180/auth/realms/cce`, the issuer Keycloak writes into every token.

Check it:

```sh
curl -s localhost:8085/admin/v1/health    # {"status":"UP","database":"UP"}
curl -s -H "Authorization: Bearer $(node scripts/token-claims.mjs admin --token)" localhost:8085/admin/v1/me
```

On the first start, the bootstrapper links the Keycloak user named in `BOOTSTRAP_ADMIN_USERNAME`
(the test administrator) as the first `SUPER_ADMIN`. On later starts it logs a warning that the
variable is no longer needed.

## Stand-in switch

The stand-in can be switched to fail, so a failed gateway sync can be checked. No restart is
needed. The mode lives in memory and starts as `ok`.

```sh
curl -X PUT --data fail http://localhost:8090/_stub/mode   # every sync now gets 503
curl -X PUT --data ok http://localhost:8090/_stub/mode     # back to normal
curl http://localhost:8090/_stub/mode                      # read the current mode
```

## URLs

- Swagger UI: <http://localhost:8085/admin/v1/swagger-ui>
- Mail: <http://localhost:8025>
- Keycloak account console (sign in as the test administrator from `CCE_TEST_ADMIN_USERNAME` and
  `CCE_TEST_ADMIN_PASSWORD`): <http://localhost:8180/auth/realms/cce/account>

## Reset

```sh
docker compose --profile app down -v
```

Then start again. Removing the volume deletes both databases. On the next start, Keycloak imports
the realm again, Flyway migrates again, and the bootstrapper creates the administrator's row
again.

## Token script

`scripts/token-claims.mjs` gets a token from the local realm and prints its `iss`, `aud`,
`realm_access.roles` and `auth_time`:

```sh
node scripts/token-claims.mjs admin      # password grant from cce-e2e, as the test administrator
node scripts/token-claims.mjs service    # client-credentials grant of cce-admin-service
node scripts/token-claims.mjs admin --token   # prints only the raw access token
```

It reads `CCE_TEST_ADMIN_USERNAME`, `CCE_TEST_ADMIN_PASSWORD` and `KEYCLOAK_ADMIN_CLIENT_SECRET`
from `.env`.

## Configuration

Every credential and every environment-specific URL comes from an environment variable. A
variable marked required has no default, and the service fails at startup, naming the variable,
when it is missing.

| Variable | Default | Notes |
|---|---|---|
| `SERVER_PORT` | `8085` | |
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | none, required | |
| `JWT_ISSUER`, `JWT_AUDIENCE` | none, required | Must equal the gateway's values. |
| `JWT_JWK_SET_URI` | `${JWT_ISSUER}/protocol/openid-connect/certs` | Set to the in-network URL in compose. |
| `JWT_ROLES_CLAIM_PATH` | `realm_access.roles` | |
| `KEYCLOAK_SERVER_URL`, `KEYCLOAK_REALM`, `KEYCLOAK_ADMIN_CLIENT_SECRET` | none, required | |
| `KEYCLOAK_ADMIN_CLIENT_ID` | `cce-admin-service` | |
| `KEYCLOAK_CONNECT_TIMEOUT`, `KEYCLOAK_READ_TIMEOUT` | `2s`, `10s` | |
| `KEYCLOAK_ACTION_EMAIL_LIFESPAN` | `43200` | Seconds. |
| `KEYCLOAK_ACTION_EMAIL_CLIENT_ID`, `KEYCLOAK_ACTION_EMAIL_REDIRECT_URI` | empty | Where Keycloak sends the user after the action. |
| `GATEWAY_BASE_URL` | none, required | |
| `GATEWAY_SYNC_PATH` | `/api/permissions/sync-to-keycloak` | |
| `GATEWAY_CONNECT_TIMEOUT`, `GATEWAY_READ_TIMEOUT` | `2s`, `10s` | |
| `PERMISSION_CHECK_ENABLED` | `true` | The service's own permission check. Must be `true` in shared environments unless the gateway owners show the gateway matches patterns differently. |
| `TRUSTED_PROXIES` | a regular expression that matches no address | The proxies whose `X-Forwarded-For` sets the audited client IP. In shared environments, the gateway's addresses. |
| `BOOTSTRAP_ADMIN_USERNAME` | empty | The Keycloak user to link as the first `SUPER_ADMIN`. Unset it after the first start. |
| `SPRINGDOC_ENABLED` | `true` | Serves the OpenAPI document and Swagger UI under `/admin/v1`. Set by each environment's owner; the service is not internet-facing. |

The database pool waits at most 2 seconds for a connection
(`spring.datasource.hikari.connection-timeout: 2000`).

## Bootstrap steps for a new shared environment

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
    authentication it serves are provisional until the gateway owners confirm that the sync
    endpoint accepts a service-account token (GW-6).
5.  Create the public client `cce-admin-ui`: PKCE S256, the environment's redirect URI, audience
    mapper, default scope `basic`.
6.  Create the first administrator's Keycloak account: enabled, and with a verified email if the
    realm requires verification.
7.  Deploy with every required variable, `TRUSTED_PROXIES` and `BOOTSTRAP_ADMIN_USERNAME`.
    Flyway migrates and seeds. Check the log for the bootstrap result, then unset the variable
    and redeploy.
8.  Ask the gateway owners to route `/admin/**` to the service and, now that `api_permissions`
    holds the seed rows, to restart or reload the gateway (GW-2, go/no-go).
9.  Run the unowned-rows query below and send the result to the gateway owners (GW-5).
10. If the environment separates a migration user from a runtime user, grant the runtime user
    only `SELECT, INSERT` on `audit_log`. The append-only trigger applies either way.

### Operator queries (read-only)

Unowned rows, the `api_permissions` names that no role here owns (GW-5):

```sql
SELECT DISTINCT p.permission_name FROM api_permissions p LEFT JOIN roles r ON r.name = p.permission_name WHERE r.id IS NULL ORDER BY 1;
```

Failures to review, the failed gateway syncs and the Keycloak failures that left a partial state:

```sql
SELECT occurred_at, action, entity_type, entity_id, failure, after_state FROM audit_log WHERE outcome = 'FAILED' ORDER BY occurred_at DESC;
```

## Rollout and rollback

- The migrations add tables and rows. They create `api_permissions` only when it is absent, and
  add seed rows to an existing one with `ON CONFLICT DO NOTHING`. They never alter an existing
  `api_permissions`.
- Rolling the service back leaves the tables and seed rows in place, and the gateway keeps
  reading `api_permissions` unchanged.
- `PERMISSION_CHECK_ENABLED=false` turns off the service's own permission check without a
  release.
- **Before cce-insights-ui switches its sidebar to `/me/menus`:** create the console roles with
  their menus and permissions, and onboard every existing console user through `POST /users`,
  which links their existing Keycloak accounts. A user with no local row gets 403 from
  `/me/menus`. `INSIGHTS_READ` and other names no role here owns stay managed by the gateway
  owners.
