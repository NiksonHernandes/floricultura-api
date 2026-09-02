package com.floricultura.api.domain;

import java.math.BigDecimal;

/**
 * Fabrica de {@link MovimentacaoEstoque} para o registro de movimentacao pelo ADMIN (SPEC-M2 §3.2/§4,
 * T-M2-4). Vive no MESMO pacote de {@link MovimentacaoEstoque} porque o construtor da entidade e
 * {@code protected} (exigido pelo JPA, T-M2-1) — este helper permite instancia-la a partir da camada
 * de servico <b>sem alterar a entity</b> (mesma convencao de {@code ProdutoFactory}/{@code
 * UsuarioFactory}).
 *
 * <p>Grava os invariantes de uma linha do ledger insert-only (AD-SQ-8): o {@code produtoNome} e o
 * {@code snapshot} do nome no momento da movimentacao (sobrevive ao hard delete do produto), a
 * {@code quantidadeResultante} e o {@code estoque_atual} <b>apos</b> aplicar a movimentacao, e o
 * {@code usuarioId} vem do {@code @AuthenticationPrincipal} (nunca do payload — §9). {@code id} e
 * {@code criadoEm} <b>nao</b> sao setados aqui: {@code id} e {@code IDENTITY} e {@code criado_em} vem
 * do {@code DEFAULT now()} do banco (coluna {@code insertable=false}) — o servico faz {@code refresh}
 * apos o {@code flush} para carrega-los.
 */
public final class MovimentacaoFactory {

    private MovimentacaoFactory() {
        // Utilitaria — sem instancia.
    }

    /**
     * Cria uma linha transiente do ledger pronta para {@code save}.
     *
     * @param produtoId            id do produto movimentado
     * @param produtoNome          snapshot do nome do produto
     * @param tipo                 {@code ENTRADA}/{@code SAIDA}/{@code AJUSTE}
     * @param quantidade           quantidade movimentada (ja validada)
     * @param quantidadeResultante estoque apos aplicar a movimentacao
     * @param motivo               motivo livre (pode ser {@code null})
     * @param usuarioId            id do autor (do principal autenticado)
     * @return entidade transiente pronta para persistir
     */
    public static MovimentacaoEstoque nova(
            Long produtoId,
            String produtoNome,
            String tipo,
            BigDecimal quantidade,
            BigDecimal quantidadeResultante,
            String motivo,
            Long usuarioId) {
        MovimentacaoEstoque mov = new MovimentacaoEstoque();
        mov.setProdutoId(produtoId);
        mov.setProdutoNome(produtoNome);
        mov.setTipo(tipo);
        mov.setQuantidade(quantidade);
        mov.setQuantidadeResultante(quantidadeResultante);
        mov.setMotivo(motivo);
        mov.setUsuarioId(usuarioId);
        return mov;
    }
}
