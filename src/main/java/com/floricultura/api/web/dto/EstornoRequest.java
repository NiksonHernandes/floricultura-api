package com.floricultura.api.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corpo de {@code POST /api/v1/movimentacoes/{id}/estorno} (SPEC-M7 §3.4, CA-11 / AD-SQ-156).
 *
 * <p><b>O motivo e OBRIGATORIO</b> (PA#2, decidida no gate do dono — AD-SQ-160 #2): {@code @NotBlank}
 * e {@code @Size(min=3, max=255)}. A razao nao e burocratica — o ledger e <b>insert-only</b> e a
 * linha nasce imutavel, entao <b>esta e a unica chance de gravar o porque</b> da correcao. E o que
 * transforma a linha num documento de auditoria em vez de um numero que apareceu sozinho.
 *
 * <p>Ausente, em branco ou com 2 caracteres ⇒ {@code 400 VALIDATION_ERROR} com
 * {@code details[0].field = "motivo"}, pelo {@code GlobalExceptionHandler} do M0 (nenhum handler novo:
 * ele ja mapeia {@code fe.getField()} do Bean Validation). O texto vai para a coluna {@code motivo}
 * <b>puro</b>: a rastreabilidade do estorno e a coluna {@code estorna_movimentacao_id}, nunca uma
 * string magica prefixada na mensagem do operador.
 *
 * @param motivo justificativa do estorno (obrigatoria, 3..255)
 */
public record EstornoRequest(
        @NotBlank(message = "Informe o motivo do estorno.")
        @Size(min = 3, max = 255, message = "O motivo deve ter entre 3 e 255 caracteres.")
        String motivo) {
}
