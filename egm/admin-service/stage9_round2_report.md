# Goldfish Review Report: Stage 9, round 2

**Target:** `docs/designs/cce-admin-service.md` (lines 1602-1937, `## Detailed Implementation Plan` to end of file, 1,937 lines in total)

**Stage:** Stage 9 (Implementation Readiness Test), round 2. This is the re-audit after the 39 fixes from round 1 (CA-01 to CA-33 and UI-1 to UI-6, see [goldfish_review_report.md](goldfish_review_report.md)). The round-1 Stage 9 report is kept unchanged in [implementation_readiness_report.md](implementation_readiness_report.md).

**GoldfishContext:** only the seven files the design links to inside this folder:
`context/prd/CCE_Admin_Module_PRD.md`, `context/gateway-service/DATABASE_PERMISSIONS_MIGRATION.md`,
`context/cce-insights-ui/docs/architecture-overview.md`, `deployment-guide.md`, `developer-setup.md`,
`docs/designs/author-decisions.md` and `docs/designs/cce-admin-ui.md`. The design contains no
documentation URLs. Its `http://localhost…` and `http://keycloak…` strings are configuration values,
not links, and were left out as instructed. Reviewers were told not to read `trial-log/` or `egm/`,
so they did not see the round-1 report.

**Dispatch:** direct (flat swarm), three fresh reviewers run in parallel, no synthesizer.

## Reviewer Status

| Reviewer | Standard | Status |
| --- | --- | --- |
| `egm-implementation-readiness` | `implementation-readiness-standard` | completed |
| `egm-codebase-details` | `implementation-readiness-standard` | completed |
| `egm-style` | (none) | completed |

**Severity breakdown (after deduplication):** 1 Blocker, 26 Major, 36 Nit.

Raw counts before deduplication (Blocker / Major / Nit): implementation-readiness 0 / 9 / 9,
codebase-details 1 / 13 / 16, style 0 / 11 / 22. When reviewers rated the same issue differently,
this report uses the higher rating and lists every lens that raised it. All three reviewers wrote
severities as words because their instructions forbid emojis. The markers below are added by
this report.

## Summary

**The round is not clean.** One Blocker and 26 Majors are open, and none of them was settled in
round 1. The plan is better than in round 1. The two round-1 Blockers are gone in their original
form: tests now have a `test` profile and `application-test.yml`, and the gateway fixture is written
out with its reconstruction marked. Codebase-details checked and confirmed several claims, among them
the endpoint counts, the Flyway baseline settings, `briefRepresentation=false`, the `.env` import
behind `on-profile: "!test"`, and the fixture DDL against the gateway doc.

The open problems fall into five groups:

1. **Test infrastructure that will break the build from Phase 3 on.** The plan's shared Testcontainers
   base class (B-1) does not share its container across test classes. Several other test details are
   unclear: how the JWT decoder is replaced, how tests stay apart in the shared database (M-9), how
   `ClientIpWebTest` can run under MockMvc (M-10), and how the concurrency test synchronises (M-18).
2. **The JPA layer, which round 1's fixes made more specific but not yet correct.** Several repository
   methods cannot be derived from their names or bound as written (M-1). `ApiPermissionEntity` has no
   id strategy, so `ddl-auto: validate` fails (M-2). The blanket `@PrePersist`/`@PreUpdate` rule maps
   columns that three tables do not have (M-3). A pre-assigned user id makes `save` merge instead of
   persist (M-4). Two `updated_at` rules are broken: the entity callback bumps `roles.updated_at` on a
   sync result (M-5), and the bulk delete's `clearAutomatically` can drop the `users.updated_at` change
   in 5.4 (M-6).
3. **Mechanisms the spec requires that the plan does not assign to a file.** Nothing makes the save
   commit before the gateway sync (M-7). The B-13 startup failure on a missing variable does not
   happen with Spring's lenient placeholder binding (M-8). The structured `ERROR` log on every 502 has
   no owning file (M-13). The `admin-service` compose block is not written out (M-17).
4. **Security-filter edge cases.** A missing token may get 403 instead of 401 (M-11), and exceptions
   inside filters bypass `ApiExceptionHandler` (M-12).
5. **Wording that is wrong or says more than the text supports** (M-19 to M-26). One example is
   "the two sample rows remain" when the fixture inserts five. Another is "Tests never read `.env`",
   which contradicts `E2eEnvironment`.

