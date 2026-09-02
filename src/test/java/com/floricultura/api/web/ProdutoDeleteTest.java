package com.floricultura.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.floricultura.api.service.JwtService;
import java.util.Map;
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
 * Hard delete de produto (T-M2-3, CA-8/CA-13 — IRREVERSIVEL) contra um PostgreSQL de DESCARTE
 * (Testcontainers postgres:16), com {@code ProdutoController}/{@code ProdutoService},
 * {@code SecurityConfig} e filtro JWT reais. Prova o fluxo de ponta a ponta que o {@code
 * V3V4MigrationTest} exercita via SQL: ADMIN {@code DELETE} → 204, o produto some e as movimentacoes
 * daquele produto sobrevivem com {@code produto_id=NULL} e {@code produto_nome} preservado (o cascade
 * {@code ON DELETE SET NULL} liberado pela V4/AD-SQ-34); USER → 403; inexistente → 404.
 *
 * <p>Nome {@code *Test} (Surefire): o repo <b>nao</b> configura Failsafe (mesma nota do
 * {@code ProdutoApiTest}). Segredos de teste sao NAO-segredos (BCrypt de {@link UUID} de runtime).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ProdutoDeleteTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-produtodelete-0123456789ab");
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
        // TRUNCATE (nao DELETE): o ledger e imutavel — a trigger BEFORE DELETE rejeita DELETE de linhas
        // ja gravadas (por design, AD-SQ-8); TRUNCATE nao dispara trigger row-level, limpando o teste.
        jdbc.execute("TRUNCATE TABLE movimentacao_estoque");
        jdbc.update("DELETE FROM produto");
        jdbc.update("DELETE FROM usuario");
        Long adminId = inserirUsuario("admin-del@floricultura.local", "ADMIN");
        Long userId = inserirUsuario("user-del@floricultura.local", "USER");
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

    private Long inserirProduto(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida, estoque_minimo, estoque_atual) "
                        + "VALUES (?, 'un', 1, 10) RETURNING id",
                Long.class, nome);
    }

    /** Insere uma linha no ledger (insert-only, permitido) vinculada ao produto; devolve o id. */
    private Long inserirMovimentacao(Long produtoId, String nomeSnapshot) {
        return jdbc.queryForObject(
                "INSERT INTO movimentacao_estoque "
                        + "(produto_id, produto_nome, tipo, quantidade, quantidade_resultante, motivo) "
                        + "VALUES (?, ?, 'ENTRADA', 10, 10, 'compra') RETURNING id",
                Long.class, produtoId, nomeSnapshot);
    }

    // ---- CA-8: ADMIN hard delete 204 + ledger sobrevive (produto_id NULL, produto_nome intacto) ---

    @Test
    void deletar_comAdmin_produtoComMovimentacoes_devolve204EPreservaLedger() throws Exception {
        Long produtoId = inserirProduto("Orquidea");
        Long movId = inserirMovimentacao(produtoId, "Orquidea");

        mockMvc.perform(delete("/api/v1/produtos/" + produtoId)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNoContent());

        // Produto removido do banco.
        Integer produtos = jdbc.queryForObject(
                "SELECT count(*) FROM produto WHERE id = ?", Integer.class, produtoId);
        assertThat(produtos).isZero();

        // Ledger sobrevive: produto_id anulado pelo cascade (V4), produto_nome (snapshot) intacto.
        Map<String, Object> mov = jdbc.queryForMap(
                "SELECT produto_id, produto_nome FROM movimentacao_estoque WHERE id = ?", movId);
        assertThat(mov.get("produto_id")).isNull();
        assertThat(mov.get("produto_nome")).isEqualTo("Orquidea");
    }

    @Test
    void deletar_comAdmin_produtoSemMovimentacoes_devolve204() throws Exception {
        Long produtoId = inserirProduto("Tulipa");

        mockMvc.perform(delete("/api/v1/produtos/" + produtoId)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNoContent());

        Integer produtos = jdbc.queryForObject(
                "SELECT count(*) FROM produto WHERE id = ?", Integer.class, produtoId);
        assertThat(produtos).isZero();
    }

    // ---- CA-8: RBAC e 404 ---------------------------------------------------------------------

    @Test
    void deletar_comUser_devolve403EProdutoPermanece() throws Exception {
        Long produtoId = inserirProduto("Girassol");

        mockMvc.perform(delete("/api/v1/produtos/" + produtoId)
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        Integer produtos = jdbc.queryForObject(
                "SELECT count(*) FROM produto WHERE id = ?", Integer.class, produtoId);
        assertThat(produtos).isEqualTo(1); // USER nao apagou nada
    }

    @Test
    void deletar_semToken_devolve401() throws Exception {
        mockMvc.perform(delete("/api/v1/produtos/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void deletar_inexistente_devolve404() throws Exception {
        mockMvc.perform(delete("/api/v1/produtos/999999")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
