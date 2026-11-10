package org.cce.admin.controller;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.cce.admin.controller.dto.CommonDtos.ErrorBody;
import org.cce.admin.controller.dto.CommonDtos.FieldErrorDto;
import org.cce.admin.controller.dto.CommonDtos.PartialStateDto;
import org.cce.admin.service.exception.ApiException;
import org.cce.admin.service.exception.ErrorCode;
import org.cce.admin.service.exception.KeycloakSyncFailedException;
import org.cce.admin.service.exception.PartialState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps exceptions to the error envelope of section 4.1 with the codes of section 4.2 (B-03). */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final String VALIDATION_MESSAGE = "The request is not valid.";
    private static final String INVALID_VALUE = "Invalid value.";
    private static final String BODY_FIELD = "body";

    /** Query and path parameters are trimmed too, and an empty one is treated as null (section 4.1). */
    @InitBinder
    void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(String.class, new StringTrimmerEditor(true));
    }

    @ExceptionHandler(KeycloakSyncFailedException.class)
    ResponseEntity<ErrorBody> keycloakSyncFailed(KeycloakSyncFailedException ex) {
        PartialState state = ex.partialState();
        log.error("event=keycloak_sync_failed operation={} entityId={} failedStep={} completedSteps={}"
                        + " keycloakUserId={} auditLogId={}",
                state.operation(), state.entityId(), state.failedStep(), String.join(",", state.completedSteps()),
                orNone(state.keycloakUserId()), orNone(state.auditLogId()));
        PartialStateDto dto = new PartialStateDto(state.operation(), state.entityId(), state.failedStep(),
                state.completedSteps(), state.keycloakUserId(), state.auditLogId());
        return ResponseEntity.status(ex.code().status())
                .body(ErrorBody.of(ex.code().name(), ex.getMessage(), null, dto));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorBody> api(ApiException ex) {
        return error(ex.code(), ex.getMessage(), ex.details());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> notValid(MethodArgumentNotValidException ex) {
        return validation(fromBindingResult(ex.getBindingResult()));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ErrorBody> methodValidation(HandlerMethodValidationException ex) {
        List<FieldErrorDto> details = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors errors) {
                details.addAll(fromBindingResult(errors));
            } else {
                String field = parameterName(result.getMethodParameter());
                result.getResolvableErrors().forEach(e -> details.add(new FieldErrorDto(field, e.getDefaultMessage())));
            }
        }
        return validation(details);
    }

    /** Malformed JSON, a missing body, an unknown property (section 4.1) or a value of the wrong type. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorBody> notReadable(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
            String message = cause instanceof UnrecognizedPropertyException ? "Unknown property." : INVALID_VALUE;
            return validation(List.of(new FieldErrorDto(jsonPath(mapping), message)));
        }
        return validation(List.of(new FieldErrorDto(BODY_FIELD, "The request body is not valid JSON.")));
    }

    /** For example a path id that is not a valid UUID: the parameter name is the field (section 4.1). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorBody> typeMismatch(MethodArgumentTypeMismatchException ex) {
        return validation(List.of(new FieldErrorDto(ex.getName(), INVALID_VALUE)));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorBody> unsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        // DESIGN-GAP: section 4.2 maps an unsupported content type to 400 VALIDATION_ERROR, and
        // section 4.1 says details appear with VALIDATION_ERROR, but names no field for this case.
        // The content type describes the body, so the one detail uses the field "body".
        return validation(List.of(new FieldErrorDto(BODY_FIELD,
                "Content type " + ex.getContentType() + " is not supported. Use application/json.")));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorBody> noResource(NoResourceFoundException ex) {
        return error(ErrorCode.NOT_FOUND, "No endpoint matches this path.", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorBody> methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return error(ErrorCode.METHOD_NOT_ALLOWED, "This path does not support " + ex.getMethod() + ".", null);
    }

    /** Any other failure. The message is generic, and the stack trace is only logged. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return error(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", null);
    }

    private static ResponseEntity<ErrorBody> validation(List<FieldErrorDto> details) {
        return error(ErrorCode.VALIDATION_ERROR, VALIDATION_MESSAGE, details);
    }

    private static ResponseEntity<ErrorBody> error(ErrorCode code, String message, List<FieldErrorDto> details) {
        return ResponseEntity.status(code.status()).body(ErrorBody.of(code.name(), message, details, null));
    }

    private static List<FieldErrorDto> fromBindingResult(Errors result) {
        List<FieldErrorDto> details = new ArrayList<>();
        for (FieldError error : result.getFieldErrors()) {
            details.add(new FieldErrorDto(error.getField(),
                    error.isBindingFailure() ? INVALID_VALUE : error.getDefaultMessage()));
        }
        for (ObjectError error : result.getGlobalErrors()) {
            // DESIGN-GAP: section 4.1 defines "field" only for a property or a parameter. An error
            // on the whole body object (a class-level constraint) has neither, so it uses "body".
            details.add(new FieldErrorDto(BODY_FIELD, error.getDefaultMessage()));
        }
        return details;
    }

    private static String parameterName(MethodParameter parameter) {
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        if (requestParam != null && !requestParam.name().isEmpty()) {
            return requestParam.name();
        }
        PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
        if (pathVariable != null && !pathVariable.name().isEmpty()) {
            return pathVariable.name();
        }
        String name = parameter.getParameterName();
        return name != null ? name : BODY_FIELD;
    }

    /** The JSON path of the property, written as in the JSON: {@code permissions[2].uriPattern}. */
    private static String jsonPath(JsonMappingException ex) {
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference reference : ex.getPath()) {
            if (reference.getFieldName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getFieldName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.isEmpty() ? BODY_FIELD : path.toString();
    }

    private static String orNone(UUID id) {
        return id == null ? "none" : id.toString();
    }
}
