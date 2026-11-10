# Goldfish Review Report

**Target:** `docs/designs/cce-admin-service.md` (`Lines 1364-1653`, `## Detailed Implementation Plan` to end of file)

**Stage:** Stage 9 (Implementation Readiness Test), round 1

## Reviewer Status

| Reviewer | Status |
| --- | --- |
| `egm-implementation-readiness` | completed |
| `egm-codebase-details` | completed |
| `egm-style` | completed |

**Severity breakdown (after deduplication):** 2 Blocker, 22 Major, 23 Nit.

Raw counts before deduplication: implementation-readiness 1 / 15 / 13, codebase-details 1 / 11 / 12, style 0 / 5 / 12. When reviewers rated the same issue differently, this report uses the higher rating and lists every lens that raised it.

**Summary:** The reviewers found the phase structure, the per-phase file tables and the exit checks strong. The plan is not ready yet for two reasons. Tests have no configuration, so `./mvnw verify` cannot pass from a clean checkout. The gateway fixture is described as "copied" from DDL that the gateway doc does not contain. Most Major findings are cross-cutting choices the plan leaves to the implementer, such as JPA id and timestamp generation, flush order, the audit actor outside a request, test JWT signing and stub environment variable names. A few of these would cause real defects: a flaky S1 E2E because the snapshot is not cleared, NOT NULL violations, and `ServiceTokenProvider` failing on unknown JSON fields.

---

## Prioritized Findings (Blockers -> Major -> Nits)

### 🔴 B-1. No test configuration: Spring-context tests fail on a clean checkout and read the developer's `.env` on a dev machine - Severity: Blocker
* **Reviewer Lens:** `egm-implementation-readiness` (Blocker), `egm-codebase-details` (Major)
* **Affected Scope:** Lines 1400, 1437, 1454, 1480, 1483, 1506, 1551, 1641-1642
* **Discrepancy Description**:
  * From Phase 3, `AdminProperties` stops startup when a required value is missing (`JWT_ISSUER`, `JWT_AUDIENCE`, `KEYCLOAK_SERVER_URL`, `KEYCLOAK_REALM`, `KEYCLOAK_ADMIN_CLIENT_SECRET`, `GATEWAY_BASE_URL`).
  * No phase lists a `src/test/resources/application*.yml`, a test profile or a `@DynamicPropertySource`. The only source of values is `optional:file:.env` (line 1400). That file is absent in CI and in a fresh clone, so every `@SpringBootTest` fails there, even though line 1641 says these tests need "no running stack".
  * Where `.env` exists, every test context imports it. That includes `BOOTSTRAP_ADMIN_USERNAME`, so `BootstrapAdminRunner` (line 1506) runs in every context. It can call a live Keycloak and write audit rows, which breaks the exact audit-row assertions and makes results depend on the machine.
  * The plan does not say how `PostgresTestContainer` is attached (`@ImportTestcontainers`, a `@TestConfiguration` bean or a base class).
* **Rationale & Recommendation**: Any fix needs a file outside every phase's allowlist, or a design choice left to the implementer, and the standard says a plan that leaves one fails. Add a Phase 3 row for `src/test/resources/application-test.yml` (with `@ActiveProfiles("test")`) that sets fixed values for every required `admin.*` property. In the same row, turn off the `.env` import for tests (for example, put `spring.config.import` only in a non-test profile), guard `BootstrapAdminRunner` (for example `@Profile("!test")` or a property switch), and name how the Postgres container is attached.

### 🔴 B-2. Gateway fixture is "copied" from trigger and index DDL that the gateway doc does not contain - Severity: Blocker
* **Reviewer Lens:** `egm-codebase-details` (Blocker), `egm-implementation-readiness` (Major)
* **Affected Scope:** Lines 1438, 1441, 1428; relies on section 6 lines 895-899
* **Discrepancy Description**:
  * Line 1438 says `gateway-api-permissions.sql` is the gateway doc's DDL "with its trigger and its two sample `INSERT`s, copied from the gateway migration doc".
  * `context/gateway-service/DATABASE_PERMISSIONS_MIGRATION.md` contains only the `CREATE TABLE`. It names the two indexes (`idx_permission_name`, `idx_resource_category`) without columns. The trigger is one sentence of prose ("Automatically updates `updated_at`"), with no function name, trigger name, timing or body.
  * The doc has four `INSERT` statements (two samples plus "Option 1" and "Option 2"), so "its two sample INSERTs" is ambiguous.
  * V1 creates its own trigger only if the table has no `BEFORE UPDATE` trigger yet (line 899). `MigrationExistingGatewayTableTest`, the main safety case for AD-02, tests exactly that branch, so the invented trigger decides what the test proves. Section 6 also calls `BEFORE UPDATE` and the index columns "exactly as in the gateway migration doc", but both are inferences.
