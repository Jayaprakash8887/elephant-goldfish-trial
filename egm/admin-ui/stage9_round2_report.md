# Goldfish Review Report: Stage 9, Implementation Readiness (Round 2)

**Target:** `docs/designs/cce-admin-ui.md`, DetailedRange lines 1111-2104 (`## Detailed Implementation Plan` to the end). FeatureRange is lines 1-1110.

**Round:** second Stage 9 check, after the first review's 30 fixes and the contract alignment were applied. The first round's report is kept unchanged in `implementation_readiness_report.md`.

**How this report was made**
- **Dispatch:** the main context dispatched the three reviewers directly, with no synthesizer, and wrote this report itself.
- **Context:** no extra GoldfishContext was given. Each reviewer received only the eight files the design links to inside this folder:
  - `docs/designs/cce-admin-service.md`
  - `docs/designs/author-decisions.md`
  - the PRD
  - `context/PROVENANCE.md`
  - the gateway migration doc
  - the three `cce-insights-ui` docs
- **URLs:** the design links to no documentation URLs, so none were left out. The `http://localhost...` strings in the design are configuration values, not links.
- **Line checks:** the main context checked these line references against the file:
  - step 2's item list (1130-1133)
  - the global coverage thresholds (1263-1266)
  - `recommendedTypeChecked` (1283)
  - the `host-gateway` Docker check (2093-2096), against admin-service line 1214 ("Every published port is bound to `127.0.0.1`")
  - the "four import rules" wording (1279, 1382, 2083), against "one file per import rule" (1946)

## Reviewer Status

| Reviewer | Persona | Standard | Status |
| --- | --- | --- | --- |
| `implementation-readiness` | `agents/implementation_readiness.md` | `implementation-readiness-standard.md` | completed |
| `codebase-details` | `agents/codebase_details.md` | `implementation-readiness-standard.md` | completed |
| `style` | `agents/style.md` | none | completed |

Each reviewer read its persona (and standard, where there is one) first. What each one then read:
- **implementation-readiness:** the whole target, plus these parts of the reading list:
  - admin-service §3, §4, the §6 seed lines, and §7 to §10
  - `author-decisions.md`
  - PRD §6 to §12
  - the `cce-insights-ui` developer setup

  It did not read PROVENANCE, the gateway migration doc, or the insights architecture and deployment docs in full.
- **codebase-details:** every file on the reading list, in full.
- **style:** the target range only. It did not need the reading list.

## Verdict: not ready to implement yet. The round is not clean.

**What changed since round 1**
- **Count:** 0 Blockers, 11 Majors and 23 Nits, down from 0 Blockers, 17 Majors and 30 Nits. Fresh reviewers sample differently, so the drop in count alone does not prove the plan improved.
- **Confirmed by round 2:** codebase-details re-checked the contract mirror in full against admin-service §3, §4 and §9 and found no mismatch in:
  - endpoints and methods
  - field names and nullability
  - status codes, error codes and headers
  - query parameters, roles, Keycloak values, the stand-in URLs and the seed menus

  It also confirmed that all 11 dependencies, all 26 devDependencies and both images match D-05 exactly. The Caddyfile, Dockerfile and query-client defaults match the `cce-insights-ui` docs where the plan says it follows them. Implementation-readiness independently confirmed the D-05 pins and the `contract.ts` shapes.

**What still blocks implementation**
1. **Checkpoints:** the step checkpoints still cannot all pass as written:
   - Step 2 misses its own coverage gate.
   - The type-checked lint preset rejects code the plan itself specifies.
   - A step 3 test needs a step 4 component.
2. **Verification:** the Docker smoke check fails if the service runs under compose.
3. **Implementer choices:** two behaviours are still left to the implementer:
   - how forms reset or keep their values after a refetch;
   - how `config.test.ts` proves that only four variables are read.
4. **Test-plan claims:** several claims say more than the listed tests or fixtures check.

## Severity Summary

| Severity | Count |
| --- | --- |
| Blocker | 0 |
| Major | 11 |
| Nit | 23 |

