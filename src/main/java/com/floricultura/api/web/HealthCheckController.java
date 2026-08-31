package com.floricultura.api.web;

import com.floricultura.api.web.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Health de aplicacao (enveloped) — SPEC-M0 §3.3. Alvo do teste de CORS e da prova de consumo
 * do front. Distinto de {@code /actuator/health} (infra, formato nativo).
 */
@RestController
@RequestMapping("/api/v1")
public class HealthCheckController {

    @GetMapping("/health-check")
    public ApiResponse<Map<String, String>> healthCheck(HttpServletRequest request) {
        return ApiResponse.ok(Map.of("status", "UP"), request.getRequestURI());
    }
}
