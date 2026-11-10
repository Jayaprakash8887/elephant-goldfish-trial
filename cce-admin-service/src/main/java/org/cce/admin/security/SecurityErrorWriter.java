package org.cce.admin.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.cce.admin.controller.dto.CommonDtos.ErrorBody;
import org.cce.admin.service.exception.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Writes the 401, 403 and 500 error envelopes from inside the filter chain, where the controller
 * advice never runs (section 3).
 */
public class SecurityErrorWriter implements AuthenticationEntryPoint, AccessDeniedHandler {

    static final String UNAUTHENTICATED_MESSAGE = "A valid bearer token is required.";
    static final String INTERNAL_ERROR_MESSAGE = "An unexpected error occurred.";

    private final ObjectMapper objectMapper;
    private final BearerTokenAuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();

    public SecurityErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 401 {@code UNAUTHENTICATED}. Spring's entry point sets the status and {@code WWW-Authenticate} first. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        bearerEntryPoint.commence(request, response, authException);
        writeBody(response, ErrorCode.UNAUTHENTICATED, UNAUTHENTICATED_MESSAGE);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        writeForbidden(response, "You are not allowed to call this endpoint.");
    }

    public void writeForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(ErrorCode.FORBIDDEN.status().value());
        writeBody(response, ErrorCode.FORBIDDEN, message);
    }

    public void writeInternalError(HttpServletResponse response) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(ErrorCode.INTERNAL_ERROR.status().value());
        writeBody(response, ErrorCode.INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE);
    }

    private void writeBody(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), ErrorBody.of(code.name(), message, null, null));
        response.flushBuffer();
    }
}
