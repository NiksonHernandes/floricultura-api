package com.floricultura.api.service;

import com.floricultura.api.web.response.FieldErrorItem;
import java.math.BigDecimal;
import java.util.List;

/**
 * SAIDA cuja {@code quantidade} excede o {@code estoque_atual} do produto (SPEC-M2 §3.2/§4, CA-11 /
 * AD-SQ-30). Lancada pelo {@code MovimentacaoService} <b>antes de qualquer escrita</b> (nem produto
 * nem ledger sao gravados; o estoque nunca fica negativo) e traduzida por handler local do
 * {@code MovimentacaoController} para {@code 400 VALIDATION_ERROR}.
 *
 * <p>A mensagem e <b>exatamente</b> {@code "Estoque insuficiente (X em estoque)."} (X = estoque atual
 * sem zeros a direita, ex.: {@code 20.000} → {@code 20}) e os {@code details} apontam o campo
 * {@code quantidade} — contrato §3.2 verificado no CA-11.
 */
public class EstoqueInsuficienteException extends RuntimeException {

    private final transient List<FieldErrorItem> details;

    public EstoqueInsuficienteException(BigDecimal estoqueAtual) {
        super("Estoque insuficiente (" + estoqueAtual.stripTrailingZeros().toPlainString()
                + " em estoque).");
        this.details = List.of(new FieldErrorItem("quantidade", "Estoque insuficiente."));
    }

    public List<FieldErrorItem> getDetails() {
        return details;
    }
}
