package com.floricultura.api.service;

/**
 * Cor inexistente em {@code GET/PUT/DELETE /api/v1/cores/{id}} (SPEC-M6 §3.2, CA-5/CA-6) →
 * {@code 404 NOT_FOUND} (traduzida por handler local no {@code CorController} — T-M6-01b-2).
 */
public class CorNaoEncontradaException extends RuntimeException {

    public CorNaoEncontradaException() {
        super("Cor nao encontrada.");
    }
}