Id prefixes: `IR-` implementation-readiness, `CD-` codebase-details, `ST-` style. Where reviewers overlapped, the finding is kept once under its main lens, with an "Also raised by" line, at the highest severity any reviewer gave it. Ids are new to this round. The "Round 1" line ties a finding to a round-1 finding at the same scope.

---

## Prioritized Findings (Blockers -> Major -> Nits)

### IR-1 Step 2 cannot pass its own coverage gate - Severity: Major
* **Reviewer Lens:** `implementation-readiness`
* **Also raised by:** `codebase-details` (Major 1), `style` (Major 1, on the wording)
* **Affected Scope:** Lines 1122-1124, 1130-1133 and 1263-1266; test table 1964-1986.
* **Round 1:** same scope as round-1 IR-1 (phases do not end buildable). That fix made the steps build. This is a different failure at the same checkpoint, and reverting the fix would not solve it, so it is not churn.
* **Discrepancy Description:**
  * **The claim:** line 1123 says "The coverage thresholds apply at every step, so each step's tests cover the files that step creates".
  * **The config:** the thresholds (80/80/80/70) are totals over all of `src/**` (1263-1266). Vitest 3.2.7 (D-05) with `include: ['src/**']` counts untested files as 0%.
  * **The gap:** step 2 creates about 15 files that none of its nine listed tests reach:
    * the 8 non-`me` key functions in `queryKeys`
    * the retry function and `MutationCache.onSettled` in `queryClient`
    * `useDebouncedValue` (item 44) and `useUnsavedChangesGuard` (46)
    * `format.ts` (49) and `rules.ts` (52)
    * `ConfirmDialog`, `StatusBadge`, `SyncStatusBadge` and `Pagination` (61-64)
    * most of the seven item-65 components
    * `NotFoundPage` (91)
  * **Estimate:** implementation-readiness puts function coverage near 50-60% at the end of step 2.
  * **Wording:** the style lens notes that the "so" promises per-file coverage the config does not enforce.
* **Rationale:** `npm run test:ci` fails at step 2's checkpoint. The implementer must decide alone whether to move items into steps 3-5 or to write tests the plan does not list. That breaks the Zero-Decision Rule and the Phasing & Checkpoints standard.
* **Reviewer guidance:** move items 36 (all but the `me` key), 44, 46, 49, 52, 61-64 and the unused item-65 components to the first step whose tests use them, or list unit tests for them in step 2. Reword line 1123, for example: "`test:ci` enforces the total thresholds at every step; each step adds tests for the files it creates."

### IR-2 The type-checked lint preset rejects code the plan specifies - Severity: Major
* **Reviewer Lens:** `implementation-readiness`
* **Affected Scope:** Lines 1283-1287 (item 9); 1388-1389 (item 19); 1442; 1717-1718; 1779-1782; forms in items 69, 72 and 80.
* **Round 1:** same scope as round-1 CD-1 (type-checked parser options not given) and IR-2 (ESLint rule blocks overwrite each other). The round-1 fix added `recommendedTypeChecked`. Dropping that preset would remove these errors, so this matches the review loop's churn test. Adding rule overrides fixes it equally well and keeps the round-1 fix. The author should choose.
* **Discrepancy Description:** `recommendedTypeChecked` turns on `no-floating-promises`, `no-misused-promises` (including JSX attributes) and `unbound-method`. Code the plan specifies breaks all three:
  * **Item 19:** calls `bootstrap(...)`, which returns a promise, without `await` or `void`. That is a floating promise.
  * **Items 26, 58 and 81:** pass `logout(): Promise<void>` to `onSignOut: () => void`. That is a misused promise.
  * **Every React Hook Form form:** uses `onSubmit={handleSubmit(fn)}`, which returns a promise in a void slot.
  * **`keycloak.test.ts`:** `expect(instance.updateToken)`-style assertions trigger `unbound-method`.
* **Rationale:** `npm run lint` fails from step 2 on. The plan gives no rule overrides and no coding convention, so the implementer must choose between relaxing rules and changing code.
* **Reviewer guidance:** in item 9, give the exact overrides, for example `no-misused-promises: ['error', { checksVoidReturn: { attributes: false } }]` and `unbound-method` off for test files. Write `void bootstrap(...)` in item 19. State how `logout` is passed, for example `() => void logout()`.

