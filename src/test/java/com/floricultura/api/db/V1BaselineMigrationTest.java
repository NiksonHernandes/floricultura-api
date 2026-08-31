package com.floricultura.api.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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
 * Integracao da migracao baseline (T-M0-3, CA-4/CA-6) contra um PostgreSQL de DESCARTE
 * (Testcontainers postgres:16) — nunca banco compartilhado/producao. Sobe o contexto real
 * (datasource/JPA/Flyway ativos): o Flyway aplica {@code V1__baseline.sql} na subida e as
 * asercoes validam o schema via JDBC (dispensa {@code psql}).
 */
@SpringBootTest
@Testcontainers
class V1BaselineMigrationTest {

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

    /** CA-4: conexao viva ao PostgreSQL do container. */
    @Test
    void conexaoComBancoEstaViva() {
        Integer um = jdbc.queryForObject("SELECT 1", Integer.class);
        assertThat(um).isEqualTo(1);
    }

    /** CA-4/CA-6: Flyway aplicou a versao 1 com sucesso. */
    @Test
    void flywayAplicouVersao1ComSucesso() {
        var row = jdbc.queryForMap(
                "SELECT version, success FROM flyway_schema_history WHERE installed_rank = 1");
        assertThat(row.get("version")).isEqualTo("1");
        assertThat(row.get("success")).isEqualTo(Boolean.TRUE);
    }

    /** CA-6: as 7 tabelas do escopo M0 existem no schema public. */
    @Test
    void seteTabelasDoEscopoExistem() {
        List<String> tabelas = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);
        assertThat(tabelas).contains(
                "usuario", "produto", "movimentacao_estoque", "evento",
                "cliente", "fornecedor", "contato");
    }

    /** CA-6: o ledger e insert-only — UPDATE e rejeitado pelo trigger de imutabilidade. */
    @Test
    void updateNoLedgerEhRejeitadoPeloTrigger() {
        jdbc.update("INSERT INTO movimentacao_estoque "
                + "(produto_nome, tipo, quantidade, quantidade_resultante) "
                + "VALUES ('Rosa (teste)', 'ENTRADA', 10, 10)");

        assertThatThrownBy(() ->
                jdbc.update("UPDATE movimentacao_estoque SET motivo = 'x'"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("imutavel");
    }

    /** CA-6: o ledger e insert-only — DELETE e rejeitado pelo trigger de imutabilidade. */
    @Test
    void deleteNoLedgerEhRejeitadoPeloTrigger() {
        jdbc.update("INSERT INTO movimentacao_estoque "
                + "(produto_nome, tipo, quantidade, quantidade_resultante) "
                + "VALUES ('Lirio (teste)', 'SAIDA', 5, 5)");

        assertThatThrownBy(() ->
                jdbc.update("DELETE FROM movimentacao_estoque"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("imutavel");
    }
}
