package com.floricultura.api.domain;

/**
 * Fabrica de {@link Cor} (SPEC-M6 §3.2, CA-1) — mesma convencao de {@code FornecedorFactory}: vive no
 * pacote da entidade porque o construtor dela e {@code protected} (exigencia do JPA).
 *
 * <p>{@code criado_em}/{@code atualizado_em} <b>nao</b> sao setados aqui (vem do {@code DEFAULT now()}).
 * O {@code nome} ja deve chegar CANONICO e o {@code hex} ja normalizado — a fabrica nao normaliza nada,
 * para a regra continuar num ponto unico ({@link Cores}).
 */
public final class CorFactory {

    private CorFactory() {
        // Utilitaria — sem instancia.
    }

    /**
     * Cria uma cor transiente pronta para {@code save}.
     *
     * @param nomeCanonico nome ja canonizado por {@link Cores#canonizar(String)} (2..40 caracteres)
     * @param hex          amostra {@code #RRGGBB} ja em maiusculas, ou {@code null}
     */
    public static Cor novo(String nomeCanonico, String hex) {
        Cor cor = new Cor();
        cor.setNome(nomeCanonico);
        cor.setHex(hex);
        return cor;
    }
}
