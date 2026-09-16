package com.floricultura.api.service;

import com.floricultura.api.web.dto.Granularidade;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * Serie CONTINUA de baldes do relatorio (SPEC-M7 §3.7-b/§3.7-c — CA-21, CA-22). Classe <b>pura, sem
 * Spring</b>, no mesmo espirito do {@code CalculoFinanceiro} (§3.10): a matematica de calendario
 * (semana ISO, mes de 28/29/30/31 dias, virada de ano) fica verificavel em milissegundos, sem
 * Testcontainers.
 *
 * <p><b>A serie nasce de {@code de}/{@code ate}, nunca dos dados</b> — os totais do banco sao
 * <i>encaixados</i> nela depois. E isso que torna o CA-21 estrutural: um mes sem nenhuma linha nao tem
 * como sumir, porque o balde ja existia antes de a query rodar.
 *
 * <p><b>Bordas recortadas (CA-22):</b> o primeiro e o ultimo balde podem ser <b>parciais</b>. A
 * {@link Balde#chave()} e sempre o inicio <i>natural</i> do balde (1o dia do mes / <b>segunda-feira</b>
 * ISO) — e o que casa com o dia agregado pelo banco; {@link Balde#inicio()} e {@link Balde#fim()} sao
 * a chave e o fim natural <b>recortados</b> por {@code de}/{@code ate}, que e o que vai no payload.
 */
public final class SerieDePeriodos {

    /**
     * Um balde da serie. {@code chave} = inicio natural (agrupador); {@code inicio}/{@code fim} = o
     * intervalo efetivamente coberto, ja recortado pelo pedido.
     */
    public record Balde(LocalDate chave, LocalDate inicio, LocalDate fim) {
    }

    private SerieDePeriodos() {
        // Utilitario estatico.
    }

    /**
     * Baldes contiguos que cobrem {@code [de, ate]}, em ordem cronologica. O intervalo e limitado a
     * 366 dias pelo {@code FiltroRelatorio}, entao a lista tem no maximo 53 semanas ou 13 meses.
     */
    public static List<Balde> baldes(LocalDate de, LocalDate ate, Granularidade granularidade) {
        List<Balde> baldes = new ArrayList<>();
        LocalDate chave = chaveDe(de, granularidade);
        while (!chave.isAfter(ate)) {
            LocalDate fimNatural = fimNatural(chave, granularidade);
            baldes.add(new Balde(
                    chave,
                    chave.isBefore(de) ? de : chave,
                    fimNatural.isAfter(ate) ? ate : fimNatural));
            chave = proxima(chave, granularidade);
        }
        return List.copyOf(baldes);
    }

    /**
     * Balde a que um dia pertence: 1o dia do mes, ou a <b>segunda-feira</b> da semana ISO (§3.7-c).
     * E a mesma chave usada para encaixar a linha agregada do banco na serie.
     */
    public static LocalDate chaveDe(LocalDate dia, Granularidade granularidade) {
        return granularidade == Granularidade.SEMANA
                ? dia.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                : dia.withDayOfMonth(1);
    }

    private static LocalDate fimNatural(LocalDate chave, Granularidade granularidade) {
        return granularidade == Granularidade.SEMANA
                ? chave.plusDays(6)
                : chave.withDayOfMonth(chave.lengthOfMonth());
    }

    private static LocalDate proxima(LocalDate chave, Granularidade granularidade) {
        return granularidade == Granularidade.SEMANA ? chave.plusWeeks(1) : chave.plusMonths(1);
    }
}
