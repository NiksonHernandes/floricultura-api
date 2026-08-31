package com.floricultura.api.web.response;

import java.util.List;

/**
 * Bloco de erro do envelope (SPEC-M0 §3.1/§3.2).
 *
 * @param code    codigo canonico do erro (ver {@code ErrorCode})
 * @param message mensagem legivel, sem vazar stacktrace/SQL/segredo
 * @param details lista de erros por campo (vazia quando nao se aplica)
 */
public record ApiError(String code, String message, List<FieldErrorItem> details) {
}