### IR-3 How forms reset or keep their values after a refetch is not specified, and two rules conflict - Severity: Major
* **Reviewer Lens:** `implementation-readiness`
* **Affected Scope:** Lines 1597-1599 (refetch rule); 1784-1789 (item 70); 1811-1815 (item 75); 1895-1899 (item 89).
* **Round 1:** same area as round-1 IR-7 (refetch after a permissions-save 502). That fix settled when to refetch, but not what the form does with the refetched data.
* **Discrepancy Description:**
  * **Two opposite outcomes:**
    * User forms must keep the values that were sent after a 502, even though the refetch rule reloads the user.
    * The Permissions form must reset to the refetched rows after a 502.
  * **No mechanism:** the form state lives inside `PermissionsEditor`, `UserProfileForm` and `UserRolesForm`. Their props (`explicitRows`, "the fetched user") do not say how the page triggers or blocks a reset. The options are a remount key, the React Hook Form `values` prop, or an effect on prop change.
* **Rationale:** each obvious choice causes a bug:
  * **The `values` prop, or reset on prop change:** breaks the resubmit rule on user forms. It also wipes unsaved permission edits whenever another 2xx mutation, such as Re-sync, invalidates every query.
  * **`defaultValues` only:** the Permissions form never shows the refetched rows after a `ROLE_PERMISSIONS_SAVE` 502.
* **Reviewer guidance:**
  * **User forms:** set `defaultValues` once, then call `reset(response)` in the mutation's `onSuccess`.
  * **Permissions:** the page bumps a `resetKey` only after the refetch that follows a 2xx or a `ROLE_PERMISSIONS_SAVE` 502.
  * **Re-sync:** say whether it is allowed while the Permissions form has unsaved edits.

### IR-4 The Docker smoke check cannot reach the service when the service runs under compose - Severity: Major
* **Reviewer Lens:** `implementation-readiness`
* **Also raised by:** `codebase-details` (Major 3)
* **Affected Scope:** Lines 2088-2099. Conflicts with admin-service lines 1214, 1225 and 1293 (§9.1 and §9.3).
* **Round 1:** same scope as round-1 ST-4 and ST-5. Those fixes settled pointing Caddy at the service (C-05) and made the outcomes measurable. This is a network problem those fixes did not touch.
* **Discrepancy Description:**
  * **Start options:** line 2088 says to start the service "as in admin-service §9.3", which allows `./mvnw spring-boot:run` or `docker compose --profile app up`.
  * **Port binding:** admin-service §9.1 binds every published port to `127.0.0.1`, including 8085 under `--profile app`.
  * **The address used:** the check runs Caddy with `GATEWAY_UPSTREAM=http://host.docker.internal:8085` and `--add-host=host.docker.internal:host-gateway`. That address is the Docker bridge gateway, not `127.0.0.1`.
* **Rationale:** with the compose option, `curl .../admin/v1/health` gets a Caddy 502 and the check fails. It passes only when the service runs on the host with `./mvnw spring-boot:run`, which listens on all interfaces.
* **Reviewer guidance:** either require the host-run service for this check, or run the console container on the compose network (`--network <project>_default`, `GATEWAY_UPSTREAM=http://admin-service:8085`).

### IR-5 The test for Goal 7's "only the four variables are read" claim has no stated method - Severity: Major
* **Reviewer Lens:** `implementation-readiness`
* **Affected Scope:** Lines 1422 (item 20) and 1966 (test table row for `config.test.ts`).
* **Discrepancy Description:**
  * **The claim:** `config.test.ts` must show "only the four variables read", and Goal 7 cites this as its check.
  * **No working method:**
    * `vi.stubEnv` cannot detect which keys are read.
    * Checking that `AppConfig` has four keys does not show that no other `import.meta.env.*` key, such as `VITE_AUTH_ENABLED`, is read.
* **Rationale:** the implementer has to design the proof. A weak version passes without proving that the auth bypass cannot be switched on.
* **Reviewer guidance:** name the mechanism. One option: stub `VITE_AUTH_ENABLED=false` and `VITE_AUTH_TOKEN=x` and assert that the result does not change. A stronger option: a lint rule or grep check that `import.meta.env` appears only in `src/config.ts`, with exactly the four keys.

