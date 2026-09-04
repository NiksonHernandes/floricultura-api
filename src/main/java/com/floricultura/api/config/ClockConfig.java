package com.floricultura.api.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fonte de tempo do alerta on-read do M4 (T-M4-7, SPEC-M4 §4.3). Expoe um {@link Clock} em
 * {@code America/Sao_Paulo} como bean <b>injetavel</b>: o {@code EventoAlertaService} calcula
 * {@code hoje = LocalDate.now(clock)} a partir dele, tornando a matematica de datas (fuso/bissexto/
 * recorrencia) <b>deterministica</b> e verificavel em JUnit com {@code Clock.fixed(...)} — imune ao
 * relogio/fuso do cliente. Fonte unica; o front so renderiza.
 */
@Configuration
public class ClockConfig {

    /** Fuso de negocio da floricultura (sem DST desde 2019 — SPEC-M4 §9). */
    public static final ZoneId ZONA_SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    @Bean
    public Clock clockSaoPaulo() {
        return Clock.system(ZONA_SAO_PAULO);
    }
}
