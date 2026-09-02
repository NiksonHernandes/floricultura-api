package com.floricultura.api.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Corpo de {@code PUT /api/v1/produtos/{id}} (SPEC-M2 §3.2, CA-7/CA-9) — edicao de produto pelo ADMIN.
 * Mesmos campos e regras do {@link CriarProdutoRequest} (§3.2), mantidos como <b>records separados</b>
 * por serem contratos nomeados distintos (ratificado no gate T-M2-3).
 *
 * <p><b>{@code estoqueAtual} NAO aparece aqui</b> (AD-SQ-30): o {@code PUT} altera so campos de
 * cadastro e <b>nunca</b> toca o estoque — a unica via de alteracao e a movimentacao (T-M2-4).
 *
 * @param nome          nome de exibicao (obrigatorio, 2..150)
 * @param descricao     descricao livre (opcional)
 * @param unidadeMedida codigo da unidade (obrigatorio, ∈ {@code un,kg,saco,m3,l,g} — AD-SQ-31)
 * @param estoqueMinimo estoque minimo (obrigatorio, {@code >= 0})
 * @param preco         preco unitario (opcional; se presente {@code >= 0} — AD-SQ-28)
 * @param imagemUrl     URL da imagem (opcional, ≤1000; sem validacao de existencia — AD-SQ-32)
 */
public record AtualizarProdutoRequest(
        @NotBlank @Size(min = 2, max = 150) String nome,
        String descricao,
        @NotBlank @Pattern(regexp = "un|kg|saco|m3|l|g",
                message = "unidadeMedida deve ser um de: un, kg, saco, m3, l, g") String unidadeMedida,
        @NotNull @DecimalMin(value = "0", message = "estoqueMinimo deve ser >= 0")
                BigDecimal estoqueMinimo,
        @DecimalMin(value = "0", message = "preco deve ser >= 0") BigDecimal preco,
        @Size(max = 1000) String imagemUrl) {
}
