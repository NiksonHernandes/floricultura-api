package com.floricultura.api.web.dto;

import com.floricultura.api.config.ClockConfig;
import com.floricultura.api.web.response.FieldErrorItem;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Filtros do {@code GET /api/v1/movimentacoes} (SPEC-M7 §3.5 — CA-16..CA-19), ja validados e
 * convertidos para o que a query nativa consome. Todos opcionais; a semantica entre parametros
 * <b>diferentes</b> e <b>E</b> (mesma regra que o M6 fixou para produtos, P4). Ausencia total =
 * contrato do M4 palavra por palavra (CA-16).
 *
 * <p><b>Fuso (§9, armadilha §12 #12):</b> {@code criado_em} e {@code TIMESTAMPTZ} (UTC). As datas
 * chegam como {@code yyyy-MM-dd} de negocio e sao ancoradas em {@code America/Sao_Paulo} aqui, uma
 * unica vez: {@code de} vira o instante de <b>00:00 do dia</b> e {@code ate} vira o instante de
 * <b>00:00 do dia SEGUINTE</b> — o predicado e {@code criado_em &gt;= de AND criado_em &lt; ate}, que
 * e como o dia final <b>entra inteiro</b> (um lancamento as 23:30 do dia {@code ate} aparece, CA-17)
 * sem depender de "23:59:59" nem de precisao de fracao de segundo.
 *
 * <p><b>Erros (400 VALIDATION_ERROR, com {@code details[0].field}):</b> {@code de > ate} →
 * {@code field=de}; {@code tipo} fora de {@code ENTRADA|SAIDA|AJUSTE} → {@code field=tipo}. Reusa a
 * {@link ParametroPaginacaoInvalidoException} com mensagem propria — o mesmo caminho que o M6 ja abriu
 * para parametros de <b>listagem</b> que nao sao de paginacao ({@code ordenarPor}/{@code direcao}),
 * aproveitando o handler local do controller sem criar um segundo dialeto de erro. Parametro de
 * <b>tipo</b> errado ({@code ?produtoId=abc}) continua sendo 400 do {@code GlobalExceptionHandler}
 * (M6.1/D3) — nao ha conversao manual aqui que pudesse regredir isso.
 */
public record FiltroMovimentacao(
        String q,
        Instant de,
        Instant ate,
        String tipo,
        Long produtoId,
        Long clienteId,
        Long fornecedorId) {

    /** Conjunto fechado do {@code tipo} — o mesmo CHECK {@code ck_movimentacao_tipo} da V1. */
    private static final Set<String> TIPOS = Set.of("ENTRADA", "SAIDA", "AJUSTE");

    private static final String MSG = "Parametros de filtro invalidos.";

    /**
     * Valida e normaliza os parametros crus da query string. {@code de}/{@code ate} ja chegam como
     * {@link LocalDate} (a conversao do texto e do Spring — formato invalido vira 400 pelo handler de
     * tipo, M6.1/D3); {@code q} e {@code tipo} em branco valem como ausentes.
     */
    public static FiltroMovimentacao de(
            String q,
            LocalDate de,
            LocalDate ate,
            String tipo,
            Long produtoId,
            Long clienteId,
            Long fornecedorId) {
        if (de != null && ate != null && de.isAfter(ate)) {
            throw invalido("de", "A data inicial não pode ser maior que a final.");
        }
        String tipoFiltro = semBranco(tipo);
        if (tipoFiltro != null && !TIPOS.contains(tipoFiltro)) {
            throw invalido("tipo", "tipo deve ser um de: ENTRADA, SAIDA, AJUSTE");
        }
        return new FiltroMovimentacao(
                semBranco(q),
                instanteDoDia(de),
                instanteDoDia(ate == null ? null : ate.plusDays(1)),
                tipoFiltro,
                produtoId,
                clienteId,
                fornecedorId);
    }

    private static Instant instanteDoDia(LocalDate dia) {
        return dia == null
                ? null
                : dia.atStartOfDay(ClockConfig.ZONA_SAO_PAULO).toInstant();
    }

    private static String semBranco(String valor) {
        return (valor == null || valor.isBlank()) ? null : valor.trim();
    }

    private static ParametroPaginacaoInvalidoException invalido(String field, String message) {
        return new ParametroPaginacaoInvalidoException(
                MSG, List.of(new FieldErrorItem(field, message)));
    }
}
