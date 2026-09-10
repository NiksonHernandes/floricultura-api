package com.floricultura.api.service;

/**
 * Query param {@code tamanho} fora do enum ({@code thumb|medio|original}) em
 * {@code GET /produtos/{id}/imagem} (SPEC-M5.2 §3.2, CA-C4/C-R10). Traduzida por handler local no
 * {@code ProdutoImagemController} para {@code 400 VALIDATION_ERROR} com
 * {@code details:[{field:"tamanho", message:<mensagem>}]}. Ausente/vazio NAO lanca (→ {@code original}).
 */
public class TamanhoImagemInvalidoException extends RuntimeException {

    /** Campo do {@code details} do envelope: o query param {@code tamanho}. */
    public static final String CAMPO = "tamanho";

    public TamanhoImagemInvalidoException() {
        super("Tamanho de imagem invalido (use thumb, medio ou original).");
    }
}
