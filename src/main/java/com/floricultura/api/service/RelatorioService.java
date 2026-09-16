package com.floricultura.api.service;

import com.floricultura.api.repository.MovimentacaoRepository;
import com.floricultura.api.service.SerieDePeriodos.Balde;
import com.floricultura.api.web.dto.FiltroMovimentacao;
import com.floricultura.api.web.dto.FiltroRelatorio;
import com.floricultura.api.web.dto.RelatorioResponse;
import com.floricultura.api.web.dto.RelatorioResponse.Periodo;
import com.floricultura.api.web.dto.RelatorioResponse.Resumo;
import com.floricultura.api.web.dto.RelatorioResponse.Totais;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agregacao do relatorio de movimentacoes (SPEC-M7 §3.7 — CA-20..CA-25, CA-55). O banco agrega por
 * <b>dia x tipo</b> (ja sem as linhas do par estornado); aqui esses totais sao <b>encaixados</b> numa
 * serie de baldes que nasce de {@code de}/{@code ate} pelo {@link SerieDePeriodos}.
 *
 * <p>A ordem importa e e o que faz o CA-21 valer por construcao: <b>a serie vem primeiro</b>, os dados
 * depois. Um mes sem nenhuma linha nao tem como sumir do payload, porque o balde ja existia antes de a
 * query rodar.
 *
 * <p><b>O contador de excluidos e o complemento exato do mesmo recorte</b> (§3.7-d): as duas consultas
 * partem do mesmo {@code RECORTE_RELATORIO} e do mesmo predicado, uma com ele e a outra com
 * {@code NOT (...)} — por isso vale {@code somados + excluidos = total do recorte} (§10 #10f). As duas
 * rodam na mesma transacao de leitura; um INSERT concorrente entre elas e a unica forma de a
 * identidade escorregar, e nao vale um {@code REPEATABLE READ} num relatorio de floricultura.
 */
@Service
public class RelatorioService {

    private static final String ENTRADA = "ENTRADA";
    private static final String SAIDA = "SAIDA";

    private final MovimentacaoRepository movimentacaoRepository;

    /**
     * {@link MovimentacaoRepository} injetado como {@link Lazy} — <b>padrao obrigatorio da casa</b>
     * (mesmo do {@code MovimentacaoService}/{@code ProdutoService}): o smoke do M0
     * ({@code FloriculturaApiApplicationTests}) sobe o contexto <b>sem</b> DataSource/JPA/Flyway, e
     * sem o {@code @Lazy} o bean de repositorio seria exigido na subida e derrubaria os 4 casos
     * herdados do smoke (medido: foi o que aconteceu antes desta linha existir).
     */
    public RelatorioService(@Lazy MovimentacaoRepository movimentacaoRepository) {
        this.movimentacaoRepository = movimentacaoRepository;
    }

    /** CA-20: resumo do intervalo + serie continua de baldes, com a omissao declarada (PA#3). */
    @Transactional(readOnly = true)
    public RelatorioResponse gerar(FiltroRelatorio filtro) {
        FiltroMovimentacao janela = filtro.janela();
        List<Object[]> agregados = movimentacaoRepository.agregarPorDiaETipo(
                janela.de(), janela.ate(), janela.tipo(),
                janela.produtoId(), janela.clienteId(), janela.fornecedorId());
        long excluidos = movimentacaoRepository.contarExcluidosDoRecorte(
                janela.de(), janela.ate(), janela.tipo(),
                janela.produtoId(), janela.clienteId(), janela.fornecedorId());

        Map<LocalDate, Acumulador> porBalde = new HashMap<>();
        Acumulador resumo = new Acumulador();
        for (Object[] linha : agregados) {
            LocalDate dia = (LocalDate) linha[0];
            String tipo = (String) linha[1];
            long lancamentos = ((Number) linha[2]).longValue();
            BigDecimal quantidade = (BigDecimal) linha[3];
            BigDecimal valor = (BigDecimal) linha[4];
            LocalDate chave = SerieDePeriodos.chaveDe(dia, filtro.granularidade());
            porBalde.computeIfAbsent(chave, nova -> new Acumulador())
                    .somar(tipo, lancamentos, quantidade, valor);
            resumo.somar(tipo, lancamentos, quantidade, valor);
        }

        List<Periodo> periodos = new ArrayList<>();
        for (Balde balde : SerieDePeriodos.baldes(
                filtro.de(), filtro.ate(), filtro.granularidade())) {
            Acumulador acumulado = porBalde.getOrDefault(balde.chave(), new Acumulador());
            periodos.add(new Periodo(
                    balde.inicio(), balde.fim(),
                    acumulado.entradas.fechar(), acumulado.saidas.fechar(),
                    acumulado.ajustes.fechar(), acumulado.resultadoValor()));
        }

        return new RelatorioResponse(
                filtro.de(), filtro.ate(), filtro.granularidade(),
                new Resumo(
                        resumo.entradas.fechar(), resumo.saidas.fechar(), resumo.ajustes.fechar(),
                        resumo.resultadoValor(), excluidos),
                List.copyOf(periodos));
    }

    /** Soma parcial de um tipo de lancamento dentro de um recorte. */
    private static final class Parcial {

        private long lancamentos;
        private BigDecimal quantidade = BigDecimal.ZERO;
        private BigDecimal valor = BigDecimal.ZERO;

        void somar(long lancamentos, BigDecimal quantidade, BigDecimal valor) {
            this.lancamentos += lancamentos;
            this.quantidade = this.quantidade.add(quantidade);
            this.valor = this.valor.add(valor);
        }

        /** Escalas do contrato (§3.7): quantidade 3 casas, valor 2 — inclusive no balde vazio. */
        Totais fechar() {
            return new Totais(
                    lancamentos,
                    quantidade.setScale(3, RoundingMode.HALF_UP),
                    valor.setScale(2, RoundingMode.HALF_UP));
        }
    }

    /** Acumulador de um recorte — um balde da serie, ou o resumo do intervalo inteiro. */
    private static final class Acumulador {

        private final Parcial entradas = new Parcial();
        private final Parcial saidas = new Parcial();
        private final Parcial ajustes = new Parcial();

        void somar(String tipo, long lancamentos, BigDecimal quantidade, BigDecimal valor) {
            // O conjunto de tipos e fechado pelo CHECK ck_movimentacao_tipo da V1.
            Parcial alvo = ENTRADA.equals(tipo) ? entradas : SAIDA.equals(tipo) ? saidas : ajustes;
            alvo.somar(lancamentos, quantidade, valor);
        }

        /** §3.7-a: {@code saidas.valor − entradas.valor}, com a fórmula escrita na tela. */
        BigDecimal resultadoValor() {
            return saidas.fechar().valor().subtract(entradas.fechar().valor());
        }
    }
}
