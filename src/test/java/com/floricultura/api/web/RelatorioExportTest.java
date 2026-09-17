package com.floricultura.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.floricultura.api.service.JwtService;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.io.InputStream;
import java.math.BigDecimal;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Export do relatorio (T-M7-06, SPEC-M7 §3.8 — CA-26..CA-30, CA-54) contra PostgreSQL de descarte.
 *
 * <p><b>Por que este arquivo nao se contenta com status + {@code Content-Type}:</b> a armadilha
 * nomeada no §10 #11 e um <b>HTML de erro com header forcado</b>, que passa por qualquer assercao de
 * cabecalho. Aqui os dois formatos sao <b>abertos</b>: o PDF pelo {@link PdfReader} (e o texto lido
 * pelo {@link PdfTextExtractor}), o XLSX pelo {@link ZipInputStream} com as celulas resolvidas.
 *
 * <p><b>E por que a assercao do XLSX resolve {@code sharedStrings}:</b> medido no JAR — o
 * {@code Worksheet.value(r, c, String)} do fastexcel grava a string na <b>tabela compartilhada</b> e
 * deixa na planilha apenas {@code <c r="B12" t="s"><v>7</v></c>}, um indice. Um
 * {@code contains("Rosa Vermelha")} sobre {@code sheet1.xml} ficaria <b>vermelho contra a
 * implementacao correta</b>; e procurar o texto no ZIP inteiro casaria em qualquer lugar. Por isso as
 * celulas sao indexadas por <b>referencia</b> ({@code G12}) e a coluna e achada pelo <b>cabecalho</b>,
 * nunca por coordenada decorada: valor errado e coluna errada falham com mensagens <b>diferentes</b>.
 *
 * <p><b>Fixture com straddle de proposito:</b> o par estornado atravessa a fronteira do periodo, entao
 * o detalhado tem de perder a linha original (decisao do dono, 2026-09-17) e o contador tem de dizer
 * <b>1</b>. O total {@code 777,77} so existe nessa linha — e o <b>controle negativo</b> que fica
 * vermelho se alguem recortar o {@code NOT EXISTS} pelo periodo (§12 #22).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RelatorioExportTest {

    private static final String BASE = "/api/v1/relatorios/movimentacoes/export";

    private static final String SETEMBRO = "?de=2026-09-01&ate=2026-09-30";

    private static final String CT_XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-export-0123456789abcd");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbc;

    /** Pasta do JUnit — o arquivo temporario e do TESTE (o servidor nao grava nada, CA-28). */
    @TempDir
    private Path tmp;

    private String admin;
    private String user;
    private Long rosa;

    @BeforeEach
    void seed() {
        jdbc.update("TRUNCATE TABLE movimentacao_estoque");
        jdbc.update("DELETE FROM produto");
        jdbc.update("DELETE FROM usuario");
        admin = "Bearer " + jwtService.gerarToken(
                usuario("Ana Admin", "admin-exp@floricultura.local", "ADMIN"));
        user = "Bearer " + jwtService.gerarToken(
                usuario("Beto User", "user-exp@floricultura.local", "USER"));
        rosa = jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida, estoque_minimo, estoque_atual) "
                        + "VALUES ('Rosa Vermelha', 'un', 1, 0) RETURNING id", Long.class);
    }

    /** Fixture padrao: 1 saida com dinheiro, 1 entrada sem dinheiro (P6) e um straddle estornado. */
    private void semearRecorte() {
        comValor("SAIDA", "2026-09-12 09:00", "12", "2.125", "25.50", null);
        semValor("ENTRADA", "2026-09-18 11:00", "2");
        Long original = comValor("ENTRADA", "2026-09-05 10:00", "100", "7.7777", "777.77", null);
        // O estorno cai em OUTUBRO — fora do recorte. O NOT EXISTS e irrestrito e enxerga assim mesmo.
        comValor("SAIDA", "2026-10-03 08:00", "100", "7.7777", "777.77", original);
    }

    // ---- CA-26: o PDF e um documento de verdade ----------------------------------------------

    /**
     * CA-26 + §10 #11: assinatura, {@code %%EOF}, content-type, {@code Content-Disposition} <b>exato</b>
     * — e o documento <b>aberto</b>, com o texto extraido. Um HTML de erro com header forcado morre em
     * qualquer uma das quatro pernas.
     */
    @Test
    void pdfEhDocumentoDeVerdade() throws Exception {
        semearRecorte();

        MvcResult res = mockMvc.perform(get(BASE + SETEMBRO + "&formato=PDF")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"movimentacoes-2026-09-01_a_2026-09-30.pdf\""))
                .andReturn();
        byte[] corpo = res.getResponse().getContentAsByteArray();

        assertThat(corpo).startsWith("%PDF-".getBytes(StandardCharsets.US_ASCII));
        assertThat(new String(corpo, StandardCharsets.ISO_8859_1)).contains("%%EOF");
        PdfReader reader = new PdfReader(corpo);
        assertThat(reader.getNumberOfPages()).isGreaterThanOrEqualTo(1);
        String texto = new PdfTextExtractor(reader).getTextFromPage(1);
        reader.close();
        // Acentuacao: se a codificacao do OpenPDF quebrar, e AQUI que fica vermelho — nao no papel.
        // (Foi este assert que pegou o U+2212 sendo comido pelo Cp1252 na 1a execucao.)
        assertThat(texto).contains("Relatório de movimentações");
        assertThat(texto).contains("01/09/2026 a 30/09/2026");
        assertThat(texto).contains("Saídas - Entradas");
        // O rodape "Página X de Y" nasce em dois pedacos (o Y e um template preenchido no fim), entao
        // o espacamento e comparado depois de normalizado — o que importa e o par de numeros.
        assertThat(texto.replaceAll("\\s+", " "))
                .contains("Página 1 de " + reader.getNumberOfPages());
    }

    /**
     * §3.8/§4 #2: a linha sem dinheiro (P6) sai com {@code —} no PDF, que o dono distingue de
     * {@code 0,00} — e a linha com dinheiro sai formatada em pt-BR.
     */
    @Test
    void pdfMostraValorEmPtBrETracoQuandoNaoHaDinheiro() throws Exception {
        semearRecorte();

        String texto = textoDoPdf(baixar(SETEMBRO + "&formato=PDF", admin));

        assertThat(texto).contains("25,50");
        assertThat(texto).contains("—");
    }

    /**
     * <b>Decisao do dono (2026-09-17) + M-F:</b> a linha do par estornado NAO entra no detalhado, e a
     * omissao vai declarada. O {@code 777,77} so existe naquela linha — se o {@code NOT EXISTS} for
     * recortado pelo periodo, ele reaparece e este caso cai.
     */
    @Test
    void detalhadoNaoTrazLinhaDeParEstornadoEDeclaraAOmissao() throws Exception {
        semearRecorte();

        String texto = textoDoPdf(baixar(SETEMBRO + "&formato=PDF", admin));

        assertThat(texto).doesNotContain("777,77");
        assertThat(texto).contains("Lançamentos de pares estornados fora deste relatório");
        assertThat(texto).contains("25,50");
    }

    // ---- CA-27: o XLSX e um ZIP de verdade, e a celula certa tem o valor certo -----------------

    /** CA-27 + §10 #11: assinatura ZIP, content-type, nome e as entradas que fazem um XLSX. */
    @Test
    void xlsxEhZipDeVerdade() throws Exception {
        semearRecorte();

        MvcResult res = mockMvc.perform(get(BASE + SETEMBRO + "&formato=XLSX")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, CT_XLSX))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"movimentacoes-2026-09-01_a_2026-09-30.xlsx\""))
                .andReturn();
        byte[] corpo = res.getResponse().getContentAsByteArray();

        assertThat(corpo).startsWith(new byte[] {0x50, 0x4B, 0x03, 0x04});
        assertThat(entradasDoZip(corpo).keySet())
                .contains("[Content_Types].xml", "xl/worksheets/sheet1.xml");
    }

    /**
     * O valor esta na celula que cruza a <b>coluna "Total"</b> (achada pelo cabecalho) com a <b>linha
     * da SAIDA</b> (achada pelo conteudo da coluna "Tipo") — nunca uma coordenada decorada. Valor
     * errado falha com "expected 25.50"; coluna trocada falha com "celula ausente": mensagens
     * <b>diferentes</b>, que e o que prova que a assercao enxerga o erro em vez de passar de raspao.
     *
     * <p>E o outro lado, que so apareceu quando o teste rodou: a linha <b>sem dinheiro</b> (P6) nao
     * tem celula de Total <b>nenhuma</b> — vazio, e nao {@code 0,00}, para a SOMA do dono nao mentir.
     */
    @Test
    void xlsxTemOTotalNaCelulaQueCruzaColunaTotalComALinhaDaSaida() throws Exception {
        semearRecorte();

        Map<String, String> celulas = celulas(baixar(SETEMBRO + "&formato=XLSX", admin));

        String colunaTotal = coluna(exigirRef(celulas, "Total"));
        String alvo = colunaTotal + linhaComTipo(celulas, "SAIDA");
        assertThat(celulas).containsKey(alvo);
        assertThat(new BigDecimal(celulas.get(alvo)))
                .isEqualByComparingTo(new BigDecimal("25.50"));

        assertThat(celulas).doesNotContainKey(colunaTotal + linhaComTipo(celulas, "ENTRADA"));
    }

    /** O detalhado tem 2 linhas (a do par estornado ficou fora) e o contador declara 1. */
    @Test
    void xlsxTemSoAsLinhasQueSomaramEOContadorDeclaraAOmissao() throws Exception {
        semearRecorte();

        Map<String, String> celulas = celulas(baixar(SETEMBRO + "&formato=XLSX", admin));

        String cabecalho = exigirRef(celulas, "Data/hora");
        long linhasDeDados = celulas.keySet().stream()
                .filter(ref -> coluna(ref).equals(coluna(cabecalho)) && linha(ref) > linha(cabecalho))
                .count();
        assertThat(linhasDeDados).isEqualTo(2);
        String refDoContador =
                exigirRef(celulas, "Lançamentos de pares estornados fora deste relatório");
        assertThat(celulas.get(proximaColuna(refDoContador))).isEqualTo("1");
    }

    // ---- CA-28: no-store nos dois formatos ----------------------------------------------------

    /** CA-28: relatorio carrega nome de cliente/fornecedor (dado pessoal) — nao pode ir para cache. */
    @Test
    void cacheControlNoStoreNosDoisFormatos() throws Exception {
        semearRecorte();

        for (String formato : List.of("PDF", "XLSX")) {
            mockMvc.perform(get(BASE + SETEMBRO + "&formato=" + formato)
                            .header(HttpHeaders.AUTHORIZATION, admin))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        }
    }

    // ---- CA-29: formato e a MESMA validacao do §3.7 -------------------------------------------

    /** CA-29: ausente, fora do conjunto e em caixa baixa — os tres no mesmo 400 {@code field=formato}. */
    @Test
    void formatoAusenteOuInvalidoDevolve400FieldFormato() throws Exception {
        for (String query : List.of("", "&formato=", "&formato=CSV", "&formato=pdf")) {
            mockMvc.perform(get(BASE + SETEMBRO + query)
                            .header(HttpHeaders.AUTHORIZATION, admin))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details[0].field").value("formato"))
                    .andExpect(jsonPath("$.error.details[0].message")
                            .value("formato deve ser um de: PDF, XLSX"));
        }
    }

    /**
     * CA-54 no {@code /export}: periodo ausente e 400 {@code field=de} — <b>nunca</b> 500 — e vem
     * <b>antes</b> do {@code formato}, porque sem periodo nao ha o que exportar (AD-SQ-170).
     */
    @Test
    void exportSemPeriodoDevolve400DoPeriodoEAntesDoFormato() throws Exception {
        mockMvc.perform(get(BASE + "?ate=2026-09-30&formato=PDF")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("de"));

        // Sem periodo E sem formato: quem responde e o periodo, com os dois itens, `de` primeiro.
        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.length()").value(2))
                .andExpect(jsonPath("$.error.details[0].field").value("de"))
                .andExpect(jsonPath("$.error.details[1].field").value("ate"));
    }

    /** CA-29: os filtros invalidos devolvem os MESMOS 400 do §3.7 — teto de 366 dias e granularidade. */
    @Test
    void filtrosInvalidosDevolvemOsMesmos400DoRelatorio() throws Exception {
        mockMvc.perform(get(BASE + "?de=2025-01-01&ate=2026-06-30&formato=PDF")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("ate"))
                .andExpect(jsonPath("$.error.details[0].message")
                        .value("O intervalo do relatório não pode passar de 366 dias."));

        mockMvc.perform(get(BASE + SETEMBRO + "&granularidade=DIA&formato=PDF")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("granularidade"));
    }

    // ---- CA-30: o teto de 5 000 ---------------------------------------------------------------

    /** CA-30: acima do teto, 400 pedindo refino — e nenhum documento e montado. */
    @Test
    void acimaDoTetoDevolve400PedindoRefino() throws Exception {
        semearEmMassa(5001);

        mockMvc.perform(get(BASE + SETEMBRO + "&formato=XLSX")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("ate"))
                .andExpect(jsonPath("$.error.details[0].message")
                        .value("O relatório passou de 5.000 lançamentos. Refine o período ou os filtros."));
    }

    /** A fronteira do outro lado: exatamente 5 000 linhas <b>passa</b> (o teto e "mais de 5 000"). */
    @Test
    void noTetoExatoAindaExporta() throws Exception {
        semearEmMassa(5000);

        mockMvc.perform(get(BASE + SETEMBRO + "&formato=XLSX")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, CT_XLSX));
    }

    // ---- RBAC (§3.9): a rota herda o matcher de /relatorios/** --------------------------------

    /** CA-23 na perna do export: USER → 403, sem token → 401. */
    @Test
    void userNaoExportaESemTokenTambemNao() throws Exception {
        mockMvc.perform(get(BASE + SETEMBRO + "&formato=PDF")
                        .header(HttpHeaders.AUTHORIZATION, user))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(BASE + SETEMBRO + "&formato=PDF"))
                .andExpect(status().isUnauthorized());
    }

    // ---- Apoio ---------------------------------------------------------------------------------

    private byte[] baixar(String query, String bearer) throws Exception {
        return mockMvc.perform(get(BASE + query).header(HttpHeaders.AUTHORIZATION, bearer))
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
     * Entradas do ZIP por nome — e o que prova que o XLSX e um pacote OOXML, nao 4 bytes magicos.
     *
     * <p><b>Pela CENTRAL DIRECTORY ({@link ZipFile}), nao por {@link java.util.zip.ZipInputStream}</b>
     * — medido: o {@code ZipInputStream} estoura {@code invalid entry size (expected 0 but got 3508
     * bytes)} neste arquivo, porque o fastexcel/opczip escreve em <b>streaming</b> e deixa os tamanhos
     * do cabecalho local zerados. O Excel (e o {@code ZipFile}) leem a central directory, onde os
     * tamanhos e os CRCs estao corretos; o {@code ZipInputStream} le os cabecalhos locais em sequencia
     * e por isso e o leitor <b>errado</b> para julgar este arquivo. De quebra, o {@code ZipFile}
     * <b>confere o CRC</b> de cada entrada que lemos, o que torna a assercao mais forte, e nao mais
     * fraca.
     */
    private Map<String, byte[]> entradasDoZip(byte[] xlsx) throws Exception {
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
        return entradas;
    }

    /**
     * Celulas da planilha indexadas por <b>referencia</b> ({@code G12}), com {@code t="s"} resolvido
     * contra a tabela de strings compartilhadas — sem isso, todo texto viraria um indice numerico.
     */
    private Map<String, String> celulas(byte[] xlsx) throws Exception {
        Map<String, byte[]> entradas = entradasDoZip(xlsx);
        List<String> compartilhadas = stringsCompartilhadas(entradas.get("xl/sharedStrings.xml"));
        String planilha = new String(
                entradas.get("xl/worksheets/sheet1.xml"), StandardCharsets.UTF_8);
        Map<String, String> celulas = new LinkedHashMap<>();
        Matcher celula = Pattern.compile("<c r=\"([A-Z]+[0-9]+)\"([^>]*)>(.*?)</c>", Pattern.DOTALL)
                .matcher(planilha);
        while (celula.find()) {
            Matcher valor = Pattern.compile("<v>(.*?)</v>", Pattern.DOTALL).matcher(celula.group(3));
            if (!valor.find()) {
                continue;
            }
            boolean compartilhada = celula.group(2).contains("t=\"s\"");
            celulas.put(celula.group(1), compartilhada
                    ? compartilhadas.get(Integer.parseInt(valor.group(1)))
                    : valor.group(1));
        }
        return celulas;
    }

    private List<String> stringsCompartilhadas(byte[] xml) {
        List<String> textos = new ArrayList<>();
        if (xml == null) {
            return textos;
        }
        Matcher item = Pattern.compile("<si>.*?<t[^>]*>(.*?)</t>.*?</si>", Pattern.DOTALL)
                .matcher(new String(xml, StandardCharsets.UTF_8));
        while (item.find()) {
            textos.add(decodificar(item.group(1)));
        }
        return textos;
    }

    /**
     * O fastexcel escreve acento como <b>referencia numerica</b> ({@code Lan&#xe7;amentos}) — medido no
     * arquivo. Sem desfazer isso, comparar com {@code "Lançamentos…"} nunca casaria, e a assercao
     * falharia <b>pelo motivo errado</b> (leitor torto, nao documento torto).
     */
    private static String decodificar(String xml) {
        Matcher referencia = Pattern.compile("&#x([0-9A-Fa-f]+);").matcher(xml);
        StringBuilder texto = new StringBuilder();
        while (referencia.find()) {
            referencia.appendReplacement(texto, Matcher.quoteReplacement(
                    String.valueOf((char) Integer.parseInt(referencia.group(1), 16))));
        }
        referencia.appendTail(texto);
        return texto.toString()
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    /**
     * Referencia da celula cujo conteudo e exatamente {@code texto} — a ancora das assercoes. Falha
     * <b>alto</b> quando nao acha: ancora ausente tem de virar erro de teste, nunca {@code null}
     * silencioso passeando por assercoes seguintes.
     */
    private String exigirRef(Map<String, String> celulas, String texto) {
        return celulas.entrySet().stream()
                .filter(e -> texto.equals(e.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "celula com o texto '" + texto + "' nao existe na planilha"));
    }

    /** Linha do detalhado cuja coluna "Tipo" tem o valor pedido — ancora por conteudo, nao por indice. */
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

    /** Vizinha a direita, para ler o valor que acompanha um rotulo. */
    private static String proximaColuna(String ref) {
        char letra = (char) (coluna(ref).charAt(coluna(ref).length() - 1) + 1);
        return coluna(ref).substring(0, coluna(ref).length() - 1) + letra + linha(ref);
    }

    private Long usuario(String nome, String email, String role) {
        return jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, ?, true, false) RETURNING id",
                Long.class, nome, email, UUID.randomUUID().toString(), role);
    }

    private Long comValor(String tipo, String quando, String quantidade, String unitario,
            String total, Long estorna) {
        return jdbc.queryForObject(
                "INSERT INTO movimentacao_estoque (produto_id, produto_nome, tipo, quantidade, "
                        + "quantidade_resultante, usuario_nome, valor_unitario, total_bruto, "
                        + "total_final, estorna_movimentacao_id, criado_em) "
                        + "VALUES (?, 'Rosa Vermelha', ?, CAST(? AS numeric), 0, 'Ana Admin', "
                        + "CAST(? AS numeric), CAST(? AS numeric), CAST(? AS numeric), ?, "
                        + "CAST(? AS timestamp) AT TIME ZONE 'America/Sao_Paulo') RETURNING id",
                Long.class, rosa, tipo, quantidade, unitario, total, total, estorna, quando);
    }

    private Long semValor(String tipo, String quando, String quantidade) {
        return jdbc.queryForObject(
                "INSERT INTO movimentacao_estoque (produto_id, produto_nome, tipo, quantidade, "
                        + "quantidade_resultante, usuario_nome, criado_em) "
                        + "VALUES (?, 'Rosa Vermelha', ?, CAST(? AS numeric), 0, 'Ana Admin', "
                        + "CAST(? AS timestamp) AT TIME ZONE 'America/Sao_Paulo') RETURNING id",
                Long.class, rosa, tipo, quantidade, quando);
    }

    /** Semeia N linhas num INSERT so — o teto precisa de milhares, e o teste nao pode custar minutos. */
    private void semearEmMassa(int quantas) {
        jdbc.update(
                "INSERT INTO movimentacao_estoque (produto_id, produto_nome, tipo, quantidade, "
                        + "quantidade_resultante, usuario_nome, criado_em) "
                        + "SELECT ?, 'Rosa Vermelha', 'ENTRADA', 1, 0, 'Ana Admin', "
                        + "CAST('2026-09-10 10:00' AS timestamp) AT TIME ZONE 'America/Sao_Paulo' "
                        + "FROM generate_series(1, ?)",
                rosa, quantas);
    }
}
