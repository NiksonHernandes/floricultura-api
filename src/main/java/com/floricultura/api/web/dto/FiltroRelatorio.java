package com.floricultura.api.web.dto;

import com.floricultura.api.web.response.FieldErrorItem;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Parametros do {@code GET /api/v1/relatorios/movimentacoes} (SPEC-M7 §3.7 — CA-20..CA-25, CA-54),
 * ja validados e normalizados. Guarda as datas de <b>negocio</b> ({@link LocalDate}, que voltam no
 * payload e geram a serie de baldes) <b>e</b> a {@link FiltroMovimentacao} que carrega os mesmos
 * limites ja ancorados em instantes para a query.
 *
 * <p><b>(a1) Reuso autorizado (§3.7-a1):</b> a ancoragem de fuso e os 400 de {@code de > ate} e de
 * {@code tipo} invalido vem da fabrica {@link FiltroMovimentacao#de} da T-M7-03 — <b>um so dialeto de
 * fuso na API</b> (duplicar as ~15 linhas seria a armadilha §12 #12). Este filtro <b>so acrescenta</b>
 * o que e dele: obrigatoriedade do periodo, {@code granularidade} e o teto de 366 dias.
 *
 * <p><b>(a0) "Obrigatorio" NAO e {@code @RequestParam} obrigatorio (§3.7-a0, CA-54):</b> o
 * {@code GlobalExceptionHandler} nao trata {@code MissingServletRequestParameterException}, entao um
 * parametro obrigatorio ausente cairia no catch-all e viraria <b>500 para erro de cliente</b>. O
 * controller declara {@code required = false} e a ausencia e validada <b>aqui</b>, como 400
 * {@code VALIDATION_ERROR} — que e a convencao ja vigente (as 52 ocorrencias de {@code @RequestParam}
 * do projeto usam {@code required = false}).
 *
 * <p><b>Ordem de validacao (deterministica, para a resposta nao depender de sorte):</b>
 * <ol>
 *   <li>presenca de {@code de}/{@code ate} — ambos ausentes ⇒ <b>dois</b> {@code details},
 *       <b>{@code de} primeiro</b>;</li>
 *   <li>a janela ({@code de > ate} ⇒ {@code field=de}; {@code tipo} fora do conjunto ⇒
 *       {@code field=tipo});</li>
 *   <li>teto de 366 dias ⇒ {@code field=ate};</li>
 *   <li>{@code granularidade} ⇒ {@code field=granularidade}.</li>
 * </ol>
 */
public record FiltroRelatorio(
        LocalDate de,
        LocalDate ate,
        Granularidade granularidade,
        FiltroMovimentacao janela) {

    /** Mesma mensagem-topo dos demais 400 de parametro de listagem (§3.5). */
    private static final String MSG = "Parametros de filtro invalidos.";

    /** Teto do intervalo (§3.7): {@code ate − de} maior que isto e 400 {@code field=ate}. */
    private static final int MAX_DIAS = 366;

    /**
     * Valida e normaliza os parametros crus da query string. {@code de}/{@code ate} chegam como
     * {@link LocalDate} (texto que nao e data ja virou 400 de tipo no handler global, M6.1/D3) e
     * {@code null} tanto quando ausentes quanto quando vem em branco ({@code ?de=}).
     */
    public static FiltroRelatorio de(
            LocalDate de,
            LocalDate ate,
            String granularidade,
            String tipo,
            Long produtoId,
            Long clienteId,
            Long fornecedorId) {
        exigirPeriodo(de, ate);
        FiltroMovimentacao janela =
                FiltroMovimentacao.de(null, de, ate, tipo, produtoId, clienteId, fornecedorId);
        if (ChronoUnit.DAYS.between(de, ate) > MAX_DIAS) {
            throw invalido("ate", "O intervalo do relatório não pode passar de 366 dias.");
        }
        return new FiltroRelatorio(de, ate, granularidadeDe(granularidade), janela);
    }

    /** CA-54: ausencia vira 400 com {@code details} em ordem fixa — nunca 500 (§3.7-a0). */
    private static void exigirPeriodo(LocalDate de, LocalDate ate) {
        List<FieldErrorItem> faltantes = new ArrayList<>(2);
        if (de == null) {
            faltantes.add(new FieldErrorItem("de", "Informe a data inicial do período (de)."));
        }
        if (ate == null) {
            faltantes.add(new FieldErrorItem("ate", "Informe a data final do período (ate)."));
        }
        if (!faltantes.isEmpty()) {
            throw new ParametroPaginacaoInvalidoException(MSG, List.copyOf(faltantes));
        }
    }

    /** Ausente/em branco ⇒ {@code MES} (default do §3.7); fora do conjunto ⇒ 400. */
    private static Granularidade granularidadeDe(String valor) {
        if (valor == null || valor.isBlank()) {
            return Granularidade.MES;
        }
        try {
            return Granularidade.valueOf(valor.trim());
        } catch (IllegalArgumentException foraDoConjunto) {
            throw invalido("granularidade", "granularidade deve ser um de: SEMANA, MES");
        }
    }

    private static ParametroPaginacaoInvalidoException invalido(String field, String message) {
        return new ParametroPaginacaoInvalidoException(
                MSG, List.of(new FieldErrorItem(field, message)));
    }
}
