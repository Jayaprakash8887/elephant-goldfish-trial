# Drift report: docs/designs/ against cce-admin-service/ and cce-admin-ui/ (2026-11-10)

Scope: the two designs in `docs/designs/`. `author-decisions.md` and `SITEMAP.md` are not designs
and were used only as context. Code compared: `cce-admin-service/` (including `gateway-stub/`,
`scripts/`, `keycloak/`, `docker-compose.yml`) and `cce-admin-ui/` (including `e2e/`), at commit
`316df44`.

Each design section has two parts:

- **Marked (`DESIGN-GAP:`)**: every place where the implementing agent recorded a departure from the
  design, or something it had to fill in. There are 82 markers in tracked files: 28 in the service
  and 54 in the console. The 83rd match is in the git-ignored local `cce-admin-service/.env`, which
  is a copy of the `.env.example` header, so it is counted once. `AccountLinker.java:50` only
  points to the marker at line 167.
- **Unmarked**: drift that no marker records, found by reading the design section by section and
  checking each named item in the code. Each finding was checked against the cited lines.

Not checked: the runtime behaviour that needs the Docker stack (the token claims, Mailpit, the
`--wait` health checks and the E2E runs), and individual test assertions beyond the test names and
spot reads.

## cce-admin-service.md

**Marked (`DESIGN-GAP:`)**

- §9.1 Containers: the design gives the health-check commands but not their interval, timeout,
  retries or start_period. The values were chosen so that `docker compose up -d --wait` survives
  the first Keycloak start with a realm import. `docker-compose.yml:5`
- §9.1: "Database keycloak on postgres" does not name the database user. Keycloak connects as
  `admin_service`, which owns the database. `docker-compose.yml:37`
- Phase 1 / §9.3: ".env.example holds every variable in section 9.3", but §9.3 also lists the
  console's `VITE_*` values. Only the two service groups are in `.env.example`. `.env.example:5`
- Phase 1 exit check step 3 expects `auth_time` in the admin token. Keycloak 26.4.7 does not set it
  for the password grant the script has to use, so `token-claims.mjs` prints "(absent)".
  `scripts/token-claims.mjs:11`
- Phase 1: the design does not say which directory `token-claims.mjs` reads `.env` from. It reads
  the `.env` next to `docker-compose.yml`. `scripts/token-claims.mjs:28`
- Phase 4, stand-in: the parent pom's shade setup writes `gateway-stub/dependency-reduced-pom.xml`.
  The marker says the file is a build output that must not be committed. `gateway-stub/pom.xml:48`
  (The marker is out of date; see Unmarked.)
- §6 Data dictionary: the design names the unique constraints, the indexes, `ck_menus_parent` and
  the two triggers. It does not name the primary keys, the foreign keys, the other CHECK
  constraints, or the function behind `trg_audit_log_append_only`. Those names were chosen in the
  code (`ck_<table>_<column>`, `fk_<table>_<column>`, `<table>_pkey`,
  `audit_log_append_only()`). `V1__schema.sql:4`
- §4.4 Menu fields: `icon` has a pattern but no length. `@Size(max = 100)` was added to match the
  `varchar(100)` column, on both the create and the update request. `MenuDtos.java:50`, `:66`
- §4.4: the order of `RoleMenus.menuIds` is not specified. The ids are sorted by their string
  form. `RoleMenuService.java:136`
- §4.1 Times: the design allows at most 6 fractional digits but does not say how an entity that
  was not read back gets there. Every `User` is truncated to microseconds, and responses are read
  back after the commit. `UserViews.java:39`
- §4.1 / §4.2 errors: an unsupported content type gives a `VALIDATION_ERROR` whose single detail
  has the invented field `body`. `ApiExceptionHandler.java:111`
- §4.1: a class-level (whole-body) constraint error also uses the field `body`.
  `ApiExceptionHandler.java:150`
- §2 / Phase 3: the OpenAPI title and version are not specified. The code uses
  `"cce-admin-service"` and `"v1"`. `OpenApiConfig.java:19`
- "How to use this plan" says each database step commits before the next Keycloak call. §5
  ("Locks") says flows 5.2 to 5.4 hold one transaction with a `FOR UPDATE` lock across the Keycloak
  calls. The code follows §5 for 5.2 to 5.4, and 5.1 and 5.5 commit each step. These two parts of
  the design contradict each other. `UserService.java:57`
