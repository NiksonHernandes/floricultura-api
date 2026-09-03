package com.floricultura.api.service;

/**
 * Sinaliza que <b>nao ha imagem para servir</b> em {@code GET /api/v1/produtos/{id}/imagem} (SPEC-M3
 * §3.3, CA-8) — cobre tanto <i>produto inexistente</i> quanto <i>produto existente sem imagem</i>
 * ({@code imagem_content_type IS NULL}). Traduzida por handler local no {@code ProdutoImagemController}
 * para {@code 404 NOT_FOUND} — <b>nunca 401</b> (o interceptor do front desloga no 401; o serve de
 * imagem precisa devolver 404 para o card apenas cair no fallback, sem encerrar a sessao).
 *
 * <p>Mensagem generica de proposito: nao revela se o produto existe quando falta a imagem.
 */
public class ImagemNaoEncontradaException extends RuntimeException {

    public ImagemNaoEncontradaException() {
        super("Imagem nao encontrada.");
    }
}
