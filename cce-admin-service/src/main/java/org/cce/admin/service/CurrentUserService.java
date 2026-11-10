package org.cce.admin.service;

import java.util.List;
import org.cce.admin.controller.dto.MeDtos.Me;
import org.cce.admin.controller.dto.MeDtos.MenuTreeItem;
import org.cce.admin.entity.RoleEntity;
import org.cce.admin.entity.UserEntity;
import org.cce.admin.repository.MenuRepository;
import org.cce.admin.repository.RoleRepository;
import org.cce.admin.repository.UserRepository;
import org.cce.admin.security.CurrentUser;
import org.springframework.stereotype.Service;

/** The caller's own profile, {@code GET /me} (B-20), and menu tree, {@code GET /me/menus} (B-21). */
@Service
public class CurrentUserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final MenuRepository menuRepository;

    public CurrentUserService(UserRepository userRepository, RoleRepository roleRepository,
            MenuRepository menuRepository) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.menuRepository = menuRepository;
    }

    /**
     * Me (section 4.4): {@code roles} holds the caller's token roles that exist in {@code roles},
     * ordered by name, so Keycloak's own roles such as {@code offline_access} are left out.
     */
    public Me me() {
        CurrentUser caller = caller();
        UserEntity user = userRepository.findById(caller.id())
                .orElseThrow(() -> new IllegalStateException("The current user's row no longer exists"));
        List<String> roles = caller.roleNames().isEmpty()
                ? List.of()
                : roleRepository.findByNameIn(caller.roleNames()).stream().map(RoleEntity::getName).sorted().toList();
        return new Me(user.getId(), user.getUsername(), roles, user.getFacilityScope());
    }

    /**
     * The menu tree (section 4.5, rules for {@code GET /me/menus}): the caller's token roles as in
     * Me (the query joins {@code roles} by name, so names that exist nowhere match nothing), the
     * union of their active granted menus, built by {@link MenuTreeBuilder}. A caller with no
     * matching roles gets an empty list.
     */
    public List<MenuTreeItem> menus() {
        CurrentUser caller = caller();
        if (caller.roleNames().isEmpty()) {
            return List.of();
        }
        return MenuTreeBuilder.build(menuRepository.findActiveGrantedToRoleNames(caller.roleNames()));
    }

    private static CurrentUser caller() {
        return CurrentUser.current()
                .orElseThrow(() -> new IllegalStateException("No current user in this request"));
    }
}
