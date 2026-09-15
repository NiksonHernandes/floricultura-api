package com.floricultura.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.floricultura.api.domain.Produto;
import com.floricultura.api.domain.ProdutoFactory;
import com.floricultura.api.repository.ProdutoRepository;
import com.floricultura.api.web.dto.AtualizarProdutoRequest;
import com.floricultura.api.web.dto.CriarProdutoRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit dos atributos <b>multivalorados</b> do produto (SPEC-M6 §3.3/§3.5, T-M6-02b) com repositorio e
 * servico de vinculo mockados — sem banco, isola as regras que o teste de integracao so observa pelo
 * JSON.
 *
 * <p>Prova: <b>CA-11</b> — dedup de {@code corIds}, validacao em <b>UMA</b> query (nunca
 * {@code findById} em laco) e <b>antes de qualquer escrita</b> (nem {@code save} nem o replace-set
 * rodam quando a cor nao existe); <b>CA-12</b> — mesma matriz para {@code necessidadeLuz}, com valor
 * fora do conjunto barrado no servico (o contrato exige {@code field:"necessidadeLuz"}, que o Bean
 * Validation no elemento da lista nao entrega); e a semantica de replace-set: {@code null} = nao toca o
 * conjunto · {@code []} = limpa.
 */
@ExtendWith(MockitoExtension.class)
class ProdutoMultivaloradosServiceTest {

    @Mock
    private ProdutoRepository produtoRepository;

    @Mock
    private ProdutoAtributosVinculoService atributosVinculoService;

    @InjectMocks
    private ProdutoService produtoService;

    // ---- CA-11: corIds — dedup, 1 query de validacao, erro antes de escrever ------------------

    @Test
    void criar_corIdsComRepetido_dedupEChamaOReplaceSetUmaVez() {
        prepararSave();
        when(produtoRepository.findCorIdsExistentes(anyList())).thenReturn(List.of(4L, 9L));

        produtoService.criar(criar(null, List.of(4L, 9L, 4L)));

        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(atributosVinculoService).substituirCores(anyLong(), captor.capture());
        assertThat(captor.getValue()).containsExactly(4L, 9L);
        // A validacao custa UMA query, independente de quantos ids vieram (§3.5/R10).
        verify(produtoRepository, times(1)).findCorIdsExistentes(anyList());
        verify(produtoRepository, never()).findById(777L);
    }

    @Test
    void criar_corIdInexistente_lancaAntesDeQualquerEscrita() {
        when(produtoRepository.findCorIdsExistentes(anyList())).thenReturn(List.of(4L));

        assertThatThrownBy(() -> produtoService.criar(criar(null, List.of(4L, 777L))))
                .isInstanceOf(AtributoDoProdutoInvalidoException.class)
                .hasMessage("Cor inexistente: 777.");

        // R10/CA-11: nada persiste — nem o produto, nem o DELETE do replace-set.
        verify(produtoRepository, never()).save(any(Produto.class));
        verify(atributosVinculoService, never()).substituirCores(anyLong(), anyList());
        verify(atributosVinculoService, never()).substituirLuzes(anyLong(), anyList());
    }

    @Test
    void corInexistente_apontaOCampoCorIdsComOIdNaMensagem() {
        AtributoDoProdutoInvalidoException ex =
                AtributoDoProdutoInvalidoException.corInexistente(777L);

        assertThat(ex.getDetails()).singleElement()
                .satisfies(d -> {
                    assertThat(d.field()).isEqualTo("corIds");
                    assertThat(d.message())
                            .isEqualTo("Cor inexistente: 777.");
                });
    }

    // ---- CA-12: necessidadeLuz — dedup e valor fora do conjunto -------------------------------

    @Test
    void criar_necessidadeLuzComRepetido_dedupEChamaOReplaceSet() {
        prepararSave();

        produtoService.criar(criar(List.of("SOMBRA", "MEIA_SOMBRA", "SOMBRA"), null));

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(atributosVinculoService).substituirLuzes(anyLong(), captor.capture());
        assertThat(captor.getValue())
                .containsExactly("SOMBRA", "MEIA_SOMBRA");
        verify(atributosVinculoService, never()).substituirCores(anyLong(), anyList());
    }

    @Test
    void criar_necessidadeLuzForaDoConjunto_lancaComFieldNecessidadeLuz() {
        assertThatThrownBy(() -> produtoService.criar(criar(List.of("LUA"), null)))
                .isInstanceOf(AtributoDoProdutoInvalidoException.class);

        AtributoDoProdutoInvalidoException ex =
                AtributoDoProdutoInvalidoException.luzInvalida("LUA");
        assertThat(ex.getDetails()).singleElement()
                .satisfies(d -> assertThat(d.field())
                        .isEqualTo("necessidadeLuz"));
        verify(produtoRepository, never()).save(any(Produto.class));
    }

    // ---- Semantica de replace-set: null nao toca, [] limpa (R9) -------------------------------

    @Test
    void atualizar_colecoesOmitidas_naoTocamOsConjuntos() {
        prepararUpdate();

        produtoService.atualizar(7L, atualizar(null, null));

        verify(atributosVinculoService, never()).substituirCores(anyLong(), anyList());
        verify(atributosVinculoService, never()).substituirLuzes(anyLong(), anyList());
        // Colecao ausente nem chega a consultar o catalogo (nenhuma query desperdicada).
        verify(produtoRepository, never()).findCorIdsExistentes(anyList());
    }

    @Test
    void atualizar_colecoesVazias_limpamOsDoisConjuntos() {
        prepararUpdate();

        produtoService.atualizar(7L, atualizar(List.of(), List.of()));

        verify(atributosVinculoService).substituirCores(7L, List.of());
        verify(atributosVinculoService).substituirLuzes(7L, List.of());
        // Lista vazia NAO vai ao banco: `IN ()` e erro de sintaxe no Postgres.
        verify(produtoRepository, never()).findCorIdsExistentes(anyList());
    }

    // ---- helpers ------------------------------------------------------------------------------

    private CriarProdutoRequest criar(List<String> luzes, List<Long> corIds) {
        return new CriarProdutoRequest("Costela-de-adão", null, "un", new BigDecimal("1"), null,
                null, null, null, null, null, luzes, corIds);
    }

    private AtualizarProdutoRequest atualizar(List<String> luzes, List<Long> corIds) {
        return new AtualizarProdutoRequest("Costela-de-adão", null, "un", new BigDecimal("1"), null,
                null, null, null, null, null, luzes, corIds);
    }

    private void prepararSave() {
        ArgumentCaptor<Produto> captor = ArgumentCaptor.forClass(Produto.class);
        when(produtoRepository.save(captor.capture())).thenAnswer(inv -> {
            Produto p = inv.getArgument(0);
            p.setId(10L);
            return p;
        });
        when(produtoRepository.findById(10L)).thenAnswer(inv -> Optional.of(captor.getValue()));
    }

    private void prepararUpdate() {
        Produto existente = ProdutoFactory.novo("Produto", null, "un", new BigDecimal("1"), null, null);
        existente.setId(7L);
        when(produtoRepository.findById(7L)).thenReturn(Optional.of(existente));
        when(produtoRepository.save(any(Produto.class))).thenAnswer(inv -> inv.getArgument(0));
    }
}
