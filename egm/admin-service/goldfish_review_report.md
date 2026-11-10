Status: resolved

# Goldfish Annotated Review Report

**Resolution (auto accept):** all 39 PROPOSE FIX items were applied to
`docs/designs/cce-admin-service.md`, and `egm/admin-service/design_draft.md` was replaced with
an identical copy. Accepted fixes: 39. Contested: 0. Escalated: 0. Declined: 0. Unaddressed: 0.
Two points were settled while applying them:
*   **CA-04's row counts.** The author kept the sync attempt on role create (AD-16), so
    `POST /roles` writes two rows (`ROLE_CREATED` and `ROLE_SYNC_REQUESTED`), as the permission
    save does. CA-04's "the permission save is the only call that writes two rows" therefore
    does not hold, and the design says "two rows each". DEV-7 records it.
*   **CA-01's bootstrapper.** The bootstrapper knows only a username, so it shares the *linking
    method* (`AccountLinker`, 5.1 steps 5 and 6) rather than the username-and-email lookup.
    Re-running the stage reviews (Stages 7 and 9) is the next step, per the consolidated report.

**Target:** `docs/designs/cce-admin-service.md` (`full file`, 1,653 lines, byte-identical to the reviewed text)

**Findings:**
*   The 33 consolidated actions CA-01 to CA-33 from
    [consolidated_review_report.md](consolidated_review_report.md).
*   The six "Items for the admin-service design" from
    [the console review](../admin-ui/consolidated_review_report.md), here called UI-1 to UI-6.
    The request named five; the console report lists six, and item 6 is included.

**Author decisions applied:** the author decided CA-01, CA-04, CA-05, CA-07, CA-08, CA-09,
CA-10, CA-11 and CA-14, and directed that every other action follow the reports, using Stage
8's direction where stages conflict. Those decisions are written into the proposed edits below
and are not put to the author again.

**Categories:** 39 PROPOSE FIX, 0 CONTEST, 0 ESCALATE, 0 DECLINE. I checked each finding's
claim against the text. None rests on a misreading, and none needs knowledge outside the files
the reviewers had.

Line numbers refer to the current target. Links are relative to `egm/admin-service/`.

## Prioritized Findings & AI Suggestions

