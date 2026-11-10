# Consolidated Review Report: `cce-admin-ui.md`

**Target:** `docs/designs/cce-admin-ui.md` (1833 lines, Status: Proposed)

**Run:** `/egm:egm-review`, preset `uninterrupted` (all five stages, no checkpoints, no fixes applied), on 2026-11-10.

**Ranges:**
- FeatureRange: lines 1-978.
- DetailedRange: lines 979-1833, from `## Detailed Implementation Plan`.

**GoldfishContext:** the 8 files the design links to. All of them resolve inside the working directory, so none were left out. The design has no Markdown links to URLs. Its `localhost` URLs are config values in code spans.
- `docs/designs/cce-admin-service.md`
- `docs/designs/author-decisions.md`
- `context/prd/CCE_Admin_Module_PRD.md`
- `context/PROVENANCE.md`
- `context/gateway-service/DATABASE_PERMISSIONS_MIGRATION.md`
- `context/cce-insights-ui/docs/architecture-overview.md`
- `context/cce-insights-ui/docs/deployment-guide.md`
- `context/cce-insights-ui/docs/developer-setup.md`

Stage 6 got no context: it read the document alone.

The design document was **not edited** during this run. All line numbers refer to the file as it was at the start.

## Stage reports

| Stage | Report | Reviewers (status) | Blocker / Major / Nit |
| --- | --- | --- | --- |
| 5 Initial Zoom Out | [zoom_out_report.md](zoom_out_report.md) | zoom-out, inventive-catalyst (both completed) | 0 / 6 / 9 |
| 6 Comprehension | [comprehension_report.md](comprehension_report.md) | comprehension (completed) | 0 / 4 / 10 |
| 7 Critic | [critic_report.md](critic_report.md) | design, structural, style (all completed) | 1 / 28 / 31 |
| 8 Intermediate Zoom Out | [intermediate_zoom_out_report.md](intermediate_zoom_out_report.md) | zoom-out (completed) | 0 / 10 / 4 |
| 9 Implementation Readiness | [implementation_readiness_report.md](implementation_readiness_report.md) | implementation-readiness, codebase-details, style (all completed) | 0 / 17 / 30 |

**Process notes:**
- **Stage 9 report.** The Stage 9 synthesizer returned before its reviewers finished. The three reviewers then reported to the main context, which wrote that report itself.
- **Stage 8 coverage.** The Stage 8 reviewer read `cce-admin-service.md` only in part: lines 1-860 and 995-1363.

## Verdict

**The design is not ready to implement as written.**

**Feature range: sound overall.**
- Stage 5 checked every endpoint, field, error code and partial-failure operation the console uses against admin-service §4.1-§4.5, and found that they match.
- Stage 9 confirmed this at plan level.
- The problems are not in the contract mapping. They fall into three groups:
  - **Failure-path behaviour.** What the console says and offers after a 502, a timeout, a gateway 403 or a repeated 401.
  - **Claims.** Several claims say more than the design delivers: Goal 6 drift protection, "approved", E2E coverage, and the ESLint layers.
  - **Missing sections.** There is no risks section, and the decision records are missing.

**Implementation plan: not executable step by step.**
- The step checks cannot pass.
- The ESLint layer rules either break lint or are silently not enforced.
- Several screens, the `api/` return types and the test coverage leave choices to the implementer.

**Triage of the remedies.** Stage 8 warned that applying every earlier remedy as written would add about a dozen mechanisms and four contract changes. This consolidation follows the Stage 8 triage. In most cases that means deleting or rewording, not adding. Where Stage 8 dropped a remedy, the action item says so.

**Severity of the risks section.** Stage 7 rated the missing risks section a Blocker, and Stage 8 proposed Major. It stays 🔴 here because the feature-spec standard requires the section, but the fix is small: file existing text into a table.

---

## Action items

### 🔴 Blocker

