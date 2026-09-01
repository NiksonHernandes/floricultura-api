package com.floricultura.api.config;

import com.floricultura.api.web.error.ErrorCode;
import com.floricultura.api.web.response.ApiError;
import com.floricultura.api.web.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Traducao de <b>401 UNAUTHORIZED</b> no envelope padrao do M0 (SPEC-M0 §3.1/§3.2, SPEC-M1 §3.4).
 * Disparado pelo {@code ExceptionTranslationFilter} quando uma requisicao <b>nao autenticada</b>
 * atinge rota protegida (sem/with token invalido — o filtro JWT nao populou o {@code SecurityContext}).
 *
 * <p>Reusa {@link ApiResponse#fail} + {@link ApiError} (nao redefine o envelope). Escreve APOS o CORS
 * (a cadeia ja aplicou os headers de origem — §12). Nao loga o token nem o header de autorizacao (§9).
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        ApiError error = new ApiError(
                ErrorCode.UNAUTHORIZED.name(),
                ErrorCode.UNAUTHORIZED.defaultMessage(),
                List.of());
        ApiResponse<Object> body = ApiResponse.fail(error, request.getRequestURI());

        response.setStatus(ErrorCode.UNAUTHORIZED.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