### CD-1 A step 3 test needs a component that step 4 creates - Severity: Major
* **Reviewer Lens:** `codebase-details`
* **Also raised by:** `implementation-readiness` (Nit, under step boundaries)
* **Affected Scope:** Lines 1134-1140, 1880-1881, 1899 and 1982.
* **Discrepancy Description:**
  * **The test:** step 3 owns `RolesListPage.test.tsx` (line 1135), which includes "201 with `ROLE_SYNC_FAILED` opens the Permissions tab with the notice" (1982).
  * **The notice:** "The role was created but not synced…" is rendered by `RolePermissionsTab` (item 89, step 4).
  * **The placeholder:** until step 4, `RoleDetailPage` renders "Permissions are added in the next step." for `?tab=permissions` (1137).
* **Rationale:** this test case cannot pass at step 3's checkpoint.
* **Reviewer guidance:** in step 3, assert only the navigation to `/roles/:id?tab=permissions`. Add the notice assertion in step 4.

### CD-2 The query client's retry and refetch rules have no direct test - Severity: Major
* **Reviewer Lens:** `codebase-details`
* **Also raised by:** `implementation-readiness` (Nit, under test plan)
* **Affected Scope:** Lines 1592-1599 (item 37), 1928 and the test table 1964-1986.
* **Discrepancy Description:**
  * **The rules:** `createQueryClient` holds two of them:
    * retry once, and only on status 0 or 500 and above (UI-AD-18);
    * the mutation-cache refetch rule by error kind (F-06).
  * **No direct test:** no test file targets `queryClient.ts`.
  * **Retry never runs:** every page test builds its client with `createQueryClient({ retry: false })`.
  * **Refetch checked only indirectly:** the rule appears in two page cases. Nothing checks that no refetch happens after a 4xx with the service's error envelope.
* **Rationale:** the standard asks for specific happy-path and edge cases. Nothing would catch a retry on a 4xx or a refetch after a 409, both of which the Technical Plan forbids.
* **Reviewer guidance:** add `src/api/__tests__/queryClient.test.ts` with cases for:
  * retry on status 0 and on a 503;
  * no retry on a 400 or a 409;
  * a refetch after a 2xx and after a 502;
  * no refetch after a 4xx.

### ST-1 "At least one error code for each endpoint" is not backed by the test table - Severity: Major
* **Reviewer Lens:** `style`
* **Also raised by:** `implementation-readiness` (Nit, under test plan)
* **Affected Scope:** Lines 1961-1962 against the table at 1964-1986.
* **Round 1:** this is round-1 CD-4 (the PRD §11 bar of one error code per endpoint or screen) raised again. The fix added the sentence at 1961-1962 but not all the table rows, so the round-1 finding is re-opened.
* **Discrepancy Description:** the sentence says "Each screen's test file covers the happy path and at least one error code for each endpoint it calls". The table lists no error case for:
  * `GET /roles` (`useAllRoles`) in `UserCreatePage` (1980) and `UserEditPage` (1981)
  * `GET /menus` and `GET /roles/:id/menus` in `RoleMenusTab` (1984), which lists only "403 on save"
  * `GET /roles/:id/permissions` in `RolePermissionsTab` (1985)
  * `GET /menus` in `MenusPage` (1986)
* **Rationale:** an implementer who follows the table falls short of the stated bar. An implementer who follows the sentence must invent the missing cases.
* **Reviewer guidance:** add one error case per missing endpoint, or narrow the sentence to the endpoints the table covers and say why the rest are exempt.

### ST-2 "`lintRules.test.ts` proves each rule fires" claims more than the fixtures check - Severity: Major
* **Reviewer Lens:** `style`
* **Affected Scope:** Line 2084; fixtures 1946-1954; ESLint groups 1292-1303.
* **Discrepancy Description:** the six fixtures cover only the components, pages and other-`src` groups. These restrictions have no fixture:
  * the `src/auth/**` group (1299-1300)
  * the `@playwright/test` path in every group
  * the `msw/*` pattern
  * `keycloak-js` in components and pages
* **Rationale:** "proves each rule fires" tells a reviewer the layer rules are fully checked when they are only sampled.
* **Reviewer guidance:** either add the missing fixtures, or reword. Suggested wording: "`lintRules.test.ts` checks one representative import for five restrictions and one allowed type import."

