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
import java.util.UUID;
import java.util.regex.Pattern;
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
 * Integracao dos filtros escalares e da ordenacao de {@code GET /produtos} (T-M6-05a, CA-19..CA-23 —
 * SPEC-M6 §3.6) contra um PostgreSQL de DESCARTE (Testcontainers postgres:16), com
 * {@code ProdutoController} e filtro JWT reais.
 *
 * <p>Prova: os 3 atalhos de estoque e o enum invalido (CA-19); faixa de preco inclusiva,
 * {@code semPreco} e os 400 de combinacao (CA-20); {@code NULLS LAST} nas <b>duas</b> direcoes,
 * ordenacao por estoque, default {@code nome asc}, {@code ordenarPor} invalido e o desempate
 * {@code id ASC} (CA-21); filtro x paginacao (CA-22); USER 200 + SQL da lista <b>sem</b> a coluna
 * {@code imagem} (CA-23). Mais: OU interno de {@code caracteristica}/{@code toxicidade}, E entre
 * dimensoes, o escape de {@code %}/{@code _} no filtro por nome e o Swagger da rota.
 *
 * <p>As fixtures de ordenacao sao inseridas em ordem <b>divergente</b> da esperada de proposito — com
 * a ordem fisica igual a esperada, o teste passaria mesmo sem {@code ORDER BY}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ProdutoFiltroApiTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private static final String PRODUTOS = "/api/v1/produtos";

    /** {@code imagem} como coluna, mas nao {@code imagem_url}/{@code imagem_content_type} (CA-23). */
    private static final Pattern COLUNA_BYTEA = Pattern.compile("imagem(?![_\\w])");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-produtofiltro-0123456789ab");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbc;

    private String userBearer;

    @BeforeEach
    void seed() {
        jdbc.update("TRUNCATE TABLE movimentacao_estoque");
        jdbc.update("DELETE FROM produto");
        jdbc.update("DELETE FROM usuario");
        Long userId = jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, 'USER', true, false) RETURNING id",
                Long.class, "Nome USER", "user-filtro@floricultura.local",
                ENCODER.encode(UUID.randomUUID().toString()));
        userBearer = "Bearer " + jwtService.gerarToken(userId);
    }

    // ---- Fixtures -----------------------------------------------------------------------------

    private void produto(String nome, String atual, String minimo, BigDecimal preco) {
        jdbc.update("INSERT INTO produto (nome, unidade_medida, estoque_atual, estoque_minimo, "
                + "preco) VALUES (?, 'un', CAST(? AS NUMERIC), CAST(? AS NUMERIC), ?)",
                nome, atual, minimo, preco);
    }

    private void botanico(String nome, String caracteristica, String toxicidade) {
        jdbc.update("INSERT INTO produto (nome, unidade_medida, caracteristica, toxicidade) "
                + "VALUES (?, 'un', ?, ?)", nome, caracteristica, toxicidade);
    }

    /** A(atual 0, min 5) · B(atual 3, min 5) · C(atual 20, min 5) — fixture do CA-19. */
    private void seedEstoque() {
        produto("Alecrim", "0", "5", null);
        produto("Begonia", "3", "5", null);
        produto("Copo de Leite", "20", "5", null);
    }

    /** Precos 10, 50 e {@code null}, inseridos na ordem INVERSA da esperada (CA-20/CA-21). */
    private void seedPreco() {
        produto("Cravo Sem Preco", "1", "0", null);
        produto("Azaleia Cara", "1", "0", new BigDecimal("50.00"));
        produto("Boca de Leao", "1", "0", new BigDecimal("10.00"));
    }

    private ResultActions esperaNomes(String query, String... nomes) throws Exception {
        ResultActions resultado = mockMvc.perform(get(PRODUTOS + "?" + query)
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(nomes.length));
        for (int i = 0; i < nomes.length; i++) {
            resultado.andExpect(jsonPath("$.data.conteudo[" + i + "].nome").value(nomes[i]));
        }
        return resultado;
    }

    /** Igual ao {@link #esperaNomes}, mas com o termo passado como PARAMETRO (nao na query string):
     * assim o {@code %} chega cru ao controller, sem depender de codificacao de URL no teste. */
    private void esperaPorNome(String termo, String... nomes) throws Exception {
        ResultActions resultado = mockMvc.perform(get(PRODUTOS).param("nome", termo)
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(nomes.length));
        for (int i = 0; i < nomes.length; i++) {
            resultado.andExpect(jsonPath("$.data.conteudo[" + i + "].nome").value(nomes[i]));
        }
    }

    private void espera400(String query, String campo) throws Exception {
        mockMvc.perform(get(PRODUTOS + "?" + query).header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value(campo));
    }

    // ---- CA-19: atalhos de estoque ------------------------------------------------------------

    @Test
    void estoque_tresAtalhos_traduzemOsPredicadosDoContrato() throws Exception {
        seedEstoque();

        esperaNomes("estoque=SEM_ESTOQUE", "Alecrim");
        // BAIXO = atual <= minimo (mesma regra do selo estoqueBaixo/FC-13) — INCLUI o zero.
        esperaNomes("estoque=BAIXO", "Alecrim", "Begonia");
        esperaNomes("estoque=COM_ESTOQUE", "Begonia", "Copo de Leite");
    }

    @Test
    void estoque_foraDoEnum_devolve400ComFieldEstoque() throws Exception {
        espera400("estoque=QUALQUER", "estoque");
    }

    @Test
    void estoque_combinaEComNome() throws Exception {
        seedEstoque();

        esperaNomes("estoque=BAIXO&nome=be", "Begonia");
    }

    // ---- CA-20: faixa de preco e semPreco -----------------------------------------------------

    @Test
    void preco_faixaEInclusivaEDeixaOSemPrecoDeFora() throws Exception {
        seedPreco();

        esperaNomes("precoMin=10&precoMax=50", "Azaleia Cara", "Boca de Leao");
        esperaNomes("precoMin=11&precoMax=49");
        esperaNomes("precoMax=10", "Boca de Leao");
    }

    @Test
    void semPreco_trazSoOProdutoSemPrecoDefinido() throws Exception {
        seedPreco();

        esperaNomes("semPreco=true", "Cravo Sem Preco");
    }

    @Test
    void preco_combinacoesInvalidas_devolvem400ComOFieldCerto() throws Exception {
        espera400("semPreco=true&precoMax=50", "semPreco");
        espera400("precoMin=60&precoMax=10", "precoMin");
        espera400("precoMin=-1", "precoMin");
    }

    // ---- CA-21: ordenacao, NULLS LAST nas duas direcoes e desempate estavel -------------------

    @Test
    void ordenarPorPreco_poeOSemPrecoNoFimNasDuasDirecoes() throws Exception {
        seedPreco();

        esperaNomes("ordenarPor=preco&direcao=desc",
                "Azaleia Cara", "Boca de Leao", "Cravo Sem Preco");
        esperaNomes("ordenarPor=preco&direcao=asc",
                "Boca de Leao", "Azaleia Cara", "Cravo Sem Preco");
    }

    @Test
    void ordenarPorEstoque_usaEstoqueAtual_eSemParamsUsaNomeAsc() throws Exception {
        seedEstoque();

        esperaNomes("ordenarPor=estoque&direcao=asc", "Alecrim", "Begonia", "Copo de Leite");
        esperaNomes("ordenarPor=estoque&direcao=desc", "Copo de Leite", "Begonia", "Alecrim");
        esperaNomes("pagina=0", "Alecrim", "Begonia", "Copo de Leite");
        esperaNomes("ordenarPor=nome&direcao=desc", "Copo de Leite", "Begonia", "Alecrim");
    }

    @Test
    void ordenacaoForaDoContrato_devolve400() throws Exception {
        espera400("ordenarPor=cor", "ordenarPor");
        espera400("ordenarPor=preco&direcao=cima", "direcao");
    }

    @Test
    void empateDeNome_desempataPorIdAsc_emQualquerOrdemFisica() throws Exception {
        // Ids EXPLICITOS fora de ordem: a ordem fisica (900, 100, 500) diverge da esperada por id.
        // Sem o desempate `id ASC` a sequencia abaixo nao teria garantia nenhuma (§12 #6).
        jdbc.update("INSERT INTO produto (id, nome, unidade_medida) VALUES (900, 'Musgo', 'un'), "
                + "(100, 'Musgo', 'un'), (500, 'Musgo', 'un')");

        for (int chamada = 0; chamada < 2; chamada++) {
            mockMvc.perform(get(PRODUTOS + "?ordenarPor=nome&direcao=asc")
                            .header(HttpHeaders.AUTHORIZATION, userBearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.conteudo[0].id").value(100))
                    .andExpect(jsonPath("$.data.conteudo[1].id").value(500))
                    .andExpect(jsonPath("$.data.conteudo[2].id").value(900));
        }
    }

    // ---- CA-22: filtro x paginacao ------------------------------------------------------------

    @Test
    void filtroComPaginacao_totalRefleteOFiltro_ePaginaAlemDoTotalVemVazia() throws Exception {
        for (int i = 1; i <= 30; i++) {
            botanico(String.format("Planta %02d", i), null, i <= 5 ? "TOXICA" : null);
        }

        mockMvc.perform(get(PRODUTOS + "?toxicidade=TOXICA&tamanho=2&pagina=0")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(5))
                .andExpect(jsonPath("$.data.totalPaginas").value(3))
                .andExpect(jsonPath("$.data.conteudo.length()").value(2));
        mockMvc.perform(get(PRODUTOS + "?toxicidade=TOXICA&tamanho=2&pagina=9")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conteudo").isEmpty())
                .andExpect(jsonPath("$.data.ultima").value(true));
    }

    // ---- Dimensoes escalares multivaloradas: OU interno, E entre dimensoes, 400 ---------------

    @Test
    void caracteristicaEToxicidade_ouInterno_eEntreDimensoes() throws Exception {
        botanico("Samambaia Muda", "MUDA", "NAO_TOXICA");
        botanico("Espada Adulta", "ADULTA", "TOXICA");
        botanico("Jiboia Jovem", "JOVEM", "TOXICA");

        esperaNomes("caracteristica=MUDA&caracteristica=ADULTA", "Espada Adulta", "Samambaia Muda");
        esperaNomes("toxicidade=TOXICA", "Espada Adulta", "Jiboia Jovem");
        esperaNomes("caracteristica=MUDA&caracteristica=ADULTA&toxicidade=TOXICA", "Espada Adulta");
    }

    @Test
    void enumsMultivaloradosForaDoContrato_devolvem400() throws Exception {
        espera400("caracteristica=BROTO", "caracteristica");
        espera400("toxicidade=talvez", "toxicidade");
    }

    // ---- Anti-regressao do filtro por nome: wildcard do usuario e LITERAL ---------------------

    @Test
    void filtroPorNome_escapaOsWildcardsDoTermo() throws Exception {
        produto("Rosa 50% Desconto", "1", "0", null);
        produto("Zinia 500 Unidades", "1", "0", null);
        produto("Kit_Festa", "1", "0", null);
        produto("KitXFesta", "1", "0", null);

        // Sem ESCAPE, "50%" viraria o padrao %50%% e casaria tambem "Zinia 500 Unidades"; "_"
        // casaria qualquer caractere e traria "KitXFesta". O termo do usuario e LITERAL — era o que
        // a derived query `findByNomeContainingIgnoreCase` dava de graca ate esta task.
        esperaPorNome("50%", "Rosa 50% Desconto");
        esperaPorNome("Kit_F", "Kit_Festa");
        esperaPorNome("50", "Rosa 50% Desconto", "Zinia 500 Unidades");
    }

    // ---- CA-23: leitura USER + a lista filtrada NAO materializa o bytea ----------------------

    @Test
    void listaFiltrada_paraUser_devolve200_eOSqlNaoSelecionaAColunaImagem() throws Exception {
        seedPreco();
        Logger sql = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        ListAppender<ILoggingEvent> capturado = new ListAppender<>();
        capturado.start();
        Level nivelAnterior = sql.getLevel();
        sql.setLevel(Level.DEBUG);
        sql.addAppender(capturado);

        String corpo;
        try {
            corpo = mockMvc.perform(get(PRODUTOS + "?precoMin=10&ordenarPor=preco&direcao=desc")
                            .header(HttpHeaders.AUTHORIZATION, userBearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElementos").value(2))
                    .andReturn().getResponse().getContentAsString();
        } finally {
            sql.detachAppender(capturado);
            sql.setLevel(nivelAnterior);
        }

        String selects = capturado.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("from produto "))
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(selects).contains("from produto ");
        // `imagem_url`/`imagem_content_type` sao permitidos; a coluna `imagem` (bytea) nunca (AD-SQ-38).
        assertThat(COLUNA_BYTEA.matcher(selects).find()).isFalse();
        assertThat(corpo).doesNotContain("\"imagem\":");
    }

    // ---- Swagger da rota alterada (exigencia permanente do dono) -----------------------------

    @Test
    void swagger_documentaOsFiltrosEAOrdenacaoDeProdutos() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/produtos'].get.summary").isNotEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/produtos'].get.parameters[*].name",
                        hasItems("estoque", "precoMin", "precoMax", "semPreco", "caracteristica",
                                "toxicidade", "ordenarPor", "direcao")));
    }
}