**A-01. Add a "Risks accepted by the author" table and a Performance NFR.**
- **Where:** under Non-Functional Requirements (lines 965-975), matching admin-service line 1353.
- **Size:** about six rows, each with Risk | Effect | Mitigation or why accepted.
- **Rows:**
  - GW-2: "Synced" does not mean the gateway enforces the rows until it restarts.
  - Last write wins on full-set saves, with an ETag follow-up on the backend (A-12).
  - `contract.ts` is a hand copy. Drift is caught only by `npm run e2e` and the backend's tests (A-09).
  - Some error paths are covered only by MSW.
  - The expected role count. `useAllRoles` walks every page. State a maximum, for example 100, and dedupe by `id`.
  - The shape of gateway error bodies is unknown until the gateway owners answer (A-06).
- **Performance NFR:** add one bullet.
- **Leave out:** items that other actions fix (A-06, A-07, A-13).

### 🟡 Major: feature range (lines 1-978)

**A-02. Explain the three external mechanisms the console relies on, and make the goals honest about operator work.**

Add two or three sentences for each, linking the admin-service section rather than restating it:
- **The gateway sync:** who calls it, what it writes, and what `GATEWAY_SYNC_SCOPE` means.
- **The stand-in gateway:** a fake sync target that the service calls. It is not in the console's request path, and it has `ok` and `fail` modes. Also resolve the clash with C-05's "Locally there is no gateway".
- **Operator reconciliation:** who the operator is, and that no console tool exists for it.

Then fix the goals that depend on these:
- Narrow Goal 2 to the success paths plus the S6 Re-sync recovery.
- Reword the Problem text at lines 64-65 to "the admin, who must either act (Re-sync) or pass it to an operator".
- **Remedy dropped:** the confirmed re-send for user partial failures (Stage 5 M5). It needs a contract change and works against PRD §9's "recorded for reconciliation rather than blindly retried".

**A-03. Remove the role-picker hint and fix CUJ 4's precondition.**
- **Why the hint is wrong.** `syncStatus` is a display hint. The service refuses an assignment only when Keycloak has no realm role (AD-10, admin-service line 627). The hint at line 604, "assigning it will fail until it is synced", is wrong for `FAILED` roles and for the seeded `SUPER_ADMIN`.
- **The fix:**
  - Keep the badge and drop the hint sentence.
  - Rewrite CUJ 4's Given (lines 280-289) as: "a role created while the gateway sync is failing, so its realm role does not exist".
  - In the e2e setup, switch the stand-in to `fail` before creating the role.

**A-04. Remove the dead `USER_CREATE` link, and raise the orphan-account conflict with the backend design.**
- **The dead link.** A `POST /users` 502 never leaves a local user (admin-service flow 5.1). Drop the "link to the user if `entityId` is set" at line 537.
- **One request to the backend design:** in flow 5.1 step 3, the 409 message should say the conflict is a Keycloak account with no local user. The console already shows that message at the field, so it needs no new code.
- **Remedies dropped:** the new error codes and `details` entries proposed in Stage 5 M1 and Stage 7 D-12.

**A-05. Replace the per-row "no steps" texts in `SyncFailedNotice` with one rule.**
- **When `completedSteps` is empty** (every operation except `ROLE_SYNC`): show "The service recorded no change. You can try again." Keep the form input and the button. This wording stays true after a timeout.
- **When it is not empty:** show the row's fixed text only.
- **`ROLE_SYNC`:** "The sync failed. The saved permissions are unchanged. Use Re-sync."
- **Delete:**
  - the step-list fallback "Nothing was changed." (line 531);
  - the "No steps:" sub-texts (lines 537, 543);
  - the test cases for states the contract cannot produce (lines 824, 1729).
- **Fix the `USER_PROFILE_UPDATE` text** (line 538): "Keycloak has the new values. This page shows the old values, which are still stored locally."
- **Remedy dropped:** D-10's extra Retry button. The form's own button already serves.

**A-06. Add one fallback rule for errors that do not come in the service's error format.**
- **Classify by HTTP status.** In `describeError`, a response with no envelope goes by its status:
  - 401 follows the existing refresh path;
  - 403 becomes `forbidden`;
  - anything else becomes `unexpected`.
- **Retry for queries only.** A mutation that fails with `network` or `unexpected` says "The result is unknown; the page was refreshed" and refetches.
- **Line 470:** reword it to "TanStack Query never retries mutations; `client.ts` repeats a request once only after a 401".
- **New open question** for the gateway owners: what shape are their error bodies?
- **Remedy dropped:** tuning the proxy timeouts. Record it as a risk instead (A-01).

