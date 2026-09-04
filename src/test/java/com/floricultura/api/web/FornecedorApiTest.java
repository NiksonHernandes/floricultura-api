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
 * Integracao da LEITURA de fornecedores do M5 (T-M5-3, CA-1/CA-2/CA-3 — SPEC-M5 §3.4), espelho de
 * {@code ClienteApiTest}, contra um PostgreSQL de DESCARTE (Testcontainers postgres:16), com
 * {@code FornecedorController}/{@code FornecedorService} e filtro JWT reais. Prova a lista paginada
 * ordenada {@code nome ASC} + filtro {@code ILIKE} + paginacao invalida (400), o detalhe com
 * {@code produtoIds} + 404, e o invariante de que a lista <b>nao</b> materializa o vinculo
 * ({@code produtoIds=null} nos itens). Dados ficticios (LGPD): "Flora Atacado" / {@code @exemplo.com.br}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class FornecedorApiTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-fornecedorapi-0123456789abcd");
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
        jdbc.update("DELETE FROM fornecedor_produto");
        jdbc.update("DELETE FROM fornecedor");
        jdbc.update("DELETE FROM usuario");
        Long userId = inserirUsuario("user-fornecedor@floricultura.local", "USER");
        userBearer = "Bearer " + jwtService.gerarToken(userId);
    }

    private Long inserirUsuario(String email, String role) {
        return jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, ?, true, false) RETURNING id",
                Long.class, "Nome " + role, email,
                ENCODER.encode(UUID.randomUUID().toString()), role);
    }

    private Long inserirFornecedor(String nome, String telefone, String email) {
        return jdbc.queryForObject(
                "INSERT INTO fornecedor (nome, telefone, email) VALUES (?, ?, ?) RETURNING id",
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
        inserirFornecedor("Verde Vale", "(11) 90000-0003", "verde@exemplo.com.br");
        inserirFornecedor("Flora Atacado", "(11) 90000-0001", "flora@exemplo.com.br");
        inserirFornecedor("Floreria Central", "(11) 90000-0002", "floreria@exemplo.com.br");

        mockMvc.perform(get("/api/v1/fornecedores?pagina=0&tamanho=20&nome=flor")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(2))
                // nome ASC → "Flora Atacado" antes de "Floreria Central".
                .andExpect(jsonPath("$.data.conteudo[0].nome").value("Flora Atacado"))
                .andExpect(jsonPath("$.data.conteudo[1].nome").value("Floreria Central"));
    }

    @Test
    void listar_tamanhoAcimaDoMaximo_devolve400() throws Exception {
        mockMvc.perform(get("/api/v1/fornecedores?tamanho=101")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("tamanho"));
    }

    @Test
    void listar_paginaNegativa_devolve400() throws Exception {
        mockMvc.perform(get("/api/v1/fornecedores?pagina=-1")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("pagina"));
    }

    // ---- CA-3: cada item da lista traz produtoIds=null (nunca materializa o vinculo) ----------

    @Test
    void listar_itemDaLista_temProdutoIdsNull() throws Exception {
        Long fornecedorId = inserirFornecedor(
                "Flora Atacado", "(11) 90000-0001", "flora@exemplo.com.br");
        Long produtoId = inserirProduto("Rosa");
        jdbc.update("INSERT INTO fornecedor_produto (fornecedor_id, produto_id) VALUES (?, ?)",
                fornecedorId, produtoId);

        mockMvc.perform(get("/api/v1/fornecedores")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(1))
                // vinculo existe no banco, mas a LISTA nao o materializa (CA-3/AD-SQ-38/44).
                .andExpect(jsonPath("$.data.conteudo[0].produtoIds").doesNotExist());
    }

    // ---- CA-2: detalhe com produtoIds + inexistente 404 ---------------------------------------

    @Test
    void detalhar_existente_devolve200ComProdutoIds() throws Exception {
        Long fornecedorId = inserirFornecedor(
                "Flora Atacado", "(11) 90000-0001", "flora@exemplo.com.br");
        Long produtoA = inserirProduto("Rosa");
        Long produtoB = inserirProduto("Tulipa");
        jdbc.update("INSERT INTO fornecedor_produto (fornecedor_id, produto_id) VALUES (?, ?)",
                fornecedorId, produtoB);
        jdbc.update("INSERT INTO fornecedor_produto (fornecedor_id, produto_id) VALUES (?, ?)",
                fornecedorId, produtoA);

        mockMvc.perform(get("/api/v1/fornecedores/" + fornecedorId)
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(fornecedorId))
                .andExpect(jsonPath("$.data.nome").value("Flora Atacado"))
                .andExpect(jsonPath("$.data.email").value("flora@exemplo.com.br"))
                // ORDER BY produto_id → menor id primeiro (independe da ordem de insercao).
                .andExpect(jsonPath("$.data.produtoIds[0]").value(produtoA))
                .andExpect(jsonPath("$.data.produtoIds[1]").value(produtoB));
    }

    @Test
    void detalhar_semVinculos_devolveProdutoIdsVazio() throws Exception {
        Long fornecedorId = inserirFornecedor("Flora Atacado", null, null);

        mockMvc.perform(get("/api/v1/fornecedores/" + fornecedorId)
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.produtoIds").isArray())
                .andExpect(jsonPath("$.data.produtoIds.length()").value(0));
    }

    @Test
    void detalhar_inexistente_devolve404() throws Exception {
        mockMvc.perform(get("/api/v1/fornecedores/999999")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
