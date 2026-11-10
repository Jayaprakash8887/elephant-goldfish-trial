# Goldfish Review Report

**Target:** `docs/designs/cce-admin-ui.md` (`FeatureRange, lines 1-978`)

Stage 7 (Critic Review). Lines 979 onward (Detailed Implementation Plan) were out of scope and
were not reviewed.

## Reviewer Status

| Reviewer | Persona | Standard | Status |
| --- | --- | --- | --- |
| `design` (egm:egm-design) | `agents/design_critic.md` | `feature-spec-standard.md` | completed |
| `structural` (egm:egm-structural) | `agents/structural.md` | `feature-spec-standard.md` | completed |
| `style` (egm:egm-style) | `agents/style.md` | none | completed |

Each reviewer read its persona, its standard if it had one, the whole target file, and the
whole reading list. Nobody needed a retry.

## Severity Summary

| Severity | Count |
| --- | --- |
| Blocker | 1 |
| Major | 28 |
| Nit | 31 |

Merging duplicates: the design and structural reviewers both raised the missing Risks section,
so it appears once as D-1. Some findings were raised under more than one lens. Each of those is
kept once, under the id of its main lens, and the others are listed in the "Also raised by"
line. The synthesizer checked every line number below against the target file. Where a finding
depends on the admin-service design, the claim was also checked against that file (lines 575,
627, 656, 748, 812, 1006, 1341, 1353, and CUJ headings 297-336).

Id prefixes: `D-` design, `S-` structural, `ST-` style.

---

## Prioritized Findings (Blockers -> Major -> Nits)

### D-1 No Risks and Limits section - Severity: Blocker
* **Reviewer Lens:** `design`, `structural`
* **Affected Scope:** No such section anywhere in lines 1-978. Non-Functional Requirements, lines 965-975, has only Security and Rollout. Risk material is scattered: 141-142, 147-152, 225-229, 488-492, 846-848, 922-935.
* **Discrepancy Description**: The feature-spec standard requires the spec to name its failure modes, security implications and scalability limits, and to say how each is mitigated. Risks & Mitigations is a required area. This design has none of that, and it has no Performance or scalability entry in its NFRs. The sibling admin-service design has both: a Performance bullet (admin-service line 1341) and a "Risks accepted by the author" table (line 1353). The material that exists here is never framed as risk plus mitigation:
    * `SYNCED` does not mean the gateway enforces the rows (CQ-2 / GW-2). An admin who revokes a dangerous permission sees "Synced" while the gateway keeps enforcing the old row until it restarts. Only help text addresses this.
    * Last-write-wins on permission rows.
    * Error paths that are covered only by MSW tests.
    * A hand-written `contract.ts` that can drift from the service.
    * `useAllRoles` fetches every role page with no upper bound.
    * Caddy does not route `/auth`.
* **Rationale & Recommendation**: Add a `### Risks accepted by the author` section under Non-Functional Requirements, or a separate H2, so the two designs match. Use a Risk | Effect | Mitigation or why accepted table. At minimum cover:
    * GW-2 (revocation not yet enforced)
    * last-write-wins (D-7)
    * gateway-originated errors (D-3)
    * writes whose outcome is unknown (D-4)
    * auth redirect loops (D-5)
    * XSS/CSP (D-6)
    * audit-IP integrity (D-8)
    * deployment topology (D-18)
    * `useAllRoles` scale (D-16)
    * MSW-only error paths (lines 150-152)

  Also add a Performance and scalability bullet to Non-Functional Requirements.

---

### D-2 Goal 6 ("cannot drift ... without a type error") overclaims - Severity: Major
* **Reviewer Lens:** `design`
* **Also raised by:** `style` (M6)
* **Affected Scope:** Lines 101-104; also 34-37, 428-437, 782-786, 932-935.
* **Discrepancy Description**: `contract.ts` is written by hand from a prose design. The type checker only ties the client and the MSW handlers to `contract.ts`. It cannot catch any of these:
    * `contract.ts` drifting from admin-service §4.
    * The running service drifting from either of them.
    * MSW behaviour drifting from the service: filters, no-ops and 409 rules (lines 782-786).

  Alternative F (lines 932-935) says Goal 9's browser tests catch drift. Those tests cover only CUJ 2-6. They never touch `POST`/`PUT /menus`, `PUT`/`DELETE /roles/:id`, `PUT /users/:id`, reset-password, or a multi-page `GET /roles`.
* **Rationale & Recommendation**: Reword Goal 6 to claim only what is true: "Request and response shapes in the client and MSW handlers cannot drift from `src/api/contract.ts` without a type error." Then do one of these:
    * Add a stack-run test that compares `contract.ts` with the service's springdoc OpenAPI document (B-06).
    * List the endpoints that no real-stack test covers as an accepted risk (D-1).
    * Add API-level smoke calls for those endpoints to `npm run e2e`.

