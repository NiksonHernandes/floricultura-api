package com.floricultura.api.service;

/**
 * Produto inexistente em {@code GET /api/v1/produtos/{id}} (SPEC-M2 §3.2, CA-5) → {@code 404
 * NOT_FOUND} (traduzida por handler local no {@code ProdutoController}). Nao expoe se o id ja existiu.
 */
public class ProdutoNaoEncontradoException extends RuntimeException {

    public ProdutoNaoEncontradoException() {
        super("Produto nao encontrado.");
    }
}
