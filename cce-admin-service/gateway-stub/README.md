# cce-gateway-stub

**This is a stand-in, not gateway-service.** It exists only in the local environment of
cce-admin-service (design decision AD-11), so that the service runs the same HTTP client code
locally that it runs against the real gateway. It is never deployed to a shared environment.

It implements one endpoint of the gateway, `POST /api/permissions/sync-to-keycloak`, under the
assumptions the design makes until the gateway owners answer its open questions:

- **GW-1 (sync contract):** no request body and no parameters; a full sync of every
  `permission_name`; any 2xx is success. The stand-in returns 200 with a JSON summary
  `{"created": [...], "deleted": [...]}`, which the service does not parse.
- **GW-4 (removing realm roles):** a sync deletes a realm role whose `permission_name` no longer
  has rows, but only realm roles the stand-in itself created. It tags every role it creates with
  the attribute `cce-managed=true`, and deletes only tagged roles.

What a sync does, in order:

1. In `fail` mode it returns 503 and changes nothing.
2. Without an `Authorization: Bearer …` header it returns 401. **It checks only that the header is
   there**: it does not validate the token in any way.
3. Otherwise it reads the distinct `permission_name` values of `api_permissions`, lists the realm
   roles with their attributes, creates a tagged realm role for every name that has none, deletes
   tagged realm roles that no longer have rows, and returns 200.

It never creates, changes or deletes Keycloak's built-in roles (`default-roles-<realm>`,
`offline_access`, `uma_authorization`), never deletes untagged roles, and never deletes
`SUPER_ADMIN`.

## Mode switch

`/_stub/mode` needs no token. The mode lives in memory and starts as `ok` on every start.

```sh
curl -X PUT --data fail http://localhost:8090/_stub/mode   # every sync now returns 503
curl -X PUT --data ok   http://localhost:8090/_stub/mode   # back to normal
curl http://localhost:8090/_stub/mode                      # prints the current mode
```

Any other `PUT` body gets 400.

## Variables

All are required. `docker-compose.yml` sets them.

| Variable | Local value |
|---|---|
| `STUB_PORT` | `8090` |
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | `jdbc:postgresql://postgres:5432/admin_service`, `admin_service`, `${POSTGRES_PASSWORD}` |
| `KEYCLOAK_SERVER_URL`, `KEYCLOAK_REALM` | `http://keycloak:8080/auth`, `cce` |
| `STUB_CLIENT_ID`, `STUB_CLIENT_SECRET` | `cce-gateway-stub`, `${GATEWAY_STUB_CLIENT_SECRET}` |

The client `cce-gateway-stub` holds the `realm-management` roles `manage-realm` and `view-realm`
in the local realm only, so that it can create and delete realm roles.

## Build and test

From `cce-admin-service/`:

```sh
./mvnw -f gateway-stub/pom.xml verify
docker compose up -d --build --wait   # builds and starts it with the rest of the stack
```
