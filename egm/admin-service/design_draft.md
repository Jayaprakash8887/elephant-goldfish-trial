# Design: cce-admin-service and its local environment

| Field | Value |
|---|---|
| Author | jayaprakash8887 |
| Status | Draft (EGM `egm-draft`, section by section) |
| Date | 2026-11-10 |
| Scope | PRD milestones M0, B1–B5 (B-01 to B-50) and API-level E2E checks for S1–S6 |
| Out of scope here | `cce-admin-ui` (F-01 to F-15), designed separately in `egm/admin-ui/` |
| EGM artifact directory | `egm/admin-service/` |

## Inputs

This design is built from these files. The team's design docs are the source of truth for
contracts, schema and behaviour; the PRD summarises and sequences them (PRD §1, golden rule).

| Input | Role in this design |
|---|---|
| [CCE_Admin_Module_PRD.md](../../context/prd/CCE_Admin_Module_PRD.md) (Markdown copy of the authoritative [PDF](../../context/prd/CCE_Admin_Module_PRD.pdf)) | Requirement IDs, milestones, scenarios, definition of done |
| [architecture-overview.md](../../context/cce-admin-service/docs/architecture-overview.md) | Stack, layering, deployment, configuration names |
| [data-dictionary.md](../../context/cce-admin-service/docs/data-dictionary.md) | Tables, columns, seed menus, indexes |
| [security-model.md](../../context/cce-admin-service/docs/security-model.md) | JWT validation, one-identifier rule, Keycloak responsibility split, secrets |
| [api-reference.md](../../context/cce-admin-service/docs/api-reference.md) | Endpoints, envelope, error codes |
| [flow-diagrams.md](../../context/cce-admin-service/docs/flow-diagrams.md) | Order of operations for login, onboarding, role configuration, sync, deactivation |
| [DATABASE_PERMISSIONS_MIGRATION.md](../../context/gateway-service/DATABASE_PERMISSIONS_MIGRATION.md) | Fixed shape of `api_permissions` as gateway-service reads it |
| [cce-admin-ui api-integration.md](../../context/cce-admin-ui/docs/api-integration.md), [pages-and-wireframes.md](../../context/cce-admin-ui/docs/pages-and-wireframes.md) | What the console expects from this API (fields shown, replace semantics, local proxy) |
| [cce-insights-ui architecture-overview.md](../../context/cce-insights-ui/docs/architecture-overview.md) | Current sidebar order, icons, realm default `cce` |
| [PROVENANCE.md](../../context/PROVENANCE.md) | Where each input came from |

## Source conflicts and gaps

Found while reading the inputs, before drafting. **Conflicts** are places where two inputs say
different things. **Gaps** are questions the inputs leave open but the implementation must
answer. Each one is either settled by a rule already in the inputs (stated in the "Resolution"
column) or marked "Author decision", which means it goes to the author at a gate during
drafting. None is resolved silently.

### Conflicts between inputs

| ID | Conflict | Inputs that disagree | Resolution |
|---|---|---|---|
| C1 | `api_permissions` column shape. The data dictionary says `id UUID`, `permission_name VARCHAR(150)`, `resource_category VARCHAR(100)`, `created_at`/`updated_at TIMESTAMPTZ NOT NULL`, no unique constraint. The gateway doc says `id BIGSERIAL`, `permission_name VARCHAR(100)`, `resource_category VARCHAR(50)`, `TIMESTAMP DEFAULT CURRENT_TIMESTAMP` (nullable), `UNIQUE (permission_name, http_method, uri_pattern)`, indexes `idx_permission_name` and `idx_resource_category`, and an `updated_at` trigger. B-01 also says all seven tables have UUID primary keys. | data-dictionary §6, PRD B-01 vs gateway migration doc | The data dictionary's own ownership note and PRD §5/§12 say the gateway doc governs this table. This design uses the gateway shape exactly. B-01's "UUID PKs" holds for the other six tables only. To be raised as a doc change on data-dictionary §6 (PRD §11). |
| C2 | Where the tables live. PRD §5 says gateway reads "a Postgres **database** named `admin_service`". PRD §6 and the data dictionary say "PostgreSQL **schema** `admin_service`". The gateway connects to database `admin_service` and queries `api_permissions` without a schema name, so it finds the table only in a schema on its search path (normally `public`). | PRD §5 vs PRD §6, data-dictionary header, gateway doc | Author decision (Technical Plan). |
| C3 | Keycloak realm name. The service architecture diagram labels the realm `smartcare`. cce-insights-ui defaults to realm `cce`. Every input agrees all three apps share one realm. | architecture-overview §3 vs cce-insights-ui docs | Realm is configuration (`KEYCLOAK_REALM`), not code. The local environment uses `cce` to match the existing console's default. |
| C4 | Seed menu order and visibility. The data dictionary lists the 12 menus in one order. The current cce-insights-ui sidebar shows 7 links in a different order (Deviations before Patients) and keeps Practitioners, Intelligence and Exports as URL-only pages, while the seed marks them `NAV`. | data-dictionary §3 vs cce-insights-ui architecture §Sidebar | B-02 requires the seed to match the data dictionary exactly, so all 10 non-DETAIL rows are `NAV`. The data dictionary gives no `sort_order` or icons, so `sort_order` follows the current sidebar order and icons come from the API reference example and the existing sidebar's icon list (detailed in the Implementation section). |
| C5 | When the audit row for a permission change is written. Flow 3 writes `ROLE_PERMISSIONS_UPDATED` after a successful gateway sync. B-43 and flow 4 say the DB change stays saved when the sync fails, and B-50 says every mutation is audited. If the audit row waits for a successful sync, a saved change with a failed sync has no audit row. | flow-diagrams §3 vs PRD B-43, B-50, flow-diagrams §4 | The audit row is written in the same transaction as the permission rows, before the sync call. The sync outcome is recorded in a separate audit row. To be raised as a doc change on flow-diagrams §3. |
| C6 | api-reference says user creation returns 502 "if the Keycloak account is created but the local insert fails (or vice versa)". In flow 2 the local insert comes after Keycloak, so "vice versa" cannot happen. | api-reference §Users vs flow-diagrams §2 | Follow flow 2 order. No action needed beyond noting it. |
| C7 | `GET /roles/:id/menus` "lists menu keys", but `PUT /roles/:id/menus` takes `menuIds`. A client that reads keys cannot send ids back without a second lookup. | api-reference §Roles | Gap in the contract; settled in the Technical Plan (contract section). |
| C8 | URI patterns. The gateway doc's sample rows use `/admin/users`. The API base path is `/admin/v1`, and PRD §12 asks for rows covering `/admin/v1/**`. The inputs do not say whether the gateway matches the path before or after it strips anything. | gateway doc samples vs api-reference, PRD §12 | Seed rows use `/admin/v1/**` as PRD §12 says. Recorded as an open question for gateway-service owners; it cannot be tested locally because the gateway is stubbed. |
| C9 | Architecture §6 says creating a user or assigning a role "must be safe to retry". api-reference and flow 2 say a partial user creation is "not silently retried" because a retry risks a duplicate Keycloak account. | architecture-overview §6 vs api-reference, flow-diagrams §2 | The service never retries on its own (flow 2 wins on behaviour). Where a client repeats a call, the service makes the repeat safe where it can (detailed in the Technical Plan). |