### ST-3 The number of import rules does not add up - Severity: Major
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 1279, 1382, 1946, 1976 and 2083; ESLint config 1292-1303. The same "four import rules" wording also appears in FeatureRange at 406 and 941.
* **Discrepancy Description:**
  * **Four:** lines 1279, 1382 and 2083 say "the four import rules".
  * **Six:** line 1946 says "one file per import rule" and then lists six files.
  * **Five:** line 1976 says five fixtures report a violation, and the ESLint config defines five file groups.
* **Rationale:** a reader cannot tell whether there are four, five or six rules, or how the fixtures map to them. Combined with ST-2, this hides which restrictions are untested.
* **Reviewer guidance:** use one count everywhere, including lines 406 and 941. At line 1946, map fixtures to rules, for example "one fixture per restricted import, plus one allowed import".

### ST-4 Earlier sections are cited by informal names with no link - Severity: Major
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 1279, 1421, 1452, 1597, 1655, 1703, 1719, 1724, 1789, 1794, 1841, 1861, 1880, 1899 and 1923.
* **Discrepancy Description:**
  * **Linked:** the plan links some earlier sections: the exit-check table (1122), Build and deployment (1360), API client (1533), Server state (1590) and `SyncFailedNotice` (1731).
  * **Not linked:** elsewhere it uses informal names:
    * "the Technical Plan" and "(see API client)"
    * "the refetch rule", "the kinds table" and "the parent rule"
    * "(resubmitting rule)" and "the Delete rules"
    * "the Menus catalog section" and "the Roles list section"
    * "the row-message area" and "form reset rules after a 502"
* **Rationale:** these names point to the behaviour an implementer must build. Without links, the reader must search the first 1110 lines to find which rule is meant. Some of these rules are where IR-3's conflict lives.
* **Reviewer guidance:** replace each name with an anchor link, as the linked references already do.

---

### Nits

#### IR-6 Step boundaries and exit-check rows do not line up - Severity: Nit
* **Reviewer Lens:** `implementation-readiness`
* **Affected Scope:** Lines 1121-1122, 1127 against 1130, and 1134-1138.
* **Discrepancy Description:**
  * **Exit-check row:** the F1 row "403 shown inline" cites `UsersListPage.test` and `RolesListPage.test`, which arrive in steps 5 and 3. Yet step 2 is told to run "its rows of the exit-check table".
  * **Duplicate items:** items 22 and 23 are in step 1 and again in step 2 ("items 19 to 30").
* **Reviewer guidance:** say which step proves each exit-check row. Write step 2's list as "items 19 to 21, 24 to 30".

#### IR-7 What the client does with an empty or non-JSON 2xx response is not stated - Severity: Nit
* **Reviewer Lens:** `implementation-readiness`
* **Affected Scope:** Lines 1530-1543.
* **Discrepancy Description:** `apiRequest` returns `SuccessEnvelope<T> | undefined`. The plan does not say:
  * what resource modules do when they get `undefined` from an endpoint that must return data;
  * what a 2xx response with an HTML or non-envelope body becomes, for example a fallback page from a misrouted ingress.

  The kinds table covers only non-2xx responses.
* **Reviewer guidance:** in both cases, throw `ApiError` with `CLIENT_UNEXPECTED_RESPONSE` and the status. Add a `client.test.ts` case.

#### IR-8 Roles outside the picker are not handled - Severity: Nit
* **Reviewer Lens:** `implementation-readiness`
* **Also raised by:** `codebase-details` (related gap under Nit 8)
* **Affected Scope:** Lines 1613, 1662-1663 and 1764-1777 (items 66, 68, 69 and 84).
* **Discrepancy Description:**
  * **Where the filter applies:** the plan does not say whether the `isAssignable: false` filter is applied in `listAllRoles`, in `useAllRoles` or in the components. Only item 66 says so, and only for the filter.
  * **Held roles not shown:** the plan does not say whether `UserRolesForm` keeps role ids the user holds that are not shown (non-assignable, or past the first 500). If it drops them, the full-set `PUT` silently removes those roles.
  * **Missing fallback:** `roleErrorMessages` has no fallback text when a `roleIds[i]` is not in `roles`.
  * **Missing test:** no test checks that `CCE_ADMIN_SERVICE` is hidden.

