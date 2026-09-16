package com.floricultura.api.service;

import com.floricultura.api.web.response.FieldErrorItem;
import java.util.List;

/**
 * Valores financeiros invalidos no {@code POST /produtos/{id}/movimentacoes} (SPEC-M7 §3.2-c,
 * V2/V3/V4/V7/V8 — CA-6). Espelha {@link ContraparteInvalidaException}: sao regras <b>cross-field</b>
 * (AJUSTE x dinheiro, desconto sem base, par tipo/valor incompleto, percentual &gt; 100, desconto em
 * reais maior que o bruto) que o Bean Validation do DTO nao alcanca, entao vivem no
 * {@code MovimentacaoService} e rodam <b>antes</b> de qualquer escrita — nada persiste, nem no ledger
 * nem no {@code estoque_atual}.
 *
 * <p>Traduzida pelo handler local do {@code MovimentacaoController} para {@code 400 VALIDATION_ERROR}
 * com {@code details[0].field} no campo ofensor. Defesa primaria; os CHECKs {@code ck_mov_*} da V13
 * sao a defesa em profundidade.
 */
public class ValoresInvalidosException extends RuntimeException {

    private final transient List<FieldErrorItem> details;

    public ValoresInvalidosException(String field, String message) {
        super(message);
        this.details = List.of(new FieldErrorItem(field, message));
    }

    public List<FieldErrorItem> getDetails() {
        return details;
    }
}
