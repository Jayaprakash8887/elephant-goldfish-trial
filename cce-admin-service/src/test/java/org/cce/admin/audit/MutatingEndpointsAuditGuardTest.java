package org.cce.admin.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.cce.admin.support.DatabaseTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * A review guard (B-50, Verification Plan): every non-GET mapping of a controller in
 * {@code org.cce.admin.controller} must be in {@link #REVIEWED}. A new mutating endpoint fails the
 * build until it is added here, which forces a review of its audit rows under section 8. This test
 * does not verify that an audit test exists.
 */
class MutatingEndpointsAuditGuardTest extends DatabaseTestBase {

    /** The mutating endpoints whose audit rows have been reviewed against section 8. */
    private static final Set<String> REVIEWED = Set.of(
            "POST /admin/v1/roles",
            "PUT /admin/v1/roles/{id}",
            "DELETE /admin/v1/roles/{id}",
            "PUT /admin/v1/roles/{id}/menus",
            "PUT /admin/v1/roles/{id}/permissions",
            "POST /admin/v1/roles/{id}/sync",
            "POST /admin/v1/menus",
            "PUT /admin/v1/menus/{id}",
            "DELETE /admin/v1/menus/{id}",
            "POST /admin/v1/users",
            "PUT /admin/v1/users/{id}",
            "PATCH /admin/v1/users/{id}/status",
            "PUT /admin/v1/users/{id}/roles",
            "POST /admin/v1/users/{id}/reset-password");

    private static final String CONTROLLER_PACKAGE = "org.cce.admin.controller";

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyMutatingEndpointIsInTheReviewedList() {
        Set<String> mutating = mutatingEndpoints();

        assertThat(mutating).as("non-GET mappings found in %s", CONTROLLER_PACKAGE).isNotEmpty();
        assertThat(REVIEWED)
                .as("Mutating endpoints missing from the reviewed list. Review their audit rows (section 8),"
                        + " then add them to MutatingEndpointsAuditGuardTest.REVIEWED")
                .containsAll(mutating);
    }

    private Set<String> mutatingEndpoints() {
        Set<String> endpoints = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            Class<?> controller = entry.getValue().getBeanType();
            if (!controller.getPackageName().equals(CONTROLLER_PACKAGE)) {
                continue;
            }
            RequestMappingInfo info = entry.getKey();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                if (methods.isEmpty()) {
                    // A mapping without a method answers every method, so it can mutate.
                    endpoints.add("ANY " + pattern);
                }
                for (RequestMethod method : methods) {
                    if (method != RequestMethod.GET) {
                        endpoints.add(method.name() + " " + pattern);
                    }
                }
            }
        }
        return endpoints;
    }
}
