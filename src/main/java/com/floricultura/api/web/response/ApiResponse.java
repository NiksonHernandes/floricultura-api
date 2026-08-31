package com.floricultura.api.web.response;

import java.time.Instant;

/**
 * Envelope unico de TODA resposta de {@code /api/v1/**} (SPEC-M0 §3.1 / AD-SQ-4).
 *
 * <p>Em sucesso, {@code error} e sempre {@code null}; em erro, {@code data} e sempre {@code null}.
 * O Actuator ({@code /actuator/**}) e isento deste envelope (formato nativo).
 *
 * @param success indica sucesso da operacao
 * @param data    payload em caso de sucesso (null em erro)
 * @param error   detalhe do erro (null em sucesso)
 * @param timestamp instante UTC (ISO-8601) da resposta
 * @param path    URI da requisicao
 */
public record ApiResponse<T>(
        boolean success,
        T data,
        ApiError error,
        Instant timestamp,
        String path) {

    /** Fabrica de resposta de sucesso ({@code error=null}). */
    public static <T> ApiResponse<T> ok(T data, String path) {
        return new ApiResponse<>(true, data, null, Instant.now(), path);
    }

    /** Fabrica de resposta de erro ({@code data=null}). */
    public static <T> ApiResponse<T> fail(ApiError error, String path) {
        return new ApiResponse<>(false, null, error, Instant.now(), path);
    }
}
