package com.floricultura.api.service;

/**
 * Usuario inexistente em {@code GET /api/v1/usuarios/{id}} (SPEC-M1 §3.2, CA-8) → {@code 404
 * NOT_FOUND} (traduzida por handler local no {@code UsuarioController}). Nao expoe se o id ja existiu.
 */
public class UsuarioNaoEncontradoException extends RuntimeException {

    public UsuarioNaoEncontradoException() {
        super("Usuario nao encontrado.");
    }
}
