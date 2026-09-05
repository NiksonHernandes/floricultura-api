package com.floricultura.api.service;

import com.floricultura.api.web.response.FieldErrorItem;
import java.util.List;

/**
 * Contraparte invalida no {@code POST /produtos/{id}/movimentacoes} (SPEC-M5 §R3.3, R-CA-3/4/5,
 * AD-SQ-64): a regra e cross-tipo (fornecedor so em ENTRADA, cliente so em SAIDA, AJUSTE nenhum) e
 * referencial (o cadastro precisa existir) — o Bean Validation do DTO nao a alcanca, entao vive no
 * {@code MovimentacaoService} e roda <b>antes</b> de qualquer escrita (nada persiste). E traduzida por
 * handler local do {@code MovimentacaoController} para {@code 400 VALIDATION_ERROR} com {@code details}
 * no campo ofensor ({@code fornecedorId} ou {@code clienteId}). Defesa primaria; o CHECK
 * {@code ck_mov_*_tipo} da V10 e a defesa em profundidade.
 */
public class ContraparteInvalidaException extends RuntimeException {

    private final transient List<FieldErrorItem> details;

    public ContraparteInvalidaException(String field, String message) {
        super(message);
        this.details = List.of(new FieldErrorItem(field, message));
    }

    public List<FieldErrorItem> getDetails() {
        return details;
    }
}
