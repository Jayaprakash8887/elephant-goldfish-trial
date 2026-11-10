package org.cce.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.cce.admin.client.KeycloakCallFailedException;
import org.cce.admin.client.KeycloakConflictException;
import org.cce.admin.client.KeycloakUserInfo;
import org.cce.admin.controller.dto.UserDtos.CreateUserRequest;
import org.cce.admin.controller.dto.UserDtos.User;
import org.cce.admin.entity.AuditEntityType;
import org.cce.admin.entity.AuditLogEntity;
import org.cce.admin.entity.AuditOutcome;
import org.cce.admin.entity.RoleEntity;
import org.cce.admin.entity.UserEntity;
import org.cce.admin.entity.UserRoleEntity;
import org.cce.admin.entity.UserStatus;
import org.cce.admin.repository.AuditLogRepository;
import org.cce.admin.repository.RoleRepository;
import org.cce.admin.repository.UserRepository;
import org.cce.admin.repository.UserRoleRepository;
import org.cce.admin.service.exception.ApiException;
import org.cce.admin.service.exception.ErrorCode;
import org.cce.admin.service.exception.KeycloakSyncFailedException;
import org.cce.admin.service.exception.PartialState;
import org.cce.admin.support.DatabaseTestBase;
import org.cce.admin.support.TestJwtFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/** Flow 5.1 (POST /users, operation USER_CREATE): every step, linking, and the audit rows of section 8. */
@ExtendWith(OutputCaptureExtension.class)
class UserCreateFlowTest extends DatabaseTestBase {

    private static final UUID CCE_ADMIN_SERVICE_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");

    @Autowired
    private UserService userService;

    @MockitoSpyBean
    private UserRepository users;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private AuditLogRepository auditLog;

    @Autowired
    private MockMvc mockMvc;

    private RoleEntity alpha;
    private RoleEntity beta;
    private UUID createdKeycloakId;

    @BeforeEach
    void setUp() {
        alpha = role("CREATE_ALPHA");
        beta = role("CREATE_BETA");
        createdKeycloakId = UUID.randomUUID();
        when(keycloakGateway.listRealmRoleNames()).thenReturn(Set.of("CREATE_ALPHA", "CREATE_BETA", "SUPER_ADMIN"));
        when(keycloakGateway.createUser(anyString(), anyString(), anyString(), anyString(), anyList()))
                .thenReturn(createdKeycloakId);
    }

    private RoleEntity role(String name) {
        RoleEntity role = new RoleEntity();
        role.setName(name);
        role.setDisplayName("Display " + name);
        role.setSystem(false);
        role.setAssignable(true);
        return roleRepository.save(role);
    }

    private CreateUserRequest request(String username, String email, List<UUID> roleIds) {
        return new CreateUserRequest(username, email, "New", "Person", "FAC-1", roleIds);
    }

    private CreateUserRequest request() {
        return request("new-person", "New.Person@CCE.local", List.of(beta.getId(), alpha.getId()));
    }

    private static KeycloakUserInfo account(UUID id, String username, String email, boolean enabled) {
        return new KeycloakUserInfo(id, username, email, "Kc", "Names", enabled, true, List.of("default-roles-cce"));
    }

    private UserEntity localUser(String username, String email) {
        UserEntity user = new UserEntity();
        user.setKeycloakId(UUID.randomUUID());
        user.setUsername(username);
        user.setEmail(email);
        user.setFirstName("Local");
        user.setLastName("User");
        user.setStatus(UserStatus.ACTIVE);
        return users.save(user);
    }

    private static ApiException expectCode(Runnable call, ErrorCode code) {
        ApiException e = catchThrowableOfType(ApiException.class, call::run);
        assertThat(e).as("expected " + code).isNotNull();
        assertThat(e.code()).isEqualTo(code);
        return e;
    }

    private List<UUID> roleIdsOf(UUID userId) {
        return userRoleRepository.findByUserId(userId).stream().map(UserRoleEntity::getRoleId).toList();
    }

    // ------------------------------------------------------------------------------ happy path

