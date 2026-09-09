package com.floricultura.api.repository;

/**
 * Projecao <b>leve</b> (SEM bytea) para o {@code GET /produtos/{id}/imagem} do M5.2 (SPEC-M5.2 §3.6):
 * decide 404 (produto inexistente → projecao vazia; sem imagem → {@code imagemContentType == null}) e
 * calcula o {@code ETag} (por {@code atualizado_em}) <b>sem materializar o binario</b>. So no cache-miss
 * uma query nativa dedicada carrega o {@code bytea} da variante/original (AD-SQ-38). O epoch em
 * <b>milissegundos</b> casa a versao {@code ?v=} do front (§3.6). Aliases casam os getters.
 */
public interface ProdutoImagemMetaProjection {

    /** Content-type do original em {@code produto.imagem}; {@code null} = produto sem imagem (→ 404). */
    String getImagemContentType();

    /** {@code atualizado_em} em epoch-millis (dirige o {@code ETag}; muda a cada upload/delete de imagem). */
    Long getAtualizadoEmEpoch();
}
