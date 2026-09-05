package com.floricultura.api.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
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
 * <p><b>Contraparte (V10/AD-SQ-64, RB-3):</b> {@code fornecedorId}/{@code clienteId} <b>opcionais</b>
 * ({@code @Positive} quando presentes). A regra cross-tipo (fornecedor so em ENTRADA, cliente so em
 * SAIDA, AJUSTE nenhum) + a existencia do cadastro sao validadas no {@code MovimentacaoService} (§R3.3),
 * que lanca {@code 400} com {@code field=fornecedorId}/{@code clienteId} <b>antes</b> de qualquer escrita.
 *
 * @param tipo         {@code ENTRADA} (soma), {@code SAIDA} (subtrai) ou {@code AJUSTE} (alvo absoluto)
 * @param quantidade   quantidade movimentada (obrigatoria, {@code >= 0})
 * @param motivo       motivo livre (opcional, ≤255)
 * @param fornecedorId contraparte fornecedor (opcional; so ENTRADA — validado no servico)
 * @param clienteId    contraparte cliente (opcional; so SAIDA — validado no servico)
 */
public record MovimentacaoRequest(
        @NotNull @Pattern(regexp = "ENTRADA|SAIDA|AJUSTE",
                message = "tipo deve ser um de: ENTRADA, SAIDA, AJUSTE") String tipo,
        @NotNull @DecimalMin(value = "0", message = "quantidade deve ser >= 0")
                BigDecimal quantidade,
        @Size(max = 255) String motivo,
        @Positive Long fornecedorId,
        @Positive Long clienteId) {

    /**
     * Construtor de conveniencia (sem contraparte) — mantem os chamadores/testes do M2/M4 intactos
     * (payload sem {@code fornecedorId}/{@code clienteId}); delega ao canonico com contraparte nula.
     */
    public MovimentacaoRequest(String tipo, BigDecimal quantidade, String motivo) {
        this(tipo, quantidade, motivo, null, null);
    }
}