### D-3 Errors from the gateway are assumed to use the admin-service error envelope - Severity: Major
* **Reviewer Lens:** `design`
* **Affected Scope:** Lines 150-152, 165 (C-05), 447-452, 497-509.
* **Discrepancy Description**: `describeError` sorts errors by `code`. A body that is not a valid envelope becomes `CLIENT_UNEXPECTED_RESPONSE`, which is shown as `unexpected` with Retry. No input says that `gateway-service` returns `{error:{code:"FORBIDDEN"}}`. The gateway doc only says Allow/Deny.

  So a real gateway 403 would show a generic error with a Retry button. That breaks F-04 and Goal 5 in exactly the environment where the gateway is the main control. The MSW test that "covers" a gateway 403 (C-05) would be written with the admin-service envelope, so it tests the assumption rather than the gateway.
* **Rationale & Recommendation**:
    * When the body is not an envelope, classify by HTTP status: 403 becomes `forbidden`, and 401 goes down the existing refresh path.
    * Add MSW cases with an empty body and with a Spring-default 401/403 body.
    * Add an open question for the gateway owners about the shape of their error bodies, alongside admin-service GW-1 to GW-6.

### D-4 Writes whose outcome is unknown get a Retry button - Severity: Major
* **Reviewer Lens:** `design`
* **Affected Scope:** Lines 438-452, 468-470, 508-509; context 264-266, 609-611.
* **Discrepancy Description**: Server-side writes can take tens of seconds:
    * `POST /users` makes several Keycloak calls, each with a 2 s connect and 10 s read timeout (admin-service §5.1).
    * Status and role changes wait on a lock that is held across Keycloak calls.

  The design sets no client timeout and says nothing about Caddy or gateway route timeouts. When a proxy times out, the browser gets a 502/504 without an envelope, or status 0. That is shown as `unexpected` or `network` with Retry (lines 508-509). For a mutation, Retry re-sends a write that may already have been applied. This contradicts line 470 ("Mutations are never retried"). It produces a confusing `USERNAME_IN_USE` on user create and a second email on reset-password.
* **Rationale & Recommendation**:
    * Add an "outcome unknown" state for mutations that fail with status 0, a non-envelope 5xx, or a 504. It says the change may have been applied, refetches the affected queries, and offers no Retry button.
    * Keep Retry for queries only.
    * Write down the proxy timeout the console expects, and set Caddy's `reverse_proxy` transport timeouts above the service's worst case.

  See also ST-8.

### D-5 Auth failures can cause an endless redirect loop or a needless redirect - Severity: Major
* **Reviewer Lens:** `design`
* **Affected Scope:** Lines 392-395, 453-457; related 568-571.
* **Discrepancy Description**:
    * **(a) Endless loop.** On a second 401 the client calls `login()`. If the 401 comes from configuration, such as a missing `gateway-service` audience mapper or an issuer mismatch, Keycloak still has a valid SSO session. It sends the browser straight back, the next call gets 401 again, and the cycle never ends.
    * **(b) Needless redirect.** Any failed `updateToken(70)` calls `login()`, including a short Keycloak network blip while the token is still valid. The admin loses unsaved form state. The `useBlocker` guard (568-571) does not cover a full-page redirect.
* **Rationale & Recommendation**:
    * Count `login()` attempts within a time window, for example in `sessionStorage`. After one repeat, show the start-up error screen with the cause instead of redirecting again.
    * On a refresh failure, call `login()` only if the token has actually expired or Keycloak rejected the refresh token. Otherwise retry on the next tick.
    * Add both cases to `keycloak.test.ts` and `client.test.ts`.

### D-6 The highest-privilege console has no Content-Security-Policy - Severity: Major
* **Reviewer Lens:** `design`
* **Affected Scope:** Lines 841-842, 398-400, 967-972.
* **Discrepancy Description**: Holding the token only in memory does not protect against script injection into the page: injected script can read `keycloak.token` or call `fetch` with `/admin/v1/**` rights. The template's three headers (X-Frame-Options, nosniff, Referrer-Policy) include no CSP. PRD §9 asks for defence in depth, and the NFR Security entry does not mention XSS.
* **Rationale & Recommendation**: Add a CSP to the Caddyfile, at least:
    * `default-src 'self'`
    * `script-src 'self'`
    * `connect-src 'self' <keycloak-origin>`
    * `frame-ancestors 'none'`
    * `object-src 'none'`
    * `base-uri 'none'`

  Say how the Keycloak origin reaches the CSP at runtime, for example a `KEYCLOAK_ORIGIN` environment variable checked at start-up like `GATEWAY_UPSTREAM`. Record the CSP under NFR Security.

