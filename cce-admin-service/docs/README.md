# cce-admin-service developer docs

`cce-admin-service` is the Spring Boot 3.5 (Java 21, Spring MVC) backend of the CCE v2 admin
module. It stores users, roles, menus and role-to-menu grants in its own Postgres tables, and
writes each role's API permissions into `api_permissions`, the table gateway-service reads. Every
user change is mirrored into Keycloak through the Keycloak Admin API, and every role or permission
change ends with a call to the gateway's sync endpoint, which creates the matching realm roles.
All successful changes, and the partial failures, are written to an append-only `audit_log`. The
service also serves `GET /admin/v1/me/menus`, the sidebar tree for the caller's token roles.

These pages describe what the code does. Where it differs from the approved design
([`docs/designs/cce-admin-service.md`](../../docs/designs/cce-admin-service.md)), a
"Differs from the design:" note names the design section. Setup and run instructions are in the
repo [README](../README.md).

| File | What it covers |
|---|---|
| [architecture-overview.md](architecture-overview.md) | Packages and classes, request pipeline, external systems, transactions and locks, configuration, local stack. |
| [api-reference.md](api-reference.md) | Every endpoint: method, path, permission, request and response shapes, status and error codes. |
| [data-dictionary.md](data-dictionary.md) | Every table from the Flyway migrations, the seed rows, and how `api_permissions` is handled. |
| [flow-diagrams.md](flow-diagrams.md) | Mermaid diagrams of the user, role, permission/sync, menu, audit and bootstrap flows. |
| [security-model.md](security-model.md) | JWT validation, current user, permission check, system roles, last-admin protection, audit, secrets, public surface. |
