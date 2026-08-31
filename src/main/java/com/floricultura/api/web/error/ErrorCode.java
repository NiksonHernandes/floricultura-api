package com.floricultura.api.web.error;

import org.springframework.http.HttpStatus;

/**
 * Tabela canonica de codigos de erro x HTTP (SPEC-M0 §3.2). O nome do enum e o valor de
 * {@code error.code} no envelope.
 */
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Requisicao invalida."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Nao autenticado."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Acesso negado."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Recurso nao encontrado."),
    CONFLICT(HttpStatus.CONFLICT, "Conflito de estado."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
