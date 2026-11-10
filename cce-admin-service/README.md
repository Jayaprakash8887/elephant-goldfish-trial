# cce-admin-service

The Spring Boot backend of the CCE v2 admin module: users, roles, menus and API permissions,
kept in step with Keycloak. The design is
[docs/designs/cce-admin-service.md](../docs/designs/cce-admin-service.md). This README covers the
local environment (milestone M0).

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
the realm again.

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