### D-7 Last-write-wins on permission rows is accepted without weighing the security cost - Severity: Major
* **Reviewer Lens:** `design`
* **Affected Scope:** Lines 141-142, 212 (AS-REQ-21), 676-681.
* **Discrepancy Description**: PRD §4 calls the role-to-permission mapping a hard control where wrong configuration is a security issue. Two admins on the same Permissions tab can lose each other's work: admin B removes a dangerous row and saves, then admin A saves from an older view and silently restores the row. The service has no ETag. But the console could narrow the gap without changing the contract, because `GET /roles/:id/permissions` returns the full current set.
* **Rationale & Recommendation**:
    * Before `PUT /roles/:id/permissions` (and the role-menus and user-roles saves), refetch the current set and compare it with the base the form started from. If they differ, show the differences and require confirmation.
    * Record the remaining race as an accepted risk (D-1).
    * Raise a service-side ETag as a follow-up request on the admin-service design.

### D-8 The Caddy hop's effect on audit IP integrity is not specified - Severity: Major
* **Reviewer Lens:** `design`
* **Affected Scope:** Lines 366, 762, 841-845.
* **Discrepancy Description**: The admin-service records the first `X-Forwarded-For` value and trusts that header by default (admin-service line 1006; B-50 requires the IP). This design puts Caddy in front of the gateway but does not say how Caddy handles the header:
    * With default settings behind an ingress, every audit row records the ingress IP.
    * If Caddy trusts every peer, a browser can forge the IP stored in `audit_log`.
* **Rationale & Recommendation**:
    * Set Caddy's `trusted_proxies` to exactly the environment's ingress ranges, taken from configuration.
    * Write down the header chain end to end: ingress, Caddy, gateway, service.
    * Confirm with the gateway owners whether the gateway appends to the header or replaces it.
    * Add a deployment check that the audit `ip_address` is the client's address.

### D-9 CUJ 4 and the role-picker hint assume a `FAILED` role cannot be assigned - Severity: Major
* **Reviewer Lens:** `design`
* **Affected Scope:** Lines 280-289 (CUJ 4), 603-605 (hint), 798-803 (e2e data setup).
* **Discrepancy Description**: The service refuses an assignment only when the realm role is missing in Keycloak (AD-10). `syncStatus` is "a display hint only" (admin-service line 627). A role that was created and synced while the stand-in was `ok` already has its realm role. A later failed permission save marks it `FAILED`, but assigning it still succeeds.

  CUJ 4's precondition ("The gateway sync is failing") does not say the role was created while the sync was failing. If the browser test creates its role through the API before it switches the stand-in to `fail`, the expected `ROLE_NOT_SYNCED` never happens and the test fails every time.

  In production the hint "assigning it will fail" is wrong for `FAILED` roles, which can be assigned while the gateway enforces their previous rows.
* **Rationale & Recommendation**:
    * Change CUJ 4's precondition to "the role is created while the sync is failing (`ROLE_CREATE` 502)", and say that the test switches the stand-in to `fail` before it creates the role.
    * Word the hint by status:
        * `NEVER`: "may not be assignable until synced".
        * `FAILED`: "the last sync failed; its permissions may not be in effect. Re-sync before assigning."

### D-10 `SyncFailedNotice` text is wrong or missing when `completedSteps` is empty - Severity: Major
* **Reviewer Lens:** `design`
* **Affected Scope:** Lines 535, 538, 539, 540.
* **Discrepancy Description**: `USER_PROFILE_UPDATE` and `USER_STATUS_CHANGE` have text only for "steps completed". `USER_ROLES_UPDATE` always says an operator reconciles from the audit log.

  The contract says a Keycloak failure before the first named step returns 502 with `completedSteps: []`, writes no audit row, and has `auditLogId: null` (admin-service lines 575, 748). In that case the console sends the admin to an operator and an audit row that do not exist. It offers no retry, even though nothing changed and a retry is safe. F-05 asks for the partial state and the next step, and here the next step is wrong.
* **Rationale & Recommendation**:
    * Give every operation an explicit no-steps branch ("Nothing was changed. You can try again.") with a Retry action.
    * Show the "operator reconciles" text only when `completedSteps` is not empty.
    * Add these cases to the MSW tests.

  See also ST-10.

### D-11 A `ROLE_DELETE` 502 started from Role detail has nowhere to be shown - Severity: Major
* **Reviewer Lens:** `design`
* **Affected Scope:** Lines 545, 631, 640-642.
* **Discrepancy Description**: Role detail offers Delete with "same rules as the list". The notice table, however, shows `ROLE_DELETE` only on the Roles list. The design does not say what Role detail does after a delete, whether it succeeds or returns 502.

  The role is already gone. A refetch therefore gives `ROLE_NOT_FOUND`, which renders `NotFoundPanel` (line 506), and the notice is lost. That contradicts Goal 3 ("always" shows `SyncFailedNotice`).
* **Rationale & Recommendation**: After a delete from Role detail (204 or 502), go to `/roles` and pass the result in navigation state, the same way user create hands its result to user edit (lines 606-609). The Roles list then shows "Role deleted" or the `ROLE_DELETE` notice above the table. Add a test for each case.

