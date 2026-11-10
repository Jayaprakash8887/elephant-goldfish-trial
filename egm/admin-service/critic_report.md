# Goldfish Review Report

**Target:** `/home/jayaprakashn/Documents/workspace/elephant-goldfish-trial/docs/designs/cce-admin-service.md` (`Lines 1-1363`, FeatureRange, up to but not including `## Detailed Implementation Plan`)

**Stage:** Stage 7 (Critic Review), round 1

## Reviewer Status

| Reviewer | Status |
| --- | --- |
| `egm-design` (design_critic, feature-spec-standard) | completed |
| `egm-structural` (structural, feature-spec-standard) | completed |
| `egm-style` (style, no standard) | completed |

**Severity breakdown (after deduplication):** 1 Blocker, 26 Major, 25 Nit (52 findings). The reviewers raised 63 raw findings: design 1 / 15 / 12, structural 0 / 4 / 8, style 0 / 14 / 9. Where reviewers overlapped, the merged finding takes the highest severity any reviewer gave it and lists every lens.

The synthesizer spot-checked lines 745-784 and 1115-1125 and confirmed that `docs/designs/cce-admin-ui.md` exists. The other line references are as the reviewers reported them.

---

## Prioritized Findings (Blockers -> Major -> Nits)

## Blocker

### B1. There is no way to bring existing Keycloak users and roles under management - Severity: Blocker
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 165-167, 276-283, 424-426, 679, 686, 756, 1345-1351
* **Discrepancy Description**: The production realm already has users holding realm roles such as `INSIGHTS_READ`, which `api_permissions` rows grant (insights-ui architecture doc, sections 1 and 3). Under B-12 (lines 424-426), a token whose `sub` has no local row gets 403 on every endpoint, including `/me/menus`. Every way to bring these users and roles into the service is closed:
  * `POST /users` returns 409 if Keycloak already has the username or email (line 756).
  * `POST /roles` returns 409 `DUPLICATE_ROLE_NAME` if `api_permissions` already holds the name (line 686), so `INSIGHTS_READ` can never become a managed role.
  * Only the bootstrapper links an existing account, and only for `SUPER_ADMIN`.

  The same dead end follows a `USER_CREATE` 502 after step 5. The orphan Keycloak account is enabled and has its roles, a retry returns `USERNAME_IN_USE`, and no API path can adopt that account.
* **Rationale & Recommendation**: Once cce-insights-ui renders its sidebar from `/me/menus`, every existing console user gets 403 and an empty sidebar. The only workaround is new accounts, which breaks identity continuity. Add an audited "link existing Keycloak user" path, for example `POST /users` with a `keycloakId`, or an import endpoint, and have it check that the account is enabled. Define how legacy `permission_name` values become managed roles. Add a rollout step that does this before the insights-ui switch-over. If this is deliberately out of scope, make it an explicit Non-Goal and add a Risks row that states the rollout consequence.

## Major

### M1. `POST /roles` silently takes over a realm role that already exists in Keycloak - Severity: Major
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 686, 793, 815
* **Discrepancy Description**: The name-uniqueness check covers only `roles` and `api_permissions`. If Keycloak already has the realm role, its existing members silently gain whatever permissions the administrator saves. The role might have been created by hand, left over from a failed AD-12 delete, or mapped outside this module. `userCount` still shows 0. `ROLE_IN_USE` (line 815) checks only local `user_roles`.
* **Rationale & Recommendation**: One name is shared across three systems (PRD section 4), so this silently grants privileges. `POST /roles` should call `findRealmRole(name)` and refuse, or at least warn, when the role exists. Role delete should also check Keycloak role membership.

### M2. Role and status diffs are computed before the lock, so Keycloak and the database can drift - Severity: Major
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 512-514, 751, 771-785
* **Discrepancy Description**: In 5.4, step 2 computes `added` and `removed` before the lock is taken at step 4. Suppose request A changes {R1} to {R1,R2} and request B changes {R1} to {R1,R3} at the same time. Keycloak ends up holding R1, R2 and R3, while the database holds R1 and R3. R2 is then a live grant at the gateway that no screen shows. This contradicts "last write wins" (line 513). 5.3 has the same pattern: it reads the user before the lock, so the audit before-state can be stale.
* **Rationale & Recommendation**: Take a per-user `SELECT ... FOR UPDATE` on `users` before reading the current state, and compute the diff after it. Apply the same to 5.2.

