# cce-admin-ui developer docs

`cce-admin-ui` is the CCE v2 admin console. It is a React 18 single-page app. Platform
administrators use it to manage users, roles (with their menu grants and gateway permission
rules) and the menus catalog. The console stores no data of its own. Every read and write goes to
`cce-admin-service` under `/admin/v1`, with a Keycloak access token obtained through
Authorization Code + PKCE. Locally the Vite dev server proxies those calls to the service. In a
deployment, Caddy serves the built files and proxies them to `gateway-service`.

These docs describe what the code does. Where the code differs from the design
([`docs/designs/cce-admin-ui.md`](../../docs/designs/cce-admin-ui.md)), a "Differs from the
design:" note says so.

| File                                                 | What it covers                                                                                                                                                                                      |
| ---------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [architecture-overview.md](architecture-overview.md) | Stack and versions, folder layout and lint-enforced layer rules, routing, auth, API client, query layer, error presentation, state and forms, test layers                                           |
| [api-integration.md](api-integration.md)             | Every service endpoint the console calls, with the calling function and hook, request and response types, warnings, error-code handling, 502 sync-failure states, and the endpoints it does not use |
| [pages-and-wireframes.md](pages-and-wireframes.md)   | Every route and page: content, actions, states, data-based visibility, and ASCII wireframes                                                                                                         |
| [developer-setup.md](developer-setup.md)             | Prerequisites, environment variables, running against the local service stack, MSW mocks, npm scripts, browser-test credentials                                                                     |
| [deployment-guide.md](deployment-guide.md)           | Dockerfile stages, Caddyfile, runtime configuration, the Keycloak client, and the `verify` image check                                                                                              |

The repository [README](../README.md) has the short quick-start. These docs go into more detail.