### D-12 `USER_CREATE` no-steps text can be wrong after a timeout, and the `USERNAME_IN_USE` that follows is never explained - Severity: Major
* **Reviewer Lens:** `design`
* **Affected Scope:** Lines 264-266, 514-516, 537, 609-611.
* **Discrepancy Description**: The service counts a timeout as a failure. A read timeout on the Keycloak create call returns 502 with `completedSteps: []`, yet Keycloak may have created the account anyway. The console then says "Nothing was created. You can submit again."

  When the admin resubmits, the service finds a Keycloak account with no local row and returns `USERNAME_IN_USE` or `EMAIL_IN_USE`. The user does not appear in the Users list, and no screen explains that an orphan Keycloak account is the cause. The same unexplained 409 follows any `USER_CREATE` 502 with completed steps once the page visit ends.
* **Rationale & Recommendation**:
    * Soften the no-steps text, for example: "The service recorded no change. If submitting again reports the username or email in use, an operator must check Keycloak for an account without a local user."
    * Add the same hint to `USERNAME_IN_USE` and `EMAIL_IN_USE` on the create form.
    * Ask the admin-service design for a detail that says when the conflict exists only in Keycloak.

### S-1 Rejections are attributed to "the author" without a link to a decision record - Severity: Major
* **Reviewer Lens:** `structural`
* **Also raised by:** `design` (Nit 7)
* **Affected Scope:** Lines 179, 867, 880, 892, 906, 919, 932.
* **Discrepancy Description**: The Alternatives Rule in the standard says a rejection must not be attributed to a maintainer without linking the issue, discussion or PR. Every "Rejected by author:" names who decided but links nothing. The gate records are in `trial-log/QA.md`, which this document never links.

  Separately, Alternative D's con "cannot run at the same time as the real service, which needs the same port" (lines 903-904) is weak. Using another port avoids the clash.
* **Rationale & Recommendation**: Link each attribution to its gate record or decision row, or drop the "by author" attribution and let the technical trade-off stand alone. Remove or reword the port argument in Alternative D.

### S-2 Gate and step identifiers are used but never defined, and there is no Author Decisions section - Severity: Major
* **Reviewer Lens:** `structural`
* **Also raised by:** `style` (M8)
* **Affected Scope:** Lines 161, 179, 219-221, 231-239, 402, 758-759, 771, 854-856, 932, 947.
* **Discrepancy Description**: These terms are not defined in this document, in `author-decisions.md`, or in the admin-service design:
    * "gate TP-1" and "gate TP-2"
    * "Step 1, item 7"
    * "Decided by the author at finalization"
    * "The admin-service draft read ..."
    * "during this drafting session"
    * "At the admin-service drafting gates"

  The sibling design has an H2 "Author Decisions in This Design" with an ID | Question | Decision | Why table (admin-service line 199). Here, a bare bullet list sits under Open Questions (lines 231-239), mostly without reasons. For example, "Browser tests run one at a time" is explained only at line 790.
* **Rationale & Recommendation**: Add an "Author Decisions in This Design" H2 that mirrors the sibling's table. Give TP-1, TP-2 and the Step 1 item 7 decision their own rows, and move the bullets from lines 231-239 into it, each with its reason. Remove the history of the drafting process from the prose.

### S-3 "Open Questions" contains no open questions and hides a real external dependency - Severity: Major
* **Reviewer Lens:** `structural`
* **Also raised by:** `style` (Nit 15)
* **Affected Scope:** Lines 181-239, especially 217 and 225-229.
* **Discrepancy Description**: Every subsection is labelled "all answered", "decided" or "accepted". CQ-2 depends on admin-service GW-2, which the gateway owners have not yet answered, but it is filed as decided. The section also mixes three kinds of content: the AS-REQ traceability table, contract points, and drafting decisions.
* **Rationale & Recommendation**: Under Open Questions, keep only what is actually open, starting with the GW-2 dependency and its interim assumption. Move the AS-REQ table to a traceability subsection, and move the decided items to the section proposed in S-2.

### ST-1 "Approved" is claimed for a contract whose design is marked Proposed - Severity: Major
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 37-38, 167.
* **Discrepancy Description**: Line 37 says the contract "is complete and approved by the author", and line 167 refers to "the approved designs in `docs/designs/`". Both designs are `Status: Proposed` (line 4; admin-service line 4). `author-decisions.md` also records "Claude, acting for the user" as the decider, so "approved by the author" suggests a human sign-off that the sources do not record.
* **Rationale & Recommendation**: Write "The contract is defined in the admin-service design (Status: Proposed)." In C-07, write "the designs in `docs/designs/`".

### ST-2 "Every departure from the template comes from the PRD" is not true - Severity: Major
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 31-32.
* **Discrepancy Description**: Several departures from `cce-insights-ui` do not come from the PRD, and three of them are not listed in Conflicts:
    * Playwright is the author's own addition (C-09).
    * The data router replaces `BrowserRouter` (lines 549-551). Not listed.
    * Pages are not lazy-loaded (lines 554-555). Not listed.
    * The query retry policy differs from the template's `retry: 1` (lines 468-470). Not listed.
