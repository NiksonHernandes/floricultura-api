package com.floricultura.api.service;

import java.util.Locale;

/**
 * Variantes de imagem servidas por {@code GET /produtos/{id}/imagem?tamanho=} (SPEC-M5.2 §3.2/§3.5).
 * Cada tamanho carrega o rotulo de wire (query param / linha da tabela {@code produto_imagem_variante})
 * e o <b>lado maximo</b> do redimensionamento (sem upscale) no pipeline (T-M5.2-3):
 * <ul>
 *   <li>{@code thumb} ~200px — card da lista (menos bytes no mobile);</li>
 *   <li>{@code medio} ~800px — modal "Visualizar produto";</li>
 *   <li>{@code original} ≤1280px — vitrine/form (coluna canonica {@code produto.imagem}).</li>
 * </ul>
 * O {@code original} NAO vive em {@code produto_imagem_variante} (dirige {@code temImagem} pela coluna
 * canonica); thumb/medio sim. {@link #fromWire(String)} devolve {@code null} para valor invalido (o
 * controller traduz em {@code 400}); ausente/vazio → {@link #ORIGINAL} (default, backward-compat M3).
 */
public enum TamanhoImagem {

    THUMB("thumb", 200),
    MEDIO("medio", 800),
    ORIGINAL("original", 1280);

    private final String wire;
    private final int ladoMaximo;

    TamanhoImagem(String wire, int ladoMaximo) {
        this.wire = wire;
        this.ladoMaximo = ladoMaximo;
    }

    /** Rotulo de wire (query param e valor da coluna {@code tamanho} na tabela de variantes). */
    public String wire() {
        return wire;
    }

    /** Lado maximo do redimensionamento (sem upscale) desta variante — §3.5. */
    public int ladoMaximo() {
        return ladoMaximo;
    }

    /** {@code true} para as variantes derivadas (thumb/medio) que vivem em {@code produto_imagem_variante}. */
    public boolean isVariante() {
        return this != ORIGINAL;
    }

    /**
     * Resolve o query param {@code tamanho}: ausente/vazio → {@link #ORIGINAL} (default, backward-compat);
     * valor conhecido (case-insensitive) → a variante; valor invalido → {@code null} (o controller
     * devolve {@code 400 VALIDATION_ERROR} {@code field=tamanho} — CA-C4/C-R10).
     */
    public static TamanhoImagem fromWire(String valor) {
        if (valor == null || valor.isBlank()) {
            return ORIGINAL;
        }
        String alvo = valor.trim().toLowerCase(Locale.ROOT);
        for (TamanhoImagem t : values()) {
            if (t.wire.equals(alvo)) {
                return t;
            }
        }
        return null;
    }
}
