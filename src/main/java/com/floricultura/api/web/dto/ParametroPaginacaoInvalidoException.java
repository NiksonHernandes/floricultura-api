package com.floricultura.api.web.dto;

import com.floricultura.api.web.response.FieldErrorItem;
import java.util.List;

/**
 * Parametros de paginacao fora do contrato §3.3 (AD-SQ-29): {@code pagina < 0} ou {@code tamanho}
 * fora de {@code 1..100}. Lancada por {@link PaginacaoParams#paraPageable} e traduzida para
 * {@code 400 VALIDATION_ERROR} (com os {@code details} por campo) por handler local do controller
 * consumidor — mesmo padrao dos handlers locais do {@code UsuarioController} do M1.
 *
 * <p>Carrega a lista de campos invalidos para que o handler a repasse em {@code error.details},
 * sem que o controller reconstrua a razao.
 */
public class ParametroPaginacaoInvalidoException extends RuntimeException {

    private final transient List<FieldErrorItem> details;

    public ParametroPaginacaoInvalidoException(List<FieldErrorItem> details) {
        this("Parametros de paginacao invalidos.", details);
    }

    /**
     * Variante com mensagem propria (SPEC-M6 §3.7): os mesmos {@code details}/handler servem aos
     * parametros de <b>listagem</b> ({@code ordenarPor}/{@code direcao}), que nao sao de paginacao.
     */
    public ParametroPaginacaoInvalidoException(String message, List<FieldErrorItem> details) {
        super(message);
        this.details = List.copyOf(details);
    }

    public List<FieldErrorItem> getDetails() {
        return details;
    }
}