**A-07. Stop the sign-in redirect loop at its cause.**
- **On a 401 after a refresh that succeeded:** throw the 401 `ApiError` and show it inline. Do not call `login()` (lines 453-457).
- **In the refresh interval:** ignore a failed `updateToken(70)` and try again on the next tick (lines 392-393).
- **Remedy dropped:** D-5's `sessionStorage` attempt counter.

**A-08. Replace the cache-invalidation table with one rule.**
- **The rule:** after any mutation settles with a 2xx or a 502, call `queryClient.invalidateQueries()`.
- **Delete:** the table at lines 475-484 and the `GATEWAY_SYNC_SCOPE` paragraph at lines 485-486.
- **Settled by this:** the two refetch rules for a 502 conflict, and Stage 9's IR-7 (Stage 7 ST-9). The over- and under-invalidation findings (Stage 5 N2, Stage 7 D-20 bullet 1) become unnecessary.

**A-09. Make Goal 6 claim only what the build checks, and stop restating the contract.**
- **Goal 6** (lines 101-104): "Request and response shapes in the client and MSW handlers cannot drift from `src/api/contract.ts` without a type error."
- **The validation table** (lines 730-748): replace it with a link to admin-service §4.4, plus the console-only rules.
- **For anything still restated:** add "the admin-service design wins if the two disagree" (Stage 7 ST-3).
- **Remedies dropped:** the OpenAPI snapshot with generated types (Stage 5 M3 Option B), which reverses D-01. Also the extra API smoke calls (Stage 7 D-2 options 1 and 3), which repeat the backend's own E2E.

**A-10. Replace the stateful MSW store with canned fixtures that record each request.**
- **What to drop:** the in-memory store re-implements the service's rules: filters, 409s, `LAST_SYSTEM_ADMIN`, and a `syncStatus` stand-in for `ROLE_NOT_SYNCED` (lines 776-787, 1692-1704).
- **What to use:** typed fixtures, handlers that record each request, and per-test `server.use(...)` overrides for each error code.
- **Side effect:** this removes Stage 9 CD-6, the `SUPER_ADMIN` seed `syncStatus` mismatch.

**A-11. Remove Delete from Role detail.**
- **Why:** F-12 puts delete on the Roles list, and the `ROLE_DELETE` notice is already placed there.
- **What to delete:** the button at lines 641-642 and `useDeleteRole` in item 86.
- **Settled by this:** Stage 7 D-11 and Stage 9 IR-6, with no navigation handoff.
- **Still needed on Role detail:** show `NotFoundPanel` for `ROLE_NOT_FOUND`, as item 84 does for users.

**A-12. Record last-write-wins as an accepted risk, and ask the backend for an ETag.**
- **Accept the risk:** add the A-01 row and remove the repeated sentence at line 142.
- **Ask the backend:** record `If-Match` with a 412 response as a follow-up on the admin-service design.
- **Optional:** refetch `['menus','tree']` with `staleTime: 0` before building a menu-toggle body. That toggle currently resends the whole cached record (lines 709-711).
- **Remedy dropped:** the console-only pre-save diff dialog (Stage 5 M6, Stage 7 D-7 bullet 1). It narrows the race window without closing it.
- **Author decision:** permissions are a hard control under PRD §4, and dropping the dialog trades safety for simplicity. The author should confirm this trade explicitly.

**A-13. Add a static Content-Security-Policy, and move the audit-IP issue to the backend design.**
- **CSP:** add to the Caddyfile `default-src 'self'; script-src 'self'; connect-src 'self' {$KEYCLOAK_ORIGIN}; frame-ancestors 'none'; object-src 'none'; base-uri 'none'`. `KEYCLOAK_ORIGIN` is optional with an empty default; the default `${origin}/auth` already counts as `'self'`.
- **Headers:** name the three existing security headers and their cache values (Stage 7 ST-25).
- **Audit IP** (Stage 7 D-8): the cause is the admin-service trusting the first `X-Forwarded-For` value (admin-service line 1006). Send it to the backend design, and add a risk row here.
- **Remedy dropped:** a start-up check for `KEYCLOAK_ORIGIN`.