* **Rationale & Recommendation**: As written, the implementer has to invent the fixture. Write the full fixture SQL into the plan: index DDL with columns, trigger function name, trigger name, timing and body. Label the trigger and index parts as this design's reconstruction of the gateway DDL, not the gateway's own text, and record it as an assumption. Name the two sample `INSERT`s (`USER_READ`, `USER_CREATE_UPDATE`). Correct the "exactly as in" wording in section 6.

---

### 🟡 M-1. The MockMvc `jwt()` post-processor cannot produce the listed 401 cases - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`, `egm-codebase-details`
* **Affected Scope:** Line 1480
* **Discrepancy Description**: `SecurityMockMvcRequestPostProcessors.jwt()` installs an authentication that is already built, so the `JwtDecoder` and its validators never run. The listed cases (malformed, expired, wrong issuer, wrong audience) cannot be shown this way, and the B-10 validators (`AudienceValidator`, issuer, 30 s skew) go untested. No test JWT signing helper is listed.
* **Rationale & Recommendation**: Add a helper such as `{test}/support/TestJwtFactory.java`. It generates an RSA key and signs tokens with chosen `iss`, `aud`, `exp` and `nbf`. A test override of the `JwtDecoder` bean (`NimbusJwtDecoder.withPublicKey(...)`) must use the same validators as `SecurityConfig`, so state how `SecurityConfig` exposes them for reuse. Use `jwt()` only for the 403 and 200 cases.

### 🟡 M-2. The `gateway-stub` build context contradicts section 9.1 - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`, `egm-style`, `egm-codebase-details` (Nit)
* **Affected Scope:** Line 1507 against line 1042; line 1402 says "exactly as in section 9.1"
* **Discrepancy Description**: Section 9.1 says ``build `./gateway-stub` ``. Phase 4 uses context `.` with `dockerfile: gateway-stub/Dockerfile`. The Phase 4 form is the one that works, because the stub's pom reads `../src/main/java/...` (line 1508). The plan's own rule tells an implementer to stop on a contradiction like this.
* **Rationale & Recommendation**: Change section 9.1 to match Phase 4.

### 🟡 M-3. The stand-in gateway's environment variables are never named - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`, `egm-codebase-details`
* **Affected Scope:** Lines 1507, 1510, 1512 (and section 9.3)
* **Discrepancy Description**: `StubConfig` reads about nine values: DB URL, user and password; Keycloak internal URL and realm; client id and secret; issuer; audience; JWKS URL. Only `STUB_PORT` and `GATEWAY_STUB_CLIENT_SECRET` have names. `StubConfig`, the compose service and the stub README must all use the same names. The plan does not give the roles-claim path, does not say whether the stub uses `env_file: .env`, and does not say whether it has `depends_on` on `postgres` and `keycloak`.
* **Rationale & Recommendation**: Add a table with each variable's name, default and local compose value. For example: `STUB_DB_URL=jdbc:postgresql://postgres:5432/admin_service`, `STUB_ISSUER=http://localhost:8180/auth/realms/cce`, `STUB_JWKS_URL=http://keycloak:8080/auth/realms/cce/protocol/openid-connect/certs`, `STUB_AUDIENCE=gateway-service`, `STUB_KEYCLOAK_URL=http://keycloak:8080/auth`, `STUB_CLIENT_ID=cce-gateway-stub`, and roles path `realm_access.roles`. Also state the `env_file` and `depends_on` choices.

