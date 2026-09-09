package com.floricultura.api.repository;

/**
 * Projecao (interface-based) que carrega <b>exclusivamente</b> o binario de uma <b>variante</b> da
 * imagem (thumb/medio) + seu content-type real, para servir o {@code GET /produtos/{id}/imagem?tamanho=}
 * (SPEC-M5.2 §3.2/§3.4, T-M5.2-4). Espelha {@link ProdutoImagemProjection} (original em {@code
 * produto.imagem}) para a tabela dedicada {@code produto_imagem_variante} (V11): materializa o {@code
 * bytea} da variante <b>so ao servir</b>, por query nativa dedicada — a tabela <b>nao tem @Entity</b>
 * (invariante de performance AD-SQ-38). Os getters casam os aliases {@code imagem} / {@code
 * imagemContentType} da query nativa em {@link ProdutoRepository#findVarianteById}.
 */
public interface ProdutoImagemVarianteProjection {

    /** Bytes crus da variante gravada no banco (coluna {@code imagem BYTEA}). */
    byte[] getImagem();

    /** Content-type REAL da variante (coluna {@code imagem_content_type}: {@code image/webp} ou {@code image/jpeg}). */
    String getImagemContentType();
}
