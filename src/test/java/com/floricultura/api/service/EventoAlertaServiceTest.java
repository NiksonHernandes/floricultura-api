package com.floricultura.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.floricultura.api.domain.Evento;
import com.floricultura.api.domain.EventoFactory;
import com.floricultura.api.repository.EventoRepository;
import com.floricultura.api.web.dto.EventoProximoResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit do alerta on-read (T-M4-7, CA-13..CA-17 — §4.3/§4.4) com {@code Clock} FIXO e {@link
 * EventoRepository} mockado — sem banco/contexto Spring. Cada teste fixa {@code hoje} e asserta
 * <b>valores exatos</b> (nunca ranges): limites 61/60/30/7/0/&lt;0, rollover anual, 29/02→28/02 e
 * periodo em curso (ancora anti-burla §6.2).
 */
@ExtendWith(MockitoExtension.class)
class EventoAlertaServiceTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    @Mock
    private EventoRepository eventoRepository;

    /** Constroi o service com {@code hoje} fixo (clock em America/Sao_Paulo). */
    private EventoAlertaService serviceEm(LocalDate hoje) {
        Clock fixo = Clock.fixed(hoje.atStartOfDay(SP).toInstant(), SP);
        return new EventoAlertaService(eventoRepository, fixo);
    }

    /** Evento com id/nome/datas/recorrencia; {@code dataFim} null = data unica. */
    private Evento evento(String nome, LocalDate dataInicio, LocalDate dataFim, boolean anual) {
        Evento e = EventoFactory.novo(nome, "COMEMORATIVA", dataInicio, dataFim, anual, null);
        e.setId(1L);
        return e;
    }

    private List<EventoProximoResponse> proximosCom(LocalDate hoje, Evento... eventos) {
        when(eventoRepository.listarTodosParaAlerta()).thenReturn(List.of(eventos));
        return serviceEm(hoje).proximos();
    }

    // ---- CA-15: limites exatos (evento anual "Dia das Maes", dataInicio 2026-05-10) -----------

    @Test
    void dia61_naoAparece() {
        Evento maes = evento("Dia das Maes", LocalDate.of(2026, 5, 10), null, true);
        assertThat(proximosCom(LocalDate.of(2026, 3, 10), maes)).isEmpty(); // 61 dias → fora
    }

    @Test
    void dia60_apareceComFaixaZero() {
        Evento maes = evento("Dia das Maes", LocalDate.of(2026, 5, 10), null, true);
        List<EventoProximoResponse> r = proximosCom(LocalDate.of(2026, 3, 11), maes);
        assertThat(r).singleElement().satisfies(item -> {
            assertThat(item.proximaOcorrencia()).isEqualTo(LocalDate.of(2026, 5, 10));
            assertThat(item.diasAte()).isEqualTo(60);
            assertThat(item.faixaUrgencia()).isZero();
            assertThat(item.destaqueReforcado()).isFalse();
        });
    }

    @Test
    void dia30_ligaReforcoComFaixaUm() {
        Evento maes = evento("Dia das Maes", LocalDate.of(2026, 5, 10), null, true);
        List<EventoProximoResponse> r = proximosCom(LocalDate.of(2026, 4, 10), maes);
        assertThat(r).singleElement().satisfies(item -> {
            assertThat(item.diasAte()).isEqualTo(30);
            assertThat(item.faixaUrgencia()).isEqualTo(1);
            assertThat(item.destaqueReforcado()).isTrue();
        });
    }

    @Test
    void dia7_faixaCinco() {
        Evento maes = evento("Dia das Maes", LocalDate.of(2026, 5, 10), null, true);
        List<EventoProximoResponse> r = proximosCom(LocalDate.of(2026, 5, 3), maes);
        assertThat(r).singleElement().satisfies(item -> {
            assertThat(item.diasAte()).isEqualTo(7);
            assertThat(item.faixaUrgencia()).isEqualTo(5);
        });
    }

    @Test
    void dia0_ehHoje_faixaCinco() {
        Evento maes = evento("Dia das Maes", LocalDate.of(2026, 5, 10), null, true);
        List<EventoProximoResponse> r = proximosCom(LocalDate.of(2026, 5, 10), maes);
        assertThat(r).singleElement().satisfies(item -> {
            assertThat(item.diasAte()).isZero();
            assertThat(item.faixaUrgencia()).isEqualTo(5);
        });
    }

    // ---- CA-13/CA-14: rollover anual (ocorrencia do ano ja passou → mira o proximo) -----------

    @Test
    void anualQuePassou_miraAnoSeguinte_eSome() {
        Evento maes = evento("Dia das Maes", LocalDate.of(2026, 5, 10), null, true);
        // hoje = 2026-05-11: passou 1 dia → proxima ocorrencia 2027-05-10 (364 dias) → fora da janela.
        assertThat(proximosCom(LocalDate.of(2026, 5, 11), maes)).isEmpty();
    }

    @Test
    void viradaDeAno_miraJaneiroDoAnoSeguinteDentroDaJanela() {
        Evento confra = evento("Confraternizacao", LocalDate.of(2026, 1, 1), null, true);
        // hoje = 2025-11-02: ocorrencia de 2025 ja passou → mira 2026-01-01, 60 dias → aparece.
        List<EventoProximoResponse> r = proximosCom(LocalDate.of(2025, 11, 2), confra);
        assertThat(r).singleElement().satisfies(item -> {
            assertThat(item.proximaOcorrencia()).isEqualTo(LocalDate.of(2026, 1, 1));
            assertThat(item.diasAte()).isEqualTo(60);
        });
    }

    // ---- CA-17: 29/02 anual em ano nao-bissexto → 28/02 (sem erro) ----------------------------

    @Test
    void bissexto29Fev_emAnoNaoBissexto_viraDia28() {
        Evento e = evento("Bissexto", LocalDate.of(2028, 2, 29), null, true);
        // hoje = 2029-01-15 → ano-alvo 2029 (nao bissexto) → 2029-02-28.
        List<EventoProximoResponse> r = proximosCom(LocalDate.of(2029, 1, 15), e);
        assertThat(r).singleElement()
                .extracting(EventoProximoResponse::proximaOcorrencia)
                .isEqualTo(LocalDate.of(2029, 2, 28));
    }

    // ---- CA-16: periodo em curso (dataFim presente) permanece; passando o fim, some -----------

    @Test
    void periodoEmCurso_emAndamentoFaixaCinco() {
        Evento festa = evento("Festa das Flores",
                LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 13), false);
        // hoje = 2026-09-10: dentro do periodo → diasAte negativo, emAndamento, faixa 5, aparece.
        List<EventoProximoResponse> r = proximosCom(LocalDate.of(2026, 9, 10), festa);
        assertThat(r).singleElement().satisfies(item -> {
            assertThat(item.emAndamento()).isTrue();
            assertThat(item.faixaUrgencia()).isEqualTo(5);
            assertThat(item.diasAte()).isEqualTo(-6);
        });
    }

    @Test
    void periodoNaoAnual_aposOFim_some() {
        Evento festa = evento("Festa das Flores",
                LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 13), false);
        // hoje = 2026-09-14: passou o fim (nao recorre) → some.
        assertThat(proximosCom(LocalDate.of(2026, 9, 14), festa)).isEmpty();
    }

    // ---- CA-13: filtra fora-da-janela e ordena por proximaOcorrencia ASC ----------------------

    @Test
    void ordenaPorProximaOcorrenciaAsc_eFiltraForaDaJanela() {
        Evento perto = evento("Perto", LocalDate.of(2026, 5, 20), null, false); // 10 dias
        Evento medio = evento("Medio", LocalDate.of(2026, 6, 9), null, false);  // 30 dias
        Evento longe = evento("Longe", LocalDate.of(2026, 9, 1), null, false);  // >60 → fora
        List<EventoProximoResponse> r =
                proximosCom(LocalDate.of(2026, 5, 10), perto, longe, medio);
        assertThat(r).extracting(EventoProximoResponse::nome).containsExactly("Perto", "Medio");
    }
}
