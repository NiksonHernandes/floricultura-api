package com.floricultura.api.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Corpo de {@code POST /api/v1/produtos} (SPEC-M2 §3.2, CA-6/CA-9) — criacao de produto pelo ADMIN.
 *
 * <p>Regras de validacao (Bean Validation → {@code 400 VALIDATION_ERROR} com {@code details} pelo
 * {@code GlobalExceptionHandler} do M0): {@code nome} obrigatorio 2..150; {@code unidadeMedida}
 * obrigatoria e ∈ {@code {un,kg,saco,m3,l,g}} (AD-SQ-31 — codigo ASCII, {@code m³} so na exibicao do
 * front); {@code estoqueMinimo} obrigatorio {@code >= 0}; {@code preco} <b>opcional</b>, se presente
 * {@code >= 0} (AD-SQ-28); {@code descricao} opcional; {@code imagemUrl} opcional ≤1000, sem validacao
 * de existencia (AD-SQ-32). O CHECK do enum/preco vive tambem na V3 (defesa em profundidade).
 *
 * <p><b>{@code estoqueAtual} NAO aparece aqui</b> (AD-SQ-30): produto nasce com {@code estoqueAtual=0}
 * e estoque so muda via movimentacao (T-M2-4) — o CRUD nunca o toca.
 *
 * @param nome          nome de exibicao (obrigatorio, 2..150)
 * @param descricao     descricao livre (opcional)
 * @param unidadeMedida codigo da unidade (obrigatorio, ∈ {@code un,kg,saco,m3,l,g})
 * @param estoqueMinimo estoque minimo (obrigatorio, {@code >= 0})
 * @param preco         preco unitario (opcional; se presente {@code >= 0})
 * @param imagemUrl     URL da imagem (opcional, ≤1000; sem validacao de existencia)
 */
public record CriarProdutoRequest(
        @NotBlank @Size(min = 2, max = 150) String nome,
        String descricao,
        @NotBlank @Pattern(regexp = "un|kg|saco|m3|l|g",
                message = "unidadeMedida deve ser um de: un, kg, saco, m3, l, g") String unidadeMedida,
        @NotNull @DecimalMin(value = "0", message = "estoqueMinimo deve ser >= 0")
                BigDecimal estoqueMinimo,
        @DecimalMin(value = "0", message = "preco deve ser >= 0") BigDecimal preco,
        @Size(max = 1000) String imagemUrl) {
}