### 🟡 M-4. The Keycloak container health check cannot run as described - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`, `egm-codebase-details`
* **Affected Scope:** Line 1402 (Phase 1 exit check 2 depends on it)
* **Discrepancy Description**: `quay.io/keycloak/keycloak:26.4.7` ships without `curl` or `wget`, so "`GET /auth/health/ready` on management port 9000" cannot be written the obvious way. `docker compose up --wait` depends on a probe the implementer would have to invent. The path itself is correct, given `KC_HTTP_RELATIVE_PATH=/auth`.
* **Rationale & Recommendation**: Give the exact `healthcheck.test` (for example, a bash `/dev/tcp/localhost/9000` request that checks for `HTTP/1.1 200`) with its interval and retries. Also give the `pg_isready -U admin_service -d admin_service` arguments.

### 🟡 M-5. Id generation and insert-time timestamps on the owned entities are not specified - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** Line 1432
* **Discrepancy Description**: The plan names only `@PreUpdate` for `updated_at`. The columns use `uuid DEFAULT gen_random_uuid()` and `timestamptz NOT NULL DEFAULT now()`. JPA sends an explicit NULL for a field that is not set, which violates NOT NULL. Flow 5.1 also needs the id before the response, for the `Location` header. `audit_log.occurred_at` has the same problem.
* **Rationale & Recommendation**: State the id strategy (for example `@UuidGenerator` on the Java side) and how `created_at`, `updated_at` and `occurred_at` get their insert-time values (`@PrePersist`, `@CreationTimestamp`, or `insertable=false` plus a refresh).

### 🟡 M-6. `users.updatedAt` does not change on role changes - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** Line 1432 against line 615; affects lines 1574, 1577
* **Discrepancy Description**: Section 4.4 says `updatedAt` changes on profile, status and role changes. A role change (5.4) only rewrites `user_roles`, so `@PreUpdate` on `UserEntity` never fires.
* **Rationale & Recommendation**: State that `LOCAL_ROLES_SAVED` sets `users.updated_at` explicitly, and add that assertion to `UserRolesFlowTest`.

### 🟡 M-7. Creating or deleting a role does not clear the permission snapshot, which makes S1 flaky - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`, `egm-codebase-details`
* **Affected Scope:** Lines 1546-1547 (section 3 line 436; section 5.9)
* **Discrepancy Description**: Only `RolePermissionService` is told to clear `PermissionSnapshot`. `RoleService.create` inserts the two implicit `api_permissions` rows (5.6), and delete removes rows (5.9 says to clear). Section 3 step 5 requires a clear after every transaction that changes `api_permissions`. Without it, a new role's `/me/menus` row is missing from the snapshot for up to `PERMISSION_CACHE_TTL` (30 s). `ScenarioS1MenusE2E` (line 1622) and `HardControlE2E` create roles and then call `/me/menus` within seconds, so they would intermittently get 403.
* **Rationale & Recommendation**: Add the after-commit clear to `RoleService` create and delete. State how "after commit" is done (`TransactionSynchronization.afterCommit`, or a call after the transactional method returns). Add a test: create a role, then `/me/menus` is allowed at once.

### 🟡 M-8. Replace semantics will hit Hibernate's insert-before-delete flush order - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** Lines 1436, 1547, 1548, 1574
* **Discrepancy Description**:
  * `api_permissions (permission_name, http_method, uri_pattern)`, `role_menus (role_id, menu_id)` and `user_roles (user_id, role_id)` all have unique constraints.
  * If rows are deleted through entities and re-inserted in the same flush, Hibernate runs the INSERTs first. Any row that stays in the set then violates its constraint.
  * Separately, the repository offers only "a delete by `permission_name`", which also removes the implicit rows, while 5.7 says to delete explicit rows only.
* **Rationale & Recommendation**: Make each "delete by" method a bulk `@Modifying` query, or require a `flush()` after the delete. Settle whether the permission delete covers explicit rows only, and make the repository method match 5.7.

### 🟡 M-9. The audit actor outside a request is undefined - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** Line 1474; affects lines 1506, 1551, 1576-1578
* **Discrepancy Description**: `AuditWriter` takes the actor from `CurrentUser`, which is a request attribute. `BootstrapAdminRunner` runs with no request and must write `system:bootstrap`. The Phase 5 and 6 service tests call service methods directly, also with no request. `actor_username` is NOT NULL.
* **Rationale & Recommendation**: Define how the actor is supplied, for example a field on `AuditEntry` that overrides `CurrentUser`, or an `ActorProvider` bean with a system fallback. State how service tests set it.