#### IR-9 Signatures, props and some exports are left to the implementer - Severity: Nit
* **Reviewer Lens:** `implementation-readiness`
* **Also raised by:** `codebase-details` (Nit 6, `normalizePagingParams`)
* **Affected Scope:** Lines 1455, 1523-1525, 1608 onward, 1637-1641, 1659-1663, 1750, 1756, 1762-1766 and 1784.
* **Discrepancy Description:**
  * **`applyServerErrors`:** `applyServerErrors(setError, error, formFields, codeToField)` has no parameter types. There is no rule for whether `permissions.2.uriPattern` counts as a form field (prefix match or exact match).
  * **`normalizePagingParams`:** it belongs to no file, and its signature is not given. It is also not said whether invalid values are rewritten in the URL (`replace`) or only normalized in memory.
  * **`ApiError`:** the constructor signature is not given.
  * **Mutation variables:** the variable shapes are not given, for example `useUpdateUser` against `useReplaceRoleMenus(id)`.
  * **Component props:** props are missing for:
    * the item-65 and item-70 components;
    * `UserFilters` (`values` and `onChange`). Because `q` and `facilityScope` reach the URL only after the 300 ms debounce, also say where the local input state lives.
  * **Name clash:** the `Pagination` component (1750) and the `Pagination` contract type (1455) share a name. Rename one.

#### IR-10 Several UI texts that tests will assert are not fixed - Severity: Nit
* **Reviewer Lens:** `implementation-readiness`
* **Affected Scope:** Lines 1394-1398, 1756-1760, 1853 and 1858-1861.
* **Discrepancy Description:** these texts are not given:
  * the `StartupErrorScreen` `title` for both failure cases
  * the `ConfirmDialog` texts for status change, delete and deactivate (beyond "names the user and the effect")
  * the success messages for row status changes
  * the `NotFoundPanel` text and back-link label

#### IR-11 `tsconfig.node.json` is only partly specified - Severity: Nit
* **Reviewer Lens:** `implementation-readiness`
* **Affected Scope:** Lines 1234-1241.
* **Round 1:** same scope as round-1 IR-10 (TypeScript config gaps).
* **Discrepancy Description:** "Same strictness" does not give `target`, `lib`, `module` or `moduleResolution`. TypeScript's defaults (CommonJS) make `import.meta.dirname` in `eslint.config.js` and `verbatimModuleSyntax` fail `tsc -b`. Spell them out as item 4 does.

#### IR-12 The fake auth module's spies may not be typed - Severity: Nit
* **Reviewer Lens:** `implementation-readiness`
* **Affected Scope:** Lines 1922-1924 (item 93).
* **Discrepancy Description:** the plan does not say whether the fake's `vi.fn()` spies are typed with the real signatures, for example `vi.fn<typeof import('../auth/keycloak')['initAuth']>()`. If they are untyped, the `satisfies` check catches exports being added or removed, but not signature changes.

#### IR-13 Some test results can depend on the environment - Severity: Nit
* **Reviewer Lens:** `implementation-readiness`
* **Affected Scope:** Lines 1667-1668, 1976 and 1985.
* **Discrepancy Description:**
  * **Time zone:** `formatInstant` renders local time, and tests assert "Synced · {time}". No `TZ` is pinned for Vitest, so exact-text checks can differ between a workstation and the Docker `verify` stage. Pin `TZ=UTC`, or require the assertions to call `formatInstant`.
  * **Timeout:** `lintRules.test.ts` runs type-aware ESLint (`projectService`). Building the TypeScript program can take longer than Vitest's default 5 s timeout. Give an explicit timeout.

#### CD-3 Leftover browser-test roles will eventually hide new ones from the role pickers - Severity: Nit
* **Reviewer Lens:** `codebase-details`
* **Also raised by:** `implementation-readiness` (Nit, under test plan)
* **Affected Scope:** Lines 1560, 2017-2021, 2028-2031 and 2043-2045. Compare 236 and 1377-1378 (UI-AD-20).
* **Discrepancy Description:**
  * **Growth:** each `npm run e2e` run adds about seven `E2E_*` roles, and nothing deletes them.
  * **Limit:** the pickers show only the first 500 roles by name.
  * **Failure:** after roughly 70 runs without `docker compose down -v`, new roles fall past 500, and CUJ 2, 4 and 6 can no longer find them.
