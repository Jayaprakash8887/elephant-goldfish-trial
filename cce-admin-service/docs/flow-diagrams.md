# Flow diagrams

The main write flows, as implemented. Step names in `CAPITALS` are the values used in a 502's
`partialState.failedStep` / `completedSteps`. Common rules:

- **No compensation.** A Keycloak step is never undone after a later step fails. Recovery is to
  submit the same request again: every Keycloak step sets a target state, so repeating it is safe.
  The service itself never retries.
- **Keycloak failures before anything changed** give 502 `KEYCLOAK_SYNC_FAILED` with
  `completedSteps: []` and no audit row.
- **Audit rows** follow the rules in [Audit writing](#audit-writing).

Class shorthand: `UserService` = [`service/UserService.java`](../src/main/java/org/cce/admin/service/UserService.java),
`AccountLinker` = [`service/AccountLinker.java`](../src/main/java/org/cce/admin/service/AccountLinker.java),
`RoleService` = [`service/RoleService.java`](../src/main/java/org/cce/admin/service/RoleService.java),
`RolePermissionService` = [`service/RolePermissionService.java`](../src/main/java/org/cce/admin/service/RolePermissionService.java),
`GatewaySyncService` = [`service/GatewaySyncService.java`](../src/main/java/org/cce/admin/service/GatewaySyncService.java).

## Create user

`POST /admin/v1/users`, operation `USER_CREATE`
([`UserService.create`, lines 153-229](../src/main/java/org/cce/admin/service/UserService.java#L153-L229);
steps 5 and 6 in [`AccountLinker.link`, lines 90-124](../src/main/java/org/cce/admin/service/AccountLinker.java#L90-L124)).

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant S as UserService
    participant L as AccountLinker
    participant K as Keycloak
    participant D as Postgres
    C->>S: POST /users {username, email, names, facilityScope, roleIds}
    S->>D: load roles by id
    Note over S: 400 if an id is unknown or repeated<br/>409 ROLE_NOT_ASSIGNABLE if is_assignable false<br/>new user id = random UUID, email lower-cased
    S->>D: username taken? email taken?
    Note over S: 409 USERNAME_IN_USE / EMAIL_IN_USE
    S->>K: search users by username, by email (exact), merge
    Note over S,K: failure: 502, failedStep KEYCLOAK_ACCOUNTS_CHECKED
    alt one account is enabled, same username and email (ignoring case), not linked locally
        Note over S: linkable, skip step KEYCLOAK_USER_CREATED
    else some account matches username or email but cannot be linked
        S-->>C: 409 USERNAME_IN_USE or EMAIL_IN_USE, message says why
    end
    S->>K: list realm role names
    Note over S,K: failure: 502, failedStep KEYCLOAK_REALM_ROLES_CHECKED<br/>missing realm role: 409 ROLE_NOT_SYNCED
    opt no linkable account
        S->>K: KEYCLOAK_USER_CREATED: create user, enabled, emailVerified false, required action UPDATE_PASSWORD
        Note over S,K: Keycloak 409 (race): look up username, then 409 USERNAME_IN_USE or EMAIL_IN_USE<br/>other failure: 502, completedSteps []
    end
    S->>L: link(account, userId, roles, facilityScope, linkedExisting)
    L->>K: KEYCLOAK_ROLES_ASSIGNED: list realm roles, add all mappings
    L->>D: LOCAL_USER_SAVED (one tx): insert users ACTIVE, user_roles, audit USER_CREATED (after has linked)
    Note over L: failure in either step: 502 with keycloakUserId,<br/>failure row USER_CREATED FAILED if a step had completed
    S->>K: send execute-actions email UPDATE_PASSWORD
    Note over S,K: failure only adds warning ACTION_EMAIL_NOT_SENT
    S-->>C: 201 User, Location /admin/v1/users/{id}
```

Notes:

- **Linking.** If the 502 came after `KEYCLOAK_USER_CREATED`, the Keycloak account exists with no
  local row. Resubmitting the same request finds it, links it, and completes. A linked account's
  local first and last names come from the request; Keycloak's names are left unchanged (a
  `DESIGN-GAP` choice, [`UserService.java:191-196`](../src/main/java/org/cce/admin/service/UserService.java#L191-L196)).
- **The email** is sent by Keycloak through the realm's SMTP server (Mailpit locally), with lifespan
  `KEYCLOAK_ACTION_EMAIL_LIFESPAN` and the optional client id and redirect URI.

Differs from the design (§5.1 step 3, §7 "exactly this username and this email"): the linking
match uses `equalsIgnoreCase` for both fields
([`UserService.java:177-183`](../src/main/java/org/cce/admin/service/UserService.java#L177-L183)).
Differs from the design (§5.1 step 5 "in one call"): `addRealmRoles` lists the realm roles first,
so it is two Keycloak calls.

## User flows that hold a lock (profile, status, roles)

`PUT /users/{id}`, `PATCH /users/{id}/status` and `PUT /users/{id}/roles` share one pattern
([`UserService.run` and `Flow`, lines 585-671](../src/main/java/org/cce/admin/service/UserService.java#L585-L671)):

```mermaid
flowchart TD
    A["Begin transaction"] --> B["SELECT users FOR UPDATE<br/>404 USER_NOT_FOUND"]
    B --> C{"Change needed?"}
    C -- no --> N["Commit, 200 current User, no audit"]
    C -- yes --> D["Checks (409s), maybe lock SUPER_ADMIN row"]
    D --> E["Keycloak step 1..n"]
    E -- "step fails" --> F["Failure row in NEW tx if an earlier step completed<br/>rollback, 502 with keycloakUserId"]
    E -- ok --> G["Local save + success audit row"]
    G -- "save or commit fails after a Keycloak step" --> F
    G -- ok --> H["Commit, read back, 200 User"]
```

The `users` row lock is held while Keycloak is called, so two requests for the same user run one
after the other.

### Update profile

`PUT /users/{id}`, `USER_PROFILE_UPDATE` ([lines 283-332](../src/main/java/org/cce/admin/service/UserService.java#L283-L332)).

1. Lock the user. A changed email (lower-cased) must not belong to another user: 409 `EMAIL_IN_USE`.
2. No-op when email, names and `facilityScope` are all unchanged.
3. `KEYCLOAK_PROFILE_UPDATED`, only when email or a name changed. If the email is unchanged, the
   current `emailVerified` is read from Keycloak first and kept; a changed email sets it `false`.
   A Keycloak 409 gives 409 `EMAIL_IN_USE`.
4. `LOCAL_PROFILE_SAVED`: update the row, audit `USER_UPDATED` (User / User).

A change to `facilityScope` alone makes no Keycloak call.

### Status change

`PATCH /users/{id}/status`, `USER_STATUS_CHANGE`
([lines 348-396](../src/main/java/org/cce/admin/service/UserService.java#L348-L396)).

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant S as UserService
    participant D as Postgres
    participant K as Keycloak
    C->>S: PATCH /users/{id}/status {status}
    S->>D: BEGIN, SELECT users FOR UPDATE
    alt same status
        S-->>C: 200 User (no-op)
    end
    opt user ACTIVE, holds SUPER_ADMIN, target not ACTIVE
        S->>D: SELECT roles SUPER_ADMIN FOR UPDATE, count other ACTIVE holders
        Note over S: 0 others: 409 LAST_SYSTEM_ADMIN
    end
    opt enabled flag changes (ACTIVE to blocked, or blocked to ACTIVE)
        S->>K: KEYCLOAK_ENABLED_CHANGED: set enabled true or false
    end
    opt target is INACTIVE or LOCKED
        S->>K: KEYCLOAK_SESSIONS_ENDED: logout all sessions
    end
    S->>D: LOCAL_STATUS_SAVED: update status, audit USER_ACTIVATED / USER_DEACTIVATED / USER_LOCKED, COMMIT
    S-->>C: 200 User
```

`user_roles` is never touched. `INACTIVE` ↔ `LOCKED` skips `KEYCLOAK_ENABLED_CHANGED` but still
ends sessions. A blocked user's next request gets 403 from `CurrentUserFilter`, even with a token
that is still valid.

### Change user roles

`PUT /users/{id}/roles`, `USER_ROLES_UPDATE`
([lines 401-463](../src/main/java/org/cce/admin/service/UserService.java#L401-L463)).

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant S as UserService
    participant D as Postgres
    participant K as Keycloak
    C->>S: PUT /users/{id}/roles {roleIds}
    S->>D: load roles (400 unknown or repeated id)
    S->>D: BEGIN, SELECT users FOR UPDATE
    S->>S: added = requested minus current, removed = current minus requested
    alt both empty
        S-->>C: 200 User (no-op)
    end
    opt added not empty
        Note over S: 409 ROLE_NOT_ASSIGNABLE for added roles
        S->>K: list realm role names
        Note over S,K: failure: 502 KEYCLOAK_REALM_ROLES_CHECKED<br/>missing: 409 ROLE_NOT_SYNCED
    end
    opt user ACTIVE and SUPER_ADMIN in removed
        S->>D: lock SUPER_ADMIN row, count other ACTIVE holders (409 LAST_SYSTEM_ADMIN)
    end
    opt added not empty
        S->>K: KEYCLOAK_ROLES_ADDED: add mappings for added
    end
    opt removed not empty
        S->>K: KEYCLOAK_ROLES_REMOVED: remove mappings (skip roles with no realm role)
    end
    S->>D: LOCAL_ROLES_SAVED: delete removed, insert added, touch updated_at, audit USER_ROLES_UPDATED, COMMIT
    S-->>C: 200 User
```

Only changed mappings move in Keycloak. Mappings the user holds in Keycloak but not locally are
left alone.

## Password reset email

`POST /users/{id}/reset-password`, `USER_PASSWORD_RESET`
([lines 468-487](../src/main/java/org/cce/admin/service/UserService.java#L468-L487)).

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant S as UserService
    participant D as Postgres
    participant K as Keycloak
    participant M as SMTP (Mailpit)
    C->>S: POST /users/{id}/reset-password
    S->>D: load user (404 USER_NOT_FOUND)
    S->>K: KEYCLOAK_ACTION_EMAIL_SENT: execute-actions email [UPDATE_PASSWORD]
    K->>M: email with action link
    alt Keycloak call fails
        S-->>C: 502 USER_PASSWORD_RESET, completedSteps [], no audit row
    end
    S->>D: one tx: audit USER_PASSWORD_RESET_REQUESTED, after {email, actions}
    S-->>C: 200 {userId, email}
```

Allowed for any status. No lock is taken and no password passes through the service.

## Create, update and delete role

### Create role

`POST /admin/v1/roles` ([`RoleService.create`, lines 111-168](../src/main/java/org/cce/admin/service/RoleService.java#L111-L168)).

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant R as RoleService
    participant D as Postgres
    participant K as Keycloak
    participant G as GatewaySyncService
    C->>R: POST /roles {name, displayName, description}
    R->>D: name in roles? name in api_permissions?
    Note over R: 409 DUPLICATE_ROLE_NAME
    R->>K: find realm role by name
    Note over R,K: exists: 409 DUPLICATE_ROLE_NAME<br/>failure: 502 ROLE_CREATE / KEYCLOAK_REALM_ROLE_CHECKED
    R->>D: one tx: insert role (is_system false, is_assignable true, NEVER),<br/>2 implicit api_permissions rows, audit ROLE_CREATED
    R->>G: sync(roleId)
    Note over G: see Gateway sync procedure
    R->>D: read role back
    alt sync failed
        R-->>C: 201 Role (syncStatus FAILED) + warning ROLE_SYNC_FAILED
    else
        R-->>C: 201 Role (syncStatus SYNCED)
    end
```

A successful create writes two audit rows: `ROLE_CREATED` and `ROLE_SYNC_REQUESTED`. The new role
cannot be assigned to users until its realm role exists (409 `ROLE_NOT_SYNCED`).

On the 502 path, `RoleService` drops the Keycloak exception: it is neither logged nor attached
([lines 125-129](../src/main/java/org/cce/admin/service/RoleService.java#L125-L129)), unlike
`UserService`, which logs it.

### Update role

`PUT /roles/{id}` ([lines 171-189](../src/main/java/org/cce/admin/service/RoleService.java#L171-L189)):
one transaction; 409 `SYSTEM_ROLE_IMMUTABLE` for system roles; no-op if `displayName` and
`description` are unchanged; else save and audit `ROLE_UPDATED`. No Keycloak or gateway call.

### Delete role

`DELETE /roles/{id}` ([lines 192-231](../src/main/java/org/cce/admin/service/RoleService.java#L192-L231)).

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant R as RoleService
    participant D as Postgres
    participant K as Keycloak
    C->>R: DELETE /roles/{id}
    R->>D: load role (404), is_system? (409 SYSTEM_ROLE_IMMUTABLE)
    R->>D: any user_roles row? (409 ROLE_IN_USE)
    R->>K: realm role has a direct user member?
    Note over R,K: yes: 409 ROLE_IN_USE<br/>failure: 502 ROLE_DELETE / KEYCLOAK_ROLE_MEMBERS_CHECKED
    R->>D: one tx: delete role_menus, delete all api_permissions rows for the name,<br/>delete role, audit ROLE_DELETED (before: Role, menuIds, permissions)
    R-->>C: 204
```

No gateway sync runs, so the realm role stays in Keycloak. Its name cannot be reused (the realm
role check in create fails) until a later sync lets the gateway delete it. With the local stub,
any later sync deletes realm roles it tagged `cce-managed` whose name has no rows.

## Change role permissions and gateway sync

`PUT /roles/{id}/permissions`, `ROLE_PERMISSIONS_SAVE`
([`RolePermissionService.replace`, lines 71-123](../src/main/java/org/cce/admin/service/RolePermissionService.java#L71-L123)).

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant P as RolePermissionService
    participant D as Postgres
    participant G as GatewaySyncService
    participant W as Gateway
    C->>P: PUT /roles/{id}/permissions {permissions}
    P->>D: load role (404), is_system? (409)
    P->>P: upper-case methods, check rules, drop implicit rows, reject duplicates (400)
    P->>D: read stored rows
    alt explicit rows unchanged (method, pattern, description, category)
        P-->>C: 200 RolePermissions (no-op, no sync)
    end
    P->>D: PERMISSIONS_SAVED (one tx): bulk delete explicit rows,<br/>insert new rows, re-add missing implicit rows,<br/>audit ROLE_PERMISSIONS_UPDATED {explicit, implicit}
    P->>G: GATEWAY_SYNC
    G->>W: POST sync-to-keycloak (Bearer service token)
    G->>D: new tx: sync_status, last_sync_at, audit ROLE_SYNC_REQUESTED
    alt sync ok
        P-->>C: 200 RolePermissions (syncStatus SYNCED)
    else sync failed
        P-->>C: 502 ROLE_PERMISSIONS_SAVE, failedStep GATEWAY_SYNC,<br/>completedSteps [PERMISSIONS_SAVED], auditLogId = ROLE_SYNC_REQUESTED row
    end
```

On a sync failure the rows stay saved and `syncStatus` is `FAILED`. The 502 body does not contain
the rows; call `GET /roles/{id}/permissions` to show them. To recover, call
`POST /roles/{id}/sync` (re-sync):

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant P as RolePermissionService
    participant G as GatewaySyncService
    C->>P: POST /roles/{id}/sync
    P->>P: load role (404). System roles allowed.
    P->>G: sync(roleId)
    alt ok
        P-->>C: 200 {roleId, syncStatus SYNCED, lastSyncAt}
    else failed
        P-->>C: 502 ROLE_SYNC, failedStep GATEWAY_SYNC, completedSteps [], auditLogId
    end
```

### Gateway sync procedure

[`GatewaySyncService.sync`, lines 65-89](../src/main/java/org/cce/admin/service/GatewaySyncService.java#L65-L89)
and [`HttpGatewaySyncClient.sync`, lines 42-62](../src/main/java/org/cce/admin/client/HttpGatewaySyncClient.java#L42-L62).
Used by role create, permission save and re-sync. It never throws.

```mermaid
flowchart TD
    A["Get service token from Keycloak admin client token manager"] -->|fails| X["SyncResult failed<br/>httpStatus null, error = exception class: message"]
    A --> B["POST GATEWAY_BASE_URL + GATEWAY_SYNC_PATH<br/>Bearer token, no body"]
    B -->|2xx| OK["SyncResult success"]
    B -->|"other status"| F["SyncResult failed<br/>error = HTTP status: first 500 chars of body"]
    B -->|"timeout / connection error"| X
    OK --> T["New tx (REQUIRES_NEW):<br/>UPDATE roles SET sync_status, last_sync_at (updated_at unchanged)<br/>audit ROLE_SYNC_REQUESTED {outcome, httpStatus, errorSummary}"]
    F --> T
    X --> T
    T -->|"write fails"| L["log ERROR, auditLogId null"]
    T --> R["Return Outcome(success, syncStatus, lastSyncAt, auditLogId)"]
    L --> R
```

The gateway sync is a full sync of every `permission_name`, but `sync_status` is updated only on
the role that asked. Other roles' `syncStatus` can therefore be stale.

## Menus and `/me/menus`

### Replace a role's menus

`PUT /roles/{id}/menus` ([`RoleMenuService.replace`, lines 68-123](../src/main/java/org/cce/admin/service/RoleMenuService.java#L68-L123)).

```mermaid
flowchart TD
    A["Load role (404), is_system? (409)"] --> B["Load menus by id<br/>400 on menuIds[i] if unknown or repeated"]
    B --> C["One DETAIL_WITHOUT_PARENT warning per DETAIL<br/>whose parent is not in the request"]
    C --> D{"Same set as stored?"}
    D -- yes --> E["200 RoleMenus + warnings (no write)"]
    D -- no --> F["One tx: delete role's role_menus, insert new set,<br/>audit ROLE_MENUS_UPDATED {menuIds}"]
    F --> G["200 RoleMenus + warnings"]
```

### Menu catalog

`POST`, `PUT` and `DELETE /menus` ([`MenuService`](../src/main/java/org/cce/admin/service/MenuService.java))
are each one transaction with one audit row (`MENU_CREATED`, `MENU_UPDATED`, `MENU_DELETED`). No
Keycloak or gateway call. Create checks the parent rule and the key; update checks the parent
rule against the stored `type` and is a no-op when nothing changed; delete refuses a menu with
children (409 `MENU_HAS_CHILDREN`), then deletes its `role_menus` rows and the menu, with the role
ids in the audit `before`.

### Build `/me/menus`

`GET /me/menus` ([`CurrentUserService.menus`](../src/main/java/org/cce/admin/service/CurrentUserService.java#L49-L55),
[`MenuTreeBuilder`](../src/main/java/org/cce/admin/service/MenuTreeBuilder.java),
[`MenuRepository.findActiveGrantedToRoleNames`](../src/main/java/org/cce/admin/repository/MenuRepository.java#L17-L19)).

```mermaid
flowchart TD
    A["CurrentUser.roleNames<br/>(from token claim JWT_ROLES_CLAIM_PATH)"] --> B{"empty?"}
    B -- yes --> Z["data: []"]
    B -- no --> C["SELECT DISTINCT menus m JOIN role_menus JOIN roles r<br/>WHERE r.name IN roleNames AND m.is_active"]
    C --> D["De-duplicate by id (union over roles)"]
    D --> E["NAV menus at top level"]
    D --> F["DETAIL menus under their parent<br/>only if the parent is in the set"]
    E --> G["Sort siblings by sortOrder, then label"]
    F --> G
    G --> H["data: MenuTreeItem[]"]
```

Roles come from the token, not from `user_roles`. A `DETAIL` granted without its parent, or whose
parent is inactive, is left out.

## Audit writing

[`AuditWriter`](../src/main/java/org/cce/admin/audit/AuditWriter.java) is the only writer.
`record()` requires an existing transaction (`Propagation.MANDATORY`);
`recordInNewTransaction()` uses `REQUIRES_NEW`. Each row gets the actor from
[`ActorProvider`](../src/main/java/org/cce/admin/audit/ActorProvider.java) and the IP from the
current request.

```mermaid
flowchart TD
    S["Something happened"] --> Q1{"Gateway sync attempt?"}
    Q1 -- yes --> R2["Rule 2: ROLE_SYNC_REQUESTED, outcome SUCCESS or FAILED,<br/>own tx with the sync_status update"]
    Q1 -- no --> Q2{"Change succeeded?"}
    Q2 -- yes --> R1["Rule 1: one SUCCESS row in the same tx as the change"]
    Q2 -- no --> Q3{"Did an earlier Keycloak step<br/>of this call already change something?"}
    Q3 -- yes --> R3["Rule 3: flow's own action, outcome FAILED,<br/>failure JSON, own tx (REQUIRES_NEW)"]
    Q3 -- no --> R4["Rule 4: no row"]
    R2 --> ID["502 auditLogId = that row's id,<br/>or null if the row could not be written (rule 5)"]
    R3 --> ID
```

No-ops write nothing. `POST /roles` and a changed `PUT /roles/{id}/permissions` write two rows
each; `POST /roles/{id}/sync` writes one.

## Bootstrap of the first SUPER_ADMIN

[`BootstrapAdminRunner.bootstrap`, lines 66-116](../src/main/java/org/cce/admin/service/BootstrapAdminRunner.java#L66-L116),
at every startup outside profile `test`. Every failure is logged at `ERROR`; startup continues.

```mermaid
flowchart TD
    A{"Any ACTIVE user holds SUPER_ADMIN?"} -- yes --> A1["Done. Warn if BOOTSTRAP_ADMIN_USERNAME still set"]
    A -- no --> B{"BOOTSTRAP_ADMIN_USERNAME set?"}
    B -- no --> B1["ERROR: no administrator"]
    B -- yes --> C["Keycloak: find user by username"]
    C --> D{"exists, enabled,<br/>email verified or not required?"}
    D -- no --> D1["ERROR: refused"]
    D -- yes --> E{"Realm role SUPER_ADMIN exists?"}
    E -- no --> E1["ERROR: create it by hand"]
    E -- yes --> F{"Local row with that keycloak_id?"}
    F -- no --> G["AccountLinker.link(..., SUPER_ADMIN, linkedExisting true)<br/>audit USER_CREATED linked true"]
    F -- yes --> H["AccountLinker.reactivateAsAdmin:<br/>add SUPER_ADMIN mapping if missing, set ACTIVE,<br/>add user_roles row, audit USER_ROLES_UPDATED"]
```

The actor of these rows is `system:bootstrap`. The bootstrapper never runs a gateway sync.
