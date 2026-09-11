package com.floricultura.api.web.dto;

/**
 * Referencia de cor no contrato de produto (SPEC-M6 §3.4): o {@link ReferenciaSimples} do M5
 * <b>estendido com {@code hex}</b>. Record proprio de proposito — {@code ReferenciaSimples} e contrato
 * congelado do M5 e nao e alterado. So aparece no <b>detalhe</b>; na lista vem {@code null} (AD-SQ-38).
 *
 * @param id   id da cor no catalogo
 * @param nome nome CANONICO persistido (AD-SQ-90); a UI exibe como veio, sem re-embelezar (R1d)
 * @param hex  amostra {@code #RRGGBB} em maiusculas, ou {@code null} (R2)
 */
public record CorReferencia(Long id, String nome, String hex) {
}
