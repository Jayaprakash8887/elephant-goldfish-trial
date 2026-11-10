# cce-admin-ui Documentation

`cce-admin-ui` is the admin console for the CCE platform's user-management backend,
[`cce-admin-service`](../../cce-admin-service/docs/README.md). It's where a platform
administrator creates users, defines roles, decides which `cce-insights-ui` menus each
role can see, and decides which APIs each role can call — so that a user logging in to
`cce-insights-ui` sees only the screens their role permits.

This repo owns the **frontend only**. It does not own the database, the Keycloak
provisioning logic, or the authorization rules — those are `cce-admin-service`'s, and
are documented there, not repeated here. Read in this order:

1. [architecture-overview.md](architecture-overview.md) — what this app is, how it fits
   with `cce-admin-service`, Keycloak, and `gateway-service`, its access-control model,
   technology stack, and internal structure.
2. [pages-and-wireframes.md](pages-and-wireframes.md) — every screen, its route, and
   what it does.
3. [api-integration.md](api-integration.md) — how the app authenticates against
   Keycloak and calls `cce-admin-service`'s REST API.
4. [developer-setup.md](developer-setup.md) — local environment, scripts, env vars.
5. [deployment-guide.md](deployment-guide.md) — container build and runtime
   configuration.

## Related repos

| Repo | Relationship |
|---|---|
| [`cce-admin-service`](../../cce-admin-service/docs/README.md) | The backend this app is a console for. Owns the `users`/`roles`/`menus`/`api_permissions` data model, the authorization model, and the REST API this app calls. Anything about *what a role/menu/permission means* or *how it's enforced* lives there. |
| [`cce-insights-ui`](https://github.com/openphc/cce-insights-ui/tree/demo-rw/docs) | The console whose sidebar this app's menu/role mappings control. Its current navigation is the seed data for `cce-admin-service`'s `menus` table. Its `docs/` set is the template this app's technology choices and doc structure follow (see [architecture-overview.md §4](architecture-overview.md#4-technology-stack)). |

Each document here links to the others (and to `cce-admin-service`'s docs) for anything
outside its own scope rather than repeating it — if you're looking for something and
don't find it where you expected, follow the links.
