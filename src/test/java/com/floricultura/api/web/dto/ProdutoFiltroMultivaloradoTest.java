package com.floricultura.api.web.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit puro das dimensoes multivaloradas <b>por juncao</b> da fabrica {@link ProdutoFiltro#de}
 * (T-M6-05b, SPEC-M6 §3.6 — CA-17/CA-18): normalizacao de {@code corIds}/{@code eventoIds} (dedup,
 * nulos fora, <b>sem</b> validar existencia) e o {@code 400 field:"luz"} do enum de necessidade de
 * luz. O efeito em SQL (o {@code EXISTS}, o OU interno e o {@code totalElementos} sob N:N) esta no
 * {@code ProdutoFiltroMultivaloradoApiTest}.
 */
class ProdutoFiltroMultivaloradoTest {

    private static ProdutoFiltro filtro(List<Long> corIds, List<String> luz, List<Long> eventoIds) {
        return ProdutoFiltro.de(null, null, null, null, null, null, null, null, null,
                corIds, luz, eventoIds);
    }

    private static List<String> campos(Throwable e) {
        return ((ParametroPaginacaoInvalidoException) e).getDetails().stream()
                .map(d -> d.field()).toList();
    }

    @Test
    void ausentes_ouVazios_naoAtivamDimensaoAlguma() {
        ProdutoFiltro nulos = filtro(null, null, null);

        assertThat(nulos.corIds()).isEmpty();
        assertThat(nulos.luz()).isEmpty();
        assertThat(nulos.eventoIds()).isEmpty();

        ProdutoFiltro vazios = filtro(List.of(), List.of(), List.of());

        assertThat(vazios.corIds()).isEmpty();
        assertThat(vazios.luz()).isEmpty();
        assertThat(vazios.eventoIds()).isEmpty();
    }

    @Test
    void compatDeNoveArgumentos_continuaValido_eDeixaAsJuncoesSemFiltro() {
        ProdutoFiltro f = ProdutoFiltro.de(null, "BAIXO", null, null, null, null, null, null, null);

        assertThat(f.estoque()).isEqualTo("BAIXO");
        assertThat(f.corIds()).isEmpty();
        assertThat(f.luz()).isEmpty();
        assertThat(f.eventoIds()).isEmpty();
    }

    @Test
    void ids_descartamNulos_eDeduplicam_semValidarExistencia() {
        ProdutoFiltro f = filtro(Arrays.asList(3L, null, 7L, 3L), null,
                Arrays.asList(null, 9L, 9L));

        assertThat(f.corIds()).containsExactly(3L, 7L);
        assertThat(f.eventoIds()).containsExactly(9L);
        // Id inexistente NAO e erro (§3.6) — so nao casa no EXISTS.
        assertThatCode(() -> filtro(List.of(999_999L), null, List.of(888_888L)))
                .doesNotThrowAnyException();
    }

    @Test
    void luz_aparaEDeduplica_osTresValoresDoContrato() {
        ProdutoFiltro f = filtro(null, Arrays.asList(" SOMBRA ", null, "  ", "SOMBRA",
                "SOL_PLENO", "MEIA_SOMBRA"), null);

        assertThat(f.luz()).containsExactly("SOMBRA", "SOL_PLENO", "MEIA_SOMBRA");
    }

    @Test
    void luz_foraDoEnum_acumulaUmUnico400NoCampoLuz() {
        assertThatThrownBy(() -> filtro(null, List.of("PENUMBRA", "ESCURIDAO"), null))
                .isInstanceOf(ParametroPaginacaoInvalidoException.class)
                .satisfies(e -> assertThat(campos(e)).containsExactly("luz"));
        // Minuscula nao e normalizada (mesmo criterio de caracteristica/toxicidade na 05a).
        assertThatThrownBy(() -> filtro(null, List.of("sombra"), null))
                .satisfies(e -> assertThat(campos(e)).containsExactly("luz"));
    }

    @Test
    void luzInvalida_somaAosDemaisCampos_semMascararOsOutrosErros() {
        assertThatThrownBy(() -> ProdutoFiltro.de(null, "QUALQUER", null, null, null, null, null,
                "cor", null, null, List.of("PENUMBRA"), null))
                .satisfies(e -> assertThat(campos(e))
                        .containsExactlyInAnyOrder("estoque", "luz", "ordenarPor"));
    }
}
