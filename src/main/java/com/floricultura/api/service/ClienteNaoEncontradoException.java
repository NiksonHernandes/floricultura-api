package com.floricultura.api.service;

/**
 * Cliente inexistente em {@code GET/PUT/DELETE /api/v1/clientes/{id}} (SPEC-M5 §3.4, CA-2/CA-5/CA-6) →
 * {@code 404 NOT_FOUND} (traduzida por handler local no {@code ClienteController}). Nao expoe se o id ja
 * existiu.
 */
public class ClienteNaoEncontradoException extends RuntimeException {

    public ClienteNaoEncontradoException() {
        super("Cliente nao encontrado.");
    }
}
