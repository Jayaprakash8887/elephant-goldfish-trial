# Goldfish Review Report

**Target:** `docs/designs/cce-admin-ui.md` (lines 1-1833)
**Stage:** 5 (Initial Zoom Out Review)
**Mode:** REVIEW

## Reviewer Status

| Reviewer | Status |
| --- | --- |
| `egm-zoom-out` | completed |
| `egm-inventive-catalyst` | completed |

Each reviewer ran as one instance and read its persona file. The zoom-out reviewer also read its
standard. Both read the GoldfishContext reading list. Before writing this report, the
synthesizer checked the line ranges below against the target, and checked the backend facts the
Major findings rely on against `docs/designs/cce-admin-service.md`: §4.2 lines 548-576, §4.4
line 627, and §5 lines 753-812. Where a reviewer's line reference was off, it is corrected here.

## Severity Summary

| Severity | Count |
| --- | --- |
| Blocker | 0 |
| Major | 6 |
| Nit | 9 |

Lens split after deduplication (15 findings):
* Both lenses: M1 and M3.
* Zoom-out only: M2, M4 and N1 to N7.
* Inventive catalyst only: M5, M6, N8 and N9.

Neither lens found a blocker. The zoom-out lens confirms the design solves the stated problem.
Every endpoint path, method, payload field, error code, pagination rule, warning and
`partialState` operation the console uses matches admin-service §4.1 to §4.5. The roles and
permissions rules (AD-07, AD-14, AD-16, CQ-1) also match.

---

## Part 1: Prioritized Findings (Blockers -> Major -> Nits)

### M1. A failed user creation offers a link that can never work, and a page reload hides the partial state - Severity: Major
* **Reviewer Lens:** `egm-inventive-catalyst`, `egm-zoom-out` (merged)
* **Affected Scope:** Lines 264-266 (CUJ 2), 537 (`USER_CREATE` row of the `SyncFailedNotice` table), 609-611 (User create: submit stays disabled "for the rest of that page visit")
* **Discrepancy Description**:
  * **The link to the user never resolves.** Line 537 offers "link to the user if `entityId` is set". In admin-service flow 5.1 (lines 753-763), a 502 comes only from step 5 (`KEYCLOAK_ROLES_ASSIGNED`) or step 6 (`LOCAL_USER_SAVED`). Step 6 is one transaction, so a 502 always means there is no local user. An email failure in step 7 returns 201, not 502. The link can only lead to a 404.
  * **The backend contradicts itself here.** Admin-service lines 550-551 say "A 502 on `POST /users` ... can come after the local row is saved". That does not match its own flow 5.1.
  * **The lock lives only in page memory.** After a reload, submit is enabled again. Resubmitting reaches flow 5.1 step 3 (line 756), which returns 409 `USERNAME_IN_USE` or `EMAIL_IN_USE` because a Keycloak account with no local row exists. The console shows this on the username field, as if another user held the name, but no such user appears in the Users list.
  * **This breaks Goal 3.** Goal 3 (lines 91-94) promises that no partial failure is hidden. The design rejected browser-only state for sync status for the same reason (Alternative G, lines 937-950).
* **Rationale & Recommendation**:
  * Drop the `entityId` link for `USER_CREATE`, or have the backend say when `entityId` points to a real user. Raise the contradiction at admin-service lines 550-551 with the backend design.
  * Ask the backend to mark the "Keycloak account with no local row" conflict separately: a `details` entry or a separate code carrying `keycloakUserId` or an audit id.
  * The console can then say "An unfinished account exists for this username. An operator must reconcile it (audit row X)" instead of showing a plain field conflict.
  * This is a contract change that both designs must agree.

### M2. The console treats `syncStatus` as a promise, but the contract calls it a hint - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Lines 280-289 (CUJ 4), 602-605 (role checkbox hint), 1542-1546 (`RoleCheckboxList`); compare 1780-1786 (CUJ 4 e2e spec)
* **Discrepancy Description**:
  * Admin-service line 627 says `syncStatus` is "a display hint only: the Keycloak check at assignment is authoritative (AD-10)".
  * The console tells the admin "Not synced yet: assigning it will fail until it is synced" (line 604) for every role that is not `SYNCED`.
  * **Case 1, a fresh stack.** The seed starts `SUPER_ADMIN` as `NEVER` (admin-service §6), but the local realm import already creates its realm role (admin-service §9.2). The picker says `SUPER_ADMIN` cannot be assigned, yet assigning it works.
  * **Case 2, a later failed save.** A role that synced when it was created and later had a failed permission save shows `FAILED`. Its realm role still exists, so assigning it succeeds.
  * **CUJ 4 overstates the refusal.** It says "Assigning it is refused with `ROLE_NOT_SYNCED`" (line 287). That holds only for a role whose realm role was never created. The e2e spec sets this up correctly by creating role B while the stand-in is failing (lines 1780-1786). The CUJ text does not say so.
