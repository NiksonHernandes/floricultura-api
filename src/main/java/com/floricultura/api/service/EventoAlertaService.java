package com.floricultura.api.service;

import com.floricultura.api.domain.Evento;
import com.floricultura.api.repository.EventoRepository;
import com.floricultura.api.web.dto.EventoProximoResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Year;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alerta on-read de "proximos eventos" do M4 (T-M4-7, CA-13..CA-17 — SPEC-M4 §3.3/§4.3/§4.4). Calcula,
 * para cada evento, a proxima ocorrencia (ancora = {@code data_inicio}, PA#4), respeitando recorrencia
 * anual (rollover de ano) e a borda 29/02→28/02 em ano nao-bissexto. Deterministico: usa um
 * {@link Clock} injetado ({@code America/Sao_Paulo}) — testavel com {@code Clock.fixed(...)}.
 *
 * <p><b>Sem job/agendador</b> (§9): O(n) sobre a tabela {@code evento} (pequena) a cada leitura,
 * compativel com o cold start do host gratis. O {@link EventoRepository} entra como {@link Lazy} (mesmo
 * padrao dos demais services) para nao acoplar JPA aos smokes do M0.
 */
@Service
public class EventoAlertaService {

    /** Janela do alerta: aparece quando {@code diasAte <= 60} (inclusivo — PA#5/§4.3). */
    private static final int JANELA_DIAS = 60;

    /** Reforco: destaque quando {@code diasAte <= 30} (inclusivo). */
    private static final int REFORCO_DIAS = 30;

    private final EventoRepository eventoRepository;
    private final Clock clock;

    public EventoAlertaService(@Lazy EventoRepository eventoRepository, Clock clock) {
        this.eventoRepository = eventoRepository;
        this.clock = clock;
    }

    /**
     * Lista os eventos na janela (§3.3), ja filtrados ({@code aparece == true}) e ordenados por
     * {@code proximaOcorrencia ASC, nome ASC} (desempate estavel — §4.4). Cada item traz
     * {@code diasAte}, {@code destaqueReforcado}, {@code faixaUrgencia} e {@code emAndamento}.
     */
    @Transactional(readOnly = true)
    public List<EventoProximoResponse> proximos() {
        LocalDate hoje = LocalDate.now(clock);
        return eventoRepository.listarTodosParaAlerta().stream()
                .map(evento -> calcular(evento, hoje))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(EventoProximoResponse::proximaOcorrencia)
                        .thenComparing(EventoProximoResponse::nome))
                .toList();
    }

    /**
     * Aplica a formula §4.3 a um evento. Devolve {@link Optional#empty()} quando o evento NAO aparece
     * (fora da janela {@code diasAte > 60} ou ja passou {@code hoje > fim} sem recorrer).
     */
    private Optional<EventoProximoResponse> calcular(Evento evento, LocalDate hoje) {
        LocalDate dataInicio = evento.getDataInicio();
        LocalDate dataFim = evento.getDataFim();
        long duracao = (dataFim == null) ? 0 : ChronoUnit.DAYS.between(dataInicio, dataFim);

        LocalDate inicio;
        if (!evento.isRepeteTodoAno()) {
            inicio = dataInicio;
        } else {
            LocalDate candidato = comAno(dataInicio, hoje.getYear());
            LocalDate fimCandidato = candidato.plusDays(duracao);
            inicio = hoje.isAfter(fimCandidato)
                    ? comAno(dataInicio, hoje.getYear() + 1) // ocorrencia do ano ja passou → proximo ano
                    : candidato;
        }
        LocalDate fim = inicio.plusDays(duracao);
        long diasAte = ChronoUnit.DAYS.between(hoje, inicio); // negativo se a ocorrencia ja comecou

        boolean aparece = !hoje.isAfter(fim) && diasAte <= JANELA_DIAS;
        if (!aparece) {
            return Optional.empty();
        }
        boolean emAndamento = duracao > 0 && !inicio.isAfter(hoje) && !hoje.isAfter(fim);
        boolean destaqueReforcado = diasAte <= REFORCO_DIAS;
        int faixaUrgencia = faixaUrgencia(diasAte);

        return Optional.of(new EventoProximoResponse(
                evento.getId(), evento.getNome(), evento.getTipo(),
                inicio, (int) diasAte, destaqueReforcado, faixaUrgencia, emAndamento));
    }

    /**
     * Ajusta {@code base} para o {@code ano} alvo, tratando 29/02 em ano nao-bissexto → 28/02 (§4.4 —
     * "regra fixa, nao lancar"). {@code LocalDate.withYear} ja faz esse clamp, mas o caso e explicitado
     * para deixar a borda auditavel.
     */
    private LocalDate comAno(LocalDate base, int ano) {
        if (base.getMonthValue() == 2 && base.getDayOfMonth() == 29 && !Year.isLeap(ano)) {
            return LocalDate.of(ano, 2, 28);
        }
        return base.withYear(ano);
    }

    /**
     * Escala de urgencia 0..5 (§4.3, tabela — <b>autoritativa sobre a nota inline</b>, coerente com
     * CA-15): {@code <0} (em curso) e {@code 0} → 5; janelas de 7 dias descendo ate 30..29 → 1;
     * {@code 31..60} → 0. Formula: para {@code 0..30}, {@code clamp(5 - floor((diasAte-1)/7), 1, 5)} —
     * casa exatamente 7→5, 8→4, 14→4, 15→3, 21→3, 22→2, 28→2, 29→1, 30→1 e 0→5.
     */
    private int faixaUrgencia(long diasAte) {
        if (diasAte < 0) {
            return 5;
        }
        if (diasAte > REFORCO_DIAS) {
            return 0; // 31..60: aparece, sem reforco
        }
        long faixa = 5 - Math.floorDiv(diasAte - 1, 7);
        return (int) Math.max(1, Math.min(5, faixa));
    }
}