- §5.1 step 3 and §5.4 step 3: the Keycloak lookups have no step names. The code adds
  `KEYCLOAK_ACCOUNTS_CHECKED` and `KEYCLOAK_REALM_ROLES_CHECKED` for the DEV-8 502 path.
  `UserService.java:89`
- §5.1: the design does not say where a linked account's local names come from. They are taken
  from the request, and Keycloak's own names are left unchanged. `UserService.java:191`
- §5.2: `KeycloakGateway.updateProfile` always writes `emailVerified`. When the email is
  unchanged, the current flag is read first so it is kept. `UserService.java:335`
- §4.2 `partialState.keycloakUserId`: it is set to the user's Keycloak id on every 502 after a
  Keycloak write in 5.2 to 5.4, and to null for a lookup that failed before any write.
  `UserService.java:604`
- §4.2 / §5.6 / §5.9: a Keycloak failure in the role-create realm-role check or the role-delete
  member check has no operation or step names. The code adds `ROLE_CREATE` /
  `KEYCLOAK_REALM_ROLE_CHECKED` and the matching delete names, and returns a 502 with
  `completedSteps []` and no audit row. `RoleService.java:52`
- §8 Audit: the design gives `ROLE_CREATED`'s after-JSON as "Role and its implicit rows" without
  naming the property. The code puts the rows under `implicit`. `RoleService.java:149`
- §5.13 Bootstrap: `reactivateAsAdmin` has no operation or step names. A database failure after the
  mapping was added is recorded under audit rule 3 with flow 5.4's `USER_ROLES_UPDATE` /
  `KEYCLOAK_ROLES_ADDED`. `AccountLinker.java:167` (constants at `:50`)
- §8: there is no after-JSON for a `USER_CREATE` failure row. The code records the intended user
  plus `linked`. `AccountLinker.java:189`
- §5.10 Gateway sync: the error text format is not specified. The code writes `HTTP <status>:
  <body cut to 500>`. `HttpGatewaySyncClient.java:54`
- Phase 3 tests: the error-shape test needs endpoints that take a body and a path id, and Phase 3
  builds none. A test-only `ShapeController` under `/admin/v1/test-shapes` was added.
  `ApiErrorShapeWebTest.java:50`
- Phase 8 E2E naming: there is one name pattern per scenario, but S1, S2, S4 and S6 need several
  roles or users. The code adds suffixed labels (`S1A`, `S1B`). `E2eEnvironment.java:21`
- Phase 8: the E2E clients have no database access in the design, but S2, S3 and S6 check audit
  rows and there is no audit read endpoint. The rows are read over plain JDBC with the `.env`
  `DATABASE_*` values. `E2eClients.java:227`

**Unmarked**

- Phase 4: the marker at `gateway-stub/pom.xml:48-51` says "no .gitignore covers"
  `dependency-reduced-pom.xml` and that no `.gitignore` change is allowed. The root `.gitignore`
  (last two lines) now ignores that exact file, with the comment "DESIGN-GAP, phase 4". The marker
  is out of date.
- §5.1 and §7 `findUserByUsername` ("Exact lookup", "exactly this username and this email"): the
  linking match uses `equalsIgnoreCase` for both the username and the email.
  `UserService.java:179-180`, `:234`, `:241`; `KeycloakAdminClientGateway.java:78`
- §5.1 step 5 ("add all the realm role mappings in one call"): `addRealmRoles` and
  `removeRealmRoles` first list every realm role, then add or remove, which is two calls. `toInfo`
  also makes one role-mapping call for each user it finds. `KeycloakAdminClientGateway.java:116-141`,
  `:194-198`
- §5.6 / §5.9, the DEV-8 502 path: when the role-create or role-delete Keycloak check fails, the
  cause exception is dropped (not logged and not attached to the exception).
  `UserService.nothingChanged` passes the cause along, so the two services behave differently.
  `RoleService.java:125-129`, `:204-208`; compare `UserService.java:174-175`
- §4.1 Times: `Role`, `RolePermissions` and `RoleSyncResult` are also truncated to microseconds,
  which the `UserViews` marker does not cover. `RoleService.java:281-287`, used at
  `RolePermissionService.java:134`, `:146`
- §8 audit writer: the design says `recordInNewTransaction` is used for rules 2 and 3. For rule 2
  (each sync attempt), `GatewaySyncService` calls `record()` inside its own `REQUIRES_NEW`
  `TransactionTemplate`, so the sync-result update and the audit row commit together.
  `GatewaySyncService.java:42-43`, `:76-80`
