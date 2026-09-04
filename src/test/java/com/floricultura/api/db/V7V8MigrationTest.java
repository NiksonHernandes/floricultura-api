package com.floricultura.api.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integracao das migracoes V7/V8 (T-M4-9, CA-19/CA-22 — camada de schema) contra um PostgreSQL de
 * DESCARTE (Testcontainers postgres:16). Cobre: V7 adiciona {@code usuario_nome VARCHAR(120)}
 * anulavel (AD-SQ-45); V8 habilita {@code pg_trgm} + indices GIN trigram em {@code produto_nome}/
 * {@code usuario_nome} (AD-SQ-46/PA#1). Confirma tambem o {@code flyway_schema_history} ate V8.
 */
@SpringBootTest
@Testcontainers
class V7V8MigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Divida §12: teste de migracao auto-contido (nao depende do env do surefire).
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-v7v8-migration-0123456789abcdef");
    }

    @Autowired
    private JdbcTemplate jdbc;

    /** Flyway aplicou V7 e V8 com success=true (o proposito do teste: efeito das duas migracoes). */
    @Test
    void flywayAplicouAteV8ComSucesso() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT version, success FROM flyway_schema_history "
                        + "WHERE version IN ('7', '8') ORDER BY version");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("version")).isEqualTo("7");
        assertThat(rows.get(0).get("success")).isEqualTo(Boolean.TRUE);
        assertThat(rows.get(1).get("version")).isEqualTo("8");
        assertThat(rows.get(1).get("success")).isEqualTo(Boolean.TRUE);
        // Head-pin removido (autorizacao direta do dono, M5): fixar "head == 8" mentia assim que a V9
        // virou o novo head — e quebraria a cada migracao futura. Nao e o proposito deste teste, que
        // afere o EFEITO de V7/V8; V3V4MigrationTest/V6MigrationTest tambem nao fixam head.
    }

    /** V7: coluna usuario_nome existe como varchar(120) anulavel (snapshot, sem backfill). */
    @Test
    void colunaUsuarioNomeExisteComoVarchar120Anulavel() {
        Map<String, Object> col = jdbc.queryForMap(
                "SELECT data_type, character_maximum_length, is_nullable "
                        + "FROM information_schema.columns "
                        + "WHERE table_name = 'movimentacao_estoque' AND column_name = 'usuario_nome'");
        assertThat(col.get("data_type")).isEqualTo("character varying");
        assertThat(((Number) col.get("character_maximum_length")).intValue()).isEqualTo(120);
        assertThat(col.get("is_nullable")).isEqualTo("YES");
    }

    /** V8: a extensao pg_trgm esta instalada (PA#1 confirmada no gate). */
    @Test
    void extensaoPgTrgmEstaInstalada() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'pg_trgm'", Integer.class);
        assertThat(n).isEqualTo(1);
    }

    /** V8: os dois indices GIN trigram existem em movimentacao_estoque. */
    @Test
    void indicesGinTrigramExistem() {
        List<String> indices = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'movimentacao_estoque'",
                String.class);
        assertThat(indices).contains("ix_mov_produto_nome_trgm", "ix_mov_usuario_nome_trgm");

        // Confirma que sao GIN (o indice correto p/ ILIKE '%..%' com curinga a esquerda).
        List<String> metodos = jdbc.queryForList(
                "SELECT am.amname FROM pg_class c "
                        + "JOIN pg_index i ON i.indexrelid = c.oid "
                        + "JOIN pg_am am ON am.oid = c.relam "
                        + "WHERE c.relname IN ('ix_mov_produto_nome_trgm', 'ix_mov_usuario_nome_trgm')",
                String.class);
        assertThat(metodos).containsOnly("gin");
    }
}