* **Reviewer guidance:** say in the README or in global setup when to reset, or delete the test roles afterwards.

#### CD-4 The README's `docker run` command gives a console that cannot sign in - Severity: Nit
* **Reviewer Lens:** `codebase-details`
* **Affected Scope:** Lines 1363 and 1379-1381. Compare 930-933.
* **Discrepancy Description:**
  * **The CSP:** item 17's CSP adds `{$KEYCLOAK_ORIGIN}` to `connect-src`.
  * **The README:** its run command (`docker run -e GATEWAY_UPSTREAM=... -p 3002:3002`) does not mention `KEYCLOAK_ORIGIN`.
  * **The effect:** an image built with a Keycloak on another origin cannot finish sign-in, because the CSP blocks keycloak-js's token request.
  * **Why the checks miss it:** the Verification Plan builds exactly such an image (2079), but its checks use `curl`, not a browser.
* **Reviewer guidance:** in the README, say to pass `-e KEYCLOAK_ORIGIN=...` whenever `VITE_KEYCLOAK_URL` points to another origin.

#### CD-5 Browser-test name stems have no character or length rule - Severity: Nit
* **Reviewer Lens:** `codebase-details`
* **Affected Scope:** Lines 2019-2021. Compare admin-service lines 594 and 608.
* **Discrepancy Description:**
  * **Usernames:** `e2e-{stem}-{suffix}` has a 16-digit suffix, so under the 50-character username limit the stem can have at most 29 characters.
  * **Role names:** `E2E_{STEM}_{suffix}` must match `^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$`, so the stem must be letters and digits only. A stem such as `cuj-2` gives an invalid role name.

#### ST-5 A hedge that never applies, and a statement made twice - Severity: Nit
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 1113-1114 and 2068-2070.
* **Discrepancy Description:** "unless its path says otherwise" never applies, because every path is inside `cce-admin-ui/`. "No existing file is modified" appears twice. Drop the hedge, and keep only one of the two statements.

#### ST-6 Vague precondition - Severity: Nit
* **Reviewer Lens:** `style`
* **Affected Scope:** Line 1146.
* **Discrepancy Description:** "once the admin-service stack runs" does not say how to start it. Cite admin-service §9.3, as line 2088 does.

#### ST-7 A UI string refers to the build plan - Severity: Nit
* **Reviewer Lens:** `style`
* **Affected Scope:** Line 1137.
* **Discrepancy Description:** the interim text "Permissions are added in the next step." describes the build plan, not anything the user can do. Use neutral placeholder text, or mark the string as temporary and removed in step 4.

#### ST-8 Awkward definition of "test files" - Severity: Nit
* **Reviewer Lens:** `style`
* **Affected Scope:** Line 1291.
* **Discrepancy Description:** suggested wording: "In this list, 'test files' means `**/*.test.{ts,tsx}` and `src/test/**`."

#### ST-9 "Starts as a copy" does not say what changes - Severity: Nit
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 1363-1364.
* **Discrepancy Description:** "The layout starts as a copy of the `cce-insights-ui` Caddyfile" does not say which parts differ. Name the differences, or drop the sentence, because the previous sentence already lists the content.

#### ST-10 An example message where a template is needed - Severity: Nit
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 1394-1396.
* **Discrepancy Description:** the text gives a fixed message for `VITE_KEYCLOAK_REALM` and then says "(one line per missing variable)". A template such as "Configuration error: {name} is not set." is clearer.

#### ST-11 Missing unit - Severity: Nit
* **Reviewer Lens:** `style`
* **Affected Scope:** Line 1633.
* **Discrepancy Description:** "used with 300" should read "used with 300 ms".

#### ST-12 Unclear `nextSortOrder` rule - Severity: Nit
* **Reviewer Lens:** `style`
* **Affected Scope:** Line 1673.
* **Discrepancy Description:** "(highest + 10, or 10, capped at 10000)" leaves "or 10" unexplained. Suggested wording: "highest sibling `sortOrder` + 10; 10 when there are no siblings; capped at 10000."

