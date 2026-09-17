package com.floricultura.api.service;

import com.floricultura.api.config.ClockConfig;
import com.floricultura.api.domain.MovimentacaoEstoque;
import com.floricultura.api.repository.MovimentacaoRepository;
import com.floricultura.api.web.dto.FiltroMovimentacao;
import com.floricultura.api.web.dto.FiltroRelatorio;
import com.floricultura.api.web.dto.FormatoExport;
import com.floricultura.api.web.dto.ParametroPaginacaoInvalidoException;
import com.floricultura.api.web.dto.RelatorioResponse;
import com.floricultura.api.web.dto.RelatorioResponse.Totais;
import com.floricultura.api.web.response.FieldErrorItem;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Export binario do relatorio de movimentacoes (SPEC-M7 §3.8, T-M7-06): o mesmo recorte do §3.7
 * materializado em <b>XLSX</b> (fastexcel) — e, a partir da fatia 2 desta task, em PDF.
 *
 * <p><b>Gerado NO SERVIDOR (decisao D-B do dono):</b> biblioteca no back nao pesa no celular; montar o
 * arquivo no navegador custaria ~300 KB de bundle e CPU no aparelho do operador (§3.14).
 *
 * <p><b>Os numeros do documento NAO sao recalculados aqui.</b> O bloco de resumo vem do
 * {@link RelatorioService#gerar} — literalmente o mesmo objeto que a API devolve —, de modo que papel e
 * tela <b>nao podem</b> divergir. Este servico nao soma, nao arredonda e nao converte dinheiro para
 * {@code double}: os valores ja chegam com escala fixa ({@code setScale(2)}/{@code (3)} no
 * {@code RelatorioService}; {@code NUMERIC(14,2)}/{@code (14,3)} nas colunas do ledger) e sao
 * repassados como {@link java.math.BigDecimal}. Nao ha caminho de arredondamento nesta classe — e e
 * de proposito (R6/AD-SQ-164: o unico jeito honesto de nao ter valor fraco e nao ter a conta).
 *
 * <p><b>O detalhado exclui o par estornado (decisao do dono, 2026-09-17)</b>, usando o <b>mesmo</b>
 * predicado da agregacao ({@code MovimentacaoRepository#listarDoRecorte}): somar a coluna "Total" com a
 * calculadora fecha com o resumo impresso na mesma folha. A omissao vai <b>declarada</b> na linha
 * "Lancamentos de pares estornados fora deste relatorio" (PA#3).
 *
 * <p><b>Nada e persistido em disco</b> (CA-28, §9): o documento nasce e morre em memoria, o que e
 * tambem a razao do teto de {@value #TETO_LINHAS} linhas (host grati, §12 #11).
 */
@Service
public class RelatorioExportService {

    /** Teto do detalhado (§3.8) — acima dele, 400 {@code field=ate} pedindo refino. */
    static final int TETO_LINHAS = 5000;

    /** Mesma mensagem-topo dos demais 400 de parametro de listagem/relatorio (§3.5/§3.7). */
    private static final String MSG = "Parametros de filtro invalidos.";

    private static final String TETO_ESTOURADO =
            "O relatório passou de 5.000 lançamentos. Refine o período ou os filtros.";

    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final DateTimeFormatter DIA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** Locale do documento — numero em pt-BR ({@code 1.234,56}), independente do locale do host. */
    private static final Locale BR = Locale.of("pt", "BR");

    /** Codificacao real das fontes padrao do PDF (Cp1252) — o que cabe no papel sem fonte embutida. */
    private static final Charset WINANSI = Charset.forName("windows-1252");

    /** O que aparece no lugar do caractere que a fonte do PDF nao escreve (AD-SQ-184). */
    private static final char MARCADOR_ILEGIVEL = '?';

    private static final Font TITULO = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);

    private static final Font CABECALHO = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);

    private static final Font CORPO = FontFactory.getFont(FontFactory.HELVETICA, 8);

    /** Colunas do detalhado — as mesmas da tela (§3.11-b), na mesma ordem. */
    static final List<String> COLUNAS = List.of(
            "Data/hora", "Produto", "Tipo", "Qtd", "Saldo", "Unitário", "Total", "Contraparte",
            "Autor");

    /**
     * <b>Decisao do dono (2026-09-17): o {@code motivo} entra no detalhado</b> — e o unico texto que
     * explica um AJUSTE ou um estorno num papel que vai para o contador (fecha com a PA#2, que tornou
     * o motivo do estorno obrigatorio justamente para esse texto existir).
     *
     * <p><b>Cada meio na forma que lhe cabe, porque a largura foi MEDIDA</b> (A4 deitado = 786 pt
     * uteis, ~3,85 pt por caractere a 8 pt):
     * <ul>
     *   <li><b>XLSX = coluna propria.</b> Planilha nao tem largura finita e o dono precisa
     *       filtrar/ordenar por ela.</li>
     *   <li><b>PDF = linha de apoio</b> abaixo do lancamento (o mesmo desenho da tela, §3.11-b:
     *       "Produto com o motivo embaixo"). Uma 10a coluna encolheria <b>Produto de 157,2 pt para
     *       131,0 pt (-17 %)</b> e quebraria um motivo de 255 caracteres em <b>~8 linhas</b>; na linha
     *       de apoio ele ocupa a largura inteira e cabe em <b>~2</b>, sem encolher nenhuma coluna.</li>
     * </ul>
     */
    static final String COLUNA_MOTIVO = "Motivo";

    private final RelatorioService relatorioService;
    private final MovimentacaoRepository movimentacaoRepository;
    private final Clock clock;

    /**
     * {@code @Lazy} no repositorio pelo mesmo motivo do {@link RelatorioService}: o smoke do M0 sobe o
     * contexto <b>sem</b> DataSource/JPA e exigir o bean na subida derrubaria os 4 casos herdados.
     */
    public RelatorioExportService(
            RelatorioService relatorioService,
            @Lazy MovimentacaoRepository movimentacaoRepository,
            Clock clock) {
        this.relatorioService = relatorioService;
        this.movimentacaoRepository = movimentacaoRepository;
        this.clock = clock;
    }

    /**
     * Conteudo pronto para a resposta binaria — fora do envelope {@code ApiResponse} (precedente do
     * endpoint de imagem, AD-SQ-37).
     *
     * @param conteudo    bytes do arquivo (nunca gravados em disco)
     * @param nome        {@code movimentacoes-<de>_a_<ate>.<ext>} — <b>so ASCII</b> por construcao
     *                    (prefixo literal + duas datas ISO), o que dispensa o {@code filename*} da
     *                    RFC 5987 e evita a divergencia entre navegadores com acento no nome
     * @param contentType {@code Content-Type} do formato (§3.8)
     */
    public record Arquivo(byte[] conteudo, String nome, String contentType) {
    }

    /**
     * CA-26..CA-30: valida o teto, busca resumo + detalhado do <b>mesmo</b> recorte e escreve o
     * documento. O 400 do teto vem <b>depois</b> da validacao de parametro (que ja rodou no
     * {@link FiltroRelatorio}), porque ele exige ida ao banco — precedencia da AD-SQ-170 preservada.
     */
    @Transactional(readOnly = true)
    public Arquivo gerar(FiltroRelatorio filtro, FormatoExport formato) {
        FiltroMovimentacao janela = filtro.janela();
        long linhas = movimentacaoRepository.contarDoRecorte(
                janela.de(), janela.ate(), janela.tipo(),
                janela.produtoId(), janela.clienteId(), janela.fornecedorId());
        if (linhas > TETO_LINHAS) {
            throw new ParametroPaginacaoInvalidoException(
                    MSG, List.of(new FieldErrorItem("ate", TETO_ESTOURADO)));
        }
        RelatorioResponse dados = relatorioService.gerar(filtro);
        List<MovimentacaoEstoque> detalhado = movimentacaoRepository.listarDoRecorte(
                janela.de(), janela.ate(), janela.tipo(),
                janela.produtoId(), janela.clienteId(), janela.fornecedorId());
        byte[] conteudo = switch (formato) {
            case PDF -> pdf(dados, filtro, detalhado);
            case XLSX -> xlsx(dados, filtro, detalhado);
        };
        return new Arquivo(conteudo, nomeArquivo(filtro, formato), formato.contentType());
    }

    /** §3.8: {@code movimentacoes-<de>_a_<ate>.<ext>} — datas ISO, sem espaco e sem acento. */
    static String nomeArquivo(FiltroRelatorio filtro, FormatoExport formato) {
        return "movimentacoes-" + filtro.de() + "_a_" + filtro.ate() + "." + formato.extensao();
    }

    // ---- XLSX (fastexcel) ---------------------------------------------------------------------

    /**
     * Planilha unica: cabecalho, resumo e detalhado. <b>Dinheiro e quantidade vao como celula
     * NUMERICA</b> (nao texto), senao o dono nao consegue somar no Excel — com formato {@code #,##0.00}
     * aplicado por faixa, para a leitura continuar em pt-BR.
     */
    private byte[] xlsx(
            RelatorioResponse dados, FiltroRelatorio filtro, List<MovimentacaoEstoque> detalhado) {
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        Workbook wb = new Workbook(saida, "Floricultura", "1.0");
        Worksheet ws = wb.newWorksheet("Movimentações");
        int linha = cabecalhoXlsx(ws, dados, filtro, detalhado);
        linha = resumoXlsx(ws, dados.resumo(), linha);
        detalhadoXlsx(ws, detalhado, linha);
        try {
            wb.finish();
        } catch (IOException falhaEmMemoria) {
            throw new UncheckedIOException(falhaEmMemoria);
        }
        return saida.toByteArray();
    }

    /** Titulo, periodo, filtros em portugues e data/hora de geracao em {@code America/Sao_Paulo}. */
    private int cabecalhoXlsx(Worksheet ws, RelatorioResponse dados, FiltroRelatorio filtro,
            List<MovimentacaoEstoque> detalhado) {
        ws.value(0, 0, "Relatório de movimentações");
        ws.style(0, 0).bold().fontSize(14).set();
        ws.value(1, 0, "Período");
        ws.value(1, 1, dados.de().format(DIA) + " a " + dados.ate().format(DIA));
        ws.value(2, 0, "Filtros");
        ws.value(2, 1, descreverFiltros(filtro, detalhado));
        ws.value(3, 0, "Gerado em");
        ws.value(3, 1, LocalDateTime.now(clock).format(DIA_HORA));
        return 5;
    }

    /** O {@code resumo} do §3.7, com a formula escrita e a omissao dos estornados declarada. */
    private int resumoXlsx(Worksheet ws, RelatorioResponse.Resumo resumo, int inicio) {
        int linha = inicio;
        ws.value(linha, 0, "Resumo");
        ws.style(linha, 0).bold().set();
        linha++;
        ws.value(linha, 1, "Lançamentos");
        ws.value(linha, 2, "Quantidade");
        ws.value(linha, 3, "Valor (R$)");
        ws.style(linha, 1).bold().set();
        ws.style(linha, 2).bold().set();
        ws.style(linha, 3).bold().set();
        linha++;
        int primeiraLinhaDeTotais = linha;
        linha = totaisXlsx(ws, linha, "Entradas", resumo.entradas());
        linha = totaisXlsx(ws, linha, "Saídas", resumo.saidas());
        linha = totaisXlsx(ws, linha, "Ajustes", resumo.ajustes());
        ws.range(primeiraLinhaDeTotais, 2, linha - 1, 2).style().format("#,##0.000").set();
        ws.range(primeiraLinhaDeTotais, 3, linha - 1, 3).style().format("#,##0.00").set();
        // No XLSX o XML e UTF-8: aqui o sinal de menos do contrato (U+2212) vai inteiro.
        ws.value(linha, 0, "Resultado (Saídas − Entradas)");
        ws.value(linha, 3, resumo.resultadoValor());
        ws.style(linha, 3).format("#,##0.00").bold().set();
        linha++;
        ws.value(linha, 0, "Lançamentos de pares estornados fora deste relatório");
        ws.value(linha, 1, resumo.lancamentosEstornadosExcluidos());
        return linha + 2;
    }

    private int totaisXlsx(Worksheet ws, int linha, String rotulo, Totais totais) {
        ws.value(linha, 0, rotulo);
        ws.value(linha, 1, totais.lancamentos());
        ws.value(linha, 2, totais.quantidade());
        ws.value(linha, 3, totais.valor());
        return linha + 1;
    }

    /** Tabela detalhada com as colunas da tela (§3.11-b), na ordem da tela ({@code criado_em DESC}). */
    private void detalhadoXlsx(Worksheet ws, List<MovimentacaoEstoque> detalhado, int inicio) {
        int linha = inicio;
        for (int coluna = 0; coluna < COLUNAS.size(); coluna++) {
            ws.value(linha, coluna, COLUNAS.get(coluna));
            ws.style(linha, coluna).bold().set();
        }
        ws.value(linha, COLUNAS.size(), COLUNA_MOTIVO);
        ws.style(linha, COLUNAS.size()).bold().set();
        int primeiraLinhaDeDados = linha + 1;
        for (MovimentacaoEstoque m : detalhado) {
            linha++;
            ws.value(linha, 0, LocalDateTime.ofInstant(m.getCriadoEm(), ClockConfig.ZONA_SAO_PAULO));
            ws.value(linha, 1, m.getProdutoNome());
            ws.value(linha, 2, m.getTipo());
            ws.value(linha, 3, m.getQuantidade());
            ws.value(linha, 4, m.getQuantidadeResultante());
            // Lancamento sem dinheiro (P6, as 5 colunas NULL) deixa a celula VAZIA — nao "0,00" e nao
            // "—": em planilha, vazio e o unico valor que nao mente numa SOMA feita pelo dono.
            if (m.getValorUnitario() != null) {
                ws.value(linha, 5, m.getValorUnitario());
            }
            if (m.getTotalFinal() != null) {
                ws.value(linha, 6, m.getTotalFinal());
            }
            ws.value(linha, 7, contraparte(m));
            if (m.getUsuarioNome() != null) {
                ws.value(linha, 8, m.getUsuarioNome());
            }
            if (m.getMotivo() != null) {
                ws.value(linha, 9, m.getMotivo());
            }
        }
        if (linha >= primeiraLinhaDeDados) {
            ws.range(primeiraLinhaDeDados, 0, linha, 0).style().format("dd/mm/yyyy hh:mm").set();
            ws.range(primeiraLinhaDeDados, 3, linha, 4).style().format("#,##0.000").set();
            ws.range(primeiraLinhaDeDados, 5, linha, 6).style().format("#,##0.00").set();
        }
        ws.width(0, 18);
        ws.width(1, 28);
        ws.width(7, 24);
        ws.width(8, 20);
    }

    // ---- PDF (OpenPDF) ------------------------------------------------------------------------

    /**
     * Mesma informacao do XLSX, em A4 <b>deitado</b> (as 9 colunas do §3.11-b nao cabem em retrato) e
     * com o rodape {@code Página X de Y} do §3.8.
     *
     * <p><b>Texto, aqui, e so apresentacao:</b> os numeros sao formatados a partir do
     * {@link java.math.BigDecimal} que ja veio com escala fixa — nunca de um {@code double} —, entao a
     * formatacao nao pode mudar um centavo (R6).
     */
    private byte[] pdf(
            RelatorioResponse dados, FiltroRelatorio filtro, List<MovimentacaoEstoque> detalhado) {
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4.rotate(), 28, 28, 36, 36);
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, saida);
            writer.setPageEvent(new Rodape());
            doc.open();
            doc.add(paragrafo("Relatório de movimentações", TITULO));
            doc.add(paragrafo(
                    "Período: " + dados.de().format(DIA) + " a " + dados.ate().format(DIA), CORPO));
            doc.add(paragrafo("Filtros: " + descreverFiltros(filtro, detalhado), CORPO));
            doc.add(paragrafo(
                    "Gerado em: " + LocalDateTime.now(clock).format(DIA_HORA), CORPO));
            doc.add(resumoPdf(dados.resumo()));
            doc.add(detalhadoPdf(detalhado));
            doc.close();
        } catch (DocumentException falhaDeMontagem) {
            throw new IllegalStateException("Falha ao montar o PDF do relatório.", falhaDeMontagem);
        }
        return saida.toByteArray();
    }

    private PdfPTable resumoPdf(RelatorioResponse.Resumo resumo) {
        PdfPTable tabela = new PdfPTable(new float[] {5, 2, 2, 2});
        tabela.setWidthPercentage(65);
        tabela.setSpacingBefore(14);
        tabela.setHorizontalAlignment(Element.ALIGN_LEFT);
        celula(tabela, "Resumo", CABECALHO, Element.ALIGN_LEFT);
        celula(tabela, "Lançamentos", CABECALHO, Element.ALIGN_RIGHT);
        celula(tabela, "Quantidade", CABECALHO, Element.ALIGN_RIGHT);
        celula(tabela, "Valor (R$)", CABECALHO, Element.ALIGN_RIGHT);
        linhaDeTotais(tabela, "Entradas", resumo.entradas());
        linhaDeTotais(tabela, "Saídas", resumo.saidas());
        linhaDeTotais(tabela, "Ajustes", resumo.ajustes());
        // A formula vai ESCRITA (§3.7-a): "saldo" sem formula e adjetivo.
        // ⚠️ HIFEN ASCII de proposito, e SO no PDF: o literal do contrato usa o sinal de menos
        // U+2212, que NAO existe no Cp1252/WinAnsi das fontes padrao do PDF — o OpenPDF descarta o
        // caractere em SILENCIO (medido: a primeira execucao deste teste imprimiu "Saídas  Entradas",
        // com o sinal comido). Embutir uma TTF Unicode custaria centenas de KB no jar, contra o
        // orcamento medido do §3.14. O XLSX e o JSON continuam com o U+2212.
        celula(tabela, "Resultado (Saídas - Entradas)", CABECALHO, Element.ALIGN_LEFT);
        celula(tabela, "", CORPO, Element.ALIGN_RIGHT);
        celula(tabela, "", CORPO, Element.ALIGN_RIGHT);
        celula(tabela, dinheiro(resumo.resultadoValor()), CABECALHO, Element.ALIGN_RIGHT);
        // PA#3: a omissao e declarada no proprio papel, nunca silenciosa.
        celula(tabela, "Lançamentos de pares estornados fora deste relatório", CORPO,
                Element.ALIGN_LEFT);
        celula(tabela, String.valueOf(resumo.lancamentosEstornadosExcluidos()), CORPO,
                Element.ALIGN_RIGHT);
        celula(tabela, "", CORPO, Element.ALIGN_RIGHT);
        celula(tabela, "", CORPO, Element.ALIGN_RIGHT);
        return tabela;
    }

    private void linhaDeTotais(PdfPTable tabela, String rotulo, Totais totais) {
        celula(tabela, rotulo, CORPO, Element.ALIGN_LEFT);
        celula(tabela, String.valueOf(totais.lancamentos()), CORPO, Element.ALIGN_RIGHT);
        celula(tabela, quantidade(totais.quantidade()), CORPO, Element.ALIGN_RIGHT);
        celula(tabela, dinheiro(totais.valor()), CORPO, Element.ALIGN_RIGHT);
    }

    private PdfPTable detalhadoPdf(List<MovimentacaoEstoque> detalhado) {
        PdfPTable tabela = new PdfPTable(new float[] {3, 5, 2, 2, 2, 2, 2, 4, 3});
        tabela.setWidthPercentage(100);
        tabela.setSpacingBefore(16);
        tabela.setHeaderRows(1);
        for (String coluna : COLUNAS) {
            celula(tabela, coluna, CABECALHO, Element.ALIGN_LEFT);
        }
        for (MovimentacaoEstoque m : detalhado) {
            celula(tabela, DIA_HORA.format(
                    LocalDateTime.ofInstant(m.getCriadoEm(), ClockConfig.ZONA_SAO_PAULO)),
                    CORPO, Element.ALIGN_LEFT);
            celula(tabela, m.getProdutoNome(), CORPO, Element.ALIGN_LEFT);
            celula(tabela, m.getTipo(), CORPO, Element.ALIGN_LEFT);
            celula(tabela, quantidade(m.getQuantidade()), CORPO, Element.ALIGN_RIGHT);
            celula(tabela, quantidade(m.getQuantidadeResultante()), CORPO, Element.ALIGN_RIGHT);
            celula(tabela, dinheiro(m.getValorUnitario()), CORPO, Element.ALIGN_RIGHT);
            celula(tabela, dinheiro(m.getTotalFinal()), CORPO, Element.ALIGN_RIGHT);
            celula(tabela, contraparte(m), CORPO, Element.ALIGN_LEFT);
            celula(tabela, m.getUsuarioNome() == null ? "" : m.getUsuarioNome(), CORPO,
                    Element.ALIGN_LEFT);
            motivoPdf(tabela, m.getMotivo());
        }
        return tabela;
    }

    /**
     * Linha de apoio com o {@code motivo}, abaixo do lancamento e ocupando a largura inteira — so
     * quando ha motivo (lancamento sem motivo nao ganha linha em branco). Ver {@link #COLUNA_MOTIVO}
     * para a medicao que descartou a 10a coluna.
     */
    private static void motivoPdf(PdfPTable tabela, String motivo) {
        if (motivo == null || motivo.isBlank()) {
            return;
        }
        PdfPCell apoio = new PdfPCell(new Phrase(legivelNoPdf("Motivo: " + motivo), CORPO));
        apoio.setColspan(COLUNAS.size());
        apoio.setHorizontalAlignment(Element.ALIGN_LEFT);
        apoio.setPadding(3);
        tabela.addCell(apoio);
    }

    private static Paragraph paragrafo(String texto, Font fonte) {
        return new Paragraph(legivelNoPdf(texto), fonte);
    }

    private static void celula(PdfPTable tabela, String texto, Font fonte, int alinhamento) {
        PdfPCell celula = new PdfPCell(new Phrase(legivelNoPdf(texto), fonte));
        celula.setHorizontalAlignment(alinhamento);
        celula.setPadding(3);
        tabela.addCell(celula);
    }

    /**
     * <b>AD-SQ-184 (decisao do dono): caractere que a fonte do PDF nao sabe escrever vira marcador
     * VISIVEL, nunca silencio.</b>
     *
     * <p>As fontes padrao do PDF escrevem em <b>Cp1252/WinAnsi</b>, e o OpenPDF <b>descarta sem erro
     * nenhum</b> o que nao cabe ali. Medido: {@code Rosa Białystok} (cultivar real) saia
     * {@code Rosa Biaystok} — <b>um nome de produto perdendo uma letra, sem nada vermelho em lugar
     * nenhum</b>. Todo o portugues passa intacto ({@code Ortênsia}, {@code Açaí}, {@code °},
     * {@code —}), entao o marcador so aparece quando ha mesmo perda.
     *
     * <p><b>Por que marcador e nao fonte embutida:</b> uma TTF Unicode custaria centenas de KB contra
     * o orcamento MEDIDO do §3.14 (3 766 034 B). As duas saidas nao se excluem — no dia em que o
     * catalogo tiver cultivar estrangeira de verdade, paga-se a fonte; ate la, o papel <b>confessa</b>
     * a perda. Mesma razao da AD-SQ-176: redigir em silencio e o pior modo de falhar.
     *
     * <p>O XLSX <b>nao</b> passa por aqui: o XML dele e UTF-8 e escreve {@code ł} sem perder nada.
     */
    static String legivelNoPdf(String texto) {
        if (texto == null) {
            return "";
        }
        if (soAscii(texto)) {
            return texto;
        }
        // Encoder e mutavel/nao thread-safe: um por chamada, e so quando ha caractere fora do ASCII.
        CharsetEncoder winAnsi = WINANSI.newEncoder();
        StringBuilder saida = new StringBuilder(texto.length());
        for (int i = 0; i < texto.length(); i++) {
            char caractere = texto.charAt(i);
            saida.append(winAnsi.canEncode(caractere) ? caractere : MARCADOR_ILEGIVEL);
        }
        return saida.toString();
    }

    private static boolean soAscii(String texto) {
        for (int i = 0; i < texto.length(); i++) {
            if (texto.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    /**
     * Rodape {@code Página X de Y} (§3.8). O {@code Y} so existe quando o documento fecha, entao ele e
     * reservado como {@link PdfTemplate} em cada pagina e preenchido no {@code onCloseDocument} — o
     * caminho classico do iText/OpenPDF, e a unica forma de nao montar o documento duas vezes.
     */
    private static final class Rodape extends PdfPageEventHelper {

        private PdfTemplate totalDePaginas;
        private BaseFont fonte;

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            totalDePaginas = writer.getDirectContent().createTemplate(36, 12);
            try {
                fonte = BaseFont.createFont();
            } catch (IOException falhaDeFonte) {
                throw new UncheckedIOException(falhaDeFonte);
            }
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte canvas = writer.getDirectContent();
            canvas.beginText();
            canvas.setFontAndSize(fonte, 8);
            canvas.setTextMatrix(document.left(), document.bottom() - 18);
            canvas.showText("Página " + writer.getPageNumber() + " de ");
            canvas.endText();
            canvas.addTemplate(totalDePaginas, document.left() + 52, document.bottom() - 18);
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            totalDePaginas.beginText();
            totalDePaginas.setFontAndSize(fonte, 8);
            totalDePaginas.setTextMatrix(0, 0);
            totalDePaginas.showText(String.valueOf(writer.getPageNumber() - 1));
            totalDePaginas.endText();
        }
    }

    // ---- Apoio compartilhado ------------------------------------------------------------------

    /**
     * Dinheiro em pt-BR a partir do {@link java.math.BigDecimal} — <b>sem passar por {@code double}</b>
     * (o {@code Formatter} tem caminho exato para {@code BigDecimal}). Valor ausente (P6: lancamento
     * sem dinheiro) vira {@code —}, que a tela ja usa e o dono distingue de {@code R$ 0,00} (§4 #4).
     */
    static String dinheiro(java.math.BigDecimal valor) {
        return valor == null ? "—" : String.format(BR, "%,.2f", valor);
    }

    /** Quantidade com as 3 casas da coluna {@code NUMERIC(14,3)}. */
    static String quantidade(java.math.BigDecimal valor) {
        return valor == null ? "—" : String.format(BR, "%,.3f", valor);
    }

    /**
     * Filtros do recorte <b>em portugues</b> (§3.8). Os nomes saem do <b>snapshot que o proprio ledger
     * guarda</b> (V10: {@code produto_nome}/{@code fornecedor_nome}/{@code cliente_nome}) — nenhuma
     * consulta extra, e o nome impresso e o que valia no lancamento, nao o de hoje.
     */
    private String descreverFiltros(FiltroRelatorio filtro, List<MovimentacaoEstoque> detalhado) {
        FiltroMovimentacao janela = filtro.janela();
        MovimentacaoEstoque amostra = detalhado.isEmpty() ? null : detalhado.get(0);
        List<String> partes = new ArrayList<>(4);
        if (janela.tipo() != null) {
            partes.add("Tipo: " + janela.tipo());
        }
        if (janela.produtoId() != null) {
            partes.add("Produto: " + nomeOuId(amostra == null ? null : amostra.getProdutoNome(),
                    janela.produtoId()));
        }
        if (janela.fornecedorId() != null) {
            partes.add("Fornecedor: " + nomeOuId(amostra == null ? null : amostra.getFornecedorNome(),
                    janela.fornecedorId()));
        }
        if (janela.clienteId() != null) {
            partes.add("Cliente: " + nomeOuId(amostra == null ? null : amostra.getClienteNome(),
                    janela.clienteId()));
        }
        return partes.isEmpty() ? "Nenhum filtro além do período." : String.join(" · ", partes);
    }

    /**
     * Sem o {@code id} do filtro casar nenhuma linha (AD-SQ-168: recorte vazio e 200, nunca 400), nao
     * ha snapshot de nome para mostrar — entao o cabecalho diz {@code #<id>} e nao mente um nome.
     */
    private static String nomeOuId(String nome, Long id) {
        return nome != null ? nome : "#" + id;
    }

    /** Fornecedor (ENTRADA) ou cliente (SAIDA) — a linha de estorno nao tem nenhum (AD-SQ-156). */
    static String contraparte(MovimentacaoEstoque m) {
        if (m.getFornecedorNome() != null) {
            return m.getFornecedorNome();
        }
        return m.getClienteNome() != null ? m.getClienteNome() : "";
    }
}