**Round-1 findings raised again.** Every round-1 Stage 9 finding was resolved as a fix (none was
contested). By the review-loop rules, a fixed finding raised again at the same scope is
re-opened. 13 round-1 findings come back in some form; the "R1" column in the
[cross-reference table](#round-1-cross-reference) maps them. In six of these, the round-1 fix added
text that is itself now the problem: the shared container (B-1), `deleteExplicitRows(name,
implicitPairs)` (M-1), the blanket timestamp rule (M-3), the custom RESTEasy client (M-14), and the
"Tests never read `.env`" bullet and test-key wording (M-20, M-9).

**Churn check.** No finding meets the strict churn test, where reverting a fix would resolve the
new finding without re-opening the old one. M-14 is the nearest case. Dropping the custom RESTEasy
client would bring back the library's `JacksonProvider` and pool settings, but would lose the
timeouts that round-1 N-13 asked for.

**Convergence.** Round 2 is not clean, so the stage has not converged. `egm-review` allows each stage
one re-audit, and this run is that re-audit. A third Stage 9 round is beyond the skill's cap and is
the author's call.

---

## Prioritized Findings (Blockers -> Major -> Nits)

### 🔴 B-1. The shared Testcontainers base class is not shared and will break cached Spring contexts from Phase 3 - Severity: Blocker
* **Reviewer Lens:** `egm-codebase-details` (Blocker), `egm-implementation-readiness` (Major)
* **Affected Scope:** Line 1674 (`DatabaseTestBase`); every test class that extends it (lines 1753, 1830, 1863-1866); line 1608 ("every phase leaves the build green")
* **R1:** follows from the round-1 M-15 fix
* **Discrepancy Description**:
  * Line 1674 puts `@Testcontainers` on the base class with a static `@Container @ServiceConnection` field "shared by every database test".
  * The Testcontainers JUnit 5 extension starts and stops a static `@Container` once per test class. Each subclass gets a fresh container on a new mapped port.
  * Spring's test context cache treats those classes as having the same configuration, so it reuses the first context and its Hikari pool, which still points at the stopped container.
  * Phase 2 has one Spring test class, so it passes. Phase 3 adds several, and the second class to reuse the cached context fails with connection-refused errors.
* **Rationale & Recommendation**: Use the singleton-container pattern. Keep the static `PostgreSQLContainer` field with `@ServiceConnection`, start it in a `static {}` block, and drop `@Testcontainers` and `@Container`. Have `FreshDatabase` use the same instance. Codebase-details also suggests declaring the `@MockitoBean KeycloakGateway` and `@MockitoBean GatewaySyncClient` in the base class. That way all service and web tests share one context instead of one per combination of mocks, and do not run Postgres out of connections with one Hikari pool per context.

---

### 🟡 M-1. Several repository methods cannot be derived from their names, and four repositories are not specified - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`, `egm-codebase-details`
* **Affected Scope:** Lines 1671-1673
* **R1:** follows from the round-1 M-8 fix (`deleteExplicitRows`)
* **Discrepancy Description**:
  * `findByIdForUpdate` and `findByNameForUpdate`: `@Lock` does not supply a query. Spring Data parses the names as properties `idForUpdate` and `nameForUpdate`, throws `PropertyReferenceException`, and the Phase 2 context does not start.
  * `countActiveUsersWithRoleExcluding(roleName, userId)` cannot be derived from its name either, and no `@Query` is given. `updateLastLoginIfLater` is called a native conditional `UPDATE`, but its SQL is not given.
  * `deleteExplicitRows(name, implicitPairs)`: JPQL cannot bind a collection of (method, pattern) pairs, so this signature cannot be the "JPQL bulk delete" line 1673 requires.
  * `MenuRepository`, `RoleMenuRepository`, `UserRoleRepository` and `AuditLogRepository` get only "the queries sections 3 and 5 need". The queries left unnamed include:
    * a role's rows by `permission_name` (for `GET /roles/:id/permissions`, the 5.7 comparison and the 5.9 before-JSON);
    * token role names to roles (for `Me.roles`);
    * the menus granted to a set of roles (for `/me/menus`);
    * `Role.userCount` for `GET /roles`, which section 4.4 (line 615) requires and the plan never mentions;
    * a role's presence in `user_roles`, for `ROLE_IN_USE`;
    * the paged, ordered role list.
* **Rationale & Recommendation**: List every repository method with its signature and query text. Give `@Query("select u from UserEntity u where u.id = :id")` with `@Lock(PESSIMISTIC_WRITE)` for the locking lookups. Give the count query as JPQL, and the native `UPDATE users SET last_login_at = :t WHERE id = :id AND (last_login_at IS NULL OR last_login_at < :t)`. For the explicit-row delete, use a fixed signature, for example `deleteExplicitRows(String name, Collection<String> implicitUris)` with `WHERE permissionName = :name AND NOT (httpMethod = 'GET' AND uriPattern IN :implicitUris)`, or write the two implicit pairs into the JPQL as literals from `ImplicitPermissions`.

### 🟡 M-2. `ApiPermissionEntity` has no id strategy, and Hibernate's default fails `ddl-auto: validate` - Severity: Major
* **Reviewer Lens:** `egm-codebase-details` (Major), `egm-implementation-readiness` (Nit)
* **Affected Scope:** Line 1669, with `ddl-auto: validate` at line 1667; column definition at line 1026
* **R1:** related to round-1 M-5
* **Discrepancy Description**: `api_permissions.id` is `BIGSERIAL`. Plain `@GeneratedValue` in Hibernate 6 means SEQUENCE with a sequence named `api_permissions_seq` and an allocation size of 50. That sequence does not exist, so schema validation fails at startup. Even if it existed, its ids would not match the gateway's `api_permissions_id_seq`.
* **Rationale & Recommendation**: State `@Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id`.

### 🟡 M-3. The blanket timestamp rule does not match three of the tables - Severity: Major
* **Reviewer Lens:** `egm-codebase-details` (Major), `egm-implementation-readiness` (Nit)
* **Affected Scope:** Line 1669, against lines 1011-1018 and 1037-1052
* **R1:** follows from the round-1 M-5 fix
* **Discrepancy Description**: Line 1669 says that on the owned tables `@PrePersist` sets the id, `created_at` and `updated_at`, and `@PreUpdate` sets `updated_at`. But `role_menus` and `user_roles` have only `created_at`, and `audit_log` has neither column. It has `occurred_at`, `NOT NULL DEFAULT now()`. Mapping `updatedAt` to a missing column fails `ddl-auto: validate`. If `AuditLogEntity` maps `occurred_at` as insertable and leaves it null, every audit insert breaks the `NOT NULL` constraint.
* **Rationale & Recommendation**: Give the timestamp handling per entity. Join entities set `created_at` only. `AuditLogEntity` either sets `occurredAt` in Java or maps it `insertable = false` so the database default applies. `ApiPermissionEntity` stays as written.

### 🟡 M-4. Saving a user whose id is already set merges instead of inserting - Severity: Major
* **Reviewer Lens:** `egm-codebase-details`
* **Affected Scope:** Lines 1669 and 1861; flow 5.1 ("Assign the new user's id", line 781; lines 954-955)
* **R1:** related to round-1 M-5
* **Discrepancy Description**: Flow 5.1 sets the user's id before saving. `SimpleJpaRepository.save` treats an entity with a non-null id and no `@Version` as not new, so it calls `merge`. That runs a `SELECT`, inserts a copy, and runs `@PrePersist` on the copy. The object the service holds keeps `createdAt` and `updatedAt` null. If the 201 body or the `USER_CREATED` audit JSON is built from it, both show null timestamps.
* **Rationale & Recommendation**: Choose and state one approach. Either `UserEntity` implements `Persistable<UUID>` with a `@Transient` new-flag that `@PostPersist` and `@PostLoad` clear, or the service must use the instance `save` returns from then on. Also state whether join entities map their foreign keys as plain UUID columns or as `@ManyToOne`.

### 🟡 M-5. Recording a sync result changes `roles.updated_at`, which the spec and the plan's own test forbid - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`, `egm-codebase-details`
* **Affected Scope:** Lines 1669, 1672, 1780 (`GatewaySyncService`) and 1796 (its test); against lines 909-910 and 616
* **R1:** re-opens round-1 N-16 (fixed by adding the rule and the test, but no mechanism)
* **Discrepancy Description**: Step 3 of 5.10 sets `sync_status` and `last_sync_at` "without changing its `updated_at`", and `GatewaySyncServiceTest` asserts it. The plan's `@PreUpdate` on `RoleEntity` bumps `updated_at` whenever a managed role is saved, and `RoleRepository` has no method that bypasses it. An implementer following the plan will load, change and save the entity, and the test will fail.
* **Rationale & Recommendation**: Add `RoleRepository.updateSyncResult(UUID id, SyncStatus status, Instant at)` as a `@Modifying` JPQL `update RoleEntity r set r.syncStatus = :s, r.lastSyncAt = :at where r.id = :id`, which skips entity callbacks, and require `GatewaySyncService` to use it.

### 🟡 M-6. `clearAutomatically = true` can silently drop the `users.updated_at` change in flow 5.4 - Severity: Major
* **Reviewer Lens:** `egm-codebase-details`
* **Affected Scope:** Line 1673; flow 5.4 (line 846); `UserRolesFlowTest` (line 1864)
* **R1:** re-opens round-1 M-6
* **Discrepancy Description**: In 5.4 the user row is loaded and locked first. Then the `user_roles` rows are removed by a bulk delete with `clearAutomatically = true`, which detaches the user entity. If the code then sets `updated_at` on that entity, the change is never flushed and no error is raised. The listed tests do not assert `updatedAt` after a role change.
* **Rationale & Recommendation**: Choose and state one approach: change the user before the bulk delete, so `flushAutomatically` writes it first; reload the user after the delete; or update `updated_at` with its own JPQL `UPDATE`. Add an `updatedAt` assertion to `UserRolesFlowTest`.

### 🟡 M-7. Nothing makes the database step commit before the gateway sync in the role services - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** Lines 1825 (`RoleService`), 1826 (`RolePermissionService`) and 1830 (their tests); flow rule at lines 763-764
* **R1:** re-opens round-1 N-12 in part (the step-boundary mechanism was named only for `UserService`, line 1861)
* **Discrepancy Description**: The stand-in, like the real gateway, reads `api_permissions` over its own connection. If `RoleService.create` or the permission replace is `@Transactional`, the insert is still uncommitted when the sync call goes out. The stand-in sees no rows and creates no realm role. The service tests mock `GatewaySyncClient`, so the first place this shows up is the Phase 5 exit check.
* **Rationale & Recommendation**: For both services, state "no class- or method-level `@Transactional`; the database step runs in its own `TransactionTemplate` and commits before `GatewaySyncService` is called". Add a test that, when the mocked `sync()` is called, reads the new rows from a separate `REQUIRES_NEW` transaction and finds them.

### 🟡 M-8. A missing required variable will not stop startup (B-13), and the `admin:` block is not written out - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`, `egm-codebase-details`, `egm-style`
* **Affected Scope:** Lines 1720 (claims B-13), 1725, 1727; datasource at 1667; section 9.4 (lines 1307-1316)
* **Discrepancy Description**:
  * Spring Boot's `@ConfigurationProperties` binder leaves an unresolvable placeholder as literal text. With `admin.keycloak.realm: ${KEYCLOAK_REALM}` and no such variable, the record receives `${KEYCLOAK_REALM}`, which passes `@NotBlank`. Startup succeeds with bad values. For example, a missing `JWT_ISSUER` gives 401 on every call instead of stopping startup.
  * No test covers the startup failure.
  * The plan does not say how the record is registered (`@ConfigurationPropertiesScan` or `@EnableConfigurationProperties`).
  * Line 1725 ("Every variable in section 9.4, bound to `admin.*`") is not true for `SERVER_PORT` (`server.port`), `DATABASE_*` (`spring.datasource.*`, already set at line 1667), `SPRINGDOC_ENABLED` (`springdoc.*`) and `TRUSTED_PROXIES` (`server.tomcat.remoteip.internal-proxies`, as the same row says).
* **Rationale & Recommendation**: Write out the `admin:` YAML block, with each property name, its environment variable and its default. Write every required value as `${VAR:}` (an empty default) and put `@NotBlank`/`@NotNull` and `@Validated` on the record, or add a check that rejects values starting with `${`. Name the registration annotation. Add an `ApplicationContextRunner` test showing that a missing `JWT_ISSUER`, `KEYCLOAK_REALM` or `GATEWAY_BASE_URL` fails startup with a message naming the variable. List which section 9.4 variables bind to `AdminProperties` and which bind elsewhere.

### 🟡 M-9. How tests replace the JWT decoder, and how tests stay apart in the shared database, are not specified - Severity: Major
* **Reviewer Lens:** `egm-codebase-details` (Major), `egm-style` (Major), `egm-implementation-readiness` (Nit)
* **Affected Scope:** Lines 1726, 1749-1751, 1674, 1681, 1796, 1830, 1864
* **R1:** follows from the round-1 B-1 and M-1 fixes
* **Discrepancy Description**:
  * `SecurityConfig` defines a `JwtDecoder` bean and `TestSecurityConfig` adds another. Spring Boot forbids bean overriding by default. If the resource server takes the decoder from the context, two beans give `NoUniqueBeanDefinitionException`. If `SecurityConfig` calls its own factory method directly, the test decoder is never used.
  * Line 1726 says "the test JWT settings point at the test key of `TestJwtFactory`", but that key is generated at runtime on each run (line 1749), so a YAML file cannot refer to it.
  * Tests share one database. Flows commit through `TransactionTemplate` and `REQUIRES_NEW`, so rolling back each test does not isolate them, and the append-only trigger blocks `DELETE FROM audit_log`. Assertions such as "exactly one `ROLE_SYNC_REQUESTED` row" depend on which tests ran first.
* **Rationale & Recommendation**: Mark the test decoder `@Primary`, and require `SecurityConfig` to take the decoder bean from the context. Reword line 1726: "The test issuer and audience equal the values `TestJwtFactory` signs with; the decoder comes from `TestSecurityConfig`." Add a `@BeforeEach` reset to `DatabaseTestBase`. It runs `TRUNCATE audit_log, user_roles, role_menus, users`, then deletes the non-seed rows of `roles`, `menus` and `api_permissions` (row-level triggers do not fire on `TRUNCATE`). Alternatively, require every assertion to filter by `entity_id`.

### 🟡 M-10. `ClientIpWebTest` cannot be written as described in Phase 3 - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`, `egm-codebase-details`
* **Affected Scope:** Line 1754; lines 1725 and 1737; section 10 (line 1363, MockMvc for web tests)
* **Discrepancy Description**: Forwarded-header handling is Tomcat's `RemoteIpValve`, through `server.forward-headers-strategy: native`. MockMvc never goes through Tomcat, so "with a trusted proxy, it does" can never pass as a MockMvc test. Phase 3 also has no endpoint that writes an audit row (only `/health` and `/me`), so there is no "audited IP" to observe.
* **Rationale & Recommendation**: Specify `@SpringBootTest(webEnvironment = RANDOM_PORT)` with a real HTTP client and two contexts: the default `TRUSTED_PROXIES`, and `TRUSTED_PROXIES=127\.0\.0\.1|0:0:0:0:0:0:0:1`. Either name a test-only controller that calls `AuditWriter`, or move the test to Phase 5 and drive it through `POST /roles`.

### 🟡 M-11. A request with no token may get 403 instead of 401 - Severity: Major
* **Reviewer Lens:** `egm-codebase-details`
* **Affected Scope:** Lines 1730 and 1733; rules at lines 413-424; test at line 1753
* **Discrepancy Description**: The two filters are added after `BearerTokenAuthenticationFilter`. With no `Authorization` header, that filter just continues, and the 401 is produced later, by `AuthorizationFilter` and `ExceptionTranslationFilter`. `CurrentUserFilter` therefore runs with an anonymous caller. If it looks up `sub` and writes a 403, the "missing token gives 401" case fails. The plan says only that the filter "skips the public paths".
* **Rationale & Recommendation**: Require both filters to pass the request on unchanged unless the authentication is a `JwtAuthenticationToken`. Alternatively, register them after `AuthorizationFilter`.

### 🟡 M-12. Exceptions thrown inside the security filters bypass `ApiExceptionHandler` - Severity: Major
* **Reviewer Lens:** `egm-codebase-details`
* **Affected Scope:** Lines 1730, 1735 and 1740; rule at line 539
* **Discrepancy Description**: `@RestControllerAdvice` never sees exceptions thrown inside servlet filters, for example a database outage during the local-user lookup. Spring Boot forwards them to `/error`, so the caller gets Boot's default error body, not the `ErrorBody` envelope with `INTERNAL_ERROR`. The reviewer adds that the error dispatch is itself secured and may come back as 401. That part depends on how Spring Security 6 restores the security context on an `ERROR` dispatch. This report did not confirm it, so treat the exact status as unverified. The envelope mismatch holds either way.
* **Rationale & Recommendation**: Require `CurrentUserFilter` and `PermissionCheckFilter` to catch unexpected exceptions and write the 500 `INTERNAL_ERROR` envelope through `SecurityErrorWriter`. State how `/error` is handled in `SecurityConfig` (for example, permit `DispatcherType.ERROR`). Add the case to `SecurityPipelineWebTest`.

### 🟡 M-13. The required structured `ERROR` log on every 502 has no file, format or test - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** Lines 1739-1740 (exceptions and handler), 1861 (`UserService`); requirement at lines 775-776 and 1178-1180
* **Discrepancy Description**: Section 5 requires one structured `ERROR` event per 502, with `operation`, `entityId`, `failedStep`, `completedSteps`, `keycloakUserId` and `auditLogId`. Under audit rule 5, it is the only record when the failure row itself cannot be written. The plan does not say which class emits it (`ApiExceptionHandler`, the flow, or `AuditWriter`). It does not say what "structured" means (key=value in the message, MDC, or Spring Boot structured logging), and no test checks it.
* **Rationale & Recommendation**: Give it to the `KeycloakSyncFailedException` branch of `ApiExceptionHandler`, fix the format, and assert it with an output-capture test in one 502 test in Phase 5 and one in Phase 6.

### 🟡 M-14. Supplying a custom RESTEasy client drops the Keycloak client's own Jackson and pool settings - Severity: Major
* **Reviewer Lens:** `egm-codebase-details`
* **Affected Scope:** Lines 1776-1777 (`KeycloakAdminClientGateway`, `KeycloakClientConfig`); line 1128
* **R1:** side effect of the round-1 N-13 fix (timeouts through a `ResteasyClientBuilder`)
* **Discrepancy Description**:
  * When a client is passed to `KeycloakBuilder.resteasyClient(...)`, `keycloak-admin-client` skips its own setup. It no longer registers `org.keycloak.admin.client.JacksonProvider`, which turns off `FAIL_ON_UNKNOWN_PROPERTIES`. The server (26.4.7) and client (26.0.12) versions differ, so any field the client does not know fails at runtime and surfaces as a 502.
  * The library's default `connectionPoolSize(10)` is lost, and the plan sets no pool size for a client shared by every Tomcat thread.
  * `ResteasyClientBuilder` is abstract, and the plan does not say how to build one.
  * `users().create(...)` returns a `jakarta.ws.rs.core.Response` that must be closed, or a pooled connection leaks on every user create.
* **Rationale & Recommendation**: Give the exact construction: `((ResteasyClientBuilder) ClientBuilder.newBuilder()).connectionPoolSize(10).connectTimeout(...).readTimeout(...).register(new org.keycloak.admin.client.JacksonProvider(), 100).build()`. For create, require `try (Response r = ...)` and `CreatedResponseUtil.getCreatedId(r)`.

### 🟡 M-15. `AccountLinker` (Phase 4) needs the `User` response shape, which is not created until Phase 6 - Severity: Major
* **Reviewer Lens:** `egm-codebase-details`
* **Affected Scope:** Lines 1781 and 1797 (Phase 4); line 1857 (Phase 6); section 8 (lines 1190 and 1197)
* **Discrepancy Description**: `AccountLinker` writes `USER_CREATED` with "after = User, plus `linked`", and section 8 says audit JSON is the resource in its API shape. The `User` record (`UserDtos.java`) is created in Phase 6, and no phase lists an entity-to-`User` mapper. In Phase 4, the implementer must invent a shape or pull Phase 6 work forward.
* **Rationale & Recommendation**: Move `UserDtos.User` and a named mapper (for example `{main}/service/UserViews.java`) into Phase 4, and have Phase 6 add only the request records.

### 🟡 M-16. The account-linking method has two names - Severity: Major
* **Reviewer Lens:** `egm-style` (Major), `egm-implementation-readiness` (Nit), `egm-codebase-details` (Nit)
* **Affected Scope:** Lines 1781 and 1861, against line 805
* **Discrepancy Description**: The plan creates a class `AccountLinker`, and line 1861 says `UserService` "calls `AccountLinker`". Section 5.1 calls the linking method `UserService.linkAccount`. A reader following section 5.1 will look for a method that does not exist. It is also not clear whether the bootstrap re-activation variant still calls `addRealmRoles`.
* **Rationale & Recommendation**: Use one name in both places, and state what the bootstrap variant calls.

### 🟡 M-17. The `admin-service` compose block is not written out, and the Phase 7 exit check would not catch a mistake in it - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** Line 1884; exit check at lines 1891-1893; sections 9.1 (line 1225) and 9.4 (line 1309)
* **Discrepancy Description**: "Adds `admin-service` … as in section 9.1" leaves the overrides to the implementer. Section 9.1 lists URLs but not variable names, and section 9.4 says separately that `JWT_JWK_SET_URI` must point inside the network. If that override is missed, the container fetches keys from `localhost:8180` and every authenticated call returns 401. The Phase 7 exit check only calls `/health`, so the error first shows up as failures across all of Phase 8.
* **Rationale & Recommendation**: Write out the block: `build: .`, `ports: ["127.0.0.1:8085:8085"]`, `env_file: .env`, `environment` overrides for `DATABASE_URL`, `KEYCLOAK_SERVER_URL`, `GATEWAY_BASE_URL` and `JWT_JWK_SET_URI=http://keycloak:8080/auth/realms/cce/protocol/openid-connect/certs`, and `depends_on` with conditions. Add an exit step: `GET /admin/v1/me` against the container with the admin token returns 200.

### 🟡 M-18. `LastSystemAdminConcurrencyTest` waits for a signal the second request cannot send - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness` (Major), `egm-codebase-details` (Nit), `egm-style` (Nit)
* **Affected Scope:** Line 1865
* **R1:** re-opens round-1 N-17
* **Discrepancy Description**: The first request holds the `SUPER_ADMIN` row lock while it waits on the latch inside `setEnabled`. The second request blocks on that row lock before it reaches any Keycloak call. If "has started" is signalled from inside the mock, the test deadlocks until the latch times out. If it is signalled when the second request is submitted, the test passes without proving that the two overlapped.
* **Rationale & Recommendation**: Have the test thread poll `pg_stat_activity` (or `pg_locks`) until the second transaction shows `wait_event_type = 'Lock'`, then release the first request. Set a latch timeout, and assert exactly one 200 and one 409 `LAST_SYSTEM_ADMIN`.

### 🟡 M-19. "No design decisions are left to the implementer" is stated as fact, and the plan does not meet it - Severity: Major
* **Reviewer Lens:** `egm-style` (Major), `egm-implementation-readiness` (Nit, in part)
* **Affected Scope:** Lines 1610-1611; examples at 1668, 1673, 1642, 1912
* **Discrepancy Description**: Besides the gaps in M-1, M-7, M-8 and M-17:
  * Line 1668 does not say what `application-test.yml` contains in Phase 2.
  * Line 1642 does not name the `.env` keys the token script reads.
  * Line 1912 does not say how `HardControlE2E` creates its "console user" or which role it holds.
  * S6 must create its user with an already-synced role before the stand-in switches to `fail`, and the plan does not say so.
* **Rationale & Recommendation**: Rewrite the bullet as an instruction: "Do not make design decisions. If a step needs one, or the design looks wrong, stop and raise it as a change to this document." Fill in the four rows above.

### 🟡 M-20. "Tests never read `.env`" contradicts `E2eEnvironment` - Severity: Major
* **Reviewer Lens:** `egm-style` (Major), `egm-implementation-readiness` (Nit)
* **Affected Scope:** Lines 1623-1624, against line 1902
* **R1:** follows from the round-1 B-1 fix
* **Discrepancy Description**: The bullet says tests never read `.env`, but `E2eEnvironment` reads `.env` by design.
* **Rationale & Recommendation**: "Spring tests never read `.env`: every Spring test runs with `@ActiveProfiles("test")`, and the `.env` import is active only outside that profile. Only the Phase 8 E2E tests read `.env`, through `E2eEnvironment`."

### 🟡 M-21. The fixture test asserts the wrong row count - Severity: Major
* **Reviewer Lens:** `egm-style` (Major), `egm-implementation-readiness` (Nit), `egm-codebase-details` (Nit)
* **Affected Scope:** Line 1679, against lines 1708-1713
* **Discrepancy Description**: "The two sample rows remain" does not match the fixture, which inserts five rows under two permission names. An implementer could assert the wrong count.
* **Rationale & Recommendation**: "The five fixture rows remain unchanged."

### 🟡 M-22. Phase 3 claims to close B-03 and B-04, which later phases finish - Severity: Major
* **Reviewer Lens:** `egm-style` (Major), `egm-implementation-readiness` (Nit), `egm-codebase-details` (Nit)
* **Affected Scope:** Lines 1720-1721; line 1755
* **R1:** re-opens round-1 N-5
* **Discrepancy Description**: The paginated endpoints (`GET /users`, `GET /roles`) arrive in Phases 6 and 5, and most error codes first appear in Phases 5 and 6. No test produces 500 `INTERNAL_ERROR` (see N-6).
* **Rationale & Recommendation**: "B-03 and B-04 for the envelope, error mapping and `PageParams`; completed in Phases 5 and 6", matching how B-06 is already qualified.

### 🟡 M-23. The guard test is described as checking more than it does - Severity: Major
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 1888 and 1930-1931
* **R1:** re-opens round-1 M-20 (and M-21)
* **Discrepancy Description**: `MutatingEndpointsAuditGuardTest` checks only that each non-GET mapping appears in a hand-maintained list. Line 1888 calls the list "endpoints whose audit rows are tested". Line 1931 says the test "keeps new mutating endpoints from skipping an audit test".
* **Rationale & Recommendation**: "It fails the build when a non-GET mapping is missing from the list, so a new mutating endpoint forces a review. It does not verify that an audit test exists."

### 🟡 M-24. Bare step numbers with no section - Severity: Major
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 1673 ("for step 5"), 1733 ("Step 4."), 1734 ("Step 5")
* **R1:** re-opens round-1 N-20
* **Discrepancy Description**: These refer to section 3, but the plan also cites flow steps such as "5.1 steps 5 and 6" (line 1781). For `CurrentUserFilter`, "Step 4." is the whole description.
* **Rationale & Recommendation**: "Section 3, step 4 (local user)" and "Section 3, step 5 (permission check)".

### 🟡 M-25. The `.env.example` placeholder rule is unclear - Severity: Major
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 1638 and 1646
* **Discrepancy Description**: "Every variable in section 9.3, with placeholder values such as `change-me`" does not say whether host-run values such as `DATABASE_URL=jdbc:postgresql://localhost:5433/...` are placeholders too. Exit-check step 1 ("set every placeholder") inherits the ambiguity.
* **Rationale & Recommendation**: "Secrets and the test administrator's details are `change-me`. The host-run values and `BOOTSTRAP_ADMIN_USERNAME` are written exactly as in 9.3."

### 🟡 M-26. The first command depends on the author's machine - Severity: Major
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Line 1634
* **Discrepancy Description**: "The machine's Maven 3.8.7 is used only for this one command" describes the author's workstation (copied from D-05), not the implementer's.
* **Rationale & Recommendation**: "Run this once with any installed Maven 3.x. Every later command uses `./mvnw`."

---

### 🟢 N-1. `FreshDatabase` has no step for running SQL with dollar-quoted bodies before Flyway
* **Lens:** `egm-implementation-readiness`, `egm-codebase-details`. **Scope:** lines 1664, 1675, 1679-1680.
* Two tests load a fixture first, and the fixture and V1 contain `$$` function bodies. `ScriptUtils` and `ResourceDatabasePopulator` split on `;` and break them. Add `runSql(Resource)` that sends the whole file in one JDBC `Statement.execute`, name the nested dollar-quote tags in V1 (for example `$do$` around `$fn$`), then `migrate()`.

### 🟢 N-2. The 12 fixed seed-menu UUIDs are not listed
* **Lens:** `egm-implementation-readiness`. **Scope:** line 1666. List them.

### 🟢 N-3. Other test wiring is unspecified
* **Lens:** `egm-implementation-readiness`. **Scope:** lines 1736, 1750-1751, 1753.
* The plan does not say how `TestActor` replaces `ActorProvider`, or how the "200 when the check is off" case gets its own context (for example, a separate class with `@TestPropertySource`).

### 🟢 N-4. `HealthController` breaks the controller rule
* **Lens:** `egm-implementation-readiness`. **Scope:** line 1744, against line 393.
* It runs `SELECT 1` itself, but section 2 allows a controller one service call and no data access. Add a `HealthService`, or record an exception.

### 🟢 N-5. The stand-in's tests have nothing to fake, and the stand-in has no version
* **Lens:** `egm-implementation-readiness`. **Scope:** lines 1784, 1790-1794. **R1:** N-2 in part.
* `SyncHandlerTest` uses a "fake store and client", but `PermissionStore` and `KeycloakRealmRoleClient` are concrete classes, and the stand-in's POM has no Mockito. Make both interfaces, and give the version (for example `0.1.0-SNAPSHOT`).

### 🟢 N-6. No test produces 500 `INTERNAL_ERROR`
* **Lens:** `egm-implementation-readiness`. **Scope:** line 1755; goal G3 (lines 103-104).
* Add a case (a mocked service that throws) to `ApiErrorShapeWebTest`.

### 🟢 N-7. Exit checks and E2E setup are less precise than elsewhere
* **Lens:** `egm-implementation-readiness`, `egm-style`. **Scope:** lines 1837, 1845-1848, 1870-1874, 1639, 1647, 1892, 1906-1912.
* The Phase 5 exit query is not limited to `PHASE5_ROLE`, so it only works once on a fresh database. Add `AND entity_id = (SELECT id FROM roles WHERE name = 'PHASE5_ROLE')`.
* The Phase 6 exit check gives no role name, request body or expected statuses (201, 200).
* Section 9.1 gives no health check for Mailpit. Say that Mailpit relies on its image's own check.
* The E2E naming scheme for created entities is not given.

### 🟢 N-8. The `e2e` profile's Surefire skip mechanism
* **Lens:** `egm-implementation-readiness`, `egm-codebase-details`. **Scope:** lines 1635, 1901.
* Setting the `skipTests` property skips Failsafe too, so `./mvnw -Pe2e verify` would pass with no tests run. Use Surefire's own `<configuration><skipTests>true</skipTests></configuration>` inside the profile, plus Failsafe `<includes>**/*E2E.java</includes>`. Surefire's default includes never match `*E2E.java`, so the Phase 1 exclusion has no effect.

### 🟢 N-9. Maven Shade under Spring Boot's parent
* **Lens:** `egm-codebase-details`. **Scope:** line 1784. **R1:** N-2 in part.
* The parent already configures `maven-shade-plugin` from `${start-class}`. Set `<start-class>org.cce.gatewaystub.GatewayStubMain</start-class>` instead of adding an execution.

### 🟢 N-10. The stand-in's explicit PostgreSQL driver version
* **Lens:** `egm-codebase-details`. **Scope:** lines 1617, 1784. **R1:** N-2 in part.
* The stand-in inherits Boot's parent, which manages `org.postgresql:postgresql`, so "outside Boot management" is inaccurate, and the explicit 42.7.14 may differ from the service's driver. Drop it, or state the override as intended.

### 🟢 N-11. Versions used but not in D-05
* **Lens:** `egm-style`, `egm-codebase-details`. **Scope:** lines 1615, 1642.
* Node 22 (for `scripts/token-claims.mjs`) and the host JDK 21 are not in D-05. Add them, or name them as README prerequisites outside the "every version comes from D-05" claim.

### 🟢 N-12. The `.gitignore` statement
* **Lens:** `egm-codebase-details`. **Scope:** line 1626.
* The reviewer could not check "the root `.gitignore` already ignores `.env` and `target/`". `egm-implementation-readiness` did check it against the repository's `.gitignore` and found it true. Optional: cite the file.

### 🟢 N-13. Jackson setup that Boot already provides
* **Lens:** `egm-codebase-details`. **Scope:** line 1728.
* A custom `ObjectMapper` bean replaces Boot's auto-configured mapper. Use a `Jackson2ObjectMapperBuilderCustomizer` that registers only the trimming deserializer, plus `spring.jackson.deserialization.fail-on-unknown-properties=true`.

### 🟢 N-14. Trimming does not reach query parameters
* **Lens:** `egm-codebase-details`. **Scope:** line 1728; rule at line 470.
* Add `@InitBinder` with `StringTrimmerEditor(true)` in a `@ControllerAdvice` for `q`, `facilityScope` and similar parameters.

### 🟢 N-15. The `WWW-Authenticate` header on 401
* **Lens:** `egm-codebase-details`. **Scope:** line 1735; rule at line 418.
* `SecurityErrorWriter` writes the 401 itself. Call `BearerTokenAuthenticationEntryPoint` first to set the header, then write the body.

### 🟢 N-16. The audience check can reuse Spring Security
* **Lens:** `egm-codebase-details`. **Scope:** line 1731.
* `JwtClaimValidator<List<String>>("aud", a -> a != null && a.contains(audience))` replaces the hand-written `AudienceValidator`.

### 🟢 N-17. Callers with no roles
* **Lens:** `egm-codebase-details`. **Scope:** line 1734.
* State that the filter returns 403 without querying when the role list is empty, so the query does not run with an empty `IN ()`.

### 🟢 N-18. The case-insensitive email check bypasses the index
* **Lens:** `egm-codebase-details`. **Scope:** line 1671.
* `existsByEmailIgnoreCase` compares `upper()` on both sides and does not use `ux_users_email_lower`. Emails are stored in lower case, so use `existsByEmail(email.toLowerCase(Locale.ROOT))`.

### 🟢 N-19. Bean injection in the audit guard test
* **Lens:** `egm-codebase-details`. **Scope:** line 1888.
* Inject `RequestMappingHandlerMapping` by the name `requestMappingHandlerMapping`.

### 🟢 N-20. How the gateway client's timeouts are applied
* **Lens:** `egm-codebase-details`. **Scope:** lines 1778-1779.
* Use `ClientHttpRequestFactoryBuilder.jdk().build(ClientHttpRequestFactorySettings.defaults().withConnectTimeout(..).withReadTimeout(..))`. The Apache HttpClient 4 that RESTEasy brings in is not supported by `RestClient`.

### 🟢 N-21. Redundant statements
* **Lens:** `egm-style`. **Scope:** lines 1608-1609, 1618-1620, 1634, 1784, 1902. **R1:** N-18.
* "Leaves the build green" and "`./mvnw verify` passes at the end of every phase" say the same thing, and "Phase 8 … runs last" repeats the numbering. Suggested text: "`./mvnw verify` passes at the end of every phase. It never runs the E2E tests, which Phase 8 adds under `-Pe2e`."
* The wrapper version and the stand-in's "only for plugin and library versions" are each stated twice.
* "8 digits, the last 8 digits of the epoch seconds" repeats itself.

### 🟢 N-22. The "What and why" column header
* **Lens:** `egm-style`. **Scope:** lines 1632, 1662, 1723, 1773, 1818, 1855, 1880, 1899.
* Most rows give only the "what". Rename the column "Contents", or add the reasons.

### 🟢 N-23. The secret-scan exit check gives no command
* **Lens:** `egm-style`. **Scope:** lines 1655-1656.
* Give it, for example `git grep -F -e "$POSTGRES_PASSWORD" -e "$KEYCLOAK_ADMIN_CLIENT_SECRET" …`.

### 🟢 N-24. "Every replace delete" is undefined
* **Lens:** `egm-style`. **Scope:** line 1673.
* "Every bulk delete used by a replace operation (5.4, 5.7, 5.11)".

### 🟢 N-25. The fixture's reconstruction label covers too much
* **Lens:** `egm-style`. **Scope:** lines 1683-1684.
* The columns, constraint and sample rows are quoted from the gateway doc. Only the index and trigger DDL are reconstructed. Say which is which, as section 6 (lines 1021-1035) already does.

### 🟢 N-26. Unnamed filter
* **Lens:** `egm-style`. **Scope:** line 1730. Name `BearerTokenAuthenticationFilter`.

### 🟢 N-27. "Logs the switch at startup"
* **Lens:** `egm-style`. **Scope:** line 1734.
* "Logs the value of `PERMISSION_CHECK_ENABLED` at startup, and a warning when it is `false`."

### 🟢 N-28. Ambiguous parenthetical
* **Lens:** `egm-style`. **Scope:** line 1753.
* "200 when a row with pattern `/admin/v1/**` matches `GET /admin/v1/me`."

### 🟢 N-29. "Closes" used for author decisions
* **Lens:** `egm-style`. **Scope:** line 1771. Use "Implements: AD-11, AD-13 …".

### 🟢 N-30. Filler adverb
* **Lens:** `egm-style`. **Scope:** line 1786. **R1:** N-21. Drop "plainly".

### 🟢 N-31. The stand-in's check order differs from section 9.1
* **Lens:** `egm-style`. **Scope:** line 1793. State which check runs first, the 503 (fail mode) or the 401.

### 🟢 N-32. "The test for 'is this row implicit'"
* **Lens:** `egm-style`. **Scope:** line 1823. It reads like a test class. Use "a predicate `isImplicit(method, uriPattern)`".

### 🟢 N-33. Unresolved names
* **Lens:** `egm-style`. **Scope:** lines 1878, 1885.
* Replace "the B-50 guard" with `MutatingEndpointsAuditGuardTest`. Point "the rollout notes" to the "Rollout and rollback" item in Non-Functional Requirements.

### 🟢 N-34. "Returns `UP`" is looser than Phase 3
* **Lens:** `egm-style`. **Scope:** line 1893. **R1:** N-22. Write `{"status":"UP","database":"UP"}`.

### 🟢 N-35. "A `RestClient` for the service, the stand-in and Mailpit"
* **Lens:** `egm-style`. **Scope:** line 1903. Say "one `RestClient` for each of …".

### 🟢 N-36. Scenario rows use inconsistent forms
* **Lens:** `egm-style`. **Scope:** lines 1906-1911. **R1:** N-23.
* Only the S1 row cites section 10. Add one lead-in sentence above the table and write every row the same way.

---

## Round-1 cross-reference

The round-1 Stage 9 findings that are raised again in this round. Each was fixed in round 1 (via the
consolidated action in brackets), so under the review-loop rules each counts as re-opened, not settled.

| Round-1 finding | Action applied | Round-2 finding | How it returns |
| --- | --- | --- | --- |
| B-1 (no test configuration) | CA-02 | M-9, M-20 | Fix added wording that is wrong ("Tests never read `.env`"; "point at the test key") |
| M-1 (MockMvc `jwt()` cannot produce 401s) | CA-23 | M-9 | Decoder replacement mechanism not specified |
| M-5 (id and timestamp generation) | CA-22 | M-2, M-3, M-4 | Fix's blanket rule does not fit three tables; no id strategy for `api_permissions`; merge on pre-set id |
| M-6 (`users.updatedAt` on role change) | CA-22 | M-6 | `clearAutomatically` can drop the change |
| M-8 (flush order and explicit-row delete) | CA-22 | M-1 | Fix's `deleteExplicitRows(name, implicitPairs)` cannot be bound in JPQL |
| M-15 (shared Postgres container) | CA-10 | B-1 | Fix's shared base class is not shared across classes |
| M-20 and M-21 (guard test overstated) | CA-27 | M-23 | Overstatement remains at lines 1888 and 1931 |
| N-2 (stand-in POM) | CA-07 | N-5, N-9, N-10 | `start-class`, driver override and version still open |
| N-5 ("Closes" lines, B-04 in Phase 3) | CA-27 | M-22 | Phase 3 still closes B-04 |
| N-12 (filters, transaction boundaries) | CA-22 | M-7, M-11 | Step boundary named only for `UserService`; anonymous requests in filters |
| N-13 (Keycloak timeouts) | CA-24 | M-14 | Custom client drops library defaults |
| N-16 (`roles.updated_at` on sync) | CA-22 | M-5 | Rule and test added, no mechanism |
| N-17 (fault injection and concurrency) | CA-23 | M-18 | Latch cannot observe the second request |
| N-18 (redundancy) | CA-33 | N-21 | Wrapper and stand-in facts still stated twice |
| N-20 (vague step references) | CA-33 | M-24 | Bare "Step 4" and "Step 5" remain |
| N-21 (loose wording) | CA-33 | N-30 | "plainly" remains at line 1786 |
| N-22 ("returns `UP`") | CA-33 | N-34 | Remains at line 1893 |
| N-23 (scenario rows) | CA-33 | N-36 | Remains at lines 1906-1911 |

Round-1 findings not raised again in round 2 include B-2 (gateway fixture), M-2 to M-4 (stand-in
compose and health checks), M-7 (snapshot clear), M-9 (audit actor), M-10 (null omission), M-12
(`scenario-rows.json`), M-13 (endpoint count, which codebase-details confirmed), M-14 (Keycloak 409),
M-16 (V2/V3 order), M-17 (`KeycloakUserInfo`), M-18 (`briefRepresentation`, confirmed) and M-19
(token response). A finding that no fresh reviewer raises is not proof that it is fixed. These
reviewers sample differently from round 1.

## Traceability Matrix

Raw finding IDs: `IR-` implementation-readiness, `CD-` codebase-details, `ST-` style, numbered in
each reviewer's own order (M = Major, N = Nit, B = Blocker).

| Raw | Report | Raw | Report | Raw | Report |
| --- | --- | --- | --- | --- | --- |
| IR-M1 | B-1 | CD-M10 | M-11 | ST-N12 | N-21 |
| IR-M2 | M-1 | CD-M11 | M-12 | ST-N13 | N-11 |
| IR-M3 | M-5 | CD-M12 | M-10 | ST-N14 | N-21 |
| IR-M4 | M-7 | CD-M13 | M-9 | ST-N15 | N-22 |
| IR-M5 | M-10 | CD-N1 | M-16, M-21, M-22, M-9 | ST-N16 | N-23 |
| IR-M6 | M-8 | CD-N2 | N-1 | ST-N17 | N-24 |
| IR-M7 | M-13 | CD-N3 | N-8 | ST-N18 | N-25 |
| IR-M8 | M-17 | CD-N4 | N-9 | ST-N19 | N-26 |
| IR-M9 | M-18 | CD-N5 | N-10 | ST-N20 | N-27 |
| IR-N1 | M-21 | CD-N6 | N-11 | ST-N21 | N-28 |
| IR-N2 | N-1 | CD-N7 | N-12 | ST-N22 | N-29 |
| IR-N3 | M-2, M-3, N-2 | CD-N8 | N-13 | ST-N23 | N-30 |
| IR-N4 | M-9, M-19, N-3 | CD-N9 | N-14 | ST-N24 | N-31 |
| IR-N5 | M-16 | CD-N10 | N-15 | ST-N25 | N-32 |
| IR-N6 | N-4 | CD-N11 | N-16 | ST-N26 | N-7 |
| IR-N7 | N-5 | CD-N12 | N-17 | ST-N27 | M-18 |
| IR-N8 | M-22, N-6 | CD-N13 | N-18 | ST-N28 | N-7 |
| IR-N9 | N-7, N-8, M-19, M-20 | CD-N14 | M-18 | ST-N29 | N-33 |
| CD-B1 | B-1 | CD-N15 | N-19 | ST-N30 | N-34 |
| CD-M1 | M-14 | CD-N16 | N-20 | ST-N31 | N-21 |
| CD-M2 | M-8 | ST-M1 | M-19 | ST-N32 | N-35 |
| CD-M3 | M-1 | ST-M2 | M-20 | ST-N33 | N-36 |
| CD-M4 | M-2 | ST-M3 | M-21 | | |
| CD-M5 | M-3 | ST-M4 | M-8 | | |
| CD-M6 | M-5 | ST-M5 | M-9 | | |
| CD-M7 | M-4 | ST-M6 | M-24 | | |
| CD-M8 | M-6 | ST-M7 | M-16 | | |
| CD-M9 | M-15 | ST-M8 | M-23 | | |
| | | ST-M9 | M-22 | | |
| | | ST-M10 | M-25 | | |
| | | ST-M11 | M-26 | | |

## Next step

This report stops at the Stage 9 checkpoint, as requested. No finding has been classified
(`PROPOSE FIX` or `CONTEST`), and `TargetFile` is unchanged.
