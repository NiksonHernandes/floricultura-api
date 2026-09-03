package com.floricultura.api.repository;

/**
 * Projecao (interface-based) que carrega <b>exclusivamente</b> o binario da imagem + seu content-type
 * para o endpoint {@code GET /produtos/{id}/imagem} (SPEC-M3 §3.5, consumido em T-M3-2). E o unico
 * caminho que materializa o {@code bytea} — a {@code @Entity Produto} nao mapeia a coluna {@code
 * imagem}, entao lista/detalhe nunca o trazem (invariante de performance AD-SQ-38). Os getters casam
 * os aliases {@code imagem} / {@code imagemContentType} da query nativa em {@link ProdutoRepository}.
 */
public interface ProdutoImagemProjection {

    /** Bytes crus da imagem gravada no banco (coluna {@code imagem BYTEA}). */
    byte[] getImagem();

    /** Content-type confirmado da imagem (coluna {@code imagem_content_type}). */
    String getImagemContentType();
}
