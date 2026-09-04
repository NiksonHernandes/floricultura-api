package com.floricultura.api.web.dto;

import java.time.LocalDate;

/**
 * Item de {@code GET /api/v1/eventos/proximos} (SPEC-M4 §3.3, CA-13..CA-17) — resultado do calculo
 * on-read de "proximos eventos". Todos os campos derivam da formula deterministica do
 * {@code EventoAlertaService} (§4.3/§4.4) sobre um {@code Clock} em {@code America/Sao_Paulo}.
 *
 * @param id                id do evento
 * @param nome              nome de exibicao
 * @param tipo              codigo do tipo em ASCII (rotulo pt-BR e do front)
 * @param proximaOcorrencia data de inicio da proxima ocorrencia (ancora do alerta = {@code data_inicio},
 *                          PA#4; anual recalcula para o ano-alvo, 29/02→28/02 quando nao bissexto)
 * @param diasAte           dias de {@code hoje} ate {@code proximaOcorrencia} (NEGATIVO se em curso)
 * @param destaqueReforcado {@code true} quando {@code diasAte <= 30} (inclui em-andamento)
 * @param faixaUrgencia     escala 0..5 (0 = na janela sem reforco; 5 = &le;7 dias / em curso)
 * @param emAndamento       {@code true} quando o periodo esta em curso ({@code inicio <= hoje <= fim})
 */
public record EventoProximoResponse(
        Long id,
        String nome,
        String tipo,
        LocalDate proximaOcorrencia,
        int diasAte,
        boolean destaqueReforcado,
        int faixaUrgencia,
        boolean emAndamento) {
}
