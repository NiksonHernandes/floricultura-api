package com.floricultura.api.service;

import com.floricultura.api.web.response.FieldErrorItem;
import java.util.List;

/**
 * Um id de {@code eventoIds} do payload de produto nao existe (SPEC-M4 §3.4, CA-11). Lancada pelo
 * {@code ProdutoService.criar/atualizar} <b>antes de gravar qualquer vinculo</b> (nada e persistido) e
 * traduzida por handler local do {@code ProdutoController} para {@code 400 VALIDATION_ERROR} com
 * {@code details} apontando o campo {@code eventoIds} — mesmo padrao de {@code EstoqueInsuficienteException}.
 */
public class EventoInexistenteException extends RuntimeException {

    private final transient List<FieldErrorItem> details;

    public EventoInexistenteException(Long eventoId) {
        super("Evento inexistente: " + eventoId + ".");
        this.details = List.of(new FieldErrorItem(
                "eventoIds", "Evento inexistente: " + eventoId + "."));
    }

    public List<FieldErrorItem> getDetails() {
        return details;
    }
}
