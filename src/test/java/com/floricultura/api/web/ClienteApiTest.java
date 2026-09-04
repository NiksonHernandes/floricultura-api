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
 * Integracao da LEITURA de clientes do M5 (T-M5-2, CA-1/CA-2/CA-3 — SPEC-M5 §3.4) contra um PostgreSQL
 * de DESCARTE (Testcontainers postgres:16), com {@code ClienteController}/{@code ClienteService} e filtro
 * JWT reais. Prova a lista paginada ordenada {@code nome ASC} + filtro {@code ILIKE} + paginacao invalida
 * (400), o detalhe com {@code produtoIds} + 404, e o invariante de que a lista <b>nao</b> materializa o
 * vinculo ({@code produtoIds=null} nos itens). Dados ficticios (LGPD): "Maria Flores" / {@code
 * @exemplo.com.br}.
 *
 * <p>Nome {@code *Test} (Surefire): integracoes Testcontainers usam {@code *Test}. Segredos de teste sao
 * NAO-segredos (BCrypt de {@link UUID} de runtime).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ClienteApiTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-clienteapi-0123456789abcd");
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
        jdbc.update("DELETE FROM cliente_produto");
        jdbc.update("DELETE FROM cliente");
        jdbc.update("DELETE FROM usuario");
        Long userId = inserirUsuario("user-cliente@floricultura.local", "USER");
        userBearer = "Bearer " + jwtService.gerarToken(userId);
    }

    private Long inserirUsuario(String email, String role) {
        return jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, ?, true, false) RETURNING id",
                Long.class, "Nome " + role, email,
                ENCODER.encode(UUID.randomUUID().toString()), role);
    }

    private Long inserirCliente(String nome, String telefone, String email) {
        return jdbc.queryForObject(
                "INSERT INTO cliente (nome, telefone, email) VALUES (?, ?, ?) RETURNING id",
                Long.class, nome, telefone, email);
    }

    private Long inserirProduto(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida) VALUES (?, 'un') RETURNING id",
                Long.class, nome);
    }

    // ---- CA-1: lista paginada, ordem nome ASC + filtro ILIKE + paginacao invalida 400 ---------

    @Test
    void listar_comUser_filtraNomeEOrdenaPorNomeAsc() throws Exception {
        inserirCliente("Zulmira Jardins", "(11) 90000-0003", "zulmira@exemplo.com.br");
        inserirCliente("Maria Flores", "(11) 90000-0001", "maria@exemplo.com.br");
        inserirCliente("Mariana Botanica", "(11) 90000-0002", "mariana@exemplo.com.br");

        mockMvc.perform(get("/api/v1/clientes?pagina=0&tamanho=20&nome=mar")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(2))
                // nome ASC → "Maria Flores" antes de "Mariana Botanica".
                .andExpect(jsonPath("$.data.conteudo[0].nome").value("Maria Flores"))
                .andExpect(jsonPath("$.data.conteudo[1].nome").value("Mariana Botanica"));
    }

    @Test
    void listar_tamanhoAcimaDoMaximo_devolve400() throws Exception {
        mockMvc.perform(get("/api/v1/clientes?tamanho=101")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("tamanho"));
    }

    @Test
    void listar_paginaNegativa_devolve400() throws Exception {
        mockMvc.perform(get("/api/v1/clientes?pagina=-1")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("pagina"));
    }

    // ---- CA-3: cada item da lista traz produtoIds=null (nunca materializa o vinculo) ----------

    @Test
    void listar_itemDaLista_temProdutoIdsNull() throws Exception {
        Long clienteId = inserirCliente("Maria Flores", "(11) 90000-0001", "maria@exemplo.com.br");
        Long produtoId = inserirProduto("Rosa");
        jdbc.update("INSERT INTO cliente_produto (cliente_id, produto_id) VALUES (?, ?)",
                clienteId, produtoId);

        mockMvc.perform(get("/api/v1/clientes")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(1))
                // vinculo existe no banco, mas a LISTA nao o materializa (CA-3/AD-SQ-38/44).
                .andExpect(jsonPath("$.data.conteudo[0].produtoIds").doesNotExist());
    }

    // ---- CA-2: detalhe com produtoIds + inexistente 404 ---------------------------------------

    @Test
    void detalhar_existente_devolve200ComProdutoIds() throws Exception {
        Long clienteId = inserirCliente("Maria Flores", "(11) 90000-0001", "maria@exemplo.com.br");
        Long produtoA = inserirProduto("Rosa");
        Long produtoB = inserirProduto("Tulipa");
        jdbc.update("INSERT INTO cliente_produto (cliente_id, produto_id) VALUES (?, ?)",
                clienteId, produtoB);
        jdbc.update("INSERT INTO cliente_produto (cliente_id, produto_id) VALUES (?, ?)",
                clienteId, produtoA);

        mockMvc.perform(get("/api/v1/clientes/" + clienteId)
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(clienteId))
                .andExpect(jsonPath("$.data.nome").value("Maria Flores"))
                .andExpect(jsonPath("$.data.email").value("maria@exemplo.com.br"))
                // ORDER BY produto_id → menor id primeiro (independe da ordem de insercao).
                .andExpect(jsonPath("$.data.produtoIds[0]").value(produtoA))
                .andExpect(jsonPath("$.data.produtoIds[1]").value(produtoB));
    }

    @Test
    void detalhar_semVinculos_devolveProdutoIdsVazio() throws Exception {
        Long clienteId = inserirCliente("Maria Flores", null, null);

        mockMvc.perform(get("/api/v1/clientes/" + clienteId)
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.produtoIds").isArray())
                .andExpect(jsonPath("$.data.produtoIds.length()").value(0));
    }

    @Test
    void detalhar_inexistente_devolve404() throws Exception {
        mockMvc.perform(get("/api/v1/clientes/999999")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
