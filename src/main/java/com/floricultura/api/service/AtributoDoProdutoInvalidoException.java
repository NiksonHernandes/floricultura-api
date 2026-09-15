package com.floricultura.api.service;

import com.floricultura.api.web.response.FieldErrorItem;
import java.util.List;

/**
 * Valor invalido num atributo <b>multivalorado</b> do produto (SPEC-M6 §3.3, CA-9/CA-11/CA-12).
 * Lancada <b>antes de qualquer escrita</b> — nada persiste e o conjunto anterior fica <b>intacto</b>
 * (nem o {@code DELETE} do replace-set roda). Handler local do {@code ProdutoController} a traduz para
 * {@code 400 VALIDATION_ERROR} com o {@code field} correspondente, na forma de
 * {@link EventoInexistenteException}.
 *
 * <p>Uma classe para os dois casos porque o que muda e so o par ({@code field}, mensagem). A luz e
 * validada aqui, e nao por {@code @Pattern} no elemento da lista, porque o Bean Validation reportaria
 * {@code field:"necessidadeLuz[0]"} e o contrato exige {@code "necessidadeLuz"}. Os CHECKs da V12
 * seguem como 2a linha de defesa — se atingidos, virariam 409 sem {@code field} (§12 #15a/AD-SQ-119).
 */
public class AtributoDoProdutoInvalidoException extends RuntimeException {

    private final transient List<FieldErrorItem> details;

    private AtributoDoProdutoInvalidoException(String campo, String mensagem) {
        super(mensagem);
        this.details = List.of(new FieldErrorItem(campo, mensagem));
    }

    /** Id de {@code corIds} ausente do catalogo (R10/CA-11) — mensagem com o id, como no §3.3. */
    public static AtributoDoProdutoInvalidoException corInexistente(Long corId) {
        return new AtributoDoProdutoInvalidoException("corIds", "Cor inexistente: " + corId + ".");
    }

    /** Valor de {@code necessidadeLuz} fora de {@code {SOL_PLENO, MEIA_SOMBRA, SOMBRA}} (CA-12). */
    public static AtributoDoProdutoInvalidoException luzInvalida(String valor) {
        return new AtributoDoProdutoInvalidoException("necessidadeLuz",
                "necessidadeLuz deve ser um de: SOL_PLENO, MEIA_SOMBRA, SOMBRA (recebido: "
                        + valor + ").");
    }

    public List<FieldErrorItem> getDetails() {
        return details;
    }
}
