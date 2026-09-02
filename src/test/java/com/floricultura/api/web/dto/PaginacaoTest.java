package com.floricultura.api.web.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Unit puro do helper de paginacao {@link PaginacaoParams} (SPEC-M2 §3.3 / AD-SQ-29, CA-3): defaults
 * {@code 0/20}, faixa valida vira {@link Pageable} com a ordenacao dada, e {@code pagina < 0} /
 * {@code tamanho} fora de {@code 1..100} lancam {@link ParametroPaginacaoInvalidoException} (→ 400
 * no handler local, provado no {@code ProdutoApiTest}). Sem contexto Spring — logica pura.
 */
class PaginacaoTest {

    private static final Sort NOME_ASC = Sort.by(Sort.Direction.ASC, "nome");

    @Test
    void semParametros_aplicaDefaults0e20() {
        Pageable pageable = PaginacaoParams.paraPageable(null, null, NOME_ASC);

        assertThat(pageable.getPageNumber()).isEqualTo(0);
        assertThat(pageable.getPageSize()).isEqualTo(20);
        assertThat(pageable.getSort()).isEqualTo(NOME_ASC);
    }

    @Test
    void comParametrosValidos_montaPageableCorreto() {
        Pageable pageable = PaginacaoParams.paraPageable(1, 5, NOME_ASC);

        assertThat(pageable.getPageNumber()).isEqualTo(1);
        assertThat(pageable.getPageSize()).isEqualTo(5);
        assertThat(pageable.getSort()).isEqualTo(NOME_ASC);
    }

    @Test
    void tamanhoLimiteInferiorESuperior_saoAceitos() {
        assertThat(PaginacaoParams.paraPageable(0, 1, NOME_ASC).getPageSize()).isEqualTo(1);
        assertThat(PaginacaoParams.paraPageable(0, 100, NOME_ASC).getPageSize()).isEqualTo(100);
    }

    @Test
    void paginaNegativa_lancaExcecaoComCampoPagina() {
        assertThatThrownBy(() -> PaginacaoParams.paraPageable(-1, 20, NOME_ASC))
                .isInstanceOf(ParametroPaginacaoInvalidoException.class)
                .satisfies(e -> {
                    var details = ((ParametroPaginacaoInvalidoException) e).getDetails();
                    assertThat(details).hasSize(1);
                    assertThat(details.get(0).field()).isEqualTo("pagina");
                });
    }

    @Test
    void tamanhoZero_lancaExcecao() {
        assertThatThrownBy(() -> PaginacaoParams.paraPageable(0, 0, NOME_ASC))
                .isInstanceOf(ParametroPaginacaoInvalidoException.class);
    }

    @Test
    void tamanhoAcimaDoMaximo_lancaExcecao() {
        assertThatThrownBy(() -> PaginacaoParams.paraPageable(0, 101, NOME_ASC))
                .isInstanceOf(ParametroPaginacaoInvalidoException.class);
    }

    @Test
    void paginaEtamanhoInvalidos_acumulamUmItemPorCampo() {
        assertThatThrownBy(() -> PaginacaoParams.paraPageable(-1, 0, NOME_ASC))
                .isInstanceOf(ParametroPaginacaoInvalidoException.class)
                .satisfies(e -> assertThat(
                        ((ParametroPaginacaoInvalidoException) e).getDetails()).hasSize(2));
    }
}
