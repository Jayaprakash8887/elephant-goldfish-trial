# Data dictionary

Database `admin_service`, schema `public`. Migrations in
[`src/main/resources/db/migration`](../src/main/resources/db/migration):

| Migration | What it does |
|---|---|
| [`V1__schema.sql`](../src/main/resources/db/migration/V1__schema.sql) | `CREATE EXTENSION IF NOT EXISTS pgcrypto`; the six owned tables; the `audit_log` append-only trigger; `api_permissions` **only if it does not exist**. |
| [`V2__verify_api_permissions.sql`](../src/main/resources/db/migration/V2__verify_api_permissions.sql) | Checks that `api_permissions` has exactly the gateway's shape. Raises an exception (migration stops) on any mismatch. |
| [`V3__seed.sql`](../src/main/resources/db/migration/V3__seed.sql) | 12 menus, 2 system roles, 6 `api_permissions` rows. |

Flyway settings (`application.yml`): `baseline-on-migrate: true`, `baseline-version: 0`,
`table: admin_service_flyway_history`. The baseline lets Flyway run on a `public` schema that
already holds the gateway's table; the custom history table keeps this service's history apart.
Hibernate runs with `ddl-auto: validate`.

**JPA conventions** (`../src/main/java/org/cce/admin/entity/`): UUID ids are assigned in Java in
`@PrePersist` when null, so the service can know an id before the insert. `created_at` and
`updated_at` are set in `@PrePersist`, and `updated_at` in `@PreUpdate`; the database defaults
are used only by the seed. Enums are stored as strings. Bulk JPQL updates
(`RoleRepository.updateSyncResult`, `UserRepository.touchUpdatedAt`,
`UserRepository.updateLastLoginIfLater`) skip `@PreUpdate`.

## `users`

