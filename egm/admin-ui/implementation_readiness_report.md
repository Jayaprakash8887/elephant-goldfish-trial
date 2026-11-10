# Goldfish Review Report: Stage 9, Implementation Readiness

**Target:** `docs/designs/cce-admin-ui.md` (DetailedRange, lines 979-1833)

**How this report was made.** The Stage 9 `egm-synthesizer` started its three reviewers in the background and returned before they finished. All three later reported straight to the main context. This report was compiled there instead of by the synthesizer, so the report content is the same but the path is different.
- **What was merged:** the three reviewer results, with duplicates merged.
- **Line checks:** the main context spot-checked the line numbers for the phasing (986-996), ESLint (1129-1138) and `RoleDetailPage` (1644-1648) findings.

## Reviewer Status

| Reviewer | Persona | Standard | Status |
| --- | --- | --- | --- |
| `implementation-readiness` | `agents/implementation_readiness.md` | `implementation-readiness-standard.md` | completed |
| `codebase-details` | `agents/codebase_details.md` | `implementation-readiness-standard.md` | completed |
| `style` | `agents/style.md` | none | completed |

Each reviewer read its persona and standard first. What each one then read:
- **implementation-readiness:** the admin-service design §3-§6, §9 and §10; `author-decisions.md`; and the PRD. It did not open PROVENANCE, the gateway migration doc, or the insights-ui architecture and developer-setup docs.
- **codebase-details:** every file on the reading list.
- **style:** the target, plus searches of the reading list.

**Verdict: not ready to implement yet.**

**What is solid:** the plan lists all 104 files, pins every version, and its pins match D-05 exactly. Codebase-details checked the contract against admin-service §4. It found that these all match:
- endpoints, DTO field names and status codes;
- the nine partial-failure operations;
- the pagination;
- the `Warning` and `PartialState` shapes.

**What blocks implementation:**
- The phases cannot each end in a state that builds.
- The ESLint layer rules, as written, either break `npm run lint` or are silently not enforced.
- Several screens leave choices to the implementer.

## Severity Summary

| Severity | Count |
| --- | --- |
| Blocker | 0 |
| Major | 17 |
| Nit | 30 |

Id prefixes: `IR-` implementation-readiness, `CD-` codebase-details, `ST-` style. Where reviewers overlapped, the finding is kept once under its main lens, with an "Also raised by" line, at the highest severity any reviewer gave it.

---

## Prioritized Findings (Blockers -> Major -> Nits)

### IR-1 The phases do not end buildable, and the file-to-milestone mapping is wrong - Severity: Major
* **Reviewer Lens:** `implementation-readiness`
* **Also raised by:** `codebase-details` (M3, M4), `style` (M1)
* **Affected Scope:** Lines 986-996; also 1083, 1107, 1151-1155, 1227-1233.
* **Discrepancy Description:**
  * **Step 1** says `npm run lint` and `npm run build` pass "on an empty app". They cannot:
    * `index.html` (1155) loads `/src/main.tsx`, which is file 19 in step 2.
    * `tsconfig.app.json` includes `src`, which is still empty, so `tsc -b` fails with "No inputs were found".
    * `vitest.config.ts` points to a `src/test/setup.ts` that does not exist yet.
    * `vite build` needs `VITE_KEYCLOAK_REALM`, but the step never copies `.env.example` to `.env`.
  * **Step 2:** "files 19 to 55 that F1 needs" leaves the subset to the reader. F1 also needs files outside 19-55: the shell and shared components (56-64), `AppLayout`, `StartupErrorScreen` and `NotFoundPage` (80, 81, 90), and MSW and test support (91-94).
  * **`routes.tsx`** (1230-1233) imports every page from F2-F5, so F1 cannot compile alone.
  * **Other gaps:**
    * Steps 3-6 list no files and no check.
    * "Hooks base" is not a term the design uses.
    * The plan never links the milestone exit-check table at design lines 806-829.