**A-14. Correct the claims that say more than the design delivers.**
- **"approved"** (lines 37, 167): say "defined in the admin-service design (Status: Proposed)".
- **"Every departure from the template comes from the PRD"** (lines 31-32): not true. The data router, non-lazy pages and the retry policy are the author's own choices. List them under Conflicts or deviations.
- **Goals intro** (lines 81-82): give Goals 6-9 their own checks.
- **Goal 9 and C-09** (lines 115-122, 169, 917-920):
  - The tests show S2-S6 and run on demand.
  - Keep the reasoning once, in C-09, instead of four times.
- **ESLint layers** (lines 369-376):
  - Say "these four import rules fail `npm run lint`".
  - Name the cases left to code review: upward imports and global `fetch`.
- **`keycloak-js`** (lines 415-416): "the only *test* file that depends on the `keycloak-js` API".
- **Playwright storage state** (Stage 7 ST-12, lowered to Nit by Stage 8): add a cross-reference at line 796 to the git-ignored path in the plan.

**A-15. Fix the document's structure for decisions and open questions.**
- **Add an "Author Decisions in This Design" H2.** Use an ID | Question | Decision | Why table, as in admin-service line 199.
  - Move the bullets from lines 231-239 into it, with reasons.
  - Give TP-1, TP-2 and "Step 1, item 7" their own rows.
- **Link each "Rejected by author"** to its row. Do not link `trial-log/`.
- **Remove drafting-process wording:** "during this drafting session", "EGM Step 6", "this trial".
- **Under Open Questions, keep only what is open:** GW-2 and the gateway error body (A-06).
- **Delete the AS-REQ table** and link admin-service §4.6 instead.
- **Introduce the ID prefixes once** under Overview: B-, AD-, DEV-, GW-.
- **Alternative D:** remove the weak argument "needs the same port".

### 🟡 Major: implementation plan (lines 979-1833)

**A-16. Give each build step its exact files and a check that can pass.**
- **Step 1:**
  - Add a minimal `src/main.tsx` and `src/vite-env.d.ts`.
  - Add `cp .env.example .env` to the check.
- **Step 2:** list the actual F1 files, including 56-64, 80, 81, 90 and 91-94.
- **Routes:** pick one approach. Either create placeholder pages in F1, or add routes milestone by milestone.
- **Steps 3-6:** list their files and tests.
- **Every step ends with:** `npm run lint && npm run build && npm run test:ci`, plus its rows of the milestone exit-check table (design 806-829).

**A-17. Rewrite the ESLint configuration so it works under flat config.**
- **Use file groups that do not overlap**, each with the full union of its restrictions:
  - `src/components/**`
  - `src/pages/**`
  - `src/auth/**`
  - other non-test `src/**`
  - tests and `e2e/**`
- **Exempt `playwright.config.ts`** from the `@playwright/test` ban.
- **Say where components get runtime constants:** `USER_STATUSES` and `PERMISSION_METHODS`. Re-exporting them from `src/schemas/rules.ts` is one option.
- **Name the exact presets:**
  - `reactHooks.configs['recommended-latest']`;
  - `parserOptions.projectService` and `tsconfigRootDir`.
- **Make the type-import scope match the design:** `contract.ts` only.
- **Prove each rule fires:** add a lint fixture.

**A-18. Make Caddy refuse to start without `GATEWAY_UPSTREAM`, and verify that it does.**
- **Guard:** an entrypoint `sh -c '[ -n "$GATEWAY_UPSTREAM" ] || exit 1; exec caddy run …'`.
- **Verification step:** `docker run` without the variable exits non-zero.
- **Or:** remove the claim at line 846.

**A-19. Test the start-up gate.**
- **Move the start-up logic** into `src/bootstrap.tsx`, and keep `main.tsx` to one line.
- **Add `bootstrap.test.tsx`** with three cases:
  - nothing renders while `initAuth` is pending;
  - the start-up error screen appears on a rejection;
  - with the realm missing, the screen names `VITE_KEYCLOAK_REALM` and `initAuth` is never called.
- **Otherwise:** remove the claim at line 816.