### 🟡 M-10. "Null fields are left out" conflicts with the `PartialState` contract - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`, `egm-codebase-details`
* **Affected Scope:** Line 1470 against section 4.2 lines 549, 573-576
* **Discrepancy Description**: `PartialStateDto` sits under "Null fields are left out of the JSON". Section 4.2 types `entityId`, `keycloakUserId` and `auditLogId` as `uuid | null` and says `auditLogId` "is `null`". The admin-ui draft types them as `string | null`. Dropping the keys gives the console `undefined`. A global `non_null` setting would also drop the nullable `User`, `Role`, `Menu` and `Me` fields.
* **Rationale & Recommendation**: Limit null omission to `ApiResponse.pagination`, `ApiResponse.warnings`, `ErrorBody.details`, `ErrorBody.partialState`, and `Warning.menuId`/`parentMenuId`. State that every other field is always written, null included.

### 🟡 M-11. No file is given the trimming and empty-to-null rule - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** Lines 1455, 1541, 1570 (rule at lines 599-600)
* **Discrepancy Description**: "All strings are trimmed before they are validated", and empty optional strings are stored as null. Bean Validation does neither, and no file is given the job. Validating before or after trimming gives different results for the length and pattern rules. If `JacksonConfig` takes the job, a later phase must modify that file.
* **Rationale & Recommendation**: Name the mechanism (a Jackson trimming deserializer in `JacksonConfig`, record compact constructors, or service code) and the file that holds it.

### 🟡 M-12. The schema and content of `scenario-rows.json` are undefined - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`, `egm-codebase-details`
* **Affected Scope:** Lines 1600, 1602, 1624
* **Discrepancy Description**: G9's `OfflinePermissionRowCheckTest` and S3/S6 both use this file, but the plan does not give its JSON shape, the rows the scenarios save, the calls each row must allow or the "must not" call. The test also needs allowed and "must not" calls for the six seed rows and the two implicit rows, and the plan does not say where they live.
* **Rationale & Recommendation**: Write the schema (for example `[{ httpMethod, uriPattern, allow: [{method, path}], deny: {method, path} }]`) and the full content into the plan, including the seed-row and implicit-row calls.

### 🟡 M-13. The `/roles` endpoint count is wrong - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`, `egm-style`, `egm-codebase-details` (Nit)
* **Affected Scope:** Line 1542 against lines 1550, 1599, 1606
* **Discrepancy Description**: "The nine `/roles` endpoints" does not match section 4.5, which lists ten: `GET`/`POST /roles`; `GET`/`PUT`/`DELETE /roles/:id`; `GET`/`PUT` menus; `GET`/`PUT` permissions; `POST` sync. Ten plus the four `/menus` endpoints gives the 14 at line 1550. Under the plan's stop-on-contradiction rule, an implementer must halt here.
* **Rationale & Recommendation**: Change "nine" to "ten".

### 🟡 M-14. No rule for telling the two Keycloak 409 cases apart in 5.1 step 4 - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** Line 1574 (rule at line 757); test at line 1576
* **Discrepancy Description**: A Keycloak 409 "is mapped to `USERNAME_IN_USE` or `EMAIL_IN_USE`", with no rule for which. `UserCreateFlowTest` asserts both codes "in Keycloak", so the rule must be fixed.
* **Rationale & Recommendation**: State how to tell them apart, for example: after a 409, call `findUserIdByUsername`; if a user is found return `USERNAME_IN_USE`, otherwise `EMAIL_IN_USE`.

### 🟡 M-15. One shared Postgres container cannot give the migration tests the new database they need - Severity: Major
* **Reviewer Lens:** `egm-codebase-details`
* **Affected Scope:** Lines 1437, 1440-1442
* **Discrepancy Description**: `MigrationEmptyDatabaseTest` needs an empty database, and `MigrationExistingGatewayTableTest` needs a new database with the fixture applied first. A single shared `@ServiceConnection` container is migrated by whichever Spring context starts first. The plan does not say whether these tests start a Spring context.
* **Rationale & Recommendation**: State the mechanism. For example, each migration test creates a uniquely named database in the shared container and runs `Flyway.configure()` directly (with `baselineOnMigrate=true`, `baselineVersion=0`), without a Spring context.

