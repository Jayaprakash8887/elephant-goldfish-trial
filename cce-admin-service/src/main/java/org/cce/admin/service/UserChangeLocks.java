package org.cce.admin.service;

import java.util.UUID;
import org.cce.admin.entity.UserEntity;
import org.cce.admin.entity.UserStatus;
import org.cce.admin.repository.RoleRepository;
import org.cce.admin.repository.UserRepository;
import org.cce.admin.service.exception.ApiException;
import org.cce.admin.service.exception.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The locks of section 5 for flows 5.2 to 5.4. Both methods must run inside the flow's transaction,
 * which holds the locks until it ends.
 */
@Component
public class UserChangeLocks {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    public UserChangeLocks(UserRepository userRepository, RoleRepository roleRepository) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
    }

    /** {@code SELECT … FOR UPDATE} on the user's row, or 404 {@code USER_NOT_FOUND}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UserEntity lockUser(UUID userId) {
        return userRepository.lockById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND, "No user has this id."));
    }

    /**
     * Called only when the change can lower the number of {@code ACTIVE} users holding
     * {@code SUPER_ADMIN}: locks the {@code SUPER_ADMIN} row, then gives 409
     * {@code LAST_SYSTEM_ADMIN} when no other {@code ACTIVE} user holds the role (AD-07). The count
     * runs after the lock, so it sees what a concurrent change committed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireAnotherActiveAdmin(UUID userId) {
        roleRepository.lockByName(AccountLinker.SUPER_ADMIN)
                .orElseThrow(() -> new IllegalStateException("The seed role SUPER_ADMIN is missing"));
        if (userRepository.countUsersWithRoleExcluding(AccountLinker.SUPER_ADMIN, UserStatus.ACTIVE, userId) == 0) {
            throw new ApiException(ErrorCode.LAST_SYSTEM_ADMIN,
                    "This change would leave no ACTIVE user holding SUPER_ADMIN.");
        }
    }
}