**A-20. Write every `api/` function's full signature.**
- Use one naming style for results that carry pagination or warnings. Today they mix `{ user, warnings }` and `{ data, warnings }`.
- Cover the ten functions that have no return type, including `listRoles`, which needs `pagination`.

**A-21. Meet PRD §11 in the unit tests: one error code per endpoint or screen.**
- **Add `RoleDetailPage.test.tsx`.**
- **Add error cases for:**
  - `PUT /roles/:id`;
  - `DELETE /roles/:id` (`ROLE_IN_USE`, 502 `ROLE_DELETE`);
  - `PUT /roles/:id/menus`;
  - `PUT /menus/:id`;
  - 502 `USER_PASSWORD_RESET`;
  - `EMAIL_IN_USE` on update;
  - `ROLE_NOT_ASSIGNABLE` on create.
- **Reword line 1708** to "each screen's test file".

**A-22. Settle the browser-test specifics.**
- **CUJ 6:** create a synced role through the API and give it to the user. Do not use `SUPER_ADMIN`. Find the user's row with `q`.
- **Header check:** add the header-username check to each spec, or correct design 817.
- **Remaining details:**
  - `setStubMode`: a `PUT` with a plain-text body;
  - the `cce-e2e` password-grant form fields;
  - the email format for created users;
  - the `.env` path base.
- **Credentials:** read them from the environment only. Do not use a `../cce-admin-service/.env` path (Stage 5 N7).
- **Remove CUJ 2's Mailpit check.** The backend's S2 already does it (Stage 8 Z8-14).

**A-23. Make the start-up error text match its real cause.**
- **The conflict:** the design (lines 394-395) says `initAuth()` does not resolve without a session. The plan (line 1260) has it reject in that case.
- **The fix:** make the two agree. Use either a message for each cause or a neutral one: "Sign-in did not complete with the sign-in service at {url}."

**A-24. State each badge's visible text once and test that text.**
- **Where:** items 61 and 62.
- **What:** "Synced", "Never synced" and "Sync failed", and the user status labels.
- **Then:** make the unit and e2e assertions match.

**A-25. Make the Verification Plan precise.**
- **MSW check** (line 1814): name the two strings it greps for. Do not say "no MSW".
- **Gateway note** (line 1824): add a note that there is no local gateway (C-05).
- **Measurable outcomes** (lines 1826-1827): replace them with checks that can fail, such as an HTTP code plus a body check.

### 🟢 Nit

