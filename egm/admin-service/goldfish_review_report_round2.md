Status: resolved (all 66 fixes applied to `docs/designs/cce-admin-service.md`; `egm/admin-service/design_draft.md` is identical)

# Goldfish Annotated Review Report (round 2)

**Target:** `docs/designs/cce-admin-service.md` (`full file`; the Stage 9 findings cover lines 1602-1937)

**Findings:** the 63 findings of [stage9_round2_report.md](stage9_round2_report.md) (1 Blocker,
26 Major, 36 Nit), plus three points from the console design, C1 to C3. The round-1 report is kept
in [goldfish_review_report.md](goldfish_review_report.md).

**Categories:** 66 PROPOSE FIX, 0 CONTEST, 0 ESCALATE, 0 DECLINE. Each claim was checked
against the design text and against the documented behaviour of the library it names: static
`@Container` fields in a JUnit 5 base class restart per test class; Spring Data cannot derive
`findByIdForUpdate`; Hibernate 6's default `@GeneratedValue` is a sequence; the Boot binder leaves
an unresolvable placeholder as text; MockMvc bypasses Tomcat's `RemoteIpValve`; filter
exceptions bypass `@RestControllerAdvice`; a client passed to `KeycloakBuilder` skips the
library's `JacksonProvider`; Surefire's and Failsafe's default includes do not match `*E2E.java`.

**Resolution:** the author pre-approved auto accept. All 66 fixes are applied, and
`egm/admin-service/design_draft.md` is identical to the design. Accepted: 66. Contested: 0.
Escalated: 0. Declined: 0. Unaddressed: 0.

**Choices made while applying** (one defensible answer, or the option with fewer moving parts):
*   **M-14 (author decision):** the Keycloak admin client is built the way the library builds its
    own client (`ResteasyClientBuilder` with `connectionPoolSize(10)` and the library's
    `JacksonProvider`), plus connect and read timeouts, and passed to `KeycloakBuilder`.
*   **B-1:** a singleton container started once in a static initializer, with `@ServiceConnection`.
    The two client mocks are declared once in the base class, so all database tests share one
    Spring context.
*   **M-4:** the service uses the instance `save` returns (fewer moving parts than
    `Persistable`). Join entities map foreign keys as plain UUID columns.
*   **M-6:** `users.updated_at` is set by its own JPQL `UPDATE` after the bulk delete.
*   **M-8:** required values are written `${VAR:}`, and the record is `@Validated` with
    `@NotBlank`; registration by `@ConfigurationPropertiesScan`.
*   **M-9:** test isolation by a `@BeforeEach` reset in the base class (truncate, then delete
    non-seed rows), not by filtering every assertion.
*   **M-10:** `ClientIpWebTest` moves to Phase 5 and is driven through `POST /roles`.
*   **M-11:** both filters pass the request on unchanged unless the authentication is a
    `JwtAuthenticationToken`.
*   **M-13:** the `KeycloakSyncFailedException` branch of `ApiExceptionHandler` logs one line in
    `key=value` form.
*   **N-4:** a `HealthService` keeps the controller rule.
*   **N-10:** the stand-in keeps the explicit driver 42.7.14 (D-05), described as an override of
    the parent's managed version.
*   **C1:** a warning is a union on `code`. `menuId` and `parentMenuId` are present only on
    `DETAIL_WITHOUT_PARENT` and absent on the other two codes. The null-fields rule lists them as
    the one per-type exception.

## Prioritized Findings & AI Suggestions

Each entry: scope, description and rationale as in the source report, then the suggestion and
the edit applied. Line numbers refer to the design before the edits.

