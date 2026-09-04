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
 * Integracao do vinculo N:N fornecedor↔produto do M5 (T-M5-5, CA-7/CA-8 — SPEC-M5 §3.5/§4.2) — irma de
 * {@code ClienteVinculoTest}. Prova o <b>replace-set</b> ({@code [30,30]→[30]} dedup; {@code []} limpa;
 * <b>ausente no PUT preserva</b> — update parcial) e o invariante critico "{@code produtoId} inexistente →
 * 400 {@code field=produtoIds} e NADA persiste" (transacao), checando a contagem antes/depois no POST e no
 * PUT. Dados ficticios (LGPD): "Flora Atacado".
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class FornecedorVinculoTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-fornvinculo-0123456789abc");
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
        jdbc.update("DELETE FROM fornecedor_produto");
        jdbc.update("DELETE FROM fornecedor");
        jdbc.update("DELETE FROM produto");
        jdbc.update("DELETE FROM usuario");
        Long adminId = inserirUsuario("admin-fornvinculo@floricultura.local", "ADMIN");
        adminBearer = "Bearer " + jwtService.gerarToken(adminId);
    }

    private Long inserirUsuario(String email, String role) {
        return jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, ?, true, false) RETURNING id",
                Long.class, "Nome " + role, email,
                ENCODER.encode(UUID.randomUUID().toString()), role);
    }

    private Long inserirFornecedor(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO fornecedor (nome) VALUES (?) RETURNING id", Long.class, nome);
    }

    private Long inserirProduto(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida) VALUES (?, 'un') RETURNING id",
                Long.class, nome);
    }

    private Integer contarVinculos(Long fornecedorId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM fornecedor_produto WHERE fornecedor_id = ?",
                Integer.class, fornecedorId);
    }

    // ---- CA-7: replace-set — POST com [prod,prod] dedup → [prod] --------------------------------

    @Test
    void criar_comProdutoIdsDuplicados_deduplicaVinculos() throws Exception {
        Long produtoId = inserirProduto("Rosa");
        String body = """
                {"nome":"Flora Atacado","produtoIds":[%d,%d]}""".formatted(produtoId, produtoId);

        String resposta = mockMvc.perform(post("/api/v1/fornecedores")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.produtoIds.length()").value(1))
                .andExpect(jsonPath("$.data.produtoIds[0]").value(produtoId))
                .andReturn().getResponse().getContentAsString();

        Long fornecedorId = com.jayway.jsonpath.JsonPath.parse(resposta).read("$.data.id", Long.class);
        assertEquals(1, contarVinculos(fornecedorId)); // dedup persistido (CA-7)
    }

    // ---- CA-7: replace-set — PUT [A] → substitui por [B] --------------------------------------

    @Test
    void atualizar_substituiConjuntoDeVinculos() throws Exception {
        Long fornecedorId = inserirFornecedor("Flora Atacado");
        Long prodA = inserirProduto("Rosa");
        Long prodB = inserirProduto("Tulipa");
        jdbc.update("INSERT INTO fornecedor_produto (fornecedor_id, produto_id) VALUES (?, ?)",
                fornecedorId, prodA);

        String body = """
                {"nome":"Flora Atacado","produtoIds":[%d]}""".formatted(prodB);
        mockMvc.perform(put("/api/v1/fornecedores/" + fornecedorId)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.produtoIds.length()").value(1))
                .andExpect(jsonPath("$.data.produtoIds[0]").value(prodB));

        Long restante = jdbc.queryForObject(
                "SELECT produto_id FROM fornecedor_produto WHERE fornecedor_id = ?",
                Long.class, fornecedorId);
        assertEquals(prodB, restante); // prodA removido, prodB inserido (CA-7)
    }

    // ---- CA-7: replace-set — PUT [] limpa todos os vinculos -----------------------------------

    @Test
    void atualizar_comProdutoIdsVazio_removeTodosOsVinculos() throws Exception {
        Long fornecedorId = inserirFornecedor("Flora Atacado");
        Long produtoId = inserirProduto("Rosa");
        jdbc.update("INSERT INTO fornecedor_produto (fornecedor_id, produto_id) VALUES (?, ?)",
                fornecedorId, produtoId);

        mockMvc.perform(put("/api/v1/fornecedores/" + fornecedorId)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Flora Atacado\",\"produtoIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.produtoIds.length()").value(0));

        assertEquals(0, contarVinculos(fornecedorId)); // [] limpou (CA-7)
    }

    // ---- CA-7: replace-set — produtoIds AUSENTE no PUT preserva os vinculos (update parcial) ---

    @Test
    void atualizar_semProdutoIds_preservaVinculosExistentes() throws Exception {
        Long fornecedorId = inserirFornecedor("Flora Atacado");
        Long produtoId = inserirProduto("Rosa");
        jdbc.update("INSERT INTO fornecedor_produto (fornecedor_id, produto_id) VALUES (?, ?)",
                fornecedorId, produtoId);

        // Payload SEM produtoIds → nao altera o conjunto de vinculos (invariante do replace-set).
        mockMvc.perform(put("/api/v1/fornecedores/" + fornecedorId)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Flora Atacado Ltda\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nome").value("Flora Atacado Ltda"))
                .andExpect(jsonPath("$.data.produtoIds.length()").value(1))
                .andExpect(jsonPath("$.data.produtoIds[0]").value(produtoId));

        assertEquals(1, contarVinculos(fornecedorId)); // preservado (CA-7)
    }

    // ---- CA-8: produtoId inexistente → 400 field=produtoIds e NADA persiste (POST) -------------

    @Test
    void criar_comProdutoIdInexistente_devolve400ENaoPersiste() throws Exception {
        String body = """
                {"nome":"Flora Atacado","produtoIds":[999999]}""";

        mockMvc.perform(post("/api/v1/fornecedores")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("produtoIds"));

        // Nada persistido: nenhum fornecedor criado, nenhum vinculo.
        Integer fornecedores = jdbc.queryForObject(
                "SELECT count(*) FROM fornecedor WHERE nome = 'Flora Atacado'", Integer.class);
        Integer links = jdbc.queryForObject("SELECT count(*) FROM fornecedor_produto", Integer.class);
        assertEquals(0, fornecedores);
        assertEquals(0, links);
    }

    // ---- CA-8: produtoId inexistente → 400 e NADA persiste (PUT — vinculos intactos) -----------

    @Test
    void atualizar_comProdutoIdInexistente_devolve400ENaoAlteraVinculos() throws Exception {
        Long fornecedorId = inserirFornecedor("Flora Atacado");
        Long produtoId = inserirProduto("Rosa");
        jdbc.update("INSERT INTO fornecedor_produto (fornecedor_id, produto_id) VALUES (?, ?)",
                fornecedorId, produtoId);

        String body = """
                {"nome":"Flora Alterada","produtoIds":[999999]}""";
        mockMvc.perform(put("/api/v1/fornecedores/" + fornecedorId)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("produtoIds"));

        // Nada mudou: nome preservado e vinculo original intacto (validacao ANTES de qualquer escrita).
        String nome = jdbc.queryForObject(
                "SELECT nome FROM fornecedor WHERE id = ?", String.class, fornecedorId);
        assertEquals("Flora Atacado", nome);
        assertEquals(1, contarVinculos(fornecedorId));
        Long restante = jdbc.queryForObject(
                "SELECT produto_id FROM fornecedor_produto WHERE fornecedor_id = ?",
                Long.class, fornecedorId);
        assertEquals(produtoId, restante);
    }
}