- §8, not in the design: `AuditLogEntity` is annotated `@Immutable`. `AuditLogEntity.java:25`
- §6, migration V2: the check also fails when `api_permissions` has any column the DDL does not
  list. §6 lists only per-column, unique-constraint and index checks. If the gateway team adds a
  column, this service would stop migrating. `V2__verify_api_permissions.sql:15`, `:38-40`
- §3 step 1, not in the design: a custom `BearerTokenResolver` ignores the `Authorization` header
  on public paths, so `GET /health` with a bad token returns 200. `SecurityConfig.java:66`,
  `:103-106`
- §3 step 4, not in the design: a token whose `sub` is not a UUID gets 403 "no local user" without
  a lookup. `CurrentUserFilter.java:71-74`, `:88-97`
- §3 step 5: the permission check matches `request.getRequestURI()`, the raw path, which is not
  percent-decoded and includes any context path. The design specifies the URI path without the
  query string, and the two differ for encoded characters. `PermissionCheckFilter.java:52`
- §9.4 / B-13 configuration: only `admin.*` values fail with a `set <VAR>` message.
  A missing `DATABASE_URL`, `DATABASE_USERNAME` or `DATABASE_PASSWORD` binds as an empty string
  and fails with the datasource's own error, which does not name the variable. The service README
  says every missing variable is named. `AdminProperties.java:25-44`, `application.yml:5-7`,
  `README.md:150-152`
- §9.2 realm, not in the design: the test administrator also holds `default-roles-cce`. §9.2
  lists only `SUPER_ADMIN`. `keycloak/realm-cce.json:167`
- §9.1 stand-in, not in the design: the stand-in has responses beyond the three §9.1 describes
  (503, 401, 200):
  - A non-POST request returns 405, and this is checked before fail mode, so a GET in fail mode
    returns 405 rather than 503.
  - A database or Keycloak error returns 500.
  - A sub-path returns 404.

  `gateway-stub/.../SyncHandler.java:41-43`, `:64-67`; `GatewayStubMain.java:62-66`
- §10 test layers: the design places the 5.4 role diff, the `uriPattern` rules and the field
  validators in the Unit layer. None has its own unit test. They are tested through
  `UserRolesFlowTest`, `RolePermissionServiceTest` and the MockMvc contract tests, and the role
  diff is inline in `UserService.java:409-416`.

## cce-admin-ui.md

**Marked (`DESIGN-GAP:`)**

- Item 22 / item 10: `@import "tailwindcss";` is written with single quotes because Prettier's
  `singleQuote` requires it. `src/index.css:1`
- §Console-only rules: the design says page sizes are 20, 50 and 100 but not which module holds
  the list. `PAGE_SIZES` lives in `schemas/rules.ts`, because components may not import hooks.
  `src/schemas/rules.ts:14`
- §Form validation: client-side validation message texts are not given. The code writes its own
  for roles, users, menus and permission rows. `roleSchemas.ts:12`, `userSchemas.ts:17`,
  `menuSchemas.ts:19`, `permissionSchemas.ts:31`
- §Form validation: the duplicate-rule message is not given. The code uses "Another rule has the
  same method and URI pattern." `permissionSchemas.ts:64`
- API client: the design says a missing token "calls login() and throws a 401 ApiError" but names
  no code. The code uses `UNAUTHENTICATED`. `src/api/client.ts:75`
- API client: the design does not cover a paginated response without `pagination`. It is treated
  as `CLIENT_UNEXPECTED_RESPONSE`. `src/api/roles.ts:13`
- `describeError`: the design does not say how "no server message" is marked. An empty string is
  used. `src/utils/describeError.ts:22`
- `describeError`: non-`ApiError` values get no status or code in the design. The code uses status
  0 and `CLIENT_UNEXPECTED_RESPONSE`. `describeError.ts:71`
- `formErrors`: "the other messages" are not defined for kinds other than validation and conflict.
  Nothing is returned for them. `src/utils/formErrors.ts:48`
- `formErrors`: a detail whose field is not `roleIds[i]` for an index that was sent shows the
  server message. `formErrors.ts:70`
- Item 59 `InlineError`: the kinds that have no fixed text (syncFailed, validation, conflict,
  notFound) show only the server message. `InlineError.tsx:19`
- Item 59: the "result is unknown" text is limited to `mutation` together with network or
  unexpected errors. The item and the error-presentation section disagree on this.
  `InlineError.tsx:41`
- Item 58 / item 81: the layout's loading spinner sits in the top bar before `Header`, because
  `Header` has no loading slot. `src/pages/AppLayout.tsx:19`