* **Rationale & Recommendation:**
  * Give each step its exact file numbers and test files.
  * Put a minimal `src/main.tsx` and `src/vite-env.d.ts` in step 1, and add `cp .env.example .env` to its check.
  * For `routes.tsx`, choose one of these:
    * create placeholder pages in F1 and replace them later;
    * add routes milestone by milestone.
  * End every step with `npm run lint && npm run build && npm run test:ci`, plus "the F*n* rows of 'How each milestone exit check is shown' pass".

### IR-2 The four ESLint layer rules overwrite each other in flat config - Severity: Major
* **Reviewer Lens:** `implementation-readiness`
* **Affected Scope:** Lines 1131-1138 (design 369-376).
* **Discrepancy Description:** All four bullets set the same rule, `@typescript-eslint/no-restricted-imports`, on file globs that overlap.
  * **How flat config treats this:** when several config objects set one rule for a file, the last one's options replace the earlier ones; they are not merged.
  * **The effect:** for `src/pages/**`, the `msw` ban would cancel the `api/`, `hooks/` and `keycloak-js` bans. Lint still passes, so Goal 6 and the layer rules are silently not enforced.
* **Rationale & Recommendation:**
  * Specify file groups that do not overlap, each with the full union of its `paths` and `patterns`:
    * `src/components/**`
    * `src/pages/**`
    * `src/auth/**`
    * other non-test `src/**`
    * tests and `e2e/**`
  * Add a lint fixture or test showing that each restriction fires.

### CD-1 The flat-config key for `eslint-plugin-react-hooks` 5.2.0 and the type-checked parser options are not given - Severity: Major
* **Reviewer Lens:** `codebase-details`
* **Also raised by:** `implementation-readiness` (Nit 13)
* **Affected Scope:** Line 1129.
* **Discrepancy Description:**
  * **Wrong preset.** In 5.2.0, `configs.recommended` is the old eslintrc format, which flat config rejects. The flat preset is `configs['recommended-latest']`.
  * **Missing parser options.** typescript-eslint's "recommended type-checked" needs `languageOptions.parserOptions` (`projectService: true`, `tsconfigRootDir`), and the plan does not give them.
* **Rationale & Recommendation:** Name `reactHooks.configs['recommended-latest']` and the exact `parserOptions`.

### CD-2 The lint rule rejects `playwright.config.ts` - Severity: Major
* **Reviewer Lens:** `codebase-details`
* **Also raised by:** `style` (M2)
* **Affected Scope:** Lines 1137-1138 against 1114-1122; design line 375.
* **Discrepancy Description:**
  * **The ban.** `@playwright/test` is banned everywhere except `src/test/**`, test files and `e2e/**`.
  * **The conflict.** `playwright.config.ts` sits at the root and must import `defineConfig` and `devices` from `@playwright/test`.
  * **The effect.** `npm run lint` fails, so the step 1 check and the Docker `verify` stage (1187) fail. That breaks Goal 8.
* **Rationale & Recommendation:** Add `playwright.config.ts` to the exception list at line 1137 and design line 375.

### IR-3 Components need the contract's constant arrays but may only import types from `api/` - Severity: Major
* **Reviewer Lens:** `implementation-readiness`
* **Also raised by:** `codebase-details` (M2)
* **Affected Scope:** Lines 1133-1134, 1279, 1310-1311, 1532-1535, 1553, 1578-1582 (design 370-372, 670-672).
* **Discrepancy Description:**
  * **The values.** `USER_STATUSES`, `SYNC_STATUSES` and `PERMISSION_METHODS` are runtime values in `contract.ts`.
  * **Who needs them:** `UserFilters` (status select), `UserStatusActions` and `PermissionsEditor` (method select).
  * **The conflict.** A value import fails lint, and copying the values breaks Goal 6's single source.
* **Rationale & Recommendation:** Choose one and write it down:
  * re-export the values from `src/schemas/rules.ts`;
  * exempt value imports from `**/api/contract`;
  * pass the values in as props from pages.

