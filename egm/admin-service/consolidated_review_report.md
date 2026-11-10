# Consolidated Review Report: `cce-admin-service`

**Target:** `docs/designs/cce-admin-service.md` (1,653 lines, Status: Proposed)
**Mode:** Uninterrupted full flow (Stages 5 to 9, no checkpoints). No edits were made to the target during the run, so every stage reviewed the same text.
**Split:** FeatureRange lines 1-1363; DetailedRange lines 1364-1653 (`## Detailed Implementation Plan`).
**GoldfishContext:** the seven files the design links to, all inside the working directory. No link was a URL or resolved outside it, so nothing was left out.

- `context/prd/CCE_Admin_Module_PRD.md`
- `context/gateway-service/DATABASE_PERMISSIONS_MIGRATION.md`
- `context/cce-insights-ui/docs/architecture-overview.md`, `deployment-guide.md`, `developer-setup.md`
- `docs/designs/author-decisions.md`
- `egm/admin-ui/design_draft.md`

Stage 6 read the target only, as the skill requires.

## Stage Reports and Reviewer Status

| Stage | Report | Reviewers (all completed, none retried) | Findings after each stage's own merge |
| --- | --- | --- | --- |
| 5 Initial Zoom Out | [zoom_out_report.md](zoom_out_report.md) | `egm-zoom-out`, `egm-inventive-catalyst` | 1 Blocker, 12 Major, 9 Nit, plus transforms T1-T9 |
| 6 Comprehension | [comprehension_report.md](comprehension_report.md) | `egm-comprehension` | 0 Blocker, 2 Major, 10 Nit |
| 7 Critic | [critic_report.md](critic_report.md) | `egm-design`, `egm-structural`, `egm-style` | 1 Blocker, 26 Major, 25 Nit |
| 8 Intermediate Zoom Out | [intermediate_zoom_out_report.md](intermediate_zoom_out_report.md) | `egm-zoom-out` | 1 Blocker, 8 Major, 3 Nit |
| 9 Implementation Readiness | [implementation_readiness_report.md](implementation_readiness_report.md) | `egm-implementation-readiness`, `egm-codebase-details`, `egm-style` | 2 Blocker, 22 Major, 23 Nit |

That is 145 stage findings in all. They merge into **33 consolidated actions: 3 Blockers, 24 Majors and 6 Nits.**

Raw finding ID prefixes used below: `Z5-` Stage 5, `C6-` Stage 6, `CR-` Stage 7, `Z8-` Stage 8, `IR-` Stage 9.

## Verification by the Elephant

I checked the claims that drive the Blocker and runbook priorities against the source files. All of them hold:

- **The gateway doc contradicts itself on reloading (CR-M10, Z5-2).** `DATABASE_PERMISSIONS_MIGRATION.md` line 8 says "Update permissions without restarting the service". Line 187 says "Currently, you need to restart the gateway service".
- **The gateway doc has no trigger or index DDL (IR-B-2).** Lines 44-49 name `idx_permission_name`, `idx_resource_category` and a trigger in prose only. There is no column list, no trigger name and no trigger body.
- **The runbook order fails (CR-M4).** Section 9.5 step 6 (line 1123) asks for a gateway restart "after the first migration has seeded `api_permissions`". That migration only runs at step 7 (line 1124).
- **The endpoint count is wrong (IR-M-13, C6-F12).** Line 1542 says "The nine `/roles` endpoints".
- **Two links point to the scratch draft (CR-M17).** Lines 41 and 230 link `../../egm/admin-ui/design_draft.md`. `docs/designs/cce-admin-ui.md` exists, and it also has uncommitted edits in the working tree.

## Cross-Stage Conflicts the Author Must Settle

The stages do not agree on direction.

- **Stages 5 and 7 add mechanisms:**
  - link and adopt endpoints (T1)
  - account tagging (T2)
  - write-ahead intent rows (T3)
  - a drift detector (T5)
  - a deadline budget per request (T6)
  - a background re-sync (T7)
  - a shadow mode (T8)
  - metrics with alert routing (CR-M14)
  - `lock_timeout` or advisory locks (CR-M3)
  - a dual-matcher test (CR-M9)
- **Stage 8 argues the opposite.** In its view, most Stage 6 and 7 findings come from mechanisms this design added beyond the PRD. The cheaper fix is to remove or narrow those mechanisms.

**Recommendation: follow Stage 8's direction.** The document is already 1,653 lines long, and many findings are internal contradictions between the parts these mechanisms add. Each extra mechanism adds contract surface that the console design must also cover (D-01).

Several actions would reverse or narrow a recorded Author Decision. These go to the author, not to an automatic resolver:

