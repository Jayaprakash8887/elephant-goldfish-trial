package org.cce.admin.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.cce.admin.entity.MenuType;

/**
 * The Menu shape of section 4.4 (AS-REQ-13) and the menu request records. Request strings are
 * trimmed before validation, and an empty one is null (section 4.1).
 */
public final class MenuDtos {

    private MenuDtos() {
    }

    public static final String KEY_PATTERN = "^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)*$";
    /** Starts with {@code /}, made only of letters, digits and {@code / _ - . :}. */
    public static final String PATH_PATTERN = "^/[A-Za-z0-9/_.:-]*$";
    /** A Heroicons 2 outline icon component name, for example {@code ChartBarIcon}. */
    public static final String ICON_PATTERN = "^[A-Z][A-Za-z0-9]*Icon$";

    /**
     * Menu. {@code children} holds the menu's child menus, ordered by {@code sortOrder} then
     * {@code label}; it is empty for {@code DETAIL} menus.
     */
    public record Menu(
            UUID id,
            String key,
            String label,
            String path,
            String icon,
            MenuType type,
            UUID parentId,
            int sortOrder,
            @JsonProperty("isActive") boolean isActive,
            List<Menu> children) {
    }

    /** {@code POST /menus}. {@code isActive} defaults to {@code true}. */
    public record CreateMenuRequest(
            @NotNull @Size(max = 100) @Pattern(regexp = KEY_PATTERN) String key,
            @NotNull @Size(min = 1, max = 100) String label,
            @NotNull @Size(min = 1, max = 255) @Pattern(regexp = PATH_PATTERN) String path,
            // DESIGN-GAP: section 4.4 gives icon a pattern but no length; the column is
            // varchar(100), so the column's length is applied to keep a long name from a 500.
            @Size(max = 100) @Pattern(regexp = ICON_PATTERN) String icon,
            @NotNull MenuType type,
            UUID parentId,
            @NotNull @Min(0) @Max(10000) Integer sortOrder,
            @JsonProperty("isActive") Boolean isActive) {
    }

    /**
     * {@code PUT /menus/:id}: the complete new state (section 4.1). A missing or null {@code icon}
     * or {@code parentId} clears it. {@code key} and {@code type} are unknown properties here.
     */
    public record UpdateMenuRequest(
            @NotNull @Size(min = 1, max = 100) String label,
            @NotNull @Size(min = 1, max = 255) @Pattern(regexp = PATH_PATTERN) String path,
            // DESIGN-GAP: as in CreateMenuRequest, the column's length of 100 is applied.
            @Size(max = 100) @Pattern(regexp = ICON_PATTERN) String icon,
            UUID parentId,
            @NotNull @Min(0) @Max(10000) Integer sortOrder,
            @NotNull @JsonProperty("isActive") Boolean isActive) {
    }
}