* **Rationale & Recommendation**:
  * Reword the hint to report the last sync result without predicting the outcome. For example: "Last gateway sync failed or has not run. The service refuses the assignment if Keycloak has no realm role yet."
  * Rewrite CUJ 4's Given as "a role created while the gateway sync is failing, so its realm role does not exist".

### M3. Goal 6 promises more drift protection than the design gives - Severity: Major
* **Reviewer Lens:** `egm-inventive-catalyst` (Major), `egm-zoom-out` (Nit); merged at the higher severity
* **Affected Scope:** Lines 101-104 (Goal 6), 428-437 (`contract.ts`), 723-748 (Forms and validation table), 922-935 (Alternative F), 147-152 (`npm run e2e` not in CI)
* **Discrepancy Description**:
  * **What a type error actually catches.** `contract.ts` is a hand copy of a Markdown section. A type error catches drift between `contract.ts` and the client or the mocks. It does not catch drift between the console and the admin-service contract or the running service. Alternative F (lines 932-935) admits that only the Playwright tests catch that.
  * **The only real-service check is opt-in.** The Playwright tests run under `npm run e2e`, which CI, Docker and Jenkins never run (lines 147-152). They cover CUJ 2 to CUJ 6 only, not F5 or 403s (lines 819, 829).
  * **The validation table is a second copy.** It restates admin-service §4.4 rule by rule, although line 43 says the design does not copy the contract. That is one more written copy that can drift.
  * **The risk reaches past this console.** D-01 says `cce-insights-ui` uses the same contract (author-decisions.md).
* **Rationale & Recommendation**: Choose one of these.
  * **Option A (zoom-out, smallest change).**
    * Reword Goal 6 to say the build keeps the client and the mocks consistent with `contract.ts`.
    * Replace the table at 730-748 with a reference to admin-service §4.4, plus the console-only rules: role count on create versus edit, and no duplicate or implicit-equal permission rows.
  * **Option B (inventive catalyst, an "Intermediary").**
    * Commit a snapshot of the service's springdoc OpenAPI output. The backend already serves it and tests that it lists every endpoint.
    * Generate types from the snapshot into a test-only file.
    * Under `test:ci`, add a type-level test that `contract.ts` and the generated types can be assigned to each other. Drift in either direction then fails the test, which answers Alternative F's objection that generated types "hide" drift.
    * Optionally run `npm run e2e` in Jenkins against `docker compose --profile app up --wait`.
    * Costs: the snapshot is shared between repos and needs a refresh process; springdoc often describes envelopes, nullability and enums poorly, so the check may need hand-written adapters; e2e adds CI time.

### M4. The MSW in-memory store re-implements the service's business rules - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Lines 776-787 (MSW), 1692-1704 (`fixtures.ts`, `db.ts`, `handlers.ts`, `server.ts`)
* **Discrepancy Description**:
  * **What the store rebuilds.** Filters and case-insensitive search, pagination and its 400s, replace semantics, no-op detection, implicit-row merging, `DETAIL_WITHOUT_PARENT`, the 409 rules, `LAST_SYSTEM_ADMIN`, and a `syncStatus` stand-in for `ROLE_NOT_SYNCED`.
  * **It is a second copy of the backend.** That is a copy of admin-service §4.5 and §5, with its own upkeep and its own drift. M2 shows one place where the stand-in already differs from the service: the store refuses roles that are not `SYNCED`, while the service checks Keycloak.
  * **The console tests need less.** They need to check that the right query parameters and request bodies go out, and that each response or error code is rendered correctly.
  * **The real rules are already tested twice.** Once by Goal 9's Playwright suite against the real service, and once by the backend's API-level E2E for S1 to S6 (admin-service §10).
  * **The PRD asks for less.** PRD §10 (F1 notes) asks only for "MSW handlers mirroring the API reference examples".
* **Rationale & Recommendation**:
  * Use canned, typed fixtures, and handlers that record each request so tests can assert on what went out.
  * Keep the per-test `server.use(...)` overrides for each error code.
  * At most, keep one generic unknown-property check.
  * Drop the stateful rule engine.
  * Note the tension with M3 Option B: the inventive catalyst would instead keep the scenarios and replay them against the real service. Settle M3 first, because it decides how much rule logic the mocks need.

