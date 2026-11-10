package org.cce.admin.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import java.util.List;
import java.util.UUID;

/**
 * The success and error envelopes of section 4.1. Only {@code pagination}, {@code warnings},
 * {@code details}, {@code partialState}, and a warning's {@code menuId} and {@code parentMenuId}
 * are left out when they have no value. Every other field is always written.
 */
public final class CommonDtos {

    private CommonDtos() {
    }

    /** {@code { data, pagination?, warnings? }} (DEV-5). */
    public record ApiResponse<T>(
            T data,
            @JsonInclude(Include.NON_NULL) Pagination pagination,
            @JsonInclude(Include.NON_NULL) List<Warning> warnings) {

        public ApiResponse {
            warnings = warnings == null || warnings.isEmpty() ? null : List.copyOf(warnings);
        }

        public static <T> ApiResponse<T> of(T data) {
            return new ApiResponse<>(data, null, null);
        }

        public static <T> ApiResponse<T> of(T data, Pagination pagination) {
            return new ApiResponse<>(data, pagination, null);
        }

        public static <T> ApiResponse<T> withWarnings(T data, List<Warning> warnings) {
            return new ApiResponse<>(data, null, warnings);
        }
    }

    /** Section 4.3. {@code totalPages} is 0 when {@code totalItems} is 0. */
    public record Pagination(int page, int pageSize, long totalItems, int totalPages) {
    }

    /**
     * A union on {@code code}. {@code DETAIL_WITHOUT_PARENT} always has {@code menuId} and
     * {@code parentMenuId}; the other codes have neither field, not even as null (section 4.1).
     */
    public record Warning(
            String code,
            String message,
            @JsonInclude(Include.NON_NULL) UUID menuId,
            @JsonInclude(Include.NON_NULL) UUID parentMenuId) {

        public static Warning detailWithoutParent(String message, UUID menuId, UUID parentMenuId) {
            return new Warning("DETAIL_WITHOUT_PARENT", message, menuId, parentMenuId);
        }

        public static Warning of(String code, String message) {
            return new Warning(code, message, null, null);
        }
    }

    /** {@code { error: { code, message, details?, partialState? } }} (DEV-9). */
    public record ErrorBody(Content error) {

        public record Content(
                String code,
                String message,
                @JsonInclude(Include.NON_NULL) List<FieldErrorDto> details,
                @JsonInclude(Include.NON_NULL) PartialStateDto partialState) {
        }

        public static ErrorBody of(String code, String message, List<FieldErrorDto> details,
                PartialStateDto partialState) {
            List<FieldErrorDto> nonEmpty = details == null || details.isEmpty() ? null : List.copyOf(details);
            return new ErrorBody(new Content(code, message, nonEmpty, partialState));
        }
    }

    /** {@code field} is the request body property path, or the query or path parameter name. */
    public record FieldErrorDto(String field, String message) {
    }

    /** Section 4.2. Every field is written, as null when it has no value. */
    public record PartialStateDto(
            String operation,
            UUID entityId,
            String failedStep,
            List<String> completedSteps,
            UUID keycloakUserId,
            UUID auditLogId) {
    }
}
