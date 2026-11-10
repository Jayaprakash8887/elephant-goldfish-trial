package org.cce.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.cce.admin.controller.dto.UserDtos.ChangeStatusRequest;
import org.cce.admin.controller.dto.UserDtos.User;
import org.cce.admin.entity.UserEntity;
import org.cce.admin.entity.UserRoleEntity;
import org.cce.admin.entity.UserStatus;
import org.cce.admin.repository.UserRepository;
import org.cce.admin.repository.UserRoleRepository;
import org.cce.admin.service.exception.ApiException;
import org.cce.admin.service.exception.ErrorCode;
import org.cce.admin.support.DatabaseTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code LAST_SYSTEM_ADMIN} under two concurrent requests (AD-07, section 5 "Locks"): two
 * {@code ACTIVE} administrators deactivate each other at the same time, and exactly one wins.
 */
class LastSystemAdminConcurrencyTest extends DatabaseTestBase {

    private static final UUID SUPER_ADMIN_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final long TIMEOUT_SECONDS = 10;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository users;

    @Autowired
    private UserRoleRepository userRoleRepository;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void stopThreads() {
        executor.shutdownNow();
    }

    private UserEntity admin(String username) {
        UserEntity entity = new UserEntity();
        entity.setKeycloakId(UUID.randomUUID());
        entity.setUsername(username);
        entity.setEmail(username + "@cce.local");
        entity.setFirstName("Admin");
        entity.setLastName(username);
        entity.setStatus(UserStatus.ACTIVE);
        UserEntity saved = users.save(entity);
        UserRoleEntity userRole = new UserRoleEntity();
        userRole.setUserId(saved.getId());
        userRole.setRoleId(SUPER_ADMIN_ID);
        userRoleRepository.save(userRole);
        return saved;
    }

    private Future<Object> deactivate(UUID userId) {
        return executor.submit(() -> {
            try {
                return userService.changeStatus(userId, new ChangeStatusRequest(UserStatus.INACTIVE));
            } catch (ApiException e) {
                return e;
            }
        });
    }

    @Test
    void exactlyOneOfTwoConcurrentDeactivationsSucceeds() throws Exception {
        UserEntity a = admin("admin-a");
        UserEntity b = admin("admin-b");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("latch released").isTrue();
            return null;
        }).when(keycloakGateway).setEnabled(any(), anyBoolean());

        // Thread 1 deactivates A and blocks in setEnabled, holding the SUPER_ADMIN lock.
        Future<Object> first = deactivate(a.getId());
        assertThat(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("thread 1 reached setEnabled").isTrue();

        // Thread 2 deactivates B, and waits for the SUPER_ADMIN lock.
        Future<Object> second = deactivate(b.getId());
        awaitALockWait();
        release.countDown();

        List<Object> results = new ArrayList<>();
        results.add(result(first));
        results.add(result(second));

        assertThat(results).filteredOn(User.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(ApiException.class::isInstance)
                .singleElement()
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.LAST_SYSTEM_ADMIN));
        assertThat(results.get(0)).isInstanceOf(User.class);
        assertThat(users.findById(a.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.INACTIVE);
        assertThat(users.findById(b.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(users.countUsersWithRole("SUPER_ADMIN", UserStatus.ACTIVE)).isEqualTo(1);
    }

    /** Polls {@code pg_stat_activity} until a backend of this database waits on a lock. */
    private void awaitALockWait() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(TIMEOUT_SECONDS));
        while (Instant.now().isBefore(deadline)) {
            Integer waiting = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_stat_activity"
                    + " WHERE datname = current_database() AND wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("No backend waited on a lock within " + TIMEOUT_SECONDS + " seconds");
    }

    private static Object result(Future<Object> future) throws InterruptedException, ExecutionException {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("A request did not finish within " + TIMEOUT_SECONDS + " seconds", e);
        }
    }
}
