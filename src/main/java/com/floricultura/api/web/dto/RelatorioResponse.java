package com.floricultura.api.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Payload do {@code GET /api/v1/relatorios/movimentacoes} (SPEC-M7 §3.7), dentro do envelope
 * {@code ApiResponse}. Espelha o JSON do contrato campo a campo e na mesma ordem.
 *
 * <p><b>Escalas fixas, porque dinheiro e quantidade sao lidos por humano:</b> {@code valor} sempre com
 * <b>2</b> casas ({@code NUMERIC(14,2)}, como {@code total_final}) e {@code quantidade} com <b>3</b>
 * ({@code NUMERIC(14,3)}) — inclusive no balde vazio, que sai {@code 0.00}/{@code 0.000} e nao
 * {@code 0}.
 *
 * @param de             1o dia do intervalo pedido (data de negocio, {@code America/Sao_Paulo})
 * @param ate            ultimo dia do intervalo pedido, <b>inteiro</b>
 * @param granularidade  corte dos baldes ({@code SEMANA} ou {@code MES})
 * @param resumo         totais do intervalo inteiro
 * @param periodos       serie <b>continua</b> de baldes (balde sem movimentacao vem com zeros, CA-21)
 */
public record RelatorioResponse(
        LocalDate de,
        LocalDate ate,
        Granularidade granularidade,
        Resumo resumo,
        List<Periodo> periodos) {

    /**
     * Totais de um tipo de lancamento num recorte.
     *
     * @param lancamentos quantas linhas do ledger entraram (as do par estornado NAO entram, §3.7-d)
     * @param quantidade  soma de {@code quantidade} (3 casas)
     * @param valor       soma de {@code total_final} tratando {@code NULL} como zero (2 casas);
     *                    em {@code ajustes} e sempre {@code 0.00} (PA#1)
     */
    public record Totais(long lancamentos, BigDecimal quantidade, BigDecimal valor) {
    }

    /**
     * Totais do intervalo inteiro.
     *
     * @param resultadoValor                 {@code saidas.valor − entradas.valor} (§3.7-a)
     * @param lancamentosEstornadosExcluidos quantas LINHAS do recorte ficaram de fora por participarem
     *                                       de um par estornado — <b>complemento exato</b> do mesmo
     *                                       recorte, nunca "2 sempre" (§3.7-d, CA-55). A omissao e
     *                                       <b>declarada</b>, jamais silenciosa (PA#3)
     */
    public record Resumo(
            Totais entradas,
            Totais saidas,
            Totais ajustes,
            BigDecimal resultadoValor,
            long lancamentosEstornadosExcluidos) {
    }

    /**
     * Um balde da serie.
     *
     * @param inicio primeiro dia coberto — <b>recortado</b> por {@code de} no primeiro balde (CA-22)
     * @param fim    ultimo dia coberto — <b>recortado</b> por {@code ate} no ultimo balde
     */
    public record Periodo(
            LocalDate inicio,
            LocalDate fim,
            Totais entradas,
            Totais saidas,
            Totais ajustes,
            BigDecimal resultadoValor) {
    }
}
