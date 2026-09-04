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
 * Integracao da ESCRITA de clientes do M5 (T-M5-4, CA-4/CA-5/CA-6 — SPEC-M5 §3.4) contra um PostgreSQL de
 * DESCARTE (Testcontainers postgres:16), com {@code ClienteController}/{@code ClienteService}, {@code
 * SecurityConfig} endurecido (matchers §3.4) e filtro JWT reais. Prova o RBAC por metodo (POST/PUT/DELETE
 * = ADMIN; USER → 403; sem token → 401), a validacao Bean Validation ({@code nome} vazio / {@code email}
 * malformado → 400 com {@code details}), o PUT (200/404) e o hard delete com cascade (contagem na juncao +
 * produtos intactos). O replace-set/{@code produtoIds} tem suite propria ({@code ClienteVinculoTest}).
 * Dados ficticios (LGPD): "Maria Flores" / {@code @exemplo.com.br}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ClienteEscritaTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-clienteescrita-0123456789");
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
        jdbc.update("DELETE FROM cliente_produto");
        jdbc.update("DELETE FROM cliente");
        jdbc.update("DELETE FROM usuario");
        Long adminId = inserirUsuario("admin-clienteescrita@floricultura.local", "ADMIN");
        Long userId = inserirUsuario("user-clienteescrita@floricultura.local", "USER");
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

    private Long inserirCliente(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO cliente (nome) VALUES (?) RETURNING id", Long.class, nome);
    }

    private Long inserirProduto(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida) VALUES (?, 'un') RETURNING id",
                Long.class, nome);
    }

    // ---- CA-4: criar (ADMIN) 201; validacoes 400; USER 403; sem token 401 ---------------------

    @Test
    void criar_comAdmin_devolve201ComProdutoIdsVazio() throws Exception {
        String body = """
                {"nome":"Maria Flores","telefone":"(11) 90000-0000",
                 "email":"maria@exemplo.com.br","observacoes":"Prefere arranjos de outono."}""";

        mockMvc.perform(post("/api/v1/clientes")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.nome").value("Maria Flores"))
                .andExpect(jsonPath("$.data.email").value("maria@exemplo.com.br"))
                // produtoIds ausente no payload → detalhe traz [] (§4.5/CA-4).
                .andExpect(jsonPath("$.data.produtoIds").isArray())
                .andExpect(jsonPath("$.data.produtoIds.length()").value(0))
                .andExpect(jsonPath("$.data.criadoEm").isNotEmpty());
    }

    @Test
    void criar_nomeVazio_devolve400FieldNome() throws Exception {
        String body = """
                {"nome":"","email":"maria@exemplo.com.br"}""";

        mockMvc.perform(post("/api/v1/clientes")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("nome"));
    }

    @Test
    void criar_emailInvalido_devolve400FieldEmail() throws Exception {
        String body = """
                {"nome":"Maria Flores","email":"xyz"}""";

        mockMvc.perform(post("/api/v1/clientes")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("email"));
    }

    @Test
    void criar_comUser_devolve403() throws Exception {
        String body = """
                {"nome":"Maria Flores"}""";

        mockMvc.perform(post("/api/v1/clientes")
                        .header(HttpHeaders.AUTHORIZATION, userBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void criar_semToken_devolve401() throws Exception {
        mockMvc.perform(post("/api/v1/clientes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Maria Flores\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // ---- CA-5: atualizar (ADMIN) 200 / inexistente 404 / USER 403 -----------------------------

    @Test
    void atualizar_comAdmin_devolve200ComCamposAtualizados() throws Exception {
        Long id = inserirCliente("Rascunho");
        String body = """
                {"nome":"Maria Flores","telefone":"(11) 91111-1111",
                 "email":"maria.nova@exemplo.com.br","observacoes":"Atualizada."}""";

        mockMvc.perform(put("/api/v1/clientes/" + id)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nome").value("Maria Flores"))
                .andExpect(jsonPath("$.data.email").value("maria.nova@exemplo.com.br"))
                .andExpect(jsonPath("$.data.telefone").value("(11) 91111-1111"))
                .andExpect(jsonPath("$.data.produtoIds").isArray());
    }

    @Test
    void atualizar_inexistente_devolve404() throws Exception {
        String body = """
                {"nome":"Fantasma"}""";

        mockMvc.perform(put("/api/v1/clientes/999999")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void atualizar_comUser_devolve403() throws Exception {
        Long id = inserirCliente("Maria Flores");
        String body = """
                {"nome":"Maria X"}""";

        mockMvc.perform(put("/api/v1/clientes/" + id)
                        .header(HttpHeaders.AUTHORIZATION, userBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    // ---- CA-6: hard delete (ADMIN) 204 + cascade / inexistente 404 / USER 403 -----------------

    @Test
    void excluir_comAdmin_devolve204ERemoveVinculosPreservandoProduto() throws Exception {
        Long clienteId = inserirCliente("Maria Flores");
        Long produtoId = inserirProduto("Rosa");
        jdbc.update("INSERT INTO cliente_produto (cliente_id, produto_id) VALUES (?, ?)",
                clienteId, produtoId);

        mockMvc.perform(delete("/api/v1/clientes/" + clienteId)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNoContent());

        Integer clientes = jdbc.queryForObject(
                "SELECT count(*) FROM cliente WHERE id = ?", Integer.class, clienteId);
        Integer vinculos = jdbc.queryForObject(
                "SELECT count(*) FROM cliente_produto WHERE cliente_id = ?", Integer.class, clienteId);
        Integer produtos = jdbc.queryForObject(
                "SELECT count(*) FROM produto WHERE id = ?", Integer.class, produtoId);
        assertEquals(0, clientes);
        assertEquals(0, vinculos); // cascade limpou a juncao (CA-6)
        assertEquals(1, produtos); // produto preservado (CA-6)
    }

    @Test
    void excluir_inexistente_devolve404() throws Exception {
        mockMvc.perform(delete("/api/v1/clientes/999999")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void excluir_comUser_devolve403() throws Exception {
        Long id = inserirCliente("Maria Flores");

        mockMvc.perform(delete("/api/v1/clientes/" + id)
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
}
