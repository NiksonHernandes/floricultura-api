package com.floricultura.api.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integracao da <b>contraparte da movimentacao</b> do M5-revisao (RB-3, R-CA-1..6 — SPEC-M5 §R3.3,
 * AD-SQ-64) contra um PostgreSQL de DESCARTE (Testcontainers postgres:16), com o {@code
 * MovimentacaoController}/{@code MovimentacaoService}, {@code SecurityConfig}, filtro JWT, Flyway
 * V1..V10 e a trigger de imutabilidade endurecida reais no contexto. Prova: ENTRADA grava
 * fornecedor_id+nome; SAIDA grava cliente_id+nome; contraparte no tipo errado → 400 com {@code field};
 * fornecedor/cliente inexistente → 400 com {@code field} e nada persiste; {@code GET /movimentacoes}
 * expoe a contraparte; e o hard delete LGPD (FC-08) do cadastro <b>anula o id e preserva o snapshot do
 * nome</b> no ledger (integra o comportamento da V10/RB-2). Dados ficticios (LGPD): "Sitio Verde" /
 * "Maria Flores".
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MovimentacaoContraparteTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-contraparte-0123456789abc");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminBearer;
    private Long produtoId;
    private Long fornecedorId;
    private Long clienteId;

    @BeforeEach
    void seed() {
        // TRUNCATE: o ledger e imutavel (trigger BEFORE DELETE row-level barra DELETE de linha).
        jdbc.update("TRUNCATE TABLE movimentacao_estoque");
        jdbc.update("DELETE FROM produto");
        jdbc.update("DELETE FROM cliente");
        jdbc.update("DELETE FROM fornecedor");
        jdbc.update("DELETE FROM usuario");
        Long adminId = inserirUsuario("admin-contraparte@floricultura.local", "ADMIN");
        adminBearer = "Bearer " + jwtService.gerarToken(adminId);
        produtoId = inserirProduto("Rosa", "100");
        fornecedorId = inserirFornecedor("Sitio Verde");
        clienteId = inserirCliente("Maria Flores");
    }

    private Long inserirUsuario(String email, String role) {
        return jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, ?, true, false) RETURNING id",
                Long.class, "Nome " + role, email,
                ENCODER.encode(UUID.randomUUID().toString()), role);
    }

    private Long inserirProduto(String nome, String estoqueAtual) {
        return jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida, estoque_minimo, estoque_atual) "
                        + "VALUES (?, 'un', 1, ?) RETURNING id",
                Long.class, nome, new java.math.BigDecimal(estoqueAtual));
    }

    private Long inserirFornecedor(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO fornecedor (nome) VALUES (?) RETURNING id", Long.class, nome);
    }

    private Long inserirCliente(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO cliente (nome) VALUES (?) RETURNING id", Long.class, nome);
    }

    private Integer contarMovimentacoes() {
        return jdbc.queryForObject("SELECT count(*) FROM movimentacao_estoque", Integer.class);
    }

    private String url() {
        return "/api/v1/produtos/" + produtoId + "/movimentacoes";
    }

    // ---- R-CA-1: ENTRADA com fornecedor grava id + snapshot do nome ---------------------------

    @Test
    void entrada_comFornecedor_gravaIdENomeERespondeContraparte() throws Exception {
        String body = """
                {"tipo":"ENTRADA","quantidade":10,"fornecedorId":%d}""".formatted(fornecedorId);

        mockMvc.perform(post(url())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.fornecedorId").value(fornecedorId))
                .andExpect(jsonPath("$.data.fornecedorNome").value("Sitio Verde"))
                .andExpect(jsonPath("$.data.clienteId").doesNotExist())
                .andExpect(jsonPath("$.data.clienteNome").doesNotExist());

        // Snapshot gravado no ledger (nunca do payload).
        String nome = jdbc.queryForObject(
                "SELECT fornecedor_nome FROM movimentacao_estoque WHERE fornecedor_id = ?",
                String.class, fornecedorId);
        assertEquals("Sitio Verde", nome);
    }

    // ---- R-CA-2: SAIDA com cliente grava id + snapshot do nome --------------------------------

    @Test
    void saida_comCliente_gravaIdENomeERespondeContraparte() throws Exception {
        String body = """
                {"tipo":"SAIDA","quantidade":5,"clienteId":%d}""".formatted(clienteId);

        mockMvc.perform(post(url())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.clienteId").value(clienteId))
                .andExpect(jsonPath("$.data.clienteNome").value("Maria Flores"))
                .andExpect(jsonPath("$.data.fornecedorId").doesNotExist())
                .andExpect(jsonPath("$.data.fornecedorNome").doesNotExist());

        String nome = jdbc.queryForObject(
                "SELECT cliente_nome FROM movimentacao_estoque WHERE cliente_id = ?",
                String.class, clienteId);
        assertEquals("Maria Flores", nome);
    }

    // ---- R-CA-3: AJUSTE nao aceita contraparte → 400 -----------------------------------------

    @Test
    void ajuste_comFornecedor_devolve400ENaoPersiste() throws Exception {
        String body = """
                {"tipo":"AJUSTE","quantidade":5,"fornecedorId":%d}""".formatted(fornecedorId);

        mockMvc.perform(post(url())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("fornecedorId"));

        assertEquals(0, contarMovimentacoes());
    }

    @Test
    void ajuste_comCliente_devolve400ENaoPersiste() throws Exception {
        String body = """
                {"tipo":"AJUSTE","quantidade":5,"clienteId":%d}""".formatted(clienteId);

        mockMvc.perform(post(url())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("clienteId"));

        assertEquals(0, contarMovimentacoes());
    }

    // ---- R-CA-4: exclusividade por tipo → 400 field certo, nada persiste ----------------------

    @Test
    void entrada_comCliente_devolve400FieldClienteId() throws Exception {
        String body = """
                {"tipo":"ENTRADA","quantidade":10,"clienteId":%d}""".formatted(clienteId);

        mockMvc.perform(post(url())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("clienteId"));

        assertEquals(0, contarMovimentacoes());
    }

    @Test
    void saida_comFornecedor_devolve400FieldFornecedorId() throws Exception {
        String body = """
                {"tipo":"SAIDA","quantidade":5,"fornecedorId":%d}""".formatted(fornecedorId);

        mockMvc.perform(post(url())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("fornecedorId"));

        assertEquals(0, contarMovimentacoes());
    }

    // ---- R-CA-5: contraparte inexistente → 400 field certo, nada persiste ---------------------

    @Test
    void entrada_comFornecedorInexistente_devolve400ENaoPersiste() throws Exception {
        String body = """
                {"tipo":"ENTRADA","quantidade":10,"fornecedorId":999999}""";

        mockMvc.perform(post(url())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("fornecedorId"));

        assertEquals(0, contarMovimentacoes());
    }

    @Test
    void saida_comClienteInexistente_devolve400ENaoPersiste() throws Exception {
        String body = """
                {"tipo":"SAIDA","quantidade":5,"clienteId":999999}""";

        mockMvc.perform(post(url())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("clienteId"));

        assertEquals(0, contarMovimentacoes());
    }

    // ---- R-CA-6: hard delete do cadastro anula o id e PRESERVA o snapshot no ledger -----------

    @Test
    void hardDeleteDoFornecedor_anulaIdEPreservaNomeNoLedger() throws Exception {
        String body = """
                {"tipo":"ENTRADA","quantidade":10,"fornecedorId":%d}""".formatted(fornecedorId);
        mockMvc.perform(post(url())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        // Hard delete LGPD do cadastro (a trigger V10 tolera o cascade SET NULL).
        mockMvc.perform(delete("/api/v1/fornecedores/" + fornecedorId)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNoContent());

        // Ledger: id anulado, snapshot do nome preservado (auditoria imutavel — AD-SQ-64).
        Long fkId = jdbc.queryForObject(
                "SELECT fornecedor_id FROM movimentacao_estoque LIMIT 1", Long.class);
        String nome = jdbc.queryForObject(
                "SELECT fornecedor_nome FROM movimentacao_estoque LIMIT 1", String.class);
        assertNull(fkId);
        assertEquals("Sitio Verde", nome);

        // GET /movimentacoes ainda mostra o nome (id nulo, nome preservado).
        mockMvc.perform(get("/api/v1/movimentacoes")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conteudo[0].fornecedorId").doesNotExist())
                .andExpect(jsonPath("$.data.conteudo[0].fornecedorNome").value("Sitio Verde"));
    }

    @Test
    void hardDeleteDoCliente_anulaIdEPreservaNomeNoLedger() throws Exception {
        String body = """
                {"tipo":"SAIDA","quantidade":5,"clienteId":%d}""".formatted(clienteId);
        mockMvc.perform(post(url())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/v1/clientes/" + clienteId)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNoContent());

        Long fkId = jdbc.queryForObject(
                "SELECT cliente_id FROM movimentacao_estoque LIMIT 1", Long.class);
        String nome = jdbc.queryForObject(
                "SELECT cliente_nome FROM movimentacao_estoque LIMIT 1", String.class);
        assertNull(fkId);
        assertEquals("Maria Flores", nome);

        mockMvc.perform(get("/api/v1/movimentacoes")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conteudo[0].clienteId").doesNotExist())
                .andExpect(jsonPath("$.data.conteudo[0].clienteNome").value("Maria Flores"));
    }
}
