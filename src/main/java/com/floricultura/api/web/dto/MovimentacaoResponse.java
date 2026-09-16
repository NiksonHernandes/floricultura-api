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
 * @param usuarioNome          snapshot do nome do autor (V7/AD-SQ-45; {@code null} em linhas historicas
 *                             pre-V7 ou autor desconhecido — front exibe "—")
 * @param fornecedorId         contraparte fornecedor (V10/AD-SQ-64; {@code null} sem contraparte ou apos
 *                             hard delete do cadastro — o {@code fornecedorNome} sobrevive)
 * @param fornecedorNome       snapshot do nome do fornecedor ({@code null} sem contraparte)
 * @param clienteId            contraparte cliente (V10/AD-SQ-64; {@code null} sem contraparte ou apos
 *                             hard delete do cadastro — o {@code clienteNome} sobrevive)
 * @param clienteNome          snapshot do nome do cliente ({@code null} sem contraparte)
 * @param criadoEm             instante de criacao (UTC ISO-8601), do {@code DEFAULT now()} do banco
 *
 * <p><b>Valores (V13/SPEC-M7 §3.3) — 6 campos ADITIVOS, no fim do record</b> de proposito: nenhum
 * campo existente muda de nome, tipo ou <b>posicao</b>. Todos {@code null} quando nao se aplicam
 * (lancamento sem dinheiro, P6), e {@code jsonPath(...).doesNotExist()} continua valendo para eles.
 *
 * @param valorUnitario         preco unitario congelado no lancamento ({@code null} = sem dinheiro)
 * @param descontoTipo          {@code PERCENTUAL}/{@code VALOR} ({@code null} = sem desconto)
 * @param descontoValor         percentual {@code 0..100} ou reais, conforme o tipo
 * @param totalBruto            {@code quantidade x valorUnitario}, calculado pelo servidor
 * @param totalFinal            {@code totalBruto - desconto efetivo} (o desconto em R$ e derivavel)
 * @param estornaMovimentacaoId id da linha que esta linha estorna ({@code null} = lancamento comum)
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
        String usuarioNome,
        Long fornecedorId,
        String fornecedorNome,
        Long clienteId,
        String clienteNome,
        Instant criadoEm,
        BigDecimal valorUnitario,
        String descontoTipo,
        BigDecimal descontoValor,
        BigDecimal totalBruto,
        BigDecimal totalFinal,
        Long estornaMovimentacaoId) {

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
                mov.getUsuarioNome(),
                mov.getFornecedorId(),
                mov.getFornecedorNome(),
                mov.getClienteId(),
                mov.getClienteNome(),
                criadoEm,
                mov.getValorUnitario(),
                mov.getDescontoTipo(),
                mov.getDescontoValor(),
                mov.getTotalBruto(),
                mov.getTotalFinal(),
                mov.getEstornaMovimentacaoId());
    }
}
