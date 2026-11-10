package org.cce.admin.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.cce.admin.controller.dto.CommonDtos.FieldErrorDto;
import org.cce.admin.controller.dto.RoleDtos.PermissionRowInput;
import org.cce.admin.service.exception.ApiException;
import org.cce.admin.service.exception.ErrorCode;

/**
 * The permission row rules of section 4.4 and 5.7 step 2: upper-cases the methods, checks them
 * against the fixed list, checks the {@code uriPattern} rules, drops the rows that equal an implicit
 * row (AD-16), and rejects duplicates. Every problem becomes a 400 {@code VALIDATION_ERROR} with one
 * {@code details} entry per field, named as in the request ({@code permissions[i].uriPattern}).
 */
public final class PermissionRowRules {

    private PermissionRowRules() {
    }

    /** Fixed by the contract and not configurable (section 4.4, UI-AD-05). No wildcard method. */
    public static final List<String> METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE");

    /** Letters, digits and {@code - . _ ~ / *}. */
    private static final Pattern URI_CHARACTERS = Pattern.compile("^[A-Za-z0-9._~/*-]+$");

    private static final int MAX_URI_LENGTH = 255;

    /** A normalized explicit row: method upper-cased, implicit rows already dropped. */
    public record Row(String httpMethod, String uriPattern, String description, String resourceCategory) {
    }

    private record Key(String httpMethod, String uriPattern) {
    }

    /**
     * The explicit rows to save, in request order. Throws {@code VALIDATION_ERROR} when any row has
     * a method outside the list, a {@code uriPattern} that breaks a rule, or the same method and
     * pattern as an earlier row (the detail names the later one).
     */
    public static List<Row> normalize(List<PermissionRowInput> input) {
        List<FieldErrorDto> details = new ArrayList<>();
        List<Row> rows = new ArrayList<>();
        Set<Key> seen = new HashSet<>();
        for (int i = 0; i < input.size(); i++) {
            PermissionRowInput row = input.get(i);
            String field = "permissions[" + i + "]";
            String method = row.httpMethod() == null ? null : row.httpMethod().toUpperCase(Locale.ROOT);
            boolean valid = true;
            if (method == null || !METHODS.contains(method)) {
                details.add(new FieldErrorDto(field + ".httpMethod", "Must be one of " + String.join(", ", METHODS) + "."));
                valid = false;
            }
            String patternProblem = uriPatternProblem(row.uriPattern());
            if (patternProblem != null) {
                details.add(new FieldErrorDto(field + ".uriPattern", patternProblem));
                valid = false;
            }
            if (!valid) {
                continue;
            }
            // 5.7 step 2: drop the rows that equal an implicit row, then reject duplicates.
            if (ImplicitPermissions.isImplicit(method, row.uriPattern())) {
                continue;
            }
            if (!seen.add(new Key(method, row.uriPattern()))) {
                details.add(new FieldErrorDto(field + ".uriPattern",
                        "Another row has the same method and pattern."));
                continue;
            }
            rows.add(new Row(method, row.uriPattern(), row.description(), row.resourceCategory()));
        }
        if (!details.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "The request is not valid.", details);
        }
        return List.copyOf(rows);
    }

    /**
     * The first broken {@code uriPattern} rule, or null when the pattern is valid: 1 to 255
     * characters, starts with {@code /}, no empty segment ({@code //}), only letters, digits and
     * {@code - . _ ~ / *}, and {@code **} only as the whole last segment.
     */
    public static String uriPatternProblem(String uriPattern) {
        if (uriPattern == null || uriPattern.isEmpty() || uriPattern.length() > MAX_URI_LENGTH) {
            return "Must be 1 to " + MAX_URI_LENGTH + " characters.";
        }
        if (!uriPattern.startsWith("/")) {
            return "Must start with /.";
        }
        if (uriPattern.contains("//")) {
            return "Must not contain an empty segment (//).";
        }
        if (!URI_CHARACTERS.matcher(uriPattern).matches()) {
            return "May contain only letters, digits and - . _ ~ / *.";
        }
        int doubleStar = uriPattern.indexOf("**");
        if (doubleStar >= 0 && !(uriPattern.endsWith("/**") && doubleStar == uriPattern.length() - 2)) {
            return "** may only be the whole last segment.";
        }
        return null;
    }
}
