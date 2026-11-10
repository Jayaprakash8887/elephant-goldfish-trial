Status: resolved

# Goldfish Annotated Review Report (Round 2)

**Target:** `docs/designs/cce-admin-ui.md` (`full file`; findings are in the Detailed
Implementation Plan, lines 1111-2104 at review time, with a few FeatureRange touch points)

**Findings:** the 11 Majors and 23 Nits in [stage9_round2_report.md](stage9_round2_report.md).
The round-1 report is kept in [goldfish_review_report.md](goldfish_review_report.md).

**Author decisions given with the request:**
- **IR-2:** keep `recommendedTypeChecked`. Fire-and-forget promises are called with `void`, and
  `@typescript-eslint/no-misused-promises` is set to `checksVoidReturn: { attributes: false }`.
- **Every other finding:** taken as the report proposes. Where a fix needs a trade-off, the option
  with fewer moving parts is chosen and named below.
- **Gate:** "auto accept" is pre-approved for this round.

**Categories:** 34 PROPOSE FIX, 0 CONTEST, 0 ESCALATE, 0 DECLINE.

**Resolution (2026-11-10):** auto accept, applied in two turns. The first turn stopped at the
account's usage limit after writing this report and editing only the feature range: Goal 7
(IR-5), the step sentence above the exit-check table (IR-6), the reset mechanisms in the
Technical Plan (IR-3), the held-roles rule on User edit (IR-8) and the spelling (ST-14). It had
marked the report resolved too early. The second turn checked the target item by item and
applied the rest: the Detailed Implementation Plan was rewritten with every remaining fix, and
two Technical Plan paragraphs were aligned (client functions, confirm-dialog texts). All 34
fixes are now in the target. Contested: 0. Escalated: 0. Declined: 0. Unaddressed: 0.

## Prioritized Findings & AI Suggestions

