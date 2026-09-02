package com.floricultura.api.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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
 * Integracao das migracoes V3/V4 (T-M2-1, CA-1/CA-13) contra um PostgreSQL de DESCARTE
 * (Testcontainers postgres:16). Sobe o contexto real (datasource/JPA/Flyway ativos): o Flyway
 * aplica V1..V4 na subida e as asercoes validam via JDBC (dispensa {@code psql}).
 *
 * <p>Cobre: <b>CA-1</b> — V3+V4 aplicadas, coluna {@code preco NUMERIC(14,2)} + {@code
 * ck_produto_preco} + {@code ck_produto_unidade}; <b>CA-13</b> — o ledger rejeita UPDATE/DELETE
 * diretos MAS o cascade {@code ON DELETE SET NULL} do hard delete de produto e permitido (V4/
 * AD-SQ-34), preservando {@code produto_nome}.
 */
@SpringBootTest
@Testcontainers
class V3V4MigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbc;

    /** Insere um produto minimo (V1+V3) e devolve o id gerado. */
    private Long inserirProduto(String nome, String unidade) {
        return jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida) VALUES (?, ?) RETURNING id",
                Long.class, nome, unidade);
    }

    /** Insere uma linha no ledger vinculada a um produto e devolve o id gerado. */
    private Long inserirMovimentacao(Long produtoId, String nomeSnapshot) {
        return jdbc.queryForObject(
                "INSERT INTO movimentacao_estoque "
                        + "(produto_id, produto_nome, tipo, quantidade, quantidade_resultante, motivo) "
                        + "VALUES (?, ?, 'ENTRADA', 10, 10, 'compra') RETURNING id",
                Long.class, produtoId, nomeSnapshot);
    }

    // ----- CA-1: V3/V4 aplicadas + coluna/CHECKs -----

    /** CA-1: Flyway registrou as versoes 3 e 4 com success=true. */
    @Test
    void flywayAplicouVersoes3e4ComSucesso() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT version, success FROM flyway_schema_history "
                        + "WHERE version IN ('3', '4') ORDER BY version");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("version")).isEqualTo("3");
        assertThat(rows.get(0).get("success")).isEqualTo(Boolean.TRUE);
        assertThat(rows.get(1).get("version")).isEqualTo("4");
        assertThat(rows.get(1).get("success")).isEqualTo(Boolean.TRUE);
    }

    /** CA-1: coluna produto.preco existe como numeric(14,2). */
    @Test
    void colunaPrecoExisteComoNumeric14x2() {
        Map<String, Object> col = jdbc.queryForMap(
                "SELECT data_type, numeric_precision, numeric_scale "
                        + "FROM information_schema.columns "
                        + "WHERE table_name = 'produto' AND column_name = 'preco'");
        assertThat(col.get("data_type")).isEqualTo("numeric");
        assertThat(((Number) col.get("numeric_precision")).intValue()).isEqualTo(14);
        assertThat(((Number) col.get("numeric_scale")).intValue()).isEqualTo(2);
    }

    /** CA-1: ck_produto_preco — aceita NULL e >=0, rejeita negativo. */
    @Test
    void checkDePrecoAceitaNuloEPositivoRejeitaNegativo() {
        assertThatCode(() -> jdbc.update(
                "INSERT INTO produto (nome, unidade_medida, preco) VALUES ('Rosa', 'un', NULL)"))
                .doesNotThrowAnyException();
        assertThatCode(() -> jdbc.update(
                "INSERT INTO produto (nome, unidade_medida, preco) VALUES ('Lirio', 'un', 4.50)"))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO produto (nome, unidade_medida, preco) VALUES ('Cravo', 'un', -1)"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_produto_preco");
    }

    /** CA-1: ck_produto_unidade — aceita o enum, rejeita valor fora dele. */
    @Test
    void checkDeUnidadeAceitaEnumRejeitaForaDele() {
        for (String unidade : List.of("un", "kg", "saco", "m3", "l", "g")) {
            assertThatCode(() -> inserirProduto("Prod " + unidade, unidade))
                    .doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> inserirProduto("Invalido", "xx"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_produto_unidade");
    }

    // ----- CA-13: imutabilidade preservada + cascade liberado -----

    /** CA-13: UPDATE de conteudo do ledger continua rejeitado pela trigger (V4 nao afrouxou isso). */
    @Test
    void updateDeConteudoNoLedgerContinuaRejeitado() {
        Long produtoId = inserirProduto("Girassol", "un");
        inserirMovimentacao(produtoId, "Girassol");

        assertThatThrownBy(() ->
                jdbc.update("UPDATE movimentacao_estoque SET motivo = 'x'"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("imutavel");

        assertThatThrownBy(() ->
                jdbc.update("UPDATE movimentacao_estoque SET quantidade = 999"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("imutavel");
    }

    /** CA-13: DELETE direto no ledger continua rejeitado pela trigger. */
    @Test
    void deleteDiretoNoLedgerContinuaRejeitado() {
        Long produtoId = inserirProduto("Tulipa", "un");
        inserirMovimentacao(produtoId, "Tulipa");

        assertThatThrownBy(() ->
                jdbc.update("DELETE FROM movimentacao_estoque"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("imutavel");
    }

    /**
     * CA-13/CA-8: o hard delete de produto com movimentacoes e permitido (V4/AD-SQ-34) — o cascade
     * {@code ON DELETE SET NULL} anula {@code produto_id} e o ledger sobrevive com {@code
     * produto_nome} preservado.
     */
    @Test
    void hardDeleteDeProdutoAnulaFkNoLedgerEPreservaSnapshot() {
        Long produtoId = inserirProduto("Orquidea", "un");
        Long movId = inserirMovimentacao(produtoId, "Orquidea");

        assertThatCode(() ->
                jdbc.update("DELETE FROM produto WHERE id = ?", produtoId))
                .doesNotThrowAnyException();

        // Produto removido.
        Integer produtos = jdbc.queryForObject(
                "SELECT count(*) FROM produto WHERE id = ?", Integer.class, produtoId);
        assertThat(produtos).isZero();

        // Movimentacao sobrevive: produto_id anulado, produto_nome (snapshot) intacto.
        Map<String, Object> mov = jdbc.queryForMap(
                "SELECT produto_id, produto_nome FROM movimentacao_estoque WHERE id = ?", movId);
        assertThat(mov.get("produto_id")).isNull();
        assertThat(mov.get("produto_nome")).isEqualTo("Orquidea");
    }
}
