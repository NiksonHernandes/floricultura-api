package com.floricultura.api.config;

import com.floricultura.api.web.error.ErrorCode;
import com.floricultura.api.web.response.ApiError;
import com.floricultura.api.web.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Traducao de <b>403 FORBIDDEN</b> no envelope padrao do M0 (SPEC-M0 §3.1/§3.2, SPEC-M1 §3.4).
 * Disparado quando uma requisicao <b>autenticada</b> nao tem autoridade para a rota — ex.: um
 * {@code USER} acessando {@code /api/v1/usuarios/**} (RBAC {@code hasRole("ADMIN")}, CA-6).
 *
 * <p>Reusa {@link ApiResponse#fail} + {@link ApiError} (nao redefine o envelope). Escreve APOS o CORS.
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        ApiError error = new ApiError(
                ErrorCode.FORBIDDEN.name(),
                ErrorCode.FORBIDDEN.defaultMessage(),
                List.of());
        ApiResponse<Object> body = ApiResponse.fail(error, request.getRequestURI());

        response.setStatus(ErrorCode.FORBIDDEN.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