#### ST-13 Descriptions matched to files by position only - Severity: Nit
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 1756-1760 and 1784.
* **Discrepancy Description:** item 65 lists seven files in its heading and seven semicolon-separated descriptions, matched only by order. Name each file next to its description.

#### ST-14 Mixed number formats and spelling - Severity: Nit
* **Reviewer Lens:** `style`
* **Affected Scope:** Lines 1144, 1532, 1595, 1627, 1673, 1765, 1809, 1923 and 1973.
* **Discrepancy Description:**
  * **Number formats:** "30 000 ms" (1595) against "10000" (1673) and "500" (1765).
  * **Spelling:** British "behaviour" (1532, 1923) and "labelled" (1809) are mixed with American "catalog" (1144) and "normalization" (1973).
  * **Wording:** "afresh" (1627) reads more simply as "again".

#### ST-15 A placeholder in a different notation - Severity: Nit
* **Reviewer Lens:** `style`
* **Affected Scope:** Line 2062.
* **Discrepancy Description:** "Not synced yet: … (B's name)" uses an ellipsis and a parenthetical. Other expected strings use braces, such as `{email}` (2030) and `{time}` (1747). Use `{roleName}` or similar.

#### ST-16 Full paths for multi-file items - Severity: Nit
* **Reviewer Lens:** `implementation-readiness` (target scope and file boundary)
* **Affected Scope:** Items 65, 70 and 95.
* **Discrepancy Description:** these items name several files by folder-relative names. That is readable, but a write allowlist is safer with each full path.

---

## Review Loop Status

| Check | Result |
| --- | --- |
| Reviewers not run | none |
| Clean round (no unsettled Blocker or Major) | **no**: 11 Majors are open |
| Converged (two clean rounds in a row) | no |
| Rounds used | 2 of 5 |
| Re-opened round-1 findings | ST-1 (round-1 CD-4: the fix added the sentence but not the table rows) |
| Churn candidates | IR-2 (dropping round-1's `recommendedTypeChecked` would clear it, but rule overrides fix it while keeping that change) |
| New problems at the scope of earlier fixes, not churn | IR-1 (round-1 IR-1), IR-3 (round-1 IR-7), IR-4 (round-1 ST-4 and ST-5), IR-11 (round-1 IR-10) |

## Traceability: raw findings to report ids

| Report id | implementation-readiness | codebase-details | style |
| --- | --- | --- | --- |
| IR-1 | Major "Step 2 cannot pass its own coverage gate" | Major 1 | Major 1 |
| IR-2 | Major "typed lint preset" | - | - |
| IR-3 | Major "form reset or keep" | - | - |
| IR-4 | Major "container smoke check" | Major 3 | - |
| IR-5 | Major "four variables" | - | - |
| CD-1 | Nit "step boundaries" (first point) | Major 2 | - |
| CD-2 | Nit "stated rules with no test case" (first point) | Major 4 | - |
| ST-1 | Nit "stated rules with no test case" (second point) | - | Major 2 |
| ST-2 | - | - | Major 3 |
| ST-3 | - | - | Major 4 |
| ST-4 | - | - | Major 5 |
| IR-6 | Nit "step boundaries" (second and third points) | - | - |
| IR-7 | Nit "client unwrap" | - | - |
| IR-8 | Nit "roles outside the picker list" | related gap under Nit 8 | - |
| IR-9 | Nit "signatures and props" | Nit 6 | - |
| IR-10 | Nit "UI wording" | - | - |
| IR-11 | Nit "`tsconfig.node.json`" | - | - |
| IR-12 | Nit "stated rules with no test case" (third point) | - | - |
| IR-13 | Nit "environment-dependent tests" (first two points) | - | - |
| CD-3 | Nit "environment-dependent tests" (third point) | Nit 8 | - |
| CD-4 | - | Nit 5 | - |
| CD-5 | - | Nit 7 | - |
| ST-5 to ST-15 | - | - | Nits 6 to 16 |
| ST-16 | target scope and file boundary note | - | - |
