package com.floricultura.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.floricultura.api.service.SerieDePeriodos.Balde;
import com.floricultura.api.web.dto.Granularidade;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Serie de baldes do relatorio (SPEC-M7 §3.7-b/§3.7-c — CA-21, CA-22). Teste <b>puro</b>, sem Spring e
 * sem Testcontainers: a matematica de calendario e deterministica e roda em milissegundos, no mesmo
 * espirito do {@code CalculoFinanceiroTest} (§3.10).
 *
 * <p>O que estes casos protegem: o balde existir mesmo <b>sem dado</b> (a serie nasce de
 * {@code de}/{@code ate}, nao da query) e as <b>bordas recortadas</b> do primeiro/ultimo balde — que e
 * onde mora o off-by-one deste recurso.
 */
class SerieDePeriodosTest {

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    /** Mes fechado: um balde so, sem recorte nenhum. */
    @Test
    void mesInteiroViraUmBaldeSemRecorte() {
        List<Balde> baldes = SerieDePeriodos.baldes(
                d("2026-09-01"), d("2026-09-30"), Granularidade.MES);

        assertEquals(1, baldes.size());
        assertEquals(new Balde(d("2026-09-01"), d("2026-09-01"), d("2026-09-30")), baldes.get(0));
    }

    /** CA-22 (perna do mes): primeiro e ultimo baldes PARCIAIS, o do meio inteiro. */
    @Test
    void mesesDasPontasVemRecortadosPorDeEAte() {
        List<Balde> baldes = SerieDePeriodos.baldes(
                d("2026-09-10"), d("2026-11-05"), Granularidade.MES);

        assertEquals(3, baldes.size());
        assertEquals(new Balde(d("2026-09-01"), d("2026-09-10"), d("2026-09-30")), baldes.get(0));
        assertEquals(new Balde(d("2026-10-01"), d("2026-10-01"), d("2026-10-31")), baldes.get(1));
        assertEquals(new Balde(d("2026-11-01"), d("2026-11-01"), d("2026-11-05")), baldes.get(2));
    }

    /**
     * CA-22: semana e <b>segunda a domingo</b> (ISO). {@code 2026-09-16} e uma QUARTA — o primeiro
     * balde comeca nela (recortado), mas sua <b>chave</b> e a segunda-feira {@code 2026-09-14}, que e
     * como a linha do banco encontra o balde.
     */
    @Test
    void semanaComecaNaSegundaEAsPontasVemRecortadas() {
        List<Balde> baldes = SerieDePeriodos.baldes(
                d("2026-09-16"), d("2026-09-27"), Granularidade.SEMANA);

        assertEquals(2, baldes.size());
        assertEquals(new Balde(d("2026-09-14"), d("2026-09-16"), d("2026-09-20")), baldes.get(0));
        assertEquals(new Balde(d("2026-09-21"), d("2026-09-21"), d("2026-09-27")), baldes.get(1));
    }

    /** Virada de ano: a semana ISO atravessa dezembro/janeiro sem buraco e sem balde duplicado. */
    @Test
    void semanaAtravessaAViradaDeAno() {
        List<Balde> baldes = SerieDePeriodos.baldes(
                d("2025-12-28"), d("2026-01-04"), Granularidade.SEMANA);

        assertEquals(2, baldes.size());
        assertEquals(new Balde(d("2025-12-22"), d("2025-12-28"), d("2025-12-28")), baldes.get(0));
        assertEquals(new Balde(d("2025-12-29"), d("2025-12-29"), d("2026-01-04")), baldes.get(1));
    }

    /** Intervalo de um dia: um balde, com {@code inicio == fim}. */
    @Test
    void umDiaSoViraUmBaldeDeUmDia() {
        List<Balde> baldes = SerieDePeriodos.baldes(
                d("2026-09-16"), d("2026-09-16"), Granularidade.MES);

        assertEquals(1, baldes.size());
        assertEquals(new Balde(d("2026-09-01"), d("2026-09-16"), d("2026-09-16")), baldes.get(0));
    }

    /** Fevereiro bissexto: o fim natural do balde e o dia 29, nao o 28 nem o 30. */
    @Test
    void fevereiroBissextoTermina29() {
        List<Balde> baldes = SerieDePeriodos.baldes(
                d("2024-02-01"), d("2024-03-01"), Granularidade.MES);

        assertEquals(2, baldes.size());
        assertEquals(new Balde(d("2024-02-01"), d("2024-02-01"), d("2024-02-29")), baldes.get(0));
        assertEquals(new Balde(d("2024-03-01"), d("2024-03-01"), d("2024-03-01")), baldes.get(1));
    }

    /** Teto do contrato (366 dias): 13 baldes de mes — a serie nao explode. */
    @Test
    void tetoDe366DiasGera13BaldesDeMes() {
        List<Balde> baldes = SerieDePeriodos.baldes(
                d("2026-01-01"), d("2027-01-02"), Granularidade.MES);

        assertEquals(13, baldes.size());
        assertEquals(d("2026-01-01"), baldes.get(0).inicio());
        assertEquals(d("2027-01-02"), baldes.get(12).fim());
    }

    /** A chave e o agrupador: dois dias da mesma semana/mes caem na MESMA chave. */
    @Test
    void chaveAgrupaDiasDoMesmoBalde() {
        assertEquals(
                SerieDePeriodos.chaveDe(d("2026-09-16"), Granularidade.SEMANA),
                SerieDePeriodos.chaveDe(d("2026-09-20"), Granularidade.SEMANA));
        assertEquals(d("2026-09-14"), SerieDePeriodos.chaveDe(d("2026-09-20"), Granularidade.SEMANA));
        assertEquals(d("2026-09-01"), SerieDePeriodos.chaveDe(d("2026-09-30"), Granularidade.MES));
    }
}
