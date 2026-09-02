package com.floricultura.api.service;

import com.floricultura.api.web.response.FieldErrorItem;
import java.util.List;

/**
 * {@code ENTRADA} ou {@code SAIDA} com {@code quantidade = 0} (SPEC-M2 §3.2/§4, CA-12 / AD-SQ-30):
 * uma movimentacao de entrada/saida precisa mover algo (o {@code AJUSTE 0} = "zerar" e valido e NAO
 * passa por aqui). A regra e cross-tipo (o Bean Validation do DTO nao ve {@code tipo} + {@code
 * quantidade} juntos), entao vive no {@code MovimentacaoService} e e traduzida por handler local do
 * {@code MovimentacaoController} para {@code 400 VALIDATION_ERROR} com {@code details} no campo
 * {@code quantidade}.
 */
public class QuantidadeInvalidaException extends RuntimeException {

    private final transient List<FieldErrorItem> details;

    public QuantidadeInvalidaException(String tipo) {
        super("Quantidade deve ser maior que zero para " + tipo + ".");
        this.details = List.of(new FieldErrorItem(
                "quantidade", "Deve ser maior que zero para " + tipo + "."));
    }

    public List<FieldErrorItem> getDetails() {
        return details;
    }
}
