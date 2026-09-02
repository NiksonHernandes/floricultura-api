package com.floricultura.api.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Corpo de {@code POST /api/v1/produtos/{id}/movimentacoes} (SPEC-M2 §3.2, CA-10/CA-11/CA-12) —
 * registro de movimentacao de estoque pelo ADMIN (AD-SQ-30).
 *
 * <p>Regras de validacao de forma (Bean Validation → {@code 400 VALIDATION_ERROR} com {@code details}
 * pelo {@code GlobalExceptionHandler} do M0): {@code tipo} obrigatorio e ∈ {@code {ENTRADA,SAIDA,
 * AJUSTE}}; {@code quantidade} obrigatoria e {@code >= 0} (o CHECK {@code ck_mov_qtd} da V1 e a
 * defesa em profundidade); {@code motivo} opcional ≤255.
 *
 * <p>A regra <b>cross-tipo</b> "{@code ENTRADA}/{@code SAIDA} exigem {@code quantidade > 0}" NAO cabe
 * aqui (Bean Validation nao ve o {@code tipo} junto), pois {@code AJUSTE 0} e valido ("zerar" — FC-08):
 * essa checagem e a do bloqueio de SAIDA vivem no {@code MovimentacaoService} (§4).
 *
 * @param tipo       {@code ENTRADA} (soma), {@code SAIDA} (subtrai) ou {@code AJUSTE} (alvo absoluto)
 * @param quantidade quantidade movimentada (obrigatoria, {@code >= 0})
 * @param motivo     motivo livre (opcional, ≤255)
 */
public record MovimentacaoRequest(
        @NotNull @Pattern(regexp = "ENTRADA|SAIDA|AJUSTE",
                message = "tipo deve ser um de: ENTRADA, SAIDA, AJUSTE") String tipo,
        @NotNull @DecimalMin(value = "0", message = "quantidade deve ser >= 0")
                BigDecimal quantidade,
        @Size(max = 255) String motivo) {
}
