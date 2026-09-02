package com.floricultura.api.web.dto;

import com.floricultura.api.web.response.FieldErrorItem;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Helper de validacao/normalizacao dos parametros de listagem paginada (SPEC-M2 §3.3 / AD-SQ-29),
 * reutilizavel por {@code /produtos}, {@code /produtos/{id}/movimentacoes} e {@code /usuarios}.
 *
 * <p>Regra do contrato: {@code pagina} inteiro {@code >= 0} (0-based, default {@code 0}); {@code
 * tamanho} inteiro {@code 1..100} (default {@code 20}). Valor {@code null} assume o default; valor
 * fora do range acumula um {@link FieldErrorItem} por campo e lanca
 * {@link ParametroPaginacaoInvalidoException} (→ {@code 400 VALIDATION_ERROR} no handler local do
 * controller). A ordenacao ({@link Sort}) e passada pelo chamador — nasce extensivel, mas o MVP fixa
 * {@code nome ASC} (produtos/usuarios) e {@code criadoEm DESC} (movimentacoes).
 */
public final class PaginacaoParams {

    /** Pagina default (0-based) quando o parametro nao vem (§3.3). */
    public static final int PAGINA_PADRAO = 0;

    /** Tamanho de pagina default quando o parametro nao vem (§3.3). */
    public static final int TAMANHO_PADRAO = 20;

    /** Tamanho maximo de pagina aceito (§3.3). */
    public static final int TAMANHO_MAX = 100;

    private PaginacaoParams() {
        // Helper estatico — nao instanciavel.
    }

    /**
     * Valida e normaliza {@code pagina}/{@code tamanho} e monta o {@link Pageable} com a ordenacao
     * dada. {@code null} → default; fora do range → {@link ParametroPaginacaoInvalidoException} com
     * um item por campo invalido.
     */
    public static Pageable paraPageable(Integer pagina, Integer tamanho, Sort sort) {
        int p = pagina == null ? PAGINA_PADRAO : pagina;
        int t = tamanho == null ? TAMANHO_PADRAO : tamanho;

        List<FieldErrorItem> erros = new ArrayList<>();
        if (p < 0) {
            erros.add(new FieldErrorItem("pagina", "Deve ser maior ou igual a 0."));
        }
        if (t < 1 || t > TAMANHO_MAX) {
            erros.add(new FieldErrorItem(
                    "tamanho", "Deve estar entre 1 e " + TAMANHO_MAX + "."));
        }
        if (!erros.isEmpty()) {
            throw new ParametroPaginacaoInvalidoException(erros);
        }
        return PageRequest.of(p, t, sort);
    }
}