### IR-4 Nothing makes Caddy refuse to start when `GATEWAY_UPSTREAM` is empty - Severity: Major
* **Reviewer Lens:** `implementation-readiness`
* **Also raised by:** `codebase-details` (M7), `style` (M7)
* **Affected Scope:** Lines 1189-1200, 1822-1827 (design 846).
* **Discrepancy Description:**
  * **The gap.** `reverse_proxy {$GATEWAY_UPSTREAM}` with an empty value expands to a bare `reverse_proxy`. Caddy will probably accept that and return 502 for each request, instead of refusing to start. Codebase-details asks for this to be checked against Caddy 2.11.
  * **No check.** The Verification Plan never runs the image without the variable.
* **Rationale & Recommendation:**
  * Add an explicit guard in the final stage, for example: `CMD ["sh","-c","[ -n \"$GATEWAY_UPSTREAM\" ] || { echo 'GATEWAY_UPSTREAM is required' >&2; exit 1; }; exec caddy run --config /etc/caddy/Caddyfile"]`.
  * Add a verification step: `docker run` without the variable exits non-zero with that message.
  * Or remove the claim at design line 846.

### IR-5 The F1 claim "nothing renders before `initAuth()` resolves" has no test - Severity: Major
* **Reviewer Lens:** `implementation-readiness`
* **Also raised by:** `codebase-details` (M9, last bullet)
* **Affected Scope:** Lines 1111-1112, 1218-1225, 1714 (design 396-398, 816).
* **Discrepancy Description:**
  * **The claim.** Design line 816 says `keycloak.test.ts` shows this. That file's planned cases cover only the `init` options, the refresh timer and `refreshToken`.
  * **Where the gate lives.** It is in `main.tsx`, which is excluded from coverage and has no test.
  * **Also untested:** the start-up error paths, a missing realm and an `initAuth` rejection.
* **Rationale & Recommendation:**
  * Move the start-up logic to `src/bootstrap.tsx` with `start(rootElement)`, and keep `main.tsx` to one line.
  * Add `bootstrap.test.tsx` with three cases:
    * pending: nothing renders;
    * rejected: the start-up error text appears;
    * missing realm: the screen names `VITE_KEYCLOAK_REALM` and `initAuth` is never called.

### IR-6 `RoleDetailPage` leaves the Delete outcomes and not-found handling undecided - Severity: Major
* **Reviewer Lens:** `implementation-readiness`
* **Also raised by:** `codebase-details` (M8)
* **Affected Scope:** Lines 1644-1648 (design 506, 545, 640-642; admin-service 565, 689).
* **Discrepancy Description:** Item 86 uses `useDeleteRole`, but the plan does not say:
  * where the browser goes after a 204. Invalidating `['roles']` refetches `useRole(id)`, which then returns 404;
  * where a 502 `ROLE_DELETE` notice appears. The design's table puts it on the Roles list only;
  * where `ROLE_IN_USE` and `SYSTEM_ROLE_IMMUTABLE` appear.

  It also does not mention `NotFoundPanel` for `ROLE_NOT_FOUND`, although item 84 does this for users.
* **Rationale & Recommendation:**
  * Same root cause as critic D-11. Stage 8 (Z8-4) recommends removing Delete from Role detail, which settles this finding with a deletion.
  * If Delete stays, decide:
    * the navigation after a 204;
    * where a 502 notice goes;
    * inline 409s;
    * `NotFoundPanel` when `kind === 'notFound'`.
  * Then add `RoleDetailPage.test.tsx`.

### CD-3 Return types and envelope handling are left open for most `api/` functions - Severity: Major
* **Reviewer Lens:** `codebase-details`
* **Also raised by:** `style` (N11)
* **Affected Scope:** Lines 1328, 1339-1364.
* **Discrepancy Description:**
  * **Return types are missing** for `getUser`, `updateUser`, `changeUserStatus`, `replaceUserRoles`, `listRoles`, `getRole`, `createRole`, `updateRole`, `createMenu` and `updateMenu`.
    * `listRoles` needs `pagination` for the Roles table (design 624).
  * **The shapes that are given do not match:**
    * `{ items, pagination }`
    * `{ user, warnings }`
    * `{ data, warnings }`
    * the bare payload
* **Rationale & Recommendation:** Give every function's full signature in admin-service §4.5 terms, with one naming style for results that carry pagination and for results that carry warnings.