### [Issue 1] CA-01 [`egm-zoom-out`, `egm-inventive-catalyst`, `egm-design`] - Severity: Blocker
*   **Affected Scope:** [Lines 165-177](../../docs/designs/cce-admin-service.md#L165-L177) (G5), [221](../../docs/designs/cce-admin-service.md#L221) (AD-15), [276-283](../../docs/designs/cce-admin-service.md#L276-L283) (GW-5), [424-426](../../docs/designs/cce-admin-service.md#L424-L426), [686](../../docs/designs/cce-admin-service.md#L686), [756](../../docs/designs/cce-admin-service.md#L756), [763](../../docs/designs/cce-admin-service.md#L763)
*   **Discrepancy Description:** Existing Keycloak accounts cannot be brought under management, and a failed user creation cannot be retried.
*   **Rationale:** Every current cce-insights-ui user exists in Keycloak with no local row and would get 403 once that console reads `/me/menus`. `POST /users` returns 409 for them. Only the bootstrapper adopts accounts. A 502 after the account is created leaves an account only Keycloak edits or SQL can repair. `POST /roles` refuses `INSIGHTS_READ`.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate: 5.1 step 3 returns 409 for any Keycloak-only account. Author decision: Stage 8's single mechanism.
*   ***Proposed Edit:***
    *   Location: 5.1 step 3; section 7 (`KeycloakGateway`); AD-15; Non-Goals; GW-5; G5; `partialState` table (`USER_CREATE` row); 4.6 (AS-REQ-05, AS-REQ-06); Risks; Phase 6 tests.
    *   Change:
        *   5.1 step 3: if an **enabled** Keycloak account matches both username and email exactly and has no local row, link it. Skip step 4, run steps 5 to 7, and write `USER_CREATED` with `after.linked = true`. Any other match is still 409.
        *   Add `findLinkableUser(username, email)` to `KeycloakGateway`. The bootstrapper calls the same method.
        *   Retrying a `POST /users` after a 502 at `KEYCLOAK_ROLES_ASSIGNED` or `LOCAL_USER_SAVED` now links the account, so the `USER_CREATE` "next step" becomes "submit the same request again".
        *   Non-Goal: adopting unowned `permission_name` values such as `INSIGHTS_READ`. Add an open question under GW-5. A rollout note says `INSIGHTS_READ` stays managed by the gateway.
        *   Rollout note: before cce-insights-ui switches its sidebar to `/me/menus`, every existing console user must be onboarded with `POST /users`, which links their accounts.
        *   No new endpoints and no account tagging.
        *   Add the link case, and the "enabled account but email differs" 409 case, to `UserCreateFlowTest`.

### [Issue 2] CA-02 [`egm-implementation-readiness`] - Severity: Blocker
*   **Affected Scope:** [Lines 1400](../../docs/designs/cce-admin-service.md#L1400), 1437, 1454, 1480, 1483, 1506, 1551, 1641-1642 (Phases 1 to 6, Verification Plan)
*   **Discrepancy Description:** Tests have no configuration, so `./mvnw verify` fails on a clean checkout and reads the developer's `.env` elsewhere.
*   **Rationale:** Required `admin.*` properties have no defaults, and `spring.config.import` loads `.env` in tests. The bootstrapper also runs in every Spring test.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate: phase 3 makes the required properties fail startup, and no test profile exists.
*   ***Proposed Edit:***
    *   Location: Phase 3 file table; `application.yml` rows; Phase 4 (`BootstrapAdminRunner`); Phase 2 (`PostgresTestContainer`).
    *   Change:
        *   Add `src/test/resources/application-test.yml`, activated with `@ActiveProfiles("test")` on every Spring test. It holds fixed values for every required `admin.*` property and overrides `spring.config.import` to empty, so `.env` is never read in tests.
        *   `BootstrapAdminRunner` is `@Profile("!test")`, and `BootstrapAdminRunnerTest` constructs it directly.
        *   `PostgresTestContainer` is a static container in an abstract `DatabaseTestBase`, attached with `@ServiceConnection` through `@Testcontainers` and `@Container`.

### [Issue 3] CA-03 [`egm-implementation-readiness`, `egm-codebase-details`] - Severity: Blocker
*   **Affected Scope:** [Lines 895-899](../../docs/designs/cce-admin-service.md#L895-L899) (section 6, `api_permissions`), 1428, 1438, 1441
*   **Discrepancy Description:** The gateway fixture is "copied" from DDL that the gateway doc does not contain.
*   **Rationale:** The gateway doc names the two indexes and a trigger in prose only.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Verified: lines 44-49 of the gateway doc have no index or trigger DDL.
*   ***Proposed Edit:***
    *   Location: section 6 `api_permissions` entry; Phase 2 fixture rows.
    *   Change:
        *   Replace "exactly as in the gateway migration doc" with "this design's reconstruction of the gateway DDL". The columns and unique constraint are quoted from the doc. The two indexes and the trigger are reconstructed from its prose, and that is recorded as an assumption under GW-5.
        *   Write the full fixture SQL into Phase 2: `CREATE INDEX idx_permission_name ON api_permissions (permission_name)`, `CREATE INDEX idx_resource_category ON api_permissions (resource_category)`, the function `update_api_permissions_updated_at()` setting `NEW.updated_at = CURRENT_TIMESTAMP`, the trigger `trg_api_permissions_updated_at BEFORE UPDATE … FOR EACH ROW`, and the doc's `USER_READ` and `USER_CREATE_UPDATE` `INSERT`s.

### [Issue 4] CA-04 [`egm-comprehension`, `egm-zoom-out`, `egm-design`] - Severity: Major
*   **Affected Scope:** [Lines 122-139](../../docs/designs/cce-admin-service.md#L122-L139) (G8), 191-198, 542, 548-576, 748, 792-796, 812-818, 914, 1026-1027, 1133, 1551, 1561
*   **Discrepancy Description:** The audit-row and 502 rules contradict each other, G8's row counts and the B-50 deviation are wrong.
*   **Rationale:** A manual sync both writes and does not write a row. A failed sync is described as both `PARTIAL_FAILURE` and `FAILED`. `KEYCLOAK_SYNC_FAILED` is limited in one place and broadened in another. Role create and delete write two rows that G8 does not mention. B-50's deviation is not recorded.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate: 4.2's "no audit row" for failures before any change contradicts 5.8 and AD-06. Author decision: the single audit rule, reopening AD-12 and AD-16.
*   ***Proposed Edit:***
    *   Location: G8, 4.2, `partialState` table, 5.6, 5.8, 5.9, 5.10, section 8, AD-06, AD-12, AD-16, Deviations, Phase 5.
    *   Change:
        *   State one rule once, in section 8, and point G8, 4.2, 5 and 10 to it:
            1.  Every sync attempt writes one `ROLE_SYNC_REQUESTED` row with its outcome.
            2.  Every 502 after a change or a sync attempt points `auditLogId` at the row that records the failure.
            3.  A failure before any change and before any sync writes nothing.
            4.  There is one failure outcome, `FAILED`, with the `failure` JSON. `PARTIAL_FAILURE` is removed.
        *   Role delete no longer syncs (AD-12 is rewritten, and `ROLE_DELETE` leaves the `partialState` table). Role create still writes the implicit rows and attempts the sync, but a failed sync returns 201 with the new warning `ROLE_SYNC_FAILED` (AD-16; `ROLE_CREATE` leaves the `partialState` table).
        *   The permission save is then the only call that writes two rows.
        *   Add DEV-7 for B-50 ("one row per mutation" becomes one row per state change).
        *   The Phase 5 exit check lists the expected `action`/`outcome` pairs for create, menus, the failed save and the re-sync.

### [Issue 5] CA-05 [`egm-design`, `egm-zoom-out`, `egm-comprehension`, `egm-implementation-readiness`] - Severity: Major
*   **Affected Scope:** [Lines 219-221](../../docs/designs/cce-admin-service.md#L219-L221) (AD-13, AD-15), 838, [1115-1125](../../docs/designs/cce-admin-service.md#L1115-L1125) (9.5), 1500, 1506, 1522
*   **Discrepancy Description:** The first-deploy runbook fails if followed in order.
*   **Rationale:** Step 6 restarts the gateway after a migration that only runs at step 7. The bootstrapper's sync then fails. "The role that asked" is undefined for the bootstrapper. `KeycloakUserInfo` lacks fields the bootstrapper inserts. The chain rests on GW-6.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Verified: 9.5 step 6 precedes the migration in step 7. Author decision applied.
*   ***Proposed Edit:***
    *   Location: AD-15, 5.13, 9.5, section 7, Phase 4, GW-6.
    *   Change:
        *   Delete AD-15 step (4). If the `SUPER_ADMIN` realm role is missing, the bootstrapper logs an error and stops.
        *   9.5 step 3 creates both realm roles, `CCE_ADMIN_SERVICE` and `SUPER_ADMIN`, by hand. The realm import does this locally.
        *   Reorder 9.5 so the gateway reload request comes after the first deploy has migrated.
        *   `KeycloakUserInfo` gains `username`, `email`, `firstName` and `lastName`.
        *   `BootstrapAdminRunnerTest` gains the "realm role missing" refusal case, replacing "runs a sync".
        *   AD-13, GW-6 and 9.5 mark the `CCE_ADMIN_SERVICE` chain as provisional until GW-6 is answered.

### [Issue 6] CA-06 [`egm-zoom-out`, `egm-inventive-catalyst`, `egm-design`] - Severity: Major
*   **Affected Scope:** [Lines 512-514](../../docs/designs/cce-admin-service.md#L512-L514), 748-751, 756, 771-785, 824, 974, 1003, 1357, 1573, 1578
*   **Discrepancy Description:** One global lock serializes every status and role change across Keycloak calls.
*   **Rationale:** 5.4 computes the diff before the lock. The `SUPER_ADMIN` lock is held across up to about 25 Keycloak calls. Waiting requests hold pool connections.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate: the diff in 5.4 step 2 precedes the lock in step 4.
*   ***Proposed Edit:***
    *   Location: section 5 shared rules, 5.2 to 5.4, section 7, Risks row, Phase 6.
    *   Change:
        *   5.2 to 5.4 begin with `SELECT … FOR UPDATE` on the user's `users` row and compute the diff after it.
        *   The `SUPER_ADMIN` row lock is taken only when the change can lower the number of `ACTIVE` `SUPER_ADMIN` holders: a status leaving `ACTIVE` for a holder, or `SUPER_ADMIN` in `removed`.
        *   Replace the per-role `findRealmRole` calls with one `listRealmRoleNames()` call.
        *   Rewrite the lock risk row to match. Add one alternative for the locking strategy (see CA-19). No `lock_timeout`, advisory locks or deadline budgets.

### [Issue 7] CA-07 [`egm-zoom-out`, `egm-design`, `egm-implementation-readiness`] - Severity: Major
*   **Affected Scope:** [Lines 140-150](../../docs/designs/cce-admin-service.md#L140-L150) (G9), 223 (AD-17), [429-455](../../docs/designs/cce-admin-service.md#L429-L455) (step 5), 653, 1109-1110, 1131-1136, 1341-1344, 1358, 1461-1463, 1508-1519, 1546-1547, 1600-1602, 1622-1628
*   **Discrepancy Description:** AD-17's own permission check drives most of the matcher, cache and stand-in complexity.
*   **Rationale:** A hand-written matcher that differs from `AntPathMatcher` and is shared between modules by folder path, a TTL snapshot not cleared on role create or delete (an intermittent 403 in S1), hand-written RS256 checks in the stand-in, and an undefined offline-check fixture.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate. Author decision: option (b), with the reason "defence in depth on the API that grants access, at a small fixed cost".
*   ***Proposed Edit:***
    *   Location: G9, AD-17, AD-11, AD-13, section 3 step 5, 4.4 `uriPattern`, 9.4, 10, Risks, Phases 3, 4 and 7.
    *   Change:
        *   Step 5 uses Spring's `AntPathMatcher` and one indexed query per request: the rows whose `permission_name` is in the token roles, using `idx_permission_name`. There is no snapshot and no TTL. The boolean switch `PERMISSION_CHECK_ENABLED` stays.
        *   Delete `PermissionMatcher`, `PermissionSnapshot`, `PERMISSION_CACHE_TTL`, the build-helper source sharing, the offline row check, `scenario-rows.json` and the G9 offline-check text.
        *   The stand-in checks only that a bearer token is present (401 otherwise). `TokenVerifier` is deleted.
        *   `uriPattern` allows `**` only as the last segment. T8 (shadow mode) is rejected.
        *   Keep the live hard-control E2E, where a console user gets 403 on `GET /users`. It is the only local evidence that the PRD's hard control works.

### [Issue 8] CA-08 [`egm-zoom-out`, `egm-comprehension`, `egm-design`] - Severity: Major
*   **Affected Scope:** [Line 212](../../docs/designs/cce-admin-service.md#L212) (AD-06), [259-262](../../docs/designs/cce-admin-service.md#L259-L262) (GW-2), 627, 824, 1123, 1358
*   **Discrepancy Description:** `SYNCED` is presented as "in effect", but the gateway may need a restart, and the gateway doc contradicts itself.
*   **Rationale:** Gateway doc line 8 says "without restarting". Line 187 says a restart is needed.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Verified both lines. Author decision: GW-2 is a go/no-go before the first shared environment, not before local Phases 4 and 5.
*   ***Proposed Edit:***
    *   Location: GW-2, AD-06, 4.4 `syncStatus`, section 8, Risks, 9.5.
    *   Change:
        *   GW-2 quotes both statements and records the conflict.
        *   `SYNCED` and `SUCCESS` mean "the last sync attempt got a 2xx", not "enforced".
        *   Add a Risks row for a gateway that loads permissions only on restart: the gateway owners restart it after each permission change until GW-2 is answered.
        *   GW-2 is a go/no-go item before the first shared environment. Locally, the stand-in defines the behaviour.
        *   Drop "likely the slower of the two anyway".

### [Issue 9] CA-09 [`egm-zoom-out`, `egm-comprehension`] - Severity: Major
*   **Affected Scope:** [Line 212](../../docs/designs/cce-admin-service.md#L212), [253-258](../../docs/designs/cce-admin-service.md#L253-L258) (GW-1), 627-628, 820-827, 1023, 1108, 1505, 1521
*   **Discrepancy Description:** The sync bookkeeping tracks more than the sync is known to do.
*   **Rationale:** `GATEWAY_SYNC_SCOPE=ROLE` cannot be used under GW-1 and AD-13. The `GLOBAL` fan-out has a race. `markedSyncedRoleIds` is unused.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate. Author decision: Z8-5.
*   ***Proposed Edit:***
    *   Location: AD-06, GW-1, 5.10, section 8 table, 9.4, Phase 4 tests.
    *   Change: remove `GATEWAY_SYNC_SCOPE` and the fan-out marking. `sync_status` and `last_sync_at` change only for the role that asked. Drop `scope` and `markedSyncedRoleIds` from the audit JSON.

### [Issue 10] CA-10 [`egm-design`, `egm-zoom-out`, `egm-implementation-readiness`] - Severity: Major
*   **Affected Scope:** [Line 208](../../docs/designs/cce-admin-service.md#L208) (AD-02), 895-901, 921-927, 1118, 1346, 1428-1442
*   **Discrepancy Description:** V1 alters an existing gateway-owned table, which breaks on ownership, Flyway history and seeding order.
*   **Rationale:** Index and trigger DDL fails on a table owned by another user. The two services may share `flyway_schema_history`. No grants are listed. V2 names the constraint and seeds rows before V3 checks the table.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate. Author decision: narrows AD-02.
*   ***Proposed Edit:***
    *   Location: AD-02, section 6 migrations, Non-Functional rollout, 9.5, Phase 2.
    *   Change:
        *   V1 creates `api_permissions` with its constraint, indexes and trigger only when the table is absent, and never alters an existing one.
        *   Renumber: `V2__verify_api_permissions.sql` (shape check), then `V3__seed.sql`, using `ON CONFLICT (permission_name, http_method, uri_pattern) DO NOTHING`.
        *   Set `spring.flyway.table: admin_service_flyway_history`.
        *   9.5 lists the DML grants on `api_permissions` for the service's database user and the `pgcrypto` prerequisite.
        *   Correct "migrations only add tables and rows".
        *   Migration tests each create a uniquely named database in the shared container and run Flyway directly, with no Spring context.

### [Issue 11] CA-11 [`egm-design`] - Severity: Major
*   **Affected Scope:** [Lines 686](../../docs/designs/cce-admin-service.md#L686), 793, 815
*   **Discrepancy Description:** `POST /roles` silently takes over a realm role that already exists in Keycloak.
*   **Rationale:** The name check covers `roles` and `api_permissions` but not Keycloak.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate. Author decision: take it, and record the risk that interacts with CA-04.
*   ***Proposed Edit:***
    *   Location: 4.5 `POST /roles` and `DELETE /roles/:id`, 5.6, 5.9, Risks.
    *   Change:
        *   5.6 step 1 also calls `findRealmRole(name)` and returns 409 `DUPLICATE_ROLE_NAME` if the realm role exists.
        *   5.9 step 1 also asks Keycloak whether any user holds the realm role (`getRealmRoleMemberCount(name)`) and returns 409 `ROLE_IN_USE` if so.
        *   Risks row: since role delete no longer syncs (CA-04), a deleted role's name stays unusable until some sync removes its realm role, which depends on GW-4.

### [Issue 12] CA-12 [`egm-design`, `egm-zoom-out`] - Severity: Major
*   **Affected Scope:** [Lines 549-551](../../docs/designs/cce-admin-service.md#L549-L551), 748, 763, 913
*   **Discrepancy Description:** `audit_log.entity_id NOT NULL` loses the B-32 partial-failure row.
*   **Rationale:** No user id exists before the local insert.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate.
*   ***Proposed Edit:***
    *   Location: 4.2 `entityId` text, 5.1.
    *   Change: 5.1 generates the user's UUID before step 4 and uses it as `entity_id` and in `partialState.entityId`. Lines 550-551 now say that a `POST /users` 502 always comes before the local row exists, and `entityId` is the id the user will get.

### [Issue 13] CA-13 [`egm-zoom-out`, `egm-inventive-catalyst`, `egm-design`] - Severity: Major
*   **Affected Scope:** [Lines 52-53](../../docs/designs/cce-admin-service.md#L52-L53), 175-177, 223, 328-334, 557-561, 658-661, 748, 1003, 1331-1360
*   **Discrepancy Description:** Partial-failure recovery, the crash window, drift and the revocation window are unstated.
*   **Rationale:** No recovery path for 5.2 to 5.4, no statement of the process-crash window, drift or token lifetime.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate. Stage 8's direction: no new mechanisms.
*   ***Proposed Edit:***
    *   Location: section 5 shared rules, `partialState` table, Risks, section 6 (queries), section 8.
    *   Change:
        *   5.2 to 5.4 recover when the same request is submitted again, because each step is skipped when Keycloak already holds the target state. Update the "next step" column.
        *   Accepted risks: the crash window before the failure row is written; Keycloak drift (for example a `SUPER_ADMIN` mapped directly in Keycloak); the token revocation window (300 s locally, unknown elsewhere).
        *   Add a read-only query for outstanding `FAILED` rows next to the GW-5 query.
        *   One structured `ERROR` log event per failure.
        *   Manual-only re-sync does not conflict with PRD §9, which asks for surfacing, not automatic retry.

### [Issue 14] CA-14 [`egm-zoom-out`, `egm-design`, `egm-style`] - Severity: Major
*   **Affected Scope:** [Lines 376-377](../../docs/designs/cce-admin-service.md#L376-L377), 1005-1007, 1111, 1465
*   **Discrepancy Description:** The audit IP address can be forged.
*   **Rationale:** The first `X-Forwarded-For` value is trusted by default.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate. Author decision: Spring's native support with trusted proxies.
*   ***Proposed Edit:***
    *   Location: section 3, section 8, 9.4, Phase 3, GW section, Risks.
    *   Change:
        *   `server.forward-headers-strategy: native` with Tomcat's `RemoteIpValve` trusted proxies from `TRUSTED_PROXIES` (default: none). The audit IP is `request.getRemoteAddr()`.
        *   Delete `ClientIpResolver` and `AUDIT_TRUST_FORWARDED_FOR`.
        *   "Reached only through the gateway" becomes "in non-local environments".
        *   Add GW-7: how the gateway forwards client addresses.
        *   Add a Risks row for a wrong `TRUSTED_PROXIES` value.

### [Issue 15] CA-15 [`egm-comprehension`] - Severity: Major
*   **Affected Scope:** Overview; Technical Plan sections 1 and 6; AD-11, AD-16; section 3 step 5
*   **Discrepancy Description:** The chain from role to `permission_name` to realm role to token is never stated.
*   **Rationale:** A fresh reader cannot connect the four.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** This is a comprehension gap, which is not contested.
*   ***Proposed Edit:***
    *   Location: section 1, after "What the service does not do".
    *   Change: add one sentence. A role's rows carry the role's `name` as `permission_name`. The gateway sync creates a realm role with that name. Once it is assigned, the token lists it in `realm_access.roles`, and the gateway and step 5 use it to select rows.

### [Issue 16] CA-16 [`egm-design`, `egm-structural`] - Severity: Major
*   **Affected Scope:** [Lines 248-290](../../docs/designs/cce-admin-service.md#L248-L290), 411-413, 1113, 1353-1360
*   **Discrepancy Description:** There is no standalone Risks section with mitigations.
*   **Rationale:** Risks are spread out and have no mitigation column.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate.
*   ***Proposed Edit:***
    *   Location: replace "Risks accepted by the author" with `## Risks & Mitigations`.
    *   Change:
        *   Columns: Risk, Effect, Mitigation, Owner.
        *   Rows: GW-1 to GW-7; the security switches with their required shared-environment values (`SPRINGDOC_ENABLED`, the stand-in's control endpoint as local only, `PERMISSION_CHECK_ENABLED=true`, `TRUSTED_PROXIES`); and the risks from CA-06, CA-08, CA-11, CA-13 and CA-14.
        *   Reword "Locally everything passes" to "Local checks can pass".

### [Issue 17] CA-17 [`egm-structural`] - Severity: Major
*   **Affected Scope:** [Lines 21](../../docs/designs/cce-admin-service.md#L21), 41, 230, 652
*   **Discrepancy Description:** Links point to the console's scratch draft, not its saved design.
*   **Rationale:** `docs/designs/cce-admin-ui.md` exists.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Verified.
*   ***Proposed Edit:***
    *   Location: Inputs table, Open Questions intro, 4.4 `httpMethod`.
    *   Change: link `cce-admin-ui.md` (relative to `docs/designs/`) and link CQ-1 there. Re-check the AS-REQ-01 to AS-REQ-24 numbering and CQ-1 against that file before editing.

### [Issue 18] CA-18 [`egm-zoom-out`, `egm-comprehension`, `egm-structural`, `egm-style`] - Severity: Major
*   **Affected Scope:** [Lines 26-27](../../docs/designs/cce-admin-service.md#L26-L27), 72-74, 111-112, 140-168, 194, 199-246, 652, 1161, 1235
*   **Discrepancy Description:** Drafting history, undefined labels and process wording are left in the text.
*   **Rationale:** Author Decisions cells carry procedures and history, and OQ, P and "drafting gate" labels are undefined.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** This is a comprehension gap.
*   ***Proposed Edit:***
    *   Location: Author Decisions table, Open Questions, Overview, Alternatives intro.
    *   Change:
        *   Cut each AD cell to the question, a one-line decision and the reason. Move the procedures of AD-13, AD-15 and AD-16 into sections 5 and 9 without copying them.
        *   Remove "amended to match" and "Superseded part".
        *   Drop the OQ, P and "pushback" labels.
        *   Replace "this trial", "drafting gates" and "this machine" with facts about the inputs.
        *   Retitle Open Questions to "Open Questions for the gateway-service owners".

### [Issue 19] CA-19 [`egm-design`, `egm-zoom-out`] - Severity: Major
*   **Affected Scope:** [Lines 1159-1327](../../docs/designs/cce-admin-service.md#L1159-L1327)
*   **Discrepancy Description:** Rejected alternatives give no technical reason.
*   **Rationale:** Several entries say only "Rejected by author".
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate for AD-14, AD-15, AD-16 and AD-17 Alternative B.
*   ***Proposed Edit:***
    *   Location: Alternatives Considered.
    *   Change: every "Rejected by author" gets its trade-off in one line and a link to its AD row. Add one alternative, a single global lock (the old 5.3/5.4 rule), rejected for CA-06's reasons.

### [Issue 20] CA-20 [`egm-design`, `egm-zoom-out`] - Severity: Major
*   **Affected Scope:** [Lines 24](../../docs/designs/cce-admin-service.md#L24), 37, 78, 151-155, 1106-1108, 1144-1155, 1360
*   **Discrepancy Description:** Goals do not follow the promised format, and G10 cannot be tested.
*   **Rationale:** Some goals lack IDs or a check. G10 is untestable.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate.
*   ***Proposed Edit:***
    *   Location: Goals, section 10, GW section.
    *   Change:
        *   Every goal gets PRD IDs, a milestone (M0, B1 to B5, E2E) and a "Check:" line.
        *   Delete G10. The GW section lists once what would need a code change: the request body, a per-role parameter, token forwarding, and a different matcher.
        *   Order the E2E list S1 to S6.

### [Issue 21] CA-21 [`egm-design`, `egm-zoom-out`] - Severity: Major
*   **Affected Scope:** [Line 211](../../docs/designs/cce-admin-service.md#L211) (AD-05), [1341-1344](../../docs/designs/cce-admin-service.md#L1341-L1344)
*   **Discrepancy Description:** Performance claims ignore `/me/menus` traffic.
*   **Rationale:** Every console page load calls `/me/menus`.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate.
*   ***Proposed Edit:***
    *   Location: Non-Functional Requirements, Performance.
    *   Change:
        *   Peak estimate: one `/me/menus` per console page load and per sign-in, under 10 requests per second at national scale.
        *   Cost per authenticated request: the user `SELECT`, the permission-row `SELECT` and one conditional `UPDATE` that changes a row once per sign-in, plus the menu query for `/me/menus`.
        *   If the service is down, cce-insights-ui cannot build its sidebar and shows its error state. That console's behaviour is in its own repository (PRD §12).

### [Issue 22] CA-22 [`egm-implementation-readiness`] - Severity: Major
*   **Affected Scope:** [Lines 1432-1436](../../docs/designs/cce-admin-service.md#L1432-L1436), 1457-1463, 1474, 1505-1506, 1547-1548, 1574
*   **Discrepancy Description:** Persistence and audit plumbing are left to the implementer.
*   **Rationale:** Id generation, timestamps, bulk deletes, the actor outside a request, filter registration, transaction boundaries and `updated_at` on sync are unstated.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate.
*   ***Proposed Edit:***
    *   Location: Phases 2 to 6.
    *   Change:
        *   Ids are assigned in Java (`UUID.randomUUID()` in `@PrePersist`, so 5.1 can set one early). `@PrePersist` sets `created_at` and `updated_at`.
        *   `LOCAL_ROLES_SAVED` sets `users.updated_at`.
        *   Replace deletes are `@Modifying(flushAutomatically = true, clearAutomatically = true)` JPQL deletes. 5.7 deletes explicit rows only, by method and pattern.
        *   `ActorProvider`: the request's `CurrentUser`, otherwise the `system:bootstrap` actor. Service tests set the actor through `TestActor`.
        *   The filters are not `@Component`, so they are registered only in the chain. `CurrentUserFilter` skips public paths.
        *   `updateLastLoginIfLater` is `@Transactional`.
        *   Step boundaries use `TransactionTemplate`.
        *   Sync updates do not change `roles.updated_at`.

### [Issue 23] CA-23 [`egm-implementation-readiness`] - Severity: Major
*   **Affected Scope:** [Lines 1480](../../docs/designs/cce-admin-service.md#L1480), 1576, 1578
*   **Discrepancy Description:** Test infrastructure cannot produce the cases the plan lists.
*   **Rationale:** `jwt()` bypasses the decoder, and no fault injection or overlap method is named.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate: `jwt()` skips validators, so the 401 cases cannot be tested with it.
*   ***Proposed Edit:***
    *   Location: Phases 3 and 6.
    *   Change:
        *   Add `{test}/support/TestJwtFactory.java`, which signs with a test RSA key, and a test `JwtDecoder` built from the key plus `SecurityConfig`'s validators, used for the 401 cases. `jwt()` is used only for the 403 and 200 cases.
        *   Fault injection: `@MockitoSpyBean UserRepository` throws on `save`.
        *   `LastSystemAdminConcurrencyTest` overlaps the two requests with a `CountDownLatch` inside a mocked `KeycloakGateway.setEnabled`.

### [Issue 24] CA-24 [`egm-implementation-readiness`, `egm-codebase-details`] - Severity: Major
*   **Affected Scope:** [Lines 1501-1503](../../docs/designs/cce-admin-service.md#L1501-L1503), 1516-1517, 1574
*   **Discrepancy Description:** Keycloak client details that would cause defects.
*   **Rationale:** The two 409 cases are not distinguished, attributes are missing from brief role lists, token parsing is unstated, 404 is not treated as empty, and timeouts are not applied.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate.
*   ***Proposed Edit:***
    *   Location: section 7, Phase 4.
    *   Change:
        *   After a Keycloak 409 on create, look up the username to choose `USERNAME_IN_USE` or `EMAIL_IN_USE`.
        *   The stand-in lists roles with `?briefRepresentation=false`, and the Phase 4 exit check confirms a created role carries `cce-managed`. If `POST` drops attributes, follow with `PUT`.
        *   Lookups map 404 to empty.
        *   Timeouts are applied through `ResteasyClientBuilder` (`connectTimeout`, `readTimeout`).
        *   The token-response item goes away under CA-32.

### [Issue 25] CA-25 [`egm-implementation-readiness`] - Severity: Major
*   **Affected Scope:** [Lines 1455](../../docs/designs/cce-admin-service.md#L1455), 1468-1470, 1477, 1482, 1541, 1570
*   **Discrepancy Description:** API serialization and validation rules have no owner.
*   **Rationale:** Null omission is too broad, nothing owns trimming, path notation is unstated, 415 is unmapped, and some response records are unnamed.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate.
*   ***Proposed Edit:***
    *   Location: 4.1, Phase 3, Phases 5 and 6 DTO rows.
    *   Change:
        *   Only `pagination`, `warnings`, `details` and `partialState` are left out when null. Every other field is always written, null included.
        *   `config/TrimmingStringDeserializer.java`, registered in `JacksonConfig`, trims strings and turns empty strings into null.
        *   `endpoints.txt` writes path parameters as `{id}`.
        *   `ApiExceptionHandler` maps `HttpMediaTypeNotSupportedException` to 400, and lists `MethodArgumentNotValidException`, `HandlerMethodValidationException`, `HttpMessageNotReadableException`, `MethodArgumentTypeMismatchException`, `NoResourceFoundException` and `HttpRequestMethodNotSupportedException`.
        *   Name the records `ResetPasswordResult`, `RoleSyncResult` and `RolePermissions`.

### [Issue 26] CA-26 [`egm-implementation-readiness`] - Severity: Major
*   **Affected Scope:** [Lines 1042](../../docs/designs/cce-admin-service.md#L1042), 1064, 1402, 1411-1417, 1507-1512
*   **Discrepancy Description:** The local stack has gaps and contradictions.
*   **Rationale:** Build contexts conflict, the stand-in's variables are not listed, the Keycloak health check cannot run in its image, the insights client's purpose is wrong, and Phase 1 checks 3 and 4 cannot run as written.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate.
*   ***Proposed Edit:***
    *   Location: 9.1, 9.2, Phase 1, Phase 4.
    *   Change:
        *   9.1's `gateway-stub` row uses build context `.` with `dockerfile: gateway-stub/Dockerfile`.
        *   Add a table of the stand-in's variables, plus its `env_file` and `depends_on`.
        *   Keycloak health check: `["CMD-SHELL", "exec 3<>/dev/tcp/127.0.0.1/9000 && printf 'GET /auth/health/ready HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q UP <&3"]`. Postgres: `pg_isready -U admin_service -d admin_service`.
        *   The `cce-insights-ui` client exists so a browser can sign in to that console locally. The E2E uses `cce-e2e`.
        *   Phase 1 checks 3 and 4 start with `set -a; . ./.env; set +a` and decode the payload with `cut -d. -f2 | base64 -d`, adding padding.

### [Issue 27] CA-27 [`egm-implementation-readiness`, `egm-comprehension`] - Severity: Major
*   **Affected Scope:** [Lines 1393](../../docs/designs/cce-admin-service.md#L1393), 1449, 1518, 1536, 1542, 1603, 1645-1647
*   **Discrepancy Description:** The plan contradicts itself or overstates in places.
*   **Rationale:** "nine" `/roles` endpoints should be ten, the 401/403 wording is wrong, "enforced by" overstates, and the "Closes" lines overstate coverage.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Verified that `/roles` has ten endpoints.
*   ***Proposed Edit:***
    *   Location: Phase 5, Phase 4 stand-in row, Verification Plan, "Closes" lines.
    *   Change: "nine" becomes "ten". Under CA-07 the stand-in returns 401 only when no bearer token is present. "Enforced by" becomes "covered by". Mark partial coverage on the "Closes" lines.

### [Issue 28] UI-1 [console review, Stage 5 M1, A-04] - Severity: Major
*   **Affected Scope:** [Lines 550-551](../../docs/designs/cce-admin-service.md#L550-L551)
*   **Discrepancy Description:** The lines say a `POST /users` 502 can follow a saved local row, but step 6 is one transaction.
*   **Rationale:** This is the same defect as CA-12.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate.
*   ***Proposed Edit:***
    *   Location: as CA-12.
    *   Change: fixed by CA-12's edit.

### [Issue 29] UI-2 [console review, A-04] - Severity: Major
*   **Affected Scope:** 4.2 `USERNAME_IN_USE` and `EMAIL_IN_USE` rows; 5.1 step 3
*   **Discrepancy Description:** The 409 message for a username or email taken only in Keycloak should say so.
*   **Rationale:** An administrator otherwise searches the local list and finds nothing.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate. After CA-01, this applies to non-linkable matches.
*   ***Proposed Edit:***
    *   Location: 4.2, 5.1.
    *   Change: for a Keycloak-only conflict, the message reads "This {username or email} belongs to a Keycloak account with no local user, and it cannot be linked because {the account is disabled, or the other field differs}." Clients still branch on the code.

### [Issue 30] UI-4 [console review, S7-D-8, A-13] - Severity: Major
*   **Affected Scope:** [Line 1006](../../docs/designs/cce-admin-service.md#L1006)
*   **Discrepancy Description:** The audit IP takes the first `X-Forwarded-For` value, trusted by default.
*   **Rationale:** This is the same defect as CA-14.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate.
*   ***Proposed Edit:***
    *   Location: as CA-14.
    *   Change: fixed by CA-14's edit.

### [Issue 31] UI-5 [console review, Stage 5 Part 3] - Severity: Major
*   **Affected Scope:** [Line 208+](../../docs/designs/cce-admin-service.md#L199) (AD-03), section 6 seed
*   **Discrepancy Description:** AD-03 makes Practitioners, Intelligence and Exports active `NAV` menus, which today are reachable by URL only.
*   **Rationale:** The menu seed is meant to match the current navigation.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate about the effect. The author keeps AD-03.
*   ***Proposed Edit:***
    *   Location: Risks & Mitigations, and a rollout note.
    *   Change: add a row. A role granted these menus shows three more sidebar links than today's console. Mitigation: grant them only to roles that should see them. Seed order keeps today's seven links first.

### [Issue 32] UI-6 [console review, A-06] - Severity: Major
*   **Affected Scope:** GW section
*   **Discrepancy Description:** New gateway question: the shape of the gateway's own 401 and 403 bodies.
*   **Rationale:** A console behind the gateway receives the gateway's error bodies, not this service's envelope.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate. No input documents the gateway's error bodies.
*   ***Proposed Edit:***
    *   Location: GW section.
    *   Change: add GW-8. What body do the gateway's 401 and 403 have? Assumed until answered: not this service's envelope. Clients must treat a 401 or 403 without `error.code` as `UNAUTHENTICATED` or `FORBIDDEN` by status.

### [Issue 33] CA-28 [`egm-comprehension`, `egm-style`, `egm-zoom-out`] - Severity: Nit
*   **Affected Scope:** [Line 756](../../docs/designs/cce-admin-service.md#L756); 4.1 health; DEV-3; DEV-4; section 1
*   **Discrepancy Description:** Wording fixes in the feature section.
*   **Rationale:** An inverted sentence, health presented as B-05's wording, DEV-3 inaccuracies, a reinterpretation in DEV-4, and intensifiers.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate.
*   ***Proposed Edit:***
    *   Location: as listed.
    *   Change:
        *   Un-invert 5.1 step 3.
        *   Health's `{status, database}` is this design's choice, extending B-05's `{status:"UP"}`.
        *   DEV-3 notes that the PRD §5 diagram itself says "schema admin_service", and that the default `search_path` is `"$user", public`.
        *   DEV-4 states the deviation only.
        *   "One transaction per request" becomes "one transaction per database step".
        *   Remove the intensifiers and the lone "we".

### [Issue 34] CA-29 [`egm-design`] - Severity: Nit
*   **Affected Scope:** [Lines 191-198](../../docs/designs/cce-admin-service.md#L191-L198) (Deviations)
*   **Discrepancy Description:** The Deviations table is missing rows.
*   **Rationale:** Three departures are not recorded.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate.
*   ***Proposed Edit:***
    *   Location: Deviations.
    *   Change: add rows for the 502 `KEYCLOAK_SYNC_FAILED` when nothing changed (B-32 and Appendix B say "state partially applied"), the `details` field in the error envelope (B-03), and hiding a `DETAIL` whose parent is not granted (B-21).

### [Issue 35] CA-30 [`egm-design`] - Severity: Nit
*   **Affected Scope:** Section 6, section 7, section 8, 4.5 health
*   **Discrepancy Description:** Data model and security details.
*   **Rationale:** Federation, the `menus.path` base, the service account's blast radius, PII retention and audit grants, and how probes read `DOWN` are unstated.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate.
*   ***Proposed Edit:***
    *   Location: as listed.
    *   Change:
        *   The realm uses no user federation, so Keycloak ids are UUIDs.
        *   `menus.path` is relative to the console's router base.
        *   Blast radius: `manage-users` can change any realm user, including role mappings of any realm role.
        *   `audit_log` holds usernames, emails and IPs, and retention follows the platform's audit policy, outside this design. 9.5 revokes `UPDATE` and `DELETE` on `audit_log` from the service's user.
        *   Probes treat HTTP 200 as alive. `"database": "DOWN"` is informational.

### [Issue 36] CA-31 [`egm-structural`, `egm-comprehension`] - Severity: Nit
*   **Affected Scope:** Whole document; [line 1375](../../docs/designs/cce-admin-service.md#L1375)
*   **Discrepancy Description:** Formatting and cross-references.
*   **Rationale:** Tables lack blank lines, flows have no headings, the ASCII diagram is misaligned, and references and notation drift.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate.
*   ***Proposed Edit:***
    *   Location: as listed.
    *   Change:
        *   Add blank lines around tables.
        *   Make 5.1 to 5.13 H4 headings.
        *   Realign the diagram and add a legend.
        *   Correct the section references.
        *   Write paths as `/admin/v1/...` in prose and `:id` throughout, and state the `/admin/v1` prefix in the Overview.
        *   Gloss PRD labels (B-, S-, F- IDs).
        *   Line 1375 says "repository root".

### [Issue 37] CA-32 [`egm-zoom-out`, `egm-inventive-catalyst`] - Severity: Nit
*   **Affected Scope:** Section 7, Phase 4, Phase 8
*   **Discrepancy Description:** Code that repeats existing libraries.
*   **Rationale:** `ServiceTokenProvider` and the hand-written E2E HTTP and Keycloak clients duplicate existing libraries.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate. The admin client already runs the same client-credentials grant.
*   ***Proposed Edit:***
    *   Location: section 7, Phases 4 and 8.
    *   Change: `GatewaySyncClient` takes its bearer token from `Keycloak.tokenManager().getAccessTokenString()`. `ServiceTokenProvider` and its test are deleted. The E2E harness uses `RestClient` and `keycloak-admin-client`, and `E2eHttp` is deleted.

### [Issue 38] CA-33 [`egm-implementation-readiness`, `egm-style`] - Severity: Nit
*   **Affected Scope:** Phases 3, 7 and 8
*   **Discrepancy Description:** Wording and detail nits in the plan.
*   **Rationale:** Unbounded connection wait, suffix format, guard-test scope, duplicates and loose references.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is accurate.
*   ***Proposed Edit:***
    *   Location: as listed.
    *   Change:
        *   The health check's connection wait is bounded by `spring.datasource.hikari.connection-timeout: 2000`.
        *   The per-run suffix is 8 digits taken from the epoch seconds.
        *   The guard test is limited to `org.cce.admin.controller`.
        *   Remove the duplicates and make the step references precise.

### [Issue 39] UI-3 [console review, A-12] - Severity: Nit
*   **Affected Scope:** 4.1 "Concurrent edits"
*   **Discrepancy Description:** Follow-up: `If-Match` and 412 on the full-set replace endpoints.
*   **Rationale:** Last-write-wins can silently drop a concurrent edit.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** The finding is valid. The author records it as a follow-up, not as work in this design.
*   ***Proposed Edit:***
    *   Location: new "Future Improvements" section.
    *   Change: one bullet: optimistic concurrency on `PUT /roles/:id/menus`, `PUT /roles/:id/permissions` and `PUT /users/:id/roles`, using `If-Match` and 412, would stop one administrator's save from silently replacing another's.