### [Issue 1] B-1 [`egm-codebase-details`, `egm-implementation-readiness`] - Severity: Blocker
*   **Affected Scope:** [Line 1674](../../docs/designs/cce-admin-service.md#L1674) (`DatabaseTestBase`)
*   **Discrepancy Description:** The shared Testcontainers base class restarts its static container per test class, and the cached Spring context then points at a stopped container.
*   **Rationale:** Phase 3 adds several Spring test classes, and the second one fails.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Accurate: the JUnit 5 extension manages static `@Container` fields per class.
*   ***Proposed Edit:*** Phase 2 `DatabaseTestBase` row: a static `PostgreSQLContainer` with `@ServiceConnection`, started once in a `static {}` block, without `@Testcontainers` or `@Container`; `@MockitoBean KeycloakGateway` and `@MockitoBean GatewaySyncClient` declared in the base class; `FreshDatabase` uses the same instance.

### [Issue 2] M-1 [`egm-implementation-readiness`, `egm-codebase-details`] - Severity: Major
*   **Affected Scope:** [Lines 1671-1673](../../docs/designs/cce-admin-service.md#L1671-L1673)
*   **Discrepancy Description:** Several repository methods cannot be derived from their names, `deleteExplicitRows(name, implicitPairs)` cannot be bound, and four repositories are unspecified.
*   **Rationale:** The Phase 2 context would not start, and the implementer would have to design queries.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Accurate.
*   ***Proposed Edit:*** A per-repository table with every method signature and its query text (JPQL or SQL), including the locking lookups, the count, the conditional update, `deleteExplicitRows(String name, Collection<String> implicitUris)`, and the role, menu, user-role and audit queries.

### [Issue 3] M-2 [`egm-codebase-details`] - Severity: Major
*   **Affected Scope:** [Line 1669](../../docs/designs/cce-admin-service.md#L1669)
*   **Discrepancy Description:** `ApiPermissionEntity` has no id strategy.
*   **Rationale:** The default sequence does not exist, so validation fails.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Accurate.
*   ***Proposed Edit:*** `@Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id`.

### [Issue 4] M-3 [`egm-codebase-details`] - Severity: Major
*   **Affected Scope:** [Line 1669](../../docs/designs/cce-admin-service.md#L1669)
*   **Discrepancy Description:** The blanket timestamp rule maps columns that join tables and `audit_log` lack.
*   **Rationale:** `ddl-auto: validate` fails, or `occurred_at` is inserted null.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Accurate.
*   ***Proposed Edit:*** Per-entity timestamp rules: owned tables both columns, join entities `created_at` only, `AuditLogEntity` sets `occurredAt` in Java.

### [Issue 5] M-4 [`egm-codebase-details`] - Severity: Major
*   **Affected Scope:** [Line 1669](../../docs/designs/cce-admin-service.md#L1669), flow 5.1
*   **Discrepancy Description:** A pre-assigned id makes `save` merge, and the held instance keeps null timestamps.
*   **Rationale:** The 201 body and audit JSON could show nulls.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Accurate.
*   ***Proposed Edit:*** "Services always continue with the instance `save` returns." Join entities map foreign keys as plain UUID columns.

### [Issue 6] M-5 [`egm-implementation-readiness`, `egm-codebase-details`] - Severity: Major
*   **Affected Scope:** Lines 1669, 1672, 1780, 1796
*   **Discrepancy Description:** Recording a sync result bumps `roles.updated_at` through `@PreUpdate`.
*   **Rationale:** Contradicts 5.10 and its own test.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Accurate.
*   ***Proposed Edit:*** `RoleRepository.updateSyncResult(id, status, at)` as a `@Modifying` JPQL update, required in `GatewaySyncService`.

### [Issue 7] M-6 [`egm-codebase-details`] - Severity: Major
*   **Affected Scope:** Line 1673, flow 5.4
*   **Discrepancy Description:** `clearAutomatically` can drop the `users.updated_at` change.
*   **Rationale:** The change is lost silently.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Accurate.
*   ***Proposed Edit:*** `UserRepository.touchUpdatedAt(id, at)` JPQL update after the delete, and an `updatedAt` assertion in `UserRolesFlowTest`.

### [Issue 8] M-7 [`egm-implementation-readiness`] - Severity: Major
*   **Affected Scope:** Lines 1825, 1826, 1830
*   **Discrepancy Description:** Nothing makes the database step commit before the sync in the role services.
*   **Rationale:** The stand-in would read no rows.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Accurate.
*   ***Proposed Edit:*** No `@Transactional` on the flow services. Each database step runs in a `TransactionTemplate` and commits before `GatewaySyncService` is called. A test reads the rows from a separate transaction inside the mocked `sync()`.

### [Issue 9] M-8 [`egm-implementation-readiness`, `egm-codebase-details`, `egm-style`] - Severity: Major
*   **Affected Scope:** Lines 1720, 1725, 1727; section 9.4
*   **Discrepancy Description:** A missing variable does not stop startup, and the `admin:` block is not written out.
*   **Rationale:** Unresolvable placeholders bind as literal text.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Accurate.
*   ***Proposed Edit:*** The full `admin:` YAML block with `${VAR:}` defaults, `@Validated` with `@NotBlank`, `@ConfigurationPropertiesScan`, an `ApplicationContextRunner` test, and a list of which variables bind elsewhere.

### [Issue 10] M-9 [`egm-codebase-details`, `egm-style`, `egm-implementation-readiness`] - Severity: Major
*   **Affected Scope:** Lines 1726, 1749-1751, 1674
*   **Discrepancy Description:** Decoder replacement and test isolation are unspecified.
*   **Rationale:** Two decoder beans, a runtime key YAML cannot name, and order-dependent assertions.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Accurate.
*   ***Proposed Edit:*** `@Primary` test decoder, `SecurityConfig` takes the decoder bean from the context, reworded test-settings row, `@BeforeEach` reset in `DatabaseTestBase`.

### [Issue 11] M-10 [`egm-implementation-readiness`, `egm-codebase-details`] - Severity: Major
*   **Affected Scope:** Line 1754
*   **Discrepancy Description:** `ClientIpWebTest` cannot run under MockMvc, and Phase 3 has nothing audited.
*   **Rationale:** `RemoteIpValve` lives in Tomcat.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Accurate.
*   ***Proposed Edit:*** Moved to Phase 5, `RANDOM_PORT` with a real HTTP client, two classes (default and `TRUSTED_PROXIES` set), driven through `POST /roles`.

### [Issue 12] M-11 [`egm-codebase-details`] - Severity: Major
*   **Affected Scope:** Lines 1730, 1733
*   **Discrepancy Description:** A request with no token may get 403.
*   **Rationale:** The filters run for anonymous callers.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Accurate.
*   ***Proposed Edit:*** Both filters pass the request on unless the authentication is a `JwtAuthenticationToken`. Section 3 states it too.

### [Issue 13] M-12 [`egm-codebase-details`] - Severity: Major
*   **Affected Scope:** Lines 1730, 1735, 1740
*   **Discrepancy Description:** Exceptions inside filters bypass `ApiExceptionHandler`.
*   **Rationale:** Callers get Boot's default error body.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Accurate. The exact status on an error dispatch was not confirmed, but the envelope mismatch holds.
*   ***Proposed Edit:*** Both filters catch unexpected exceptions and write 500 `INTERNAL_ERROR` through `SecurityErrorWriter`. `SecurityConfig` permits `DispatcherType.ERROR`. A test case is added.

### [Issue 14] M-13 [`egm-implementation-readiness`] - Severity: Major
*   **Affected Scope:** Lines 1739-1740, 1861
*   **Discrepancy Description:** The structured `ERROR` log has no owner, format or test.
*   **Rationale:** It is the only record under audit rule 5.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Accurate.
*   ***Proposed Edit:*** `ApiExceptionHandler`'s 502 branch logs `event=keycloak_sync_failed operation=… entityId=… failedStep=… completedSteps=… keycloakUserId=… auditLogId=…`. Output-capture assertions in Phases 5 and 6.

### [Issue 15] M-14 [`egm-codebase-details`] - Severity: Major
*   **Affected Scope:** Lines 1776-1777, 1128
*   **Discrepancy Description:** A custom RESTEasy client drops the library's Jackson and pool settings, and the create `Response` is not closed.
*   **Rationale:** Unknown fields fail at runtime, and connections leak.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Accurate. Author decision: build the client as the library does, plus timeouts.
*   ***Proposed Edit:*** The exact construction in section 7 and Phase 4, and `try (Response r = …)` with `CreatedResponseUtil.getCreatedId(r)`.

### [Issue 16] M-15 [`egm-codebase-details`] - Severity: Major
*   **Affected Scope:** Lines 1781, 1797, 1857
*   **Discrepancy Description:** `AccountLinker` needs the `User` shape before Phase 6.
*   **Rationale:** Phase 4 would invent a shape.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Accurate.
*   ***Proposed Edit:*** `UserDtos.User` and `service/UserViews.java` move to Phase 4; Phase 6 adds the request records.

### [Issue 17] M-16 [`egm-style`, `egm-implementation-readiness`, `egm-codebase-details`] - Severity: Major
*   **Affected Scope:** Lines 1781, 1861, 805
*   **Discrepancy Description:** The linking method has two names.
*   **Rationale:** A reader of 5.1 looks for a method that does not exist.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Accurate.
*   ***Proposed Edit:*** `AccountLinker.link(...)` everywhere, and `AccountLinker.reactivateAsAdmin(...)` for the bootstrap variant, which also calls `addRealmRoles` when the mapping is missing.

### [Issue 18] M-17 [`egm-implementation-readiness`] - Severity: Major
*   **Affected Scope:** Line 1884
*   **Discrepancy Description:** The `admin-service` compose block is not written out.
*   **Rationale:** A missed `JWT_JWK_SET_URI` override gives 401 everywhere, first seen in Phase 8.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Accurate.
*   ***Proposed Edit:*** The block in section 9.1 with every override, and a Phase 7 exit step calling `GET /admin/v1/me` on the container.

### [Issue 19] M-18 [`egm-implementation-readiness`, `egm-codebase-details`, `egm-style`] - Severity: Major
*   **Affected Scope:** Line 1865
*   **Discrepancy Description:** The concurrency test waits for a signal the second request cannot send.
*   **Rationale:** It deadlocks or proves nothing.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Accurate.
*   ***Proposed Edit:*** Poll `pg_stat_activity` until the second transaction waits on a lock, then release the latch (10-second timeout), and assert one 200 and one 409.

### [Issue 20] M-19 [`egm-style`, `egm-implementation-readiness`] - Severity: Major
*   **Affected Scope:** Lines 1610-1611, 1668, 1642, 1912
*   **Discrepancy Description:** "No design decisions are left" is stated as fact while gaps remain.
*   **Rationale:** Four rows lack content.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Accurate.
*   ***Proposed Edit:*** Rewritten as an instruction. The Phase 2 test YAML, the script's keys, the hard-control user and S6's ordering are filled in.

### [Issue 21] M-20 [`egm-style`, `egm-implementation-readiness`] - Severity: Major
*   **Affected Scope:** Lines 1623-1624
*   **Discrepancy Description:** "Tests never read `.env`" contradicts `E2eEnvironment`.
*   **Rationale:** Self-contradiction.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Accurate.
*   ***Proposed Edit:*** The suggested wording.

### [Issue 22] M-21 [`egm-style`, `egm-implementation-readiness`, `egm-codebase-details`] - Severity: Major
*   **Affected Scope:** Line 1679
*   **Discrepancy Description:** "The two sample rows remain" against a five-row fixture.
*   **Rationale:** Wrong count.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Accurate.
*   ***Proposed Edit:*** "The five fixture rows remain unchanged."

### [Issue 23] M-22 [`egm-style`, `egm-implementation-readiness`, `egm-codebase-details`] - Severity: Major
*   **Affected Scope:** Lines 1720-1721
*   **Discrepancy Description:** Phase 3 claims B-03 and B-04, which later phases finish.
*   **Rationale:** Overstated coverage.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Accurate.
*   ***Proposed Edit:*** The qualified wording.

### [Issue 24] M-23 [`egm-style`] - Severity: Major
*   **Affected Scope:** Lines 1888, 1930-1931
*   **Discrepancy Description:** The guard test is described as checking more than it does.
*   **Rationale:** Overstatement.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Accurate.
*   ***Proposed Edit:*** The suggested wording, in both places.

### [Issue 25] M-24 [`egm-style`] - Severity: Major
*   **Affected Scope:** Lines 1673, 1733, 1734
*   **Discrepancy Description:** Bare step numbers.
*   **Rationale:** Ambiguous with flow steps.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Accurate.
*   ***Proposed Edit:*** "Section 3, step 4 (local user)" and "Section 3, step 5 (permission check)".

### [Issue 26] M-25 [`egm-style`] - Severity: Major
*   **Affected Scope:** Lines 1638, 1646
*   **Discrepancy Description:** The placeholder rule for `.env.example` is unclear.
*   **Rationale:** Host-run values look like placeholders.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Accurate.
*   ***Proposed Edit:*** The suggested wording.

### [Issue 27] M-26 [`egm-style`] - Severity: Major
*   **Affected Scope:** Line 1634
*   **Discrepancy Description:** The first command depends on the author's machine.
*   **Rationale:** Workstation detail.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Accurate.
*   ***Proposed Edit:*** "Run this once with any installed Maven 3.x. Every later command uses `./mvnw`."

### [Issues 28-63] N-1 to N-36 [`egm-implementation-readiness`, `egm-codebase-details`, `egm-style`] - Severity: Nit
*   **Affected Scope, Description, Rationale:** as in [stage9_round2_report.md](stage9_round2_report.md), N-1 to N-36.
*   ***AI Suggestion:*** PROPOSE FIX for all 36.
*   ***Justification:*** Each is accurate, and each fix is a line or less of plan text.
*   ***Proposed Edit:*** as the report recommends, with these specifics:
    *   N-1: `FreshDatabase.runSql` sends the whole file in one `Statement.execute`; V1 uses `$do$` and `$fn$` tags.
    *   N-2: menu ids `10000000-0000-4000-8000-0000000000NN` (NN = 01 to 12, in table order); role ids `20000000-0000-4000-8000-000000000001` (`SUPER_ADMIN`) and `…002` (`CCE_ADMIN_SERVICE`).
    *   N-4: `service/HealthService.java`.
    *   N-5: `PermissionStore` and `RealmRoles` are interfaces, with JDBC and HTTP implementations; stand-in version `0.1.0-SNAPSHOT`.
    *   N-8: Surefire `<skipTests>true</skipTests>` inside the profile and Failsafe `<includes>`; the Phase 1 Surefire exclusion is dropped.
    *   N-9: `<start-class>` and an unconfigured `maven-shade-plugin` declaration.
    *   N-10: the explicit 42.7.14 is described as an override.
    *   N-11: Node 22 and a JDK 21 are named as README prerequisites outside D-05.
    *   N-13: `Jackson2ObjectMapperBuilderCustomizer` and `spring.jackson.deserialization.fail-on-unknown-properties: true`.
    *   N-14: `@InitBinder` with `StringTrimmerEditor(true)` in `ApiExceptionHandler`.
    *   N-15: `BearerTokenAuthenticationEntryPoint` sets the header first.
    *   N-16: `JwtClaimValidator` replaces `AudienceValidator`.
    *   N-17: 403 without a query when the role list is empty.
    *   N-18: `existsByEmail(email.toLowerCase(Locale.ROOT))`.
    *   N-20: `ClientHttpRequestFactoryBuilder.jdk()` with the timeouts.
    *   The rest are wording fixes, applied as worded in the report.

### [Issue 64] C1 [console design] - Severity: Major
*   **Affected Scope:** Section 4.1, null fields and Warning
*   **Discrepancy Description:** The null-fields rule lists four optional fields, but the Warning shape has `menuId?` and `parentMenuId?`.
*   **Rationale:** Self-contradiction in the contract.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Accurate.
*   ***Proposed Edit:*** Warning is a union on `code`: `DETAIL_WITHOUT_PARENT` carries `menuId` and `parentMenuId`, the other codes carry neither field. The null-fields rule names this as the one per-type exception.

### [Issue 65] C2 [console design] - Severity: Nit
*   **Affected Scope:** Section 4.4, `httpMethod`
*   **Discrepancy Description:** The CQ-1 link points at a heading that no longer exists.
*   **Rationale:** Broken anchor.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Verified: the console design's decision is now in "Author Decisions in This Design" as UI-AD-05.
*   ***Proposed Edit:*** Link `../../docs/designs/cce-admin-ui.md#author-decisions-in-this-design`.

### [Issue 66] C3 [console design] - Severity: Nit
*   **Affected Scope:** Section 4.4, named result records
*   **Discrepancy Description:** `syncStatus` is typed `string` in `RoleSyncResult` and `RolePermissions`.
*   **Rationale:** The Role resource names the three values.
*   ***AI Suggestion:*** PROPOSE FIX
*   ***Justification:*** Accurate.
*   ***Proposed Edit:*** `"syncStatus": "NEVER" | "SYNCED" | "FAILED"` in both records.