    @Test
    void createsTheAccountAssignsTheRolesSavesTheUserAndSendsTheEmail() {
        UserService.UserCreated created = userService.create(request());

        InOrder order = inOrder(keycloakGateway);
        order.verify(keycloakGateway).findUsersByUsernameOrEmail("new-person", "new.person@cce.local");
        order.verify(keycloakGateway).listRealmRoleNames();
        order.verify(keycloakGateway).createUser("new-person", "new.person@cce.local", "New", "Person",
                List.of("UPDATE_PASSWORD"));
        order.verify(keycloakGateway).addRealmRoles(createdKeycloakId, List.of("CREATE_BETA", "CREATE_ALPHA"));
        order.verify(keycloakGateway).sendExecuteActionsEmail(createdKeycloakId, List.of("UPDATE_PASSWORD"));

        User user = created.user();
        assertThat(created.warnings()).isEmpty();
        assertThat(user.keycloakId()).isEqualTo(createdKeycloakId);
        assertThat(user.username()).isEqualTo("new-person");
        assertThat(user.email()).isEqualTo("new.person@cce.local");
        assertThat(user.firstName()).isEqualTo("New");
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.facilityScope()).isEqualTo("FAC-1");
        assertThat(user.lastLoginAt()).isNull();
        assertThat(user.roles()).extracting(r -> r.name()).containsExactly("CREATE_ALPHA", "CREATE_BETA");
        assertThat(user.createdAt()).isEqualTo(users.findById(user.id()).orElseThrow().getCreatedAt());
        assertThat(roleIdsOf(user.id())).containsExactlyInAnyOrder(alpha.getId(), beta.getId());

        List<AuditLogEntity> rows = auditLog.findAll();
        assertThat(rows).hasSize(1);
        AuditLogEntity row = rows.get(0);
        assertThat(row.getAction()).isEqualTo("USER_CREATED");
        assertThat(row.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(row.getEntityType()).isEqualTo(AuditEntityType.USER);
        assertThat(row.getEntityId()).isEqualTo(user.id());
        assertThat(row.getBeforeState()).isNull();
        assertThat(row.getAfterState().path("linked").asBoolean()).isFalse();
        assertThat(row.getAfterState().path("keycloakId").asText()).isEqualTo(createdKeycloakId.toString());
    }

    // --------------------------------------------------- step 6 fails after Keycloak (B-32, PRD case)

