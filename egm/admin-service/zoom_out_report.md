# Goldfish Review Report

**Target:** `docs/designs/cce-admin-service.md` (lines 1-1653, full file)
**Stage:** Stage 5 (Initial Zoom Out Review)
**Mode:** REVIEW

## Reviewer Status

| Reviewer | Status | Raw findings |
| --- | --- | --- |
| `egm-zoom-out` (standard: `zoom-out-standard.md`) | completed | 1 Blocker, 6 Major, 7 Nit |
| `egm-inventive-catalyst` (no standard) | completed | 7 Major, 5 Nit, 9 proposed transforms |

Both reviewers read their persona and the whole target. Zoom-out read every GoldfishContext file. Inventive-catalyst read the PRD, the gateway migration doc and `author-decisions.md` in full, read the gateway, audience and roles parts of `architecture-overview.md`, and only searched the other context files.

**After deduplication:** 1 Blocker, 12 Major, 9 Nit (22 findings). Five findings were raised by both reviewers and are merged below. Where the two gave different severities, the higher one is used.

Note: the inventive-catalyst persona only allows advisory severities, so it reported nothing above Major. Its F1 matches zoom-out's Blocker B1, and it said F1 "may deserve escalation". The merged finding is reported as a Blocker.

Source IDs in brackets: `ZO-` = zoom-out, `IC-` = inventive-catalyst.

## Prioritized Findings (Blockers -> Major -> Nits)

### 1. Existing Keycloak users and existing permission names cannot be brought under management - Severity: Blocker
* **Reviewer Lens:** `egm-zoom-out` [ZO-B1], `egm-inventive-catalyst` [IC-F1]
* **Affected Scope:** Lines 165-166, 221 (AD-15), 276-283 (GW-5), 297-303 (CUJ 1), 424-426 (B-12, pipeline step 4), 686 (`POST /roles` 409), 756 (5.1 step 3), 1125
* **Discrepancy Description**:
  * Pipeline step 4 (B-12) returns 403 to any token whose `sub` has no local `users` row. This includes `GET /me/menus`.
  * 5.1 step 3 returns 409 from `POST /users` when the username or email belongs to an existing Keycloak account with no local row.
  * Only the bootstrapper (AD-15) can adopt an existing Keycloak account, and only for the single `SUPER_ADMIN`.
  * Every current cce-insights-ui user already exists in realm `cce`. The gateway checks their `INSIGHTS_READ` role (`architecture-overview.md` §3, around lines 67 and 195-196). None of them has a local row. So once cce-insights-ui switches to `/me/menus`, the whole existing population gets 403 on the sidebar call. The API offers no way to create their local rows, so S1 and G7 fail in any real environment.
  * `INSIGHTS_READ` is an unowned `permission_name` under GW-5. `POST /roles` refuses that name (line 686). That leaves SQL as the only way to manage permissions for the one console that exists today, which defeats PRD §2 ("configured through the admin console rather than SQL").
  * The design never says that the inputs are silent on migrating existing users. Local runs cannot show the problem, because the local realm and the E2E tests only hold users the service created.
* **Rationale & Recommendation**: Decide this explicitly. Either:
  * add an audited *link* operation that adopts an existing Keycloak account with no local row, reusing the bootstrapper's lookup logic, plus an *adopt* operation for unowned `permission_name` values (see transform T1); or
  * state the migration of existing users and permissions as a Non-Goal, with an owner and an open question.

  In both cases, record the input gap as a deviation or open question, and say how `INSIGHTS_READ` relates to the new console roles.

