package com.floricultura.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.floricultura.api.domain.Cor;
import com.floricultura.api.domain.CorComUso;
import com.floricultura.api.domain.CorFactory;
import com.floricultura.api.domain.Cores;
import com.floricultura.api.repository.CorRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Unit da regra do catalogo de cores (T-M6-01b-1) com {@link CorRepository} mockado — sem banco/contexto
 * Spring. Prova: <b>CA-39</b> (tabela-verdade do {@code canonizar}, bordas → 400, filtro canonizado),
 * <b>CA-1</b> (persiste o canonico + hex maiusculo), <b>CA-2/CA-5</b> (duplicata por igualdade EXATA do
 * canonico, proprio canonico nao conflita), <b>CA-4</b> (contagem agrupada sem N+1) e <b>CA-6</b>
 * (409 com a contagem real vs 204). O CRUD ponta a ponta no envelope e do {@code CorApiTest}
 * (T-M6-01b-2).
 */
@ExtendWith(MockitoExtension.class)
class CorServiceTest {

    @Mock
    private CorRepository corRepository;

    @InjectMocks
    private CorService corService;

    private static Cor cor(long id, String nomeCanonico) {
        Cor cor = CorFactory.novo(nomeCanonico, null);
        cor.setId(id);
        return cor;
    }

    // ---- CA-39: tabela-verdade do canonizar (os 5 passos da §3.2.1, na ordem) -------------------

    @ParameterizedTest
    @CsvSource({
        "azul, AZUL", "Azul, AZUL", "AZUL, AZUL", "'  azul  ', AZUL", "'-azul-', AZUL",
        "'cinza escuro', CINZA-ESCURO", "'CINZA - ESCURO', CINZA-ESCURO",
        "'cinza   escuro', CINZA-ESCURO", "'cinza--escuro', CINZA-ESCURO",
        "'-CINZA-ESCURO-', CINZA-ESCURO", "'a z u l', A-Z-U-L", "'lilás', LILÁS", "'lilas', LILAS",
    })
    void canonizar_aplicaOsCincoPassos(String bruto, String esperado) {
        assertThat(Cores.canonizar(bruto)).isEqualTo(esperado);
    }

    @Test
    void canonizar_lilasComEsemAcento_saoNomesDiferentes() {
        assertThat(Cores.canonizar("lilás")).isNotEqualTo(Cores.canonizar("lilas")); // R1c/CA-39.3
    }

    @ParameterizedTest
    @ValueSource(strings = {"---", " - ", "   ", " ", ""})
    void canonizar_soSeparadores_viraVazio(String bruto) {
        assertThat(Cores.canonizar(bruto)).isEmpty();
    }

    @Test
    void canonizar_nuloViraVazio() {
        assertThat(Cores.canonizar(null)).isEmpty();
    }

    // ---- CA-39.4/39.5: bordas rejeitadas pelo SERVICO, sobre o canonico, sem persistir ----------

    @ParameterizedTest
    @CsvSource({
        "'---', Informe um nome de cor válido.",
        "' - ', Informe um nome de cor válido.",
        "'a', O nome da cor deve ter ao menos 2 caracteres.",
        "' a ', O nome da cor deve ter ao menos 2 caracteres.",
    })
    void criar_nomeInvalidoAposCanonizar_falhaComMensagemDaSpec(String bruto, String mensagem) {
        assertThatThrownBy(() -> corService.criar(bruto, null))
                .isInstanceOf(NomeCorInvalidoException.class)
                .hasMessage(mensagem);
        verify(corRepository, never()).save(any());
    }

    @Test
    void criar_canonicoAcimaDe40_falhaAntesDoBanco() {
        String longo = "a".repeat(41);

        assertThatThrownBy(() -> corService.criar(longo, null))
                .isInstanceOf(NomeCorInvalidoException.class)
                .hasMessage("O nome da cor deve ter no máximo 40 caracteres.");
        verify(corRepository, never()).save(any());
    }

    @Test
    void nomeInvalido_carregaOFieldNome() {
        NomeCorInvalidoException ex = new NomeCorInvalidoException("qualquer");
        assertThat(ex.getDetails()).singleElement()
                .satisfies(item -> assertThat(item.field()).isEqualTo("nome"));
    }

    // ---- CA-1: persiste o CANONICO + hex maiusculo -------------------------------------------

    @Test
    void criar_persisteCanonicoEHexEmMaiusculas() {
        ArgumentCaptor<Cor> captor = ArgumentCaptor.forClass(Cor.class);
        when(corRepository.existsByNome("CINZA-ESCURO")).thenReturn(false);
        when(corRepository.save(captor.capture())).thenAnswer(inv -> {
            Cor c = inv.getArgument(0);
            c.setId(7L);
            return c;
        });
        when(corRepository.findById(7L)).thenAnswer(inv -> Optional.of(captor.getValue()));

        CorComUso resp = corService.criar("  cinza escuro ", "#c4326b");

        assertThat(captor.getValue().getNome()).isEqualTo("CINZA-ESCURO");
        assertThat(captor.getValue().getHex()).isEqualTo("#C4326B");
        assertThat(resp.produtosVinculados()).isZero();
    }

