package com.floricultura.api.web;

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
 * Fatia de ESCRITA de produtos do M2 (T-M2-3, CA-6/CA-7/CA-9) contra um PostgreSQL de DESCARTE
 * (Testcontainers postgres:16), com {@code ProdutoController}/{@code ProdutoService},
 * {@code SecurityConfig} endurecido e filtro JWT reais. Prova o RBAC por metodo (POST/PUT = ADMIN;
 * USER → 403), a validacao (400 com {@code details}), a criacao com {@code estoqueAtual=0} (AD-SQ-30) e
 * o PUT que <b>nunca</b> toca o estoque (semeado via SQL direto).
 *
 * <p>Nome {@code *Test} (Surefire): o repo <b>nao</b> configura Failsafe — todas as integracoes
 * Testcontainers usam {@code *Test} para rodarem no {@code clean verify} (mesma nota do
 * {@code ProdutoApiTest}). Segredos de teste sao NAO-segredos (BCrypt de {@link UUID} de runtime).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ProdutoWriteTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-produtowrite-0123456789ab");
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
        Long adminId = inserirUsuario("admin-write@floricultura.local", "ADMIN");
        Long userId = inserirUsuario("user-write@floricultura.local", "USER");
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

    /** Insere um produto direto no banco com estoque_atual definido; devolve o id gerado. */
    private Long inserirProduto(String nome, String estoqueMin, String estoqueAtual) {
        return jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida, estoque_minimo, estoque_atual) "
                        + "VALUES (?, 'un', ?, ?) RETURNING id",
                Long.class, nome,
                new java.math.BigDecimal(estoqueMin), new java.math.BigDecimal(estoqueAtual));
    }

    // ---- CA-6: POST ADMIN 201 (estoqueAtual=0) / USER 403 / validacao 400 ---------------------

    @Test
    void criar_comAdmin_devolve201ComEstoqueAtualZero() throws Exception {
        String body = """
                {"nome":"Rosa Vermelha","descricao":"Maco com 12","unidadeMedida":"un",
                 "estoqueMinimo":10,"preco":4.50,"imagemUrl":"https://exemplo.local/rosa.jpg"}""";

        mockMvc.perform(post("/api/v1/produtos")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.nome").value("Rosa Vermelha"))
                .andExpect(jsonPath("$.data.unidadeMedida").value("un"))
                .andExpect(jsonPath("$.data.estoqueAtual").value(0.0)) // AD-SQ-30
                .andExpect(jsonPath("$.data.preco").value(4.50))
                .andExpect(jsonPath("$.data.ativo").value(true))
                .andExpect(jsonPath("$.data.criadoEm").isNotEmpty());
    }

    @Test
    void criar_comUser_devolve403() throws Exception {
        String body = """
                {"nome":"Rosa","unidadeMedida":"un","estoqueMinimo":1}""";

        mockMvc.perform(post("/api/v1/produtos")
                        .header(HttpHeaders.AUTHORIZATION, userBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void criar_semToken_devolve401() throws Exception {
        mockMvc.perform(post("/api/v1/produtos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Rosa\",\"unidadeMedida\":\"un\",\"estoqueMinimo\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void criar_unidadeForaDoEnum_devolve400ComDetails() throws Exception {
        String body = """
                {"nome":"Rosa","unidadeMedida":"x","estoqueMinimo":1}""";

        mockMvc.perform(post("/api/v1/produtos")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("unidadeMedida"));
    }

    @Test
    void criar_nomeVazio_devolve400() throws Exception {
        String body = """
                {"nome":"","unidadeMedida":"un","estoqueMinimo":1}""";

        mockMvc.perform(post("/api/v1/produtos")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("nome"));
    }

    @Test
    void criar_estoqueMinimoNegativo_devolve400() throws Exception {
        String body = """
                {"nome":"Rosa","unidadeMedida":"un","estoqueMinimo":-1}""";

        mockMvc.perform(post("/api/v1/produtos")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("estoqueMinimo"));
    }

    // ---- CA-9: preco opcional / negativo ------------------------------------------------------

    @Test
    void criar_semPreco_devolvePrecoNull() throws Exception {
        String body = """
                {"nome":"Lirio","unidadeMedida":"un","estoqueMinimo":5}""";

        mockMvc.perform(post("/api/v1/produtos")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.preco").doesNotExist());
    }

    @Test
    void criar_precoNegativo_devolve400() throws Exception {
        String body = """
                {"nome":"Cravo","unidadeMedida":"un","estoqueMinimo":1,"preco":-0.01}""";

        mockMvc.perform(post("/api/v1/produtos")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("preco"));
    }

    // ---- CA-7: PUT ADMIN 200 sem tocar estoque / USER 403 / 404 -------------------------------

    @Test
    void atualizar_comAdmin_devolve200ComEstoqueInalterado() throws Exception {
        Long id = inserirProduto("Rosa", "10", "25"); // estoque semeado = 25

        String body = """
                {"nome":"Rosa Editada","descricao":"nova","unidadeMedida":"kg",
                 "estoqueMinimo":15,"preco":9.90}""";

        mockMvc.perform(put("/api/v1/produtos/" + id)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nome").value("Rosa Editada"))
                .andExpect(jsonPath("$.data.unidadeMedida").value("kg"))
                .andExpect(jsonPath("$.data.estoqueMinimo").value(15.0))
                .andExpect(jsonPath("$.data.estoqueAtual").value(25.0)); // CA-7: intacto
    }

    @Test
    void atualizar_comUser_devolve403() throws Exception {
        Long id = inserirProduto("Rosa", "1", "5");
        String body = """
                {"nome":"Rosa Editada","unidadeMedida":"un","estoqueMinimo":2}""";

        mockMvc.perform(put("/api/v1/produtos/" + id)
                        .header(HttpHeaders.AUTHORIZATION, userBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void atualizar_inexistente_devolve404() throws Exception {
        String body = """
                {"nome":"Fantasma","unidadeMedida":"un","estoqueMinimo":1}""";

        mockMvc.perform(put("/api/v1/produtos/999999")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void atualizar_precoNegativo_devolve400() throws Exception {
        Long id = inserirProduto("Rosa", "1", "5");
        String body = """
                {"nome":"Rosa","unidadeMedida":"un","estoqueMinimo":1,"preco":-5}""";

        mockMvc.perform(put("/api/v1/produtos/" + id)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