### 2. `SYNCED` and the `SUCCESS` sync audit row claim the permission is "in effect", but the gateway may need a restart - Severity: Major
* **Reviewer Lens:** `egm-zoom-out` [ZO-M1], `egm-inventive-catalyst` [IC-F5]
* **Affected Scope:** Lines 212 (AD-06 rationale), 259-262 (GW-2), 627, 824, 1123, 1358
* **Discrepancy Description**:
  * AD-06 justifies `ROLE_SYNC_REQUESTED` and `syncStatus` as the auditor's answer to "is the saved permission actually in effect?".
  * The gateway migration doc says permissions load only at startup ("Currently, you need to restart", around line 187). Its own overview says "Update permissions without restarting the service" (around lines 125-131). The input contradicts itself, and the design does not say so. GW-2 quotes only the restart line.
  * Under GW-1's assumption, a 2xx from the sync proves only that the realm roles exist. An auditor reading `SUCCESS`, or the console showing `SYNCED`, can be told a revocation took effect while the gateway still enforces the old rows.
  * The service's step-5 check covers only `/admin/**`. Patterns for other services, such as `/v1/insights/**`, have no backstop.
* **Rationale & Recommendation**:
  * Record the contradiction inside the gateway doc.
  * Define `SYNCED` and `SUCCESS` narrowly in the contract and in AD-06 as "realm-role sync acknowledged (2xx)", not "enforced".
  * Add the restart dependency to the accepted risks, with what operators must do after each permission change until GW-2 is answered.
  * Make GW-2 a go/no-go item for the first shared environment.

### 3. A global `SUPER_ADMIN` lock serializes every status and role change and is held across Keycloak calls - Severity: Major
* **Reviewer Lens:** `egm-inventive-catalyst` [IC-F4, Major], `egm-zoom-out` [ZO-N1, Nit]
* **Affected Scope:** Lines 748, 751, 773 (5.3 step 2), 782 (5.4 step 4), 1003, 1357, 1573
* **Discrepancy Description**:
  * Every `PATCH /users/:id/status` and `PUT /users/:id/roles` locks the `SUPER_ADMIN` row with `SELECT … FOR UPDATE`, even when the target neither holds nor gains `SUPER_ADMIN`.
  * The lock and its database connection stay held across Keycloak calls with timeouts of up to 10 seconds each.
  * The accepted-risk row (line 1357) covers only stalls. It misses pool exhaustion:
    * Waiting requests each hold a connection.
    * The lock holder then fails a Keycloak call and needs a *second* connection for `recordInNewTransaction`.
    * That connection waits until the pool timeout, the audit insert fails, and the fallback at line 748 (log at `ERROR`) drops the compliance row.
    * `CurrentUserFilter` also needs a connection, so read-only requests stall as well.
* **Rationale & Recommendation**:
  * Take the lock only when the change can lower the count of `ACTIVE` `SUPER_ADMIN` holders, then re-read the target after locking (T4).
  * Reserve connections for audit writes, or size the pool for them.
  * Add the pool-starvation path to the accepted-risk table, or drop that row if the narrower lock removes the risk.

### 4. The in-memory permission snapshot adds staleness without a proven need - Severity: Major
* **Reviewer Lens:** `egm-zoom-out` [ZO-M2, Major], `egm-inventive-catalyst` [IC-F10, Nit]
* **Affected Scope:** Lines 433-436 (step 5), 1110 (`PERMISSION_CACHE_TTL`), 1341-1344, 1358, 1461
* **Discrepancy Description**:
  * Step 5 keeps a TTL snapshot of all of `api_permissions`, clears it after commits in 5.7 and 5.9, adds `PERMISSION_CACHE_TTL`, and accepts a risk that other instances lag by up to 30 seconds.
  * Its stated purpose is to add "no query per request". But step 4 already runs one `SELECT` and one conditional `UPDATE` (`last_login_at`) on every request, and PRD §6 calls this low-throughput CRUD.
* **Rationale & Recommendation**: Run one indexed query per request (`idx_permission_name`, `WHERE permission_name IN (...)`). That removes `PermissionSnapshot`, the TTL setting, the clear calls and the lag risk row (T9). If load ever grows, bring the cache back with push invalidation instead of a TTL.

