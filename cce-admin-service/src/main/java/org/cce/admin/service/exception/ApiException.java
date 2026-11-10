package org.cce.admin.service.exception;

import java.util.List;
import org.cce.admin.controller.dto.CommonDtos.FieldErrorDto;

/**
 * The base exception for every error with a code of section 4.2. {@code details} is optional, and
 * is used by {@code VALIDATION_ERROR}, {@code ROLE_NOT_SYNCED} and {@code ROLE_NOT_ASSIGNABLE}
 * (section 4.1).
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final List<FieldErrorDto> details;

    public ApiException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public ApiException(ErrorCode code, String message, List<FieldErrorDto> details) {
        super(message);
        this.code = code;
        this.details = details == null || details.isEmpty() ? null : List.copyOf(details);
    }

    public ErrorCode code() {
        return code;
    }

    /** The per-field errors, or null when there are none. */
    public List<FieldErrorDto> details() {
        return details;
    }
}
