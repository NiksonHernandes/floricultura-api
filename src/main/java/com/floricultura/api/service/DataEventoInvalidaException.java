package com.floricultura.api.service;

import com.floricultura.api.web.response.FieldErrorItem;
import java.util.List;

/**
 * Regra cruzada de datas do evento violada (SPEC-M4 §3.2/§4.1, CA-2): {@code dataFim} presente e menor
 * que {@code dataInicio}. Lancada pelo {@code EventoService} <b>antes do save</b> e traduzida por
 * handler local do {@code EventoController} para {@code 400 VALIDATION_ERROR} com {@code details}
 * apontando o campo {@code dataFim} — mesmo padrao de {@code EstoqueInsuficienteException}.
 */
public class DataEventoInvalidaException extends RuntimeException {

    private final transient List<FieldErrorItem> details;

    public DataEventoInvalidaException() {
        super("dataFim deve ser maior ou igual a dataInicio.");
        this.details = List.of(new FieldErrorItem(
                "dataFim", "dataFim deve ser maior ou igual a dataInicio."));
    }

    public List<FieldErrorItem> getDetails() {
        return details;
    }
}