* **Rationale & Recommendation**: Say that some departures come from the PRD and some from the author, or add the unlisted departures to the Conflicts table or the deviations.

### ST-3 "Does not copy the shapes" while the document restates contract rules - Severity: Major
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 43-44; restated content at 428-437, 500-509, 535-545, 730-748.
* **Discrepancy Description**: The document restates these from the contract:
    * the envelope parts
    * every error code
    * the partial-state operations
    * every field length and regular expression

  These copies can drift from the admin-service design, which is still Proposed.
* **Rationale & Recommendation**: Name what is restated, and say that the admin-service design wins if the two disagree.

### ST-4 The Goals intro claims a check that several goals do not have - Severity: Major
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 81-82.
* **Discrepancy Description**: The intro says each goal is checked by the PRD §10 exit checks and the §11 definition of done. That does not hold for:
    * Goal 6: type-level drift.
    * Goal 7: no bypass, beyond what F-01 covers.
    * Goal 8: Node 22.22 and the `verify` stage.
    * Goal 9: Playwright.
* **Rationale & Recommendation**: Give each goal its own check, as the admin-service design does.

### ST-5 Goal 2 "from the console alone" does not hold on partial-failure paths - Severity: Major
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 87-90; related 537-540.
* **Discrepancy Description**: The document's own `SyncFailedNotice` text sends several cases to an operator who reconciles from the audit log, and PRD §12 says there is no UI for that. So S2 and S4 after a 502 need work outside the console.
* **Rationale & Recommendation**: Narrow the goal to the success paths plus S6's Re-sync recovery, and name the operator-reconciliation cases as outside the console.

### ST-6 Goal 9 and C-09 overclaim E2E coverage and repeat the same reasoning four times - Severity: Major
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 115-122, 169, 917-920.
* **Discrepancy Description**: Three claims say more than the document supports:
    * "This is how the PRD's E2E milestone (§10) is shown". The milestone covers S1 to S6, and S1 is excluded (line 147).
    * "Regression coverage". `npm run e2e` runs in no CI, Docker build or Jenkins job (lines 148-149), so the coverage depends on someone running it by hand.
    * "Needs a browser driver" (line 120). Alternative E shows the milestone can be met without one.

  The reasoning "a manual run gives no regression coverage" appears at lines 116-117, 121-122, 169 and 919.
* **Rationale & Recommendation**: Say the tests show S2 to S6 and can be repeated on demand. Keep the reasoning once, in C-09, and refer to it elsewhere.

### ST-7 "ESLint rules enforce the layers" claims more than the four listed rules do - Severity: Major
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 369-376; related 335.
* **Discrepancy Description**: The four rules do not stop these breaches:
    * `hooks/` or `api/` importing `pages/` or `components/`, an upward import that line 335 forbids.
    * Pages calling the global `fetch` (line 372). `no-restricted-imports` cannot catch a global.

  So "a breach fails `npm run lint`" is not supported for those cases.
* **Rationale & Recommendation**: Write "These four import rules fail `npm run lint`." Either add rules for the rest, for example `no-restricted-globals` for `fetch` and restrictions on upward imports, or state that those cases are left to code review.

### ST-8 "Mutations are never retried" conflicts with the 401 repeat - Severity: Major
* **Reviewer Lens:** `style`
* **Affected Scope:** Line 470; related 453-457.
* **Discrepancy Description**: `client.ts` repeats any request once after a 401 that a refresh resolves, writes included. "Never retried" is absolute and contradicts that. D-4 raises a separate gap in the same rule: the UI's Retry on writes whose outcome is unknown.
* **Rationale & Recommendation**: Write "TanStack Query never retries mutations. `client.ts` repeats a request once only after a 401 (see 401 handling)."

### ST-9 Two different rules for when a 502 triggers a refetch - Severity: Major
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 475, 482, 695-698.
* **Discrepancy Description**: The table header at line 475 limits refetching on a 502 to responses whose `completedSteps` is not empty, with the sync row (482) as the one exception. Line 695 says that after any save or Re-sync, success or 502, the console refetches the role queries. That includes a permission-save 502 with no completed steps.
* **Rationale & Recommendation**: Pick one rule and change either the table header or line 695 to match it.

### ST-10 `SyncFailedNotice` text repeats itself and can read as a contradiction - Severity: Major
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 530-532, 535, 537, 541, 543-544.
* **Discrepancy Description**:
    * The step list falls back to "Nothing was changed." when the list is empty. Row texts already say this ("Nothing was created", "Nothing changed", "Nothing was saved"), so the admin sees the same sentence twice.
    * A `ROLE_SYNC` 502 always has `completedSteps: []` (admin-service line 812). The screen would show "The permissions were already saved" above "Nothing was changed." Both are true, but together they read as a contradiction.
    * "Try Re-sync again" doubles the "re".
    * The column header "first sentence fixed; rest from completed steps" does not fit the rows that have no conditional part.