### M5. Partial failures on user profile, status and role changes are left to an operator, when a confirmed re-send would usually finish them - Severity: Major
* **Reviewer Lens:** `egm-inventive-catalyst`
* **Affected Scope:** Lines 470 (mutations never retried), 538-540 (`SyncFailedNotice` rows for `USER_PROFILE_UPDATE`, `USER_STATUS_CHANGE`, `USER_ROLES_UPDATE`, Action "none"), 613-622 (User edit sections). The edit sections do not say whether the form resets after a 502. The Permissions tab does say so (lines 695-698).
* **Discrepancy Description**:
  * **Keycloak goes first.** In flows 5.2, 5.3 and 5.4 (admin-service lines 765-785), the Keycloak steps run before the local save.
  * **The service compares against the unchanged local row.** It works out what to change by comparing the request with the local row, which a failed local save left unchanged.
  * **So a re-send of the same request would usually finish the job.** It repeats Keycloak writes that look safe to repeat (set `enabled`, set profile fields, add or remove mappings), then completes the local save.
  * **The PRD does not forbid this.** PRD §9 bans only "blindly retried" writes, and B-32 covers user creation only. A re-send that the admin confirms after seeing `partialState` is not blind.
  * **The design stretches its own rule.** Line 470's "never retry mutations" is right for automatic retries. Here it also blocks a deliberate re-send by the admin.
* **Rationale & Recommendation**:
  * For these three operations, when `completedSteps` is not empty:
    * keep the exact body that was sent instead of resetting to the refetched data,
    * offer "Save again" behind a `ConfirmDialog` that shows the partial state.
  * The backend must first confirm that each Keycloak call can safely run twice: adding a mapping that already exists, removing one that is already gone, setting `enabled` to the same value. Its §4.2 "next step" column would then say a re-send is supported.
  * `USER_CREATE` and `ROLE_DELETE` stay as they are.
  * Costs:
    * each attempt writes another `PARTIAL_FAILURE` audit row,
    * if Keycloak stays down, the admin sees the same 502 again,
    * the console must hold the sent body apart from the refetched data,
    * both designs must agree the contract change.

### M6. "Last save wins" is accepted on security-relevant full-set saves, and the menu toggle sends a whole record that may be stale - Severity: Major
* **Reviewer Lens:** `egm-inventive-catalyst`
* **Affected Scope:** Lines 141-142 (Non-Goal), 212 (AS-REQ-21), 468 (`staleTime` 30 s, `refetchOnWindowFocus` off), 676-681 (permissions Save), 709-711 (menu Deactivate/Activate), 750-752
* **Discrepancy Description**:
  * **It conflicts with the Problem section.** The design accepts lost updates because the contract has no ETag (admin-service §4.1). Yet the Problem section calls permissions "a security control" (lines 55-57).
  * **A permission save can silently undo another admin's work.** A second admin's save can remove the first admin's rows, while the first admin still sees "Saved and synced".
  * **The menu toggle widens the risk.** Deactivate and Activate send the cached label, path, icon, parent and sort order with `isActive` flipped (lines 709-711).
    * Refetch on focus is off, so that cache can be much older than 30 seconds.
    * The admin means to change one flag, but the request can revert another admin's label, path or parent change.
* **Rationale & Recommendation**:
  * **Console-only fix (Preliminary Action).**
    * Just before building any `PUT` body, refetch the record with no cache (`fetchQuery`, `staleTime: 0`).
    * For menu toggles, take the other fields from that fresh copy.
    * For the user-roles, role-menus and role-permissions saves, compare the fresh server set with the baseline the form started from. If they differ, ask "Changed since you opened it" before saving.
  * **Complete fix (cross-design).** `If-Match` with a server-computed hash of the set, and a 412 response. That needs a new error code, console UI for the 412, and an admin-service contract change.
  * Costs: one extra GET per save. The console-only check narrows the window but does not close it.

### N1. The menu icon picker is limited to a hard-coded list in this console - Severity: Nit
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Lines 235, 715-721, 1589-1592 (`iconMap.ts`)
* **Discrepancy Description**:
  * Admin-service §4.4 accepts any icon name that fits the pattern. Admin-service §6 says an administrator can change any icon with `PUT /menus/:id`, "with no migration and no release".
  * The icons are drawn by `cce-insights-ui`, not this console. Limiting the select to this console's map means every new icon needs an admin-ui release.
  * `HomeIcon` and `DocumentTextIcon` are not used by any seed row or screen.
