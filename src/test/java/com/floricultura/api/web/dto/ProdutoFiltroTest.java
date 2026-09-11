package com.floricultura.api.web.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit puro da fabrica {@link ProdutoFiltro#de} (SPEC-M6 §3.6, CA-19..CA-21): defaults
 * ({@code nome}/{@code asc}), normalizacao, dedup das dimensoes multivaloradas e o 400 por campo —
 * enum fora do conjunto, preco negativo, faixa invertida e {@code semPreco} combinado com a faixa.
 * Sem contexto Spring; o efeito HTTP/SQL esta no {@code ProdutoFiltroApiTest}.
 */
class ProdutoFiltroTest {

    private static ProdutoFiltro filtro(String estoque, String ordenarPor, String direcao) {
        return ProdutoFiltro.de(null, estoque, null, null, null, null, null, ordenarPor, direcao);
    }

    private static List<String> campos(Throwable e) {
        return ((ParametroPaginacaoInvalidoException) e).getDetails().stream()
                .map(d -> d.field()).toList();
    }

    @Test
    void semParametros_aplicaDefaultsNomeAsc_eNenhumaDimensaoAtiva() {
        ProdutoFiltro f = filtro(null, null, null);

        assertThat(f.nome()).isNull();
        assertThat(f.estoque()).isNull();
        assertThat(f.precoMin()).isNull();
        assertThat(f.precoMax()).isNull();
        assertThat(f.semPreco()).isFalse();
        assertThat(f.caracteristica()).isEmpty();
        assertThat(f.toxicidade()).isEmpty();
        assertThat(f.ordenarPor()).isEqualTo("nome");
        assertThat(f.direcao()).isEqualTo("asc");
        assertThat(f.ascendente()).isTrue();
    }

    @Test
    void nomeEmBrancoViraSemFiltro_eOrdenacaoEmMaiusculaENormalizada() {
        assertThat(ProdutoFiltro.de("  ", null, null, null, null, null, null, null, null).nome())
                .isNull();
        assertThat(ProdutoFiltro.de(" Rosa ", null, null, null, null, null, null, null, null).nome())
                .isEqualTo("Rosa");

        ProdutoFiltro f = filtro(null, " PRECO ", " DESC ");
        assertThat(f.ordenarPor()).isEqualTo("preco");
        assertThat(f.direcao()).isEqualTo("desc");
        assertThat(f.ascendente()).isFalse();
    }

    @Test
    void camposDoContrato_saoAceitos() {
        assertThatCode(() -> {
            filtro("SEM_ESTOQUE", "nome", "asc");
            filtro("BAIXO", "estoque", "desc");
            filtro("COM_ESTOQUE", "preco", "asc");
        }).doesNotThrowAnyException();
    }

    @Test
    void multivalorados_deduplicamEDescartamNulosEBrancos() {
        ProdutoFiltro f = ProdutoFiltro.de(null, null, null, null, null,
                Arrays.asList("MUDA", "MUDA", null, "  "), List.of("TOXICA"), null, null);

        assertThat(f.caracteristica()).containsExactly("MUDA");
        assertThat(f.toxicidade()).containsExactly("TOXICA");
    }

    @Test
    void estoqueForaDoEnum_lancaComFieldEstoque() {
        assertThatThrownBy(() -> filtro("QUALQUER", null, null))
                .isInstanceOf(ParametroPaginacaoInvalidoException.class)
                .satisfies(e -> assertThat(campos(e)).containsExactly("estoque"));
    }

    @Test
    void multivaloradoForaDoEnum_lancaUmItemPorCampo() {
        assertThatThrownBy(() -> ProdutoFiltro.de(null, null, null, null, null,
                List.of("BROTO"), List.of("talvez"), null, null))
                .isInstanceOf(ParametroPaginacaoInvalidoException.class)
                .satisfies(e ->
                        assertThat(campos(e)).containsExactly("caracteristica", "toxicidade"));
    }

    @Test
    void precoNegativoOuFaixaInvertida_lancaComFieldDoPreco() {
        assertThatThrownBy(() -> ProdutoFiltro.de(null, null, new BigDecimal("-1"), null, null,
                null, null, null, null))
                .satisfies(e -> assertThat(campos(e)).containsExactly("precoMin"));
        assertThatThrownBy(() -> ProdutoFiltro.de(null, null, new BigDecimal("60"),
                new BigDecimal("10"), null, null, null, null, null))
                .satisfies(e -> assertThat(campos(e)).containsExactly("precoMin"));
    }

    @Test
    void semPrecoComFaixa_lancaComFieldSemPreco_eSozinhoEAceito() {
        assertThatThrownBy(() -> ProdutoFiltro.de(null, null, null, new BigDecimal("50"), true,
                null, null, null, null))
                .satisfies(e -> assertThat(campos(e)).containsExactly("semPreco"));

        ProdutoFiltro f = ProdutoFiltro.de(null, null, null, null, true, null, null, null, null);
        assertThat(f.semPreco()).isTrue();
        // semPreco=false e "sem filtro", nao "so com preco" (§3.6: o unico valor util e true).
        assertThat(ProdutoFiltro.de(null, null, null, null, false, null, null, null, null)
                .semPreco()).isFalse();
    }

    @Test
    void ordenacaoForaDoContrato_acumulaUmItemPorCampo() {
        assertThatThrownBy(() -> filtro(null, "cor", "cima"))
                .isInstanceOf(ParametroPaginacaoInvalidoException.class)
                .satisfies(e -> assertThat(campos(e)).containsExactly("ordenarPor", "direcao"));
    }
}
