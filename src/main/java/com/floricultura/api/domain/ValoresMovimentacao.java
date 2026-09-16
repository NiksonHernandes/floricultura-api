package com.floricultura.api.domain;

import java.math.BigDecimal;

/**
 * Bloco financeiro de uma linha do ledger (SPEC-M7 §3.1-a/§3.10) — as 5 colunas de valor da V13 mais o
 * ponteiro de estorno, agrupados num unico parametro.
 *
 * <p><b>Por que um record e nao 6 parametros posicionais:</b> {@link MovimentacaoFactory#nova} ja tem
 * 12 posicoes; acrescentar 6 viraria uma assinatura de 18 (§12 #7) — armadilha permanente, em que
 * trocar dois {@code BigDecimal} de lugar compila e grava errado num registro que ninguem pode
 * corrigir depois.
 *
 * <p>Todos os campos sao anulaveis e andam em bloco: ou ha dinheiro na linha (os 3 totais preenchidos)
 * ou nao ha nenhum ({@link #vazio()}) — invariante que os CHECKs {@code ck_mov_totais_par} e
 * {@code ck_mov_desconto_par} da V13 garantem no banco. Os valores ja chegam aqui <b>normalizados</b>
 * na escala 2 (§3.2-b1): o que esta neste record e exatamente o que vai para as colunas.
 *
 * @param valorUnitario          preco unitario congelado ({@code null} = lancamento sem dinheiro)
 * @param descontoTipo           {@code PERCENTUAL} ou {@code VALOR} ({@code null} = sem desconto)
 * @param descontoValor          percentual {@code 0..100} ou reais, conforme o tipo
 * @param totalBruto             {@code quantidade x valorUnitario}, escala 2
 * @param totalFinal             {@code totalBruto - desconto efetivo}, escala 2
 * @param estornaMovimentacaoId  id da linha que esta linha estorna ({@code null} = lancamento comum)
 */
public record ValoresMovimentacao(
        BigDecimal valorUnitario,
        String descontoTipo,
        BigDecimal descontoValor,
        BigDecimal totalBruto,
        BigDecimal totalFinal,
        Long estornaMovimentacaoId) {

    private static final ValoresMovimentacao VAZIO =
            new ValoresMovimentacao(null, null, null, null, null, null);

    /** Lancamento sem dinheiro (P6) — as 6 colunas ficam {@code NULL}. E o caso do M2/M4/M5. */
    public static ValoresMovimentacao vazio() {
        return VAZIO;
    }
}
