package com.floricultura.api.repository;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import java.util.Locale;

/**
 * Ponto <b>unico</b> do filtro textual "contem, ignorando caixa" das listas ({@code ILIKE '%termo%'})
 * — usado por {@link ProdutoSpecs} e {@link ContatoSpecs}.
 *
 * <p><b>Por que existe:</b> ate o M6 a busca por nome era a derived query
 * {@code findByNomeContainingIgnoreCase}, e o Spring Data <b>escapa os wildcards</b> do termo por
 * padrao. Trocar a listagem por {@code Specification} com {@code cb.like} cru faria {@code "50%"}
 * virar o padrao {@code %50%%} e casar <b>qualquer</b> produto com "50" — regressao silenciosa. Aqui o
 * termo e escapado ({@code \}, {@code %}, {@code _}) e o {@code LIKE} declara o {@code ESCAPE},
 * preservando a semantica herdada nas <b>tres</b> listas (buscar "50%" nao pode significar coisas
 * diferentes em produtos, clientes e fornecedores).
 */
public final class FiltroTexto {

    /** Caractere de escape declarado no {@code LIKE ... ESCAPE '\'}. */
    private static final char ESCAPE = '\\';

    private FiltroTexto() {
        // Helper estatico — nao instanciavel.
    }

    /** {@code lower(campo) LIKE '%termo%' ESCAPE '\'} com o termo literal (ja aparado, nunca nulo). */
    public static Predicate contem(CriteriaBuilder cb, Expression<String> campo, String termo) {
        return cb.like(
                cb.lower(campo), "%" + escapar(termo.toLowerCase(Locale.ROOT)) + "%", ESCAPE);
    }

    // A barra vem primeiro: escapar %/_ antes dela escaparia a barra recem-inserida de novo.
    private static String escapar(String termo) {
        return termo.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