- `useMenus`: the design does not say what happens when an edited entry is missing from the
  re-read tree. The hook throws a client-side 404 `MENU_NOT_FOUND` and sends no PUT.
  `src/hooks/useMenus.ts:32`
- Item 80 `MenuForm`: the panel heading is not given. The code uses "Edit {label}", "Add detail
  under {parent}" and "Add menu". `MenuForm.tsx:67`
- `MenuForm`: on create, `type` is a select that defaults to the button's type. Switching to NAV
  clears the parent, and the sort order is not recomputed. `MenuForm.tsx:197`
- `MenuForm`: the button labels "Save" and "Cancel" are invented. `MenuForm.tsx:249`
- `MenusPage`: "Menu saved." is also shown after a deactivate or activate. `MenusPage.tsx:62`
- `MenusPage`: the empty-catalog text "No menus yet." is invented. `MenusPage.tsx:108`
- `SyncFailedNotice`: the id labels "Keycloak user id:" and "Audit log id:" are invented.
  `SyncFailedNotice.tsx:56`
- `SyncFailedNotice`: the `USER_CREATE` users-list link text is "Back to users".
  `SyncFailedNotice.tsx:78`
- Role detail: the "System roles cannot be changed." note is shown once, in the header above the
  tabs. `RoleDisplayForm.tsx:78`
- Item 72 `RoleCreateForm`: the props `pending` and `serverError` were added. `RoleCreateForm.tsx:28`
- `RoleCreateForm`: the submit label "Create role" is invented. `RoleCreateForm.tsx:108`
- Item 75 `PermissionsEditor`: the prop `onDirtyChange` was added so the tab can use
  `useUnsavedChangesGuard` (item 89). `PermissionsEditor.tsx:41`
- `PermissionsEditor`: the implicit-rows section appears only when there are rows (a system role
  has none). The section and column titles are invented. `PermissionsEditor.tsx:106`
- `PermissionsEditor`: the per-row input and remove labels are invented ("URI pattern, row 2").
  `PermissionsEditor.tsx:131`
- `PermissionsEditor`: the add-button label "Add rule" is invented. `PermissionsEditor.tsx:211`
- Item 88 `RoleMenusTab`: after a save, the selection is reset from the 200 response's `menuIds`.
  `RoleMenusTab.tsx:103`
- `RolePermissionsTab`: when the refetch after a save fails, the editor is not remounted and the
  failed read is shown with Retry. `RolePermissionsTab.tsx:83`
- `RoleDetailPage`: the user-count text "Users: {n}" is invented. `RoleDetailPage.tsx:68`
- `RolesListPage`: the out-of-range page correction specified for Users is also applied to Roles.
  `RolesListPage.tsx:39`
- `RolesListPage`: the empty-page text "No roles on this page." is invented.
  `RolesListPage.tsx:79`
- `UsersListPage`: an invalid `status` in the URL is not sent and the filter shows All.
  `UsersListPage.tsx:35`
- Item 83: each filter and `page=1` are written in one `setSearchParams` update instead of a
  separate `resetPage()` call. `UsersListPage.tsx:64`
- `UsersListPage`: a "New user" link to `/users/new` was added, because the design gives no way to
  reach the route. `UsersListPage.tsx:161`
- `UsersListPage`: the row-action result area shows the username above the result.
  `UsersListPage.tsx:178`
- `UsersListPage`: the empty-page text "No users on this page." is invented.
  `UsersListPage.tsx:193`
- `UserFilters`: the labels "Status", "Role", "Facility scope" and "Search" are invented.
  `UserFilters.tsx:28`
- `RoleCheckboxList`: the legend "Roles" is invented. `RoleCheckboxList.tsx:39`
- `RoleCheckboxList`: a held role beyond the first 500 is shown as "Role {id}".
  `RoleCheckboxList.tsx:65` (The reason the marker gives is wrong; see Unmarked.)
- Item 69 / item 84 `UserCreateForm`: the form builds the `CreateUserRequest`, not the page. The two
  items contradict each other. `UserCreateForm.tsx:23`
- Item 69: the prop `rolesNotice` was added so a `GET /roles` failure shows at the role list.
  `UserCreateForm.tsx:39`
- `UserCreateForm`: the field labels and the "Create user" button text are invented.
  `UserCreateForm.tsx:105`
- `UserRolesForm`: the same `rolesNotice` prop was added. `UserRolesForm.tsx:34`
- `UserProfileForm`: the buttons are labelled "Save profile" and "Save roles" to tell them apart.
  `UserProfileForm.tsx:97`