### CD-4 The test plan misses the PRD §11 bar of one error code per endpoint or screen - Severity: Major
* **Reviewer Lens:** `codebase-details`
* **Also raised by:** `style` (M5, a claim that the table does not back)
* **Affected Scope:** Lines 1708-1730 (PRD line 377).
* **Discrepancy Description:**
  * **No test file for `RoleDetailPage`.**
  * **No error-path test for:**
    * `PUT /roles/:id`;
    * `DELETE /roles/:id` (`ROLE_IN_USE`, 502 `ROLE_DELETE`);
    * `PUT /roles/:id/menus`;
    * `PUT /menus/:id`;
    * 502 `USER_PASSWORD_RESET`;
    * `EMAIL_IN_USE` on `PUT /users/:id`;
    * `ROLE_NOT_ASSIGNABLE` on create.
  * **Rows with no error code.** The `menuTree`, `permissions` and `useAllRoles` rows list none, yet line 1708 says every test file covers one.
* **Rationale & Recommendation:**
  * Add `RoleDetailPage.test.tsx` and the missing error cases.
  * Change line 1708 to "Each screen's test file covers the happy path and at least one error code".

### CD-5 The CUJ 6 browser test does not say which role the created user holds - Severity: Major
* **Reviewer Lens:** `codebase-details`
* **Also raised by:** `implementation-readiness` (Nit 16)
* **Affected Scope:** Lines 1759, 1794-1798 (admin-service 679: `roleIds` must have 1-20 entries).
* **Discrepancy Description:**
  * **A role is required.** The user cannot be created without one, and the plan does not name it.
  * **The obvious choice is harmful.** `SUPER_ADMIN` is the ready-made role, but it would leave extra super-admins in the shared local stack.
  * **Finding the row.** The test should find the row by `q`, because the default page shows 20 users.
* **Rationale & Recommendation:**
  * "Create a synced role through the API, then a user holding it", as CUJ 5 does (1790).
  * Search for the user by `q`.

### CD-6 The MSW seed's `syncStatus` for `SUPER_ADMIN` is unset, so the mocks would disagree with the service - Severity: Major
* **Reviewer Lens:** `codebase-details`
* **Affected Scope:** Lines 1695-1699; design 778-786 (admin-service 956, 1058).
* **Discrepancy Description:**
  * **What MSW does.** It decides `ROLE_NOT_SYNCED` from `syncStatus`.
  * **If the seed is copied as `NEVER`:** MSW refuses `SUPER_ADMIN`. The real service allows it, because its realm role comes from the realm import.
  * **If it is `SYNCED`:** the fixtures no longer match the seed.
* **Rationale & Recommendation:**
  * State the fixture values for `SUPER_ADMIN`.
  * Or drop the stateful rule, as Stage 5 M4 recommends, which removes this issue.

### ST-1 The start-up error text names a cause that may not be the real one - Severity: Major
* **Reviewer Lens:** `style`
* **Also raised by:** `codebase-details` (N1)
* **Affected Scope:** Lines 1224-1225, 1259-1260, 1714 (design 394-395, 413-414).
* **Discrepancy Description:**
  * **The plan:** `initAuth` rejects when `init` resolves without a session. Every rejection then shows "Cannot reach the sign-in service at {url}.", which is false when Keycloak answered.
  * **The design:** `initAuth()` "does not resolve without a session", so plan and design disagree.
* **Rationale & Recommendation:**
  * Make the plan and the design agree.
  * Then either give each cause its own message, or use a neutral one: "Sign-in did not complete with the sign-in service at {url}."

### ST-2 Badge labels and test expectations use different words - Severity: Major
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 1513, 1517-1518, 1729, 1776, 1782-1785, 1797-1798.
* **Discrepancy Description:**
  * **`SyncStatusBadge`:** it shows "Synced", "Never synced" and "Sync failed", but the tests expect `SYNCED` and `FAILED`.
  * **`StatusBadge`:** the plan does not say whether it shows `ACTIVE` or "Active". CUJ 6 expects `INACTIVE`, `LOCKED` and `ACTIVE`.