* **Rationale & Recommendation**: Choose one.
  * Use a text input checked against the contract pattern, with a live preview that falls back to a default icon.
  * Or limit the select to the ten seed icons plus the entry's current value, and drop the extra icons.

### N2. Some cache refreshes after writes are not needed - Severity: Nit
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Lines 477, 483
* **Discrepancy Description**:
  * **Menu writes.** `POST /menus` and `PUT /menus/:id` refresh every `['roles']` query "because a role's menu tree shows menu labels". But `GET /roles/:id/menus` returns only `{ roleId, menuIds }` (line 650; admin-service §4.5). The labels come from `['menus','tree']`, which is already refreshed.
  * **User writes.** `PUT /users/:id` and `PATCH /users/:id/status` refresh `['roles']` for `userCount`. `userCount` counts users of any status (admin-service §4.4), so neither call changes it.
* **Rationale & Recommendation**: Refresh `['roles']` only after `POST /users` and `PUT /users/:id/roles`. After menu writes, refresh only `['menus']`.

### N3. Error branches that the contract cannot produce - Severity: Nit
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Lines 505 and 522-523 (`MENU_HAS_CHILDREN`); 530-531 and 1722 ("Nothing was changed." for any empty `completedSteps`); 543, 696-698, 824 and 1729 (`ROLE_PERMISSIONS_SAVE` with no steps). The `USER_CREATE` link case is covered under M1.
* **Discrepancy Description**:
  * **`ROLE_PERMISSIONS_SAVE` with no steps.** Flow 5.7 makes no Keycloak or gateway call before `PERMISSIONS_SAVED`. It returns 502 only with `completedSteps: ["PERMISSIONS_SAVED"]` (admin-service line 810). The "Nothing was saved. You can save again." branch and its test cannot occur.
  * **"Nothing was changed." on `ROLE_SYNC`.** A `ROLE_SYNC` 502 always has `completedSteps: []` (admin-service line 812). But it did change the role's status to `FAILED` and wrote an audit row, so the generic line misleads.
  * **`MENU_HAS_CHILDREN`.** Only `DELETE /menus/:id` returns it, and the console never calls that endpoint (lines 143-145).
* **Rationale & Recommendation**:
  * Remove these branches and their tests, or mark them as defensive only.
  * Choose the "nothing changed" wording per operation, not for every empty list.

### N4. The backend design is called "approved", but its status is Proposed - Severity: Nit
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Lines 37, 167
* **Discrepancy Description**:
  * Line 37 says the contract is "complete and approved by the author".
  * C-07 (line 167) refers to "the approved designs in `docs/designs/`".
  * Both `cce-admin-service.md` and this document have status Proposed (line 4 of each).
* **Rationale & Recommendation**: Say the console follows the Proposed contract as of 2026-11-10. The `contract.ts` header (lines 436, 1315) already says this.

### N5. The design does not say what it reuses from `cce-insights-ui` - Severity: Nit
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Lines 23-32, 1254-1266, 1323-1331, 1511-1530
* **Discrepancy Description**:
  * The insights-ui docs describe existing pieces: `src/auth/keycloak.ts`, an `src/api/client.ts` with buildUrl, authHeaders and handleResponse, and shared `StatusBadge`, `PageHeader`, `EmptyState`, `LoadingSpinner` and `PagePagination` components (developer setup §5, architecture §3).
  * This plan writes all of them from scratch and does not say whether the template's versions are copied and adapted.
  * The template's code is not in this workspace, so the reviewer could not check it.
* **Rationale & Recommendation**:
  * Name which files start as copies, for example `keycloak.ts` without the auth flag and the static-token fallback, and the presentational shared components.
  * Name which files are deliberately new.

### N6. The unsaved-changes guard is the only reason for changing the template's router - Severity: Nit
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Lines 549-551, 568-571, 1417-1421
* **Discrepancy Description**:
  * PRD F-13 and F-14 do not ask for an unsaved-changes guard.
  * Yet the guard is the only reason given for moving from the template's `BrowserRouter` with inline `<Routes>` (insights-ui architecture §7) to a data router.
* **Rationale & Recommendation**: Confirm the author wants the guard. If not, drop it and keep the template's router.

### N7. The browser tests read the backend repo's `.env` through a relative path - Severity: Nit
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Lines 796-798, 970-971, 1736-1739 (`e2e/env.ts`)
* **Discrepancy Description**: `../cce-admin-service/.env` assumes the two repos sit side by side. That holds in this trial folder, but not for separate repos or CI checkouts.
* **Rationale & Recommendation**:
  * Read `CCE_TEST_ADMIN_USERNAME` and `CCE_TEST_ADMIN_PASSWORD` from the environment only.
  * Say in the README to export them from the admin-service `.env`.

