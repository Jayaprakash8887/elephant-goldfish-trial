Status: resolved

# Goldfish Annotated Review Report

**Resolution (2026-11-10):** the author chose "auto accept". All 30 PROPOSE FIX items and the
alignments K-1 to K-6 were applied to `docs/designs/cce-admin-ui.md` in one pass. The author
accepted the two choices made in this report: a limit of 500 roles with dedupe and a notice
(A-01), and `DUPLICATE_ROLE_NAME` for the 409 when a Keycloak realm role with that name exists
(K-2). Two further contract details from the admin-service resolve were applied at the same time:
response fields are always present and typed `T | null` (only `pagination`, `warnings`,
`details` and `partialState` are left out when absent); on a `USER_CREATE` 502,
`partialState.entityId` is always the future user id, and the console still does not link to it.
Following K-1, the `USER_CREATE` notice now tells the admin to submit again with the same
username and email, so the service links the account. The form is no longer locked after a 502
with completed steps. Contested: 0. Escalated: 0. Declined: 0. Unaddressed: 0. Exact names and
codes are to be re-checked against the resolved admin-service design.

**Target:** `docs/designs/cce-admin-ui.md` (`full file`, 1833 lines at the time of the review)

**Findings:** the 30 consolidated actions A-01 to A-30 in
[consolidated_review_report.md](consolidated_review_report.md). Line numbers refer to the target
as it was when the review ran; the file has not changed since.

**Author decisions applied in this report** (given with the resolve request):
- **A-12:** confirmed as an accepted risk; the ETag follow-up goes to the admin-service design;
  the pre-save diff dialog is dropped.
- **A-30:**
  - keep the unsaved-changes guard, not widened;
  - icon select = ten seed icons plus the entry's current value;
  - drop runtime `/config.json`;
  - drop the self-lockout warning;
  - state the deployment origin requirement and record the base-path alternative as rejected;
  - no wider guard coverage and no real-stack F-04 check.
- **Every other action:** taken as the report proposes, following its Stage 8 triage.

**Summary of categories:** 30 PROPOSE FIX, 0 CONTEST, 0 ESCALATE, 0 DECLINE. In addition, six
author-directed contract alignments (K-1 to K-6) are listed at the end; they come from decisions
just taken on the admin-service design, not from a reviewer.

## Prioritized Findings & AI Suggestions

