package com.floricultura.api.service;

import com.floricultura.api.web.response.FieldErrorItem;
import java.util.List;

/**
 * Um id de {@code produtoIds} do payload de cliente/fornecedor nao existe (SPEC-M5 §3.5/§4.2, CA-8).
 * Reutilizavel pelos dois cadastros irmaos. Lancada pelo {@code ClienteService}/{@code FornecedorService}
 * ({@code criar}/{@code atualizar}) <b>antes de gravar qualquer vinculo</b> (nada e persistido) e
 * traduzida por handler local do controller para {@code 400 VALIDATION_ERROR} com {@code details}
 * apontando o campo {@code produtoIds} — mesmo padrao de {@code EventoInexistenteException} do M4.
 */
public class ProdutoInexistenteException extends RuntimeException {

    private final transient List<FieldErrorItem> details;

    public ProdutoInexistenteException(Long produtoId) {
        super("Produto inexistente: " + produtoId + ".");
        this.details = List.of(new FieldErrorItem(
                "produtoIds", "Produto inexistente: " + produtoId + "."));
    }

    public List<FieldErrorItem> getDetails() {
        return details;
    }
}