### [Issue 1] IR-1 [`egm-implementation-readiness`, `egm-codebase-details`, `egm-style`] - Severity: Major
*   **Affected Scope:** [Lines 1122-1133, 1263-1266](../../docs/designs/cce-admin-ui.md#L1122-L1266) (Order of work, item 7)
*   **Discrepancy Description:** Step 2 creates about 15 files that none of its tests reach, so the global coverage thresholds fail at step 2.
*   **Rationale:** `npm run test:ci` fails at the checkpoint; the implementer must decide alone.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid. Moving items to the first step whose tests use them has fewer moving parts than adding unit tests only to reach a number.
*   ***Proposed Edit:***
    *   Location: Order of work.
    *   Change:
        *   Step 2 keeps only what its tests reach. `LoadingSpinner` is the only item-65 file in step 2.
        *   To step 3: `useUnsavedChangesGuard`, `format`, `ConfirmDialog`, `SyncStatusBadge`, `Pager`, and the other six item-65 files.
        *   To step 5: `useDebouncedValue` and `StatusBadge`.
        *   `api/` modules, hook files and `rules.ts` gain each function or rule in the step whose tests use it.
        *   `queryKeys` stays in step 2 and is checked by `queryClient.test.ts` (CD-2). `NotFoundPage` is covered by a routing case in `AppLayout.test.tsx`. `InlineError` gets its own test in step 2.
        *   Reword: "`test:ci` enforces the total thresholds at every step; each step adds tests for the files it creates."

### [Issue 2] IR-2 [`egm-implementation-readiness`] - Severity: Major
*   **Affected Scope:** [Lines 1283-1287](../../docs/designs/cce-admin-ui.md#L1283-L1287) (item 9), items 19, 26, 58, 81, forms
*   **Discrepancy Description:** `recommendedTypeChecked` rejects a floating `bootstrap(...)`, `logout` passed to a void callback, `onSubmit={handleSubmit(fn)}`, and `unbound-method` in tests.
*   **Rationale:** `npm run lint` fails from step 2.
*   ***AI Suggestion:*** PROPOSE FIX (author decision)
*   ***Justification:*** The preset catches real promise bugs; the standard convention satisfies it.
*   ***Proposed Edit:***
    *   Location: item 9, item 19, item 81.
    *   Change:
        *   Item 9 names `'@typescript-eslint/no-misused-promises': ['error', { checksVoidReturn: { attributes: false } }]` for `src/**`, and `'@typescript-eslint/unbound-method': 'off'` for test files only.
        *   Convention: a promise that is not awaited is called with `void`. Item 19 is `void bootstrap(...)`, and item 81 passes `onSignOut={() => void logout()}`.

### [Issue 3] IR-3 [`egm-implementation-readiness`] - Severity: Major
*   **Affected Scope:** [Lines 1597-1599, 1784-1789, 1811-1815, 1895-1899](../../docs/designs/cce-admin-ui.md#L1597-L1899)
*   **Discrepancy Description:** No mechanism for when forms reset after a refetch; user forms must keep values after a 502 while the Permissions form must reset.
*   **Rationale:** Each obvious mechanism causes a bug in one of the two cases.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid. The reviewer's mechanism is the one with fewest moving parts: no effect on prop change anywhere.
*   ***Proposed Edit:***
    *   Location: Technical Plan (`SyncFailedNotice` resubmitting rule, Permissions tab), items 69, 70, 75, 85, 89.
    *   Change:
        *   **User forms:** `defaultValues` are read once at mount. `onSubmit` returns `Promise<User>`, and on resolve the form calls `reset(toFormValues(user))`. On reject it keeps its values. No `values` prop, and no effect on prop change.
        *   **Permissions:** `PermissionsEditor` is rendered with `key={resetKey}` and `defaultValues` from the fetched rows. The tab bumps `resetKey` only after it has refetched the rows following a 2xx save or a `ROLE_PERMISSIONS_SAVE` 502.
        *   **Re-sync:** allowed while the form has unsaved edits. It does not bump `resetKey`, so the edits stay.

### [Issue 4] IR-4 [`egm-implementation-readiness`, `egm-codebase-details`] - Severity: Major
*   **Affected Scope:** [Lines 2088-2099](../../docs/designs/cce-admin-ui.md#L2088-L2099) (Verification Plan)
*   **Discrepancy Description:** With the service in compose, port 8085 is bound to `127.0.0.1`, so the container's `host.docker.internal:8085` cannot reach it.
*   **Rationale:** The smoke check fails under one of the two start options.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid. Requiring the host-run service has fewer moving parts than naming the compose network (whose name depends on the project directory).
*   ***Proposed Edit:***
    *   Location: Verification Plan, last bullet.
    *   Change: "This check needs the service started on the host with `./mvnw spring-boot:run` (admin-service §9.3), which listens on all interfaces; under `--profile app` the service's port is bound to `127.0.0.1` and the container cannot reach it."

### [Issue 5] IR-5 [`egm-implementation-readiness`] - Severity: Major
*   **Affected Scope:** [Lines 1422, 1966](../../docs/designs/cce-admin-ui.md#L1422-L1966)
*   **Discrepancy Description:** "Only the four variables read" has no stated proof method.
*   **Rationale:** A weak test would pass without proving that no bypass can be switched on.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid. A source scan in the existing test file has fewer moving parts than a custom lint rule.
*   ***Proposed Edit:***
    *   Location: Goal 7, item 24, test table.
    *   Change: `config.test.ts` reads every non-test file under `src/` (Node `fs`, `// @vitest-environment node`) and asserts:
        *   that `import.meta.env` occurs only in `src/config.ts`;
        *   that the keys read there are exactly the four §8.3 names.

        A second case stubs `VITE_AUTH_ENABLED=false` and `VITE_AUTH_TOKEN=x` and asserts that `readConfig()` returns the same result.

### [Issue 6] CD-1 [`egm-codebase-details`, `egm-implementation-readiness`] - Severity: Major
*   **Affected Scope:** [Lines 1134-1140, 1982](../../docs/designs/cce-admin-ui.md#L1134-L1982)
*   **Discrepancy Description:** A step 3 test asserts a notice that the step 4 component renders.
*   **Rationale:** Step 3's checkpoint cannot pass.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid.
*   ***Proposed Edit:***
    *   Location: test table, Order of work.
    *   Change: `RolesListPage.test.tsx` asserts only the navigation to `/roles/{id}?tab=permissions` and the navigation state. The notice assertion moves to `RolePermissionsTab.test.tsx` in step 4.

### [Issue 7] CD-2 [`egm-codebase-details`, `egm-implementation-readiness`] - Severity: Major
*   **Affected Scope:** [Lines 1592-1599](../../docs/designs/cce-admin-ui.md#L1592-L1599) (item 37), test table
*   **Discrepancy Description:** The retry and refetch rules have no direct test.
*   **Rationale:** A retry on a 4xx or a refetch after a 409 would go unnoticed.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid. The test lives next to the module, in `src/hooks/__tests__/`.
*   ***Proposed Edit:***
    *   Location: test table.
    *   Change: add `src/hooks/__tests__/queryClient.test.ts`, step 2. It covers:
        *   retry on status 0 and on a 503;
        *   no retry on a 400 or a 409;
        *   a refetch after a 2xx and after a 502;
        *   no refetch after a 409 with the envelope;
        *   the `queryKeys` functions.

### [Issue 8] ST-1 [`egm-style`, `egm-implementation-readiness`] - Severity: Major
*   **Affected Scope:** [Lines 1961-1986](../../docs/designs/cce-admin-ui.md#L1961-L1986) (test table)
*   **Discrepancy Description:** Several read endpoints have no error case although the sentence promises one per endpoint.
*   **Rationale:** Re-opened round-1 CD-4.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid; one row each is cheap.
*   ***Proposed Edit:***
    *   Location: test table.
    *   Change: add these error cases:
        *   `GET /roles` 500 (role list shows `InlineError` with Retry) in `UserCreatePage` and `UserEditPage`;
        *   `GET /menus` 500 and `GET /roles/:id/menus` 403 in `RoleMenusTab`;
        *   `GET /roles/:id/permissions` 403 in `RolePermissionsTab`;
        *   `GET /menus` 500 in `MenusPage`.

### [Issue 9] ST-2 [`egm-style`] - Severity: Major
*   **Affected Scope:** [Lines 1946-1954, 2084](../../docs/designs/cce-admin-ui.md#L1946-L2084)
*   **Discrepancy Description:** "Proves each rule fires" claims more than six fixtures check.
*   **Rationale:** The auth group, `@playwright/test` and others are untested.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid. Adding one fixture (Playwright in `src/auth/`) and rewording makes the claim exact with few files.
*   ***Proposed Edit:***
    *   Location: item 96, Verification Plan.
    *   Change: seven fixtures, each mapped to its rule:
        *   rule 1: `pages/importsApi`;
        *   rule 2: `components/importsHook` and `components/importsQuery`;
        *   rule 3: `utils/importsKeycloak`;
        *   rule 4: `utils/importsMsw` and `auth/importsPlaywright`;
        *   allowed: `pages/importsContractType`.

        Wording: "checks one representative violation of each of the four rules, in the groups listed, and one allowed type import".

### [Issue 10] ST-3 [`egm-style`] - Severity: Major
*   **Affected Scope:** Lines 406, 941, 1279, 1382, 1946, 1976, 2083
*   **Discrepancy Description:** Four, five or six import rules.
*   **Rationale:** Inconsistent counts.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid.
*   ***Proposed Edit:***
    *   Location: as listed.
    *   Change: "four import rules" everywhere; item 9 says they are configured through five non-overlapping file groups; fixtures as in Issue 9.

### [Issue 11] ST-4 [`egm-style`] - Severity: Major
*   **Affected Scope:** Lines 1279-1923 (informal names)
*   **Discrepancy Description:** Earlier sections cited by informal names without links.
*   **Rationale:** The implementer must search for the rule meant.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Valid.
*   ***Proposed Edit:***
    *   Location: Detailed Implementation Plan.
    *   Change: each informal name becomes an anchor link (Start-up, API client, refetch rule, kinds table, Forms and validation, `SyncFailedNotice`, Users list, Roles list, Menus catalog, Permissions tab).

### [Issue 12] IR-6 [`egm-implementation-readiness`] - Severity: Nit
*   **Affected Scope:** Lines 1121-1138
*   **Discrepancy Description:** Exit-check rows proved in later steps; items 22-23 listed twice.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Cheap and removes ambiguity.
*   ***Proposed Edit:***
    *   Change:
        *   Add a "Step" column to the exit-check table.
        *   Step 2 lists "items 19 to 21, 24 to 30".

### [Issue 13] IR-7 [`egm-implementation-readiness`] - Severity: Nit
*   **Affected Scope:** Lines 1530-1543
*   **Discrepancy Description:** Empty or non-envelope 2xx bodies unspecified.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** One rule, one test.
*   ***Proposed Edit:***
    *   Change: a 2xx other than 204 whose body is not a JSON object with `data` throws `ApiError` (`CLIENT_UNEXPECTED_RESPONSE`, that status). Add a `client.test.ts` case.

### [Issue 14] IR-8 [`egm-implementation-readiness`, `egm-codebase-details`] - Severity: Nit
*   **Affected Scope:** Items 66, 68, 69, 84
*   **Discrepancy Description:** Where the assignable filter applies; held roles not shown; no fallback text; no test.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** A silent role removal through a full-set PUT is a real bug.
*   ***Proposed Edit:***
    *   Change:
        *   `useAllRoles` returns every role; `RoleCheckboxList` and `UserFilters` show only `isAssignable` roles.
        *   `UserRolesForm` keeps held ids that have no checkbox, lists them read-only under "Also held:", and always includes them in the `PUT`.
        *   `roleErrorMessages` falls back to "Role {id}".
        *   Test: `CCE_ADMIN_SERVICE` is not offered; a held role that is not shown is kept in the request.

### [Issue 15] IR-9 [`egm-implementation-readiness`, `egm-codebase-details`] - Severity: Nit
*   **Affected Scope:** Items 28, 39-42, 45, 48, 64-66, 70
*   **Discrepancy Description:** Signatures, props, mutation variables, `normalizePagingParams`, name clash.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Removes implementer choices.
*   ***Proposed Edit:***
    *   Change:
        *   Typed signatures for `applyServerErrors`. A field matches by exact path or by prefix plus `.`.
        *   `ApiError` constructor.
        *   Every mutation hook takes no argument and receives its variables in `mutate` (`{ id, body }`, `{ body }` or `{ id }`).
        *   `normalizePagingParams` lives in `usePagingParams.ts`, with a signature; invalid URL values are rewritten with `replace: true`.
        *   Props are given for items 65 and 70, and for `UserFilters`, which is controlled; the page holds the draft text and debounces it.
        *   The `Pagination` component is renamed `Pager`.

### [Issue 16] IR-10 [`egm-implementation-readiness`] - Severity: Nit
*   **Affected Scope:** Items 20, 61, 65, pages
*   **Discrepancy Description:** UI texts that tests assert are not fixed.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Tests need exact strings.
*   ***Proposed Edit:***
    *   Change: a "Fixed UI texts" table gives:
        *   the start-up titles;
        *   the confirm-dialog titles and bodies for status, set-password email, role delete, and menu deactivate and activate;
        *   the row success messages;
        *   the `NotFoundPanel` texts.

### [Issue 17] IR-11 [`egm-implementation-readiness`] - Severity: Nit
*   **Affected Scope:** Item 5
*   ***AI Suggestion:*** PROPOSE FIX
*   **Discrepancy Description / Justification:** "Same strictness" leaves `module` at its default.
*   ***Proposed Edit:***
    *   Change: spell out target ES2022, lib `["ES2023"]`, module ESNext, moduleResolution bundler, and the strict flags.

### [Issue 18] IR-12 [`egm-implementation-readiness`] - Severity: Nit
*   **Affected Scope:** Item 93
*   **Discrepancy Description:** Spies untyped.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Typed spies make the `satisfies` check cover signatures.
*   ***Proposed Edit:***
    *   Change: `vi.fn<typeof Real.initAuth>(...)` etc., with `import type * as Real from '../auth/keycloak'`.

### [Issue 19] IR-13 [`egm-implementation-readiness`] - Severity: Nit
*   **Affected Scope:** Items 7, 96 test
*   **Discrepancy Description:** Time zone and lint-test timeout.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Proposed Edit:***
    *   Change: `vitest.config.ts` sets `process.env.TZ = 'UTC'` at the top; `lintRules.test.ts` has a 60000 ms timeout.

### [Issue 20] CD-3 [`egm-codebase-details`, `egm-implementation-readiness`] - Severity: Nit
*   **Affected Scope:** e2e items
*   **Discrepancy Description:** Leftover e2e roles eventually push new ones past 500.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** A pre-check is one call.
*   ***Proposed Edit:***
    *   Change: global setup reads `GET /roles?pageSize=1`. If `totalItems` is above 400, it stops with the reset command. The README says the same.

### [Issue 21] CD-4 [`egm-codebase-details`] - Severity: Nit
*   **Affected Scope:** Item 18
*   ***AI Suggestion:*** PROPOSE FIX
*   **Discrepancy Description / Justification:** The README's run command omits `KEYCLOAK_ORIGIN`.
*   ***Proposed Edit:***
    *   Change: the README says to pass `-e KEYCLOAK_ORIGIN=<origin>` when `VITE_KEYCLOAK_URL` is on another origin.

### [Issue 22] CD-5 [`egm-codebase-details`] - Severity: Nit
*   **Affected Scope:** Item 100
*   ***AI Suggestion:*** PROPOSE FIX
*   **Discrepancy Description / Justification:** Stems have no character or length rule.
*   ***Proposed Edit:***
    *   Change: stems are 1 to 10 upper-case letters or digits, lower-cased for usernames and emails.

### [Issue 23] ST-5 [`egm-style`] - Severity: Nit
*   ***AI Suggestion:*** PROPOSE FIX
*   **Discrepancy Description / Proposed Edit:** Drop "unless its path says otherwise" and say "No existing file is modified" once.

### [Issue 24] ST-6 [`egm-style`] - Severity: Nit
*   ***AI Suggestion:*** PROPOSE FIX
*   **Discrepancy Description / Proposed Edit:** Step 7 cites admin-service §9.3 for starting the stack.

### [Issue 25] ST-7 [`egm-style`] - Severity: Nit
*   ***AI Suggestion:*** PROPOSE FIX
*   **Discrepancy Description / Proposed Edit:** Interim text becomes "Permissions are not available yet.", marked temporary and removed in step 4.

### [Issue 26] ST-8 [`egm-style`] - Severity: Nit
*   ***AI Suggestion:*** PROPOSE FIX
*   **Discrepancy Description / Proposed Edit:** "In this list, 'test files' means `**/*.test.{ts,tsx}` and `src/test/**`."

### [Issue 27] ST-9 [`egm-style`] - Severity: Nit
*   ***AI Suggestion:*** PROPOSE FIX
*   **Discrepancy Description / Proposed Edit:** Drop the "starts as a copy" sentence from item 17.

### [Issue 28] ST-10 [`egm-style`] - Severity: Nit
*   ***AI Suggestion:*** PROPOSE FIX
*   **Discrepancy Description / Proposed Edit:** Template "Configuration error: {name} is not set."

### [Issue 29] ST-11 [`egm-style`] - Severity: Nit
*   ***AI Suggestion:*** PROPOSE FIX
*   **Discrepancy Description / Proposed Edit:** "used with 300 ms".

### [Issue 30] ST-12 [`egm-style`] - Severity: Nit
*   ***AI Suggestion:*** PROPOSE FIX
*   **Discrepancy Description / Proposed Edit:** "highest sibling `sortOrder` + 10; 10 when there are no siblings; capped at 10000".

### [Issue 31] ST-13 [`egm-style`] - Severity: Nit
*   ***AI Suggestion:*** PROPOSE FIX
*   **Discrepancy Description / Proposed Edit:** Name each item-65 and item-70 file next to its description.

### [Issue 32] ST-14 [`egm-style`] - Severity: Nit
*   ***AI Suggestion:*** PROPOSE FIX
*   **Discrepancy Description / Proposed Edit:**
    *   Numbers without separators (30000).
    *   American spelling throughout ("behavior", "labeled").
    *   "again" instead of "afresh".

### [Issue 33] ST-15 [`egm-style`] - Severity: Nit
*   ***AI Suggestion:*** PROPOSE FIX
*   **Discrepancy Description / Proposed Edit:** "Not synced yet: {displayName} ({name})" in CUJ 6's spec.

### [Issue 34] ST-16 [`egm-implementation-readiness`] - Severity: Nit
*   ***AI Suggestion:*** PROPOSE FIX
*   **Discrepancy Description / Proposed Edit:** Items 65, 70 and 95 give each file's full path.
