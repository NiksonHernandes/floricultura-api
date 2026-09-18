package com.floricultura.api.service;

import com.floricultura.api.domain.ValoresMovimentacao;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Regra de calculo dos valores da movimentacao (SPEC-M7 §3.2-b/§3.2-b1) — classe <b>pura</b>, sem
 * Spring e sem banco, para o arredondamento ser testavel em milissegundos (§3.10).
 *
 * <p><b>Escala 2, {@link RoundingMode#HALF_UP}</b>, por tres razoes, na ordem em que pesaram:
 * (1) e a escala das colunas {@code NUMERIC(14,2)} da V13, a mesma de {@code produto.preco} (V3);
 * (2) {@code HALF_UP} e o arredondamento comercial e <b>o mesmo que o PostgreSQL aplica</b> ao gravar
 * em {@code NUMERIC} (half away from zero) — usar {@code HALF_EVEN} faria aplicacao e banco divergirem
 * num empate exato, e divergencia em linha imutavel nao tem conserto; (3) a divisao do percentual
 * SEMPRE leva escala e modo explicitos, nunca {@code divide(x)} puro, que estoura em dizima.
 *
 * <p><b>Normalizacao da entrada (§3.2-b1, CA-53):</b> {@code valorUnitario} e {@code descontoValor}
 * chegam do JSON e podem vir com 3+ casas ({@code 10.005}). Se a conta usasse o valor cru e a coluna
 * guardasse o arredondado, a linha ficaria inconsistente <b>consigo mesma</b>: medido que
 * {@code quantidade=2, valorUnitario=10.005} gravaria {@code valor_unitario=10.01} com
 * {@code total_bruto=20.01}, enquanto {@code round(2 x 10.01, 2) = 20.02}. Por isso a normalizacao e o
 * <b>passo 0</b>, e e o valor normalizado que vai para a coluna, para a conta <b>e para as validacoes
 * de faixa</b> (V6/V7/V8 do §3.2-c) — regra que depende de qual numero se olha ninguem reproduz.
 */
public final class CalculoFinanceiro {

    /** Escala de moeda do projeto — casa com {@code NUMERIC(14,2)} da V13. */
    public static final int ESCALA = 2;

    /**
     * Escala de <b>quantidade</b> — casa com {@code NUMERIC(14,3)} de {@code quantidade} e
     * {@code quantidade_resultante} (V1).
     */
    public static final int ESCALA_QUANTIDADE = 3;

    /** Arredondamento do projeto — o mesmo do PostgreSQL ao gravar {@code NUMERIC}. */
    public static final RoundingMode ARREDONDAMENTO = RoundingMode.HALF_UP;

    public static final String DESCONTO_PERCENTUAL = "PERCENTUAL";
    public static final String DESCONTO_VALOR = "VALOR";

    private static final BigDecimal CEM = new BigDecimal("100");

    private CalculoFinanceiro() {
        // Utilitaria — sem instancia.
    }

    /** Passo 0 (§3.2-b1): poe o numero na escala da coluna. {@code null} continua {@code null}. */
    public static BigDecimal normalizar(BigDecimal valor) {
        return valor == null ? null : valor.setScale(ESCALA, ARREDONDAMENTO);
    }

    /**
     * Passo 0 da <b>quantidade</b> (decisao do dono, 2026-09-18) — o campo que tinha ficado de fora.
     *
     * <p><b>O defeito que isto fecha, medido:</b> a conta usava a quantidade <b>crua</b> enquanto a
     * coluna {@code NUMERIC(14,3)} guardava a arredondada, entao a linha nao fechava consigo mesma —
     * {@code 1.0005 x 1000.00} gravava {@code quantidade=1.001} com {@code total_bruto=1000.50},
     * quando {@code round(1.001 x 1000.00, 2) = 1001.00}. Numa linha <b>imutavel</b>, quem reconferisse
     * a multiplicacao acharia outro total e nao teria como corrigir.
     *
     * <p><b>Por que arredondar e nao recusar:</b> o dono escolheu entre as duas e ficou com a
     * consistencia — "e exatamente a regra que ja aprovei para o preco unitario, so estende ao campo
     * que ficou de fora". Um 400 para 4+ casas criaria <b>duas regras para o mesmo problema</b>.
     */
    public static BigDecimal normalizarQuantidade(BigDecimal quantidade) {
        return quantidade == null
                ? null
                : quantidade.setScale(ESCALA_QUANTIDADE, ARREDONDAMENTO);
    }

    /** {@code (quantidade x valorUnitario)} na escala 2 — com o valor unitario JA normalizado. */
    public static BigDecimal totalBruto(BigDecimal quantidade, BigDecimal valorUnitario) {
        return quantidade.multiply(valorUnitario).setScale(ESCALA, ARREDONDAMENTO);
    }

    /**
     * Desconto efetivo em reais: {@code PERCENTUAL} incide sobre o bruto <b>ja arredondado</b> (e o que
     * faz a borda da CA-8 fechar em {@code 0.33}); {@code VALOR} e o proprio numero normalizado. Sem
     * desconto, {@code ZERO}.
     */
    public static BigDecimal descontoEfetivo(
            BigDecimal totalBruto, String descontoTipo, BigDecimal descontoValor) {
        if (descontoTipo == null || descontoValor == null) {
            return BigDecimal.ZERO.setScale(ESCALA);
        }
        if (DESCONTO_PERCENTUAL.equals(descontoTipo)) {
            return totalBruto.multiply(descontoValor).divide(CEM, ESCALA, ARREDONDAMENTO);
        }
        return descontoValor;
    }

    /**
     * Monta o bloco financeiro da linha a partir do que veio no payload. Sem {@code valorUnitario} o
     * lancamento e legitimo e fica <b>sem dinheiro</b> (P6): {@link ValoresMovimentacao#vazio()}.
     *
     * <p>Nao valida nada — faixas e combinacoes sao do servico (§3.2-c), que chama {@link #normalizar}
     * antes de comparar, para julgar o mesmo numero que sera gravado.
     */
    public static ValoresMovimentacao calcular(
            BigDecimal quantidade,
            BigDecimal valorUnitario,
            String descontoTipo,
            BigDecimal descontoValor) {
        if (valorUnitario == null) {
            return ValoresMovimentacao.vazio();
        }
        BigDecimal unitario = normalizar(valorUnitario);
        BigDecimal desconto = normalizar(descontoValor);
        // A quantidade entra no passo 0 pelo MESMO motivo dos outros dois: e ela multiplicada que
        // tem de bater com a que a coluna guarda (decisao do dono, 2026-09-18).
        BigDecimal bruto = totalBruto(normalizarQuantidade(quantidade), unitario);
        BigDecimal efetivo = descontoEfetivo(bruto, descontoTipo, desconto);
        return new ValoresMovimentacao(
                unitario, descontoTipo, desconto, bruto, bruto.subtract(efetivo), null);
    }
}