    @Test
    void criar_hexEmBranco_viraNull() {
        when(corRepository.save(any())).thenAnswer(inv -> {
            Cor c = inv.getArgument(0);
            c.setId(8L);
            return c;
        });
        when(corRepository.findById(8L)).thenAnswer(inv -> Optional.of(cor(8L, "AZUL")));

        corService.criar("azul", "   ");

        ArgumentCaptor<Cor> captor = ArgumentCaptor.forClass(Cor.class);
        verify(corRepository).save(captor.capture());
        assertThat(captor.getValue().getHex()).isNull();
    }

    // ---- CA-2: duplicata por igualdade EXATA do canonico ---------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"Azul", "azul", "  AZUL  ", "-azul-", "AZUL"})
    void criar_canonicoJaExistente_conflita(String bruto) {
        when(corRepository.existsByNome("AZUL")).thenReturn(true);

        assertThatThrownBy(() -> corService.criar(bruto, null))
                .isInstanceOf(CorConflitoException.class)
                .hasMessage("Já existe uma cor com esse nome.");
        verify(corRepository, never()).save(any());
    }

    // ---- CA-5: PUT — proprio canonico nao conflita; canonico de outra cor conflita --------------

    @Test
    void atualizar_proprioCanonicoComOutraCaixa_naoConflita() {
        when(corRepository.findById(2L)).thenReturn(Optional.of(cor(2L, "ROSA")));
        when(corRepository.existsByNomeAndIdNot("ROSA", 2L)).thenReturn(false);
        when(corRepository.contarProdutos(2L)).thenReturn(0L);

        CorComUso resp = corService.atualizar(2L, "Rosa", null);

        assertThat(resp.cor().getNome()).isEqualTo("ROSA");
        assertThat(resp.cor().getAtualizadoEm()).isNotNull(); // §4.1: PUT estampa atualizado_em
    }

    @Test
    void atualizar_paraCanonicoDeOutraCor_conflita() {
        when(corRepository.findById(2L)).thenReturn(Optional.of(cor(2L, "ROSA")));
        when(corRepository.existsByNomeAndIdNot("AZUL", 2L)).thenReturn(true);

        assertThatThrownBy(() -> corService.atualizar(2L, "azul", null))
                .isInstanceOf(CorConflitoException.class);
        verify(corRepository, never()).save(any());
    }

    @Test
    void atualizar_idInexistente_naoEncontrada() {
        when(corRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> corService.atualizar(99L, "azul", null))
                .isInstanceOf(CorNaoEncontradaException.class);
    }

    // ---- CA-6: DELETE — 409 com a contagem real vs exclusao --------------------------------

    @Test
    void excluir_corEmUso_conflitaComAContagemReal() {
        when(corRepository.findById(5L)).thenReturn(Optional.of(cor(5L, "AZUL")));
        when(corRepository.contarProdutos(5L)).thenReturn(3L);

        assertThatThrownBy(() -> corService.excluir(5L))
                .isInstanceOf(CorConflitoException.class)
                .hasMessage("Cor em uso por 3 produto(s) — desvincule dos produtos antes de excluir.");
        verify(corRepository, never()).delete(any());
    }

    @Test
    void excluir_corSemVinculo_apaga() {
        Cor alvo = cor(5L, "AZUL");
        when(corRepository.findById(5L)).thenReturn(Optional.of(alvo));
        when(corRepository.contarProdutos(5L)).thenReturn(0L);

        corService.excluir(5L);

        verify(corRepository).delete(alvo);
    }

    // ---- CA-4: contagem agrupada (1 query por pagina, sem N+1) + CA-39.6: filtro canonizado -----

    @Test
    void listar_contagemAgrupadaEmUmaQuery_semNmais1() {
        Page<Cor> pagina = new PageImpl<>(
                List.of(cor(1L, "AZUL"), cor(2L, "ROSA"), cor(3L, "VERDE")),
                PageRequest.of(0, 20), 3);
        when(corRepository.findAll(any(Pageable.class))).thenReturn(pagina);
        when(corRepository.contarProdutosPorCor(List.of(1L, 2L, 3L)))
                .thenReturn(List.<Object[]>of(new Object[] {1L, 2L}));

        Page<CorComUso> resp = corService.listar(0, 20, null);

        assertThat(resp.getContent()).extracting(CorComUso::produtosVinculados)
                .containsExactly(2L, 0L, 0L); // cor sem vinculo -> 0, casado em memoria
        verify(corRepository, times(1)).contarProdutosPorCor(any());
        verify(corRepository, never()).contarProdutos(anyLong());
    }

    @Test
    void listar_paginaVazia_naoChamaAContagemAgrupada() {
        when(corRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        assertThat(corService.listar(0, 20, null).getContent()).isEmpty();
        verify(corRepository, never()).contarProdutosPorCor(any()); // IN () quebraria o Postgres
    }

    @Test
    void listar_filtroCanonizaOTermoAntesDeBuscar() {
        when(corRepository.findByNomeContaining(eq("CINZA-ESCURO"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        corService.listar(0, 20, "cinza escuro"); // CA-39.6

        verify(corRepository).findByNomeContaining(eq("CINZA-ESCURO"), any(Pageable.class));
        verify(corRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void listar_termoQueCanonizaParaVazio_naoFiltra() {
        when(corRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        corService.listar(0, 20, "---"); // leitura tolerante (PR3)

        verify(corRepository, never()).findByNomeContaining(any(), any());
    }
}
