package com.floricultura.api.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * Integracao da ESCRITA de fornecedores do M5 (T-M5-5, CA-4/CA-5/CA-6 — SPEC-M5 §3.4) — irma de
 * {@code ClienteEscritaTest}. Prova o RBAC por metodo (POST/PUT/DELETE = ADMIN; USER → 403; sem token →
 * 401), a validacao Bean Validation ({@code nome} vazio / {@code email} malformado → 400 com {@code
 * details}), o PUT (200/404) e o hard delete (204; produtos intactos). Dados ficticios (LGPD): "Flora
 * Atacado" / {@code @exemplo.com.br}.
 *
 * <p><b>Revisao 2026-09-04 (AD-SQ-65):</b> o vinculo fornecedor↔produto virou derivado da movimentacao —
 * nao ha mais {@code produtoIds} de escrita, replace-set nem juncao {@code fornecedor_produto} (a suite
 * {@code FornecedorVinculoTest} foi removida).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class FornecedorEscritaTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-fornescrita-0123456789abc");
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
        jdbc.update("DELETE FROM fornecedor");
        jdbc.update("DELETE FROM usuario");
        Long adminId = inserirUsuario("admin-fornescrita@floricultura.local", "ADMIN");
        Long userId = inserirUsuario("user-fornescrita@floricultura.local", "USER");
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

    private Long inserirFornecedor(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO fornecedor (nome) VALUES (?) RETURNING id", Long.class, nome);
    }

    private Long inserirProduto(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida) VALUES (?, 'un') RETURNING id",
                Long.class, nome);
    }

    // ---- CA-4: criar (ADMIN) 201; validacoes 400; USER 403; sem token 401 ---------------------

    @Test
    void criar_comAdmin_devolve201() throws Exception {
        String body = """
                {"nome":"Flora Atacado","telefone":"(11) 90000-0000",
                 "email":"contato@exemplo.com.br","observacoes":"Entrega as tercas."}""";

        mockMvc.perform(post("/api/v1/fornecedores")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.nome").value("Flora Atacado"))
                .andExpect(jsonPath("$.data.email").value("contato@exemplo.com.br"))
                .andExpect(jsonPath("$.data.criadoEm").isNotEmpty());
    }

    @Test
    void criar_nomeVazio_devolve400FieldNome() throws Exception {
        String body = """
                {"nome":"","email":"contato@exemplo.com.br"}""";

        mockMvc.perform(post("/api/v1/fornecedores")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("nome"));
    }

    @Test
    void criar_emailInvalido_devolve400FieldEmail() throws Exception {
        String body = """
                {"nome":"Flora Atacado","email":"xyz"}""";

        mockMvc.perform(post("/api/v1/fornecedores")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("email"));
    }

    @Test
    void criar_comUser_devolve403() throws Exception {
        String body = """
                {"nome":"Flora Atacado"}""";

        mockMvc.perform(post("/api/v1/fornecedores")
                        .header(HttpHeaders.AUTHORIZATION, userBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void criar_semToken_devolve401() throws Exception {
        mockMvc.perform(post("/api/v1/fornecedores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Flora Atacado\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // ---- CA-5: atualizar (ADMIN) 200 / inexistente 404 / USER 403 -----------------------------

    @Test
    void atualizar_comAdmin_devolve200ComCamposAtualizados() throws Exception {
        Long id = inserirFornecedor("Rascunho");
        String body = """
                {"nome":"Flora Atacado","telefone":"(11) 91111-1111",
                 "email":"novo@exemplo.com.br","observacoes":"Atualizado."}""";

        mockMvc.perform(put("/api/v1/fornecedores/" + id)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nome").value("Flora Atacado"))
                .andExpect(jsonPath("$.data.email").value("novo@exemplo.com.br"))
                .andExpect(jsonPath("$.data.telefone").value("(11) 91111-1111"));
    }

    @Test
    void atualizar_inexistente_devolve404() throws Exception {
        String body = """
                {"nome":"Fantasma"}""";

        mockMvc.perform(put("/api/v1/fornecedores/999999")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void atualizar_comUser_devolve403() throws Exception {
        Long id = inserirFornecedor("Flora Atacado");
        String body = """
                {"nome":"Flora X"}""";

        mockMvc.perform(put("/api/v1/fornecedores/" + id)
                        .header(HttpHeaders.AUTHORIZATION, userBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    // ---- CA-6: hard delete (ADMIN) 204 / inexistente 404 / USER 403 ---------------------------

    @Test
    void excluir_comAdmin_devolve204PreservandoProduto() throws Exception {
        Long fornecedorId = inserirFornecedor("Flora Atacado");
        Long produtoId = inserirProduto("Rosa");

        mockMvc.perform(delete("/api/v1/fornecedores/" + fornecedorId)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNoContent());

        Integer fornecedores = jdbc.queryForObject(
                "SELECT count(*) FROM fornecedor WHERE id = ?", Integer.class, fornecedorId);
        Integer produtos = jdbc.queryForObject(
                "SELECT count(*) FROM produto WHERE id = ?", Integer.class, produtoId);
        assertEquals(0, fornecedores); // cadastro removido (hard delete FC-08)
        assertEquals(1, produtos); // produto (dado independente) preservado (CA-6)
    }

    @Test
    void excluir_inexistente_devolve404() throws Exception {
        mockMvc.perform(delete("/api/v1/fornecedores/999999")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void excluir_comUser_devolve403() throws Exception {
        Long id = inserirFornecedor("Flora Atacado");

        mockMvc.perform(delete("/api/v1/fornecedores/" + id)
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
}