* **Rationale & Recommendation**:
    * Show the step list only when it is not empty, or drop the fallback.
    * Change `ROLE_SYNC` to "The sync failed. The saved permissions are unchanged. Use Re-sync."
    * Make the column header match the rows.

  Fix this together with D-10.

### ST-11 `USER_PROFILE_UPDATE` text is ambiguous - Severity: Major
* **Reviewer Lens:** `style`
* **Affected Scope:** Line 538.
* **Discrepancy Description**: "Keycloak has the new values, the console shows the saved ones." is a comma splice. "the saved ones" can also be read as the new values, but admin-service §5.2 keeps the old values in the local row.
* **Rationale & Recommendation**: Write "Keycloak has the new values. This page shows the old values, which are still stored locally."

### ST-12 Saved Playwright browser state is not covered by the credential claims - Severity: Major
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 795-798, 967-971.
* **Discrepancy Description**: The setup project saves the browser state, which holds a live Keycloak session for the test administrator. Line 798 says credentials are never written to a file in `cce-admin-ui`, and the NFR says no credential is committed. Yet the design names neither the state file's path nor whether it is git-ignored.
* **Rationale & Recommendation**: Name the storage-state path. State that it is git-ignored and is overwritten or deleted on each run.

### ST-13 "The one person who can fix it" conflicts with operator reconciliation - Severity: Major
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 64-65; related 537-540.
* **Discrepancy Description**: The Problem section says a generic error hides the partial state from the one person who can fix it. The document's own notice text sends four user operations to an operator, not to the admin.
* **Rationale & Recommendation**: Write "... hides that partial state from the admin, who must either act (Re-sync) or pass it to an operator."

### ST-14 "Only file that depends on `keycloak-js`" leaves out the production module - Severity: Major
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 415-416, 880-882; related 374.
* **Discrepancy Description**: `src/auth/keycloak.ts` also depends on the `keycloak-js` API. Line 374 allows it to import the package.
* **Rationale & Recommendation**: Write "the only *test* file that depends on the `keycloak-js` API".

---

### D-13 The implicit-rows note is wrong for system roles - Severity: Nit
* **Reviewer Lens:** `design`
* **Affected Scope:** Lines 666-668.
* **Discrepancy Description**: The note "Every role keeps these two rules" does not hold for `SUPER_ADMIN` and `CCE_ADMIN_SERVICE`, which have no implicit rows (AD-16; admin-service line 656).
* **Rationale & Recommendation**: Show the note only for non-system roles, or reword it to "Every non-system role".

### D-14 Wrong citation for inactive menus - Severity: Nit
* **Reviewer Lens:** `design`
* **Affected Scope:** Lines 649-650.
* **Discrepancy Description**: The sentence cites "admin-service AD-03, §4.5". AD-03 is about the seed routes (admin-service lines 929, 1195).
* **Rationale & Recommendation**: Cite AS-REQ-15 and §4.5 instead.

### D-15 A 200 that changed nothing still says "Saved and synced" - Severity: Nit
* **Reviewer Lens:** `design`
* **Affected Scope:** Lines 698-699.
* **Discrepancy Description**: A save that changes nothing returns 200 without running a sync (admin-service §5.7). On a `FAILED` role, the console would show "Saved and synced at ..." next to a `FAILED` badge.
* **Rationale & Recommendation**: Build the message from the `syncStatus` in the response, not from the 200.

### D-16 `useAllRoles` has no scale limit and can return an inconsistent list - Severity: Nit
* **Reviewer Lens:** `design`
* **Affected Scope:** Lines 477, 488-492, 601-602.
* **Discrepancy Description**:
    * It fetches every page in a row, with no upper bound.
    * A role created or deleted between page fetches can make the joined list miss or repeat a role.
    * Every user mutation invalidates `['roles']`, so every page is fetched again.
    * A checkbox list of hundreds of roles is unusable.
* **Rationale & Recommendation**: State the expected maximum number of roles and what happens beyond it. Record this under D-1.

### D-17 Admins can lock themselves out without warning - Severity: Nit
* **Reviewer Lens:** `design`
* **Affected Scope:** Lines 594-596, 617-620.
* **Discrepancy Description**: An admin can deactivate or lock their own account, or remove `SUPER_ADMIN` from it, as long as another active admin remains. The next call then fails with no explanation.
* **Rationale & Recommendation**: Compare the user's `id` with `GET /me`'s `id`, and add a warning about the admin's own account to the confirmation dialog.

### D-18 The deployment topology is not stated - Severity: Nit
* **Reviewer Lens:** `design`
* **Affected Scope:** Lines 552, 846-848.
* **Discrepancy Description**: The console has no router base path, and the default Keycloak URL is `${origin}/auth`, which Caddy does not route. Together these require a dedicated origin whose ingress also sends `/auth` to Keycloak. The template is served under a path prefix (`/insights`).
* **Rationale & Recommendation**: State the dedicated-origin requirement, or record serving under a base path as an alternative with the reason it was rejected.

