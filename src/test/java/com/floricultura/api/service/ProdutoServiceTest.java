package com.floricultura.api.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit da regra de escrita de produtos (T-M2-3, CA-6/CA-7/CA-9/CA-15) com {@link ProdutoRepository}
 * mockado — sem banco/contexto Spring, isola a logica de negocio.
 *
 * <p>Prova: <b>CA-6</b> — criacao nasce com {@code estoqueAtual=0} (via {@code ProdutoFactory}) e
 * {@code ativo=true}; <b>CA-7</b> — {@code atualizar} <b>nunca</b> altera {@code estoqueAtual} (o valor
 * pre-existente da entity e preservado) e avanca {@code atualizadoEm}; <b>CA-9</b> — {@code preco}
 * {@code null} e aceito na criacao/edicao; <b>CA-15</b> — {@code estoqueBaixo} computado no mapeamento.
 */
@ExtendWith(MockitoExtension.class)
class ProdutoServiceTest {

    @Mock
    private ProdutoRepository produtoRepository;

    @InjectMocks
    private ProdutoService produtoService;

    // ---- CA-6: criacao com estoqueAtual=0 -----------------------------------------------------

    @Test
    void criar_nasceComEstoqueAtualZeroEAtivo() {
        CriarProdutoRequest req = new CriarProdutoRequest(
                "Rosa Vermelha", "Maco com 12", "un", new BigDecimal("10"),
                new BigDecimal("4.50"), "https://exemplo.local/rosa.jpg");

        ArgumentCaptor<Produto> captor = ArgumentCaptor.forClass(Produto.class);
        when(produtoRepository.save(captor.capture())).thenAnswer(inv -> {
            Produto p = inv.getArgument(0);
            p.setId(10L);
            return p;
        });
        when(produtoRepository.findById(10L)).thenAnswer(inv -> Optional.of(captor.getValue()));

        ProdutoResponse resp = produtoService.criar(req);

        Produto salvo = captor.getValue();
        assertThat(salvo.getEstoqueAtual()).isEqualByComparingTo(BigDecimal.ZERO); // AD-SQ-30
        assertThat(salvo.isAtivo()).isTrue();
        assertThat(salvo.getNome()).isEqualTo("Rosa Vermelha");
        assertThat(resp.estoqueAtual()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ---- CA-9: preco opcional -----------------------------------------------------------------

    @Test
    void criar_semPreco_persistePrecoNull() {
        CriarProdutoRequest req = new CriarProdutoRequest(
                "Lirio", null, "un", new BigDecimal("5"), null, null);

        ArgumentCaptor<Produto> captor = ArgumentCaptor.forClass(Produto.class);
        when(produtoRepository.save(captor.capture())).thenAnswer(inv -> {
            Produto p = inv.getArgument(0);
            p.setId(11L);
            return p;
        });
        when(produtoRepository.findById(11L)).thenAnswer(inv -> Optional.of(captor.getValue()));

        ProdutoResponse resp = produtoService.criar(req);

        assertThat(captor.getValue().getPreco()).isNull();
        assertThat(resp.preco()).isNull();
    }

    // ---- CA-7: atualizar nunca toca estoqueAtual ----------------------------------------------

    @Test
    void atualizar_naoAlteraEstoqueAtual() {
        Produto existente = produtoComEstoque(new BigDecimal("30"), new BigDecimal("10"));
        when(produtoRepository.findById(7L)).thenReturn(Optional.of(existente));
        when(produtoRepository.save(any(Produto.class))).thenAnswer(inv -> inv.getArgument(0));

        AtualizarProdutoRequest req = new AtualizarProdutoRequest(
                "Rosa Editada", "nova desc", "kg", new BigDecimal("15"),
                new BigDecimal("9.90"), null);

        ProdutoResponse resp = produtoService.atualizar(7L, req);

        // Campos de cadastro atualizados, estoque PRESERVADO (30), atualizadoEm avancou.
        assertThat(resp.nome()).isEqualTo("Rosa Editada");
        assertThat(resp.unidadeMedida()).isEqualTo("kg");
        assertThat(resp.estoqueMinimo()).isEqualByComparingTo("15");
        assertThat(resp.estoqueAtual()).isEqualByComparingTo("30"); // CA-7: intacto
        assertThat(existente.getAtualizadoEm()).isNotNull();
    }

    @Test
    void atualizar_inexistente_lanca404() {
        when(produtoRepository.findById(999L)).thenReturn(Optional.empty());
        AtualizarProdutoRequest req = new AtualizarProdutoRequest(
                "X", null, "un", new BigDecimal("1"), null, null);

        assertThatThrownBy(() -> produtoService.atualizar(999L, req))
                .isInstanceOf(ProdutoNaoEncontradoException.class);
        verify(produtoRepository, never()).save(any());
    }

    // ---- CA-15: estoqueBaixo computado no mapeamento ------------------------------------------

    @Test
    void atualizar_estoqueAbaixoDoMinimo_marcaEstoqueBaixo() {
        // estoqueAtual 5 <= estoqueMinimo (novo) 8 → estoqueBaixo true (FC-13/CA-15).
        Produto existente = produtoComEstoque(new BigDecimal("5"), new BigDecimal("3"));
        when(produtoRepository.findById(1L)).thenReturn(Optional.of(existente));
        when(produtoRepository.save(any(Produto.class))).thenAnswer(inv -> inv.getArgument(0));

        AtualizarProdutoRequest req = new AtualizarProdutoRequest(
                "Cravo", null, "un", new BigDecimal("8"), null, null);

        ProdutoResponse resp = produtoService.atualizar(1L, req);
        assertThat(resp.estoqueBaixo()).isTrue();
    }

    // ---- CA-8: delete inexistente -> 404 (o caminho feliz e coberto no ProdutoDeleteTest) -----

    @Test
    void deletar_inexistente_lanca404() {
        when(produtoRepository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> produtoService.deletar(999L))
                .isInstanceOf(ProdutoNaoEncontradoException.class);
        verify(produtoRepository, never()).deleteById(any());
    }

    @Test
    void deletar_existente_removeProduto() {
        when(produtoRepository.existsById(5L)).thenReturn(true);

        produtoService.deletar(5L);

        verify(produtoRepository).deleteById(5L);
    }

    /**
     * Monta uma entity de produto ja persistida com estoque atual/minimo definidos. Usa
     * {@link ProdutoFactory} (construtor da entity e {@code protected}) e sobrescreve o
     * {@code estoqueAtual} — a Factory sempre nasce zerada, mas aqui simulamos um produto que ja
     * recebeu movimentacao.
     */
    private Produto produtoComEstoque(BigDecimal atual, BigDecimal minimo) {
        Produto p = ProdutoFactory.novo("Produto", null, "un", minimo, null, null);
        p.setId(1L);
        p.setEstoqueAtual(atual);
        p.setAtualizadoEm(Instant.parse("2026-09-01T00:00:00Z"));
        return p;
    }
}
