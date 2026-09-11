package com.floricultura.api.repository;

/**
 * Projecao (interface-based) de {@code (id, nome, hex)} para as cores de UM produto (SPEC-M6 §3.5) —
 * mesmo padrao de {@link ReferenciaSimplesProjection}. Os getters casam os aliases de
 * {@code findCoresByProdutoId}. <b>{@code produto_cor} NAO e @Entity</b>: mapea-la poria colecao na
 * @Entity {@code Produto} e quebraria a hidratacao da vitrine sob query nativa (§12 #3).
 */
public interface CorReferenciaProjection {

    Long getId();

    String getNome();

    /** Amostra {@code #RRGGBB} ou {@code null} (R2). */
    String getHex();
}