### N8. One image per environment is accepted, though Caddy already reads configuration at container start - Severity: Nit
* **Reviewer Lens:** `egm-inventive-catalyst`
* **Affected Scope:** Lines 835-840 and 1182-1186 (Dockerfile build arguments), 841-848 (Caddy reads `GATEWAY_UPSTREAM` at start-up), 973-974 (Rollout)
* **Discrepancy Description**:
  * The design treats build-time `VITE_*` values as the reason each environment needs its own image (PRD §8.3).
  * Yet the same image already reads one setting, `GATEWAY_UPSTREAM`, when the container starts.
* **Rationale & Recommendation**:
  * Have Caddy serve a small `/config.json` built from environment variables at start-up. `readConfig()` reads it before `initAuth()`.
  * One image could then be promoted through every environment.
  * This would be a PRD deviation and a departure from the template, so put it to the author as a question rather than change the design silently.
  * Costs: one more request before sign-in, and the config check moves from `vite build` to container start.

### N9. Walking every role page can skip or repeat a role - Severity: Nit
* **Reviewer Lens:** `egm-inventive-catalyst`
* **Affected Scope:** Lines 488-492 (`useAllRoles`), 1347-1348, 1721 (`useAllRoles.test.tsx`)
* **Discrepancy Description**:
  * Pages are fetched one after another and joined in order, from a list ordered by name with offset paging (admin-service §4.3).
  * If a role is created or deleted between two page requests, the offsets shift, so a picker can miss a role or show one twice.
* **Rationale & Recommendation**:
  * Remove duplicates by `id` when joining.
  * Fetch page 1, then pages 2 to `totalPages` in parallel.
  * If `totalItems` changes between pages, refetch once.

---

## Part 2: Inventive-catalyst view (contradiction lens)

The inventive catalyst's verdict is **not** "No false compromise detected". Its proposals are
filed above as M1 (Blessing in Disguise), M3 Option B (Intermediary), M5 (Self-Service), M6
(Preliminary Action), N8 and N9. The table lists the trade-off it found under each one.

| Finding | Accepted compromise in the design | Proposed resolution | Needs a backend contract change |
| --- | --- | --- | --- |
| M1 | A partial create can only be locked in browser memory | Backend marks the "orphan Keycloak account" conflict separately | Yes |
| M3 | Hand-written types against generated types, framed as either-or | Hand-written types stay the source; a generated snapshot is used as a check | No (shared snapshot file) |
| M5 | No automatic retry, so no retry at all | The admin confirms a re-send of the same intended state | Yes (re-send support in §4.2) |
| M6 | No ETag, so lost updates are accepted | Refetch before building a `PUT` (console only); `If-Match` later | Optional |
| N8 | Build-time config, so one image per environment | Config served at container start | No (PRD deviation) |
| N9 | Sequential offset paging is accepted as correct | Remove duplicates and check `totalItems` | No |

It did not treat these two constraints as false compromises:
* "Synced" means only that the gateway's sync call succeeded (CQ-2, lines 225-229). This depends on the gateway owners (admin-service GW-2).
* Browser tests run one at a time (line 790). The sync call has no body (GW-1), so the shared stand-in cannot fail for one role only.

---

## Part 3: Checked with no finding, and cross-design notes

* **Gateway table.** The `api_permissions` limits fit the fixed gateway table shape in `context/gateway-service/DATABASE_PERMISSIONS_MIGRATION.md`: method, a 255-character URI pattern, a 50-character category, and a TEXT description.
* **Menu seed.** The MSW menu store matches the 12-route seed in admin-service §6.
* **Recorded departures.** Every departure from the PRD and the template (C-01 to C-09, UI-DEV-1) has a recorded reason.
* **Section boundaries.** Implementation detail stays in the implementation plan and does not leak into the high-level sections.
* **Backend item 1, outside this file.** Admin-service lines 550-551 say a `POST /users` 502 can come after the local row is saved. That contradicts admin-service flow 5.1 (lines 759, 763). See M1.
* **Backend item 2, outside this file.** AD-03 makes Practitioners, Intelligence and Exports active `NAV` menus. In `cce-insights-ui` today they are reachable by URL only and are not in the sidebar. Check this against the requirement that the menu seed match the current navigation.
* **Workspace limit.** The workspace holds no source code. `cce-insights-ui` exists only as three docs, so reuse checks (N5) are based on those docs.
