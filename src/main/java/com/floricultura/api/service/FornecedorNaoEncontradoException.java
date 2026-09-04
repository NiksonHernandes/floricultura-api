package com.floricultura.api.service;

/**
 * Fornecedor inexistente em {@code GET/PUT/DELETE /api/v1/fornecedores/{id}} (SPEC-M5 §3.4,
 * CA-2/CA-5/CA-6) → {@code 404 NOT_FOUND} (traduzida por handler local no {@code FornecedorController}).
 * Nao expoe se o id ja existiu.
 */
public class FornecedorNaoEncontradoException extends RuntimeException {

    public FornecedorNaoEncontradoException() {
        super("Fornecedor nao encontrado.");
    }
}
