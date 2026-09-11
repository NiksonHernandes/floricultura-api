package com.floricultura.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.floricultura.api.service.JwtService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integracao dos filtros MULTIVALORADOS por juncao de {@code GET /produtos} — {@code corIds},
 * {@code luz} e {@code eventoIds} (T-M6-05b, CA-17/CA-18 — SPEC-M6 §3.6) — contra um PostgreSQL de
 * DESCARTE (Testcontainers postgres:16), com {@code ProdutoController} e filtro JWT reais.
 *
 * <p>Prova, nesta ordem de importancia:
 * <ol>
 *   <li><b>{@code totalElementos} sob N:N</b> — o produto que casa por <b>2</b> cores (ou 2 luzes, ou
 *       2 eventos) aparece <b>uma</b> vez e conta <b>um</b>. E o teste que reprova um {@code JOIN}
 *       disfarcado: com juncao a linha duplicaria e o {@code count} iria a 4 (§12 #5);</li>
 *   <li><b>E entre dimensoes / OU dentro da dimensao</b> (P4/CA-17/CA-18), nos tres eixos;</li>
 *   <li>o SQL emitido contem {@code exists} e <b>nao</b> junta {@code produto_cor} — inspecao direta,
 *       nao so pelo efeito;</li>
 *   <li>a ordenacao da T-M6-05a (NULLS LAST + desempate {@code id ASC}) sobrevive ao filtro por
 *       juncao, com as fixtures inseridas em ordem <b>divergente</b> da esperada;</li>
 *   <li>a listagem <b>sem</b> filtro nao toca as tabelas de juncao (as colecoes mapeadas para o
 *       {@code EXISTS} sao LAZY: nenhum N+1, nenhum bytea — AD-SQ-38/FC-09).</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ProdutoFiltroMultivaloradoApiTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private static final String PRODUTOS = "/api/v1/produtos";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-multivalorado-0123456789ab");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbc;

    private String userBearer;

    private Long rosa;
    private Long azul;

    @BeforeEach
    void seed() {
        jdbc.update("TRUNCATE TABLE movimentacao_estoque");
        // Produto cascateia produto_cor / produto_necessidade_luz / evento_produto (V6/V12); a cor so
        // pode sair depois (FK RESTRICT).
        jdbc.update("DELETE FROM produto");
        jdbc.update("DELETE FROM cor");
        jdbc.update("DELETE FROM evento");
        jdbc.update("DELETE FROM usuario");
        Long userId = jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, 'USER', true, false) RETURNING id",
                Long.class, "Nome USER", "user-multivalorado@floricultura.local",
                ENCODER.encode(UUID.randomUUID().toString()));
        userBearer = "Bearer " + jwtService.gerarToken(userId);
        rosa = cor("ROSA");
        azul = cor("AZUL");
    }

    // ---- Fixtures -----------------------------------------------------------------------------

    private Long cor(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO cor (nome) VALUES (?) RETURNING id", Long.class, nome);
    }

    private Long produto(String nome, BigDecimal preco) {
        return jdbc.queryForObject("INSERT INTO produto (nome, unidade_medida, preco) "
                + "VALUES (?, 'un', ?) RETURNING id", Long.class, nome, preco);
    }

    private Long evento(String nome) {
        return jdbc.queryForObject("INSERT INTO evento (nome, data_inicio, tipo) "
                + "VALUES (?, DATE '2026-12-25', 'COMEMORATIVA') RETURNING id", Long.class, nome);
    }

    private void pintar(Long produtoId, Long... corIds) {
        for (Long corId : corIds) {
            jdbc.update("INSERT INTO produto_cor (produto_id, cor_id) VALUES (?, ?)",
                    produtoId, corId);
        }
    }

    private void iluminar(Long produtoId, String... luzes) {
        for (String luz : luzes) {
            jdbc.update("INSERT INTO produto_necessidade_luz (produto_id, luz) VALUES (?, ?)",
                    produtoId, luz);
        }
    }

    private void vincular(Long produtoId, Long... eventoIds) {
        for (Long eventoId : eventoIds) {
            jdbc.update("INSERT INTO evento_produto (evento_id, produto_id) VALUES (?, ?)",
                    eventoId, produtoId);
        }
    }

    /**
     * Fixture do CA-17/CA-18, endurecida: <b>P1 tem as DUAS cores</b> do filtro (Rosa e Azul) — e a
     * linha que um {@code JOIN} duplicaria. P2 (Rosa, Sol pleno) e P3 (Azul, Sombra) completam o
     * quadro da semantica E/OU.
     */
    private void seedCores() {
        Long p1 = produto("P1 Rosa e Azul Sombra", null);
        Long p2 = produto("P2 Rosa Sol", null);
        Long p3 = produto("P3 Azul Sombra", null);
        pintar(p1, rosa, azul);
        pintar(p2, rosa);
        pintar(p3, azul);
        iluminar(p1, "SOMBRA");
        iluminar(p2, "SOL_PLENO", "MEIA_SOMBRA");
        iluminar(p3, "SOMBRA");
    }

    private ResultActions esperaNomes(String query, String... nomes) throws Exception {
        ResultActions resultado = mockMvc.perform(get(PRODUTOS + "?" + query)
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(nomes.length))
                .andExpect(jsonPath("$.data.conteudo.length()").value(nomes.length));
        for (int i = 0; i < nomes.length; i++) {
            resultado.andExpect(jsonPath("$.data.conteudo[" + i + "].nome").value(nomes[i]));
        }
        return resultado;
    }

    // ---- CA-17: E entre dimensoes diferentes --------------------------------------------------

    @Test
    void corELuz_combinamComE_entreDimensoesDiferentes() throws Exception {
        seedCores();

        esperaNomes("corIds=" + rosa + "&luz=SOMBRA", "P1 Rosa e Azul Sombra");
        esperaNomes("corIds=" + azul + "&luz=SOL_PLENO");
    }

    // ---- CA-18: OU dentro da dimensao, SEM duplicar a linha do produto -----------------------

    @Test
    void corIds_ouInterno_naoDuplicaOProdutoQueCasaPorDuasCores() throws Exception {
        seedCores();

        // P1 casa por ROSA **e** por AZUL: com JOIN viria 2x e totalElementos seria 4.
        esperaNomes("corIds=" + rosa + "&corIds=" + azul,
                "P1 Rosa e Azul Sombra", "P2 Rosa Sol", "P3 Azul Sombra");
    }

    @Test
    void luzes_ouInterno_naoDuplicaOProdutoQueCasaPorDuasLuzes() throws Exception {
        seedCores();

        // P2 tem SOL_PLENO e MEIA_SOMBRA: casa duas vezes o IN, conta uma.
        esperaNomes("luz=SOL_PLENO&luz=MEIA_SOMBRA", "P2 Rosa Sol");
        esperaNomes("luz=SOMBRA&luz=SOL_PLENO",
                "P1 Rosa e Azul Sombra", "P2 Rosa Sol", "P3 Azul Sombra");
    }

    @Test
    void eventoIds_ouInterno_naoDuplicaOProdutoVinculadoADoisEventos() throws Exception {
        Long natal = evento("Natal");
        Long finados = evento("Finados");
        Long p1 = produto("Poinsetia", null);
        Long p2 = produto("Crisantemo", null);
        produto("Avulso", null);
        vincular(p1, natal, finados);
        vincular(p2, finados);

        esperaNomes("eventoIds=" + natal + "&eventoIds=" + finados, "Crisantemo", "Poinsetia");
        esperaNomes("eventoIds=" + natal, "Poinsetia");
        // E entre dimensoes: evento + cor.
        pintar(p1, azul);
        esperaNomes("eventoIds=" + finados + "&corIds=" + azul, "Poinsetia");
    }

    @Test
    void totalElementosEPaginacao_naoInflamComOProdutoDeDuasCores() throws Exception {
        seedCores();

        // 3 produtos, 4 linhas em produto_cor casando o filtro: o total tem de ser 3 (2 paginas).
        mockMvc.perform(get(PRODUTOS + "?corIds=" + rosa + "&corIds=" + azul + "&tamanho=2")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(3))
                .andExpect(jsonPath("$.data.totalPaginas").value(2))
                .andExpect(jsonPath("$.data.conteudo.length()").value(2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM produto_cor", Integer.class))
                .isEqualTo(4);
    }

    // ---- O predicado e EXISTS, nao JOIN (§3.6/§12 #5) — inspecao do SQL ----------------------

    @Test
    void sqlDoFiltroPorCor_usaExistsCorrelacionado_eNaoJuntaAProdutoCor() throws Exception {
        seedCores();

        // `tamanho=2` e deliberado: com a pagina cabendo inteira o Spring Data DEDUZ o total e nao
        // emite a query de contagem — e este teste precisa justamente inspecionar o count.
        String sql = capturandoSql(() -> mockMvc.perform(
                        get(PRODUTOS + "?tamanho=2&corIds=" + rosa + "&corIds=" + azul)
                                .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(3)));

        assertThat(sql).contains("exists").contains("produto_cor");
        assertThat(sql).doesNotContain("join produto_cor");
        // A contagem usa o MESMO predicado: sem exists no count, o total divergiria da pagina.
        assertThat(sql.lines().filter(l -> l.contains("count(")).toList())
                .isNotEmpty()
                .allSatisfy(count -> assertThat(count).contains("exists"));
    }

    @Test
    void listagemSemFiltro_naoTocaAsTabelasDeJuncao_nemMaterializaOBytea() throws Exception {
        seedCores();

        String sql = capturandoSql(() -> mockMvc.perform(get(PRODUTOS)
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(3)));

        // Colecoes LAZY: nenhum SELECT em produto_cor/produto_necessidade_luz e nenhum N+1.
        assertThat(sql).doesNotContain("produto_cor").doesNotContain("produto_necessidade_luz");
        assertThat(sql).doesNotContain("p1_0.imagem,").doesNotContain(".imagem ");
    }

    // ---- Erros e ids inexistentes ------------------------------------------------------------

    @Test
    void luzForaDoEnum_devolve400ComFieldLuz_eIdInexistenteApenasNaoCasa() throws Exception {
        seedCores();

        mockMvc.perform(get(PRODUTOS + "?luz=PENUMBRA")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("luz"));
        // Cor/evento inexistentes NAO sao erro de contrato (§3.6): 200 com lista vazia.
        esperaNomes("corIds=999999");
        esperaNomes("eventoIds=999999");
    }

    // ---- A ordenacao da 05a sobrevive ao filtro por juncao (CA-21 + CA-17) -------------------

    @Test
    void ordenacaoPorPreco_mantemNullsLastEDesempate_sobFiltroDeCor() throws Exception {
        // Inseridos em ordem DIVERGENTE da esperada e com ids explicitos fora de ordem: sem o
        // ORDER BY (ou sem o desempate id ASC) a sequencia abaixo nao teria garantia nenhuma.
        jdbc.update("INSERT INTO produto (id, nome, unidade_medida, preco) VALUES "
                + "(900, 'Musgo', 'un', NULL), (100, 'Musgo', 'un', NULL), "
                + "(500, 'Dalia', 'un', 50.00), (300, 'Cravo', 'un', 10.00)");
        pintar(900L, rosa, azul);
        pintar(100L, azul);
        pintar(500L, rosa);
        pintar(300L, rosa, azul);

        esperaNomes("corIds=" + rosa + "&corIds=" + azul + "&ordenarPor=preco&direcao=desc",
                "Dalia", "Cravo", "Musgo", "Musgo")
                .andExpect(jsonPath("$.data.conteudo[2].id").value(100))
                .andExpect(jsonPath("$.data.conteudo[3].id").value(900));
        esperaNomes("corIds=" + rosa + "&corIds=" + azul + "&ordenarPor=preco&direcao=asc",
                "Cravo", "Dalia", "Musgo", "Musgo")
                .andExpect(jsonPath("$.data.conteudo[2].id").value(100))
                .andExpect(jsonPath("$.data.conteudo[3].id").value(900));
    }

    // ---- Swagger dos params novos (exigencia permanente do dono) -----------------------------

    @Test
    void swagger_documentaOsFiltrosMultivaloradosPorJuncao() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/produtos'].get.parameters[*].name",
                        hasItems("corIds", "luz", "eventoIds")));
    }

    // ---- Infra do teste -----------------------------------------------------------------------

    /** Executa a chamada com o log de SQL do Hibernate ligado e devolve o capturado (minusculo). */
    private String capturandoSql(Chamada chamada) throws Exception {
        Logger sql = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        ListAppender<ILoggingEvent> capturado = new ListAppender<>();
        capturado.start();
        Level nivelAnterior = sql.getLevel();
        sql.setLevel(Level.DEBUG);
        sql.addAppender(capturado);
        try {
            chamada.executar();
        } finally {
            sql.detachAppender(capturado);
            sql.setLevel(nivelAnterior);
        }
        List<String> linhas = capturado.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .map(m -> m.toLowerCase(Locale.ROOT).replaceAll("\\s+", " "))
                .toList();
        assertThat(linhas).isNotEmpty();
        return String.join("\n", linhas);
    }

    @FunctionalInterface
    private interface Chamada {
        void executar() throws Exception;
    }
}