| Action | Author Decision it reopens |
| --- | --- |
| CA-04 | AD-12 (role delete syncs) and AD-16 (role create syncs) |
| CA-05 | AD-15 step (4) (the bootstrapper's sync) |
| CA-07 | AD-17 (the service's own permission check) |
| CA-09 | AD-06 (per-role sync status, `GLOBAL` marking) |
| CA-10 | AD-02 (V1 alters an existing gateway table) |

Actions depend on each other:

- If CA-07 option (a) is taken, these Stage 9 items disappear instead of needing fixes: IR-M-7, IR-M-12, IR-N-2, IR-N-4, CR-N8 and Z5-16.
- If CA-04 and CA-09 are taken, CA-04's single audit rule covers fewer cases.

Settle CA-04, CA-07, CA-09 and CA-10 before resolving the Stage 9 actions.

---

## Consolidated Action Items

### 🔴 Blockers

#### CA-01. Existing Keycloak accounts cannot be brought under management, and a failed user creation cannot be retried
- **Sources:** Z5-1, Z5-10 (5.1 part), CR-B1, Z8-1
- **Scope:** Lines 165-177, 221 (AD-15), 276-283 (GW-5), 424-426 (B-12), 686, 756, 763
- **Problem:**
  - Every current cce-insights-ui user already exists in Keycloak and has no local `users` row. Once that console reads `/me/menus`, every one of them gets 403.
  - `POST /users` returns 409 for an existing Keycloak username or email.
  - Only the bootstrapper can adopt an existing account.
  - A 502 at `KEYCLOAK_ROLES_ASSIGNED` or `LOCAL_USER_SAVED` leaves an account with no local row that only manual Keycloak edits or SQL can repair.
  - `POST /roles` refuses the existing `INSIGHTS_READ` name.
- **Action (Stage 8's single mechanism):**
  - In 5.1 step 3, if an enabled Keycloak account matches both the username and the email exactly and has no local row, link it instead of returning 409. Skip step 4, run steps 5 to 7, and record `after.linked = true` in `USER_CREATED`.
  - Let the bootstrapper call the same method.
  - Move adoption of unowned `permission_name` values such as `INSIGHTS_READ` into Non-Goals, with an open question under GW-5, and state in the rollout notes that `INSIGHTS_READ` stays managed by the gateway.
  - Do not add T1's two endpoints or T2's tagging.

#### CA-02. Tests have no configuration, so `./mvnw verify` fails on a clean checkout and reads the developer's `.env` elsewhere
- **Sources:** IR-B-1
- **Scope:** Lines 1400, 1437, 1454, 1480, 1483, 1506, 1551, 1641-1642
- **Action:**
  - Add a Phase 3 row for `src/test/resources/application-test.yml` (`@ActiveProfiles("test")`) with fixed values for every required `admin.*` property.
  - Keep the `optional:file:.env` import out of the test profile.
  - Guard `BootstrapAdminRunner` with `@Profile("!test")` or a property switch.
  - Name how `PostgresTestContainer` is attached.

#### CA-03. The gateway fixture is "copied" from DDL that the gateway doc does not contain
- **Sources:** IR-B-2 (verified above)
- **Scope:** Lines 1428, 1438, 1441; section 6, lines 895-899
- **Action:**
  - Write the full fixture SQL into the plan: index DDL with columns, and the trigger function name, trigger name, timing and body.
  - Label it as this design's reconstruction of the gateway DDL, and record that as an assumption.
  - Name the two sample `INSERT`s (`USER_READ`, `USER_CREATE_UPDATE`).
  - Replace "exactly as in the gateway migration doc" in section 6.
  - CA-10 makes this fixture matter less, but `MigrationExistingGatewayTableTest` still needs it.

### 🟡 Major: design (FeatureRange)

#### CA-04. Audit-row and 502 rules contradict each other; G8's row counts and the B-50 deviation are wrong
- **Sources:** C6-F2, C6-F3, Z5-8, CR-M12, CR-N4 (B-50 part), Z8-2, Z8-4, IR-M-22 (exit-check part)
- **Scope:** Lines 122-139 (G8), 191-198, 542, 548-576, 748, 792-796, 812-818, 914, 1026-1027, 1133, 1551, 1561
- **Problem:**
  - Manual sync with `completedSteps: []` both writes and does not write a row.
  - A failed sync after a save is described both as `PARTIAL_FAILURE` on the flow's own action and as `ROLE_SYNC_REQUESTED`/`FAILED`.
  - `KEYCLOAK_SYNC_FAILED` is limited to failures after a change in one place (line 542) and used for failures before any change in another (line 573).
  - Role create and role delete also write two rows, which G8 does not say.
  - B-50's "one row per mutation" deviation is not recorded.
- **Action:**
  - State one rule and point G8, sections 4.2, 5 and 8, and section 10 to it:
    - Every sync attempt writes one `ROLE_SYNC_REQUESTED` row with its outcome.
    - Every 502 after a change or a sync attempt points `auditLogId` at the row that records the failure.
    - A failure before any change and before any sync writes nothing.
    - Use one failure outcome, `FAILED` with the `failure` JSON.
  - Author decision: remove the sync from role delete (reopens AD-12), and make role create return 201 with a `ROLE_SYNC_FAILED` warning (reopens AD-16). This leaves the permission save as the only call that writes two rows, and milestone B2 no longer depends on the gateway.
  - Add a DEV row for B-50.
  - List the expected `action`/`outcome` pairs in the Phase 5 exit check (line 1561).

#### CA-05. The first-deploy runbook fails if followed in order
- **Sources:** CR-M4 (verified), Z8-7, C6-F9, Z5-18, IR-M-17
- **Scope:** Lines 219-221 (AD-13, AD-15), 838, 1115-1125, 1500, 1506, 1522
- **Problem:**
  - Step 6 asks for a gateway restart after a migration that only runs at step 7.
  - The bootstrapper's sync then fails, because the gateway has not loaded the `CCE_ADMIN_SERVICE` row, and the bootstrapper stops.
  - When the bootstrapper triggers a sync, "the role that asked" is undefined.
  - `KeycloakUserInfo` lacks the email and names that the bootstrapper inserts.
  - The `CCE_ADMIN_SERVICE` chain rests on the unanswered GW-6.
- **Action:**
  - Delete AD-15 step (4) (author decision).
  - Have 9.5 step 3 create the `SUPER_ADMIN` realm role by hand, next to `CCE_ADMIN_SERVICE`. If it is missing, the bootstrapper logs an error and stops.
  - Add `username`, `email`, `firstName` and `lastName` to `KeycloakUserInfo`, specify how the bootstrapper refuses invalid accounts, and test that case.
  - Mark the GW-6 chain as provisional until GW-6 is answered, before Phase 4.

#### CA-06. One global lock serializes every status and role change across Keycloak calls
- **Sources:** Z5-3, Z5-13, CR-M2, CR-M3, Z8-8, Z8-10
- **Scope:** Lines 512-514, 748-751, 756, 771-785, 824, 974, 1003, 1357, 1573, 1578
- **Problem:**
  - In 5.4 the role diff is computed before the lock, so Keycloak and the database can drift apart.
  - The `SUPER_ADMIN` row lock is held across up to about 25 Keycloak calls of up to 10 s each.
  - Waiting requests hold pool connections, so the audit insert for a failure and `/me/menus` can stall.
- **Action (Stage 8):**
  - Take a per-user `SELECT … FOR UPDATE` on `users` first, and compute the diff after it (5.2 to 5.4).
  - Take the `SUPER_ADMIN` lock only when the change can lower the number of `ACTIVE` `SUPER_ADMIN` holders (T4).
  - Replace the up to 20 `findRealmRole` calls with one realm-roles list call.
  - Rewrite the risk row at line 1357 to match.
  - Do not add `lock_timeout`, advisory locks, deadline budgets (T6) or reserved connections.

#### CA-07. AD-17's own permission check drives most of the matcher, cache and stand-in complexity
- **Sources:** Z5-4, Z5-5, Z5-6, Z5-16, Z5-21, CR-M9, CR-N8, CR-M25 (TTL part), Z8-3, IR-M-7, IR-M-12, IR-N-2, IR-N-4
- **Scope:** Lines 140-150 (G9), 223 (AD-17), 429-455, 653, 1109-1110, 1131-1136, 1341-1344, 1358, 1461-1463, 1508-1519, 1546-1547, 1600-1602, 1622-1628
- **Problem:** On top of the PRD's defence in depth (B-10, B-12), the design re-implements the gateway's authorization. That brings in:
  - a hand-written matcher, which already differs from `AntPathMatcher` and is shared between modules through folder paths
  - a TTL snapshot that role create and delete do not clear, so the S1 E2E fails intermittently with 403 (IR-M-7)
  - a stand-in that hand-writes RS256 checking
  - an offline row check with an undefined `scenario-rows.json` (IR-M-12)

  Later stages ask for even more: a dual-matcher test, grammar limits, a firewall test and a shadow mode.
- **Action (author decision, reopens AD-17):**
  - **(a)** Take AD-17 Alternative A. Record "any `ACTIVE` local user can call admin endpoints if the gateway is bypassed" as an accepted risk. Everything listed above is removed.
  - **(b)** Keep a minimal check: `AntPathMatcher`, one indexed query per request with no snapshot and no TTL, a boolean switch, a stand-in that checks only that a bearer token is present, and no offline row check.
  - Under either option, allow `**` only as the last segment (CR-M9) and reject T8.
  - Under (b), also fix IR-M-7, IR-N-2 and IR-N-4.

#### CA-08. `SYNCED` is presented as "in effect", but the gateway may need a restart, and the gateway doc contradicts itself
- **Sources:** Z5-2, C6-F4, CR-M10 (verified), CR-M25, Z8 altitude check
- **Scope:** Lines 212 (AD-06), 259-262 (GW-2), 627, 824, 1123, 1358
- **Action:**
  - Quote both gateway-doc statements (line 8 and line 187) in GW-2 and record the conflict.
  - Define `SYNCED` and `SUCCESS` as "the last sync attempt got a 2xx", not "enforced".
  - Add a Risks row for a gateway that only loads permissions on restart, naming who restarts it and when.
  - Make GW-2 a go/no-go item before Phases 4 and 5 and before the first shared environment.
  - Drop the guess at line 1358 ("likely the slower of the two anyway").

#### CA-09. The sync bookkeeping tracks more than the sync is known to do
- **Sources:** Z5-7, Z5-22, C6-F9, Z8-5, CR-M22 (scope part)
- **Scope:** Lines 212, 253-258 (GW-1), 627-628, 820-827, 1023, 1108, 1505, 1521
- **Problem:**
  - `GATEWAY_SYNC_SCOPE=ROLE` can never be used under GW-1 and AD-13.
  - `GLOBAL` fan-out marking has a race that can show `SYNCED` too early.
  - `markedSyncedRoleIds` adds audit JSON that nothing needs.
- **Conflict:** Z5-7 removes `ROLE` but keeps `GLOBAL` marking. Z8-5 drops both.
- **Action (author decision, reopens AD-06):** take Z8-5. Remove the `ROLE` setting and the fan-out marking, and update `sync_status` only for the role that asked. This removes the race and `markedSyncedRoleIds`.

#### CA-10. V1 alters an existing gateway-owned table, which breaks on ownership, Flyway history and seeding order
- **Sources:** CR-M5, CR-M6, CR-M23, Z8-6, IR-M-15, IR-M-16
- **Scope:** Lines 208 (AD-02), 895-901, 921-927, 1118, 1346, 1428-1442
- **Problem:**
  - Index and trigger DDL fails on a table owned by `opensrp`.
  - The two services may share `flyway_schema_history`.
  - Nothing grants the second database user access to the tables.
  - "Migrations only add tables and rows" is false.
  - V2's `ON CONFLICT ON CONSTRAINT` fails if an existing table names the constraint differently, and V2 seeds rows before V3 has checked the table's shape.
  - One shared container cannot give the migration tests the fresh databases they need.
- **Action:**
  - Author decision, narrows AD-02: V1 creates `api_permissions` with its constraint, indexes and trigger only when the table is absent, and never alters an existing one.
  - Run the shape check before the seed, and use `ON CONFLICT (permission_name, http_method, uri_pattern)`.
  - Set `spring.flyway.table`.
  - List the DML grants and the `pgcrypto` extension in 9.5.
  - Have each migration test create a uniquely named database and run Flyway directly, with no Spring context.

#### CA-11. `POST /roles` silently takes over a realm role that already exists in Keycloak
- **Sources:** CR-M1
- **Scope:** Lines 686, 793, 815
- **Action:**
  - Have `POST /roles` call `findRealmRole(name)` and refuse with 409 when the role exists.
  - Have role delete check Keycloak role membership, not only local `user_roles`.

#### CA-12. `audit_log.entity_id NOT NULL` loses the B-32 partial-failure row
- **Sources:** CR-M11, Z8-9 (adopts it)
- **Scope:** Lines 549-551, 748, 763, 913
- **Action:**
  - Generate the user UUID before 5.1 step 4 and use it as `entity_id`.
  - Fix line 550: a 502 on `POST /users` cannot come after the local row is saved.

#### CA-13. Partial-failure recovery, the crash window, drift and the revocation window are unstated
- **Sources:** Z5-10 (5.2-5.4 part), Z5-11, Z5-12, Z5-20, CR-M8, CR-M14, Z8-9
- **Scope:** Lines 52-53, 175-177, 223, 328-334, 557-561, 658-661, 748, 1003, 1331-1360
- **Action (Stage 8; do not add T3, T5, T7 or metrics pipelines):**
  - State that 5.2 to 5.4 recover when the same request is submitted again, and update the "next step" column of the `partialState` table.
  - Record these as accepted risks:
    - the crash window before the failure row is written
    - Keycloak drift, such as a `SUPER_ADMIN` mapped directly in Keycloak
    - the token revocation window (300 s locally, unknown elsewhere)
  - Add one read-only SQL query for outstanding `FAILED` rows, next to the GW-5 query.
  - Keep one structured `ERROR` log event per failure.
  - State whether a manual-only re-sync conflicts with PRD §9.

#### CA-14. The audit IP address can be forged
- **Sources:** Z5-14, CR-M7, IR-N-14
- **Scope:** Lines 376-377, 1005-1007, 1111, 1465
- **Action:**
  - Use `server.forward-headers-strategy` (`ForwardedHeaderFilter`) and read the remote address, or take the rightmost entry minus the number of trusted proxies.
  - Remove `ClientIpResolver` or `AUDIT_TRUST_FORWARDED_FOR`, or default the switch to `false`.
  - Qualify "reached only through the gateway" with "in non-local environments".
  - Add how the gateway forwards headers as a GW question, and add a Risks row.

#### CA-15. The chain from role to `permission_name` to realm role to token is never stated
- **Sources:** C6-F1
- **Scope:** Overview; Technical Plan sections 1 and 6; AD-11, AD-16; section 3 step 5
- **Action:** Add one sentence in section 1, or in the `api_permissions` entry of section 6: a role's rows carry the role's `name` as `permission_name`; the sync creates a realm role with that name; once it is assigned, the token lists it in `realm_access.roles`; the gateway and step 5 use it to select rows.

#### CA-16. There is no standalone Risks section with mitigations
- **Sources:** CR-M16, CR-M24, CR-N7, CR-N25, CR-M8 (risk part)
- **Scope:** Lines 248-290, 411-413, 1113, 1353-1360
- **Action:**
  - Add `## Risks & Mitigations` with a Mitigation column. Include GW-1 to GW-6, the security switches with their required values in shared environments (`SPRINGDOC_ENABLED`, the stand-in's control endpoint, `PERMISSION_CHECK_ENABLED`, `AUDIT_TRUST_FORWARDED_FOR`), and the risks from CA-06, CA-08, CA-13 and CA-14.
  - Reword line 1360 ("Locally everything passes"): nothing has run yet.

#### CA-17. Links point to the console's scratch draft, not its saved design
- **Sources:** CR-M17 (verified), CR-M18 (CQ-1 part)
- **Scope:** Lines 21, 41, 230, 652
- **Action:**
  - Point all three references to `cce-admin-ui.md`, and link CQ-1 there.
  - `cce-admin-ui.md` has uncommitted edits, so re-check the AS-REQ-01 to AS-REQ-24 numbering and CQ-1 against its final version.

#### CA-18. Drafting history, undefined labels and process wording are left in the text
- **Sources:** Z5-9, C6-F5, C6-F6, CR-M18, CR-M19, CR-M20, CR-M26, CR-N24, Z8-12 (cell-cutting part)
- **Scope:** Lines 26-27, 72-74, 111-112, 140-168, 194, 199-246, 652, 1161, 1235
- **Action:**
  - Cut each Author Decisions cell to the question, a one-line decision and the reason, and move each procedure (AD-13, AD-15, AD-16) into section 5 or 9 without copying it.
  - Remove the "amended to match" notes and AD-10's "Superseded part".
  - Define or drop OQ-1 to OQ-15 and P1, P2 and P4.
  - Replace trial and workstation wording ("this trial", "drafting gates", "this machine") with facts about the inputs.
  - Retitle Open Questions, since every item in it is decided.

#### CA-19. Rejected alternatives give no technical reason
- **Sources:** CR-M13, Z8-12
- **Scope:** Lines 1159-1327
- **Action:**
  - Give each "Rejected by author" entry its trade-off in one line, and link it to its AD row.
  - Add an alternative only for the locking strategy (CA-06), not for every decision CR-M13 lists.

#### CA-20. Goals do not follow the promised format, and G10 cannot be tested
- **Sources:** CR-M21, CR-M22, Z8-11
- **Scope:** Lines 24, 37, 78, 151-155, 1106-1108, 1144-1155, 1360
- **Action:**
  - Give every goal its PRD IDs and a check line.
  - Map goals to milestones B1 to B5.
  - Order the E2E list S1 to S6.
  - Delete G10. In the GW section, list once what would need a code change: the request body, a per-role parameter, token forwarding and the matcher.

#### CA-21. Performance claims ignore `/me/menus` traffic
- **Sources:** CR-M15, Z8-9 (estimate part)
- **Scope:** Line 211 (AD-05), lines 1341-1344
- **Action:**
  - Add a one-line peak estimate for `/me/menus`.
  - Describe the real cost per request consistently: one SELECT plus one conditional UPDATE that changes a row once per sign-in.
  - State what the client does when the service is down.

### 🟡 Major: implementation plan (DetailedRange)

#### CA-22. Persistence and audit plumbing are left to the implementer
- **Sources:** IR-M-5, IR-M-6, IR-M-8, IR-M-9, IR-N-12, IR-N-16
- **Scope:** Lines 1432-1436, 1457-1463, 1474, 1505-1506, 1547-1548, 1574
- **Action:** State each of these:
  - id generation and insert-time timestamps (`@UuidGenerator`, `@PrePersist`, or `insertable=false` plus a refresh)
  - that `LOCAL_ROLES_SAVED` sets `users.updated_at`
  - bulk `@Modifying` deletes, or a `flush()` before the re-inserts, and explicit-only deletion for 5.7
  - an actor source outside a request (an `AuditEntry` override, or an `ActorProvider` with a system fallback) and how service tests set it
  - that the filters are not registered twice, and that `CurrentUserFilter` skips public paths
  - that `updateLastLoginIfLater` is `@Transactional`
  - the step-boundary mechanism (`TransactionTemplate` or separate beans)
  - whether sync updates bump `roles.updated_at`

#### CA-23. Test infrastructure cannot produce the cases the plan lists
- **Sources:** IR-M-1, IR-N-17 (IR-M-15 is in CA-10)
- **Scope:** Lines 1480, 1576, 1578
- **Action:**
  - Add `TestJwtFactory`, which signs with an RSA key, and a test `JwtDecoder` that reuses `SecurityConfig`'s validators for the 401 cases. Use `jwt()` only for the 403 and 200 cases.
  - Name the fault-injection method (for example a spy repository) and the overlap method for `LastSystemAdminConcurrencyTest` (for example a latch).

#### CA-24. Keycloak client details that would cause defects
- **Sources:** IR-M-14, IR-M-18, IR-M-19, IR-N-13
- **Scope:** Lines 1501-1503, 1516-1517, 1574
- **Action:**
  - Tell the two Keycloak 409 cases apart: after a 409, look up the username.
  - Use `GET .../roles?briefRepresentation=false`, confirm in the Phase 4 exit check that a created role carries the `cce-managed` attribute, and follow up with a `PUT` if `POST` ignores attributes.
  - Read the token response as `JsonNode`, or with `@JsonIgnoreProperties(ignoreUnknown = true)`, and make the fake return a realistic Keycloak token body.
  - Treat a 404 on a lookup as empty.
  - Apply the timeouts through a `ResteasyClientBuilder`.
  - If CA-32 is taken, the token-response item goes away.

#### CA-25. API serialization and validation rules have no owner
- **Sources:** IR-M-10, IR-M-11, IR-N-3, IR-N-6, IR-N-9
- **Scope:** Lines 1455, 1468-1470, 1477, 1482, 1541, 1570
- **Action:**
  - Limit null omission to the optional envelope fields, and state that every other field is always written, null included.
  - Name the file and mechanism that trims strings and turns empty strings into null.
  - Write path parameters in `endpoints.txt` as `{id}`.
  - Map Spring's 415 to 400 `VALIDATION_ERROR`, and list the Spring 6.1 exceptions the handler covers.
  - Name the response records for reset-password, sync and the permissions GET.

#### CA-26. The local stack has gaps and contradictions
- **Sources:** IR-M-2, IR-M-3, IR-M-4, IR-N-10, IR-N-11
- **Scope:** Lines 1042, 1064, 1402, 1411-1417, 1507-1512
- **Action:**
  - Make section 9.1's `gateway-stub` build context match Phase 4 (context `.`).
  - Add a table of the stand-in's environment variables, plus its `env_file` and `depends_on` settings.
  - Give the exact Keycloak `healthcheck.test`. The image has no curl or wget, so use bash with `/dev/tcp`. Also give the `pg_isready` arguments.
  - Fix the stated purpose of the `cce-insights-ui` client.
  - Make the Phase 1 checks 3 and 4 runnable as written.

#### CA-27. The plan contradicts itself or overstates in places
- **Sources:** IR-M-13, C6-F12, IR-M-20, IR-M-21, IR-M-22 (401/403 part), IR-N-5
- **Scope:** Lines 1393, 1449, 1518, 1536, 1542, 1603, 1645-1647
- **Action:**
  - Change "nine" to "ten" (verified).
  - Reword line 1518: 401 when the token fails verification, 403 when no row allows the call.
  - Reword the guard-test claim and change "enforced by" to "covered by".
  - Mark partial coverage on the "Closes" lines.

### 🟢 Nits

#### CA-28. Wording fixes in the feature section
- **Sources:** C6-F8, CR-N1, Z5-19, CR-N17, CR-N2, CR-N3, CR-N18, CR-N19, CR-N20, CR-N21, CR-N22, CR-N23
- **Action:**
  - Un-invert 5.1 step 3 (line 756).
  - Present the `{status, database}` shape as this design's choice, not as B-05's wording.
  - Correct DEV-3: the PRD section 5 diagram contradicts itself, and `search_path` has a `$user` case.
  - Keep DEV-4 as a deviation only, without the reinterpretation.
  - Reword "one transaction per request".
  - Remove the intensifiers, the lone "we" and the vague words.

#### CA-29. The Deviations table is missing rows
- **Sources:** CR-N4 (except B-50, which is in CA-04)
- **Action:** Add rows for:
  - the 502 `KEYCLOAK_SYNC_FAILED` returned when nothing changed
  - the `details` field in the error envelope
  - hiding a `DETAIL` whose parent menu is not granted (B-21)

#### CA-30. Data model and security details
- **Sources:** CR-N5, CR-N6, CR-N9, CR-N10, CR-N11
- **Action:**
  - State that the realm uses no federation, or type the Keycloak id as `varchar`.
  - State the base that `menus.path` is relative to.
  - State the service account's blast radius.
  - State the audit PII retention, and revoke UPDATE and DELETE on `audit_log`.
  - State how probes treat `"database": "DOWN"`.

#### CA-31. Formatting and cross-references
- **Sources:** CR-N12, CR-N13, CR-N14, CR-N15, CR-N16, C6-F7, C6-F10, C6-F11, IR-N-1
- **Action:**
  - Add blank lines around tables.
  - Make flows 5.1 to 5.13 H4 headings.
  - Realign the ASCII diagram and add a legend.
  - Correct the section references.
  - Use one path notation, and state the `/admin/v1` prefix in the Overview.
  - Gloss PRD labels in a few words each.
  - Say "repository root" at line 1375.

#### CA-32. Code that repeats existing libraries
- **Sources:** Z5-15, Z5-17
- **Action:**
  - Reuse `Keycloak.tokenManager()` or Spring's client-credentials interceptor instead of `ServiceTokenProvider`.
  - Use `RestClient` and `keycloak-admin-client` in the E2E harness.

#### CA-33. Wording and detail nits in the plan
- **Sources:** IR-N-7, IR-N-8, IR-N-15, IR-N-18, IR-N-19, IR-N-20, IR-N-21, IR-N-22, IR-N-23
- **Action:**
  - Bound the health check's wait for a database connection.
  - Use digits only for the per-run suffix, and state its length.
  - Limit the guard test to `org.cce.admin.controller`, so Boot's `/error` is not counted.
  - Remove the redundant and duplicate statements.
  - Make the step references precise, fix the loose wording, and write the scenario rows the same way.

---

## Traceability Matrix

Every raw finding from the five stage reports maps to exactly one primary action. Where a finding has a second home, the second action is in brackets.

| Raw finding | Action | Raw finding | Action | Raw finding | Action |
| --- | --- | --- | --- | --- | --- |
| Z5-1 | CA-01 | CR-M1 | CA-11 | CR-N14 | CA-31 |
| Z5-2 | CA-08 | CR-M2 | CA-06 | CR-N15 | CA-31 |
| Z5-3 | CA-06 | CR-M3 | CA-06 | CR-N16 | CA-31 |
| Z5-4 | CA-07 | CR-M4 | CA-05 | CR-N17 | CA-28 |
| Z5-5 | CA-07 | CR-M5 | CA-10 | CR-N18 | CA-28 |
| Z5-6 | CA-07 | CR-M6 | CA-10 | CR-N19 | CA-28 |
| Z5-7 | CA-09 | CR-M7 | CA-14 | CR-N20 | CA-28 |
| Z5-8 | CA-04 | CR-M8 | CA-13 (CA-16) | CR-N21 | CA-28 |
| Z5-9 | CA-18 | CR-M9 | CA-07 | CR-N22 | CA-28 |
| Z5-10 | CA-01 (CA-13) | CR-M10 | CA-08 | CR-N23 | CA-28 |
| Z5-11 | CA-13 | CR-M11 | CA-12 | CR-N24 | CA-18 |
| Z5-12 | CA-13 | CR-M12 | CA-04 | CR-N25 | CA-16 |
| Z5-13 | CA-06 | CR-M13 | CA-19 | Z8-1 | CA-01 |
| Z5-14 | CA-14 | CR-M14 | CA-13 | Z8-2 | CA-04 |
| Z5-15 | CA-32 | CR-M15 | CA-21 | Z8-3 | CA-07 |
| Z5-16 | CA-07 | CR-M16 | CA-16 | Z8-4 | CA-04 |
| Z5-17 | CA-32 | CR-M17 | CA-17 | Z8-5 | CA-09 (CA-08) |
| Z5-18 | CA-05 | CR-M18 | CA-18 (CA-17) | Z8-6 | CA-10 |
| Z5-19 | CA-28 | CR-M19 | CA-18 | Z8-7 | CA-05 |
| Z5-20 | CA-13 | CR-M20 | CA-18 | Z8-8 | CA-06 |
| Z5-21 | CA-07 | CR-M21 | CA-20 | Z8-9 | CA-13 (CA-12, CA-21) |
| Z5-22 | CA-09 | CR-M22 | CA-20 (CA-09) | Z8-10 | CA-06 |
| C6-F1 | CA-15 | CR-M23 | CA-10 | Z8-11 | CA-20 |
| C6-F2 | CA-04 | CR-M24 | CA-16 | Z8-12 | CA-19 (CA-18) |
| C6-F3 | CA-04 | CR-M25 | CA-08 (CA-07) | IR-B-1 | CA-02 |
| C6-F4 | CA-08 | CR-M26 | CA-18 | IR-B-2 | CA-03 |
| C6-F5 | CA-18 | CR-N1 | CA-28 | IR-M-1 | CA-23 |
| C6-F6 | CA-18 | CR-N2 | CA-28 | IR-M-2 | CA-26 |
| C6-F7 | CA-31 | CR-N3 | CA-28 | IR-M-3 | CA-26 |
| C6-F8 | CA-28 | CR-N4 | CA-29 (CA-04) | IR-M-4 | CA-26 |
| C6-F9 | CA-05 (CA-09) | CR-N5 | CA-30 | IR-M-5 | CA-22 |
| C6-F10 | CA-31 | CR-N6 | CA-30 | IR-M-6 | CA-22 |
| C6-F11 | CA-31 | CR-N7 | CA-16 | IR-M-7 | CA-07 |
| C6-F12 | CA-27 | CR-N8 | CA-07 | IR-M-8 | CA-22 |
| CR-B1 | CA-01 | CR-N9 | CA-30 | IR-M-9 | CA-22 |
| | | CR-N10 | CA-30 | IR-M-10 | CA-25 |
| | | CR-N11 | CA-30 | IR-M-11 | CA-25 |
| | | CR-N12 | CA-31 | IR-M-12 | CA-07 |
| | | CR-N13 | CA-31 | IR-M-13 | CA-27 |

| Raw finding | Action | Raw finding | Action | Raw finding | Action |
| --- | --- | --- | --- | --- | --- |
| IR-M-14 | CA-24 | IR-N-1 | CA-31 | IR-N-13 | CA-24 |
| IR-M-15 | CA-10 | IR-N-2 | CA-07 | IR-N-14 | CA-14 |
| IR-M-16 | CA-10 | IR-N-3 | CA-25 | IR-N-15 | CA-33 |
| IR-M-17 | CA-05 | IR-N-4 | CA-07 | IR-N-16 | CA-22 |
| IR-M-18 | CA-24 | IR-N-5 | CA-27 | IR-N-17 | CA-23 |
| IR-M-19 | CA-24 | IR-N-6 | CA-25 | IR-N-18 | CA-33 |
| IR-M-20 | CA-27 | IR-N-7 | CA-33 | IR-N-19 | CA-33 |
| IR-M-21 | CA-27 | IR-N-8 | CA-33 | IR-N-20 | CA-33 |
| IR-M-22 | CA-27 (CA-04) | IR-N-9 | CA-25 | IR-N-21 | CA-33 |
| | | IR-N-10 | CA-26 | IR-N-22 | CA-33 |
| | | IR-N-11 | CA-26 | IR-N-23 | CA-33 |
| | | IR-N-12 | CA-22 | | |

The Stage 5 transforms are treated as proposed fixes, not findings:

| Transform | Disposition | Following |
| --- | --- | --- |
| T1 (link and adopt endpoints) | Replaced by CA-01's single link rule | Z8-1 |
| T2 (account tagging) | Rejected | Z8-1 |
| T3 (write-ahead intent rows) | Rejected; the crash window becomes an accepted risk | CA-13 |
| T4 (narrow lock) | Adopted | CA-06 |
| T5 (drift detector) | Rejected; drift becomes an accepted risk | CA-13 |
| T6 (deadline budget) | Rejected | CA-06 |
| T7 (background re-sync) | Rejected | CA-13 |
| T8 (shadow mode) | Rejected | CA-07 |
| T9 (drop the cache) | Adopted under CA-07 option (b); moot under option (a) | CA-07 |

## Suggested Order of Work

1. **Author decisions first:** CA-04 (AD-12, AD-16), CA-05 (AD-15), CA-07 (AD-17), CA-09 (AD-06) and CA-10 (AD-02). They decide how much of the Stage 9 work remains.
2. **Blockers:** CA-01, CA-02, CA-03.
3. **Remaining feature Majors:** CA-06, CA-08, CA-11 to CA-21.
4. **Plan Majors:** CA-22 to CA-27, re-scoped after step 1.
5. **Nits:** CA-28 to CA-33.

After the edits, run Stages 7 and 9 again. Each stage allows one re-audit.
