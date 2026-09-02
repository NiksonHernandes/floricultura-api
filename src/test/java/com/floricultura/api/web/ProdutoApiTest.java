package com.floricultura.api.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.floricultura.api.service.JwtService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Fatia de LEITURA de produtos do M2 (T-M2-2, CA-2/CA-3/CA-4/CA-5/CA-15) contra um PostgreSQL de
 * DESCARTE (Testcontainers postgres:16), com {@code ProdutoController}/{@code ProdutoService},
 * {@code SecurityConfig} endurecido e filtro JWT reais no contexto. Os tokens ADMIN/USER sao emitidos
 * pelo {@link JwtService} (mesmo bean/segredo do filtro) — o RBAC de leitura ({@code GET /produtos/**}
 * = qualquer autenticado; sem token → 401) e exercitado de ponta a ponta.
 *
 * <p>Nome {@code *Test} (Surefire): o repo <b>nao</b> configura Failsafe — todas as integracoes
 * Testcontainers ({@code AuthSecurityTest}, {@code UsuarioAdminTest}, {@code V3V4MigrationTest}) usam
 * {@code *Test} para rodarem sob o Surefire no {@code verify}. Um {@code *IT} ficaria fora do {@code
 * clean verify} (§10 #1). <b>Sem segredo versionado (§9):</b> os {@code senha_hash} de seed sao BCrypt
 * de {@link UUID} aleatorios de runtime.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ProdutoApiTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Segredo test-only (NAO-segredo; >= 32 bytes) do JwtService que emite os tokens do teste.
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-produtoapi-0123456789ab");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminBearer;
    private String userBearer;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM movimentacao_estoque");
        jdbc.update("DELETE FROM produto");
        jdbc.update("DELETE FROM usuario");
        Long adminId = inserirUsuario("admin-prod@floricultura.local", "ADMIN");
        Long userId = inserirUsuario("user-prod@floricultura.local", "USER");
        adminBearer = "Bearer " + jwtService.gerarToken(adminId);
        userBearer = "Bearer " + jwtService.gerarToken(userId);
    }

    private Long inserirUsuario(String email, String role) {
        return jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, ?, true, false) RETURNING id",
                Long.class, "Nome " + role, email,
                ENCODER.encode(UUID.randomUUID().toString()), role);
    }

    /** Insere um produto com estoque_atual/minimo e preco definidos; devolve o id gerado. */
    private Long inserirProduto(
            String nome, String unidade, String estoqueMin, String estoqueAtual, String preco) {
        return jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida, estoque_minimo, estoque_atual, preco) "
                        + "VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class, nome, unidade,
                new java.math.BigDecimal(estoqueMin), new java.math.BigDecimal(estoqueAtual),
                preco == null ? null : new java.math.BigDecimal(preco));
    }

    // ---- CA-2: RBAC de leitura + shape do PaginaResponse --------------------------------------

    @Test
    void listar_comTokenUser_devolve200ComPaginaResponse() throws Exception {
        inserirProduto("Rosa", "un", "10", "25", "4.50");

        mockMvc.perform(get("/api/v1/produtos").header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.conteudo").isArray())
                .andExpect(jsonPath("$.data.pagina").value(0))
                .andExpect(jsonPath("$.data.tamanho").value(20))
                .andExpect(jsonPath("$.data.totalElementos").value(1))
                .andExpect(jsonPath("$.data.totalPaginas").value(1))
                .andExpect(jsonPath("$.data.primeira").value(true))
                .andExpect(jsonPath("$.data.ultima").value(true));
    }

    @Test
    void listar_comTokenAdmin_devolve200() throws Exception {
        mockMvc.perform(get("/api/v1/produtos").header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conteudo").isArray());
    }

    @Test
    void listar_semToken_devolve401() throws Exception {
        mockMvc.perform(get("/api/v1/produtos"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // ---- CA-3: defaults, ordenacao nome ASC, paginacao e range invalido ----------------------

    @Test
    void listar_semParams_ordenaPorNomeAsc() throws Exception {
        inserirProduto("Zinia", "un", "1", "5", null);
        inserirProduto("Azaleia", "un", "1", "5", null);
        inserirProduto("Margarida", "un", "1", "5", null);

        mockMvc.perform(get("/api/v1/produtos").header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conteudo.length()").value(3))
                .andExpect(jsonPath("$.data.conteudo[0].nome").value("Azaleia"))
                .andExpect(jsonPath("$.data.conteudo[1].nome").value("Margarida"))
                .andExpect(jsonPath("$.data.conteudo[2].nome").value("Zinia"));
    }

    @Test
    void listar_segundaPagina_devolveFatiaCorreta() throws Exception {
        // 7 produtos "P0".."P6" (nome ASC); tamanho=5 → pagina 1 traz P5, P6.
        for (int i = 0; i < 7; i++) {
            inserirProduto("P" + i, "un", "1", "5", null);
        }

        mockMvc.perform(get("/api/v1/produtos?pagina=1&tamanho=5")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pagina").value(1))
                .andExpect(jsonPath("$.data.tamanho").value(5))
                .andExpect(jsonPath("$.data.totalElementos").value(7))
                .andExpect(jsonPath("$.data.totalPaginas").value(2))
                .andExpect(jsonPath("$.data.conteudo.length()").value(2))
                .andExpect(jsonPath("$.data.conteudo[0].nome").value("P5"))
                .andExpect(jsonPath("$.data.primeira").value(false))
                .andExpect(jsonPath("$.data.ultima").value(true));
    }

    @Test
    void listar_paginaAlemDoTotal_devolve200Vazio() throws Exception {
        inserirProduto("Unico", "un", "1", "5", null);

        mockMvc.perform(get("/api/v1/produtos?pagina=9&tamanho=5")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conteudo.length()").value(0))
                .andExpect(jsonPath("$.data.ultima").value(true));
    }

    @Test
    void listar_tamanhoZero_devolve400() throws Exception {
        mockMvc.perform(get("/api/v1/produtos?tamanho=0")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("tamanho"));
    }

    @Test
    void listar_tamanhoAcimaDoMaximo_devolve400() throws Exception {
        mockMvc.perform(get("/api/v1/produtos?tamanho=101")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void listar_paginaNegativa_devolve400() throws Exception {
        mockMvc.perform(get("/api/v1/produtos?pagina=-1")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("pagina"));
    }

    // ---- CA-4: filtro ILIKE substring case-insensitive ---------------------------------------

    @Test
    void listar_comFiltroNome_aplicaIlikeSubstring() throws Exception {
        // CA-4: "Rosa"/"Girassol" + nome=ro (minusculo) → so "Rosa" (substring, case-insensitive).
        inserirProduto("Rosa Vermelha", "un", "1", "5", null);
        inserirProduto("Girassol", "un", "1", "5", null);

        mockMvc.perform(get("/api/v1/produtos?nome=ro")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(1))
                .andExpect(jsonPath("$.data.conteudo[0].nome").value("Rosa Vermelha"));
    }

    @Test
    void listar_comFiltroNome_substringInterna() throws Exception {
        // "ass" e substring interna de "Girassol" (ILIKE '%ass%'), provando o match no meio da string.
        inserirProduto("Girassol", "un", "1", "5", null);
        inserirProduto("Rosa", "un", "1", "5", null);

        mockMvc.perform(get("/api/v1/produtos?nome=ASS")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(1))
                .andExpect(jsonPath("$.data.conteudo[0].nome").value("Girassol"));
    }

    @Test
    void listar_comFiltroNome_caseInsensitivePrefixo() throws Exception {
        inserirProduto("Rosa", "un", "1", "5", null);
        inserirProduto("Girassol", "un", "1", "5", null);

        mockMvc.perform(get("/api/v1/produtos?nome=rosa")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(1))
                .andExpect(jsonPath("$.data.conteudo[0].nome").value("Rosa"));
    }

    @Test
    void listar_filtroSemMatch_devolve200Vazio() throws Exception {
        inserirProduto("Rosa", "un", "1", "5", null);

        mockMvc.perform(get("/api/v1/produtos?nome=xyz")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conteudo.length()").value(0))
                .andExpect(jsonPath("$.data.totalElementos").value(0));
    }

    // ---- CA-5 / CA-15: detalhe (+ estoqueBaixo) e 404 ----------------------------------------

    @Test
    void detalhar_existente_devolve200ComProdutoResponse() throws Exception {
        Long id = inserirProduto("Rosa Vermelha", "un", "10", "25", "4.50");

        mockMvc.perform(get("/api/v1/produtos/" + id)
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.nome").value("Rosa Vermelha"))
                .andExpect(jsonPath("$.data.unidadeMedida").value("un"))
                .andExpect(jsonPath("$.data.preco").value(4.50))
                .andExpect(jsonPath("$.data.estoqueBaixo").value(false)) // 25 > 10
                .andExpect(jsonPath("$.data.criadoEm").isNotEmpty());
    }

    @Test
    void detalhar_semPreco_devolvePrecoNull() throws Exception {
        Long id = inserirProduto("Lirio", "un", "5", "10", null);

        mockMvc.perform(get("/api/v1/produtos/" + id)
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.preco").doesNotExist());
    }

    @Test
    void detalhar_estoqueNoLimite_estoqueBaixoTrue() throws Exception {
        // estoqueAtual (5) <= estoqueMinimo (10) → estoqueBaixo true (FC-13/CA-15).
        Long id = inserirProduto("Cravo", "un", "10", "5", null);

        mockMvc.perform(get("/api/v1/produtos/" + id)
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.estoqueBaixo").value(true));
    }

    @Test
    void detalhar_estoqueZerado_estoqueBaixoTrue() throws Exception {
        Long id = inserirProduto("Tulipa", "un", "3", "0", null);

        mockMvc.perform(get("/api/v1/produtos/" + id)
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.estoqueBaixo").value(true));
    }

    @Test
    void detalhar_inexistente_devolve404() throws Exception {
        mockMvc.perform(get("/api/v1/produtos/999999")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void detalhar_semToken_devolve401() throws Exception {
        mockMvc.perform(get("/api/v1/produtos/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }
}
