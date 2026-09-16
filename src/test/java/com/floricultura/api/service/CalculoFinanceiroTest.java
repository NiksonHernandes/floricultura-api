package com.floricultura.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.floricultura.api.domain.ValoresMovimentacao;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Unitario puro (sem Spring, sem banco) da regra de calculo da SPEC-M7 §3.2-b/§3.2-b1 — CA-4, CA-7,
 * CA-8 e CA-53. Cada caso crava os numeros literais do SDD.
 *
 * <p><b>Os dois casos de EMPATE existem por um motivo especifico</b> (§10 #3c): sem eles, nenhuma
 * mutacao distingue {@code HALF_UP} de {@code HALF_DOWN}/{@code HALF_EVEN} — a borda da CA-8
 * ({@code 0.329967}) nao e empate, a parte descartada e {@code 0.9967} e todo modo {@code HALF_*}
 * arredonda igual. Um empate pinga no arredondamento do <b>desconto</b> ({@code 0.225}) e o outro no
 * do <b>bruto</b> ({@code 0.22500}), que sao sitios diferentes do calculo.
 */
class CalculoFinanceiroTest {

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    private ValoresMovimentacao calcular(
            String quantidade, String valorUnitario, String descontoTipo, String descontoValor) {
        return CalculoFinanceiro.calcular(
                bd(quantidade),
                valorUnitario == null ? null : bd(valorUnitario),
                descontoTipo,
                descontoValor == null ? null : bd(descontoValor));
    }

    // ----- CA-4: o caminho comum -----

    /** Sem desconto: o final e o proprio bruto (e as colunas de desconto ficam nulas). */
    @Test
    void semDescontoTotalFinalIgualAoBruto() {
        ValoresMovimentacao v = calcular("3", "10.00", null, null);

        assertThat(v.valorUnitario()).isEqualTo(bd("10.00"));
        assertThat(v.totalBruto()).isEqualTo(bd("30.00"));
        assertThat(v.totalFinal()).isEqualTo(bd("30.00"));
        assertThat(v.descontoTipo()).isNull();
        assertThat(v.descontoValor()).isNull();
        assertThat(v.estornaMovimentacaoId()).isNull();
    }

    /** Exemplo cravado do §3.2-b: 3 x 10,00 com 15 % => bruto 30,00, desconto 4,50, final 25,50. */
    @Test
    void descontoPercentualDeQuinzePorCento() {
        ValoresMovimentacao v = calcular("3", "10.00", "PERCENTUAL", "15");

        assertThat(v.totalBruto()).isEqualTo(bd("30.00"));
        assertThat(v.totalFinal()).isEqualTo(bd("25.50"));
        assertThat(v.descontoValor()).isEqualTo(bd("15.00"));
    }

    /** Lancamento sem dinheiro (P6): sem valorUnitario, as 5 colunas ficam nulas. */
    @Test
    void semValorUnitarioNaoHaDinheiroNaLinha() {
        ValoresMovimentacao v = calcular("3", null, null, null);

        assertThat(v).isEqualTo(ValoresMovimentacao.vazio());
        assertThat(v.totalBruto()).isNull();
        assertThat(v.totalFinal()).isNull();
    }

    // ----- CA-7: as 4 fronteiras que PASSAM -----

    /** 0 % e desconto legitimo: nao altera o total. */
    @Test
    void descontoPercentualZeroNaoAlteraOTotal() {
        ValoresMovimentacao v = calcular("3", "10.00", "PERCENTUAL", "0");

        assertThat(v.totalBruto()).isEqualTo(bd("30.00"));
        assertThat(v.totalFinal()).isEqualTo(bd("30.00"));
    }

    /** 100 % e legitimo (brinde/doacao): final 0,00 — que NAO e o mesmo que "sem valor" (null). */
    @Test
    void descontoPercentualCemZeraOTotal() {
        ValoresMovimentacao v = calcular("3", "10.00", "PERCENTUAL", "100");

        assertThat(v.totalBruto()).isEqualTo(bd("30.00"));
        assertThat(v.totalFinal()).isEqualTo(bd("0.00"));
    }

    /** Desconto em reais igual ao bruto: final 0,00 (a fronteira que V8 deixa passar). */
    @Test
    void descontoValorIgualAoBrutoZeraOTotal() {
        ValoresMovimentacao v = calcular("3", "10.00", "VALOR", "30.00");

        assertThat(v.totalBruto()).isEqualTo(bd("30.00"));
        assertThat(v.totalFinal()).isEqualTo(bd("0.00"));
    }

    /** Unitario 0,00 sem desconto: bruto e final 0,00 — tambem e um lancamento valido. */
    @Test
    void valorUnitarioZeroGeraTotaisZerados() {
        ValoresMovimentacao v = calcular("3", "0", null, null);

        assertThat(v.valorUnitario()).isEqualTo(bd("0.00"));
        assertThat(v.totalBruto()).isEqualTo(bd("0.00"));
        assertThat(v.totalFinal()).isEqualTo(bd("0.00"));
    }

    // ----- CA-8 e os empates: a prova de que HALF_UP nao e decorativo -----

    /** Borda do §3.2-b: 3 x 0,33 = 0,99 com 33,33 % => 0,329967 -> 0,33; final 0,66. */
    @Test
    void descontoPercentualArredondaParaCima() {
        ValoresMovimentacao v = calcular("3", "0.33", "PERCENTUAL", "33.33");

        assertThat(v.totalBruto()).isEqualTo(bd("0.99"));
        assertThat(v.totalFinal()).isEqualTo(bd("0.66"));
    }

    /** Empate no DESCONTO: 0,50 @ 45 % = 0,225 -> HALF_UP 0,23 (final 0,27), nao 0,22. */
    @Test
    void empateNoDescontoArredondaParaCima() {
        ValoresMovimentacao v = calcular("1", "0.50", "PERCENTUAL", "45");

        assertThat(v.totalBruto()).isEqualTo(bd("0.50"));
        assertThat(v.totalFinal()).isEqualTo(bd("0.27"));
    }

    /** Empate no BRUTO: 1,500 x 0,15 = 0,22500 -> HALF_UP 0,23, nao 0,22. */
    @Test
    void empateNoBrutoArredondaParaCima() {
        ValoresMovimentacao v = calcular("1.500", "0.15", null, null);

        assertThat(v.totalBruto()).isEqualTo(bd("0.23"));
        assertThat(v.totalFinal()).isEqualTo(bd("0.23"));
    }

    // ----- CA-53: normalizacao da entrada (a linha tem de fechar consigo mesma) -----

    /**
     * {@code 10.005} vira {@code 10.01} e e ESSE numero que entra na conta: com quantidade 2, o bruto e
     * {@code 20.02} (e nao {@code 20.01}, que sairia do valor cru e nao bateria com a coluna gravada).
     */
    @Test
    void normalizaValorUnitarioDeTresCasas() {
        ValoresMovimentacao v = calcular("2", "10.005", null, null);

        assertThat(v.valorUnitario()).isEqualTo(bd("10.01"));
        assertThat(v.totalBruto()).isEqualTo(bd("20.02"));
        assertThat(v.totalBruto())
                .as("a linha fecha consigo mesma: bruto = round(quantidade x unitario gravado, 2)")
                .isEqualTo(CalculoFinanceiro.totalBruto(bd("2"), v.valorUnitario()));
    }

    /** O percentual tambem e normalizado antes de entrar na conta: {@code 33.335} vira {@code 33.34}. */
    @Test
    void normalizaPercentualDeTresCasas() {
        ValoresMovimentacao v = calcular("3", "10.00", "PERCENTUAL", "33.335");

        assertThat(v.descontoValor()).isEqualTo(bd("33.34"));
        assertThat(v.totalBruto()).isEqualTo(bd("30.00"));
        assertThat(v.totalFinal()).isEqualTo(bd("20.00"));
    }

    /**
     * <b>O caso que REPROVA</b> (§3.2-b2, CA-53). Os dois casos acima <b>documentam</b> a normalizacao,
     * mas nao a provam: {@code 10.005} e {@code 33.335} sao aprovados tambem pelas implementacoes
     * ingenuas, porque o erro de representacao binaria cai do lado de CIMA do empate
     * ({@code new BigDecimal(10.005d) = 10.00500000000000078...} ⇒ sobe para {@code 10.01} por
     * acidente). Em {@code 8.165} o erro cai do lado de BAIXO
     * ({@code new BigDecimal(8.165d) = 8.16499999999999914...}) e a implementacao errada grava
     * {@code 8.16} — um centavo a menos, numa linha imutavel.
     *
     * <p>Por isso o numero aqui e {@code 8.165} e nao outro: trocar {@code valor.setScale(2, HALF_UP)}
     * por {@code new BigDecimal(valor.doubleValue()).setScale(2, HALF_UP)} deixa este caso VERMELHO
     * ({@code expected 8.17, was 8.16}) enquanto todos os demais continuam verdes.
     */
    @Test
    void normalizacaoDiscriminanteOitoCentoESessentaECinco() {
        ValoresMovimentacao v = calcular("2", "8.165", null, null);

        assertThat(v.valorUnitario()).isEqualTo(bd("8.17"));
        assertThat(v.totalBruto()).isEqualTo(bd("16.34"));
        assertThat(v.totalFinal()).isEqualTo(bd("16.34"));
        assertThat(v.totalBruto())
                .as("a linha fecha consigo mesma: bruto = round(quantidade x unitario gravado, 2)")
                .isEqualTo(CalculoFinanceiro.totalBruto(bd("2"), v.valorUnitario()));
    }
}