### 🟡 M-16. V2's `ON CONFLICT ON CONSTRAINT` contradicts V3, and the seed runs before the shape check - Severity: Major
* **Reviewer Lens:** `egm-codebase-details`
* **Affected Scope:** Line 1429 (inherits section 6 lines 922-927)
* **Discrepancy Description**: V1 adds `unique_permission_method_uri` only if no unique constraint already covers the three columns, and V3 accepts such a constraint "whatever its name". If an existing table has it under another name, V2 fails with "constraint does not exist" before V3 can report anything. Even when the shapes match, V2 commits seed rows into a table that has not been checked yet.
* **Rationale & Recommendation**: Use `ON CONFLICT (permission_name, http_method, uri_pattern) DO NOTHING`, and run the shape check before the seed (verify as V2, seed as V3). Update `MigrationShapeMismatchTest` to match.

### 🟡 M-17. `KeycloakUserInfo` cannot supply what the bootstrapper inserts - Severity: Major
* **Reviewer Lens:** `egm-codebase-details`
* **Affected Scope:** Lines 1500, 1506 (section 7 line 976)
* **Discrepancy Description**: `getUser` returns only `enabled`, `emailVerified` and the realm role names. AD-15 step 5 inserts a `users` row whose `email`, `first_name` and `last_name` are NOT NULL. There is no rule for a Keycloak user with a missing name or a username that fails `^[a-z0-9][a-z0-9._-]*$`.
* **Rationale & Recommendation**: Add `username`, `email`, `firstName` and `lastName` to `KeycloakUserInfo`. Specify the bootstrap refusal (log and change nothing) when they are missing or invalid, and add that case to `BootstrapAdminRunnerTest`.

### 🟡 M-18. Listing realm roles needs `briefRepresentation=false` - Severity: Major
* **Reviewer Lens:** `egm-codebase-details` (Major), `egm-implementation-readiness` (Nit)
* **Affected Scope:** Lines 1516-1517
* **Discrepancy Description**: `GET /admin/realms/{realm}/roles` returns brief representations by default, without `attributes`. The planner then never sees `cce-managed=true` and never deletes anything (GW-4). `RealmRoleSyncPlannerTest` is pure logic and will not catch this.
* **Rationale & Recommendation**: Name the exact Admin REST calls: `GET .../roles?briefRepresentation=false`, `POST .../roles` with `attributes: {"cce-managed":["true"]}`, `DELETE .../roles/{name}`. Have the Phase 4 exit check confirm that a created role carries the attribute. If `POST` ignores attributes on this Keycloak version, add a follow-up `PUT`.

### 🟡 M-19. Global `FAIL_ON_UNKNOWN_PROPERTIES` will break `ServiceTokenProvider` - Severity: Major
* **Reviewer Lens:** `egm-codebase-details`
* **Affected Scope:** Lines 1455, 1503
* **Discrepancy Description**: If the token response is read through Boot's `RestClient.Builder`, it uses the customised `ObjectMapper`. Keycloak's token response has fields a minimal record will not declare (`refresh_expires_in`, `not-before-policy`, `scope` and others), so deserialization throws. The JDK `HttpServer` fake will likely return minimal JSON, so the bug would first show up as 502s in the Phase 5 demo.
* **Rationale & Recommendation**: State that the token response is read as `JsonNode`, or as a record with `@JsonIgnoreProperties(ignoreUnknown = true)`. Make the fake return a realistic Keycloak token body.

### 🟡 M-20. The guard-test claim says more than the test checks - Severity: Major
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Line 1603
* **Discrepancy Description**: The text says a new mutating endpoint "cannot be added without an audit test". The test only checks that each non-GET mapping appears in a fixed table, and anyone can add a row to the table without writing the test.
* **Rationale & Recommendation**: Reword: "so that a new mutating endpoint fails the build until it is added to the table of audit-tested endpoints". See also N-15.

### 🟡 M-21. "Enforced" overstates how PRD §11's done rule is checked - Severity: Major
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 1645-1647
* **Discrepancy Description**: PRD §11's rule ("the happy path and at least one error code per endpoint") is said to be "enforced by" the per-phase tests and `MutatingEndpointsAuditGuardTest`. Nothing fails the build when an endpoint lacks either test. The guard covers only non-GET mappings, and only whether they are listed for audit.
* **Rationale & Recommendation**: Replace "is enforced by" with "is covered by", and cite the guard test only for the audit part.

