package com.floricultura.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.floricultura.api.service.JwtService;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * Os dois invariantes do export que o QA achou <b>sem caso executavel</b> (BUG-003 e BUG-005): o
 * documento nao toca o disco, e o papel <b>fecha consigo mesmo</b>.
 *
 * <p><b>Arquivo NOVO:</b> {@code RelatorioExportTest} e {@code RelatorioExportConteudoTest} ja estao
 * rastreados e o hook anti-burla os protege ate do autor (§12 #27). Caso novo em arquivo novo e
 * adicao pura — nenhum caso herdado tocado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RelatorioExportInvariantesTest {

    private static final String BASE = "/api/v1/relatorios/movimentacoes/export";

    private static final String SETEMBRO = "?de=2026-09-01&ate=2026-09-30";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-invariante-01234567");
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
    private Long rosa;

    @BeforeEach
    void seed() {
        jdbc.update("TRUNCATE TABLE movimentacao_estoque");
        jdbc.update("DELETE FROM produto");
        jdbc.update("DELETE FROM usuario");
        admin = "Bearer " + jwtService.gerarToken(
                usuario("Ana Admin", "admin-inv@floricultura.local", "ADMIN"));
        rosa = jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida, estoque_minimo, estoque_atual) "
                        + "VALUES ('Rosa Vermelha', 'un', 1, 0) RETURNING id", Long.class);
        // Dinheiro dos dois lados + uma linha sem dinheiro (P6) + um par estornado que ATRAVESSA a
        // fronteira: a linha de 777,77 fica fora do detalhado E fora do resumo, nos dois.
        lancamento("ENTRADA", "2026-09-05 10:00", "3500.00", null);
        lancamento("SAIDA", "2026-09-12 09:00", "4700.00", null);
        lancamento("AJUSTE", "2026-09-18 11:00", null, null);
        Long original = lancamento("ENTRADA", "2026-09-20 08:00", "777.77", null);
        lancamento("SAIDA", "2026-10-03 08:00", "777.77", original);
    }

    // ---- BUG-003: nada em disco, provado pelo EFEITO -----------------------------------------

    /**
     * <b>CA-28, a metade do disco.</b> A prova nao e um {@code grep} no fonte — grep nao executa nada e
     * ficaria verde com o servico trocado por {@code createTempFile} amanha. Aqui o JDK Flight
     * Recorder grava os eventos {@code jdk.FileWrite} <b>reais</b> da JVM e o caso confere que, na
     * thread que atende a requisicao, <b>nenhum</b> arquivo foi escrito.
     *
     * <p><b>O instrumento e provado vivo no mesmo intervalo</b> (senao "zero eventos" tambem seria o
     * resultado de uma gravacao que nao gravou — falso-verde classico): o proprio caso escreve um
     * arquivo-isca depois do export, e exige que a isca <b>apareca</b>. Ou seja: a mesma janela que
     * afirma "o export nao escreveu" demonstra que ela <b>enxergaria</b> se tivesse escrito.
     */
    @Test
    @DisplayName("BUG-003: gerar PDF e XLSX nao escreve UM arquivo sequer (JFR, com isca de controle)")
    void exportNaoEscreveArquivoEmDisco() throws Exception {
        baixar("&formato=PDF");   // aquecimento: carga de classe/fonte fica fora da janela medida
        baixar("&formato=XLSX");
        Path isca = tmp.resolve("isca-de-controle.bin");

        Set<String> escritos;
        try (Recording gravacao = new Recording()) {
            gravacao.enable("jdk.FileWrite").withoutThreshold();
            gravacao.start();
            baixar("&formato=PDF");
            baixar("&formato=XLSX");
            Files.write(isca, new byte[] {1, 2, 3});
            gravacao.stop();
            Path dump = tmp.resolve("gravacao.jfr");
            gravacao.dump(dump);
            escritos = arquivosEscritosNestaThread(dump);
        }

        assertThat(escritos)
                .as("a isca prova que a gravacao estava viva — sem ela, 'zero' nao significaria nada")
                .anyMatch(caminho -> caminho.endsWith("isca-de-controle.bin"));
        assertThat(escritos)
                .as("nenhum arquivo alem da isca: o documento nasce e morre em memoria (CA-28/§9)")
                .allMatch(caminho -> caminho.endsWith("isca-de-controle.bin"));
    }

    // ---- BUG-005: o papel fecha consigo mesmo -------------------------------------------------

    /**
     * <b>§3.8-a, a frase que decidiu a questao com o dono:</b> "somar a coluna Total na calculadora
     * daria numero diferente do resumo impresso na mesma folha".
     *
     * <p><b>Os dois lados da conta saem do DOCUMENTO</b> — a soma vem das celulas da coluna "Total" do
     * detalhado e o total vem das celulas de "Valor (R$)" do resumo, ambas achadas pelo cabecalho.
     * Nada e recalculado com a logica do servico: somar no teste do mesmo jeito que o servico soma
     * provaria que o codigo concorda consigo mesmo, que nao e o que o contador precisa.
     */
    @Test
    @DisplayName("BUG-005: a soma da coluna Total impressa bate com o resumo impresso na mesma folha")
    void somaDaColunaTotalFechaComOResumoImpresso() throws Exception {
        Map<String, String> celulas = celulas(baixar("&formato=XLSX"));

        BigDecimal somaDoDetalhado = somaDaColuna(celulas, "Total");
        BigDecimal totalDoResumo = valorDoResumo(celulas, "Entradas")
                .add(valorDoResumo(celulas, "Saídas"))
                .add(valorDoResumo(celulas, "Ajustes"));

        assertThat(somaDoDetalhado)
                .as("papel que nao fecha consigo mesmo e pior que papel curto")
                .isEqualByComparingTo(totalDoResumo);
        // E o numero nao pode ser zero dos dois lados por acidente (identidade trivial nao prova nada).
        assertThat(somaDoDetalhado).isEqualByComparingTo(new BigDecimal("8200.00"));
        // A linha do par estornado ficou fora dos DOIS lados — e o que mantem a identidade verdadeira.
        assertThat(celulas.values()).doesNotContain("777.77");
    }

    // ---- Apoio ---------------------------------------------------------------------------------

    /** Caminhos escritos NESTA thread durante a gravacao — o filtro por thread e o que atribui a culpa. */
    private Set<String> arquivosEscritosNestaThread(Path dump) throws Exception {
        String minhaThread = Thread.currentThread().getName();
        Set<String> caminhos = new TreeSet<>();
        try (RecordingFile arquivo = new RecordingFile(dump)) {
            while (arquivo.hasMoreEvents()) {
                RecordedEvent evento = arquivo.readEvent();
                if (!"jdk.FileWrite".equals(evento.getEventType().getName())) {
                    continue;
                }
                if (evento.getThread() != null
                        && minhaThread.equals(evento.getThread().getJavaName())) {
                    caminhos.add(evento.getString("path"));
                }
            }
        }
        return caminhos;
    }

    /** Soma as celulas de uma coluna do detalhado, achada pelo texto do cabecalho. */
    private BigDecimal somaDaColuna(Map<String, String> celulas, String cabecalho) {
        String ref = exigirRef(celulas, cabecalho);
        BigDecimal soma = BigDecimal.ZERO;
        for (Map.Entry<String, String> celula : celulas.entrySet()) {
            if (coluna(celula.getKey()).equals(coluna(ref)) && linha(celula.getKey()) > linha(ref)) {
                soma = soma.add(new BigDecimal(celula.getValue()));
            }
        }
        return soma;
    }

    /** Valor impresso no bloco de resumo, no cruzamento do rotulo com a coluna "Valor (R$)". */
    private BigDecimal valorDoResumo(Map<String, String> celulas, String rotulo) {
        String refDoRotulo = exigirRef(celulas, rotulo);
        String colunaValor = coluna(exigirRef(celulas, "Valor (R$)"));
        String alvo = colunaValor + linha(refDoRotulo);
        assertThat(celulas).containsKey(alvo);
        return new BigDecimal(celulas.get(alvo));
    }

    private byte[] baixar(String formato) throws Exception {
        return mockMvc.perform(get(BASE + SETEMBRO + formato)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

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

    private Long lancamento(String tipo, String quando, String total, Long estorna) {
        return jdbc.queryForObject(
                "INSERT INTO movimentacao_estoque (produto_id, produto_nome, tipo, quantidade, "
                        + "quantidade_resultante, usuario_nome, valor_unitario, total_bruto, "
                        + "total_final, estorna_movimentacao_id, criado_em) "
                        + "VALUES (?, 'Rosa Vermelha', ?, 1, 0, 'Ana Admin', "
                        + "CAST(? AS numeric), CAST(? AS numeric), CAST(? AS numeric), ?, "
                        + "CAST(? AS timestamp) AT TIME ZONE 'America/Sao_Paulo') RETURNING id",
                Long.class, rosa, tipo, total, total, total, estorna, quando);
    }
}
