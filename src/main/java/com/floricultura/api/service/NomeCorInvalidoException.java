package com.floricultura.api.service;

import com.floricultura.api.web.response.FieldErrorItem;
import java.util.List;

/**
 * Nome de cor invalido <b>apos a canonizacao</b> (SPEC-M6 §3.2.1, CA-39.4/39.5) → {@code 400
 * VALIDATION_ERROR} com {@code field:"nome"} (handler local do {@code CorController} — T-M6-01b-2).
 *
 * <p>Vem do SERVICO, nao do Bean Validation: a regra autoritativa {@code 2..40} roda sobre o CANONICO
 * (o cru {@code " a "} tem 3 caracteres e vira {@code A} com 1). Mesmo padrao de
 * {@link ContraparteInvalidaException}.
 */
public class NomeCorInvalidoException extends RuntimeException {

    private final transient List<FieldErrorItem> details;

    public NomeCorInvalidoException(String message) {
        super(message);
        this.details = List.of(new FieldErrorItem("nome", message));
    }

    public List<FieldErrorItem> getDetails() {
        return details;
    }
}
