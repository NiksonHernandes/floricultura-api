package com.floricultura.api.web.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit puro da fabrica {@link ContatoFiltro#de} (SPEC-M6 §3.7, CA-24/CA-25): defaults
 * ({@code nome}/{@code asc}), normalizacao (trim + minusculas, {@code nome} em branco = sem filtro),
 * preservacao do <b>tri-estado</b> ({@code false} nao pode colapsar em {@code null} — armadilha 14) e
 * 400 por campo para {@code ordenarPor}/{@code direcao} fora do conjunto. Sem contexto Spring — logica
 * pura; o efeito HTTP esta no {@code ContatoFiltroApiTest}.
 */
class ContatoFiltroTest {

    @Test
    void semParametros_aplicaDefaultsNomeAsc() {
        ContatoFiltro filtro = ContatoFiltro.de(null, null, null, null, null);

        assertThat(filtro.nome()).isNull();
        assertThat(filtro.comTelefone()).isNull();
        assertThat(filtro.comEmail()).isNull();
        assertThat(filtro.ordenarPor()).isEqualTo("nome");
        assertThat(filtro.direcao()).isEqualTo("asc");
        assertThat(filtro.ascendente()).isTrue();
    }

    @Test
    void nomeEmBranco_viraSemFiltro_eNomePreenchidoEAparado() {
        assertThat(ContatoFiltro.de("   ", null, null, null, null).nome()).isNull();
        assertThat(ContatoFiltro.de("  Maria  ", null, null, null, null).nome()).isEqualTo("Maria");
    }

    @Test
    void ordenacaoEmMaiusculaOuComEspacos_eNormalizada() {
        ContatoFiltro filtro = ContatoFiltro.de(null, null, null, " EMAIL ", " DESC ");

        assertThat(filtro.ordenarPor()).isEqualTo("email");
        assertThat(filtro.direcao()).isEqualTo("desc");
        assertThat(filtro.ascendente()).isFalse();
    }

    @Test
    void triEstado_falsePermaneceDistintoDeAusente() {
        ContatoFiltro semFiltro = ContatoFiltro.de(null, null, null, null, null);
        ContatoFiltro semTelefone = ContatoFiltro.de(null, false, false, null, null);
        ContatoFiltro comTelefone = ContatoFiltro.de(null, true, true, null, null);

        assertThat(semFiltro.comTelefone()).isNull();
        assertThat(semFiltro.comEmail()).isNull();
        assertThat(semTelefone.comTelefone()).isFalse();
        assertThat(semTelefone.comEmail()).isFalse();
        assertThat(comTelefone.comTelefone()).isTrue();
        assertThat(comTelefone.comEmail()).isTrue();
    }

    @Test
    void camposDeOrdenacaoDoContrato_saoAceitos() {
        assertThatCode(() -> {
            ContatoFiltro.de(null, null, null, "nome", "asc");
            ContatoFiltro.de(null, null, null, "telefone", "desc");
            ContatoFiltro.de(null, null, null, "email", "asc");
        }).doesNotThrowAnyException();
    }

    @Test
    void ordenarPorForaDoConjunto_lancaComFieldOrdenarPor() {
        assertThatThrownBy(() -> ContatoFiltro.de(null, null, null, "observacoes", null))
                .isInstanceOf(ParametroPaginacaoInvalidoException.class)
                .satisfies(e -> {
                    var details = ((ParametroPaginacaoInvalidoException) e).getDetails();
                    assertThat(details).hasSize(1);
                    assertThat(details.get(0).field()).isEqualTo("ordenarPor");
                });
    }

    @Test
    void direcaoForaDoConjunto_lancaComFieldDirecao() {
        assertThatThrownBy(() -> ContatoFiltro.de(null, null, null, "email", "cima"))
                .isInstanceOf(ParametroPaginacaoInvalidoException.class)
                .satisfies(e -> {
                    var details = ((ParametroPaginacaoInvalidoException) e).getDetails();
                    assertThat(details).hasSize(1);
                    assertThat(details.get(0).field()).isEqualTo("direcao");
                });
    }

    @Test
    void ordenarPorEDirecaoInvalidos_acumulamUmItemPorCampo() {
        assertThatThrownBy(() -> ContatoFiltro.de(null, null, null, "cep", "cima"))
                .isInstanceOf(ParametroPaginacaoInvalidoException.class)
                .satisfies(e -> {
                    var details = ((ParametroPaginacaoInvalidoException) e).getDetails();
                    assertThat(details).hasSize(2);
                    assertThat(details.stream().map(d -> d.field()))
                            .containsExactly("ordenarPor", "direcao");
                });
    }
}