### D-19 "Caddy refuses to start if the variable is empty" has no stated mechanism - Severity: Nit
* **Reviewer Lens:** `design`
* **Affected Scope:** Line 846.
* **Discrepancy Description**: Caddy can accept an empty `{$GATEWAY_UPSTREAM}` in `reverse_proxy` and fail only when a request arrives.
* **Rationale & Recommendation**: Name the check, for example an entrypoint `test -n` or a configuration validation step.

### D-20 Small gaps in cache invalidation, the unsaved-changes guard, and F-04 coverage - Severity: Nit
* **Reviewer Lens:** `design`
* **Affected Scope:** Lines 479, 568-571, 819.
* **Discrepancy Description**:
    * `PUT /roles/:id` does not invalidate `['users']`, but the Users list shows role display names.
    * `useBlocker` does not cover a reload, a `login()` redirect, or the User edit sections.
    * F-04 is "not shown" on the real stack, although a local 403 is easy to produce (admin-service line 1155).
* **Rationale & Recommendation**: Add `['users']` to the invalidations for `PUT /roles/:id`. State what the unsaved-changes guard covers. Consider a real-stack F-04 check in `npm run e2e`.

### S-4 The Screens section has no sub-headings - Severity: Nit
* **Reviewer Lens:** `structural`
* **Affected Scope:** Lines 573-721. Bold labels at 575, 600, 613, 624, 640, 647, 660, 701.
* **Discrepancy Description**: About 150 lines describing eight screens have no sub-headings, so they cannot be navigated or linked. The Menus and Permissions tabs (647, 660) look like siblings of Role detail (640) instead of its children, and the sync state diagram (683-699) has no structural tie to the Permissions tab.
* **Rationale & Recommendation**: Give each screen an H4, for example `#### Users list (F-10)`, and nest the two Role detail tabs under it.

### S-5 The Alternatives headings are inconsistent - Severity: Nit
* **Reviewer Lens:** `structural`
* **Affected Scope:** Lines 852-956; headings at 858, 871, 884, 897, 910, 922, 937, 952.
* **Discrepancy Description**:
    * A to D carry a "(gate TP-n)" suffix, but E to G do not.
    * "H: Options the PRD rules out" breaks the "Alternative X:" pattern and has no Pros, Cons or Reason fields.
    * G's "Reason for Rejection: Moot" (947) breaks the meaning of that field.
* **Rationale & Recommendation**:
    * Either tag every alternative's origin the same way or tag none.
    * Retitle H as `### Options ruled out by the PRD (not weighed)` and drop the letter.
    * Mark G "Status: Superseded".

### S-6 CUJ numbering does not match the admin-service design - Severity: Nit
* **Reviewer Lens:** `structural`
* **Affected Scope:** Lines 280 (CUJ 4 = S6), 291 (CUJ 5 = S4), 300 (CUJ 6 = S5).
* **Discrepancy Description**: In the admin-service design, CUJ n matches scenario Sn (CUJ 4 = S4 at line 321, CUJ 5 = S5 at line 328, CUJ 6 = S6 at line 336). Here CUJ 4 to 6 are in a different order, so matching E2E coverage across the two designs by CUJ number pairs the wrong scenarios.
* **Rationale & Recommendation**: Renumber so CUJ n matches Sn, and update the references at lines 110, 169, 761, 790, 801, 824-827 and 920.

### S-7 The sync state diagram is ambiguous - Severity: Nit
* **Reviewer Lens:** `structural`
* **Also raised by:** `style` (Nit 18)
* **Affected Scope:** Lines 685-693.
* **Discrepancy Description**: FAILED to SYNCED ("Re-sync 200") and SYNCED to FAILED ("any of those 502s") share one line, and its only arrowhead points into FAILED. A successful `PUT permissions 200` or role create from FAILED is not drawn at all.
* **Rationale & Recommendation**: Draw FAILED to SYNCED as its own edge, labelled "PUT permissions 200 / Re-sync 200", and keep the 502 edge separate.

### S-8 Heading case and horizontal rules are inconsistent - Severity: Nit
* **Reviewer Lens:** `structural`
* **Affected Scope:** Line 154 compared with 171, 181, 241, 852, 965; the rule at 963.
* **Discrepancy Description**: "Conflicts between inputs" is the only H2 in sentence case. A horizontal rule separates Alternatives from the NFRs (963), but no other pair of H2s.
* **Rationale & Recommendation**: Use one case convention for H2s. If the Conflicts heading changes, update the anchor at line 32. Remove the rule at 963.

### S-9 Minor Markdown formatting - Severity: Nit
* **Reviewer Lens:** `structural`
* **Affected Scope:** Lines 233-234, 369-370, 614-615, 703-704, 808-809; 839.
* **Discrepancy Description**: At each of the listed places, a list starts directly after its paragraph with no blank line, which some renderers (for example Python-Markdown) merge into the paragraph. Line 839 runs past the document's wrap width.
* **Rationale & Recommendation**: Put a blank line before each of these lists and re-wrap line 839.

