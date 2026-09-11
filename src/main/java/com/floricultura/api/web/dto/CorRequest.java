package com.floricultura.api.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corpo de {@code POST}/{@code PUT /api/v1/cores} (SPEC-M6 §3.2) — mesmo record nas duas rotas (padrao
 * {@link FornecedorRequest}).
 *
 * <p><b>O front manda o texto CRU</b> ({@code "cinza escuro"}): quem canoniza e o back (§3.2.1 / armadilha
 * #22). Por isso o Bean Validation daqui e so guarda barata contra abuso — {@code @NotBlank} +
 * {@code @Size(max=200)} sobre o cru — e o {@code 2..40} <b>autoritativo roda sobre o CANONICO</b>, no
 * {@code CorService} (armadilha #23 / CA-39.4-5): {@code " a "} passa no cru com 3 caracteres e vira
 * {@code A} com 1 depois de padronizado.
 *
 * @param nome nome CRU da cor (obrigatorio, ≤200 antes da canonizacao)
 * @param hex  amostra opcional {@code #RRGGBB} (CA-3); ausente ou em branco → {@code null} no servico;
 *             devolvida em MAIUSCULAS. O {@code @Pattern} aceita o vazio de proposito — `""` e "sem
 *             amostra", nao payload invalido.
 */
public record CorRequest(
        @NotBlank @Size(max = 200) String nome,
        @Pattern(regexp = "^(\\s*|#[0-9A-Fa-f]{6})$",
                message = "Informe a cor no formato #RRGGBB.") String hex) {
}