### 🟡 M-22. Two places still leave the implementer to decide - Severity: Major
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 1561, 1518 (against the rule at line 1372)
* **Discrepancy Description**: The exit check at line 1561 ("`SELECT action, outcome FROM audit_log` shows the matching rows") does not name the rows, so nobody can confirm it passed. At line 1518, "(401 or 403 otherwise)" does not say which failure gets which status.
* **Rationale & Recommendation**: List the expected `action`/`outcome` pairs, for example `ROLE_CREATED`/`SUCCESS`, `ROLE_SYNC_REQUESTED`/`SUCCESS`, `ROLE_MENUS_UPDATED`, then `ROLE_PERMISSIONS_UPDATED` followed by `ROLE_SYNC_REQUESTED`/`FAILED`. Reword line 1518: "401 when the token fails verification, 403 when no row allows the call".

---

### 🟢 N-1. "At the root of this folder" is ambiguous - Severity: Nit
* **Reviewer Lens:** `egm-implementation-readiness`, `egm-style`
* **Affected Scope:** Line 1375
* **Discrepancy Description**: The document lives in `docs/designs/`, so "this folder" has no clear referent. (The `.gitignore` claim at line 1389 checks out.)
* **Rationale & Recommendation**: Say "at the repository root".

### 🟢 N-2. Stand-in POM details are missing - Severity: Nit
* **Reviewer Lens:** `egm-implementation-readiness`, `egm-codebase-details`
* **Affected Scope:** Lines 1386, 1508
* **Discrepancy Description**:
  * No `<relativePath/>` on the parent, so Maven tries `../pom.xml` first and warns.
  * No `<version>`, so the name of the shaded jar used by `java -jar` in the Dockerfile is unknown.
  * `java.version` is not set to 21. Boot's parent defaults to 17, and AD-11 says Java 21.
  * The explicit `org.postgresql:postgresql` 42.7.14 is justified as being "outside Boot management", but the stub inherits Boot's parent.
  * The Dockerfile must also `COPY` `src/main/java/org/cce/admin/security/matcher/`.
* **Rationale & Recommendation**: Add `<relativePath/>`, a version and `java.version=21`. Set `start-class` instead of overriding the shade transformer. Drop or explain the Postgres override, and state the `COPY` line.

### 🟢 N-3. The path-parameter format in `endpoints.txt` is not given - Severity: Nit
* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** Lines 1477, 1482
* **Discrepancy Description**: The plan does not say whether paths use `:id` or `{id}`. `OpenApiDocumentTest` compares against springdoc, which uses `{id}`.
* **Rationale & Recommendation**: Fix the format to `{id}`, or state the conversion.

### 🟢 N-4. No test for `SyncHandler` - Severity: Nit
* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** Lines 1518-1519 (section 10 line 1136)
* **Discrepancy Description**: Section 10 says the stand-in tests cover the permission-row check, but no test is listed for `SyncHandler`'s token-role check.
* **Rationale & Recommendation**: Add a `SyncHandlerTest` row. The roles-claim path is covered in M-3.

### 🟢 N-5. The "Closes" lines are inaccurate and inconsistent - Severity: Nit
* **Reviewer Lens:** `egm-implementation-readiness`, `egm-style`
* **Affected Scope:** Lines 1393, 1449, 1536
* **Discrepancy Description**: Phase 3 "closes B-04" (pagination), but no paginated endpoint exists until Phases 5 and 6. B-13 is closed in both Phase 1 and Phase 3, and B-06 and B-50 are closed in parts. Phase 3 does not name milestone B1. "This is milestones B2 and B4" is ungrammatical.
* **Rationale & Recommendation**: Use "Closes … (part)" or "Contributes to" for partial coverage. Name B1 in Phase 3, and write "Completes milestones B2 and B4".