### ST-15 Goal 7 "renders a page" is contradicted by the start-up error screen - Severity: Nit
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 105-106; related 338, 384, 394, 397.
* **Discrepancy Description**: The start-up error screen renders without a session.
* **Rationale & Recommendation**: Say "route" instead of "page", as line 397 does.

### ST-16 "The bundle stays small" has no measure - Severity: Nit
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 554-555.
* **Discrepancy Description**: No size or limit is given.
* **Rationale & Recommendation**: State a budget or drop the clause.

### ST-17 The gateway behaviour on invalid tokens is cited to the wrong source - Severity: Nit
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 454-455.
* **Discrepancy Description**: The claim that the gateway rejects an invalid token before doing anything cites admin-service §3, which describes only the service.
* **Rationale & Recommendation**: Cite the gateway doc's runtime flow, or mark the gateway behaviour as assumed.

### ST-18 Permissions-tab text repeats itself - Severity: Nit
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 670-672.
* **Discrepancy Description**: The five methods and CQ-1 are stated twice in the same bullet.
* **Rationale & Recommendation**: Keep one mention.

### ST-19 Sentences that add nothing - Severity: Nit
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 141-142, 156-157, 232-233.
* **Discrepancy Description**: Line 142 repeats line 141. "None was resolved silently." adds nothing. Line 233 repeats its heading.
* **Rationale & Recommendation**: Remove them.

### ST-20 A Non-Goals heading does not parse - Severity: Nit
* **Reviewer Lens:** `style`
* **Affected Scope:** Line 147.
* **Discrepancy Description**: "Browser tests for S1, or run in CI." is not a grammatical phrase.
* **Rationale & Recommendation**: Write "Browser tests for S1, and running browser tests in CI."

### ST-21 The display format for `lastSyncAt` is not defined - Severity: Nit
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 592, 663, 687, 699.
* **Discrepancy Description**: The document uses `yyyy-MM-dd HH:mm` in one place, "Synced at HH:mm" in another, and a raw `{lastSyncAt}` in two more.
* **Rationale & Recommendation**: State one format for every timestamp.

### ST-22 "This machine" - Severity: Nit
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 108, 163, 834.
* **Discrepancy Description**: The phrase only makes sense to someone at the author's workstation.
* **Rationale & Recommendation**: Name the environment, for example "the author's workstation (Node 22.22, npm 10.9)".

### ST-23 Subjective wording in UI-DEV-1 - Severity: Nit
* **Reviewer Lens:** `style`
* **Affected Scope:** Line 179.
* **Discrepancy Description**: "empty or ceremonial" and "where their names mislead" are opinions rather than facts.
* **Rationale & Recommendation**: State the concrete fact, for example "no module would live there".

### ST-24 Two messages give no exact text - Severity: Nit
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 509, 524-525.
* **Discrepancy Description**: These two places describe or illustrate the message instead of giving it, unlike every other message in the document.
* **Rationale & Recommendation**: Quote the exact strings.

### ST-25 The Caddy headers are not specified - Severity: Nit
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 841-842.
* **Discrepancy Description**: "long cache headers" and "the same three security headers" give no values.
* **Rationale & Recommendation**: Give the values, for example `max-age=31536000, immutable`, and name the three headers. See also D-6.

### ST-26 A vague duration and drafting-exercise context in Alternatives C and D - Severity: Nit
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 893-895, 907.
* **Discrepancy Description**: "the period without a service is short" gives no duration, and "in this trial" refers to the drafting exercise rather than the design.
* **Rationale & Recommendation**: Give a duration or the condition that ends the period, and drop "in this trial".

### ST-27 Vague wording in Alternatives B and F - Severity: Nit
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 876, 927.
* **Discrepancy Description**: "The most real code" is vague, and "actually" is a filler word. Generated types match the spec, not necessarily what the running service returns.
* **Rationale & Recommendation**: Reword both pros precisely.

### ST-28 "And the reverse" is ambiguous - Severity: Nit
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 651-652.
* **Discrepancy Description**: It is unclear what "the reverse" refers to.
* **Rationale & Recommendation**: Write "Checking a child does not check its parent."

### ST-29 The Problem section mixes present and future - Severity: Nit
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 50-52.
* **Discrepancy Description**: "Giving a person access means changing three systems" includes a system that does not exist yet.
* **Rationale & Recommendation**: Separate today's two systems from the planned third.

### ST-30 Sentence fragment - Severity: Nit
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 644-645.
* **Discrepancy Description**: "Then the two tabs." is not a sentence.
* **Rationale & Recommendation**: Make it a full sentence, or replace it with sub-headings (S-4).

### ST-31 The browser-tests paragraph is too dense - Severity: Nit
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 789-804.
* **Discrepancy Description**: One 16-line paragraph mixes server start-up, credentials, test data and the stand-in switch.
* **Rationale & Recommendation**: Split it into a bulleted list.