    @Test
    void aDatabaseFailureAfterKeycloakSucceededGives502AndAFailedRowAndResubmittingLinks() {
        doThrow(new DataAccessResourceFailureException("database down")).when(users).save(any(UserEntity.class));

        KeycloakSyncFailedException e = catchThrowableOfType(KeycloakSyncFailedException.class,
                () -> userService.create(request()));

        PartialState state = e.partialState();
        assertThat(e.code()).isEqualTo(ErrorCode.KEYCLOAK_SYNC_FAILED);
        assertThat(state.operation()).isEqualTo("USER_CREATE");
        assertThat(state.failedStep()).isEqualTo("LOCAL_USER_SAVED");
        assertThat(state.completedSteps()).containsExactly("KEYCLOAK_USER_CREATED", "KEYCLOAK_ROLES_ASSIGNED");
        assertThat(state.keycloakUserId()).isEqualTo(createdKeycloakId);
        assertThat(state.auditLogId()).isNotNull();
        assertThat(users.findById(state.entityId())).isEmpty();
        verify(keycloakGateway, never()).sendExecuteActionsEmail(any(), anyList());

        AuditLogEntity failure = auditLog.findById(state.auditLogId()).orElseThrow();
        assertThat(failure.getAction()).isEqualTo("USER_CREATED");
        assertThat(failure.getOutcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(failure.getEntityId()).isEqualTo(state.entityId());
        assertThat(failure.getFailure().path("keycloakUserId").asText()).isEqualTo(createdKeycloakId.toString());
        assertThat(failure.getFailure().path("failedStep").asText()).isEqualTo("LOCAL_USER_SAVED");
        assertThat(auditLog.count()).isEqualTo(1);

        // Resubmitting: the account the first attempt created now exists, with no local user, so it is linked.
        reset(users);
        when(keycloakGateway.findUsersByUsernameOrEmail("new-person", "new.person@cce.local"))
                .thenReturn(List.of(account(createdKeycloakId, "new-person", "new.person@cce.local", true)));

        UserService.UserCreated created = userService.create(request());

        verify(keycloakGateway, times(1)).createUser(anyString(), anyString(), anyString(), anyString(), anyList());
        verify(keycloakGateway, times(2)).addRealmRoles(createdKeycloakId, List.of("CREATE_BETA", "CREATE_ALPHA"));
        verify(keycloakGateway).sendExecuteActionsEmail(createdKeycloakId, List.of("UPDATE_PASSWORD"));
        assertThat(created.user().keycloakId()).isEqualTo(createdKeycloakId);
        assertThat(created.user().firstName()).isEqualTo("New");
        AuditLogEntity success = auditLog.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(AuditEntityType.USER,
                created.user().id()).get(0);
        assertThat(success.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(success.getAfterState().path("linked").asBoolean()).isTrue();
        assertThat(auditLog.count()).isEqualTo(2);
    }

    @Test
    void the502IsLoggedAsOneStructuredLine(CapturedOutput output) throws Exception {
        UserEntity admin = localUser("create-admin", "create-admin@cce.local");
        String token = TestJwtFactory.token().subject(admin.getKeycloakId()).roles("SUPER_ADMIN").bearer();
        doThrow(new DataAccessResourceFailureException("database down")).when(users).save(any(UserEntity.class));

        mockMvc.perform(post("/admin/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"logged-user\",\"email\":\"logged-user@cce.local\",\"firstName\":\"L\","
                                + "\"lastName\":\"U\",\"roleIds\":[\"" + alpha.getId() + "\"]}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("KEYCLOAK_SYNC_FAILED"))
                .andExpect(jsonPath("$.error.partialState.operation").value("USER_CREATE"))
                .andExpect(jsonPath("$.error.partialState.failedStep").value("LOCAL_USER_SAVED"))
                .andExpect(jsonPath("$.error.partialState.keycloakUserId").value(createdKeycloakId.toString()));

        AuditLogEntity row = auditLog.findAll().get(0);
        assertThat(output.getOut()).contains("event=keycloak_sync_failed operation=USER_CREATE entityId="
                + row.getEntityId() + " failedStep=LOCAL_USER_SAVED"
                + " completedSteps=KEYCLOAK_USER_CREATED,KEYCLOAK_ROLES_ASSIGNED keycloakUserId=" + createdKeycloakId
                + " auditLogId=" + row.getId());
    }

    // ------------------------------------------------------------------- step 5 and step 4 failures

    @Test
    void aRoleAssignmentFailureGives502WithTheFailedRowAndResubmittingCompletes() {
        doThrow(new KeycloakCallFailedException("HTTP 500", 500, null))
                .when(keycloakGateway).addRealmRoles(any(), anyCollection());

        KeycloakSyncFailedException e = catchThrowableOfType(KeycloakSyncFailedException.class,
                () -> userService.create(request()));

        assertThat(e.partialState().failedStep()).isEqualTo("KEYCLOAK_ROLES_ASSIGNED");
        assertThat(e.partialState().completedSteps()).containsExactly("KEYCLOAK_USER_CREATED");
        assertThat(e.partialState().keycloakUserId()).isEqualTo(createdKeycloakId);
        assertThat(auditLog.findById(e.partialState().auditLogId()).orElseThrow().getOutcome())
                .isEqualTo(AuditOutcome.FAILED);

        reset(keycloakGateway);
        when(keycloakGateway.listRealmRoleNames()).thenReturn(Set.of("CREATE_ALPHA", "CREATE_BETA"));
        when(keycloakGateway.findUsersByUsernameOrEmail(anyString(), anyString()))
                .thenReturn(List.of(account(createdKeycloakId, "new-person", "new.person@cce.local", true)));

        User user = userService.create(request()).user();

        verify(keycloakGateway, never()).createUser(anyString(), anyString(), anyString(), anyString(), anyList());
        assertThat(user.keycloakId()).isEqualTo(createdKeycloakId);
        assertThat(auditLog.count()).isEqualTo(2);
    }

    @Test
    void aKeycloakCreateFailureGives502WithNothingChangedAndNoRow() {
        when(keycloakGateway.createUser(anyString(), anyString(), anyString(), anyString(), anyList()))
                .thenThrow(new KeycloakCallFailedException("timeout", null, null));

        KeycloakSyncFailedException e = catchThrowableOfType(KeycloakSyncFailedException.class,
                () -> userService.create(request()));

        assertThat(e.partialState().operation()).isEqualTo("USER_CREATE");
        assertThat(e.partialState().failedStep()).isEqualTo("KEYCLOAK_USER_CREATED");
        assertThat(e.partialState().completedSteps()).isEmpty();
        assertThat(e.partialState().keycloakUserId()).isNull();
        assertThat(e.partialState().auditLogId()).isNull();
        assertThat(auditLog.count()).isZero();
        assertThat(users.count()).isZero();
        verify(keycloakGateway, never()).addRealmRoles(any(), anyCollection());
    }

    @Test
    void aFailedAccountLookupGives502WithNothingChanged() {
        when(keycloakGateway.findUsersByUsernameOrEmail(anyString(), anyString()))
                .thenThrow(new KeycloakCallFailedException("HTTP 503", 503, null));

        KeycloakSyncFailedException e = catchThrowableOfType(KeycloakSyncFailedException.class,
                () -> userService.create(request()));

        assertThat(e.partialState().failedStep()).isEqualTo("KEYCLOAK_ACCOUNTS_CHECKED");
        assertThat(e.partialState().completedSteps()).isEmpty();
        assertThat(e.partialState().auditLogId()).isNull();
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void aKeycloak409RaceIsResolvedByLookingUpTheUsername() {
        when(keycloakGateway.createUser(anyString(), anyString(), anyString(), anyString(), anyList()))
                .thenThrow(new KeycloakConflictException("409", null));
        when(keycloakGateway.findUserByUsername("new-person"))
                .thenReturn(Optional.of(account(UUID.randomUUID(), "new-person", "x@cce.local", true)));
        expectCode(() -> userService.create(request()), ErrorCode.USERNAME_IN_USE);

        when(keycloakGateway.findUserByUsername("new-person")).thenReturn(Optional.empty());
        expectCode(() -> userService.create(request()), ErrorCode.EMAIL_IN_USE);

        verify(keycloakGateway, never()).addRealmRoles(any(), anyCollection());
        assertThat(auditLog.count()).isZero();
        assertThat(users.count()).isZero();
    }

    // ------------------------------------------------------------------------------ linking (AD-18)

    @Test
    void anEnabledAccountWithTheSameUsernameAndEmailIsLinked() {
        UUID existing = UUID.randomUUID();
        when(keycloakGateway.findUsersByUsernameOrEmail("new-person", "new.person@cce.local"))
                .thenReturn(List.of(account(existing, "new-person", "new.person@cce.local", true)));

        UserService.UserCreated created = userService.create(request());

        verify(keycloakGateway, never()).createUser(anyString(), anyString(), anyString(), anyString(), anyList());
        verify(keycloakGateway).addRealmRoles(existing, List.of("CREATE_BETA", "CREATE_ALPHA"));
        verify(keycloakGateway).sendExecuteActionsEmail(existing, List.of("UPDATE_PASSWORD"));
        assertThat(created.user().keycloakId()).isEqualTo(existing);
        assertThat(created.user().firstName()).isEqualTo("New");
        assertThat(created.user().lastName()).isEqualTo("Person");
        AuditLogEntity row = auditLog.findAll().get(0);
        assertThat(row.getAction()).isEqualTo("USER_CREATED");
        assertThat(row.getAfterState().path("linked").asBoolean()).isTrue();
    }

    @Test
    void aDisabledMatchIsRefusedWithTheReason() {
        when(keycloakGateway.findUsersByUsernameOrEmail(anyString(), anyString()))
                .thenReturn(List.of(account(UUID.randomUUID(), "new-person", "new.person@cce.local", false)));

        ApiException e = expectCode(() -> userService.create(request()), ErrorCode.USERNAME_IN_USE);

        assertThat(e.getMessage()).contains("no local user").contains("cannot be linked").contains("disabled");
        verify(keycloakGateway, never()).createUser(anyString(), anyString(), anyString(), anyString(), anyList());
        verify(keycloakGateway, never()).addRealmRoles(any(), anyCollection());
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void aMatchOnOneFieldOnlyIsRefusedWithTheReason() {
        when(keycloakGateway.findUsersByUsernameOrEmail(anyString(), anyString()))
                .thenReturn(List.of(account(UUID.randomUUID(), "new-person", "other@cce.local", true)));
        ApiException byUsername = expectCode(() -> userService.create(request()), ErrorCode.USERNAME_IN_USE);
        assertThat(byUsername.getMessage()).contains("cannot be linked").contains("email address differs");

        when(keycloakGateway.findUsersByUsernameOrEmail(anyString(), anyString()))
                .thenReturn(List.of(account(UUID.randomUUID(), "someone-else", "new.person@cce.local", true)));
        ApiException byEmail = expectCode(() -> userService.create(request()), ErrorCode.EMAIL_IN_USE);
        assertThat(byEmail.getMessage()).contains("address belongs to a Keycloak account with no local user")
                .contains("username differs");

        verify(keycloakGateway, never()).createUser(anyString(), anyString(), anyString(), anyString(), anyList());
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void anAccountAlreadyLinkedToAnotherLocalUserIsRefused() {
        UserEntity other = localUser("other-user", "other-user@cce.local");
        when(keycloakGateway.findUsersByUsernameOrEmail(anyString(), anyString()))
                .thenReturn(List.of(account(other.getKeycloakId(), "new-person", "new.person@cce.local", true)));

        ApiException e = expectCode(() -> userService.create(request()), ErrorCode.USERNAME_IN_USE);
        assertThat(e.getMessage()).contains("already linked");
    }

    // ---------------------------------------------------------------------- steps 1 to 3: refusals

    @Test
    void aTakenUsernameOrEmailIsRefusedBeforeKeycloak() {
        localUser("new-person", "first@cce.local");
        expectCode(() -> userService.create(request()), ErrorCode.USERNAME_IN_USE);

        expectCode(() -> userService.create(request("another", "FIRST@cce.local", List.of(alpha.getId()))),
                ErrorCode.EMAIL_IN_USE);

        verify(keycloakGateway, never()).findUsersByUsernameOrEmail(anyString(), anyString());
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void unknownAndRepeatedRoleIdsAre400() {
        UUID unknown = UUID.randomUUID();
        ApiException e = expectCode(
                () -> userService.create(request("x-user", "x@cce.local", List.of(alpha.getId(), unknown, alpha.getId()))),
                ErrorCode.VALIDATION_ERROR);
        assertThat(e.details()).extracting(d -> d.field()).containsExactly("roleIds[1]", "roleIds[2]");
        verify(keycloakGateway, never()).findUsersByUsernameOrEmail(anyString(), anyString());
    }

    @Test
    void aNonAssignableRoleIs409WithDetails() {
        ApiException e = expectCode(
                () -> userService.create(request("x-user", "x@cce.local", List.of(alpha.getId(), CCE_ADMIN_SERVICE_ID))),
                ErrorCode.ROLE_NOT_ASSIGNABLE);
        assertThat(e.details()).hasSize(1);
        assertThat(e.details().get(0).field()).isEqualTo("roleIds[1]");
        assertThat(e.details().get(0).message()).contains("CCE_ADMIN_SERVICE");
        verify(keycloakGateway, never()).findUsersByUsernameOrEmail(anyString(), anyString());
        assertThat(auditLog.count()).isZero();
    }

    @Test
    void aRoleWithoutARealmRoleIs409RoleNotSyncedBeforeAnythingChanges() {
        when(keycloakGateway.listRealmRoleNames()).thenReturn(Set.of("CREATE_ALPHA"));

        ApiException e = expectCode(() -> userService.create(request()), ErrorCode.ROLE_NOT_SYNCED);

        assertThat(e.details()).hasSize(1);
        assertThat(e.details().get(0).field()).isEqualTo("roleIds[0]");
        assertThat(e.details().get(0).message()).contains("CREATE_BETA");
        verify(keycloakGateway, never()).createUser(anyString(), anyString(), anyString(), anyString(), anyList());
        assertThat(auditLog.count()).isZero();
        assertThat(users.count()).isZero();
    }

    // ----------------------------------------------------------------------- step 7 (AD-08)

    @Test
    void aFailedEmailStillCreatesTheUserWithAWarning() {
        doThrow(new KeycloakCallFailedException("SMTP down", 500, null))
                .when(keycloakGateway).sendExecuteActionsEmail(any(), anyList());

        UserService.UserCreated created = userService.create(request());

        assertThat(created.warnings()).hasSize(1);
        assertThat(created.warnings().get(0).code()).isEqualTo("ACTION_EMAIL_NOT_SENT");
        assertThat(created.warnings().get(0).menuId()).isNull();
        assertThat(users.findById(created.user().id())).isPresent();
        assertThat(auditLog.count()).isEqualTo(1);
        assertThat(auditLog.findAll().get(0).getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
    }
}