### [Issue 1] A-01 [`egm-design`, `egm-zoom-out`] - Severity: Blocker
*   **Affected Scope:** [Lines 965-975](../../docs/designs/cce-admin-ui.md#L965-L975) (Non-Functional Requirements)
*   **Discrepancy Description:** No "Risks accepted by the author" table and no Performance NFR.
*   **Rationale:** The feature-spec standard requires a risks section. The accepted risks are scattered through the text or missing.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid. The risks exist and are known; they only need to be filed in one place, as the admin-service design does (its "Risks accepted by the author").
*   ***Proposed Edit:***
    *   Location: `## Non-Functional Requirements`, new `### Risks accepted by the author` table (Risk | Effect | Mitigation or why accepted), and a Performance bullet.
    *   Change: rows for:
        *   GW-2: `SYNCED` means "the sync call succeeded", not "the gateway enforces the rows".
        *   Last write wins on full-set saves; ETag (`If-Match`/412) follow-up recorded on the admin-service design (A-12). The author accepts it: the race needs two administrators editing the same role within seconds, and the audit log records both saves.
        *   `contract.ts` is a hand copy; drift from the service is caught only by `npm run e2e` and the service's own tests (A-09).
        *   Some error paths are proved only against MSW (gateway 403, Keycloak failure mid-create, network loss).
        *   Role count: `useAllRoles` walks every page; the console assumes at most 500 roles (five pages), dedupes by `id`, and shows a notice if the limit is reached.
        *   Gateway error bodies are not in the service's format until the gateway owners answer (A-06); classified by HTTP status.
        *   Proxy timeouts (Caddy, ingress, gateway) are not tuned by the console; a slow Keycloak call can surface as a non-envelope 502/504, handled by the A-06 rule.
        *   Audit IP: the service trusts `X-Forwarded-For`; behind Caddy the recorded IP depends on the proxy chain (A-13, raised on the admin-service design).

        Performance bullet: "Lists are server-paged (20 per page by default); the only unpaged reads are the menu tree (12 seed rows) and `useAllRoles` (bounded as above). No polling."

### [Issue 2] A-02 [`egm-comprehension`, `egm-zoom-out`, `egm-style`] - Severity: Major
*   **Affected Scope:** [Lines 9-122](../../docs/designs/cce-admin-ui.md#L9-L122) (Overview, The Problem, Goals); C-05 at line 163
*   **Discrepancy Description:** The gateway sync, the stand-in gateway and operator reconciliation are used but never explained; Goal 2 claims more than the console delivers on failure paths.
*   **Rationale:** A reader cannot tell who calls the sync, what the stand-in is, or who the "operator" is; C-05 says "Locally there is no gateway" while the stand-in exists.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Comprehension gap; a human reader needs this context.
*   ***Proposed Edit:***
    *   Location: Overview, new paragraph "Three mechanisms outside the console"; Goal 2; The Problem bullet "Writes can half-succeed"; C-05.
    *   Change:
        *   Two or three sentences each, linking admin-service §1, §5.10, AD-11 and §8 instead of restating them:
            *   **Gateway sync:** the service calls the gateway after a permission change, and the gateway creates the realm roles.
            *   **Stand-in gateway:** a local fake sync target that the service calls. It is not in the console's request path, and it has `ok` and `fail` modes.
            *   **Operator reconciliation:** a person with database and Keycloak access reads `audit_log`. The console has no tool for it.
        *   Goal 2: "...on the success paths, plus recovery from a failed gateway sync with Re-sync (S6)".
        *   The Problem: "...hides that partial state from the admin, who must either act (Re-sync) or pass it to an operator".
        *   C-05: "Locally there is no gateway in the request path; the stand-in only receives the service's sync call".
        *   Dropped remedy: no confirmed re-send for user partial failures.

### [Issue 3] A-03 [`egm-zoom-out`, `egm-comprehension`, `egm-design`] - Severity: Major
*   **Affected Scope:** [Lines 280-289](../../docs/designs/cce-admin-ui.md#L280-L289) (CUJ 4), line 604 (User create), item 101
*   **Discrepancy Description:** The role-picker hint "assigning it will fail until it is synced" is wrong for `FAILED` roles whose realm role exists and for the seeded `SUPER_ADMIN`; CUJ 4's precondition does not guarantee a missing realm role.
*   **Rationale:** `syncStatus` is a display hint; the service refuses only when Keycloak has no realm role (admin-service AD-10).
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid; the text contradicts the contract the console mirrors.
*   ***Proposed Edit:***
    *   Location: User create screen text, `RoleCheckboxList` (item 67), CUJ 4 Given, e2e item 101.
    *   Change:
        *   Keep the badge and delete the hint sentence.
        *   CUJ 4 Given: "A role was created while the gateway sync was failing, so its realm role does not exist in Keycloak."
        *   e2e: switch the stand-in to `fail` before creating the role in the console. Under K-2 that create returns 201 with `ROLE_SYNC_FAILED`.

### [Issue 4] A-04 [`egm-zoom-out`, `egm-design`] - Severity: Major
*   **Affected Scope:** [Line 537](../../docs/designs/cce-admin-ui.md#L537) (`SyncFailedNotice` table, `USER_CREATE`)
*   **Discrepancy Description:** The "link to the user if `entityId` is set" can never be shown; the conflict for an account that exists only in Keycloak needs a clear message.
*   **Rationale:** A `POST /users` 502 never leaves a local user (flow 5.1; confirmed by the author, K-6).
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid dead path.
*   ***Proposed Edit:***
    *   Location: the `USER_CREATE` row; Open Questions / items for the admin-service design.
    *   Change:
        *   Delete the conditional user link; keep the Users list link.
        *   Record the request that a username or email clash with a Keycloak account that has no local user returns a 409 message saying so. Under K-1, a full match on both is now linked instead of refused.
        *   Dropped remedies: new error codes and new `details` entries.

### [Issue 5] A-05 [`egm-design`, `egm-style`, `egm-zoom-out`] - Severity: Major
*   **Affected Scope:** [Lines 522-546](../../docs/designs/cce-admin-ui.md#L522-L546) (`SyncFailedNotice`); test lines 824, 1729
*   **Discrepancy Description:** Per-row "No steps" texts claim "Nothing was created/saved" although a timeout may have changed state; some tests check states the contract cannot produce; the `USER_PROFILE_UPDATE` text is wrong.
*   **Rationale:** Empty `completedSteps` means the service recorded no change, not that nothing happened.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid; one rule replaces several texts and stays true after a timeout.
*   ***Proposed Edit:***
    *   Location: `SyncFailedNotice` paragraph and table; item 59; test table rows.
    *   Change:
        *   **Rule for an empty `completedSteps`** (every operation except `ROLE_SYNC`): show "The service recorded no change. You can try again.", and keep the form input and the button.
        *   **Non-empty `completedSteps`:** show the row's fixed text.
        *   **`ROLE_SYNC`:** "The sync failed. The saved permissions are unchanged. Use Re-sync."
        *   **`USER_PROFILE_UPDATE`:** "Keycloak has the new values. This page shows the old values, which are still stored locally."
        *   **Delete:** the "Nothing was changed." fallback, the "No steps:" sub-texts, and the test cases for impossible states.
        *   **Remove rows:** `ROLE_CREATE` (now a 201 warning, K-2) and `ROLE_DELETE` (K-3).

### [Issue 6] A-06 [`egm-design`, `egm-style`] - Severity: Major
*   **Affected Scope:** [Lines 462-546](../../docs/designs/cce-admin-ui.md#L462-L546) (Server state, Error presentation); line 470
*   **Discrepancy Description:** Errors that are not in the service's envelope (gateway, proxy, timeouts) have no rule; a mutation that fails with an unknown result is not handled; line 470 is ambiguous about retries.
*   **Rationale:** Gateway and proxy errors bypass the service's format.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid gap.
*   ***Proposed Edit:***
    *   Location: `describeError` rules, kinds table, QueryClient bullet, Open Questions.
    *   Change:
        *   A response without an error envelope is classified by its HTTP status: 401 takes the existing refresh path, 403 becomes `forbidden`, and anything else becomes `unexpected`.
        *   Retry applies to queries only. A mutation that fails with `network` or `unexpected` shows "The result is unknown; the page was refreshed." and refetches.
        *   Line 470: "TanStack Query never retries mutations; `client.ts` repeats a request once only after a 401."
        *   New open question for the gateway owners: the shape of the gateway's 401 and 403 bodies.
        *   Timeout tuning becomes an A-01 risk row.

### [Issue 7] A-07 [`egm-design`] - Severity: Major
*   **Affected Scope:** [Lines 392-393, 453-457](../../docs/designs/cce-admin-ui.md#L392-L457) (Start-up, 401 handling)
*   **Discrepancy Description:** A 401 after a successful refresh calls `login()`, which can loop forever when the service keeps rejecting a valid Keycloak session (wrong audience or issuer); a failed interval refresh also forces a redirect.
*   **Rationale:** Redirect loop with no visible error.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid.
*   ***Proposed Edit:***
    *   Location: 401 handling bullet; Start-up step 2; item 24, item 28; `keycloak.test.ts` and `client.test.ts` cases.
    *   Change: after a refresh that succeeded, a second 401 throws the 401 `ApiError`, shown inline ("The admin service did not accept your sign-in."); no `login()`. The interval ignores a failed `updateToken(70)` and tries on the next tick. `refreshToken()` still calls `login()` when the refresh itself fails. Counter remedy dropped.

### [Issue 8] A-08 [`egm-zoom-out`, `egm-style`] - Severity: Major
*   **Affected Scope:** [Lines 475-486](../../docs/designs/cce-admin-ui.md#L475-L486) (invalidation table and scope paragraph)
*   **Discrepancy Description:** The invalidation table conflicts with the 502 refetch rules and both over- and under-invalidates.
*   **Rationale:** One rule is simpler and correct for a console this size.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid; also needed by K-4 (no fan-out marking).
*   ***Proposed Edit:***
    *   Location: Server state section; hooks items 38-42; Permissions tab "refetches the role queries".
    *   Change: "After any mutation settles with a 2xx or a 502, the hook calls `queryClient.invalidateQueries()`." Delete the table and the `GATEWAY_SYNC_SCOPE` paragraph.

### [Issue 9] A-09 [`egm-zoom-out`, `egm-design`, `egm-style`] - Severity: Major
*   **Affected Scope:** [Lines 101-104](../../docs/designs/cce-admin-ui.md#L101-L104) (Goal 6); [lines 730-748](../../docs/designs/cce-admin-ui.md#L730-L748) (validation table)
*   **Discrepancy Description:** Goal 6 claims the build catches drift from the service; it only catches drift from `contract.ts`. The validation table restates the contract.
*   **Rationale:** Overclaim; a second copy of the rules can disagree.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid.
*   ***Proposed Edit:***
    *   Location: Goal 6; Forms and validation; item 51.
    *   Change:
        *   Goal 6: "Request and response shapes in the client and the MSW handlers cannot drift from `src/api/contract.ts` without a type error."
        *   Replace the table with a link to admin-service §4.4 and §4.5, plus the console-only rules: the duplicate row and implicit-equal row checks, and the page-size choices.
        *   Add: "Where this design restates the contract, the admin-service design wins if the two disagree."

### [Issue 10] A-10 [`egm-zoom-out`, `egm-codebase-details`] - Severity: Major
*   **Affected Scope:** [Lines 776-787](../../docs/designs/cce-admin-ui.md#L776-L787) (MSW); [lines 1692-1704](../../docs/designs/cce-admin-ui.md#L1692-L1704) (item 94)
*   **Discrepancy Description:** The MSW store re-implements service rules (filters, 409s, `LAST_SYSTEM_ADMIN`, a `syncStatus` stand-in for `ROLE_NOT_SYNCED`), a second implementation that can be wrong; the `SUPER_ADMIN` seed status mismatches.
*   **Rationale:** Tests should check the console's behaviour against given responses, not a fake service.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid.
*   ***Proposed Edit:***
    *   Location: MSW paragraph; item 94.
    *   Change:
        *   Use typed canned fixtures and default handlers that return them and record each request (method, URL, query, body) in a list that tests read.
        *   Error cases are per-test `server.use(...)` overrides built with `errorResponse(...)`.
        *   `fixtures.ts`, `handlers.ts`, `server.ts` and a `requests.ts` recorder replace `db.ts`.

### [Issue 11] A-11 [`egm-design`, `egm-implementation-readiness`] - Severity: Major
*   **Affected Scope:** [Lines 641-642](../../docs/designs/cce-admin-ui.md#L641-L642) (Role detail); item 86
*   **Discrepancy Description:** Delete on Role detail duplicates the list action and leaves the post-delete navigation undefined.
*   **Rationale:** F-12 places delete on the Roles list.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid.
*   ***Proposed Edit:***
    *   Location: Role detail screen text; item 86.
    *   Change: remove Delete and `useDeleteRole` from Role detail; add `NotFoundPanel` on `ROLE_NOT_FOUND`.

### [Issue 12] A-12 [`egm-zoom-out`, `egm-design`] - Severity: Major
*   **Affected Scope:** [Line 142](../../docs/designs/cce-admin-ui.md#L142) (Non-Goals); [lines 709-711](../../docs/designs/cce-admin-ui.md#L709-L711) (menu toggle)
*   **Discrepancy Description:** Last write wins on full-set saves of a hard control, with no recorded acceptance; the menu toggle resends a cached record.
*   **Rationale:** PRD §4 calls permissions a hard control.
*   ***AI Suggestion:*** PROPOSE FIX (author decision applied)
*   ***Justification:*** The author confirmed last write wins as an accepted risk, with the reason given above.
*   ***Proposed Edit:***
    *   Location: Non-Goals line 142; A-01 risks table; Menus catalog; items for the admin-service design.
    *   Change:
        *   Remove the repeated Non-Goal sentence and keep the A-01 risk row.
        *   Record the follow-up "`If-Match` with 412 on the full-set replace endpoints" on the admin-service design.
        *   The menu toggle refetches the menu tree (`staleTime: 0`) before it builds the `PUT` body.
        *   Record the dropped pre-save diff dialog in Author Decisions (A-15).

### [Issue 13] A-13 [`egm-design`, `egm-style`] - Severity: Major
*   **Affected Scope:** [Lines 831-850](../../docs/designs/cce-admin-ui.md#L831-L850) (Build and deployment); item 17
*   **Discrepancy Description:** No Content-Security-Policy; security headers not spelled out; audit IP depends on `X-Forwarded-For` trust in the service.
*   **Rationale:** Defence in depth for an admin console.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid. The policy fits the build: Vite emits no inline scripts, React sets styles through the DOM, and `keycloak-js` with `checkLoginIframe: false` uses no frames.
*   ***Proposed Edit:***
    *   Location: Caddy bullet; item 17; A-01 risks; items for the admin-service design.
    *   Change:
        *   Caddy adds `Content-Security-Policy "default-src 'self'; script-src 'self'; connect-src 'self' {$KEYCLOAK_ORIGIN}; frame-ancestors 'none'; object-src 'none'; base-uri 'none'"`.
        *   `KEYCLOAK_ORIGIN` is optional with an empty default; the default `${origin}/auth` is already `'self'`.
        *   Name the three existing headers and the `/assets/*` cache value.
        *   Audit IP: send the request to the admin-service design and add the A-01 risk row.
        *   Dropped: a start-up check for `KEYCLOAK_ORIGIN`.

### [Issue 14] A-14 [`egm-style`] - Severity: Major
*   **Affected Scope:** [Lines 31-32, 37, 81-82, 115-122, 167, 369-376, 415-416, 796, 917-920](../../docs/designs/cce-admin-ui.md#L31-L920)
*   **Discrepancy Description:** Claims that say more than the design delivers: "approved", "every departure comes from the PRD", goals all checked by milestone exit checks, Goal 9 coverage repeated, ESLint "enforces the layers", "the only file that depends on keycloak-js".
*   **Rationale:** Overclaims mislead the implementer and reviewers.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid. The admin-service design's status is Proposed. The data router, non-lazy pages and retry policy are author choices, not PRD.
*   ***Proposed Edit:***
    *   Location: as listed.
    *   Change:
        *   Status wording: "defined in the admin-service design (Status: Proposed)".
        *   List the author's own departures from the template in a new row under Deviations or Conflicts:
            *   the data router;
            *   pages that are not lazy-loaded;
            *   the retry policy;
            *   no `VITE_ROUTER_BASE`.
        *   Goals intro: Goals 6-9 get their own checks (type-check, lint/no-bypass grep, the build matrix, `npm run e2e`).
        *   Goal 9: "the tests show S2-S6 and run on demand", with the reasoning kept once in C-09.
        *   ESLint: "these four import rules fail `npm run lint`; upward imports and direct `fetch` are left to code review".
        *   "the only *test* file that depends on the `keycloak-js` API".
        *   Add a cross-reference from the browser-tests text to `e2e/.auth/` being git-ignored.

### [Issue 15] A-15 [`egm-structural`] - Severity: Major
*   **Affected Scope:** [Lines 181-239](../../docs/designs/cce-admin-ui.md#L181-L239) (Open Questions), Alternatives headings, drafting-process wording throughout
*   **Discrepancy Description:** Decisions are spread across bullets and Alternatives; Open Questions holds closed items; process wording ("this drafting session", "EGM Step 6", "this trial") leaks in; ID prefixes are never introduced.
*   **Rationale:** A reader cannot find a decision and its reason in one place.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid. C-07 keeps a factual reference to the missing team documents (PROVENANCE), reworded without "trial".
*   ***Proposed Edit:***
    *   Location: new `## Author Decisions in This Design` H2 (ID | Question | Decision | Why) after Deviations; Open Questions; Alternatives; Overview.
    *   Change:
        *   Rows UI-AD-01 onward:
            *   TP-1, TP-2;
            *   the hand-written contract module (from the earlier decision);
            *   Playwright and e2e;
            *   CQ-1 and CQ-2;
            *   the six drafting decisions with their reasons;
            *   A-12;
            *   the A-30 decisions;
            *   the folder layout (cross-reference UI-DEV-1).
        *   Each "Rejected by author" links its row.
        *   Open Questions keeps only GW-2 and the gateway error-body question.
        *   Delete the AS-REQ table and link admin-service §4.6.
        *   One sentence in the Overview introduces the prefixes B-, F-, S-, AD-, DEV-, GW-, D- and AS-REQ-.
        *   Remove "needs the same port" from Alternative D.
        *   Remove the drafting-process wording.

### [Issue 16] A-16 [`egm-implementation-readiness`] - Severity: Major
*   **Affected Scope:** [Lines 986-997](../../docs/designs/cce-admin-ui.md#L986-L997) (Order of work)
*   **Discrepancy Description:** Step checks cannot pass (step 1 has no entry file; routes import pages that do not exist yet); steps 3-6 do not list files.
*   **Rationale:** The implementer must invent the sequence.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid.
*   ***Proposed Edit:***
    *   Location: Order of work.
    *   Change:
        *   Step 1 adds a minimal `src/main.tsx` and `src/vite-env.d.ts`, and its check runs `cp .env.example .env` first.
        *   Step 2 lists exactly the F1 files: 19-29, 35-37, 43, 46-48, 51, 56-64, 80, 81, 90, 91-94, and their tests.
        *   Routes are added milestone by milestone: `routes.tsx` gains each page's route in the step that creates it.
        *   Steps 3-6 list their files and tests.
        *   Every step ends with `npm run lint && npm run build && npm run test:ci` and its rows of the exit-check table.

### [Issue 17] A-17 [`egm-implementation-readiness`, `egm-codebase-details`] - Severity: Major
*   **Affected Scope:** [Lines 1124-1139](../../docs/designs/cce-admin-ui.md#L1124-L1139) (item 9), [lines 369-376](../../docs/designs/cce-admin-ui.md#L369-L376)
*   **Discrepancy Description:** Overlapping `no-restricted-imports` blocks override each other under flat config; `playwright.config.ts` is banned from `@playwright/test`; components need runtime constants from `api/contract.ts`; presets are unnamed; type-import scope is wider than the design says.
*   **Rationale:** As written, lint either fails or silently does not enforce.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid. In flat config a later block's options for the same rule replace the earlier one's.
*   ***Proposed Edit:***
    *   Location: item 9; Layers section; item 51; item 26.
    *   Change:
        *   Five non-overlapping file groups, each with the full union of its restrictions: `src/components/**`, `src/pages/**`, `src/auth/**`, other non-test `src/**`, and tests plus `e2e/**`.
        *   `playwright.config.ts` is exempt from the `@playwright/test` ban.
        *   Runtime constants (`USER_STATUSES`, `PERMISSION_METHODS`) are re-exported from `src/schemas/rules.ts`.
        *   Presets: `reactHooks.configs['recommended-latest']`, and `parserOptions.projectService: true` with `tsconfigRootDir: import.meta.dirname`.
        *   Type imports are allowed from `src/api/contract.ts` only. Use a `paths` entry with `allowTypeImports` for `contract`, and a pattern ban for the other `api/` modules.
        *   Add a lint fixture, `src/test/lint-fixtures/`, with one file per rule that must fail, checked by a Vitest case that runs ESLint's Node API on it.

### [Issue 18] A-18 [`egm-implementation-readiness`] - Severity: Major
*   **Affected Scope:** [Line 846](../../docs/designs/cce-admin-ui.md#L846); items 16, 17; Verification Plan
*   **Discrepancy Description:** "Caddy refuses to start if the variable is empty" is not guaranteed.
*   **Rationale:** An empty placeholder may not fail the way the text claims.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid; an explicit guard is cheap and checkable.
*   ***Proposed Edit:***
    *   Location: item 16 `CMD`; Build and deployment; Verification Plan.
    *   Change: `CMD ["sh", "-c", "[ -n \"$GATEWAY_UPSTREAM\" ] || { echo 'GATEWAY_UPSTREAM is required' >&2; exit 1; }; exec caddy run --config /etc/caddy/Caddyfile"]`; verification: `docker run --rm cce-admin-ui:local` without the variable exits non-zero with that message.

### [Issue 19] A-19 [`egm-implementation-readiness`] - Severity: Major
*   **Affected Scope:** [Line 816](../../docs/designs/cce-admin-ui.md#L816) (exit check F1); item 19
*   **Discrepancy Description:** "Nothing renders before it resolves" is claimed as tested but nothing tests `main.tsx`.
*   **Rationale:** Unverified claim on the no-bypass path.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid.
*   ***Proposed Edit:***
    *   Location: items 19, new `src/bootstrap.tsx`; test table; exit-check F1 row.
    *   Change:
        *   `bootstrap(rootElement)` holds the start-up logic, and `main.tsx` only calls it.
        *   `src/__tests__/bootstrap.test.tsx` covers three cases: nothing renders while `initAuth` is pending; the start-up screen shows on a rejection; with the realm missing, the screen names `VITE_KEYCLOAK_REALM` and `initAuth` is never called.

### [Issue 20] A-20 [`egm-codebase-details`] - Severity: Major
*   **Affected Scope:** [Lines 1333-1365](../../docs/designs/cce-admin-ui.md#L1333-L1365) (items 29-34)
*   **Discrepancy Description:** Ten `api/` functions have no return type; results with pagination or warnings use mixed names.
*   **Rationale:** The implementer must choose.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid.
*   ***Proposed Edit:***
    *   Location: items 29-34.
    *   Change:
        *   Full signatures for every function.
        *   One result shape: `Page<T> = { items: T[]; pagination: Pagination }` for lists, and `WithWarnings<T> = { data: T; warnings: Warning[] }` for writes that can warn (`createUser`, `createRole`, `replaceRoleMenus`).
        *   Every other function returns the resource or `void`.

### [Issue 21] A-21 [`egm-codebase-details`] - Severity: Major
*   **Affected Scope:** [Lines 1706-1731](../../docs/designs/cce-admin-ui.md#L1706-L1731) (unit test table)
*   **Discrepancy Description:** Several endpoints and screens have no error-code test (PRD §11).
*   **Rationale:** Definition of done not met.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid. Adjusted for K-3: there is no 502 `ROLE_DELETE` to test.
*   ***Proposed Edit:***
    *   Location: test table.
    *   Change:
        *   Add `RoleDetailPage.test.tsx`: `PUT /roles/:id` `SYSTEM_ROLE_IMMUTABLE`, and `ROLE_NOT_FOUND` showing `NotFoundPanel`.
        *   Add error cases for:
            *   `DELETE /roles/:id` `ROLE_IN_USE`;
            *   `PUT /roles/:id/menus` 403;
            *   `PUT /menus/:id` `VALIDATION_ERROR` mapped to a field;
            *   502 `USER_PASSWORD_RESET`;
            *   `EMAIL_IN_USE` on profile update;
            *   `ROLE_NOT_ASSIGNABLE` on create.
        *   Reword the intro to "each screen's test file".

### [Issue 22] A-22 [`egm-codebase-details`, `egm-implementation-readiness`] - Severity: Major
*   **Affected Scope:** [Lines 1734-1803](../../docs/designs/cce-admin-ui.md#L1734-L1803) (browser tests); line 817
*   **Discrepancy Description:** Browser-test specifics are missing or fragile: CUJ 6's role, the header check, the stub request format, the password-grant fields, the email format, the `.env` path base; the Mailpit check repeats the backend.
*   **Rationale:** Implementer must decide; cross-repo path assumption.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid; the Mailpit check, accepted earlier, is removed because the backend's S2 already checks it, as the report proposes.
*   ***Proposed Edit:***
    *   Location: Browser tests paragraph; items 95-103; exit-check F1 row.
    *   Change:
        *   CUJ 6 (renumbered CUJ 5, A-27) creates a synced role through the API and gives it to the user, and finds the row with `q`.
        *   Every spec checks the header username after navigation.
        *   `setStubMode` is `PUT` with `Content-Type: text/plain` and body `ok` or `fail`.
        *   Password grant: form fields `grant_type=password`, `client_id=cce-e2e`, `username`, `password`.
        *   Created users get `e2e-{suffix}@cce.local`.
        *   Credentials come only from the environment (`CCE_TEST_ADMIN_USERNAME`, `CCE_TEST_ADMIN_PASSWORD`), with no `.env` path. The README shows how to export them from the admin-service `.env`.
        *   Remove the CUJ 2 Mailpit check and the Mailpit URL.

### [Issue 23] A-23 [`egm-style`] - Severity: Major
*   **Affected Scope:** [Lines 394-395](../../docs/designs/cce-admin-ui.md#L394-L395); [line 1260](../../docs/designs/cce-admin-ui.md#L1260)
*   **Discrepancy Description:** The design says `initAuth()` does not resolve without a session; the plan says it rejects; the start-up text names only one cause.
*   **Rationale:** Contradiction.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid.
*   ***Proposed Edit:***
    *   Location: Start-up step 2; items 19 and 24.
    *   Change: `initAuth()` rejects when `init` resolves without a session or throws; both texts say so; the screen shows the neutral message "Sign-in did not complete with the sign-in service at {url}."

### [Issue 24] A-24 [`egm-style`] - Severity: Major
*   **Affected Scope:** [Lines 1511-1519](../../docs/designs/cce-admin-ui.md#L1511-L1519) (items 61, 62)
*   **Discrepancy Description:** Badge texts are given differently in the screens text and the items; tests assert unstated text.
*   **Rationale:** Ambiguous assertions.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid.
*   ***Proposed Edit:***
    *   Location: items 61, 62; screens; tests.
    *   Change: user status labels "Active", "Inactive", "Locked"; sync labels "Synced", "Never synced", "Sync failed", each followed by "· {yyyy-MM-dd HH:mm}" when `lastSyncAt` is set; all unit and e2e assertions use these strings.

### [Issue 25] A-25 [`egm-style`] - Severity: Major
*   **Affected Scope:** [Lines 1810-1827](../../docs/designs/cce-admin-ui.md#L1810-L1827) (Verification Plan)
*   **Discrepancy Description:** "No MSW" is not checkable as written; the gateway note is missing; the final checks are not measurable.
*   **Rationale:** Verification must be able to fail.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid.
*   ***Proposed Edit:***
    *   Location: Verification Plan.
    *   Change:
        *   Grep `dist/` for `mockServiceWorker` and `setupServer`.
        *   Note that pointing `GATEWAY_UPSTREAM` at the service is a local check only, because no gateway runs locally (C-05).
        *   `curl -s -o /dev/null -w '%{http_code}' http://localhost:3002/users` prints `200`, and the body contains `<div id="root">`.
        *   `curl -s http://localhost:3002/admin/v1/health` contains `"status":"UP"`.

### [Issue 26] A-26 [`egm-design`, `egm-comprehension`, `egm-zoom-out`] - Severity: Nit
*   **Affected Scope:** Feature range, several lines (implicit rows note, AD-03 citation, save message, CUJ 3, Goal 3)
*   **Discrepancy Description:** Small correctness gaps.
*   **Rationale:** See consolidated A-26.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Each fix is one sentence and removes a real inaccuracy.
*   ***Proposed Edit:***
    *   Change:
        *   The implicit-rows note becomes "Every non-system role keeps these two rules"; CUJ 3 names `GET /admin/v1/me` and `GET /admin/v1/me/menus`.
        *   Cite AS-REQ-15 / admin-service §4.5 for inactive grants.
        *   A permissions save shows "Saved." and the badge shows the sync state.
        *   Define CCE (Care Coordination Engine) and facility scope.
        *   State the `LAST_SYSTEM_ADMIN` rule: refused if no other `ACTIVE` user would hold `SUPER_ADMIN`.
        *   Explain that a malformed id is a 400 because the service validates path ids as UUIDs.
        *   Jenkins: "the team's CI".
        *   Goal 3: "on the screen given in the `SyncFailedNotice` table".
        *   Name the files that start as copies of `cce-insights-ui`: the `vite.config.ts` shape, the `Caddyfile`, the Dockerfile layout and the Vitest setup.

### [Issue 27] A-27 [`egm-structural`] - Severity: Nit
*   **Affected Scope:** Screens, Alternatives, CUJs, sync diagram
*   **Discrepancy Description:** Structure Nits S-4 to S-9.
*   **Rationale:** See consolidated A-27.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Cheap and improves navigation; the CUJ renumbering aligns with the admin-service design.
*   ***Proposed Edit:***
    *   Change:
        *   Each screen gets its own H4, with the tabs nested under Role detail.
        *   Alternatives headings follow one form: "Alternative X: …".
        *   CUJs are renumbered: CUJ 4 is S4 (change roles), CUJ 5 is S5 (deactivate), CUJ 6 is S6 (sync failure). The e2e spec names follow.
        *   The sync diagram is split into one edge per line.
        *   One heading case is used throughout, and the stray horizontal rule is removed.
        *   Every list has a blank line before it.

### [Issue 28] A-28 [`egm-style`] - Severity: Nit
*   **Affected Scope:** Feature range (Stage 7 ST-15 to ST-31 except ST-25)
*   **Discrepancy Description:** Wording, vague durations, repeated sentences, timestamp formats, exact message strings, dense paragraph.
*   **Rationale:** See critic_report.md.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Applied item by item from the critic report when editing.
*   ***Proposed Edit:***
    *   Change:
        *   Apply each ST item's own suggestion.
        *   Use one timestamp format, `yyyy-MM-dd HH:mm` local time.
        *   Give every user-facing message as an exact quoted string.
        *   Split the browser-tests paragraph into a list.

### [Issue 29] A-29 [`egm-implementation-readiness`, `egm-style`] - Severity: Nit
*   **Affected Scope:** Detailed range (Stage 9 IR-8 to IR-14, ST-6 to ST-23)
*   **Discrepancy Description:** Plan-level gaps and wording.
*   **Rationale:** See implementation_readiness_report.md.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Each closes a choice the implementer would otherwise make.
*   ***Proposed Edit:***
    *   Change:
        *   Roles list paging: name the item that implements it.
        *   A filter change resets `page`; name the item.
        *   `MenuCheckboxTree` shows path and type.
        *   `MenuForm` is shown inline in a panel above the table, not in a `ConfirmDialog`.
        *   Menus UI choices as the report lists them.
        *   `tsconfig`: `noEmit`, `tsBuildInfoFile` under `node_modules/.tmp`, `skipLibCheck: true`, and jest-dom types.
        *   Coverage `exclude` extends Vitest's defaults.
        *   `fakeAuth` spies are reset in `afterEach`; `createWrapper()` is exported; `createQueryClient(options?)` accepts overrides.
        *   `describeError(error, context?)` takes an optional context, and `DescribedError` is defined.
        *   Naming and wording fixes as listed.

### [Issue 30] A-30 [`egm-zoom-out`, `egm-design`] - Severity: Nit
*   **Affected Scope:** Shell and routing (guard), Menus catalog (icon select), Build and deployment (topology), Alternatives
*   **Discrepancy Description:** Decisions for the author.
*   **Rationale:** See consolidated A-30.
*   ***AI Suggestion:*** PROPOSE FIX (author decisions applied)
*   ***Justification:*** The author decided each point.
*   ***Proposed Edit:***
    *   Change:
        *   Keep the unsaved-changes guard with the data router, unchanged in scope.
        *   The icon select holds the ten seed icons plus the entry's current value. The five extra icons are dropped, and the sidebar's own icons stay in `Sidebar.tsx`.
        *   No runtime `/config.json`; record it as rejected.
        *   No self-lockout warning; record it as rejected because `LAST_SYSTEM_ADMIN` covers the catastrophic case.
        *   Deployment: "The console needs an origin that also routes `/auth` to Keycloak, or an image built with `VITE_KEYCLOAK_URL` set." Add a rejected alternative: serve the console under a base path on a shared origin.
        *   No wider guard tests and no real-stack F-04 check. Record both in Author Decisions.

## Author-directed contract alignment (not reviewer findings)

These follow the contract changes the author has just decided on the admin-service design. Names
are as given by the author; exact names and codes are re-checked against the resolved
admin-service file afterwards.

*   **K-1 `POST /users` links an existing account.**
    *   An enabled Keycloak account that matches both username and email and has no local row is linked instead of refused. The response is unchanged.
    *   Edit: CUJ 2 and the User create screen say a 201 can also mean "an existing sign-in account was linked".
    *   The 409s `USERNAME_IN_USE` and `EMAIL_IN_USE` remain for partial matches and for local users.
*   **K-2 `POST /roles` sync failure is a warning.**
    *   A failed sync after create returns 201 with the warning `ROLE_SYNC_FAILED` instead of a 502.
    *   Edit: Roles list on 201 with that warning shows "The role was created but not synced. Use Re-sync on its Permissions tab before assigning it." and still navigates to the role; contract types gain the warning code; CUJ 3/CUJ 6 and e2e follow; remove the `ROLE_CREATE` 502 row.
    *   `POST /roles` also returns 409 when a realm role with that name already exists in Keycloak. It is mapped to the name field (code to be confirmed at the re-check; `DUPLICATE_ROLE_NAME` assumed).
*   **K-3 No `ROLE_DELETE` 502.**
    *   `DELETE /roles/:id` no longer syncs.
    *   Edit: remove the `ROLE_DELETE` row, its test, its operation in `contract.ts`, and the Roles list notice.
*   **K-4 No fan-out marking.**
    *   `syncStatus` changes only for the role whose sync ran.
    *   Edit: remove `GATEWAY_SYNC_SCOPE` references (with A-08), and the diagram notes that only that role's status changes.
*   **K-5 Service-side permission check stays.**
    *   A 403 "role lacks permission" can also come from the service locally.
    *   Edit: C-05 keeps that sentence and cites the service's simplified check.
*   **K-6 `POST /users` 502 never leaves a local user.**
    *   Edit: as A-04.
