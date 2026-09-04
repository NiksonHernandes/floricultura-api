package com.floricultura.api.service;

/**
 * Evento inexistente em {@code GET/PUT/DELETE /api/v1/eventos/{id}} (SPEC-M4 §3.2, CA-5/CA-6/CA-7) →
 * {@code 404 NOT_FOUND} (traduzida por handler local no {@code EventoController}). Nao expoe se o id ja
 * existiu.
 */
public class EventoNaoEncontradoException extends RuntimeException {

    public EventoNaoEncontradoException() {
        super("Evento nao encontrado.");
    }
}
