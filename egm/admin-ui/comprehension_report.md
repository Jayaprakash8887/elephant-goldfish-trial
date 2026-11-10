# Comprehension Test (EGM Stage 6, Review mode): `cce-admin-ui.md`

**Target:** `docs/designs/cce-admin-ui.md` (FeatureRange 1-978, DetailedRange 979-1833)
**Reviewer:** `egm:egm-comprehension` (completed). It read only the target document: no document standard and no linked files.

## What this proposes

The document proposes a new React single-page app, `cce-admin-ui`, for CCE v2 administrators. It has three areas:
- **Users:** a filtered, paged list; create; and an edit page with separate profile, roles, status and password-email actions.
- **Roles:** create with a name that can never change, edit, delete, grant menus, edit API permission rows, and Re-sync.
- **Menus catalog:** add, edit, activate and deactivate.

The console stores no data. Every call goes to `cce-admin-service` at `/admin/v1` with a Keycloak token:
- **Local development:** through a Vite proxy straight to the service.
- **Deployed:** through Caddy and then `gateway-service`.

Its main rules:
- It shows only what the server confirmed.
- A 502 `KEYCLOAK_SYNC_FAILED` is shown in one dedicated `SyncFailedNotice`.
- A 403 is shown inline, where the action was taken.
- There is no way to skip sign-in.
- All API shapes live in one hand-written TypeScript module that copies the admin-service contract. The API client and the MSW mocks are both typed from it.

It is tested with Vitest and MSW, plus Playwright browser tests for CUJ 2 to 6 against the real local stack. The detailed plan lists 104 files, built in milestone order F1 to F5.

## How the existing system works, per this document

There is no access-control screen in CCE today. `cce-insights-ui` shows every user the same sidebar, and it has an auth-bypass flag and a static-token fallback. Giving someone access means three hand edits:
- a Keycloak account and its realm roles,
- gateway `api_permissions` rows, edited with SQL,
- and, once the admin service exists, the role-to-menu mapping.

A role's name is also its Keycloak realm role name and its gateway `permission_name`. A "gateway sync" creates the realm role in Keycloak. Writes go through the database, Keycloak and the gateway in sequence, so they can half-succeed. The gateway loads its permissions only at start-up. `cce-admin-service` is being designed at the same time and owns the API contract.

## Verdict

The document is understandable to a fresh reader: every section could be paraphrased, so there are no Blockers. There are **4 Major and 10 Nit findings**.

## Findings

### Major

**1. Major: "the gateway sync" is never explained** (lines 66-67, 225-228, 282-289, 485-486, 545, 686-692)
- **Gap:** The sync drives the Permissions tab, `ROLE_NOT_SYNCED`, CUJ 4 and three of the nine `SyncFailedNotice` operations. Yet the document never says:
  - who starts it (the admin-service? `gateway-service`?);
  - what it writes, in which order, and to what. Line 67 says it creates the Keycloak realm role. Line 227 says the gateway loads permissions only at start-up.
  - what scope it has. Line 485 says "Under the default `GATEWAY_SYNC_SCOPE=GLOBAL`, a successful sync can mark other roles `SYNCED` too" without saying what this setting is, who owns it, or what the other values are.
  - why a deleted role's realm role "may stay in Keycloak until the next successful sync of any role" (line 545).
- **Why it matters:** A reader can follow what the console displays, but not why it displays it. Without a two- or three-sentence description of the sync (caller, target, effect, scope), they cannot judge the invalidation rule on line 485 or the delete message on line 545.

**2. Major: what makes a role "not synced" for assignment is unclear** (lines 283-288, 603-605, 785, 1780-1785)
- **Gap:** The document gives three readings that do not agree:
  - The role-checkbox hint (line 604) shows when `syncStatus` is not `SYNCED`, and says "assigning it will fail until it is synced".
  - The next sentence says "the service decides (admin-service AD-10)".
  - Line 785 says the MSW mock uses `syncStatus` only "as the stand-in for 'the realm role exists in Keycloak'". That suggests the real service checks whether the realm role exists, not the sync status.
- **Example:** In CUJ 4 (lines 283-287), a role whose permission save failed is refused with `ROLE_NOT_SYNCED`. But if that role had synced earlier, its realm role already exists, so the real service might allow the assignment. The CUJ 4 spec (lines 1780-1785) avoids this by creating role B while the sync is failing, which is a different setup from the CUJ narrative.
- **Why it matters:** A reader cannot tell whether a `FAILED` role whose realm role exists can be assigned. So they cannot tell whether the hint and the notice text ("do not assign until a sync succeeds") describe what the service enforces, or only advise the admin.

