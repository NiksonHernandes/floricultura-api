package com.floricultura.api.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Anti-burla CA-26 (T-M4-9): a trigger {@code trg_movimentacao_imutavel} ENDURECIDA na V7 continua
 * insert-only e agora tambem barra alteracao de {@code usuario_nome} embutida no cascade legitimo.
 * Integracao contra PostgreSQL de DESCARTE (Testcontainers postgres:16), Flyway V1..V8 na subida.
 *
 * <p>Casos: (1) UPDATE de {@code usuario_nome} arbitrario -> exceção; (2) DELETE arbitrario ->
 * exceção; (3) cascade "puro" ({@code produto_id -> NULL}, resto identico) -> PASSA, preservando o
 * {@code usuario_nome}; (4) cascade + {@code usuario_nome} alterado juntos -> FALHA (o guarda novo
 * da V7). Complementa os testes de imutabilidade herdados de V1/V4 (NAO os substitui — anti-burla).
 */
@SpringBootTest
@Testcontainers
class MovimentacaoImutavelTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Divida §12: teste de migracao/imutabilidade auto-contido (nao depende do env do surefire).
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-imutavel-0123456789abcdef");
    }

    @Autowired
    private JdbcTemplate jdbc;

    private Long inserirProduto(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida) VALUES (?, 'un') RETURNING id",
                Long.class, nome);
    }

    /** Insere uma linha do ledger ja com autor desnormalizado (usuario_nome). */
    private Long inserirMovimentacao(Long produtoId, String produtoNome, String usuarioNome) {
        return jdbc.queryForObject(
                "INSERT INTO movimentacao_estoque "
                        + "(produto_id, produto_nome, tipo, quantidade, quantidade_resultante, usuario_nome) "
                        + "VALUES (?, ?, 'ENTRADA', 10, 10, ?) RETURNING id",
                Long.class, produtoId, produtoNome, usuarioNome);
    }

    // ----- CA-26: UPDATE/DELETE arbitrarios continuam rejeitados -----

    /** UPDATE arbitrario de usuario_nome e rejeitado pela trigger endurecida (V7). */
    @Test
    void updateDeUsuarioNomeERejeitado() {
        Long produtoId = inserirProduto("Rosa");
        inserirMovimentacao(produtoId, "Rosa", "Ana");

        assertThatThrownBy(() ->
                jdbc.update("UPDATE movimentacao_estoque SET usuario_nome = 'Hacker'"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("imutavel");
    }

    /** DELETE arbitrario no ledger continua rejeitado. */
    @Test
    void deleteArbitrarioERejeitado() {
        Long produtoId = inserirProduto("Lirio");
        inserirMovimentacao(produtoId, "Lirio", "Bia");

        assertThatThrownBy(() ->
                jdbc.update("DELETE FROM movimentacao_estoque"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("imutavel");
    }

    // ----- CA-26: cascade puro passa; cascade + usuario_nome alterado falha -----

    /**
     * Cascade "puro": o hard delete do produto anula produto_id (ON DELETE SET NULL) e a linha
     * sobrevive com produto_nome E usuario_nome (snapshot) intactos — a trigger permite.
     */
    @Test
    void cascadePuroProdutoIdParaNullEhPermitidoEPreservaAutor() {
        Long produtoId = inserirProduto("Orquidea");
        Long movId = inserirMovimentacao(produtoId, "Orquidea", "Carla");

        assertThatCode(() ->
                jdbc.update("DELETE FROM produto WHERE id = ?", produtoId))
                .doesNotThrowAnyException();

        Map<String, Object> mov = jdbc.queryForMap(
                "SELECT produto_id, produto_nome, usuario_nome FROM movimentacao_estoque WHERE id = ?",
                movId);
        assertThat(mov.get("produto_id")).isNull();
        assertThat(mov.get("produto_nome")).isEqualTo("Orquidea");
        assertThat(mov.get("usuario_nome")).isEqualTo("Carla");
    }

    /**
     * O guarda novo da V7: um UPDATE que anula produto_id (mimetizando o cascade legitimo) MAS
     * altera usuario_nome junto e REJEITADO — nao da para se esconder no cascade.
     */
    @Test
    void cascadeComUsuarioNomeAlteradoJuntoERejeitado() {
        Long produtoId = inserirProduto("Girassol");
        Long movId = inserirMovimentacao(produtoId, "Girassol", "Dora");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE movimentacao_estoque SET produto_id = NULL, usuario_nome = 'Hacker' WHERE id = ?",
                movId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("imutavel");

        // A linha permanece intacta (nada foi alterado).
        Map<String, Object> mov = jdbc.queryForMap(
                "SELECT produto_id, usuario_nome FROM movimentacao_estoque WHERE id = ?", movId);
        assertThat(mov.get("produto_id")).isEqualTo(produtoId);
        assertThat(mov.get("usuario_nome")).isEqualTo("Dora");
    }
}
