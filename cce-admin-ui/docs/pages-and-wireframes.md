# Pages and wireframes

The wireframes below are drawn from the components. They show layout and texts, not styling.
The sample rows are illustrative, loosely based on the test fixtures in
[`src/test/mocks/fixtures.ts`](../src/test/mocks/fixtures.ts).
Every timestamp is shown as `yyyy-MM-dd HH:mm` in the browser's local time
([`formatInstant`](../src/utils/format.ts)). When there is no timestamp, it shows "Never".

## Routes

Defined in [`src/routes.tsx`](../src/routes.tsx). All routes render inside `AppLayout`.

| Path                                                    | Page component                                      | Purpose                                      |
| ------------------------------------------------------- | --------------------------------------------------- | -------------------------------------------- |
| `/`                                                     | `<Navigate to="/users" replace>`                    | Redirect                                     |
| `/users`                                                | [`UsersListPage`](../src/pages/UsersListPage.tsx)   | Paged, filtered user list with row actions   |
| `/users/new`                                            | [`UserCreatePage`](../src/pages/UserCreatePage.tsx) | Create a user                                |
| `/users/:id`                                            | [`UserEditPage`](../src/pages/UserEditPage.tsx)     | Profile, roles, status, password             |
| `/roles`                                                | [`RolesListPage`](../src/pages/RolesListPage.tsx)   | Paged role list, inline create, delete       |
| `/roles/:id` (`?tab=menus` default, `?tab=permissions`) | [`RoleDetailPage`](../src/pages/RoleDetailPage.tsx) | Role header form, Menus tab, Permissions tab |
| `/menus`                                                | [`MenusPage`](../src/pages/MenusPage.tsx)           | Menus catalog                                |
| `*`                                                     | [`NotFoundPage`](../src/pages/NotFoundPage.tsx)     | "Page not found" with "Go to users"          |