### Gaps the inputs leave open

| ID | Gap | Where it is raised | Resolution |
|---|---|---|---|
| G1 | How the first `SUPER_ADMIN` gets a Keycloak account, a local `users` row with the right `keycloak_id`, and `api_permissions` rows for `/admin/v1/**`. Without the local row every call is 403 (B-12). | PRD §12 "Bootstrap admin" | Author decision. |
| G2 | What the local gateway stub does. gateway-service is the only thing that creates realm role definitions in Keycloak. A no-op stub means a new role never becomes a realm role locally, so S2/S4 with a new role fail at the Keycloak "assign realm role" step. | PRD §10 setup note, §9 "Ordering" | Author decision. |
| G3 | Whether the service itself checks `api_permissions` for the caller's roles, or leaves that to the gateway. Locally there is no gateway, so without an in-service check any ACTIVE user can call every admin endpoint. | security-model §3 ("method-level authorization" in the layering list), PRD B-10–B-12 | Author decision. |
| G4 | How `users.last_login_at` is updated. The data dictionary says "a lightweight callback, not polled"; the PRD says the mechanism is not specified. The console's Users list shows "last login". | data-dictionary §1, PRD §12 | Author decision. |
| G5 | How a new user gets a first password, and what reset-password sends, given "no password ever passes through the service" (B-36). Keycloak's reset email needs an SMTP server; none is in the local setup. | flow-diagrams §2 ("temp password / required actions"), PRD B-36 | Author decision. |
| G6 | What happens when a user is assigned a role whose realm role does not exist in Keycloak yet (for example, a role that has no permission rows, so the gateway sync never created it). | PRD §9 "Ordering", flow-diagrams §4 note | Settled in the Technical Plan. |
| G7 | Duplicate `username` on `POST /users`. Only `EMAIL_IN_USE` is defined. | api-reference error codes | Settled in the Technical Plan. |
| G8 | Shape of the DETAIL-without-parent warning (B-41). The envelope has only `data` and `pagination`. | PRD B-41, data-dictionary §4 | Settled in the Technical Plan (contract section). |
| G9 | Response field lists for users, roles, menus and permissions. The docs give request bodies and a few examples but not full response shapes, and the console needs `roles`, `lastLoginAt` and a role `userCount`. | api-reference, pages-and-wireframes §1, §3 | Settled in the Technical Plan (contract section). Must match the `egm/admin-ui/` design exactly. |
| G10 | Which lists are paginated. B-04 says "list endpoints paginate", but `GET /menus` is a tree and the role sub-resources use replace semantics, which need the full set. | PRD B-04, api-reference | Settled in the Technical Plan. |
| G11 | Deleting a menu that has child menus. `parent_id` has no `ON DELETE` rule. | data-dictionary §3, PRD B-44 | Settled in the Technical Plan. |
| G12 | `/me/menus` edge cases: a DETAIL row granted without its parent, an active child under an inactive parent, and whether "DETAIL inherits its parent's visibility" means a DETAIL row is returned whenever its parent is granted. | PRD §4, B-21, data-dictionary §3 | Settled in the Technical Plan. |
| G13 | Whether `PUT /users/:id` also updates first name, last name and email in Keycloak, and what `LOCKED` means compared with `INACTIVE`. Audit action names exist only for some actions. | api-reference §Users, data-dictionary §1, §7 | Settled in the Technical Plan. |
| G14 | Which IP address goes in `audit_log.ip_address` when the gateway proxies the call. | data-dictionary §7, PRD B-50 | Settled in the Technical Plan. |
| G15 | The `aud` value to check. Keycloak access tokens do not carry a custom audience unless the client has an audience mapper. | security-model §3, cce-insights-ui deployment guide | Settled in the Technical Plan (local realm). |
