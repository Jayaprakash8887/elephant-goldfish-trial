package org.cce.admin.service;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.cce.admin.controller.dto.UserDtos.User;
import org.cce.admin.controller.dto.UserDtos.UserRole;
import org.cce.admin.entity.RoleEntity;
import org.cce.admin.entity.UserEntity;
import org.cce.admin.entity.UserRoleEntity;
import org.cce.admin.repository.RoleRepository;
import org.cce.admin.repository.UserRoleRepository;
import org.springframework.stereotype.Component;

/** Maps a {@link UserEntity} and its roles to {@link User}, for response bodies and audit JSON. */
@Component
public class UserViews {

    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;

    public UserViews(UserRoleRepository userRoleRepository, RoleRepository roleRepository) {
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
    }

    /** The user with the roles its {@code user_roles} rows name. */
    public User view(UserEntity user) {
        List<UUID> roleIds = userRoleRepository.findByUserId(user.getId()).stream()
                .map(UserRoleEntity::getRoleId)
                .toList();
        return toUser(user, roleIds.isEmpty() ? List.of() : roleRepository.findAllById(roleIds));
    }

    public static User toUser(UserEntity user, Collection<RoleEntity> roles) {
        return new User(
                user.getId(),
                user.getKeycloakId(),
                user.getUsername(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getStatus(),
                user.getFacilityScope(),
                toRoles(roles),
                user.getLastLoginAt(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }

    /** {@code User.roles}: {@code { id, name, displayName }}, ordered by name. */
    public static List<UserRole> toRoles(Collection<RoleEntity> roles) {
        return roles.stream()
                .sorted(Comparator.comparing(RoleEntity::getName))
                .map(role -> new UserRole(role.getId(), role.getName(), role.getDisplayName()))
                .toList();
    }
}
