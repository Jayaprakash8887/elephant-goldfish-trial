# Goldfish Review Report: Stage 8, Intermediate Zoom Out

**Target:** `/home/jayaprakashn/Documents/workspace/elephant-goldfish-trial/docs/designs/cce-admin-ui.md` (full file, lines 1-1833)

**Scope of this stage:** the remedies proposed in the Stage 5 zoom-out report (`zoom_out_report.md`, ids M*, N*), the Stage 6 comprehension report (`comprehension_report.md`, ids #*) and the Stage 7 critic report (`critic_report.md`, ids D-*, S-*, ST-*), viewed from altitude. The TargetFile has not changed since those stages, so their line numbers still apply.

## Reviewer Status

| Reviewer | Status |
| --- | --- |
| `egm-zoom-out` | completed |

**Severity summary:** 0 Blocker, 10 Major, 4 Nit.

**What the reviewer read and what it couldn't check:**
- **Read:** the persona, the zoom-out standard, the full TargetFile, the three earlier reports and the GoldfishContext reading list.
- **`cce-admin-service.md` only in part:** lines 1-860 and 995-1363, which cover the contract, the flows, audit, M0, E2E, the alternatives and the risks. It did not read the backend's data dictionary or its implementation plan.
- **D-1 not judged against the critic's standard:** the critic's `feature-spec-standard.md` was not supplied.
- **No reuse search:** the workspace has no source code, so it couldn't look for reuse beyond the `cce-insights-ui` docs.

## Prioritized Findings (Blockers -> Major -> Nits)

### Z8-1. Four remedies would change the API contract and turn a console fix into a two-design negotiation - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:**
  * partial failures: lines 470, 537-540, 609-611;
  * last write wins: lines 141-142, 212, 676-681, 709-711.
* **Concerns:** zoom-out M5, M6 (the complete fix), M1 bullets 2-3; critic D-12 bullet 3, D-7 bullet 3.
* **Discrepancy Description:**
  * **M5's confirmed re-send needs:**
    * an idempotency review of every Keycloak call in admin-service flows 5.2 to 5.4;
    * a new "next step" column in admin-service §4.2;
    * a console state that keeps the sent body apart from the refetched data.
  * **Why M5 cuts against both designs:** PRD §9 says partial states are "recorded for reconciliation rather than blindly retried". The backend lists "Automatic repair of partial failures" as a Non-Goal (admin-service lines 175-177).
  * **The other contract changes:**
    * M1 and D-12 each ask for a new code or detail to mark an orphan Keycloak account.
    * M6 and D-7 ask for `If-Match` and a 412.
  * **Conflict:** critic ST-5, ST-13 and comprehension #4 go the other way. They accept operator reconciliation and ask the document to say so honestly. Both directions can't be applied.
* **Rationale & Recommendation:**
  * **Drop M5.** Apply ST-5, ST-13 and #4 instead, which are text-only.
  * **Replace M1 bullets 2-3 and D-12 bullet 3 with one request to the backend design.** In flow 5.1 step 3, the 409 message should say that the conflict is an account in Keycloak with no local user. The console already shows that message at the field, so it explains the conflict without a new code or any console logic.
  * **Record `If-Match` and 412 as a follow-up on the admin-service design,** not as a remedy in this document.

### Z8-2. The drift-protection remedies are heavier than the risk, and Option B undoes a recorded decision - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:**
  * lines 101-104 (Goal 6);
  * lines 428-437 (`contract.ts`);
  * lines 922-935 (Alternative F);
  * lines 147-152.
* **Concerns:** zoom-out M3 Option B; critic D-2 options 1 and 3, ST-6.
* **Discrepancy Description:**
  * **What M3 Option B adds:**
    * an OpenAPI snapshot shared between repos, plus a process to refresh it;
    * a type generator;
    * a two-way assignability test, which may need hand-written adapters for envelopes and nullability;
    * optionally, e2e in Jenkins.
  * **Why it reverses D-01:** under D-01 and Alternative F, the contract is the admin-service *design*, not its code. Option B makes the code's generated spec the check.
  * **D-2 option 3 duplicates the backend:** API smoke calls inside `npm run e2e` repeat work the backend already does.
    * Its Web tests assert that the OpenAPI document lists every endpoint (admin-service line 1134).
    * Its API-level E2E runs S2 to S6 and the live 403 (admin-service lines 1139-1155).
* **Rationale & Recommendation:**
  * Adopt M3 Option A, which rewords Goal 6. D-2's reword is the same change.
  * Drop M3 Option B, D-2 options 1 and 3, and the Jenkins e2e idea.
  * Add a row to the risks table (Z8-12): "`contract.ts` is a hand copy; drift from the service is caught only by `npm run e2e` and the backend's tests".

### Z8-3. Checking for changes before saving adds a diff dialog that narrows the race without closing it - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** lines 141-142, 676-681, 709-711, 750-752.
* **Concerns:** zoom-out M6 (the console-only fix); critic D-7 bullet 1.
* **Discrepancy Description:**
  * **What the fix costs:** on three replace screens, an extra no-cache GET before every save, a comparison with the form's starting values, and a "changed since you opened it" dialog with a visible diff.
  * **What it still misses:** M6 itself says the race window stays open.
  * **The menu half is low-stakes:** PRD §4 calls Role → Menu a soft control where wrong config is "cosmetic". The menu-toggle part of M6 guards cosmetic data.
* **Rationale & Recommendation:**
  * Drop both console-only fixes, and record last-write-wins as an accepted risk (Z8-12).
  * If anything is kept, keep only a fresh read of `['menus','tree']` (`staleTime: 0`) before building the menu-toggle body. Skip the dialog.

### Z8-4. Removing Delete from Role detail is simpler than D-11's navigation handoff - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:**
  * lines 640-642 (Delete on Role detail);
  * line 545 (the `ROLE_DELETE` row);
  * line 631 (502 on delete);
  * `useDeleteRole` in file 86 of the implementation plan (around line 1646).
* **Concerns:** critic D-11.
* **Discrepancy Description:**
  * **What D-11 adds:** a navigation-state handoff to `/roles` for both 204 and 502, a notice on the list page, and a test for each case.
  * **The requirements don't need Delete here:**
    * F-12 puts delete on the Roles list.
    * F-13 and F-14 don't ask for it on Role detail.
  * **The list already handles it:** the `ROLE_DELETE` notice is already placed there (line 545).
* **Rationale & Recommendation:**
  * Remove Delete from Role detail (lines 641-642, and `useDeleteRole` in file 86). This makes D-11 unnecessary.
  * Goal 3's "always" then holds with no new code.

### Z8-5. Four remedies for `SyncFailedNotice` conflict; one data-driven rule settles them - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** lines 528-545, 609-611, 695-698, 824, 1722, 1729.
* **Concerns:** critic D-10, ST-10, D-12 bullets 1-2; zoom-out N3, M5.
* **Discrepancy Description:**
  * **The four directions:**
    * D-10 adds a "no steps" branch with a Retry action to every operation.
    * N3 removes branches the contract can't produce, such as `ROLE_PERMISSIONS_SAVE` with no steps.
    * ST-10 removes the "Nothing was changed." fallback.
    * D-12 says "Nothing was created" can be false after a timeout.
  * **What the contract actually produces** (admin-service flows 5.1-5.10, line 748):
    * `ROLE_CREATE`, `ROLE_PERMISSIONS_SAVE` and `ROLE_DELETE` always have completed steps.
    * `ROLE_SYNC` and `USER_PASSWORD_RESET` never do.
    * The four user operations can have either.
  * **D-10's Retry button duplicates existing buttons.** The form's own Save or submit, and the row action, already let the admin try again.
* **Rationale & Recommendation:** replace the per-row branches with one rule.
  * **When `completedSteps` is empty** (any operation except `ROLE_SYNC`): show "The service recorded no change. You can try again." Keep the form input and the button enabled. This wording stays true after a timeout, which covers D-12 bullet 1.
  * **When it is not empty:** show the row's fixed text only.
  * **`ROLE_SYNC`:** use ST-10's wording.
  * **What to drop:**
    * the "No steps:" sub-texts in lines 537 and 543;
    * the step-list fallback (line 531);
    * D-10's Retry action;
    * D-12 bullet 2, which Z8-1's backend message replaces;
    * the test cases for states the contract can't produce (lines 824 and 1729).

### Z8-6. Invalidate every query after each write and delete the invalidation table - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:**
  * lines 475-486 (the invalidation table and the `GATEWAY_SYNC_SCOPE=GLOBAL` paragraph);
  * lines 1386-1387, 1392-1393.
* **Concerns:** critic ST-9, D-20 bullet 1; zoom-out N2.
* **Discrepancy Description:**
  * **The remedies pull both ways:**
    * N2 removes invalidations.
    * D-20 adds one.
    * ST-9 finds two different refetch rules for a 502.
  * **The table is low-level cache bookkeeping** for a low-traffic admin console. It also brings a backend setting (`GATEWAY_SYNC_SCOPE`) into the console.
* **Rationale & Recommendation:**
  * Use one rule: after any mutation settles with a 2xx or a 502, call `queryClient.invalidateQueries()`. Only active queries refetch, which is a handful of GETs.
  * Delete the table and lines 485-486.
  * This settles ST-9 and makes N2 and D-20 bullet 1 unnecessary.

### Z8-7. The auth-loop fix needs no `sessionStorage` counter - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:**
  * lines 392-393 and 1260-1261 (`login()` when the interval refresh fails);
  * lines 453-457 (`login()` on a second 401).
* **Concerns:** critic D-5.
* **Discrepancy Description:**
  * **The loop.** On a second 401, `refreshToken()` has just succeeded, so the Keycloak session is valid. Calling `login()` can't help there, and it is what causes the loop. D-5's attempt counter treats the symptom.
  * **The needless redirect.** The interval's `login()` duplicates the 401 path, which already calls `login()` when a refresh genuinely fails.
* **Rationale & Recommendation:**
  * In `client.ts`, a 401 after a successful refresh throws the 401 `ApiError` and shows it inline. It does not call `login()`.
  * In the interval, ignore a failed `updateToken(70)` and try again on the next tick.
  * Drop D-5's `sessionStorage` counter and its expiry checks.

### Z8-8. One error-fallback rule covers D-3, D-4 and ST-8 - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** lines 447-452, 468-470, 500-509.
* **Concerns:** critic D-3, D-4, ST-8.
* **Discrepancy Description:**
  * **The three findings overlap:**
    * D-3: classify a response that has no envelope by its HTTP status.
    * D-4: an "outcome unknown" state for mutations, plus Caddy and proxy timeout tuning.
    * ST-8: reword line 470.
  * **The timeout tuning belongs elsewhere.** It depends on ingress and gateway settings this console doesn't own.
* **Rationale & Recommendation:**
  * In `describeError`, classify a response with no envelope by its status: 401 goes down the existing path, 403 becomes `forbidden`, anything else becomes `unexpected`.
  * Offer Retry only for queries.
  * A mutation that fails with `network` or `unexpected` says "The result is unknown; the page was refreshed" and invalidates queries (Z8-6).
  * Apply ST-8's wording.
  * Drop D-4's proxy-timeout part, or record it as a risk.

### Z8-9. Remove the role-picker hint rather than reword it - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** lines 603-605, 1542-1546, 784-785, 280-289.
* **Concerns:** zoom-out M2; critic D-9; comprehension #2.
* **Discrepancy Description:**
  * **The two rewordings disagree.** M2 proposes a neutral hint. D-9's `FAILED` text, "Re-sync before assigning", again asks for more than the service enforces.
  * **The hint adds nothing the error doesn't.** The badge already shows the status. A real refusal (`ROLE_NOT_SYNCED`) names the role and says "Open the role and use Re-sync" (lines 517-521).
* **Rationale & Recommendation:**
  * Keep the badge and drop the hint sentence.
  * Rewrite CUJ 4's Given as M2 and D-9 both propose: the role is created while the sync is failing.
  * M4's canned mocks remove the MSW stand-in that encoded the wrong rule (lines 784-785).

### Z8-12. The risks table: lower D-1 to Major and use it to absorb remedies - Severity: Major (down from Blocker)
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** lines 965-975 (Non-Functional Requirements; the document has no risks table).
* **Concerns:** critic D-1, D-5, D-6, D-7, D-8, D-16.
* **Discrepancy Description:**
  * **The risks are already written down,** but scattered across the document. The fix files existing text into a table, so Blocker overstates it.
  * **D-1's list conflicts with other findings.** It lists D-5, D-6 and D-8 as accepted risks, while those findings also propose fixes. Each item should be either fixed or accepted, not both.
* **Rationale & Recommendation:** add a short "Risks accepted by the author" table, matching the backend design. Limit it to about six rows:
  * GW-2: "synced" doesn't mean the gateway enforces the rows;
  * last write wins, with an ETag follow-up on the backend (Z8-1, Z8-3);
  * contract drift (Z8-2);
  * error paths covered only by MSW;
  * more than 100 roles (Z8-11);
  * the shape of gateway error bodies, until the gateway owners answer.

  Leave out the items that are fixed (Z8-7, Z8-8, Z8-10).

### Z8-10. Some remedies add runtime machinery or belong in another component - Severity: Nit
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** lines 841-848, 1193-1200, 973-975, 594-596.
* **Concerns:** critic D-6, D-8, D-17; zoom-out N8.
* **Discrepancy Description:**
  * **D-6 (CSP).** A static CSP header is cheap. Its `KEYCLOAK_ORIGIN` check at start-up is not needed: with the default `VITE_KEYCLOAK_URL` (`${origin}/auth`, line 383), `connect-src 'self'` already works.
  * **D-8 (audit IP).** The cause sits in the admin-service, which reads the *first* `X-Forwarded-For` value (admin-service lines 1005-1007), and in the ingress chain. It is not in the console.
  * **N8 (runtime `/config.json`).** It is a deviation from PRD §8.3 with no requirement behind it.
  * **D-17 (self-lockout warning).** It is a feature request. `LAST_SYSTEM_ADMIN` already covers the catastrophic case.
* **Rationale & Recommendation:**
  * Add a static CSP using `'self'`. A cross-origin Keycloak only needs an optional `{$KEYCLOAK_ORIGIN}` placeholder with an empty default.
  * Move D-8 to the admin-service design, or to a risks row.
  * Drop N8 and D-17.

### Z8-11. Fetching every role: a one-line dedupe is enough - Severity: Nit
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** lines 488-492, 1347-1348.
* **Concerns:** zoom-out N9; critic D-16.
* **Discrepancy Description:** N9 proposes deduping, parallel page fetches and a refetch when `totalItems` changes. For a role count that will realistically stay under 100, that is optimisation before any bottleneck exists.
* **Rationale & Recommendation:** keep the sequential fetch, dedupe by `id`, and state an expected maximum as a risk (D-16, Z8-12). Drop the parallel fetch and the `totalItems` check.

### Z8-13. Documentation remedies that conflict - Severity: Nit
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:**
  * line 179 and lines 867-932 (the author's rejections);
  * lines 43-44 and 723-748 (restated rules);
  * lines 181-239 (Open Questions).
* **Concerns:** critic S-1 vs S-2 and comprehension #6; critic ST-3 vs zoom-out M3 Option A; critic S-3.
* **Discrepancy Description:**
  * **S-1 vs S-2 and #6.** S-1 suggests linking `trial-log/QA.md`. S-2 and #6 ask for drafting-process references to be removed.
  * **ST-3 vs M3 Option A.** ST-3 keeps the restated validation table and adds a precedence note. M3 Option A replaces the table with a reference.
  * **S-3.** S-3 moves the 24-row AS-REQ table, which mostly repeats admin-service §4.6.
* **Rationale & Recommendation:**
  * Link each rejection to the in-document Author Decisions table that S-2 proposes. Don't link `trial-log/`.
  * For the validation table, apply M3 Option A. Use ST-3's precedence sentence for whatever is still restated.
  * Delete the AS-REQ table and link admin-service §4.6. That settles S-3 by removing the table, not moving it.

### Z8-14. Scope of the browser tests, the guard and the remaining smaller items - Severity: Nit
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** lines 109-122, 236, 549-551, 568-571, 698-699, 713-721, 795-798, 1120, 1170, 1763-1768, 1800-1802.
* **Concerns:** critic D-20 bullets 2-3, ST-12, D-15; zoom-out N1, N6.
* **Discrepancy Description:**
  * **D-20 bullet 2 conflicts with N6.** D-20 widens what the unsaved-changes guard covers, while N6 questions whether the guard is needed at all.
  * **D-20 bullet 3 overlaps the backend.** It adds a real-stack 403 check, which the backend E2E already runs (admin-service line 1155).
  * **CUJ 2's Mailpit check is also duplicated.** The backend's S2 already checks Mailpit (admin-service line 1144).
  * **ST-12 is already addressed** in the implementation plan (lines 1120, 1170, 1800-1802), so Major overstates it.
  * **N1 option 1 has a hidden cost.** A live preview of any icon name means importing the whole Heroicons set.
  * **D-15 has a simpler fix.** Show "Saved." and let the badge show the sync state.
* **Rationale & Recommendation:**
  * Don't widen the guard. The author decides N6.
  * Don't expand Playwright, and remove CUJ 2's Mailpit check.
  * Lower ST-12 to Nit: add only a cross-reference at line 796.
  * For N1, take option 2.
  * For D-15, use "Saved." plus the badge, and drop the "and synced at" text.

## Overall Verdict

**Without triage, the remedies push the console past its PRD scope.** Applied as written, the earlier reports would add about a dozen mechanisms:
* an "outcome unknown" state;
* a login-attempt counter;
* a CSP start-up check;
* `trusted_proxies` configuration;
* a pre-save diff dialog;
* a re-send flow;
* an OpenAPI snapshot with generated types;
* a navigation-state delete handoff;
* a self-lockout warning;
* a runtime `/config.json`;
* parallel role paging.

They would also need four contract changes in the admin-service design.

**With the triage above, the design keeps its scope and simplicity.** The changes are mostly deletions:
* Delete on Role detail;
* the invalidation table;
* the role-picker hint;
* the per-row "no steps" branches;
* the stateful MSW store;
* the AS-REQ table;
* the restated validation table.

What remains is text fixes, two simple rules (the error fallback and the empty-steps notice), a short risks table, and one message change requested from the backend.

## Earlier Remedies Endorsed As-Is

* **Zoom-out (Stage 5):**
  * M1 bullet 1 (drop the `entityId` link), and raising the backend contradiction at lines 550-551;
  * M2 (rewrite CUJ 4's Given);
  * M3 Option A;
  * M4;
  * N3, merged as in Z8-5;
  * N4, N5 and N7.
* **Critic, design lens (Stage 7):** D-3; D-9 bullet 1 (CUJ 4's precondition); D-13; D-14; D-18; D-19.
* **Critic, structural lens (Stage 7):** S-2, S-4, S-5, S-7, S-8 and S-9.
* **Critic, style lens (Stage 7):**
  * ST-1, ST-2, ST-4, ST-5, ST-6 and ST-8;
  * ST-7 (the reword only);
  * ST-10, merged as in Z8-5;
  * ST-11;
  * ST-13 to ST-31.

  ST-12 is lowered to Nit (Z8-14).
* **Comprehension (Stage 6):** #1 to #14. For #1, #3 and #4, add short explanations that link to admin-service sections rather than restating them.

## Severity Changes Proposed to Earlier Findings

| Earlier finding | Earlier severity | Proposed | Where |
| --- | --- | --- | --- |
| critic D-1 | Blocker | Major | Z8-12 |
| critic ST-12 | Major | Nit | Z8-14 |

## Remedies Proposed for Dropping or Moving

* **Drop:**
  * M5 (Z8-1);
  * M3 Option B, and D-2 options 1 and 3 (Z8-2);
  * the console-only part of M6 and D-7 bullet 1 (Z8-3);
  * D-11 (Z8-4);
  * D-10's Retry action, and D-12 bullet 2 (Z8-5);
  * N2 and D-20 bullet 1, made unnecessary by the single invalidation rule (Z8-6);
  * D-5's counter (Z8-7);
  * D-4's proxy-timeout part (Z8-8);
  * D-6's start-up check, N8 and D-17 (Z8-10);
  * N9's parallel fetch and `totalItems` check (Z8-11);
  * S-1's `trial-log/` link (Z8-13);
  * D-20 bullets 2-3 (Z8-14).
* **Move to the admin-service design:**
  * the `If-Match`/412 part of M6 and D-7 bullet 3 (Z8-1);
  * the orphan-account 409 message, which replaces M1 bullets 2-3 and D-12 bullet 3 (Z8-1);
  * D-8 (Z8-10).