**3. Major: "the stand-in gateway" is undefined and seems to contradict "Locally there is no gateway"** (lines 112, 137, 165, 214, 789-803, 1742)
- **Gap:** Line 112 lists "the stand-in gateway in Docker" as part of the local stack, and CUJ 4 switches it at `localhost:8090/_stub/mode`. Line 165 (C-05) says "Locally there is no gateway" and that local 403s come from the service.
- **What's missing:** The document never says that the stand-in is a fake sync target that the admin-service calls (if that is what it is), that it is not in the console's request path, or what the `ok` and `fail` modes do.
- **Why it matters:** A new reader sees a contradiction and an unexplained test prerequisite that the whole of CUJ 4 depends on.

**4. Major: "an operator reconciles it from the audit log" is the next step, but neither is defined** (lines 264-265, 532, 537-540; Goal 3 at lines 91-94; Non-Goals at line 126)
- **Gap:** Goal 3 promises that a partial failure says "what to do next". For four operations (`USER_CREATE`, `USER_PROFILE_UPDATE`, `USER_STATUS_CHANGE`, `USER_ROLES_UPDATE`), the next step is "An operator reconciles it from the audit log". The document never says:
  - who the operator is, and whether that is a different person or role from the administrator;
  - how the admin reaches them;
  - what reconciling involves, or whether any tool for it exists.
- **Why it matters:** An audit-log viewer is explicitly out of scope (line 126), and the audit log itself is never described. This is a "someone handles it" explanation sitting exactly where the Problem section (lines 62-65) says the console must give "a specific next step".

### Nit

**5. Nit: requirement and decision ID prefixes are never introduced** (B-04 line 581, B-06 line 924, B-12 line 165, B-34 line 166, B-44 line 143; the AD-xx IDs throughout, for example lines 165-166, 194-215, 485, 604, 644; DEV-4 line 166; GW-2 line 227)
- **Gap:** F-xx, D-xx and the PRD are linked or explained. B-xx, AD-xx, DEV-n and GW-n are not. A reader has to guess that B means a backend PRD requirement and AD means an admin-service design decision. Most uses restate the meaning inline, so the effect is small.
- **Fix:** One line under Overview would close it.

**6. Nit: drafting-process terms leak into the design** (lines 161, 179, 402, 771, 854, 932, 1806)
- **Gap:** "gate TP-1/TP-2", "Decided by the author at finalization", "Step 1, item 7", "EGM Step 6", "`SITEMAP.md`" and "this trial" (lines 167, 893) are terms from the authoring process. They are never defined for someone reading only the design.

**7. Nit: the implicit permission rows are used before they are defined, and their content is never given** (lines 275, 666-668, 748, 1775)
- **Gap:** CUJ 3 mentions "the role's two implicit rows" about 390 lines before line 666 explains them ("so that its users can load their profile and menus"). The two rows are never listed, although the schema rejects rows equal to them and a browser test checks that they appear.

**8. Nit: domain terms are left undefined** (lines 1, 12, 127-128, 199, 735)
- **Gap:** "CCE" is never spelled out. "Facility scope" is stored, filtered and validated (free text, up to 50 characters, exact match), but what a facility is, and what the scope will mean once it is enforced, is never said.

**9. Nit: the `LAST_SYSTEM_ADMIN` rule is never stated** (lines 298, 302, 307, 522)
- **Gap:** The reader has to infer from the error code's name that the service refuses to leave zero active `SUPER_ADMIN` holders. It is also not said whether a holder of the other system role, `CCE_ADMIN_SERVICE`, counts toward that minimum.

**10. Nit: the `USER_PROFILE_UPDATE` notice text is ambiguous** (line 538)
- **Gap:** "Keycloak has the new values, the console shows the saved ones" does not say whether "the saved ones" are the old values in the database or the new values the admin typed. The document also never says whether the database or Keycloak is written first.

**11. Nit: `VALIDATION_ERROR` on field `id` is treated as not-found without a reason** (line 506)
- **Gap:** Presumably a malformed UUID in the URL comes back as a 400, but the document never says so.

**12. Nit: Jenkins appears once with no context** (line 149)
- **Gap:** The console's CI is otherwise only the Docker `verify` stage. The plan creates no Jenkins file, so the reader cannot tell whether a Jenkins pipeline exists for this repo.

**13. Nit: the order of work is vague about files** (lines 990-995)
- **Gap:** Step 2 says "files 19 to 55 that F1 needs" without saying which ones. Steps 3 to 6 give no file numbers at all, so a reader cannot match the order of work to the numbered file list.

**14. Nit: smaller internal inconsistencies**
- **Line 92 (Goal 3):** says the notice appears on "the screen the admin-service contract names for its operation". The screen assignment is actually this console's own table at lines 535-545.
- **Line 1780 (CUJ 4 spec):** creates "a synced role A and a user holding A", but role A is never used again in the steps described.
