package org.cce.admin.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.cce.admin.service.HealthService;
import org.cce.admin.support.DatabaseTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Any unexpected failure in a controller is 500 {@code INTERNAL_ERROR}, with a generic message. */
class InternalErrorWebTest extends DatabaseTestBase {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HealthService healthService;

    @Test
    void anUnexpectedExceptionIs500WithTheEnvelope() throws Exception {
        when(healthService.isDatabaseUp()).thenThrow(new IllegalStateException("secret internals"));
        mockMvc.perform(get("/admin/v1/health"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.message").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.error.details").doesNotExist())
                .andExpect(jsonPath("$.error.partialState").doesNotExist());
    }

    @Test
    void aDatabaseThatIsDownIsReportedNotFailed() throws Exception {
        when(healthService.isDatabaseUp()).thenReturn(false);
        mockMvc.perform(get("/admin/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("DOWN"));
    }
}