### 🟢 N-6. `ApiExceptionHandler` leaves out unsupported content type - Severity: Nit
* **Reviewer Lens:** `egm-implementation-readiness`, `egm-codebase-details`
* **Affected Scope:** Line 1468 (section 4.2 line 524)
* **Discrepancy Description**: Section 4.2 maps unsupported content type to 400 `VALIDATION_ERROR`, but Spring's default is 415.
* **Rationale & Recommendation**: Name the Spring 6.1 exception types to handle: `NoResourceFoundException`, `HttpRequestMethodNotSupportedException`, `HttpMediaTypeNotSupportedException`, `MethodArgumentTypeMismatchException`, `HttpMessageNotReadableException` (whose `UnrecognizedPropertyException` cause gives the unknown property), `MethodArgumentNotValidException` and `HandlerMethodValidationException`.

### 🟢 N-7. The health check's database wait is not bounded - Severity: Nit
* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** Line 1472
* **Discrepancy Description**: "`SELECT 1` with a 2-second timeout" does not limit the wait for a database connection, which defaults to 30 s in Hikari.
* **Rationale & Recommendation**: State whether the connection wait is bounded too (for example via `connection-timeout`).

### 🟢 N-8. The per-run suffix format is not given - Severity: Nit
* **Reviewer Lens:** `egm-implementation-readiness`, `egm-codebase-details`
* **Affected Scope:** Line 1617
* **Discrepancy Description**: The suffix must satisfy the role-name pattern (upper case), the username pattern (lower case, 3 to 50 characters) and the menu-key pattern at the same time.
* **Rationale & Recommendation**: Use digits only, and state the length.

### 🟢 N-9. Some response shapes have no record or file - Severity: Nit
* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** Lines 1541, 1570
* **Discrepancy Description**: No response record is named for reset-password `{ userId, email }`, for sync `{ roleId, syncStatus, lastSyncAt }` or for the permissions GET.
* **Rationale & Recommendation**: Name the file for each.

### 🟢 N-10. Section 9.2's `cce-insights-ui` purpose does not match the E2E - Severity: Nit
* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** Line 1064 against line 1619
* **Discrepancy Description**: Section 9.2 says `cce-insights-ui` exists "for S1 tokens", but the E2E gets every token from `cce-e2e`, and `cce-insights-ui` has no direct-access grant.
* **Rationale & Recommendation**: Correct section 9.2, or have the E2E use the client as stated.

### 🟢 N-11. Phase 1 exit checks 3 and 4 cannot be run as written - Severity: Nit
* **Reviewer Lens:** `egm-implementation-readiness`, `egm-codebase-details`, `egm-style`
* **Affected Scope:** Lines 1411, 1417
* **Discrepancy Description**: Check 3 uses `$CCE_TEST_ADMIN_USERNAME` without first loading `.env` into the shell. Check 4 gives no command.
* **Rationale & Recommendation**: Add `set -a; . ./.env; set +a`, and give the `grant_type=client_credentials` curl call for check 4.

### 🟢 N-12. Filter registration and transaction boundaries are not specified - Severity: Nit
* **Reviewer Lens:** `egm-implementation-readiness`, `egm-codebase-details`
* **Affected Scope:** Lines 1434, 1457, 1460-1463
* **Discrepancy Description**:
  * If `CurrentUserFilter` and `PermissionCheckFilter` are Spring beans, Boot also registers them as servlet filters, so they run twice.
  * The plan does not say that `CurrentUserFilter` skips requests without JWT authentication (public paths).
  * `updateLastLoginIfLater` is a `@Modifying` query called from a filter, outside any transaction.
  * "One transaction per step" has no stated mechanism, which invites the `@Transactional` self-invocation trap.
* **Rationale & Recommendation**: Say that the filters are not beans, or register them with `FilterRegistrationBean.setEnabled(false)`. State the public-path skip. Mark `updateLastLoginIfLater` `@Transactional`. Name the step-boundary mechanism (`TransactionTemplate` or separate beans).

### 🟢 N-13. `KeycloakGateway` 404 handling and timeouts - Severity: Nit
* **Reviewer Lens:** `egm-codebase-details`
* **Affected Scope:** Lines 1501-1502 (section 7)
* **Discrepancy Description**: "Every other failure" becoming `KeycloakCallFailedException` conflicts with section 7, where the lookups return empty on 404. `KeycloakBuilder` has no timeout setters.
* **Rationale & Recommendation**: State that a 404 on a lookup means empty. Apply the timeouts through a `ResteasyClientBuilder` (`connectTimeout`, `readTimeout`) passed to `.resteasyClient(...)`.

