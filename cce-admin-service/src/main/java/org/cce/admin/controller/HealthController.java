package org.cce.admin.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.cce.admin.service.HealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /admin/v1/health} (B-05). No token, and no envelope (DEV-5). */
@RestController
public class HealthController {

    /** {@code { "status": "UP", "database": "UP" | "DOWN" }}. */
    public record Health(String status, String database) {
    }

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/admin/v1/health")
    @SecurityRequirements
    public Health health() {
        return new Health("UP", healthService.isDatabaseUp() ? "UP" : "DOWN");
    }
}