### M3. Waits on the last-administrator lock can exhaust the connection pool and take `/me/menus` down - Severity: Major
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 750, 751, 824, 1343, 1357
* **Discrepancy Description**: Each waiter on the `SUPER_ADMIN` row lock holds a pooled connection while the lock holder is stuck in Keycloak calls. The holder can make up to 20 realm-role lookups plus an add and a remove call, at up to 10 s each, which is more than 200 s in the worst case. Every request also needs a connection for the local-user filter and the `last_login_at` UPDATE. GLOBAL sync marking (line 824) updates the same row. There is no overall request deadline, so the gateway may return 504 while the service goes on and commits.
* **Rationale & Recommendation**:
  * Use `SET LOCAL lock_timeout` or a try-lock advisory lock that fails fast with 409 or 503.
  * Batch the role lookups, or set a total time budget per request that is below the gateway route timeout. Ask the gateway owners for that timeout value.
  * Restate the accepted risk at line 1357 to match the real impact.

### M4. The first-deploy bootstrap runbook fails if followed in order - Severity: Major
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 219, 221, 1115-1125 (section 9.5)
* **Discrepancy Description**:
  * Step 6 asks for a gateway restart "after the first migration has seeded `api_permissions`", but that migration only runs at step 7.
  * At step 7 the bootstrapper finds no `SUPER_ADMIN` realm role and calls the sync (AD-15 (4)).
  * The gateway has not loaded the `CCE_ADMIN_SERVICE` row, so it refuses the sync, and the bootstrapper "logs an error and stops."
* **Rationale & Recommendation**: As written, no environment gets a first administrator on the first pass. Reorder the steps: deploy, restart the gateway, then restart the service with `BOOTSTRAP_ADMIN_USERNAME` set. Alternatively, add a manual step that creates and maps `SUPER_ADMIN`. State plainly that two deploys are needed.

### M5. Flyway may share its history table with the gateway's migration - Severity: Major
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 208, 921
* **Discrepancy Description**: The gateway's `V1__create_api_permissions_table.sql` sits in `src/main/resources/db/migration` (gateway doc, line 80), which is a Flyway layout. If Flyway has ever run against `admin_service.public`, its own V1 is already recorded in `flyway_schema_history`. This service's V1 is then skipped or fails checksum validation, the six owned tables are never created, and V2 fails. `baseline-on-migrate=true` hides this case instead of catching it.
* **Rationale & Recommendation**: Set `spring.flyway.table` to a module-specific name such as `admin_service_schema_history`. Add a migration test that starts from a database that already has a foreign history table.

### M6. Table ownership and grants between the two database users are not specified - Severity: Major
* **Reviewer Lens:** `egm-design` (related: `egm-style` M23)
* **Affected Scope:** Lines 895-901, 922, 1118
* **Discrepancy Description**: The gateway connects as `opensrp` (gateway doc, lines 63-64), and this service connects as a different user.
  * If this service creates `api_permissions`, nothing grants the gateway's user SELECT on it.
  * If the gateway created the table, this service needs INSERT, UPDATE and DELETE on it, plus USAGE on `api_permissions_id_seq`. V1 also runs `CREATE INDEX IF NOT EXISTS` and creates a trigger. PostgreSQL requires table ownership for the index, checked before `IF NOT EXISTS` applies, and the TRIGGER privilege for the trigger. So V1 fails.
* **Rationale & Recommendation**: Define the ownership model and the exact GRANT statements, and add them to 9.5. Make V1's DDL on `api_permissions` conditional on catalog checks, not only on `IF NOT EXISTS`.