### 🟢 N-14. `ClientIpResolver` leaves out the forwarded-for switch - Severity: Nit
* **Reviewer Lens:** `egm-codebase-details`
* **Affected Scope:** Line 1465 (section 8)
* **Discrepancy Description**: The `AUDIT_TRUST_FORWARDED_FOR` switch from section 8 is not mentioned.
* **Rationale & Recommendation**: Add it to the `ClientIpResolver` row.

### 🟢 N-15. The audit guard test would count Boot's `/error` mapping - Severity: Nit
* **Reviewer Lens:** `egm-codebase-details`
* **Affected Scope:** Line 1603
* **Discrepancy Description**: `RequestMappingHandlerMapping` includes Boot's `/error`, which has no method restriction, so the guard would count it as a non-GET endpoint.
* **Rationale & Recommendation**: Limit the guard to handlers in `org.cce.admin.controller`.

### 🟢 N-16. Whether sync updates bump `roles.updated_at` is not stated - Severity: Nit
* **Reviewer Lens:** `egm-codebase-details`
* **Affected Scope:** Lines 1432, 1505
* **Discrepancy Description**: `@PreUpdate` would bump `updated_at` when `sync_status` or `last_sync_at` changes, but a bulk JPQL update under `GLOBAL` would not. Section 4.4 settles this only for users (AD-05).
* **Rationale & Recommendation**: State the rule for roles.

### 🟢 N-17. Fault injection and concurrency methods for the flow tests are not given - Severity: Nit
* **Reviewer Lens:** `egm-codebase-details`
* **Affected Scope:** Lines 1576, 1578
* **Discrepancy Description**: The plan does not say how "the database insert fails after Keycloak succeeded" is injected, or how `LastSystemAdminConcurrencyTest` makes its two requests overlap. Without a forced overlap, the test can pass with the requests running one after the other.
* **Rationale & Recommendation**: Name the mechanisms, for example a spy repository that throws, and a latch inside the mocked Keycloak call.

### 🟢 N-18. Redundant statements - Severity: Nit
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 1370, 1371, 1382-1384 against 1397, and 1383-1384 against 1508
* **Discrepancy Description**: "From phase 1 on" adds nothing. "Runs last" repeats the phase order. The wrapper version pin and the reason for the stub's Spring parent are each stated twice. "Keeps the stand-in free of any web framework" repeats AD-11.
* **Rationale & Recommendation**: Keep each fact in one place. If "runs last" stays, reword it to say that E2E is outside `./mvnw verify`.

### 🟢 N-19. Duplicate source and owner statements - Severity: Nit
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 1438, 1453, 1455
* **Discrepancy Description**: Line 1438 names the gateway doc as the source twice. The ISO-8601 date setting is assigned to both `application.yml` (1453) and `JacksonConfig` (1455).
* **Rationale & Recommendation**: Pick one owner for the date setting, and name the source once.

### 🟢 N-20. Vague step and section references - Severity: Nit
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 1460-1462, 1521, 1574
* **Discrepancy Description**: "Step 4" and "step 5" name no section. "The right status updates" and "the stated order … step names" are vague.
* **Rationale & Recommendation**: Write "section 3, step 4" (the style used at line 1478), "the status updates in section 5.10", and "the order and step names in sections 5.1 to 5.5".

### 🟢 N-21. Loose wording - Severity: Nit
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 1510, 1517, 1618
* **Discrepancy Description**: "Pure logic" is jargon, "a small JSON client" has a filler word, and so does "states plainly".
* **Rationale & Recommendation**: Write "No I/O", and drop "small" and "plainly".

### 🟢 N-22. Unclear subject and loose expected output - Severity: Nit
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 1529-1532, 1608
* **Discrepancy Description**: In the bullet at line 1531, the subject of "it returns 503" is unclear. Line 1608 says "returns `UP`", which is looser than line 1489.
* **Rationale & Recommendation**: Repeat "the `POST`" as the subject, and write `{"status":"UP","database":"UP"}`.

### 🟢 N-23. Scenario rows use inconsistent forms - Severity: Nit
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 1623-1627
* **Discrepancy Description**: The S2 row says "as in section 10", while the S4 to S6 rows give only the scenario ID.
* **Rationale & Recommendation**: Add one lead-in sentence ("Each scenario follows section 10") and write every row the same way.