* **Rationale & Recommendation:** State each badge's visible text once, in items 61 and 62, and make every test assert that text.

### ST-3 "No MSW in `dist/`" claims more than the grep proves - Severity: Major
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 1187-1188, 1814.
* **Discrepancy Description:** The `verify` stage greps for `mockServiceWorker|setupServer`. A minifier can rename `setupServer`, so a clean grep does not show that MSW is absent.
* **Rationale & Recommendation:** Change line 1814 to name the two strings. Or grep for an MSW literal that survives minification.

### ST-4 The Docker check points Caddy at the service, which the design says must never happen - Severity: Major
* **Reviewer Lens:** `style`
* **Also raised by:** `codebase-details` (N6, as a Nit)
* **Affected Scope:** Line 1824 (design 845).
* **Discrepancy Description:** `GATEWAY_UPSTREAM=http://host.docker.internal:8085` is the admin service itself. Design line 845 says the upstream is "never the admin service directly (PRD §9)", and the plan does not mention the local exception, C-05.
* **Rationale & Recommendation:** Add "(locally there is no gateway, C-05; this points Caddy at the service for the check only)". Or reword line 845 to "in shared environments".

### ST-5 The Docker verification outcomes cannot be measured - Severity: Major
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 1826-1827.
* **Discrepancy Description:** "Returns `index.html`" and "reaches the service" give no observable pass condition.
* **Rationale & Recommendation:** For example:
  * `curl -s -o /dev/null -w '%{http_code}' …/users` prints 200, and the body contains `<div id="root">`;
  * `…/admin/v1/health` returns 200 with the service's health body.

---

### IR-7 The plan does not settle the refetch rule after a permissions-save 502 with no completed steps - Severity: Nit
* **Reviewer Lens:** `implementation-readiness`
* **Affected Scope:** Lines 1399-1401, 1729 (design 475, 481 against 695-698).
* **Discrepancy Description:**
  * **The conflict.** The invalidation table says to invalidate only when there are completed steps. Line 695 says to refetch after any 502.
  * **The plan.** Item 41 restates neither rule.
  * Same root as critic ST-9.
* **Rationale & Recommendation:** State the rule. Stage 8 Z8-6 (invalidate everything after any write) settles it.

### IR-8 Page items leave out behaviour the design specifies - Severity: Nit
* **Reviewer Lens:** `implementation-readiness`
* **Also raised by:** `style` (N16, N18, N19)
* **Affected Scope:** Lines 1411-1415, 1558-1561, 1575-1576, 1621-1625, 1639-1642, 1664-1665.
* **Discrepancy Description:**
  * `RolesListPage` leaves out `usePagingParams` and `Pagination` (design 624).
  * Items 44 and 82 do not say that a filter change resets `page` to 1 (design 578).
  * `RoleTable` has no props.
  * `MenuCheckboxTree` leaves out the path and type of each row (design 648).
  * Menus page: Add and Edit open `MenuForm`, and only Deactivate and Activate use `ConfirmDialog` (design 704-711). The plan reads as if all four use `ConfirmDialog`.
* **Rationale & Recommendation:** Add each missing behaviour to its item.

### IR-9 The Menus page leaves UI choices open - Severity: Nit
* **Reviewer Lens:** `implementation-readiness`
* **Affected Scope:** Lines 1604-1607, 1662-1665.
* **Discrepancy Description:** The plan does not say:
  * whether `MenuForm` is a modal, an inline panel or a separate area;
  * the default `sortOrder` and `isActive` for a new entry;
  * whether the parent select for a `DETAIL` entry lists inactive `NAV` entries.
* **Rationale & Recommendation:** Decide each one.

### IR-10 TypeScript config gaps - Severity: Nit
* **Reviewer Lens:** `implementation-readiness`
* **Also raised by:** `codebase-details` (N5)
* **Affected Scope:** Lines 1076-1091.
* **Discrepancy Description:**
  * `tsconfig.node.json` lacks `noEmit: true`. With `allowJs`, `tsc -b` could overwrite `eslint.config.js`.
  * No `tsBuildInfoFile` location is given.
  * No `skipLibCheck`.
  * `@testing-library/jest-dom` is listed in `types`, which may pull in Jest type references; `setup.ts` already imports `/vitest`.
