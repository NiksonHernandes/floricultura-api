package com.floricultura.api.web.dto;

import com.floricultura.api.domain.MovimentacaoEstoque;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * {@code data} do {@code POST /api/v1/produtos/{id}/movimentacoes} e item de
 * {@code PaginaResponse.conteudo} no {@code GET /api/v1/produtos/{id}/movimentacoes} (SPEC-M2 §3.2,
 * CA-10/CA-14). Espelha uma linha do ledger imutavel {@code movimentacao_estoque} (AD-SQ-8).
 *
 * <p>{@code produtoId} e {@code usuarioId} podem ser {@code null} (FK {@code ON DELETE SET NULL}: o
 * hard delete do produto/usuario anula o vinculo — AD-SQ-34), mas {@code produtoNome} sobrevive como
 * snapshot legivel. {@code quantidadeResultante} e o {@code estoque_atual} <b>apos</b> a movimentacao.
 *
 * @param id                   id da linha do ledger
 * @param produtoId            id do produto (pode ser {@code null} apos hard delete)
 * @param produtoNome          snapshot do nome do produto no momento da movimentacao
 * @param tipo                 {@code ENTRADA}/{@code SAIDA}/{@code AJUSTE}
 * @param quantidade           quantidade movimentada
 * @param quantidadeResultante estoque do produto apos aplicar a movimentacao
 * @param motivo               motivo livre (pode ser {@code null})
 * @param usuarioId            id do autor (do {@code @AuthenticationPrincipal}; pode ser {@code null})
 * @param criadoEm             instante de criacao (UTC ISO-8601), do {@code DEFAULT now()} do banco
 */
public record MovimentacaoResponse(
        Long id,
        Long produtoId,
        String produtoNome,
        String tipo,
        BigDecimal quantidade,
        BigDecimal quantidadeResultante,
        String motivo,
        Long usuarioId,
        Instant criadoEm) {

    /**
     * Mapeia uma linha do ledger para o response (SPEC-M2 §3.2) — usa o {@code criadoEm} ja carregado
     * na entidade (caso do {@code GET} historico, onde as linhas vem frescas do banco).
     */
    public static MovimentacaoResponse de(MovimentacaoEstoque mov) {
        return de(mov, mov.getCriadoEm());
    }

    /**
     * Variante para o {@code POST}: a entidade recem-inserida tem {@code criadoEm=null} em memoria
     * (coluna {@code insertable=false}, valor do {@code DEFAULT now()} do banco), entao o servico
     * carrega o {@code criadoEm} por projecao escalar e o injeta aqui (T-M2-4).
     */
    public static MovimentacaoResponse de(MovimentacaoEstoque mov, Instant criadoEm) {
        return new MovimentacaoResponse(
                mov.getId(),
                mov.getProdutoId(),
                mov.getProdutoNome(),
                mov.getTipo(),
                mov.getQuantidade(),
                mov.getQuantidadeResultante(),
                mov.getMotivo(),
                mov.getUsuarioId(),
                criadoEm);
    }
}