### M7. Audit IP addresses can be spoofed, and the "gateway only" assumption is false locally - Severity: Major
* **Reviewer Lens:** `egm-design`, `egm-style`, `egm-structural`
* **Affected Scope:** Lines 376-377, 1005-1007, 1111
* **Discrepancy Description**:
  * The design takes the first `X-Forwarded-For` value, and `AUDIT_TRUST_FORWARDED_FOR` defaults to `true`. The client controls the first value.
  * Line 1007 says the service "is reached only through the gateway", but lines 376-377 describe direct local access on port 8085 (style).
  * The risk is not listed among the risks (structural).
* **Rationale & Recommendation**: `ip_address` is audit evidence (PRD section 9). Use the right-most address added by a trusted proxy, configured by hop count or CIDR, or use Spring's `ForwardedHeaderFilter`. Qualify line 1007 with "in non-local environments". Add a Risks row.

### M8. The token revocation window is not stated, and drift is undetectable - Severity: Major
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 52-53, 223 (AD-17 (3)), 328-334, 420-436, 658-661, 1052
* **Discrepancy Description**:
  * Roles come from the token, so a removed role keeps working until the token expires: 300 s locally, unknown in shared environments. 5.4 ends no sessions, and ending sessions would not revoke tokens already issued.
  * Deactivation has the same window at the gateway for other services' APIs. CUJ 5 claims a 403 only for this service.
  * The Problem statement names Keycloak/database drift, but nothing detects it. A `SUPER_ADMIN` role mapped directly in Keycloak grants full admin while `user_roles` shows nothing.
* **Rationale & Recommendation**: State the revocation window as a risk. Inside the service, intersect token roles with `user_roles` as defence in depth. Either add a read-only drift report or declare drift detection a Non-Goal.

### M9. The permission-pattern grammar exceeds what the gateway can parse, and the matcher-agreement claim is unverified - Severity: Major
* **Reviewer Lens:** `egm-design`, `egm-style`
* **Affected Scope:** Lines 263-270 (GW-3), 442-443, 454, 653
* **Discrepancy Description**:
  * `uriPattern` allows `**` as any segment, for example `/admin/**/roles`. A reactive gateway most likely uses Spring 6 `PathPattern`, which throws when `**` is not at the end. One saved row could then break the gateway's permission loading for every caller (design).
  * Line 454 says the rules "agree with Spring's `AntPathMatcher` for every pattern that section 4.5 accepts". No test compares the two matchers. The path normalization rules are not AntPathMatcher defaults. The pattern rules are in 4.4, not 4.5 (style).
* **Rationale & Recommendation**: Until GW-3 is answered, accept only the syntax both matchers share, with `**` only as the last segment. Add parse-failure behaviour to the GW-3 question. Write "are intended to agree" and add a unit test that runs both matchers over the same table. Fix the section reference.

### M10. The gateway doc contradicts itself on reload, and GW-2 quotes only one side - Severity: Major
* **Reviewer Lens:** `egm-design`, `egm-style`
* **Affected Scope:** Lines 259-262, 1123, 1358
* **Discrepancy Description**: Gateway doc line 8 says "Update permissions without restarting the service". Line 187 says a restart is required. GW-2 cites only the restart statement. Neither Goals nor Risks says what happens if a restart really is required. In that case PRD goal 2 (configure permissions in the console, not in SQL) fails in shared environments, and S3 and S6 prove nothing there.
* **Rationale & Recommendation**: Project rules require input conflicts to be written down, not resolved silently. Quote both statements in GW-2. Add a Risks row for "the sync does not reload" that names the fallback: who restarts the gateway, and when.

### M11. `audit_log.entity_id NOT NULL` breaks the PRD's own partial-failure case - Severity: Major
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 549-551, 748, 763, 913
* **Discrepancy Description**: A failure at `LOCAL_USER_SAVED` in 5.1 (the B-32/B3 case) happens before any local user id exists. The `PARTIAL_FAILURE` row therefore cannot be inserted. The fallback then logs the state and returns `auditLogId: null`, and the reconciliation record B-32 requires is lost. Line 550 also says a 502 on `POST /users` can come after the local row is saved. It cannot, because the only later failure is the email, which returns 201.
* **Rationale & Recommendation**: Generate the user UUID before step 4 and use it as `entity_id`, or make `entity_id` nullable for this case. Fix line 550.

