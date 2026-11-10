package org.cce.admin.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.cce.admin.repository.UserRepository;
import org.cce.admin.support.DatabaseTestBase;
import org.cce.admin.support.TestJwtFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/** An unexpected exception inside a filter is answered with 500 {@code INTERNAL_ERROR} (section 3). */
class FilterFailureWebTest extends DatabaseTestBase {

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private UserRepository users;

    @Test
    void aDatabaseFailureInTheLocalUserFilterIs500() throws Exception {
        doThrow(new DataAccessResourceFailureException("database down")).when(users).findByKeycloakId(any());
        mockMvc.perform(get("/admin/v1/me").header(HttpHeaders.AUTHORIZATION,
                        TestJwtFactory.token().subject(UUID.randomUUID()).roles("SUPER_ADMIN").bearer()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.message").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.error.details").doesNotExist());
    }
}
