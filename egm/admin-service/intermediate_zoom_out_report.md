# Goldfish Review Report

**Target:** `/home/jayaprakashn/Documents/workspace/elephant-goldfish-trial/docs/designs/cce-admin-service.md` (`lines 1-1653`)

**Stage:** Stage 8 (Intermediate Zoom Out Review). This is the second zoom-out pass, run after Stage 5 (Initial Zoom Out), Stage 6 (Comprehension) and Stage 7 (Critic). The TargetFile is unchanged since Stage 5.

**Earlier stage reports used as input:**

- `/home/jayaprakashn/Documents/workspace/elephant-goldfish-trial/egm/admin-service/zoom_out_report.md` (Stage 5)
- `/home/jayaprakashn/Documents/workspace/elephant-goldfish-trial/egm/admin-service/comprehension_report.md` (Stage 6)
- `/home/jayaprakashn/Documents/workspace/elephant-goldfish-trial/egm/admin-service/critic_report.md` (Stage 7)

## Reviewer Status

| Reviewer | Status |
| --- | --- |
| `egm-zoom-out` | completed |

**Severity breakdown:** 1 Blocker, 8 Major, 3 Nit (12 findings).

**Notes from the reviewer:**

- The workspace has no `.java`, `.xml` or `.gradle` files. Reuse was judged only against the stack the design already names (Spring, `keycloak-admin-client`).
- Severities are written as words (Blocker, Major, Nit). They map to the red, yellow and green markers.

## Overall Verdict

The architecture and PRD coverage still hold. But most of what Stages 6 and 7 found comes from mechanisms the design added on top of the PRD. Specifying those mechanisms further would make the document longer and more fragile. The better fix is usually to remove or narrow them.

### Altitude check: scope, PRD coverage, architecture