- `UserCreatePage`: the page title "New user" is invented. `UserCreatePage.tsx:52`
- `UserEditPage`: the header text "Last login: {time}" / "Never" is invented.
  `UserEditPage.tsx:109`
- `Pager`: the "Page size" label is invented. `Pager.tsx:40`
- Test mocks: `handlers.ts` and the fixtures are created in full, not grown step by step.
  `src/test/mocks/handlers.ts:6`
- Item 100, E2E fixtures: role display names and user first and last names are invented
  (`E2E {STEM} {suffix}`). `e2e/fixtures.ts:20`

**Unmarked**

- §Error presentation ("For a failed query, `network` and `unexpected` errors show a Retry
  button"): every query error passes `onRetry`, whatever its kind, and `InlineError` shows Retry
  whenever `onRetry` is set. So 403, 401 and non-detail not-found errors also get Retry.
  `UsersListPage.tsx:189`, `RolesListPage.tsx:75`, `MenusPage.tsx:106`, `RoleDetailPage.tsx:41`,
  `UserEditPage.tsx:60`, `:66`, `:100`, `UserCreatePage.tsx:47`, `RoleMenusTab.tsx:46-51`,
  `RolePermissionsTab.tsx:120`, `:136`
- Permissions tab / `SyncFailedNotice` (the rules editor resets only through `resetKey`):
  `RoleDetailPage` treats any `role.isError` as a full-page error. In TanStack Query 5 that
  includes a failed background refetch, for example after the refetch rule invalidates the role.
  The page would then unmount both tabs, losing unsaved permission edits and any shown
  `SyncFailedNotice`. `UserEditPage` (`isRefetchError`, `:49`, `:65`) and `RolePermissionsTab`
  (`isLoadingError`, `:119`) handle this case. `RoleDetailPage.tsx:29-42`
- Item 88, Menus tab: likewise, a refetch error on `GET /menus` or `GET /roles/:id/menus`
  unmounts the selection editor, and the unsaved selection is lost when it remounts.
  `RoleMenusTab.tsx:41-57`
- Item 80 (the form keeps its values): `MenusPage` renders `MenuForm` only while
  `tree.isSuccess`, so a failed tree refetch closes the open form and drops its values.
  `MenusPage.tsx:85`
- Item 68, user Roles section: the marker at `RoleCheckboxList.tsx:65` says a role beyond the
  first 500 is shown as "Role {id}" because "its name is not loaded". On User edit the names are
  loaded: `user.roles` carries `{ id, name, displayName }` (`UserRoleRef`). `UserRolesForm` does not
  pass them to `RoleCheckboxList`. `UserRolesForm.tsx:106-115`, `RoleCheckboxList.tsx:64-68`
- Items 57 to 80, component exports the design does not describe, which other modules depend on:
  - `Field` in `RoleCreateForm.tsx:135`, used by four other forms.
  - `STATUS_ACTIONS`, `otherStatuses`, `statusChangedText`, `StatusConfirmDialog` and
    `UserActionError` in `UserStatusActions.tsx:14-75`.
  - `SEND_PASSWORD_LABEL`, `passwordSentText` and `PasswordConfirmDialog` in
    `UserPasswordAction.tsx:11-39`.
  - `STATUS_TEXT` in `StatusBadge.tsx:4`.
  - `ICON_NAMES`, `isKnownIcon`, `FallbackIcon` and `IconComponent` in `iconMap.ts:17-39`.
  - `MenuFormTarget` and the full `MenuForm` props (`tree`, `target`, `onCreate`, `onUpdate`,
    `onCancel`, `pending`, `serverError`) in `MenuForm.tsx:19`, `:72-88`. Item 80 lists no props.
- Item 25, not in the design: `logout()` also stops the token refresh timer.
  `src/auth/keycloak.ts:63`
- Item 7: `process.env.TZ = 'UTC'` is meant to be the first statement of `vitest.config.ts`. It
  comes after the imports, which has no effect at runtime because ES imports are hoisted.
  `vitest.config.ts:1-6`
- Item 102, CUJ 3 browser test: the test checks that the warning codes equal
  `['DETAIL_WITHOUT_PARENT']` and that a text is visible. That text is the same as the client-side
  warning shown before saving. The test does not check that the server warning names Patient
  detail and Patients (`menuId` / `parentMenuId`). `e2e/cuj3-configure-role.spec.ts:8`, `:32`,
  `:40-43`