### 5. A self-written path matcher replaces Spring's, and is shared with the stand-in by folder path - Severity: Major
* **Reviewer Lens:** `egm-zoom-out` [ZO-M3]
* **Affected Scope:** Lines 223 (AD-17 (1)), 438-455, 1462, 1508
* **Discrepancy Description**:
  * The design writes its own Ant-style matcher, then says (line 454) that it "agree[s] with Spring's `AntPathMatcher` for every pattern that section 4.5 accepts".
  * It already differs from `AntPathMatcher`: it strips one trailing `/`, while `AntPathMatcher` does not match `/x/` against `/x`.
  * To share the class, the stand-in's `pom.xml` uses `build-helper-maven-plugin` to compile `../src/main/java/org/cce/admin/security/matcher`, which ties one module's build to the other's folder layout.
* **Rationale & Recommendation**: Use `org.springframework.util.AntPathMatcher` (in `spring-core`, not a web framework, so it fits the stand-in's own rule) in both modules. Keep only the trailing-slash normalisation, if wanted, as a few lines of code. Otherwise use a proper Maven multi-module parent instead of build-helper.

### 6. The stand-in gateway is overbuilt and hand-writes security code - Severity: Major
* **Reviewer Lens:** `egm-zoom-out` [ZO-M4]
* **Affected Scope:** Lines 217 (AD-11), 1136, 1508-1519
* **Discrepancy Description**:
  * PRD §10 asks only to "stub the gateway sync call behind an interface so it can be a no-op or a mock server locally".
  * The stand-in is a separate Maven module with a shaded jar and its own Dockerfile. Under a self-imposed "no web framework" rule it hand-writes RS256 checking against the JWKS with only `java.security`, JWKS caching, and Keycloak Admin REST calls through `java.net.http`.
  * It also enforces the assumed GW-6 token rules, which are unconfirmed, so the extra check proves little about the real gateway.
* **Rationale & Recommendation**: A stand-in that creates realm roles is justified, because a no-op would leave every role `ROLE_NOT_SYNCED`. Build it as a minimal Spring Boot app on the parent POM already in use, with `spring-boot-starter-oauth2-resource-server` for tokens and `keycloak-admin-client` for realm roles. Or drop the token check and just require a bearer token. Either way, a lot of security-sensitive custom code goes away.

### 7. `GATEWAY_SYNC_SCOPE=ROLE` cannot be reached under the design's own assumptions - Severity: Major
* **Reviewer Lens:** `egm-zoom-out` [ZO-M5]
* **Affected Scope:** Lines 212 (AD-06), 253-258 (GW-1), 824, 1108, 1521
* **Discrepancy Description**:
  * GW-1 assumes the sync request has no body and no parameters, and says (line 258) that a per-role parameter "would need a code change".
  * AD-13 makes the service account the caller, so the gateway cannot know which role asked.
  * So the `ROLE` setting can never be used through configuration alone, yet it adds a branch in 5.10, a setting and a doubled test matrix. G10's claim that configuration absorbs gateway differences does not hold here.
* **Rationale & Recommendation**: Remove the setting and mark roles under the global rule only. If GW-1 comes back role-scoped, record that as a code change.

### 8. B-50's "one row per mutation" deviation is not recorded, and G8 contradicts the flows - Severity: Major
* **Reviewer Lens:** `egm-zoom-out` [ZO-M6]
* **Affected Scope:** Lines 122-139 (G8 and its check), 191-198 (Deviations), 794-796, 816-817, 1133, 1551
* **Discrepancy Description**:
  * B-50's acceptance criterion says "A test asserts one row per mutation."
  * The design writes two rows for a permission save, for `POST /roles` (`ROLE_CREATED` + `ROLE_SYNC_REQUESTED`) and for `DELETE /roles/:id` (`ROLE_DELETED` + `ROLE_SYNC_REQUESTED`). No-ops write no row. None of this is in the Deviations table, which PRD §11 requires.
  * G8 says only a permission save writes two rows, and its check (lines 137-139) lists only permission save and manual sync. Line 1551 says create and delete also write two rows.
* **Rationale & Recommendation**: Add a DEV row for B-50 covering two rows per state change and no row on a no-op. Restate G8 and its check to list create, delete and permission save.

### 9. The Author Decisions table duplicates the design and cites labels the document never defines - Severity: Major
* **Reviewer Lens:** `egm-zoom-out` [ZO-M7]
* **Affected Scope:** Lines 199-223 (especially AD-02, AD-06, AD-11, AD-13, AD-15, AD-16 at lines 212, 214, 216, 220), 652
* **Discrepancy Description**:
  * Several cells are 200-300 words of mechanism that sections 5-9 repeat.
  * The table carries drafting history: "G8 is amended to match", "G5 is amended to match", "AD-07 is amended to match", and AD-10's "Superseded part".
  * It cites OQ-1 to OQ-15, "pushback P1/P2/P4" and "console review CQ-1", none of which this document defines. Constraint 4 says this document is the coding agent's sole input, and that agent cannot resolve these labels.
* **Rationale & Recommendation**: Cut each cell to question, decision and reason, with a link to the section that holds the mechanism. Remove superseded and "amended to match" notes. Define or drop the OQ, P and CQ labels.

### 10. Partial failures all go to manual repair, though most flows recover if the same request is submitted again - Severity: Major
* **Reviewer Lens:** `egm-inventive-catalyst` [IC-F2]
* **Affected Scope:** Lines 175-177, 557-561 (`partialState` table), 747, 756, 763
* **Discrepancy Description**:
  * The `partialState` table tells the operator to "reconcile from `audit_log`" for `USER_CREATE` and `USER_ROLES_UPDATE`.
  * But 5.2, 5.3 and 5.4 compare against *local* state, and their Keycloak calls give the same result when repeated (set enabled, update the profile, add or remove a role mapping). Submitting the same request again already brings Keycloak and the database back in line. The design never says so.
  * 5.1 is a real dead end. After a 502 at `KEYCLOAK_ROLES_ASSIGNED` or `LOCAL_USER_SAVED`, a retry hits step 3 and gets 409 `USERNAME_IN_USE`. The only way out is a manual Keycloak deletion or an SQL insert, which is the hand editing the PRD exists to remove.
  * PRD §9 forbids *blind* retries. A resubmission that an administrator starts on purpose, and that is audited, is not blind.
* **Rationale & Recommendation**: State that 5.2 to 5.4 recover when submitted again. Make 5.1 resumable by tagging the account it creates (T2). Rewrite the "next step" column of the `partialState` table to match.

### 11. The partial-failure audit row exists only if the service process survives - Severity: Major
* **Reviewer Lens:** `egm-inventive-catalyst` [IC-F3]
* **Affected Scope:** Lines 105-114 (G5), 132-135 (G8), 748, 1003
* **Discrepancy Description**: G8 promises one row for every call that fails part-way, but that row is written only *after* the failure, by `recordInNewTransaction`. A JVM crash, pod eviction or rolling deploy, a lost database connection, or the fallback at line 748 ("logs the full partial state at `ERROR`") leaves Keycloak changed with no `audit_log` row at all. Logs are not the store of record for this compliance platform (PRD §9 Auditability).
* **Rationale & Recommendation**: Commit a write-ahead intent row before the first Keycloak call (T3), or record the crash window as an accepted risk with an operator procedure.

### 12. Drift is detected only when this service causes it - Severity: Major
* **Reviewer Lens:** `egm-inventive-catalyst` [IC-F6]
* **Affected Scope:** Lines 53 (Problem), 175-177, 223 (AD-17 (3)), 659-661, 1016
* **Discrepancy Description**:
  * Step 5, `/me` and `/me/menus` take roles from the *token*, so Keycloak is the effective authority. `user_roles` is a copy that nothing checks against Keycloak.
  * A role granted directly in the Keycloak admin console (for example `SUPER_ADMIN`) works at the gateway and in the service, while `user_roles` and `audit_log` show nothing. "Who gave this user access" then cannot be answered, which is the exact failure the Problem section names.
  * The Non-Goal forbids *automatic repair*, not *detection*.
* **Rationale & Recommendation**: Add a drift detector that only reads and reports and never repairs (T5), or record this gap as an explicit Non-Goal or accepted risk.

### 13. No overall time limit for a request that makes many Keycloak calls - Severity: Major
* **Reviewer Lens:** `egm-inventive-catalyst` [IC-F7]
* **Affected Scope:** Lines 403-404, 750, 756, 1102
* **Discrepancy Description**: `POST /users` can make about 25 Keycloak calls in a row: username and email lookups, up to 20 realm-role lookups, then create, assign and email. Each call has a 10-second timeout. Behind a gateway whose proxy timeout is unknown (it is not among the GW questions), the client can get a 504 while the service goes on to succeed. The administrator retries and gets 409 (see finding 10). This hidden success produces no 502 and no `partialState`.
* **Rationale & Recommendation**: Give each request a deadline that bounds the sum of its calls (T6), and add the gateway's proxy timeout as GW-7.

### 14. Client IP resolution is custom-built and trusts the forgeable first `X-Forwarded-For` value by default - Severity: Nit
* **Reviewer Lens:** `egm-zoom-out` [ZO-N3], `egm-inventive-catalyst` [IC-F12]
* **Affected Scope:** Lines 1005-1007, 1111, 1465
* **Discrepancy Description**: `ClientIpResolver` and `AUDIT_TRUST_FORWARDED_FOR` re-implement a standard Spring setting. The resolver takes the first `X-Forwarded-For` value, which the client controls. The default `true` trusts that header even when no gateway sits in front, as in local runs. On a compliance platform, the IP in an audit row can therefore be forged.
* **Rationale & Recommendation**: Use `server.forward-headers-strategy` (`ForwardedHeaderFilter`) and read the remote address. Or take the rightmost entry, minus a configured number of trusted proxies. Either way you need to know how the gateway forwards headers, which is another GW question.

### 15. `ServiceTokenProvider` repeats token handling the Keycloak client already does - Severity: Nit
* **Reviewer Lens:** `egm-zoom-out` [ZO-N2]
* **Affected Scope:** Lines 219, 992-997, 1503
* **Discrepancy Description**: It runs the client-credentials grant and caches the token for the same `cce-admin-service` client that the `keycloak-admin-client` bean already authenticates as.
* **Rationale & Recommendation**: Reuse `Keycloak.tokenManager().getAccessTokenString()`, or Spring Security's client-credentials `OAuth2ClientHttpRequestInterceptor` for `RestClient`.

### 16. The G9 offline row check checks the test data against itself - Severity: Nit
* **Reviewer Lens:** `egm-zoom-out` [ZO-N4]
* **Affected Scope:** Lines 140-150 (G9), 1135, 1600-1602
* **Discrepancy Description**: It runs scenario rows through the same assumed matcher they were written for. With AD-17, step 5 enforces the hard control live, and `HardControlE2E` already checks it.
* **Rationale & Recommendation**: Fold the cases into `PermissionMatcherTest`, and drop `scenario-rows.json` and `OfflinePermissionRowCheckTest`.

### 17. The E2E harness hand-writes clients that are already dependencies - Severity: Nit
* **Reviewer Lens:** `egm-zoom-out` [ZO-N5]
* **Affected Scope:** Lines 1617-1621
* **Discrepancy Description**: `E2eHttp` and `KeycloakTestAdmin` hand-write HTTP and Keycloak Admin REST calls, while `RestClient` and `keycloak-admin-client` are already on the classpath.
* **Rationale & Recommendation**: Use the existing clients.

### 18. The `CCE_ADMIN_SERVICE` chain rests on the unanswered GW-6 - Severity: Nit
* **Reviewer Lens:** `egm-zoom-out` [ZO-N6]
* **Affected Scope:** Lines 219-220, 284-290, 953-960, 1120
* **Discrepancy Description**: These exist only because GW-6 is assumed: the `CCE_ADMIN_SERVICE` role row, the `is_assignable` column and its no-default rule, `ROLE_NOT_ASSIGNABLE`, its seed rows, and bootstrap step 3.
* **Rationale & Recommendation**: Mark the chain as provisional, and get GW-6 answered before Phase 4 is built.

### 19. B-05 is misquoted - Severity: Nit
* **Reviewer Lens:** `egm-zoom-out` [ZO-N7]
* **Affected Scope:** Lines 197, 497-498
* **Discrepancy Description**: B-05 says `{status:"UP"}` "with best-effort DB status". It does not mention a `database` field. The `{status, database}` shape is reasonable, but it is the design's own choice, not "as B-05 writes it".
* **Rationale & Recommendation**: Reword both places to present the shape as the design's choice.

### 20. The no-retry rule is applied to the gateway sync, which is safe to repeat - Severity: Nit
* **Reviewer Lens:** `egm-inventive-catalyst` [IC-F8]
* **Affected Scope:** Lines 175-177, 747, 812, 820-827
* **Discrepancy Description**: PRD §9's rule against blind retries is about partial Keycloak user state. A `GLOBAL` sync is safe to repeat. Even so, a gateway outage leaves roles `FAILED` until an administrator presses Re-sync for each one.
* **Rationale & Recommendation**: Consider a bounded, audited background re-sync of `FAILED` roles (T7), keeping the 502 and S6 unchanged. Either way, state explicitly whether this conflicts with PRD §9.

### 21. AD-17's switch has only "enforce" and "off" - Severity: Nit
* **Reviewer Lens:** `egm-inventive-catalyst` [IC-F9]
* **Affected Scope:** Lines 223, 429, 1109, 1350
* **Discrepancy Description**: If GW-3 proves wrong, the only fix is to turn defence in depth off completely. GW-3 is still unknown in the first shared environment, which is exactly where this would happen.
* **Rationale & Recommendation**: Add a `shadow` mode that logs would-be denials without enforcing them (T8).

### 22. A race in `GLOBAL` marking can show `SYNCED` too early - Severity: Nit
* **Reviewer Lens:** `egm-inventive-catalyst` [IC-F11]
* **Affected Scope:** Lines 212, 824
* **Discrepancy Description**: A role created while another administrator's sync is in flight can be marked `SYNCED`, because marking checks "has at least one row" at commit time, not at send time. Only the display is affected, because AD-10's Keycloak lookup is the real check.
* **Rationale & Recommendation**: Record the set of role ids before sending the sync call, and mark only those roles.

## Proposed Inventive Transforms (from `egm-inventive-catalyst`)

These ideas are optional. Each one names the finding it resolves and the new costs it brings.

| ID | Pattern | Resolves | Ideal Final Result | Secondary liabilities |
| --- | --- | --- | --- | --- |
| T1 | Segmentation (separate creating from linking) | Finding 1 | `POST /users/link` (or `POST /users` with `keycloakUserId`) adopts an existing, enabled account: it checks username and email, skips creation, adds mappings and writes `USER_LINKED`. `POST /roles/adopt` turns an unowned `permission_name` into a managed role, with its existing rows in the before JSON. Together they generalise the bootstrapper's adopt path. | Two new endpoints, which the console contract and AS-REQs must cover (D-01). Adopted rows may break the pattern syntax in 4.4, so they need a "legacy, read-only until edited" rule. Adding implicit rows to an adopted role triggers a sync. Risk of linking the wrong person, so it needs an exact username and email match plus an audit row. |
| T2 | Blessing in Disguise (a leftover account becomes a resume point) | Finding 10 | 5.1 step 4 tags the new account with the attribute `cce-admin-create=<auditIntentId>`. If step 3 finds a tagged account with no local row and the same username and email, the flow resumes from `KEYCLOAK_USER_CREATED` instead of returning 409. 5.2 to 5.4 are documented as recovering on resubmission. | Keycloak attributes to manage. The resume path has its own state matrix to test. Stale tags must expire or be cleared by an operator. |
| T3 | Preliminary Action (write-ahead intent) | Findings 11, 10 | Before the first Keycloak call, commit an `audit_log` row with outcome `STARTED`. The final row refers to it. A query for "`STARTED` with no outcome row" finds every crash window. | One extra insert per Keycloak flow. G8's row counts change (B-50 and the guard test must be updated), or a separate intent table is needed, which breaks B-01's "seven tables". Leftover intents need an operator procedure. |
| T4 | Segmentation (lock only when it can matter) | Finding 3 | Take the `SUPER_ADMIN` lock only when the target holds `SUPER_ADMIN` (any status) or the change adds or removes it, and re-read the target after locking. Optionally commit the local check-and-reserve before the Keycloak calls so the lock is not held across remote I/O. | Subtle race reasoning, which needs the "holds the role in any status" condition and a concurrency test for each path. Moving the commit earlier changes the step order and step names in 5.3 and 5.4. |
| T5 | Self-Service (a reconciliation loop that only detects) | Finding 12 | A scheduled job compares `user_roles` and `users.status` with Keycloak's mappings and `enabled` flag, and reports drift as log lines or a metric. It never repairs, so it stays inside the Non-Goal. | Load on the Keycloak Admin API (paginated scans). False positives while requests are in flight. With several instances, only one should run it (for example via a Postgres advisory lock). One more component to run. |
| T6 | Dynamics (a deadline budget per request) | Finding 13 | Each flow has a total deadline set below the gateway's proxy timeout. Before the first named step, a short remaining budget fails fast with nothing changed. After it, the remaining budget limits each call's timeout. | Needs the gateway's timeout, which is unknown. Behaviour changes with load. One more setting to keep in step with the gateway. |
| T7 | Beforehand Cushioning with Self-Service (bounded, repeatable re-sync) | Finding 20 | A background task re-runs the global sync with backoff while any role is `FAILED`, writing an audited `ROLE_SYNC_REQUESTED` row with actor `system:resync`. The 502 and S6 stay unchanged. | Can hide a gateway outage. Adds audit rows. Needs a single runner across instances. Its fit with PRD §9's wording must be argued explicitly. |
| T8 | Blessing in Disguise (shadow mode turns disagreement into a signal) | Finding 21 | `PERMISSION_CHECK_MODE=enforce\|shadow\|off`. In `shadow`, the service logs and counts every request it would have denied but the gateway allowed, which checks GW-3 against live traffic before `enforce` is turned on. | No protection while in shadow. Log volume. A third mode to test. |
| T9 | Cheap Dispensables (drop the cache) | Finding 4 | Step 5 runs one indexed `SELECT` per request for the caller's role names, so changes are visible on every instance at once. | One more query per request, small next to step 4's two. If load grows, the cache returns, ideally invalidated by Postgres `NOTIFY`, but that adds a trigger on a gateway-owned table. |

## Files Reviewed

- Target: `/home/jayaprakashn/Documents/workspace/elephant-goldfish-trial/docs/designs/cce-admin-service.md`
- Personas: `/home/jayaprakashn/.claude/plugins/cache/egm/egm/0.1.1/agents/zoom_out.md`, `/home/jayaprakashn/.claude/plugins/cache/egm/egm/0.1.1/agents/inventive_catalyst.md`
- Standard: `/home/jayaprakashn/.claude/plugins/cache/egm/egm/0.1.1/resources/document_standards/zoom-out-standard.md`
- GoldfishContext: the PRD, `DATABASE_PERMISSIONS_MIGRATION.md`, the three `cce-insights-ui/docs/` files, `docs/designs/author-decisions.md`, `egm/admin-ui/design_draft.md` (zoom-out checked the draft's AS-REQ table against section 4.6 and found them consistent)
