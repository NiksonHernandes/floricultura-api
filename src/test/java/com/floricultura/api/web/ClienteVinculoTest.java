package com.floricultura.api.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * Integracao do vinculo N:N cliente↔produto do M5 (T-M5-4, CA-7/CA-8 — SPEC-M5 §3.5/§4.2) contra um
 * PostgreSQL de DESCARTE (Testcontainers postgres:16). Prova o <b>replace-set</b> ({@code [30,30]→[30]}
 * dedup; {@code []} limpa; <b>ausente no PUT preserva</b> os vinculos — update parcial) e o invariante
 * critico "{@code produtoId} inexistente → 400 {@code field=produtoIds} e NADA persiste" (transacao),
 * checando a contagem antes/depois no POST e no PUT. Dados ficticios (LGPD): "Maria Flores".
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ClienteVinculoTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-clientevinculo-0123456789");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminBearer;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM cliente_produto");
        jdbc.update("DELETE FROM cliente");
        jdbc.update("DELETE FROM produto");
        jdbc.update("DELETE FROM usuario");
        Long adminId = inserirUsuario("admin-clientevinculo@floricultura.local", "ADMIN");
        adminBearer = "Bearer " + jwtService.gerarToken(adminId);
    }

    private Long inserirUsuario(String email, String role) {
        return jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, ?, true, false) RETURNING id",
                Long.class, "Nome " + role, email,
                ENCODER.encode(UUID.randomUUID().toString()), role);
    }

    private Long inserirCliente(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO cliente (nome) VALUES (?) RETURNING id", Long.class, nome);
    }

    private Long inserirProduto(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida) VALUES (?, 'un') RETURNING id",
                Long.class, nome);
    }

    private Integer contarVinculos(Long clienteId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM cliente_produto WHERE cliente_id = ?", Integer.class, clienteId);
    }

    // ---- CA-7: replace-set — POST com [prod,prod] dedup → [prod] --------------------------------

    @Test
    void criar_comProdutoIdsDuplicados_deduplicaVinculos() throws Exception {
        Long produtoId = inserirProduto("Rosa");
        String body = """
                {"nome":"Maria Flores","produtoIds":[%d,%d]}""".formatted(produtoId, produtoId);

        String resposta = mockMvc.perform(post("/api/v1/clientes")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.produtoIds.length()").value(1))
                .andExpect(jsonPath("$.data.produtoIds[0]").value(produtoId))
                .andReturn().getResponse().getContentAsString();

        Long clienteId = com.jayway.jsonpath.JsonPath.parse(resposta).read("$.data.id", Long.class);
        assertEquals(1, contarVinculos(clienteId)); // dedup persistido (CA-7)
    }

    // ---- CA-7: replace-set — PUT [30] → substitui por [outro] ---------------------------------

    @Test
    void atualizar_substituiConjuntoDeVinculos() throws Exception {
        Long clienteId = inserirCliente("Maria Flores");
        Long prodA = inserirProduto("Rosa");
        Long prodB = inserirProduto("Tulipa");
        jdbc.update("INSERT INTO cliente_produto (cliente_id, produto_id) VALUES (?, ?)",
                clienteId, prodA);

        String body = """
                {"nome":"Maria Flores","produtoIds":[%d]}""".formatted(prodB);
        mockMvc.perform(put("/api/v1/clientes/" + clienteId)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.produtoIds.length()").value(1))
                .andExpect(jsonPath("$.data.produtoIds[0]").value(prodB));

        Long restante = jdbc.queryForObject(
                "SELECT produto_id FROM cliente_produto WHERE cliente_id = ?", Long.class, clienteId);
        assertEquals(prodB, restante); // prodA removido, prodB inserido (CA-7)
    }

    // ---- CA-7: replace-set — PUT [] limpa todos os vinculos -----------------------------------

    @Test
    void atualizar_comProdutoIdsVazio_removeTodosOsVinculos() throws Exception {
        Long clienteId = inserirCliente("Maria Flores");
        Long produtoId = inserirProduto("Rosa");
        jdbc.update("INSERT INTO cliente_produto (cliente_id, produto_id) VALUES (?, ?)",
                clienteId, produtoId);

        mockMvc.perform(put("/api/v1/clientes/" + clienteId)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Maria Flores\",\"produtoIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.produtoIds.length()").value(0));

        assertEquals(0, contarVinculos(clienteId)); // [] limpou (CA-7)
    }

    // ---- CA-7: replace-set — produtoIds AUSENTE no PUT preserva os vinculos (update parcial) ---

    @Test
    void atualizar_semProdutoIds_preservaVinculosExistentes() throws Exception {
        Long clienteId = inserirCliente("Maria Flores");
        Long produtoId = inserirProduto("Rosa");
        jdbc.update("INSERT INTO cliente_produto (cliente_id, produto_id) VALUES (?, ?)",
                clienteId, produtoId);

        // Payload SEM produtoIds → nao altera o conjunto de vinculos (invariante do replace-set).
        mockMvc.perform(put("/api/v1/clientes/" + clienteId)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Maria das Flores\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nome").value("Maria das Flores"))
                // detalhe reflete os vinculos preservados.
                .andExpect(jsonPath("$.data.produtoIds.length()").value(1))
                .andExpect(jsonPath("$.data.produtoIds[0]").value(produtoId));

        assertEquals(1, contarVinculos(clienteId)); // preservado (CA-7)
    }

    // ---- CA-8: produtoId inexistente → 400 field=produtoIds e NADA persiste (POST) -------------

    @Test
    void criar_comProdutoIdInexistente_devolve400ENaoPersiste() throws Exception {
        String body = """
                {"nome":"Maria Flores","produtoIds":[999999]}""";

        mockMvc.perform(post("/api/v1/clientes")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("produtoIds"));

        // Nada persistido: nenhum cliente criado, nenhum vinculo.
        Integer clientes = jdbc.queryForObject(
                "SELECT count(*) FROM cliente WHERE nome = 'Maria Flores'", Integer.class);
        Integer links = jdbc.queryForObject("SELECT count(*) FROM cliente_produto", Integer.class);
        assertEquals(0, clientes);
        assertEquals(0, links);
    }

    // ---- CA-8: produtoId inexistente → 400 e NADA persiste (PUT — vinculos intactos) -----------

    @Test
    void atualizar_comProdutoIdInexistente_devolve400ENaoAlteraVinculos() throws Exception {
        Long clienteId = inserirCliente("Maria Flores");
        Long produtoId = inserirProduto("Rosa");
        jdbc.update("INSERT INTO cliente_produto (cliente_id, produto_id) VALUES (?, ?)",
                clienteId, produtoId);

        String body = """
                {"nome":"Maria Alterada","produtoIds":[999999]}""";
        mockMvc.perform(put("/api/v1/clientes/" + clienteId)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("produtoIds"));

        // Nada mudou: nome preservado e vinculo original intacto (validacao ANTES de qualquer escrita).
        String nome = jdbc.queryForObject(
                "SELECT nome FROM cliente WHERE id = ?", String.class, clienteId);
        assertEquals("Maria Flores", nome);
        assertEquals(1, contarVinculos(clienteId));
        Long restante = jdbc.queryForObject(
                "SELECT produto_id FROM cliente_produto WHERE cliente_id = ?", Long.class, clienteId);
        assertEquals(produtoId, restante);
    }
}