[`V1__schema.sql:12-31`](../src/main/resources/db/migration/V1__schema.sql#L12-L31) ·
[`UserEntity`](../src/main/java/org/cce/admin/entity/UserEntity.java)

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` | PK `users_pkey` |
| `keycloak_id` | uuid | no | | Unique `ux_users_keycloak_id`. Equals the token `sub`. |
| `username` | varchar(50) | no | | Unique `ux_users_username`. Never updated by the service. |
| `email` | varchar(254) | no | | Unique index `ux_users_email_lower` on `lower(email)`. The service stores it lower case. |
| `first_name` | varchar(100) | no | | |
| `last_name` | varchar(100) | no | | |
| `status` | varchar(10) | no | | CHECK `ck_users_status` in (`ACTIVE`, `INACTIVE`, `LOCKED`) |
| `facility_scope` | varchar(50) | yes | | Free text. |
| `last_login_at` | timestamptz | yes | | Moved forward only, to the token's `auth_time`, by `CurrentUserFilter`. Does not touch `updated_at`. |
| `created_at` | timestamptz | no | `now()` | |
| `updated_at` | timestamptz | no | `now()` | Changes on profile, status and role changes. |

Indexes: `ix_users_status (status)`, `ix_users_facility_scope (facility_scope)`.

## `roles`

[`V1__schema.sql:34-49`](../src/main/resources/db/migration/V1__schema.sql#L34-L49) ·
[`RoleEntity`](../src/main/java/org/cce/admin/entity/RoleEntity.java)

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` | PK `roles_pkey` |
| `name` | varchar(100) | no | | Unique `ux_roles_name`; CHECK `ck_roles_name` `^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$`. Equals the Keycloak realm role name and `api_permissions.permission_name`. Never updated. |
| `display_name` | varchar(100) | no | | |
| `description` | varchar(500) | yes | | |
| `is_system` | boolean | no | `false` | System roles cannot be edited or deleted. |
| `is_assignable` | boolean | no | **none** | Every insert must set it. `false` blocks assignment to users. |
| `sync_status` | varchar(10) | no | `'NEVER'` | CHECK `ck_roles_sync_status` in (`NEVER`, `SYNCED`, `FAILED`). Result of the last gateway sync made *for this role*. |
| `last_sync_at` | timestamptz | yes | | Time of that sync. |
| `created_at`, `updated_at` | timestamptz | no | `now()` | A sync does not change `updated_at`. |

## `menus`

[`V1__schema.sql:52-72`](../src/main/resources/db/migration/V1__schema.sql#L52-L72) ·
[`MenuEntity`](../src/main/java/org/cce/admin/entity/MenuEntity.java)

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` | PK `menus_pkey` |
| `key` | varchar(100) | no | | Unique `ux_menus_key`. Never updated. |
| `label` | varchar(100) | no | | |
| `path` | varchar(255) | no | | Console route, may contain `:param`. |
| `icon` | varchar(100) | yes | | Heroicons 2 outline component name. |
| `type` | varchar(10) | no | | CHECK `ck_menus_type` in (`NAV`, `DETAIL`). Never updated. |
| `parent_id` | uuid | yes | | FK `fk_menus_parent_id` → `menus(id)` ON DELETE RESTRICT. Index `ix_menus_parent_id`. |
| `sort_order` | integer | no | | CHECK `ck_menus_sort_order` 0 to 10000. |
| `is_active` | boolean | no | `true` | |
| `created_at`, `updated_at` | timestamptz | no | `now()` | |

Table CHECK `ck_menus_parent`: (`type = 'NAV'` and `parent_id IS NULL`) or (`type = 'DETAIL'`
and `parent_id IS NOT NULL`). That a `DETAIL`'s parent is a `NAV` is checked in
[`MenuService.checkParent`](../src/main/java/org/cce/admin/service/MenuService.java#L149-L171),
not in the database. So the tree is at most two levels deep.

## `role_menus`

[`V1__schema.sql:75-85`](../src/main/resources/db/migration/V1__schema.sql#L75-L85)

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` | PK |
| `role_id` | uuid | no | | FK → `roles(id)` ON DELETE CASCADE |
| `menu_id` | uuid | no | | FK → `menus(id)` ON DELETE CASCADE. Index `ix_role_menus_menu_id`. |
| `created_at` | timestamptz | no | `now()` | |

Unique `ux_role_menus_role_menu (role_id, menu_id)`. The service deletes these rows itself on role
and menu delete; the cascades are backstops.

## `user_roles`

[`V1__schema.sql:88-98`](../src/main/resources/db/migration/V1__schema.sql#L88-L98)

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` | PK |
| `user_id` | uuid | no | | FK → `users(id)` ON DELETE RESTRICT |
| `role_id` | uuid | no | | FK → `roles(id)` ON DELETE RESTRICT. Index `ix_user_roles_role_id`. |
| `created_at` | timestamptz | no | `now()` | |

Unique `ux_user_roles_user_role (user_id, role_id)`. Changing a user's status never touches these
rows. No endpoint deletes a user.

## `audit_log` (append-only)

[`V1__schema.sql:101-131`](../src/main/resources/db/migration/V1__schema.sql#L101-L131) ·
[`AuditLogEntity`](../src/main/java/org/cce/admin/entity/AuditLogEntity.java)

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` | PK |
| `occurred_at` | timestamptz | no | `now()` | Set in `@PrePersist`. |
| `actor_user_id` | uuid | yes | | No FK. Null for `system:bootstrap`. |
| `actor_keycloak_id` | varchar(64) | yes | | The caller's `keycloak_id` (= token `sub`). |
| `actor_username` | varchar(100) | no | | Username snapshot, or `system:bootstrap`. |
| `action` | varchar(50) | no | | An `AuditAction` name. No CHECK. |
| `entity_type` | varchar(20) | no | | CHECK in (`USER`, `ROLE`, `MENU`) |
| `entity_id` | uuid | no | | No FK (the entity may be deleted, or never created). |
| `outcome` | varchar(10) | no | | CHECK in (`SUCCESS`, `FAILED`) |
| `before_state` | jsonb | yes | | |
| `after_state` | jsonb | yes | | |
| `failure` | jsonb | yes | | On a failure row: `{ operation, failedStep, completedSteps, keycloakUserId, error }`. |
| `ip_address` | varchar(45) | yes | | Request remote address after Tomcat's forwarded-header handling. |

Indexes: `ix_audit_log_entity (entity_type, entity_id, occurred_at)`,
`ix_audit_log_occurred_at`, `ix_audit_log_actor_user_id`, `ix_audit_log_outcome`.

Trigger `trg_audit_log_append_only` (BEFORE UPDATE OR DELETE, FOR EACH ROW) calls
`audit_log_append_only()`, which raises `audit_log is append-only: <op> is not allowed`. The
entity is also `@Immutable`, so Hibernate never issues an `UPDATE` for it.

**What goes in `before_state` / `after_state`** (API shapes from
[api-reference.md](api-reference.md#resource-shapes)):

| Action | Entity | Before | After |
|---|---|---|---|
| `USER_CREATED` | USER | null | `User` + `linked` (bool) |
| `USER_UPDATED` | USER | `User` | `User` |
| `USER_ACTIVATED`, `USER_DEACTIVATED`, `USER_LOCKED` | USER | `User` | `User` |
| `USER_ROLES_UPDATED` | USER | `{ roles: [{id,name,displayName}] }` | same |
| `USER_PASSWORD_RESET_REQUESTED` | USER | null | `{ email, actions: ["UPDATE_PASSWORD"] }` |
| `ROLE_CREATED` | ROLE | null | `Role` + `implicit: [PermissionRow]` |
| `ROLE_UPDATED` | ROLE | `Role` | `Role` |
| `ROLE_DELETED` | ROLE | `Role` + `menuIds` + `permissions` | null |
| `ROLE_MENUS_UPDATED` | ROLE | `{ menuIds }` | `{ menuIds }` |
| `ROLE_PERMISSIONS_UPDATED` | ROLE | `{ explicit, implicit }` | `{ explicit, implicit }` |
| `ROLE_SYNC_REQUESTED` | ROLE | null | `{ outcome, httpStatus, errorSummary }` |
| `MENU_CREATED` | MENU | null | `Menu` |
| `MENU_UPDATED` | MENU | `Menu` | `Menu` |
| `MENU_DELETED` | MENU | `Menu` + `roleIds` | null |

A failure row (a Keycloak step failed after an earlier step changed something) uses the flow's
own action, `outcome = FAILED`, the intended after state and the `failure` JSON. A
`ROLE_SYNC_REQUESTED` row records the sync result in `outcome` (`SUCCESS` or `FAILED`). See
[security-model.md](security-model.md#audit) and [flow-diagrams.md](flow-diagrams.md#audit-writing).

## `api_permissions` (owned by gateway-service)

The gateway reads this table; its shape is fixed. The service writes rows whose
`permission_name` is a role name. Mapped by
[`ApiPermissionEntity`](../src/main/java/org/cce/admin/entity/ApiPermissionEntity.java)
(`id` IDENTITY; `created_at`/`updated_at` are `insertable=false, updatable=false`, so the database
default and trigger set them).

| Column | Type | Null | Default |
|---|---|---|---|
| `id` | bigint (`BIGSERIAL`) | no | `nextval('api_permissions_id_seq')` |
| `permission_name` | varchar(100) | no | |
| `http_method` | varchar(10) | no | |
| `uri_pattern` | varchar(255) | no | |
| `description` | text | yes | |
| `resource_category` | varchar(50) | yes | |
| `created_at` | timestamp (no tz) | yes | `CURRENT_TIMESTAMP` |
| `updated_at` | timestamp (no tz) | yes | `CURRENT_TIMESTAMP` |

Constraint `unique_permission_method_uri UNIQUE (permission_name, http_method, uri_pattern)`.
Indexes `idx_permission_name (permission_name)`, `idx_resource_category (resource_category)`.
Trigger `trg_api_permissions_updated_at` (BEFORE UPDATE) → `update_api_permissions_updated_at()`
sets `updated_at := CURRENT_TIMESTAMP`.

**How the migrations handle it**

1. **V1** creates the table, its constraint, indexes, function and trigger inside a `DO` block
   only when `to_regclass('public.api_permissions') IS NULL`
   ([`V1__schema.sql:135-162`](../src/main/resources/db/migration/V1__schema.sql#L135-L162)).
   An existing table is never altered.
2. **V2** compares the table with the shape above and collects every problem
   ([`V2__verify_api_permissions.sql`](../src/main/resources/db/migration/V2__verify_api_permissions.sql)):
   each column's name, `data_type`, length, nullability and default; a unique constraint on
   exactly `(permission_name, http_method, uri_pattern)` under any name; a non-unique,
   non-partial single-column index on `permission_name` and one on `resource_category`. Any
   problem raises `api_permissions check failed: …` and stops the migration before V3 writes a row.
3. **V3** inserts its rows with `ON CONFLICT (permission_name, http_method, uri_pattern) DO NOTHING`,
   so existing gateway rows are kept and the seed is idempotent.

Differs from the design (§6, V2): V2 also fails when the table has **any extra column**
(`unexpected column […]`,
[`V2__verify_api_permissions.sql:39-40`](../src/main/resources/db/migration/V2__verify_api_permissions.sql#L39-L40)). The design lists only per-column, constraint and index
checks. If the gateway team adds a column, this service will stop migrating.

**How the service writes it**

- Every non-system role holds two *implicit* rows, `GET /admin/v1/me` ("Read own profile",
  category `Self`) and `GET /admin/v1/me/menus` ("Read own menu tree", `Self`)
  ([`ImplicitPermissions`](../src/main/java/org/cce/admin/service/ImplicitPermissions.java)).
  There is no column for this; a row is implicit when its method and pattern equal one of these.
  They are inserted on role create and re-added on every permission save if missing.
- `PUT /roles/{id}/permissions` deletes the role's explicit rows in one bulk delete that keeps the
  implicit rows, then inserts the new rows, so explicit row ids change on every save.
- `DELETE /roles/{id}` deletes every row with that `permission_name`.
- Rows whose `permission_name` matches no role (for example `INSIGHTS_READ`) are never read or
  written by the API. The only exception is `POST /roles`, which refuses a name that already has
  rows (409 `DUPLICATE_ROLE_NAME`).
- `PermissionCheckFilter` reads the table on every authenticated request (see
  [security-model.md](security-model.md#authorization)).

## Seed data

From [`V3__seed.sql`](../src/main/resources/db/migration/V3__seed.sql). All menus are active.
Menu row NN has id `10000000-0000-4000-8000-0000000000NN`.

| NN | key | label | path | icon | type | parent | sort |
|---|---|---|---|---|---|---|---|
| 01 | `dashboard` | Dashboard | `/` | `ChartBarIcon` | NAV | | 10 |
| 02 | `facilities` | Facilities | `/facilities` | `BuildingOffice2Icon` | NAV | | 20 |
| 03 | `compliance` | Compliance | `/compliance` | `ClipboardDocumentCheckIcon` | NAV | | 30 |
| 04 | `deviations` | Deviations | `/deviations` | `ExclamationTriangleIcon` | NAV | | 40 |
| 05 | `patients` | Patients | `/compliance/patients` | `UserGroupIcon` | NAV | | 50 |
| 06 | `events` | Events | `/events` | `SignalIcon` | NAV | | 60 |
| 07 | `ingestion` | Ingestion | `/ingestion` | `CogIcon` | NAV | | 70 |
| 08 | `practitioners` | Practitioners | `/practitioners` | `UserIcon` | NAV | | 80 |
| 09 | `intelligence` | Intelligence | `/intelligence` | `LightBulbIcon` | NAV | | 90 |
| 10 | `exports` | Exports | `/exports` | `ArrowDownTrayIcon` | NAV | | 100 |
| 11 | `compliance.protocol-detail` | Protocol analytics | `/compliance/protocols/:id` | | DETAIL | 03 | 10 |
| 12 | `patients.patient-detail` | Patient detail | `/compliance/patients/:id` | | DETAIL | 05 | 10 |

Roles (both `sync_status = NEVER`, no menus):

| id | name | display name | is_system | is_assignable |
|---|---|---|---|---|
| `20000000-0000-4000-8000-000000000001` | `SUPER_ADMIN` | Super administrator | true | true |
| `20000000-0000-4000-8000-000000000002` | `CCE_ADMIN_SERVICE` | Admin service account | true | false |

`api_permissions`:

| permission_name | method | uri_pattern | category |
|---|---|---|---|
| `SUPER_ADMIN` | GET, POST, PUT, PATCH, DELETE (5 rows) | `/admin/v1/**` | `Admin` |
| `CCE_ADMIN_SERVICE` | POST | `/api/permissions/sync-to-keycloak` | `Gateway` |

No user rows are seeded. The first `SUPER_ADMIN` user is created at startup by
`BootstrapAdminRunner` (see [flow-diagrams.md](flow-diagrams.md#bootstrap-of-the-first-super_admin)).
