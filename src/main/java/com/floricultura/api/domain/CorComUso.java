package com.floricultura.api.domain;

/**
 * Cor + contagem de produtos que a usam (SPEC-M6 §3.2 — {@code produtosVinculados}). E o retorno do
 * {@code CorService}: o servico nao conhece DTO de web; a T-M6-01b-2 mapeia este par para o
 * {@code CorResponse} do contrato.
 *
 * @param cor                entidade persistida (nome ja canonico)
 * @param produtosVinculados quantos produtos usam a cor; {@code 0} quando nao ha vinculo
 */
public record CorComUso(Cor cor, long produtosVinculados) {
}
