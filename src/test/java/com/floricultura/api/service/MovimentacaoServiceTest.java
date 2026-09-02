package com.floricultura.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.floricultura.api.domain.Produto;
import com.floricultura.api.domain.ProdutoFactory;
import com.floricultura.api.repository.MovimentacaoRepository;
import com.floricultura.api.repository.ProdutoRepository;
import com.floricultura.api.web.dto.MovimentacaoRequest;
import com.floricultura.api.web.dto.MovimentacaoResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit da regra de movimentacao (T-M2-4, CA-10/CA-11/CA-12) com repositorios mockados — isola a
 * semantica ENTRADA/SAIDA/AJUSTE, o bloqueio de saida com a mensagem exata e o "nao grava nada" no
 * bloqueio, <b>sem</b> banco/lock real (a atomicidade sob concorrencia e provada no
 * {@code MovimentacaoApiTest} com Testcontainers). O {@code produtoId} do produto mock e {@code null}
 * (nao persistido) — irrelevante para estas asercoes, que olham estoque resultante e efeitos colaterais.
 */
@ExtendWith(MockitoExtension.class)
class MovimentacaoServiceTest {

    private static final Long PRODUTO_ID = 1L;
    private static final Long USUARIO_ID = 3L;

    @Mock
    private ProdutoRepository produtoRepository;

    @Mock
    private MovimentacaoRepository movimentacaoRepository;

    @InjectMocks
    private MovimentacaoService service;

    private Produto produtoComEstoque(String estoque) {
        Produto produto = ProdutoFactory.novo(
                "Rosa Vermelha", null, "un", new BigDecimal("10"), null, null);
        produto.setEstoqueAtual(new BigDecimal(estoque));
        return produto;
    }

    private MovimentacaoRequest req(String tipo, String quantidade) {
        return new MovimentacaoRequest(tipo, new BigDecimal(quantidade), "motivo teste");
    }

    /** Stubs para os caminhos que chegam a gravar (ENTRADA/SAIDA valida/AJUSTE). */
    private void stubGravacaoOk(Produto produto) {
        when(produtoRepository.findByIdForUpdate(PRODUTO_ID)).thenReturn(Optional.of(produto));
        when(movimentacaoRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(movimentacaoRepository.findCriadoEmById(any())).thenReturn(Instant.now());
    }

    // ---- CA-10: ENTRADA soma -----------------------------------------------------------------

    @Test
    void entrada_somaAoEstoque() {
        Produto produto = produtoComEstoque("0");
        stubGravacaoOk(produto);

        MovimentacaoResponse resp = service.movimentar(PRODUTO_ID, req("ENTRADA", "30"), USUARIO_ID);

        assertEquals(0, resp.quantidadeResultante().compareTo(new BigDecimal("30")));
        assertEquals("ENTRADA", resp.tipo());
        assertEquals(USUARIO_ID, resp.usuarioId());
        assertEquals(0, produto.getEstoqueAtual().compareTo(new BigDecimal("30")));
        verify(produtoRepository).save(produto);
        verify(movimentacaoRepository).saveAndFlush(any());
    }

    // ---- CA-11: SAIDA subtrai / bloqueio sem gravar ------------------------------------------

    @Test
    void saida_subtraiDoEstoque() {
        Produto produto = produtoComEstoque("30");
        stubGravacaoOk(produto);

        MovimentacaoResponse resp = service.movimentar(PRODUTO_ID, req("SAIDA", "10"), USUARIO_ID);

        assertEquals(0, resp.quantidadeResultante().compareTo(new BigDecimal("20")));
        assertEquals(0, produto.getEstoqueAtual().compareTo(new BigDecimal("20")));
        verify(movimentacaoRepository).saveAndFlush(any());
    }

    @Test
    void saida_maiorQueEstoque_lancaComMensagemExataENaoGravaNada() {
        Produto produto = produtoComEstoque("20");
        when(produtoRepository.findByIdForUpdate(PRODUTO_ID)).thenReturn(Optional.of(produto));

        EstoqueInsuficienteException ex = assertThrows(EstoqueInsuficienteException.class,
                () -> service.movimentar(PRODUTO_ID, req("SAIDA", "999"), USUARIO_ID));

        assertEquals("Estoque insuficiente (20 em estoque).", ex.getMessage());
        assertEquals("quantidade", ex.getDetails().get(0).field());
        // CA-11: estoque intacto e NADA gravado (nem produto, nem ledger).
        assertEquals(0, produto.getEstoqueAtual().compareTo(new BigDecimal("20")));
        verify(produtoRepository, never()).save(any());
        verify(movimentacaoRepository, never()).saveAndFlush(any());
    }

    // ---- CA-12: AJUSTE define alvo (0 = zerar) / ENTRADA e SAIDA com 0 -> 400 -----------------

    @Test
    void ajuste_defineQuantidadeAlvo() {
        Produto produto = produtoComEstoque("20");
        stubGravacaoOk(produto);

        MovimentacaoResponse resp = service.movimentar(PRODUTO_ID, req("AJUSTE", "5"), USUARIO_ID);

        assertEquals(0, resp.quantidadeResultante().compareTo(new BigDecimal("5")));
        assertEquals(0, produto.getEstoqueAtual().compareTo(new BigDecimal("5")));
    }

    @Test
    void ajuste_zero_zeraEstoqueSemDeletar() {
        Produto produto = produtoComEstoque("20");
        stubGravacaoOk(produto);

        MovimentacaoResponse resp = service.movimentar(PRODUTO_ID, req("AJUSTE", "0"), USUARIO_ID);

        assertEquals(0, resp.quantidadeResultante().compareTo(BigDecimal.ZERO));
        assertEquals(0, produto.getEstoqueAtual().compareTo(BigDecimal.ZERO));
        verify(movimentacaoRepository).saveAndFlush(any()); // gravou a movimentacao, sem deletar produto
    }

    @Test
    void entrada_zero_lanca400ENaoGrava() {
        Produto produto = produtoComEstoque("10");
        when(produtoRepository.findByIdForUpdate(PRODUTO_ID)).thenReturn(Optional.of(produto));

        assertThrows(QuantidadeInvalidaException.class,
                () -> service.movimentar(PRODUTO_ID, req("ENTRADA", "0"), USUARIO_ID));

        verify(produtoRepository, never()).save(any());
        verify(movimentacaoRepository, never()).saveAndFlush(any());
    }

    @Test
    void saida_zero_lanca400ENaoGrava() {
        Produto produto = produtoComEstoque("10");
        when(produtoRepository.findByIdForUpdate(PRODUTO_ID)).thenReturn(Optional.of(produto));

        assertThrows(QuantidadeInvalidaException.class,
                () -> service.movimentar(PRODUTO_ID, req("SAIDA", "0"), USUARIO_ID));

        verify(produtoRepository, never()).save(any());
        verify(movimentacaoRepository, never()).saveAndFlush(any());
    }

    // ---- 404: produto inexistente ------------------------------------------------------------

    @Test
    void movimentar_produtoInexistente_lanca404() {
        when(produtoRepository.findByIdForUpdate(PRODUTO_ID)).thenReturn(Optional.empty());

        assertThrows(ProdutoNaoEncontradoException.class,
                () -> service.movimentar(PRODUTO_ID, req("ENTRADA", "5"), USUARIO_ID));
    }
}
