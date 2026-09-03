package com.floricultura.api.web.dto;

import com.floricultura.api.domain.Evento;
import java.time.Instant;
import java.time.LocalDate;

/**
 * {@code data} das respostas de leitura/escrita de evento (SPEC-M4 §3.2): item de
 * {@code PaginaResponse.conteudo} no {@code GET /eventos} (lista), corpo do {@code GET /eventos/{id}}
 * (detalhe) e retorno do {@code POST}/{@code PUT}.
 *
 * <p>{@code dataUnica} e <b>computado</b> ({@code dataFim == null}) — a UX distingue "dia unico" de
 * "periodo de 1 dia" (AD-SQ-43/PA#3). {@code dataFim} e {@code descricao} podem ser {@code null}.
 *
 * @param id            id do evento
 * @param nome          nome de exibicao
 * @param tipo          codigo do tipo em ASCII (rotulo pt-BR e do front)
 * @param dataInicio    inicio do evento
 * @param dataFim       fim do periodo (pode ser {@code null} ⇒ data unica)
 * @param dataUnica     {@code true} quando {@code dataFim == null}
 * @param repeteTodoAno recorrencia anual
 * @param descricao     descricao livre (pode ser {@code null})
 * @param criadoEm      instante de criacao (UTC ISO-8601), do {@code DEFAULT now()} do banco
 * @param atualizadoEm  instante da ultima atualizacao (UTC ISO-8601)
 */
public record EventoResponse(
        Long id,
        String nome,
        String tipo,
        LocalDate dataInicio,
        LocalDate dataFim,
        boolean dataUnica,
        boolean repeteTodoAno,
        String descricao,
        Instant criadoEm,
        Instant atualizadoEm) {

    /** Mapeia a entidade para o response, computando {@code dataUnica} ({@code dataFim == null}). */
    public static EventoResponse de(Evento evento) {
        return new EventoResponse(
                evento.getId(),
                evento.getNome(),
                evento.getTipo(),
                evento.getDataInicio(),
                evento.getDataFim(),
                evento.getDataFim() == null,
                evento.isRepeteTodoAno(),
                evento.getDescricao(),
                evento.getCriadoEm(),
                evento.getAtualizadoEm());
    }
}