* **Rationale & Recommendation:** Specify each setting.

### IR-11 The coverage `exclude` list replaces Vitest's defaults - Severity: Nit
* **Reviewer Lens:** `implementation-readiness`
* **Affected Scope:** Lines 1111-1112.
* **Discrepancy Description:** Setting `exclude` replaces Vitest's defaults, so test files and `__tests__` folders would count toward coverage.
* **Rationale & Recommendation:** Spread `coverageConfigDefaults.exclude`, or list the test-file patterns.

### IR-12 The fake auth spies are not reset between tests - Severity: Nit
* **Reviewer Lens:** `implementation-readiness`
* **Also raised by:** `codebase-details` (N4)
* **Affected Scope:** Lines 1673-1693.
* **Discrepancy Description:**
  * **No reset.** `setup.ts` resets MSW but not the `fakeAuth` spies, so a `refreshToken` that resolves `false` leaks into later tests.
  * **Export shape.** `vi.mock(..., () => import('./fakeAuth'))` needs named exports, and the plan does not say so.
  * **Retries.** `renderRoute` needs retries off, but `createQueryClient()` takes no options.
* **Rationale & Recommendation:**
  * Set `mockReset`/`restoreMocks`, or reset and reinstall the defaults in `afterEach`.
  * Require named exports.
  * Give `createQueryClient` an options parameter.

### IR-13 Hook tests have no render helper - Severity: Nit
* **Reviewer Lens:** `implementation-readiness`
* **Affected Scope:** Lines 1686-1690, 1721.
* **Discrepancy Description:** `renderWithProviders` offers only `renderRoute(path)`, so `useAllRoles.test.tsx` cannot render a hook.
* **Rationale & Recommendation:** Add `createWrapper()` for `renderHook` to file 93.

### IR-14 `describeError` has no context for its `notFound` special case - Severity: Nit
* **Reviewer Lens:** `implementation-readiness`
* **Also raised by:** `codebase-details` (N3), `style` (N12, `DescribedError` never defined)
* **Affected Scope:** Lines 1425-1428, 1492, 1496, 1716 (design 506).
* **Discrepancy Description:**
  * **Missing context.** The rule "field `id` becomes `notFound`" applies only to a detail page's fetch, but the function takes only `error`. A mutation's 400 would also become `notFound`.
  * **Undefined type.** Item 58 uses `DescribedError`, which item 46 never defines.
* **Rationale & Recommendation:**
  * Add a `{ detailFetch: true }` option, or drop the qualifier in the design.
  * Name the return type in item 46.

### IR-15 Browser-test details are left open - Severity: Nit
* **Reviewer Lens:** `implementation-readiness`
* **Also raised by:** `style` (N20)
* **Affected Scope:** Lines 1736-1761, 1794-1798 (design 817).
* **Discrepancy Description:**
  * **`setStubMode`:** a `PUT` with a plain-text body (admin-service §9.3). The plan does not say whether it is a fixture or a plain export.
  * **The `cce-e2e` password-grant form fields** are not given.
  * **The email format** for created users is not given.
  * **The `.env` path:** it does not say whether `../cce-admin-service/.env` resolves against `process.cwd()` or the file's location.
  * **Name mismatches:** `nameStem` and `stem` name the same thing.
  * **Header check:** design 817 says every CUJ test checks the header username, but only `auth.setup.ts` does.
* **Rationale & Recommendation:** Specify each item. Add the header check to each spec, or correct design 817.

### CD-7 `SyncFailedNotice` adds "Nothing was changed." to a `ROLE_SYNC` 502 - Severity: Nit
* **Reviewer Lens:** `codebase-details`
* **Affected Scope:** Line 1722 (design 531, 544; admin-service 812).
* **Discrepancy Description:**
  * **What the contract says.** A `ROLE_SYNC` 502 always has `completedSteps: []`, yet it sets `FAILED` and writes an audit row.
  * **What the notice shows.** It reads "The permissions were already saved…" followed by "Nothing was changed."
  * This is the same as critic ST-10 and Stage 5 N3, carried into the plan's test row.