**A-26. Small correctness fixes in the feature range.**
- **Implicit permission rows:**
  - The note "every role keeps these two rules" applies to non-system roles only (Stage 7 D-13).
  - List the two rows where CUJ 3 first mentions them (Stage 6 #7).
- **Citations:** AS-REQ-15 replaces the AD-03 citation (Stage 7 D-14).
- **Saved message:** a permissions save shows "Saved." and lets the badge show the sync state (Stage 7 D-15, Stage 8 Z8-14).
- **Undefined terms:**
  - define CCE and facility scope (Stage 6 #8);
  - state the `LAST_SYSTEM_ADMIN` rule (Stage 6 #9);
  - explain why a malformed id is a 400 (Stage 6 #11);
  - give Jenkins its context (Stage 6 #12).
- **Goal 3:** it refers to "the screen the contract names", but this console's own table assigns the screen (Stage 6 #14).
- **Reuse:** say which files start as copies of `cce-insights-ui` (Stage 5 N5).

**A-27. Structure Nits** (Stage 7 S-4 to S-9).
- Give each screen its own H4, and nest the tabs.
- Make the Alternatives headings consistent.
- Renumber CUJ 4-6 to match S4-S6 in the admin-service design.
- Split the sync-diagram edges.
- Use one heading case and remove the stray horizontal rule.
- Put a blank line before each list.

**A-28. Style Nits in the feature range** (Stage 7 ST-15 to ST-31, except ST-25, which moved to A-13).
- Wording, vague durations, repeated sentences, and one timestamp format.
- Give exact message strings.
- Split the dense browser-tests paragraph into a list.

**A-29. Plan-level Nits** (Stage 9 IR-8 to IR-14 and ST-6 to ST-23).
- **Missing page behaviours:**
  - paging on the Roles list;
  - a filter change resets the page;
  - `MenuCheckboxTree` shows path and type;
  - `MenuForm` versus `ConfirmDialog`.
- **Menus UI choices.**
- **TypeScript config:** `noEmit`, `tsBuildInfoFile`, `skipLibCheck`, jest-dom types.
- **Coverage `exclude`** keeps Vitest's defaults.
- **Test support:** reset the fake-auth spies; add `createWrapper()`; let `createQueryClient` take options.
- **`describeError`:** give it its context option, and define `DescribedError`.
- **Naming and wording** fixes.

**A-30. Decisions the author should make or confirm.** Each lists the Stage 8 position.
- **Unsaved-changes guard** (Stage 5 N6): keep it, or drop it and keep the template's `BrowserRouter`. Stage 8: do not widen it.
- **Menu icon select** (Stage 5 N1): Stage 8 chose the ten seed icons plus the entry's current value.
- **Runtime `/config.json`** (Stage 5 N8): Stage 8 dropped it as a PRD §8.3 deviation with no requirement behind it.
- **Self-lockout warning** (Stage 7 D-17): Stage 8 dropped it, because `LAST_SYSTEM_ADMIN` covers the catastrophic case.
- **Deployment topology** (Stage 7 D-18): state that the console needs a dedicated origin that also routes `/auth`, or record the base-path alternative.
- **Unsaved-changes guard coverage and a real-stack F-04 check** (Stage 7 D-20 bullets 2-3): Stage 8 said not to expand either.

---

## Items for the admin-service design (outside this file)

These came up while the console was checked against `cce-admin-service.md`. They are not fixed by editing `cce-admin-ui.md`.

1. **Lines 550-551 contradict flow 5.1.** They say a `POST /users` 502 can follow a saved local row, but step 6 is a single transaction. (Stage 5 M1, A-04.)
2. **The 409 message for a username or email taken only in Keycloak** should say so (A-04).
3. **Follow-up:** `If-Match` and a 412 on the full-set replace endpoints (A-12).
4. **Audit IP.** The service takes the first `X-Forwarded-For` value and trusts the header by default (line 1006). Behind Caddy and an ingress this records the wrong IP or one a client can forge. (A-13.)
5. **AD-03 adds menus to the sidebar.** It makes Practitioners, Intelligence and Exports active `NAV` menus, but `cce-insights-ui` today reaches them by URL only. Check this against the rule that the menu seed matches the current navigation. (Stage 5, Part 3.)
6. **New gateway question:** the shape of the gateway's own 401 and 403 bodies (A-06).

## Traceability Matrix

Raw ids carry their stage prefix, because Stages 7 and 9 both use `ST-`.

| Raw finding | Severity | Action |
| --- | --- | --- |
| S5-M1 | Major | A-04 |
| S5-M2 | Major | A-03 |
| S5-M3 | Major | A-09 (Option A taken, Option B dropped) |
| S5-M4 | Major | A-10 |
| S5-M5 | Major | A-02 (dropped; text-only route taken) |
| S5-M6 | Major | A-12 |
| S5-N1 | Nit | A-30 |
| S5-N2 | Nit | A-08 |
| S5-N3 | Nit | A-05 |
| S5-N4 | Nit | A-14 |
| S5-N5 | Nit | A-26 |
| S5-N6 | Nit | A-30 |
| S5-N7 | Nit | A-22 |
| S5-N8 | Nit | A-30 (dropped) |
| S5-N9 | Nit | A-01 (dedupe only) |
| S6-#1 | Major | A-02 |
| S6-#2 | Major | A-03 |
| S6-#3 | Major | A-02 |
| S6-#4 | Major | A-02 |
| S6-#5 | Nit | A-15 |
| S6-#6 | Nit | A-15 |
| S6-#7 | Nit | A-26 |
| S6-#8 | Nit | A-26 |
| S6-#9 | Nit | A-26 |
| S6-#10 | Nit | A-05 |
| S6-#11 | Nit | A-26 |
| S6-#12 | Nit | A-26 |
| S6-#13 | Nit | A-16 |
| S6-#14 | Nit | A-26 (line 92), A-22 (line 1780) |
| S7-D-1 | Blocker | A-01 |
| S7-D-2 | Major | A-09 |
| S7-D-3 | Major | A-06 |
| S7-D-4 | Major | A-06 (timeout tuning moved to A-01) |
| S7-D-5 | Major | A-07 (counter dropped) |
| S7-D-6 | Major | A-13 |
| S7-D-7 | Major | A-12 (diff dialog dropped) |
| S7-D-8 | Major | A-13, admin-service item 4 |
| S7-D-9 | Major | A-03 |
| S7-D-10 | Major | A-05 (Retry button dropped) |
| S7-D-11 | Major | A-11 |
| S7-D-12 | Major | A-04, A-05 |
| S7-S-1 | Major | A-15 |
| S7-S-2 | Major | A-15 |
| S7-S-3 | Major | A-15 |
| S7-ST-1 | Major | A-14 |
| S7-ST-2 | Major | A-14 |
| S7-ST-3 | Major | A-09 |
| S7-ST-4 | Major | A-14 |
| S7-ST-5 | Major | A-02 |
| S7-ST-6 | Major | A-14 |
| S7-ST-7 | Major | A-14 |
| S7-ST-8 | Major | A-06 |
| S7-ST-9 | Major | A-08 |
| S7-ST-10 | Major | A-05 |
| S7-ST-11 | Major | A-05 |
| S7-ST-12 | Major (Nit per Stage 8) | A-14 |
| S7-ST-13 | Major | A-02 |
| S7-ST-14 | Major | A-14 |
| S7-D-13, D-14, D-15 | Nit | A-26 |
| S7-D-16 | Nit | A-01 |
| S7-D-17, D-18 | Nit | A-30 |
| S7-D-19 | Nit | A-18 |
| S7-D-20 | Nit | A-08 (bullet 1), A-30 (bullets 2-3) |
| S7-S-4 to S-9 | Nit | A-27 |
| S7-ST-15 to ST-24, ST-26 to ST-31 | Nit | A-28 |
| S7-ST-25 | Nit | A-13 |
| S8-Z8-1 | Major | A-02, A-04, A-12 |
| S8-Z8-2 | Major | A-09 |
| S8-Z8-3 | Major | A-12 |
| S8-Z8-4 | Major | A-11 |
| S8-Z8-5 | Major | A-05 |
| S8-Z8-6 | Major | A-08 |
| S8-Z8-7 | Major | A-07 |
| S8-Z8-8 | Major | A-06 |
| S8-Z8-9 | Major | A-03 |
| S8-Z8-12 | Major | A-01 |
| S8-Z8-10 | Nit | A-13, A-30 |
| S8-Z8-11 | Nit | A-01 |
| S8-Z8-13 | Nit | A-09, A-15 |
| S8-Z8-14 | Nit | A-14, A-22, A-26, A-30 |
| S9-IR-1 | Major | A-16 |
| S9-IR-2 | Major | A-17 |
| S9-CD-1 | Major | A-17 |
| S9-CD-2 | Major | A-17 |
| S9-IR-3 | Major | A-17 |
| S9-IR-4 | Major | A-18 |
| S9-IR-5 | Major | A-19 |
| S9-IR-6 | Major | A-11 |
| S9-CD-3 | Major | A-20 |
| S9-CD-4 | Major | A-21 |
| S9-CD-5 | Major | A-22 |
| S9-CD-6 | Major | A-10 |
| S9-ST-1 | Major | A-23 |
| S9-ST-2 | Major | A-24 |
| S9-ST-3, ST-4, ST-5 | Major | A-25 |
| S9-IR-7 | Nit | A-08 |
| S9-IR-8 to IR-14 | Nit | A-29 |
| S9-IR-15 | Nit | A-22 |
| S9-CD-7 | Nit | A-05 |
| S9-ST-6 to ST-23 | Nit | A-29 |

## What happens next

This run applied no fixes. To act on these items, run `egm-resolve` on `docs/designs/cce-admin-ui.md`:
- A-01 to A-15 and A-26 to A-28 apply to the FeatureRange.
- A-16 to A-25 and A-29 apply to the DetailedRange.

Those runs classify each item as PROPOSE FIX or CONTEST, then apply the approved edits. A-30 and the admin-service items need the author's decision first.