### M12. The audit-row and 502 rules contradict each other, and the row counts are incomplete - Severity: Major
* **Reviewer Lens:** `egm-design`, `egm-style`
* **Affected Scope:** Lines 122-139 (G8), 542, 573-576, 748, 812, 1026-1027, 1133
* **Discrepancy Description**:
  * **Manual sync.** Lines 574-576 and 748 say a failure before any step completes writes no audit row and returns `auditLogId: null`. But 5.8 (line 812) and G8 say a failed manual sync writes `ROLE_SYNC_REQUESTED` and returns its id.
  * **Partial failures.** Line 748 says a partial failure writes the flow's own action with `PARTIAL_FAILURE`. G8, AD-06 and line 1027 give a failed sync after a permission save `ROLE_SYNC_REQUESTED` with `FAILED`.
  * **Error code.** Line 542 limits `KEYCLOAK_SYNC_FAILED` to failures after a change, but line 573 also uses it for failures before any change.
  * **Row counts.** G8 says "for most mutating endpoints" and lists only two exceptions. Role create (5.6, AD-16), role delete (5.9, AD-12) and the bootstrapper also write two rows.
* **Rationale & Recommendation**: The document says it leaves no decisions to the implementer, so these rules must agree. Add one table: flow, then outcome, then the exact audit rows (action and outcome). Make G8, the "every flow" rules and the section 10 checks point to it. Make line 542 match line 573.

### M13. Rejected alternatives give no technical reason, and some decisions have no alternatives - Severity: Major
* **Reviewer Lens:** `egm-design`, `egm-structural`, `egm-style`
* **Affected Scope:** Lines 1159-1327, especially 1285, 1297, 1309, 1327
* **Discrepancy Description**:
  * AD-14 A, AD-15 A, AD-16 A and AD-17 B give only "Rejected by author."
  * Every other entry opens with an unlinked "Rejected by author:". That repeats the field label and credits a person without a link.
  * There are no alternatives for:
    * AD-05 (event listener or polling for `last_login_at`)
    * AD-09
    * AD-10
    * AD-12
    * the `LAST_SYSTEM_ADMIN` locking strategy
* **Rationale & Recommendation**: The feature-spec standard requires each rejection to state a concrete trade-off, and forbids crediting a rejection to a person without a link. State each trade-off; the Cons line or the AD "Why" cell often has it already. Link each rejection to its AD row or to the gate record, or drop the prefix. Add the missing alternatives.

### M14. Partial failures and failed syncs raise no operational signal - Severity: Major
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 175-177, 748, 1331-1351
* **Discrepancy Description**: Reconciliation depends on someone noticing `PARTIAL_FAILURE` rows or failed syncs, and PRD section 12 says there is no screen for this. The design defines no metrics, no alert-worthy log event, no actuator endpoints beyond health, and no reconciliation query. Only the administrator who hit the 502 ever sees it.
* **Rationale & Recommendation**: Add an Observability NFR. It should cover a structured log event or a Micrometer counter for each partial failure and failed sync, a read-only query listing outstanding partial states, and who gets alerted.

### M15. Performance claims ignore `/me/menus` traffic and describe the per-request cost inconsistently - Severity: Major
* **Reviewer Lens:** `egm-design`, `egm-style`
* **Affected Scope:** Line 211 (AD-05), lines 1341-1344
* **Discrepancy Description**:
  * "Low-throughput admin CRUD" ignores that, under S1, every insights-ui page load by every user calls `/me/menus`. Each call costs a local-user lookup, the `last_login_at` UPDATE and an uncached menu join.
  * The design gives no QPS estimate, instance count, availability target or client behaviour for when the service is down (design).
  * AD-05 says "one write per sign-in", while Performance says "one conditional UPDATE per request" and "no query per request". It never mentions the per-request `users` SELECT (style).
* **Rationale & Recommendation**: Estimate the peak `/me/menus` QPS and set a minimum instance count, or justify one instance. State the expected client behaviour on failure. In both places, describe the real per-request cost: one SELECT plus one conditional UPDATE that changes a row once per sign-in.

