package com.floricultura.api.repository;

/**
 * Projecao (interface-based) para as leituras derivadas {@code (id, nome)} do endpoint
 * {@code GET /produtos/{id}/relacionamentos} (SPEC-M5 §R3.5, AD-SQ-66) — mesmo padrao de
 * {@link ProdutoImagemProjection}. Os getters casam os aliases {@code id}/{@code nome} das queries
 * nativas em {@link ProdutoRepository} (colunas enumeradas, sem bytea — AD-SQ-38). O servico converte
 * para o record {@code ReferenciaSimples}.
 */
public interface ReferenciaSimplesProjection {

    /** Id da entidade relacionada (coluna {@code id} da query nativa). */
    Long getId();

    /** Nome de exibicao da entidade relacionada (coluna {@code nome} da query nativa). */
    String getNome();
}
