package com.floricultura.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.floricultura.api.service.JwtService;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Conteudo textual do export (T-M7-06, 2a rodada): <b>fidelidade tipografica</b> (AD-SQ-184) e o
 * <b>{@code motivo} no detalhado</b> (decisao do dono, 2026-09-17).
 *
 * <p><b>Arquivo NOVO de proposito.</b> O {@code RelatorioExportTest} ja esta <b>rastreado</b> e o hook
 * anti-burla protege teste rastreado <b>inclusive do autor</b> (§12 #27). Nao havia liberacao aberta e
 * eu nao peco uma para acrescentar caso: caso novo em arquivo novo e <b>adicao pura</b> — nenhum caso
 * herdado e tocado, enfraquecido ou reordenado.
 *
 * <p><b>O que estes casos existem para impedir:</b> a perda <b>silenciosa</b> de caractere. As fontes
 * padrao do PDF escrevem em Cp1252 e o OpenPDF <b>descarta sem erro</b> o que nao cabe — medido:
 * {@code Rosa Białystok} (cultivar real) saia {@code Rosa Biaystok}, um nome de produto perdendo uma
 * letra sem nada vermelho em lugar nenhum. Sem o filtro, o comportamento errado e <b>verde por
 * omissao</b>; e por isso que o caso do marcador tem de existir junto com o caso do portugues
 * intacto: um prova que marca o que precisa, o outro que <b>nao</b> marca o que nao precisa.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RelatorioExportConteudoTest {

    private static final String BASE = "/api/v1/relatorios/movimentacoes/export";

    private static final String SETEMBRO = "?de=2026-09-01&ate=2026-09-30";

    /** Cultivar real, com {@code ł} (U+0142) — fora do Cp1252. */
    private static final String CULTIVAR_POLONESA = "Rosa Białystok";

    /** Acentuacao portuguesa + simbolos que o Cp1252 TEM: nada aqui pode virar marcador. */
    private static final String NOME_PORTUGUES = "Ortênsia Açaí 25°C — ok";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-conteudo-0123456789ab");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    private Path tmp;

    private String admin;

    @BeforeEach
    void seed() {
        jdbc.update("TRUNCATE TABLE movimentacao_estoque");
        jdbc.update("DELETE FROM produto");
        jdbc.update("DELETE FROM usuario");
        admin = "Bearer " + jwtService.gerarToken(
                usuario("Ana Admin", "admin-cont@floricultura.local", "ADMIN"));
    }

    // ---- AD-SQ-184: o papel CONFESSA o que nao consegue escrever ------------------------------

    /**
     * Caractere fora do Cp1252 vira <b>marcador visivel</b>. Sem o filtro, o {@code ł} some e o teste
     * fica verde por omissao — e por isso a assercao tem <b>duas pernas</b>: o marcador aparece
     * <b>e</b> o nome original nao aparece inteiro.
     */
    @Test
    void caractereForaDoCp1252ViraMarcadorVisivelNoPdf() throws Exception {
        lancamento(CULTIVAR_POLONESA, "SAIDA", "2026-09-12 09:00", null);

        String texto = textoDoPdf(baixar("&formato=PDF"));

        assertThat(texto).contains("Rosa Bia?ystok");
        assertThat(texto).doesNotContain(CULTIVAR_POLONESA);
    }

    /**
     * O outro lado, sem o qual o filtro poderia marcar tudo e ainda passar: <b>todo o portugues</b> e
     * os simbolos que o Cp1252 tem saem <b>intactos</b>, sem nenhum marcador.
     */
    @Test
    void portuguesAcentuadoESimbolosDoCp1252SaemIntactosNoPdf() throws Exception {
        lancamento(NOME_PORTUGUES, "SAIDA", "2026-09-12 09:00", null);

        String texto = textoDoPdf(baixar("&formato=PDF"));

        assertThat(texto).contains(NOME_PORTUGUES);
        assertThat(texto).doesNotContain("?");
    }

    /** O filtro e SO do PDF: o XLSX e UTF-8 e guarda o {@code ł} inteiro (nada se perde na planilha). */
    @Test
    void xlsxGuardaOCaractereOriginalSemMarcador() throws Exception {
        lancamento(CULTIVAR_POLONESA, "SAIDA", "2026-09-12 09:00", null);

        Map<String, String> celulas = celulas(baixar("&formato=XLSX"));

        assertThat(celulas.values()).contains(CULTIVAR_POLONESA);
        assertThat(celulas.values()).doesNotContain("Rosa Bia?ystok");
    }

    // ---- Decisao do dono: o motivo entra no detalhado -----------------------------------------

    /**
     * O {@code motivo} aparece no PDF, na <b>linha de apoio</b> abaixo do lancamento (mesmo desenho da
     * tela, §3.11-b). A medicao que descartou a 10a coluna esta no
     * {@code RelatorioExportService#COLUNA_MOTIVO}.
     */
    @Test
    void motivoApareceNaLinhaDeApoioDoPdf() throws Exception {
        lancamento("Rosa Vermelha", "AJUSTE", "2026-09-12 09:00", "Quebra no transporte");

        String texto = textoDoPdf(baixar("&formato=PDF"));

        assertThat(texto).contains("Motivo: Quebra no transporte");
    }

    /** Lancamento sem motivo <b>nao</b> ganha linha de apoio em branco — uma linha, um "Motivo:". */
    @Test
    void lancamentoSemMotivoNaoGanhaLinhaDeApoio() throws Exception {
        lancamento("Rosa Vermelha", "AJUSTE", "2026-09-12 09:00", "Quebra no transporte");
        lancamento("Rosa Vermelha", "SAIDA", "2026-09-13 09:00", null);

        String texto = textoDoPdf(baixar("&formato=PDF"));

        assertThat(texto.split("Motivo:", -1).length - 1).isEqualTo(1);
    }

    /** No XLSX o motivo e <b>coluna</b> (o dono filtra e ordena por ela), ancorada pelo cabecalho. */
    @Test
    void motivoApareceNaColunaMotivoDoXlsx() throws Exception {
        lancamento("Rosa Vermelha", "AJUSTE", "2026-09-12 09:00", "Quebra no transporte");

        Map<String, String> celulas = celulas(baixar("&formato=XLSX"));

        String colunaMotivo = coluna(exigirRef(celulas, "Motivo"));
        String alvo = colunaMotivo + linhaComTipo(celulas, "AJUSTE");
        assertThat(celulas).containsKey(alvo);
        assertThat(celulas.get(alvo)).isEqualTo("Quebra no transporte");
    }

    /**
     * Motivo no tamanho maximo da coluna ({@code varchar(255)}) cabe na linha de apoio <b>sem estourar
     * a folha e sem ser truncado</b> — que e o ponto: o {@code −} ja mostrou como e caro engolir texto
     * em silencio.
     *
     * <p>Largura util do A4 deitado = 786 pt. A ~3,85 pt por caractere (media do alfabeto minusculo a
     * 8 pt) dao ~204 caracteres por linha; este caso usa o <b>pior caso possivel</b>, 255 {@code M}
     * maiusculos (~6,6 pt cada), que quebram em <b>3</b> linhas — e ainda assim o documento fica com
     * <b>uma</b> pagina. A assercao remove o espaco em branco justamente porque a quebra de linha e
     * legitima; o que nao pode e faltar caractere.
     */
    @Test
    void motivoNoTamanhoMaximoNaoEstouraAFolhaNemEhTruncado() throws Exception {
        lancamento("Rosa Vermelha", "AJUSTE", "2026-09-12 09:00", "M".repeat(255));

        byte[] corpo = baixar("&formato=PDF");

        PdfReader reader = new PdfReader(corpo);
        int paginas = reader.getNumberOfPages();
        reader.close();
        assertThat(paginas).isEqualTo(1);
        assertThat(textoDoPdf(corpo).replaceAll("\\s+", ""))
                .contains("Motivo:" + "M".repeat(255));
    }

    // ---- Apoio ---------------------------------------------------------------------------------

    private byte[] baixar(String formato) throws Exception {
        return mockMvc.perform(get(BASE + SETEMBRO + formato)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    private String textoDoPdf(byte[] corpo) throws Exception {
        PdfReader reader = new PdfReader(corpo);
        StringBuilder texto = new StringBuilder();
        PdfTextExtractor extrator = new PdfTextExtractor(reader);
        for (int pagina = 1; pagina <= reader.getNumberOfPages(); pagina++) {
            texto.append(extrator.getTextFromPage(pagina));
        }
        reader.close();
        return texto.toString();
    }

    /**
     * Celulas por referencia, com {@code t="s"} resolvido contra a tabela compartilhada. Lido pela
     * <b>central directory</b> ({@link ZipFile}) — o {@code ZipInputStream} estoura no streaming do
     * fastexcel/opczip, que zera os tamanhos do cabecalho local.
     */
    private Map<String, String> celulas(byte[] xlsx) throws Exception {
        Path arquivo = Files.createTempFile(tmp, "export", ".xlsx");
        Files.write(arquivo, xlsx);
        Map<String, byte[]> entradas = new HashMap<>();
        try (ZipFile zip = new ZipFile(arquivo.toFile())) {
            Enumeration<? extends ZipEntry> nomes = zip.entries();
            while (nomes.hasMoreElements()) {
                ZipEntry entrada = nomes.nextElement();
                try (InputStream conteudo = zip.getInputStream(entrada)) {
                    entradas.put(entrada.getName(), conteudo.readAllBytes());
                }
            }
        }
        List<String> compartilhadas = new ArrayList<>();
        byte[] strings = entradas.get("xl/sharedStrings.xml");
        if (strings != null) {
            Matcher item = Pattern.compile("<si>.*?<t[^>]*>(.*?)</t>.*?</si>", Pattern.DOTALL)
                    .matcher(new String(strings, StandardCharsets.UTF_8));
            while (item.find()) {
                compartilhadas.add(decodificar(item.group(1)));
            }
        }
        Map<String, String> celulas = new LinkedHashMap<>();
        Matcher celula = Pattern.compile("<c r=\"([A-Z]+[0-9]+)\"([^>]*)>(.*?)</c>", Pattern.DOTALL)
                .matcher(new String(
                        entradas.get("xl/worksheets/sheet1.xml"), StandardCharsets.UTF_8));
        while (celula.find()) {
            Matcher valor = Pattern.compile("<v>(.*?)</v>", Pattern.DOTALL).matcher(celula.group(3));
            if (valor.find()) {
                celulas.put(celula.group(1), celula.group(2).contains("t=\"s\"")
                        ? compartilhadas.get(Integer.parseInt(valor.group(1)))
                        : valor.group(1));
            }
        }
        return celulas;
    }

    /** O fastexcel escapa acento como referencia numerica ({@code Lan&#xe7;amentos}) — medido. */
    private static String decodificar(String xml) {
        Matcher referencia = Pattern.compile("&#x([0-9A-Fa-f]+);").matcher(xml);
        StringBuilder texto = new StringBuilder();
        while (referencia.find()) {
            referencia.appendReplacement(texto, Matcher.quoteReplacement(
                    String.valueOf((char) Integer.parseInt(referencia.group(1), 16))));
        }
        referencia.appendTail(texto);
        return texto.toString().replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    private String exigirRef(Map<String, String> celulas, String texto) {
        return celulas.entrySet().stream()
                .filter(e -> texto.equals(e.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "celula com o texto '" + texto + "' nao existe na planilha"));
    }

    private int linhaComTipo(Map<String, String> celulas, String tipo) {
        String cabecalhoTipo = exigirRef(celulas, "Tipo");
        return celulas.entrySet().stream()
                .filter(e -> coluna(e.getKey()).equals(coluna(cabecalhoTipo)))
                .filter(e -> linha(e.getKey()) > linha(cabecalhoTipo))
                .filter(e -> tipo.equals(e.getValue()))
                .map(e -> linha(e.getKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("nenhuma linha do detalhado com tipo " + tipo));
    }

    private static String coluna(String ref) {
        return ref.replaceAll("[0-9]", "");
    }

    private static int linha(String ref) {
        return Integer.parseInt(ref.replaceAll("[A-Z]", ""));
    }

    private Long usuario(String nome, String email, String role) {
        return jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, ?, true, false) RETURNING id",
                Long.class, nome, email, UUID.randomUUID().toString(), role);
    }

    /** Produto + lancamento com {@code criado_em} cravado, para o recorte nao depender do relogio. */
    private void lancamento(String produto, String tipo, String quando, String motivo) {
        Long id = jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida, estoque_minimo, estoque_atual) "
                        + "VALUES (?, 'un', 1, 0) RETURNING id", Long.class, produto);
        jdbc.update(
                "INSERT INTO movimentacao_estoque (produto_id, produto_nome, tipo, quantidade, "
                        + "quantidade_resultante, usuario_nome, motivo, criado_em) "
                        + "VALUES (?, ?, ?, 1, 0, 'Ana Admin', ?, "
                        + "CAST(? AS timestamp) AT TIME ZONE 'America/Sao_Paulo')",
                id, produto, tipo, motivo, quando);
    }
}
