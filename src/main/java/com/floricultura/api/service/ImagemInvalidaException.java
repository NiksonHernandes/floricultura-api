package com.floricultura.api.service;

/**
 * Upload de imagem reprovado na validacao (M3/T-M3-3, CA-4/CA-5) — arquivo ausente/vazio, acima do
 * limite, content-type fora da whitelist ou conteudo que nao casa os <i>magic bytes</i> do tipo
 * declarado (anti-spoofing, SPEC-M3 §3.3). Traduzida por handler local no {@code ProdutoImagemController}
 * para {@code 400 VALIDATION_ERROR} com {@code details:[{field:"arquivo", message:<mensagem>}]}.
 *
 * <p>A mensagem e voltada ao usuario (exata do §3.3) e <b>nunca</b> vaza bytes/conteudo do arquivo.
 */
public class ImagemInvalidaException extends RuntimeException {

    /** Campo do {@code details} do envelope (§3.3): sempre {@code "arquivo"} (a parte multipart). */
    public static final String CAMPO = "arquivo";

    public ImagemInvalidaException(String message) {
        super(message);
    }
}
