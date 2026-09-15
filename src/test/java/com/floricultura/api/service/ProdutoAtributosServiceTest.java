package com.floricultura.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.floricultura.api.domain.Produto;
import com.floricultura.api.domain.ProdutoFactory;
import com.floricultura.api.repository.ProdutoRepository;
import com.floricultura.api.web.dto.AtualizarProdutoRequest;
import com.floricultura.api.web.dto.CriarProdutoRequest;
import com.floricultura.api.web.dto.ProdutoResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit da <b>regra cruzada da altura</b> (SPEC-M6 §4.3/R13, CA-10) e da gravacao dos atributos
 * botanicos escalares (CA-8), com {@link ProdutoRepository} mockado — sem banco.
 *
 * <p>O ponto do arquivo e provar que a recusa vem do <b>servico</b>, <b>antes</b> de qualquer escrita:
 * se ela viesse do {@code ck_produto_altura_exige_porte}, o erro chegaria como
 * {@code DataIntegrityViolationException} e o endpoint devolveria <b>500</b> em vez do <b>400
 * {@code field:"alturaCm"}</b> do contrato (armadilha §12 #15a). Por isso cada caso negativo assere
 * tambem {@code never()} sobre o {@code save}.
 */
@ExtendWith(MockitoExtension.class)
class ProdutoAtributosServiceTest {

    @Mock
    private ProdutoRepository produtoRepository;

    @InjectMocks
    private ProdutoService produtoService;

    // ---- CA-10: matriz altura x caracteristica (criacao) --------------------------------------

    /**
     * Combinacoes recusadas no POST. A linha {@code (vazio, 30)} e o caso da <b>AD-SQ-89</b>: altura
     * SEM caracteristica — exatamente o que a forma ingenua do CHECK deixava passar.
     */
    @ParameterizedTest(name = "caracteristica={0} + alturaCm={1} -> 400 field=alturaCm")
    @CsvSource({"MUDA,30", ",30", ",1", "MUDA,10000"})
    void criar_alturaSemPorteJovemOuAdulta_recusaAntesDeGravar(String caracteristica, Integer altura) {
        CriarProdutoRequest req = requisicaoCriar(caracteristica, altura, null);

        assertThatThrownBy(() -> produtoService.criar(req))
                .isInstanceOf(AlturaSemPorteException.class)
                .hasMessage(AlturaSemPorteException.MENSAGEM);
        verify(produtoRepository, never()).save(any()); // nada persiste (CA-10)
    }

    @Test
    void alturaSemPorte_apontaOCampoAlturaCm() {
        AlturaSemPorteException ex = new AlturaSemPorteException();

        assertThat(ex.getDetails()).singleElement()
                .satisfies(item -> {
                    assertThat(item.field()).isEqualTo("alturaCm");
                    assertThat(item.message()).isEqualTo(AlturaSemPorteException.MENSAGEM);
                });
    }

    @ParameterizedTest(name = "caracteristica={0} + alturaCm={1} -> aceito")
    @CsvSource({"JOVEM,1", "ADULTA,120", "ADULTA,10000", "MUDA,", ",", "JOVEM,"})
    void criar_combinacoesValidas_gravamOsEscalares(String caracteristica, Integer altura) {
        CriarProdutoRequest req = requisicaoCriar(caracteristica, altura, "TOXICA");
        ArgumentCaptor<Produto> captor = capturarSave(10L);

        assertThatCode(() -> produtoService.criar(req)).doesNotThrowAnyException();

        Produto salvo = captor.getValue();
        assertThat(salvo.getCaracteristica()).isEqualTo(caracteristica);
        assertThat(salvo.getAlturaCm()).isEqualTo(altura);
        assertThat(salvo.getToxicidade()).isEqualTo("TOXICA");
    }

    // ---- CA-8: os 3 escalares sao opcionais ---------------------------------------------------

    @Test
    void criar_semAtributos_nasceComOsTresEscalaresNulos() {
        // Construtor de compatibilidade de 6 args (M2) — prova que o contrato antigo segue valido.
        CriarProdutoRequest req = new CriarProdutoRequest(
                "Samambaia", null, "un", new BigDecimal("2"), null, null);
        ArgumentCaptor<Produto> captor = capturarSave(11L);

        ProdutoResponse resp = produtoService.criar(req);

        assertThat(captor.getValue().getCaracteristica()).isNull();
        assertThat(captor.getValue().getAlturaCm()).isNull();
        assertThat(captor.getValue().getToxicidade()).isNull();
        assertThat(resp.caracteristica()).isNull();
        assertThat(resp.alturaCm()).isNull();
        assertThat(resp.toxicidade()).isNull(); // tri-estado: ausente = "nao informado" (R8/P2)
    }

    // ---- CA-10 no PUT: a regra roda sobre o ESTADO RESULTANTE do update ------------------------

    /**
     * O caso que o SDD destaca: a linha ja tem {@code JOVEM + 80} e o operador muda so a caracteristica
     * para {@code MUDA} sem limpar a altura. O estado resultante seria {@code MUDA + 80} → 400, e o
     * produto <b>nao</b> e sequer carregado para escrita.
     */
    @Test
    void atualizar_trocaParaMudaSemLimparAltura_recusaAntesDeGravar() {
        AtualizarProdutoRequest req = requisicaoAtualizar("MUDA", 80, null);

        assertThatThrownBy(() -> produtoService.atualizar(7L, req))
                .isInstanceOf(AlturaSemPorteException.class);
        verify(produtoRepository, never()).save(any());
    }

    @Test
    void atualizar_trocaParaMudaLimpandoAltura_gravaAlturaNula() {
        Produto existente = produtoJovemComAltura();
        when(produtoRepository.findById(7L)).thenReturn(Optional.of(existente));
        when(produtoRepository.save(any(Produto.class))).thenAnswer(inv -> inv.getArgument(0));

        ProdutoResponse resp = produtoService.atualizar(7L, requisicaoAtualizar("MUDA", null, null));

        assertThat(existente.getCaracteristica()).isEqualTo("MUDA");
        assertThat(existente.getAlturaCm()).isNull();
        assertThat(resp.alturaCm()).isNull();
    }

    /** Escalar: o PUT grava o que veio, inclusive {@code null} — limpa os 3 (§3.3). */
    @Test
    void atualizar_comEscalaresNulos_limpaOsAtributosExistentes() {
        Produto existente = produtoJovemComAltura();
        existente.setToxicidade("TOXICA");
        when(produtoRepository.findById(7L)).thenReturn(Optional.of(existente));
        when(produtoRepository.save(any(Produto.class))).thenAnswer(inv -> inv.getArgument(0));

        produtoService.atualizar(7L, requisicaoAtualizar(null, null, null));

        assertThat(existente.getCaracteristica()).isNull();
        assertThat(existente.getAlturaCm()).isNull();
        assertThat(existente.getToxicidade()).isNull();
    }

    // ---- helpers ------------------------------------------------------------------------------

    private ArgumentCaptor<Produto> capturarSave(Long id) {
        ArgumentCaptor<Produto> captor = ArgumentCaptor.forClass(Produto.class);
        when(produtoRepository.save(captor.capture())).thenAnswer(inv -> {
            Produto p = inv.getArgument(0);
            p.setId(id);
            return p;
        });
        when(produtoRepository.findById(id)).thenAnswer(inv -> Optional.of(captor.getValue()));
        return captor;
    }

    private static CriarProdutoRequest requisicaoCriar(
            String caracteristica, Integer alturaCm, String toxicidade) {
        return new CriarProdutoRequest("Costela-de-adao", null, "un", new BigDecimal("1"),
                null, null, null, caracteristica, alturaCm, toxicidade);
    }

    private static AtualizarProdutoRequest requisicaoAtualizar(
            String caracteristica, Integer alturaCm, String toxicidade) {
        return new AtualizarProdutoRequest("Costela-de-adao", null, "un", new BigDecimal("1"),
                null, null, null, caracteristica, alturaCm, toxicidade);
    }

    private static Produto produtoJovemComAltura() {
        Produto p = ProdutoFactory.novo("Costela-de-adao", null, "un", new BigDecimal("1"), null, null);
        p.setId(7L);
        p.setCaracteristica("JOVEM");
        p.setAlturaCm(80);
        p.setAtualizadoEm(Instant.parse("2026-09-01T00:00:00Z"));
        return p;
    }
}