- **Requirements.** B-01 to B-06, B-10 to B-13, B-20, B-21, B-30 to B-36, B-40 to B-44 and B-50 are covered. Deviations DEV-1 to DEV-6 are recorded. The B-50 "one row per mutation" deviation is still not recorded (Stage 5 #8, Critic N4).
- **Milestones.** M0, B1 to B5 and E2E map to Phases 1 to 8. B1 is split across Phases 2 and 3. B2 and B4 are merged into Phase 5, which depends on Phase 4's gateway client and stand-in (see Finding 4).
- **Scenarios.** S1 to S6 hold locally. In a real environment S1 still fails for existing users (see Finding 1).
- **Architecture.** It holds: a service behind the unchanged gateway, `client/` interfaces, one Postgres database, and Keycloak. The weak point is how many mechanisms rest on the unanswered questions GW-1 to GW-6, GW-2 above all. Critic M10 shows that a restart-only gateway would defeat PRD goal 2 ("configured through the admin console rather than SQL") in shared environments. More configuration cannot fix that. It is a go/no-go question.

## Prioritized Findings (Blockers -> Major -> Nits)

### 1. Existing users and the 5.1 retry dead-end need one mechanism, not two endpoints plus account tagging - Severity: Blocker

- **Reviewer Lens:** `egm-zoom-out`
- **Affected Scope:** `Lines 165-177, 221 (AD-15), 424-426, 686, 756, 763, 793`
- **Related earlier findings:** Stage 5 #1 (T1), #10 (T2); Critic B1, M1. This finding is carried forward and agrees with the Blocker.
- **Discrepancy Description**: Stage 5 and the Critic both confirm the Blocker. Together their fixes add `POST /users/link`, `POST /roles/adopt` with a "legacy read-only" rule, account tagging so a failed 5.1 can resume (T2, with its own state matrix and tag expiry), and a rollout step. Each one is a new contract surface that the console must also cover under D-01.
- **Rationale & Recommendation**: Three problems share one cause: a Keycloak account exists with no local row, and only the bootstrapper can adopt one. The three cases are existing `cce-insights-ui` users, the orphan account left by a 502 at `KEYCLOAK_ROLES_ASSIGNED` or `LOCAL_USER_SAVED`, and the first administrator.
  - Change 5.1 step 3. If Keycloak has an enabled account whose username and email both match exactly, and that account has no local row, link it instead of returning 409: skip step 4, then run steps 5 to 7. Record the link in the `USER_CREATED` row (for example `after.linked = true`).
  - This one rule fixes existing-user onboarding and makes 5.1 recover when the request is resubmitted, without tags. Drop T2.
  - The bootstrapper can call the same service method.
  - Move adopting unowned `permission_name` values such as `INSIGHTS_READ` into Non-Goals, with an open question to the gateway owners (GW-5 already asks for this). An adopt endpoint for rows another team created is the scope expansion to avoid.
  - State in the rollout notes that `INSIGHTS_READ` stays gateway-managed.

### 2. Three kinds of 502 and audit row should become two, not a lookup table - Severity: Major

- **Reviewer Lens:** `egm-zoom-out`
- **Affected Scope:** `Lines 122-139 (G8), 542, 548-576, 748, 812, 914, 1026-1027`
- **Related earlier findings:** Comprehension F2, F3; Critic M12; Stage 5 #8
- **Discrepancy Description**: The Critic asks for a table of flows, outcomes and exact audit rows. That table would record three categories that contradict each other:
  - a failure before any change (502, `completedSteps: []`, no row)
  - a partial failure (`PARTIAL_FAILURE` row)
  - a sync failure (`ROLE_SYNC_REQUESTED` row with `FAILED`, and in 5.8 a row even when `completedSteps` is empty)

  `audit_log.outcome` also has two failure values that mean almost the same thing.
- **Rationale & Recommendation**: A table that lists the contradictions does not remove them. One rule is shorter and leaves nothing to decide. State the rule once, and point G8, §4.2, §5 and §8 to it:
  - Every gateway sync attempt is a state change. It writes one `ROLE_SYNC_REQUESTED` row with its outcome.
  - Every 502 that follows a change or a sync attempt sets `auditLogId` to the one row that records the failure.
  - A Keycloak failure before any change and before any sync writes nothing, and its `auditLogId` is `null`.
  - Use one failure outcome, `FAILED` with the `failure` JSON, and drop `PARTIAL_FAILURE`.
  - Findings 4 and 5 shrink the set of cases this rule has to cover.

### 3. AD-17's own permission check drives most of the matcher and stand-in work; cut it or reduce it to the minimum - Severity: Major

- **Reviewer Lens:** `egm-zoom-out`
- **Affected Scope:** `Lines 140-150 (G9), 223 (AD-17), 429-455, 653, 1109-1110, 1131, 1135-1136, 1155, 1341-1344, 1358, 1461-1463, 1478, 1508, 1514, 1518, 1600-1602, 1628`
- **Related earlier findings:** Stage 5 #4, #5, #6, #16, #21 (T8); Critic M9, N8, M25. Stage 5 challenged the parts of this mechanism but not the decision behind them.
- **Discrepancy Description**: The PRD defines defence in depth in §5, B-10 and B-12: check the JWT again, and require an `ACTIVE` local user. PRD §9 (Network) adds that the service is reached only through the gateway. AD-17 goes further and re-implements the authorization the gateway is assumed to do. That brings in:
  - a hand-written matcher shared across two modules through folder paths
  - a TTL snapshot with its own setting and risk row
  - an on/off switch, which Stage 5 #21 would turn into three modes
  - the stand-in's permission check
  - an offline row check with `scenario-rows.json`
  - `HardControlE2E`

  The later stages then ask for more: a dual-matcher test, grammar limits, a firewall-dependency test and a shadow mode. All of it serves an assumption (GW-3) that cannot be checked locally.
- **Rationale & Recommendation**: Pick one of two options.
  - **(a) Take AD-17 Alternative A** and keep defence in depth as the PRD scopes it. Record the local gap ("any `ACTIVE` local user can call admin endpoints when the gateway is bypassed") as an accepted risk, since local users are test users. This removes everything listed above.
  - **(b) Keep the check in its smallest form:** Spring's `AntPathMatcher`, one indexed query per request, no snapshot and no TTL, only the boolean switch, a stand-in that checks only that a bearer token is present, and no offline row check.
  - In either option, adopt Critic M9's grammar limit (`**` only as the last segment). That is a cut, not an addition. Reject T8.

### 4. AD-16 and AD-12 extend the gateway sync from the PRD's two endpoints to four - Severity: Major

- **Reviewer Lens:** `egm-zoom-out`
- **Affected Scope:** `Lines 218 (AD-12), 222 (AD-16), 562, 565, 792-796, 814-818, 1149, 1536, 1551, 1557`
- **Related earlier findings:** Stage 5 #8; Comprehension F3; Critic M12. Stage 5 did not raise this.
- **Discrepancy Description**: In the PRD, only `PUT /roles/:id/permissions` and `POST /roles/:id/sync` call the sync (B-42, B-43). AD-16 makes `POST /roles` sync and return 502 (`ROLE_CREATE`). AD-12 makes `DELETE /roles/:id` sync and return 502 (`ROLE_DELETE`). The results:
  - two more `partialState` operations for the console
  - two more cases of two audit rows per call, which is the root of the G8 contradictions
  - a create flow that can report a successful creation as a failure, which is the exact case AD-08 rejected for email
  - PRD milestone B2 ("create a role, map menus") can no longer be demonstrated without the gateway client, the service token and the stand-in
- **Rationale & Recommendation**:
  - **Create.** Insert the implicit rows, try the sync, and always return 201. On sync failure, add a warning (for example `ROLE_SYNC_FAILED`), following AD-08's `ACTION_EMAIL_NOT_SENT`. The attempt keeps its `ROLE_SYNC_REQUESTED` row.
  - **Delete.** Do not sync. AD-12 already argues that the leftover realm role grants nothing, and the next sync of any role removes it.
  - **Effect.** Two `partialState` operations go away. Only the permission save still writes two rows per call. B2 no longer depends on the gateway.

### 5. The sync bookkeeping tracks more than the sync is known to do - Severity: Major

- **Reviewer Lens:** `egm-zoom-out`
- **Affected Scope:** `Lines 212 (AD-06), 627-628, 820-827, 1023, 1108, 1505, 1521`
- **Related earlier findings:** Stage 5 #2, #7, #22; Comprehension F4, F9; Critic M10, M22. This finding partly disagrees with Stage 5 #7.
- **Discrepancy Description**: Under GW-1 the sync only creates and deletes realm roles, and the gateway reads the rows straight from the table. Even so, the design keeps per-role `sync_status` and `last_sync_at`, "mark every role that has rows" under `GLOBAL` (with `markedSyncedRoleIds` in the audit JSON), and the `ROLE` scope. This bookkeeping causes a race (Stage 5 #22), an "auditor's in effect" claim that GW-2 may make false, and an unclear "role that asked" when the bootstrapper syncs (Comprehension F9). The only check that matters, the Keycloak lookup in AD-10, does not use it.
- **Rationale & Recommendation**:
  - Stage 5 #7 keeps `GLOBAL` marking. Instead, drop both the `ROLE` scope and the fan-out marking, and update `sync_status` only for the role that asked. This removes the race, `markedSyncedRoleIds` and the role-set query. A role that stays stale after another role's sync shows `FAILED` until someone presses Re-sync, which is a cosmetic cost.
  - Define `SYNCED` as "the last sync attempt for this role got a 2xx". Do not claim more.
  - Make GW-2 a go/no-go item before Phases 4 and 5, rather than adding mechanisms to work around it.
  - This also removes G10's only real configuration case (Finding 11).

### 6. AD-02's conditional DDL on a gateway-owned table grows with each fix; stop altering an existing table - Severity: Major

- **Reviewer Lens:** `egm-zoom-out`
- **Affected Scope:** `Lines 208 (AD-02), 895-901, 921-927, 1118, 1346, 1428, 1441`
- **Related earlier findings:** Critic M5, M6, M23. Stage 5 did not raise this.
- **Discrepancy Description**: V1 creates the gateway's table only if it is missing. On an existing table it also adds the unique constraint, both indexes and the trigger if they are missing. The Critic shows three consequences: these statements fail on a table owned by `opensrp` (M6), the Flyway history table can clash with the gateway's own Flyway (M5), and the rollout claim "only adds tables and rows" is false (M23). Following the recommendations as written means adding catalog-checked DDL and an ownership and grants matrix.
- **Rationale & Recommendation**: AD-02 rejected "gateway script only" because it adds a manual step. But §9.5 already has manual DBA steps, and M6 shows that an existing environment needs grants from a DBA anyway.
  - V1 creates `api_permissions`, with its constraint, indexes and trigger, only when the table is absent. That is the case on empty and local databases. V1 never alters an existing table.
  - V3 stays as the read-only shape check and fails loudly on a mismatch, so the gateway owners fix it.
  - Accept M5 as one configuration line (`spring.flyway.table`).
  - List the DML grants in §9.5.
  - This removes the `DO`-block constraint, the conditional indexes and the conditional trigger, and M23 does not arise.

### 7. The bootstrapper's sync step is what breaks the first-deploy order; remove it - Severity: Major

- **Reviewer Lens:** `egm-zoom-out`
- **Affected Scope:** `Lines 219 (AD-13 bootstrap order), 221 (AD-15 step 4), 838, 1115-1125, 1506, 1522`
- **Related earlier findings:** Critic M4; Comprehension F9; Stage 5 #18. Stage 5 did not raise this.
- **Discrepancy Description**: AD-15 step (4) runs a gateway sync when the `SUPER_ADMIN` realm role is missing. On a first deploy that sync fails, because the gateway has not loaded the `CCE_ADMIN_SERVICE` row (GW-2, GW-6). The bootstrapper then stops, so the §9.5 runbook fails if followed in order (M4).
- **Rationale & Recommendation**: The Critic's fix is a two-deploy order. It is simpler to stop the bootstrapper from depending on the gateway.
  - §9.5 step 3 already has the operator create the `CCE_ADMIN_SERVICE` realm role by hand. Have the same step create the `SUPER_ADMIN` realm role.
  - Delete AD-15 step (4). If `SUPER_ADMIN` is missing, the bootstrapper stops with an error.
  - This fixes M4, removes the ambiguity in Comprehension F9, and removes a test case from `BootstrapAdminRunnerTest`.

### 8. Narrow the last-administrator lock instead of adding timeouts, advisory locks or deadline budgets - Severity: Major

- **Reviewer Lens:** `egm-zoom-out`
- **Affected Scope:** `Lines 751, 773, 780-782, 1357, 1435, 1573, 1578`
- **Related earlier findings:** Stage 5 #3 (T4), #13 (T6); Critic M2, M3. Later stages add weight to Stage 5 #3.
- **Discrepancy Description**: The fixes on offer stack up: a per-user `FOR UPDATE`, `SET LOCAL lock_timeout` or advisory try-locks returning 409 or 503, a time budget per request below an unknown gateway timeout (T6, GW-7), and reserved pool connections.
- **Rationale & Recommendation**: The cause is one choice: every status and role change takes a global lock and holds it across Keycloak calls.
  - Take a per-user `FOR UPDATE` on `users` first, and compute the diff after it. This fixes M2 and only blocks requests for the same user.
  - Take the `SUPER_ADMIN` lock only when the change can lower the number of `ACTIVE` `SUPER_ADMIN` holders (T4).
  - With both changes, normal traffic no longer queues, so the pool-exhaustion risk can be dropped.
  - Reject T6, `lock_timeout`, advisory locks and reserved connections.
  - Restate the risk row at line 1357 so it covers only the rare `SUPER_ADMIN` case.

### 9. Keep stateful and background proposals out of scope - Severity: Major

- **Reviewer Lens:** `egm-zoom-out`
- **Affected Scope:** `Lines 175-177 (Non-Goals), 748, 913, 1003, 1331-1360`
- **Related earlier findings:** Stage 5 #11 (T3), #12 (T5), #20 (T7); Critic M8, M11, M14, M15. This finding disagrees with Stage 5 #20's T7.
- **Discrepancy Description**: Several proposals would add new processes or new persistent state:
  - write-ahead intent rows (T3), which change B-50 row counts or add an eighth table against B-01
  - a scheduled drift detector with leader election (T5)
  - a background re-sync (T7), against PRD §9 and this design's own Non-Goal
  - Micrometer counters with alert routing (M14)
  - capacity planning with an instance count (M15)
- **Rationale & Recommendation**:
  - Record the crash window and Keycloak drift as accepted risks, with an operator query. For M14, one read-only SQL query for outstanding `FAILED` rows, next to the GW-5 query, is enough.
  - Keep one structured `ERROR` log event per failure.
  - State the token-revocation window (300 s locally) as a risk (M8). Do not add a check of the token against `user_roles` on every request.
  - Adopt the cheap, local fix in M11: generate the user UUID before step 4 so `entity_id` exists.
  - For M15, a one-line traffic estimate for `/me/menus` is enough.

### 10. Replace up to 20 realm-role lookups with one list call - Severity: Nit

- **Reviewer Lens:** `egm-zoom-out`
- **Affected Scope:** `Lines 756, 781, 974`
- **Related earlier findings:** Critic M3; Stage 5 #13. Stage 5 did not raise this.
- **Discrepancy Description**: 5.1 step 3 and 5.4 step 3 call `findRealmRole` once per role, up to 20 times. That is the main reason for the Critic's worst case of more than 200 seconds.
- **Rationale & Recommendation**: Read the realm's roles once with `keycloak-admin-client`'s realm-roles list, and check the requested names against that set. This also makes deadline budgets less necessary (Finding 8).

### 11. Delete G10 instead of rewording it - Severity: Nit

- **Reviewer Lens:** `egm-zoom-out`
- **Affected Scope:** `Lines 151-155, 1106-1108, 1360`
- **Related earlier findings:** Stage 5 #7; Critic M22
- **Discrepancy Description**: After Findings 3 and 5, configuration only has to cover the URL, the path, the timeouts and possibly one boolean. These are ordinary settings, not a design goal.
- **Rationale & Recommendation**: Remove G10. In the GW section, list once what would need a code change: the request body, a per-role parameter, token forwarding and the matcher.

### 12. Add alternatives only where a real trade-off exists - Severity: Nit

- **Reviewer Lens:** `egm-zoom-out`
- **Affected Scope:** `Lines 1159-1327`
- **Related earlier findings:** Critic M13, M26; Stage 5 #9
- **Discrepancy Description**: M13 asks for alternatives for AD-05, AD-09, AD-10, AD-12 and the locking strategy. The document is already 1,653 lines, and the reasoning for some decisions appears in up to three places.
- **Rationale & Recommendation**:
  - Add an alternative only for the locking strategy, as narrowed in Finding 8.
  - Give each "Rejected by author" entry its trade-off in one line.
  - When cutting the AD cells (M26), move the mechanism into §5 or §9 instead of copying it.

## How This Pass Relates to Stage 5

- **Agreed, and given more weight by later stages:**
  - #1 (Blocker), carried forward as Finding 1
  - #3, now backed by Critic M2 and M3 (Finding 8)
  - #4: Critic M25 and M15 add to it, and it goes away under Finding 3
  - #8, now backed by Comprehension F3 and Critic N4
  - #14, which matches Critic M7. Use `ForwardedHeaderFilter`, as both stages recommend.
- **Disagreed:**
  - #7's fix, which keeps `GLOBAL` marking (Finding 5)
  - #10's T2 tagging; the link rule in Finding 1 is preferred
  - #20's T7 and #21's T8 shadow mode, because both add scope (Findings 3 and 9)
- **Missed by Stage 5:**
  - Findings 2, 4, 6, 7 and 10
  - AD-17 as the root cause in Finding 3. Stage 5 challenged its parts but not the decision.