### M16. Risks are not a standalone section and have no mitigations - Severity: Major
* **Reviewer Lens:** `egm-structural` (supported by `egm-design`'s standard-compliance note)
* **Affected Scope:** Lines 248-290, 1353-1360
* **Discrepancy Description**: The only risk register is the H3 "Risks accepted by the author", under Non-Functional Requirements. Its columns are Risk / Effect / Why accepted, with no Mitigation column. Other risks are scattered elsewhere:
  * GW-5, in Open Questions
  * `AUDIT_TRUST_FORWARDED_FOR=true`
  * the stand-in's unauthenticated control endpoint (AD-11)
  * `PERMISSION_CHECK_ENABLED=false` (AD-17)
* **Rationale & Recommendation**: The standard asks for failure modes, security implications and scalability limits, each with a mitigation. Add a top-level `## Risks & Mitigations` section with a Mitigation column. Include the security risks, the gateway assumptions GW-1 to GW-6, and the partial-failure risks, plus the risks raised in M3, M7, M8 and M10.

### M17. Links point to the console's working draft, not its saved design - Severity: Major
* **Reviewer Lens:** `egm-structural`
* **Affected Scope:** Lines 21, 41, 230
* **Discrepancy Description**: The Inputs table and Open Questions link `../../egm/admin-ui/design_draft.md` (Status: Draft). Line 21 names `egm/admin-ui/` as plain text. The saved companion design, `docs/designs/cce-admin-ui.md`, exists and has the same AS-REQ-01 to AS-REQ-24 table and CQ-1.
* **Rationale & Recommendation**: A Proposed design should not depend on a scratch file that a new EGM cycle overwrites. Point all three references to `cce-admin-ui.md`.

### M18. Some identifiers are defined nowhere the reader can find them - Severity: Major
* **Reviewer Lens:** `egm-structural`, `egm-style`
* **Affected Scope:** Lines 207-223, 213, 216, 652
* **Discrepancy Description**:
  * OQ-1 to OQ-15 (the Question column) are not defined anywhere in the target, the console design, the draft or `author-decisions.md`.
  * Pushbacks P1, P2 and P4 (lines 213, 216) are undefined. AD-10's "Superseded part" depends on P2.
  * "console review CQ-1" (line 652) is not linked.
* **Rationale & Recommendation**: Drop the OQ and P labels and keep the inline text, or link each one to where it is recorded. Link CQ-1 to `cce-admin-ui.md`.

### M19. Trial, process and workstation wording has leaked into the design - Severity: Major
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 26-27, 72-74, 140, 148, 156, 168, 194, 201, 218, 227, 1161
* **Discrepancy Description**:
  * Process wording: "not inputs to this trial", "A coding agent will implement this service from this document alone...", "drafting gates", "There was no brainstorm phase".
  * Workstation wording: "this machine", including DEV-2's "Jenkins is not available on this machine".
* **Rationale & Recommendation**: These phrases describe how the document was written, not the system. A new team member cannot interpret them. Restate them as facts about the inputs, for example "The data dictionary, API reference and flow diagrams cited by the PRD are unavailable; this document defines that content." Replace "this machine" with "the local development environment", or name the real constraint.

### M20. The document narrates its own edits instead of stating the current rule - Severity: Major
* **Reviewer Lens:** `egm-style`, `egm-structural`
* **Affected Scope:** Lines 111-112, 212, 214, 216, 220, 222, 1235
* **Discrepancy Description**: Examples include "G8 is amended to match", "G5 is amended to match", "AD-07 is amended to match", AD-10's "Superseded part: ... so it is dropped" (repeated in AD-16), and "the original G5".
* **Rationale & Recommendation**: As written, a reader has to replay the draft history to find the rule in force. State only the current rules, and move the history to a changelog or to Alternatives.

### M21. Goals do not follow the format the document promises - Severity: Major
* **Reviewer Lens:** `egm-style`, `egm-structural`
* **Affected Scope:** Line 78; G4, G6, G9, G10, G11; lines 24, 37, 1144-1155
* **Discrepancy Description**:
  * Line 78 says every goal names its PRD IDs and how it is checked. G4, G6 and G10 have no "Check:", G9 and G11 have no explicit check line, and G10 cites no PRD ID.
  * Within this range, milestones B1 to B5 appear only as a span, so coverage cannot be checked from the feature section.
  * The E2E list runs S2 to S6 and then S1, while the CUJs run S1 to S6.
* **Rationale & Recommendation**: Give every goal its PRD IDs and a check, or reword line 78. Consider adding a goal-to-milestone column, and put the E2E list in S1 to S6 order.

### M22. G10's configurability claim is a hedge that cannot be tested - Severity: Major
* **Reviewer Lens:** `egm-style`, `egm-design`
* **Affected Scope:** Lines 151-155 (G10), 257, 960, 1360
* **Discrepancy Description**: "Wherever that is reasonable" cannot be tested. The document already shows what is configurable (`GATEWAY_BASE_URL`, `GATEWAY_SYNC_PATH`, the timeouts, `GATEWAY_SYNC_SCOPE`, `PERMISSION_CHECK_ENABLED`) and what needs code changes: the request body, a per-role parameter, the matcher, and token forwarding (GW-6). The sync path in the seeded `CCE_ADMIN_SERVICE` row is hard-coded, and system roles cannot be edited through the API, so a different GW-1 path needs a migration (design).
* **Rationale & Recommendation**: Replace the hedge with the explicit list of what is configurable and what needs a code change or migration.

### M23. The rollout claim leaves out the changes made to a table another team owns - Severity: Major
* **Reviewer Lens:** `egm-style` (related: M6)
* **Affected Scope:** Line 1346 (also 899, 922)
* **Discrepancy Description**: "The migrations only add tables and rows" is not accurate. V1 can add a unique constraint, indexes and an `updated_at` trigger to an existing gateway-owned `api_permissions`, and it also creates the `pgcrypto` extension.
* **Rationale & Recommendation**: List every object the migrations may add to an existing `api_permissions` table, and the extension.

### M24. Line 1360 states a test result before any test has run - Severity: Major
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Line 1360
* **Discrepancy Description**: "Locally everything passes against the stand-in". The design is Proposed, so nothing has passed yet.
* **Rationale & Recommendation**: Reword to: "The local checks run only against the stand-in, so passing them does not show how the real gateway behaves."

### M25. A guess is used to justify accepting a risk - Severity: Major
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Line 1358
* **Discrepancy Description**: "The gateway's own reload behaviour (GW-2) is likely the slower of the two anyway" rests on an open question. "At most 30 seconds" holds only at the default TTL.
* **Rationale & Recommendation**: Drop the GW-2 sentence. Write "at most `PERMISSION_CACHE_TTL` (default 30 s)".

### M26. Long procedures are buried in Author Decisions table cells - Severity: Major
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 207-223 (AD-13, AD-15, AD-16); section 5.13
* **Discrepancy Description**: AD-13, AD-15 and AD-16 each fill one or more paragraphs in a single cell, with numbered sub-steps. Section 5.13 says the bootstrap is "Described in AD-15", so the only full bootstrap procedure lives in a table cell.
* **Rationale & Recommendation**: Cut each table row to the question, a one-line decision and the reason. Move the procedures into section 5 or 9 and link to them.

## Nit

### N1. The 5.1 step 3 condition reads inverted - Severity: Nit
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Line 756
* **Discrepancy Description**: "Ask Keycloak whether ... already used ... Otherwise 409" reads backwards.
* **Rationale & Recommendation**: Write "If either is used, 409 `USERNAME_IN_USE` or `EMAIL_IN_USE`."

### N2. DEV-3 misstates PRD section 5 - Severity: Nit
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 195, 1177-1178
* **Discrepancy Description**: The PRD section 5 diagram itself says "PostgreSQL (schema admin_service)", so section 5 contradicts itself. It does not simply contradict section 6.
* **Rationale & Recommendation**: Record the inconsistency inside section 5 as it actually is.

### N3. The "one transaction per request" wording conflicts with the flows - Severity: Nit
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 379-380
* **Discrepancy Description**: The wording conflicts with 5.1, 5.7, 5.10 and the audit writes that use their own new transaction.
* **Rationale & Recommendation**: Reword it to match the flows.

### N4. The Deviations table is missing several deviations - Severity: Nit
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 186-198
* **Discrepancy Description**: The table does not list:
  * a 502 `KEYCLOAK_SYNC_FAILED` when nothing changed, against Appendix B's "state partially applied"
  * the `details` field in the error envelope
  * two audit rows per permission save, role create and role delete, against B-50's one row per mutation
  * hiding a `DETAIL` whose parent is not granted, against B-21
* **Rationale & Recommendation**: Add these rows.

### N5. Keycloak user ids are typed as uuid - Severity: Nit
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 549, 607, 851
* **Discrepancy Description**: Federated users without import have ids of the form `f:<provider>:<id>`, which are not UUIDs.
* **Rationale & Recommendation**: State that the realm uses no federation, or use `varchar(255)`.

### N6. `menus.path` has no stated base - Severity: Nit
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 639, 929-944
* **Discrepancy Description**: The insights-ui image uses `VITE_ROUTER_BASE=/insights`, and the design does not say what `menus.path` is relative to.
* **Rationale & Recommendation**: State that `menus.path` is relative to the console's router basename.

### N7. Swagger UI and the OpenAPI document are on and public in every environment - Severity: Nit
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 411-413, 1113
* **Discrepancy Description**: `SPRINGDOC_ENABLED` defaults to `true` and the endpoints are public.
* **Rationale & Recommendation**: Consider defaulting it to off outside local.

### N8. The raw-path matcher depends on firewall defaults - Severity: Nit
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 442-443
* **Discrepancy Description**: The matcher checks the raw path, but Spring MVC routes on the decoded path. The two agree only because `StrictHttpFirewall` rejects `%2F`, `;` and encoded dots by default.
* **Rationale & Recommendation**: State that dependency and add a test for it.

### N9. The "least privilege" claim leaves out the service account's blast radius - Severity: Nit
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 988-990
* **Discrepancy Description**: With `manage-users`, the service account can change or disable any user in the realm and map any non-admin realm role.
* **Rationale & Recommendation**: State the blast radius. Mention Keycloak 26 fine-grained admin permissions as a way to narrow it.

### N10. Audit PII retention is not stated, and the append-only guard is weak - Severity: Nit
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 903-919, 1008
* **Discrepancy Description**: Before and after snapshots of names and emails are kept forever, and no retention or PII stance is stated. The table owner can drop the append-only trigger.
* **Rationale & Recommendation**: State the retention and PII policy. Also revoke UPDATE and DELETE on the table, which is a stronger guard than the trigger.

### N11. `/health` returns 200 even when the database is down - Severity: Nit
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Line 675
* **Discrepancy Description**: The response is 200 with `"database": "DOWN"`, and the design does not say how probes should treat it.
* **Rationale & Recommendation**: State whether probes should treat that response as unhealthy.

### N12. Markdown blank lines are missing around tables and headings - Severity: Nit
* **Reviewer Lens:** `egm-structural`
* **Affected Scope:** Lines 198-199, 847-848, 862-863, 875-876, 903-904
* **Discrepancy Description**: A heading follows a table directly, and several tables follow bold labels with no blank line.
* **Rationale & Recommendation**: Add the blank lines so stricter renderers display them correctly.

### N13. Subsections are formatted inconsistently - Severity: Nit
* **Reviewer Lens:** `egm-structural`
* **Affected Scope:** Sections 4-9
* **Discrepancy Description**: 4.x and 9.x are H4 headings, but flows 5.1-5.13 are bold labels even though other sections cite them. The section 6 tables use three different formats.
* **Rationale & Recommendation**: Make 5.1-5.13 H4 headings so they can be linked, and use one format for all seven tables.

### N14. The ASCII diagram is misaligned and has no legend - Severity: Nit
* **Reviewer Lens:** `egm-structural`
* **Affected Scope:** Lines 350-365
* **Discrepancy Description**: The box borders are off by one column at lines 351 and 363-364, and `creates` runs into `└` at line 359. The flow numbers have no legend and are written inconsistently.
* **Rationale & Recommendation**: Realign the borders and add a legend.

### N15. Some cross-references are imprecise - Severity: Nit
* **Reviewer Lens:** `egm-structural`, `egm-style`
* **Affected Scope:** Lines 219, 221, 230, 282, 454
* **Discrepancy Description**: "section 9" should be 9.5, "Technical Plan, section 4" should be 4.6, and "section 4.5" at line 454 should be 4.4.
* **Rationale & Recommendation**: Correct each reference.

### N16. Link and path notation is inconsistent - Severity: Nit
* **Reviewer Lens:** `egm-structural`
* **Affected Scope:** Lines 31, 40, 202, 411, 497, 675, 679, 703-710, 747-760, 1035, 1143
* **Discrepancy Description**:
  * Links to `author-decisions.md` use `../../docs/designs/author-decisions.md`, which leaves the directory and comes back.
  * Short and full endpoint paths are mixed, as are `:id` and `{id}`.
  * Some long lines are not wrapped.
* **Rationale & Recommendation**: Link `author-decisions.md` directly. Pick one path notation. Wrap lines to about 100 columns.

### N17. B-05 is quoted inaccurately - Severity: Nit
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 197 (DEV-5), 498
* **Discrepancy Description**: B-05 says `{status:"UP"}` "with best-effort DB status". It does not name a `database` field.
* **Rationale & Recommendation**: Say that the `database` field is this design's choice.

### N18. DEV-3 states an inference about `search_path` as fact - Severity: Nit
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 195, 1169
* **Discrepancy Description**: "It reads `public`" holds only under the default `search_path`. A schema named after the connecting user is searched first.
* **Rationale & Recommendation**: Qualify the statement and note the `$user` case.

### N19. DEV-4 presents a deviation as an interpretation - Severity: Nit
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 196, 1219-1220
* **Discrepancy Description**: The row records a deviation and then argues that "Terminal" can be read as compatible with the design. It cannot be both.
* **Rationale & Recommendation**: Keep the deviation and its evidence, and drop the reinterpretation.

### N20. Intensifiers - Severity: Nit
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 208, 212, 217, 223, 949, 1229-1230
* **Discrepancy Description**: The text uses intensifiers: "exactly the state", "exactly what an auditor asks", "says plainly", "Real defence in depth", "It is easy to change".
* **Rationale & Recommendation**: Drop them. The sentences keep their meaning.

### N21. The icon-choice sentence repeats the one before it - Severity: Nit
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 946-950
* **Discrepancy Description**: The sentence about the author's icon choice restates the previous sentence.
* **Rationale & Recommendation**: Merge the two sentences.

### N22. A lone first-person "we" - Severity: Nit
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 219, 1277
* **Discrepancy Description**: "we have no evidence of" is the only first-person wording in the document.
* **Rationale & Recommendation**: Write "the inputs show no such check".

### N23. Some wording is vague or absolute - Severity: Nit
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 53, 1336, 1357; AD-05, AD-15
* **Discrepancy Description**: "nobody notices", "has least privilege", "safe" and "low-traffic admin work" are vague, and none is backed by a figure.
* **Rationale & Recommendation**: Write "no process detects it", "holds only the roles listed in section 7" and "idempotent". Give a traffic figure.

### N24. "Open Questions" holds only decided items - Severity: Nit
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 225-246 (line 234 "so far")
* **Discrepancy Description**: Every item in the section is already decided, yet it is titled Open Questions and says "so far".
* **Rationale & Recommendation**: Retitle it as a traceability table, or keep only the questions that are still open.

### N25. Swagger, the stand-in control endpoint and the permission-check toggle need explicit defaults per environment - Severity: Nit
* **Reviewer Lens:** `egm-structural`
* **Affected Scope:** AD-11, AD-17, section 9.4 table
* **Discrepancy Description**: These are environment-sensitive switches (`SPRINGDOC_ENABLED`, the unauthenticated stand-in control endpoint, `PERMISSION_CHECK_ENABLED=false`). The design does not say what each must be in shared environments.
* **Rationale & Recommendation**: State the required value of each switch in shared environments, and point to the Risks section (M16).
