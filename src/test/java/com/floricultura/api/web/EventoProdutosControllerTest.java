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
 * Integracao da vitrine do M4.1 (T-M4.1-1, CA-1/CA-2 — SPEC-M4.1 §3.1) contra um PostgreSQL de
 * DESCARTE (Testcontainers postgres:16) — mesmo padrao do {@link EventoApiTest}: exercita a query
 * nativa {@code JOIN evento_produto}, o RBAC catch-all (USER 200, sem token 401), a paginacao
 * (tamanho=101 → 400) e o 404 antes de paginar (ancoras #1/#2/#3). Prova ordem {@code nome ASC},
 * {@code sazonal=true} fixo e {@code eventoIds=null} na variante de lista (sem {@code bytea}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class EventoProdutosControllerTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-vitrine-0123456789abcdef");
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
        jdbc.update("DELETE FROM evento_produto");
        jdbc.update("DELETE FROM evento");
        jdbc.update("DELETE FROM produto");
        jdbc.update("DELETE FROM usuario");
        Long userId = inserirUsuario("user-vitrine@floricultura.local", "USER");
        userBearer = "Bearer " + jwtService.gerarToken(userId);
    }

    private Long inserirUsuario(String email, String role) {
        return jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, ?, true, false) RETURNING id",
                Long.class, "Nome " + role, email,
                ENCODER.encode(UUID.randomUUID().toString()), role);
    }

    private Long inserirEvento(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO evento (nome, data_inicio, tipo, repete_todo_ano) "
                        + "VALUES (?, '2026-05-10'::date, 'COMEMORATIVA', false) RETURNING id",
                Long.class, nome);
    }

    private Long inserirProduto(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida, estoque_minimo, estoque_atual) "
                        + "VALUES (?, 'un', 10, 88) RETURNING id",
                Long.class, nome);
    }

    private void vincular(Long eventoId, Long produtoId) {
        jdbc.update("INSERT INTO evento_produto (evento_id, produto_id) VALUES (?, ?)",
                eventoId, produtoId);
    }

    /** CA-1: 200 ordenado nome ASC, sazonal=true fixo, eventoIds=null, so os vinculados. */
    @Test
    void listar_eventoComProdutos_devolve200OrdenadoPorNomeComSazonalTrue() throws Exception {
        Long eventoId = inserirEvento("Dia das Maes");
        vincular(eventoId, inserirProduto("Rosa Vermelha"));
        vincular(eventoId, inserirProduto("Girassol"));
        inserirProduto("Orquidea"); // nao vinculado — nao pode aparecer

        mockMvc.perform(get("/api/v1/eventos/" + eventoId + "/produtos?pagina=0&tamanho=50")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalElementos").value(2))
                .andExpect(jsonPath("$.data.tamanho").value(50))
                // nome ASC → Girassol antes de Rosa Vermelha (orquidea fora: totalElementos=2).
                .andExpect(jsonPath("$.data.conteudo[0].nome").value("Girassol"))
                .andExpect(jsonPath("$.data.conteudo[1].nome").value("Rosa Vermelha"))
                // sazonal fixo em true e eventoIds omitido (null) na variante de lista.
                .andExpect(jsonPath("$.data.conteudo[0].sazonal").value(true))
                .andExpect(jsonPath("$.data.conteudo[1].sazonal").value(true))
                .andExpect(jsonPath("$.data.conteudo[0].eventoIds").doesNotExist());
    }

    /** CA-1: evento inexistente → 404 (antes de paginar, ancora #2). */
    @Test
    void listar_eventoInexistente_devolve404() throws Exception {
        mockMvc.perform(get("/api/v1/eventos/999999/produtos")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    /** CA-1: sem token → 401 (RBAC catch-all autenticado). */
    @Test
    void listar_semToken_devolve401() throws Exception {
        Long eventoId = inserirEvento("Natal");
        mockMvc.perform(get("/api/v1/eventos/" + eventoId + "/produtos"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    /** CA-1: tamanho=101 → 400 paginacao invalida (ancora #1). */
    @Test
    void listar_tamanhoAcimaDoMaximo_devolve400() throws Exception {
        Long eventoId = inserirEvento("Finados");
        mockMvc.perform(get("/api/v1/eventos/" + eventoId + "/produtos?tamanho=101")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("tamanho"));
    }

    /** CA-2: evento existente sem produtos → 200 conteudo=[] totalElementos=0. */
    @Test
    void listar_eventoSemProdutos_devolve200ComConteudoVazio() throws Exception {
        Long eventoId = inserirEvento("Dia dos Pais");
        mockMvc.perform(get("/api/v1/eventos/" + eventoId + "/produtos")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(0))
                .andExpect(jsonPath("$.data.conteudo").isEmpty());
    }
}
