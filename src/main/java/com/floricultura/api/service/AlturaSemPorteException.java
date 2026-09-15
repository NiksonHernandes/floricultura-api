package com.floricultura.api.service;

import com.floricultura.api.web.response.FieldErrorItem;
import java.util.List;

/**
 * Regra cruzada da altura da planta (SPEC-M6 §4.3/R13, CA-10): {@code alturaCm} so e aceita quando
 * {@code caracteristica ∈ {JOVEM, ADULTA}} — logo {@code MUDA + altura} e, sobretudo,
 * <b>altura SEM caracteristica</b> ({@code null}) sao recusadas.
 *
 * <p><b>Por que existe uma validacao de servico se o banco ja tem o CHECK</b> (armadilha §12 #15a): o
 * {@code ck_produto_altura_exige_porte} e defesa em profundidade e, quando atingido, chega ao Spring
 * como {@code DataIntegrityViolationException} → <b>409</b> generico, com {@code details} vazio
 * (AD-SQ-119 — medido por mutacao; o "500" antes citado nao acontece). O contrato manda <b>400
 * VALIDATION_ERROR</b> com {@code field:"alturaCm"}, entao o {@code ProdutoService} avalia a regra
 * sobre o <b>estado RESULTANTE</b> do POST/PUT e <b>antes</b> de qualquer escrita (nada persiste).
 * Traduzida por handler local do {@code ProdutoController}, no mesmo padrao de
 * {@link EventoInexistenteException}.
 */
public class AlturaSemPorteException extends RuntimeException {

    /** Mensagem exata do §3.3 — o front a exibe sob o campo de altura. */
    public static final String MENSAGEM =
            "A altura só pode ser informada quando a característica for JOVEM ou ADULTA.";

    private final transient List<FieldErrorItem> details;

    public AlturaSemPorteException() {
        super(MENSAGEM);
        this.details = List.of(new FieldErrorItem("alturaCm", MENSAGEM));
    }

    public List<FieldErrorItem> getDetails() {
        return details;
    }
}