[`StartupErrorScreen`](../src/pages/StartupErrorScreen.tsx) is not a route. `bootstrap` renders
it when the configuration is missing ("The console cannot start" / "Configuration error:
VITE_KEYCLOAK_REALM is not set.") or sign-in fails ("Sign-in failed" / "Sign-in did not complete
with the sign-in service at {keycloakUrl}.").

## Permission-based visibility

The console hides nothing based on the signed-in user's roles. The sidebar always shows Users,
Roles and Menus. The header lists the caller's role names from `GET /me`, but no code uses them
to show or hide anything. Authorization is left to the service (and the gateway in a
deployment). A refused call shows "You do not have permission to do this." where the content
would be.

Visibility and enabled state depend on the data only:

| Condition                      | Effect                                                                                                                                                                                                                                             |
| ------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `role.isSystem`                | Lock icon next to the name. No Delete on the Roles list. On Role detail, the display form, the Menus tab checkboxes and Save, and the Permissions tab editing and Save are disabled, with "System roles cannot be changed." Re-sync stays enabled. |
| `role.userCount > 0`           | Delete is disabled, with "Assigned to {n} users".                                                                                                                                                                                                  |
| `role.isAssignable === false`  | Badge "Service account, cannot be assigned" on the Roles list. No checkbox in the role pickers and not in the Users role filter. A user holding it sees it under "Also held:".                                                                     |
| `role.syncStatus !== 'SYNCED'` | A sync badge next to the role's checkbox. This is a hint only; the service decides.                                                                                                                                                                |
| `user.status`                  | The status buttons offer only the statuses the user does not have (Activate, Deactivate, Lock).                                                                                                                                                    |
| `menu.type === 'NAV'`          | "Add detail" on the row.                                                                                                                                                                                                                           |

## Shell (`AppLayout`)

[`AppLayout`](../src/pages/AppLayout.tsx), [`Sidebar`](../src/components/layout/Sidebar.tsx),
[`Header`](../src/components/layout/Header.tsx).

- The header shows a spinner while `GET /me` loads. Then it shows the username and the comma-joined
  role names. If `/me` fails, an `InlineError` (no Retry) takes their place. Pages keep working.
- "Sign out" calls `logout()` and returns to the console root, which starts a new sign-in.

```text
+----------------+-----------------------------------------------------------+
| CCE Admin      |                                  cce-admin                |
|                |                                  SUPER_ADMIN   [Sign out] |
| [*] Users      +-----------------------------------------------------------+
| [ ] Roles      |                                                           |
| [ ] Menus      |   <page content (Outlet)>                                 |
|                |                                                           |
+----------------+-----------------------------------------------------------+
```

## Users list (`/users`)

Shows one page of users. Filters and paging are in the URL: `status`, `roleId`,
`facilityScope`, `q`, `page`, `pageSize`.

- **Filters** ([`UserFilters`](../src/components/users/UserFilters.tsx)):
  - Status: All / Active / Inactive / Locked.
  - Role: All, then the assignable roles by display name. Shows "Showing the first 500 roles."
    when truncated.
  - Facility scope: exact match, max 50 characters.
  - Search: max 100 characters.
  - The two text inputs write to the URL 300 ms after the last keystroke. Every filter change
    sets `page=1`.
- **Table** ([`UserTable`](../src/components/users/UserTable.tsx)): Username, Email, Status
  badge, Roles (display names), Facility scope ("—" when empty), Last login, Actions.
- **Row actions**:
  - "View / edit" opens the user.
  - One button per other status (Activate / Deactivate / Lock), and "Send set-password email".
  - Each opens a dialog. Status: "{Action} {username}?" with the effect text. Password: "Send a
    set-password email to {username}?" / "Keycloak sends an email to {email} with a link to set a
    new password." / [Send].
  - The result appears in the "Row action result" area above the table: the username, then
    "{username} is now Active." (or Inactive / Locked), "Set-password email sent to {email}.", or
    the error (`SyncFailedNotice` for a 502).
  - The row's buttons are disabled while its action runs.
- **Header action**: "New user" link to `/users/new`.
- **Pager** ([`Pager`](../src/components/shared/Pager.tsx)): Previous / "Page {n} of {total} ·
  {items} items" / Next, and Page size 20, 50 or 100.

States:

| State                              | What shows                                                                                                                                                 |
| ---------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| First load                         | `LoadingSpinner` ("Loading…") in place of the table. Later page and filter changes keep the previous rows until the new page arrives (`keepPreviousData`). |
| Error                              | `InlineError` with Retry                                                                                                                                   |
| Empty page                         | "No users on this page." and the Pager                                                                                                                     |
| Invalid `page` / `pageSize` in URL | Replaced with 1 / 20 (URL rewritten with `replace`) before the request                                                                                     |
| Invalid `status` in URL            | Not sent. The filter shows All.                                                                                                                            |
| Past the last page                 | An empty page with `page > totalPages >= 1` jumps to `totalPages`                                                                                          |
| Role list fails to load            | The role filter shows only "All". No error is shown on this page.                                                                                          |

```text
Users                                                              [New user]
Status [All      v]  Role [All          v]  Facility scope [      ]  Search [        ]

 jdoe                                       <- row action result area (when present)
 jdoe is now Inactive.

| Username | Email          | Status   | Roles        | Facility scope | Last login       | Actions                                                   |
|----------|----------------|----------|--------------|----------------|------------------|-----------------------------------------------------------|
| jdoe     | jdoe@cce.local | Inactive | Facility Adm | FAC-01         | 2026-11-10 09:15 | View / edit [Activate] [Lock] [Send set-password email]   |
| asmith   | as@cce.local   | Active   | Super Admin  | —              | Never            | View / edit [Deactivate] [Lock] [Send set-password email] |

[Previous]  Page 1 of 2 · 25 items  [Next]   Page size [20 v]
```

Differs from the design (Users list): the "New user" link, the empty-page text, the filter
labels, and the username line above the row result are additions the design does not specify.
The code marks each with `DESIGN-GAP`.

## User create (`/users/new`)

Title "New user". [`UserCreateForm`](../src/components/users/UserCreateForm.tsx) has these fields:
Username, Email, First name, Last name, Facility scope (optional), and Roles. The Roles field
([`RoleCheckboxList`](../src/components/users/RoleCheckboxList.tsx)) has one checkbox per
assignable role, labelled "{displayName} ({name})", with a sync badge when the role is not
synced. Client rules: at least 1 and at most 20 roles, plus the field patterns in
[`userSchemas.ts`](../src/schemas/userSchemas.ts).

- Submit sends one `POST /users`. On 201 the page navigates to `/users/{id}`. User edit then shows
  "User created. A set-password email was sent to {email}.", or the `ACTION_EMAIL_NOT_SENT`
  warning.
- On a 502 `USER_CREATE`, a `SyncFailedNotice` with "Back to users" appears above the form. The
  form keeps its values, so you can submit again.
- Field errors, conflicts (`USERNAME_IN_USE`, `EMAIL_IN_USE`) and role errors (`ROLE_NOT_SYNCED`,
  `ROLE_NOT_ASSIGNABLE`) show on the form. "Create user" is disabled while the request runs.
- While roles load, a spinner replaces the checkboxes. If they fail, an `InlineError` with Retry
  replaces them.
- There is no Cancel button and no unsaved-changes guard.

```text
New user
+-------------------------------------------------------------------------+
| ! <server message>                         (SyncFailedNotice, on 502)    |
|   The sign-in account may exist in Keycloak ... pass the ids below ...   |
|   For the operator:  • Keycloak user id: ...  • Audit log id: ...        |
|   Back to users                                                          |
+-------------------------------------------------------------------------+
+-------------------------------------------------------------------------+
| Username [              ]        Email [                      ]          |
| First name [            ]        Last name [                  ]          |
| Facility scope [        ]                                                |
| Roles                                                                    |
|  [ ] Facility Admin (FACILITY_ADMIN)                                     |
|  [ ] New Role (NEW_ROLE)  [Never synced]                                 |
|  Not synced yet: New Role (NEW_ROLE). Open the role and use Re-sync.     |
| [Create user]                                                            |
+-------------------------------------------------------------------------+
```

## User edit (`/users/:id`)

A header, then four independent sections. Each section has its own mutation, pending state and
error area.

- **Header**: the username, a status badge, and "Last login: {time}" or "Never". After a create
  it also shows the created notice or warning.
- **Profile** ([`UserProfileForm`](../src/components/users/UserProfileForm.tsx)): Email, First
  name, Last name, Facility scope. [Save profile] sends all four fields; an empty scope is sent as
  `null`. On success: "Profile saved."
- **Roles** ([`UserRolesForm`](../src/components/users/UserRolesForm.tsx)): the same checkbox
  list (0 to 20 roles). Held roles without a checkbox appear under "Also held:" and are always
  sent. A held role outside the first 500 is shown as "Role {id}". [Save roles] is enabled only
  when the selection differs from the last saved set. On success: "Roles saved."
- **Status** ([`UserStatusActions`](../src/components/users/UserStatusActions.tsx)): buttons
  for the other statuses, each behind a dialog. On success: "{username} is now {Status}."
- **Password** ([`UserPasswordAction`](../src/components/users/UserPasswordAction.tsx)): [Send
  set-password email] behind a dialog. On success: "Set-password email sent to {email}."

States:

| State                   | What shows                                                                |
| ----------------------- | ------------------------------------------------------------------------- |
| Loading                 | `LoadingSpinner`                                                          |
| Unknown or malformed id | `NotFoundPanel` "This user does not exist." / "Back to users"             |
| Other first-load error  | `InlineError` with Retry                                                  |
| Refetch fails later     | `InlineError` with Retry above the page. The forms and their values stay. |
| 502 on a section        | `SyncFailedNotice` in that section. The form keeps what was sent.         |

```text
jdoe
[Active]  Last login: 2026-11-10 09:15
 User created. A set-password email was sent to jdoe@cce.local.

+- Profile ----------------------------------------------------------------+
| Email [jdoe@cce.local      ]     First name [Jane        ]               |
| Last name [Doe             ]     Facility scope [FAC-01  ]               |
| [Save profile]                                                           |
+--------------------------------------------------------------------------+
+- Roles ------------------------------------------------------------------+
| Roles                                                                    |
|  [x] Facility Admin (FACILITY_ADMIN)                                     |
|  [ ] Super Admin (SUPER_ADMIN)                                           |
| Also held:  • Admin Service (CCE_ADMIN_SERVICE)                          |
| [Save roles]   <LAST_SYSTEM_ADMIN message here>                          |
+--------------------------------------------------------------------------+
+- Status -----------------------------------------------------------------+
| [Deactivate] [Lock]                                                      |
+--------------------------------------------------------------------------+
+- Password ---------------------------------------------------------------+
| [Send set-password email]                                                |
+--------------------------------------------------------------------------+
```

## Roles list (`/roles`)

- **Create role form** ([`RoleCreateForm`](../src/components/roles/RoleCreateForm.tsx)) above
  the table: Name, Display name, Description.
  - The Name field shows "The name cannot be changed later. It is also the Keycloak realm role
    name and the gateway permission name." It is validated as you type (`mode: 'onChange'`).
  - [Create role] opens the dialog "Create {name}?" with the same warning and [Create]. The
    request is sent only after Create.
  - On 201 the page goes to `/roles/{id}`. If the response has `ROLE_SYNC_FAILED`, it goes to
    `/roles/{id}?tab=permissions` instead.
  - `DUPLICATE_ROLE_NAME` shows on the Name field.
- **Table** ([`RoleTable`](../src/components/roles/RoleTable.tsx)): Name (lock icon for system
  roles; badge "Service account, cannot be assigned"), Display name, Description, Users, Sync
  badge, Actions.
- **Actions**: "Open". "Delete" for non-system roles; it is disabled with "Assigned to {n} users"
  when `userCount > 0`. Delete opens the dialog "Delete {name}?": "The role, its menu grants and
  its permission rules are deleted. The name cannot be reused until a later gateway sync removes
  its realm role."
  - A delete error (for example `ROLE_IN_USE`) shows in the area above the table.
  - A successful delete shows no message. The list refetches.
- **Paging**: `page` and `pageSize` in the URL, same Pager as Users. A page past the end moves
  to the last page.

States: `LoadingSpinner`; `InlineError` with Retry; "No roles on this page." with the Pager.

```text
Roles
+- Create role ------------------------------------------------------------+
| Name [FACILITY_ADMIN ]     Display name [            ]  Description [   ] |
| The name cannot be changed later. It is also the Keycloak realm role ... |
| [Create role]                                                            |
+--------------------------------------------------------------------------+
 <delete error area>
| Name                    | Display name   | Description | Users | Sync                     | Actions                           |
|-------------------------|----------------|-------------|-------|--------------------------|-----------------------------------|
| CCE_ADMIN_SERVICE (lock)| Admin Service  | ...         | 0     | Synced · 2026-11-10 09:20| Open                              |
|  [Service account, cannot be assigned]   |             |       |                          |                                   |
| FACILITY_ADMIN          | Facility Admin | ...         | 3     | Synced · 2026-11-10 09:20| Open [Delete] Assigned to 3 users |
| NEW_ROLE                | New Role       |             | 0     | Never synced             | Open [Delete]                     |
| SUPER_ADMIN (lock)      | Super Admin    | ...         | 1     | Synced · 2026-11-10 09:20| Open                              |
[Previous]  Page 1 of 1 · 4 items  [Next]   Page size [20 v]
```

## Role detail (`/roles/:id`)

- **Header**:
  - The name (monospace, lock icon for system roles), the sync badge, and "Users: {n}".
  - [`RoleDisplayForm`](../src/components/roles/RoleDisplayForm.tsx) with Display name,
    Description and [Save]. It sends `PUT /roles/:id`; an empty description is sent as `null`. On
    success: "Saved."
  - For system roles: "System roles cannot be changed." and the form is disabled.
- **Tabs**: Menus and Permissions. The tab is in `?tab=`, so it can be linked and reloaded. There
  is no Delete here.

States: `LoadingSpinner`; `NotFoundPanel` "This role does not exist." / "Back to roles";
otherwise `InlineError` with Retry.

Differs from the design (Permissions tab, editor reset): `RoleDetailPage` checks `role.isError`.
In TanStack Query 5 that is also true when a background refetch fails, for example the refetch
the refetch rule starts after a save. The whole page, with both tabs, is then replaced by the
error, and unsaved edits and any shown `SyncFailedNotice` are lost. `UserEditPage` handles the
same case with `isRefetchError`.

### Menus tab

[`RoleMenusTab`](../src/pages/role-detail/RoleMenusTab.tsx) with
[`MenuCheckboxTree`](../src/components/roles/MenuCheckboxTree.tsx).

- A two-level checkbox tree from `GET /menus`, ordered by `sortOrder`, then label. Each row shows
  the label, the path, the type, and "Inactive" for inactive menus (they can still be granted).
  The initial selection is `menuIds` from `GET /roles/:id/menus`.
- Checking a parent does not check its children, and the reverse. Each menu is granted on its
  own.
- While editing, the tab warns about each selected DETAIL whose NAV parent is not selected: "{detail}
  is shown only when {parent} is also granted." After a save, the server's
  `DETAIL_WITHOUT_PARENT` warnings replace that list.
- [Save] is enabled only when the selection differs from the last fetched or saved set. It sends
  the complete set. On success: "Menus saved."
- Leaving the page or switching tab while dirty opens "Unsaved changes" / "You have unsaved
  changes. Leave without saving?" [Stay] [Leave].
- States: spinner while either query loads; one `InlineError` with Retry per failed query.

Differs from the design (Menus tab): any error on `GET /menus` or `GET /roles/:id/menus`, including
a background refetch error, unmounts the selection editor. An unsaved selection is lost.

```text
FACILITY_ADMIN
[Synced · 2026-11-10 09:20]  Users: 3
+--------------------------------------------------------------------------+
| Display name [Facility Admin ]   Description [                     ]     |
| [Save]                                                                   |
+--------------------------------------------------------------------------+
 [Menus] [Permissions]
-------------------------------------------------------------------------
 [x] Dashboard          /                      NAV
 [x] Compliance         /compliance            NAV
     [ ] Protocol analytics  /compliance/protocols/:id  DETAIL
 [ ] Patients           /compliance/patients   NAV
     [x] Patient detail      /compliance/patients/:id   DETAIL
 [ ] Exports            /exports               NAV   Inactive
 • Patient detail is shown only when Patients is also granted.
 Menus saved.
 [Save]
```

### Permissions tab

[`RolePermissionsTab`](../src/pages/role-detail/RolePermissionsTab.tsx),
[`SyncStatusPanel`](../src/components/roles/SyncStatusPanel.tsx),
[`PermissionsEditor`](../src/components/roles/PermissionsEditor.tsx).

- **Created-with-sync-warning**: when the page was opened from a create whose sync failed, a
  warning says "The role was created but not synced. Use Re-sync before assigning it."
- **Gateway sync panel**: the badge from `GET /roles/:id/permissions`, "Synced means the
  gateway's sync call succeeded.", and [Re-sync]. Re-sync is also enabled for system roles and
  while there are unsaved edits.
- **Always included**: the implicit rows (for example `GET /admin/v1/me` and
  `GET /admin/v1/me/menus`) in a read-only table, with "Every non-system role keeps these two rules
  so that its users can load their profile and menus." This section is hidden when there are no
  implicit rows. They are never sent.
- **Rules**: the explicit rows, editable. Each row has Method (GET, POST, PUT, PATCH, DELETE), URI
  pattern, Description, Category and [Remove]. [Add rule] adds a GET row.
  - Client rules: at most 200 rows. URI pattern starts with `/`, uses only letters, digits and
    `- . _ ~ / *`, has no `//`, and `**` only as the last segment. A row equal to an implicit
    row gets "Already included for every role". A duplicate method and pattern gets "Another rule
    has the same method and URI pattern."
- [Save] is enabled when the normalized rule set differs from the fetched one. It sends every
  explicit row. On 200 the tab refetches, resets the editor, and shows "Saved.". On a 502 see
  [api-integration.md](api-integration.md#sync-failures-502-keycloak_sync_failed).
- The same unsaved-changes guard as the Menus tab.

States: spinner on first load; `InlineError` with Retry if the first load fails; if a later
refetch fails, an `InlineError` with Retry is added and the editor stays.

```text
 [Menus] [Permissions]
-------------------------------------------------------------------------
 • The role was created but not synced. Use Re-sync before assigning it.
+- Gateway sync -----------------------------------------------------------+
| [Sync failed · 2026-11-10 09:20]  Synced means the gateway's sync call   |
| succeeded.                                                    [Re-sync]  |
+--------------------------------------------------------------------------+
 ! <server message>                              (SyncFailedNotice, on 502)
   The permissions were saved but not synced. Use Re-sync, and do not ...
   [Re-sync]
 Always included
 Every non-system role keeps these two rules so that its users can load ...
 | Method | URI pattern         | Description | Category |
 | GET    | /admin/v1/me        | ...         | ...      |
 | GET    | /admin/v1/me/menus  | ...         | ...      |
 Rules
 | Method  | URI pattern              | Description | Category |          |
 | [GET v] | [/v1/insights/**       ] | [         ] | [      ] | [Remove] |
 | [POST v]| [/v1/exports           ] | [         ] | [      ] | [Remove] |
 [Add rule]
 [Save]
 Saved.
```

## Menus catalog (`/menus`)

[`MenusPage`](../src/pages/MenusPage.tsx), [`MenuTreeTable`](../src/components/menus/MenuTreeTable.tsx),
[`MenuForm`](../src/components/menus/MenuForm.tsx), [`MenuIcon`](../src/components/menus/MenuIcon.tsx).

- **Table**: Label (DETAIL rows indented), Key, Path, Icon, Type, Sort order, Active badge,
  Actions. NAV rows come in `sortOrder`, each followed by its DETAIL children.
  - The Icon column shows the Heroicon and its name. An unknown name shows a question-mark icon
    and the raw name.
- **Actions**:
  - "Add menu" in the header (disabled until the tree has loaded).
  - "Add detail" on NAV rows.
  - "Edit".
  - "Deactivate" or "Activate". These open a dialog: "Deactivate {label}?" / "Roles keep the
    grant, but no console shows this menu while it is inactive." or "Activate {label}?" / "Roles
    that were granted this menu show it again."
- **Form panel** (inline, above the table):
  - Heading: "Add menu", "Add detail under {parent}" or "Edit {label}".
  - Fields: Key, Label, Path, Icon (a select of "No icon", the ten seed icon names, plus the
    current value if it is unknown), Type (a select when creating, read-only when editing),
    Parent (only for DETAIL; inactive NAVs are marked "(inactive)"), Sort order, Active.
  - New entries default to active, with sort order = highest sibling + 10 (10 if none, max
    10000).
  - Key is read-only when editing.
  - [Save] closes the panel and shows "Menu saved." [Cancel] closes it.
  - `DUPLICATE_MENU_KEY` shows on the Key field.
- Deactivate and Activate also show "Menu saved." on success. Errors show in the same result
  area.
- There is no delete.

States: `LoadingSpinner`; `InlineError` with Retry; "No menus yet." when the tree is empty.

Differs from the design (Menus catalog, form keeps its values): the form renders only while the
tree query is in success state. A failed background refetch of `GET /menus` closes an open form
and drops its values, and replaces the table with the error.

```text
Menus                                                              [Add menu]
+- Add detail under Compliance --------------------------------------------+
| Key [compliance.audit   ]       Label [Audit             ]               |
| Path [/compliance/audit ]       Icon [No icon          v]                |
| Type [DETAIL v]                 Parent [Compliance     v]                |
| Sort order [20 ]                [x] Active                               |
| [Save] [Cancel]                                                          |
+--------------------------------------------------------------------------+
 Menu saved.
| Label            | Key                    | Path            | Icon                 | Type   | Sort | Active   | Actions                           |
|------------------|------------------------|-----------------|----------------------|--------|------|----------|-----------------------------------|
| Dashboard        | dashboard              | /               | (icon) ChartBarIcon  | NAV    | 10   | Active   | [Add detail] [Edit] [Deactivate]  |
| Compliance       | compliance             | /compliance     | (icon) ClipboardD... | NAV    | 30   | Active   | [Add detail] [Edit] [Deactivate]  |
|     Protocol an. | compliance.protocol-...| /compliance/... |                      | DETAIL | 10   | Active   | [Edit] [Deactivate]               |
| Exports          | exports                | /exports        | (icon) ArrowDownTr...| NAV    | 100  | Inactive | [Add detail] [Edit] [Activate]    |
```

## Not found (`*`)

"Page not found" with a "Go to users" link. It renders inside the shell.

## Things to know when changing pages

- The Users list text inputs (`q`, `facilityScope`) read the URL only when the page mounts. If
  the URL changes later, for example with browser Back, the list follows the URL but the text
  boxes keep their old text.
- The "created" notices on User edit and the Permissions tab come from history state. They stay
  until you navigate away, also after a successful Re-sync. Switching tab clears the Permissions
  tab warning, because it changes the URL.