* **Rationale & Recommendation:** Apply Stage 8 Z8-5's single empty-steps rule, with its `ROLE_SYNC` exception.

### ST-6 to ST-23 Wording and naming Nits - Severity: Nit
* **Reviewer Lens:** `style`

| Id | Lines | Issue | Fix |
| --- | --- | --- | --- |
| ST-6 | 981-982, 998 | "This folder" names no folder. The heading says "Create/Modify", but nothing is modified. | Use "repository root". Title the heading "Target Files to Create". |
| ST-7 | 982-984, 1014, 1061-1062 | Exact versions are stated three times. | Keep line 1014 and the install command. |
| ST-8 | 1098 | `changeOrigin: false` is cited to D-03, which fixes only the proxy target. | Give it its own reason. |
| ST-9 | 1131-1134 | The type-import scope (`**/api/*`) and the rule name differ from design 370-372. | Make the two places use the same scope and rule name. |
| ST-10 | 1206 | "Node 22.22 or 20.19+" does not match `engines` `>=20.19.0 <23`. | Quote the `engines` range. |
| ST-11 | 1260, 1714 | The refresh interval is written as "60 000 ms", "60 s" and "60-second". | Pick one form. |
| ST-12 | 1265-1266 | The redirect parenthetical reads as applying to both `login()` and `logout()`. | Place it after `logout()`. |
| ST-13 | 1271-1272 | The reason given for `useAuth` differs from the design's (UI-DEV-1). | Cite UI-DEV-1. |
| ST-14 | 1282 | `ErrorEnvelope.code: string` has no stated reason, so a reader may "fix" it. | Add "(`string`: unknown codes become `unexpected`)". |
| ST-15 | 1328-1329 | "As in the Technical Plan" includes `login()`-on-no-token, which that plan does not have. | Mark it as an addition, or add it to design 438-457. |
| ST-16 | 1386-1387, 1428, 1497, 1502, 1607, 1695, 1716 | Cross-references point to tables that have no name. | Link the design subsection headings. |
| ST-17 | 1544-1546 | The `RoleCheckboxList` sentence is ambiguous about when the badge shows. | "and, when not `SYNCED`, a badge and the hint". See also Z8-9. |
| ST-18 | 1551, 1631 | The prop `locked` collides with the `LOCKED` status. | Rename it `resubmitBlocked`. |
| ST-19 | 1582 | "Save enabled by `sameRuleSet`" states the logic backwards. | "enabled when `sameRuleSet(current, fetched)` is false". |
| ST-20 | 1682-1690, 1721, 1724, 1757-1761 | Test-support names: the default behaviour is not stated; the file `renderWithProviders` exports `renderRoute`; `useAllRoles.test.tsx` has no matching module; the two page-correction names are easy to confuse; "upper case" is applied to digits. | Make the names consistent and state the defaults. |
| ST-21 | 1699 | "409s, `LAST_SYSTEM_ADMIN`" repeats itself. | Drop the second term. |
| ST-22 | 1800-1802 | Item 104 is a run-time folder, but it is numbered as a file. | Move it out of the list. |
| ST-23 | 1806-1808, 1817 | "EGM Step 6" is process jargon, and "the C-02 message" is never defined. | Reword the first and quote the message. |

---

## Cross-stage notes

* **Same root as earlier stages:**
  * IR-6 is critic D-11 at plan level. Stage 8 Z8-4 settles both by removing Delete from Role detail.
  * IR-4 repeats critic D-19 and adds the missing verification step.
  * CD-6 follows from Stage 5 M4 (the stateful MSW store) and M2 (`syncStatus` treated as a promise).
  * IR-7 and CD-7 are critic ST-9 and ST-10 at plan level.
* **New at plan level:** IR-1, IR-2, CD-1, CD-2, IR-3, IR-5, CD-3, CD-4, CD-5 and ST-1 to ST-5. Nothing in the feature range shows these.
