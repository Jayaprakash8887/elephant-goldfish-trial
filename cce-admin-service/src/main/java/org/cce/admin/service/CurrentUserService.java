package org.cce.admin.service;

import java.util.List;
import org.cce.admin.controller.dto.MeDtos.Me;
import org.cce.admin.entity.RoleEntity;
import org.cce.admin.entity.UserEntity;
import org.cce.admin.repository.RoleRepository;
import org.cce.admin.repository.UserRepository;
import org.cce.admin.security.CurrentUser;
import org.springframework.stereotype.Service;

/** The caller's own profile, {@code GET /me} (B-20). {@code /me/menus} comes in Phase 7. */
@Service
public class CurrentUserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    public CurrentUserService(UserRepository userRepository, RoleRepository roleRepository) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
    }

    /**
     * Me (section 4.4): {@code roles} holds the caller's token roles that exist in {@code roles},
     * ordered by name, so Keycloak's own roles such as {@code offline_access} are left out.
     */
    public Me me() {
        CurrentUser caller = CurrentUser.current()
                .orElseThrow(() -> new IllegalStateException("No current user in this request"));
        UserEntity user = userRepository.findById(caller.id())
                .orElseThrow(() -> new IllegalStateException("The current user's row no longer exists"));
        List<String> roles = caller.roleNames().isEmpty()
                ? List.of()
                : roleRepository.findByNameIn(caller.roleNames()).stream().map(RoleEntity::getName).sorted().toList();
        return new Me(user.getId(), user.getUsername(), roles, user.getFacilityScope());
    }
}
