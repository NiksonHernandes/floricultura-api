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
 * Integracao da migracao V9 (CA-9 — camada de schema) contra um PostgreSQL de DESCARTE (Testcontainers
 * postgres:16). Sobe o contexto real (datasource/JPA/Flyway ativos com {@code ddl-auto=validate}): o
 * Flyway aplica V1..V9 na subida e as asercoes validam via JDBC (dispensa {@code psql}).
 *
 * <p>Cobre a evolucao dos stubs `cliente`/`fornecedor` do M0 (AD-SQ-58) sob minimizacao LGPD: DROP de
 * {@code tipo_pessoa}/{@code documento}/{@code endereco}, {@code telefone -> VARCHAR(40)}, nova coluna
 * {@code observacoes VARCHAR(500)} e indices {@code ix_cliente_nome}/{@code ix_fornecedor_nome}.
 *
 * <p><b>Revisao 2026-09-04 (AD-SQ-65 / R-CA-9):</b> a V9 foi editada in-place e <b>nao</b> cria mais as
 * juncoes N:N {@code cliente_produto}/{@code fornecedor_produto} — o vinculo passou a ser derivado da
 * movimentacao. Este teste passa a provar a <b>ausencia</b> dessas tabelas. O boot com {@code validate}
 * verde comprova que o schema V9 nao quebra as entidades ja mapeadas (M0..M4 + M5).
 */
@SpringBootTest
@Testcontainers
class V9MigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Divida §12: teste de migracao auto-contido (nao depende do env do surefire).
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-v9-migration-0123456789abcdef");
    }

    @Autowired
    private JdbcTemplate jdbc;

    private Map<String, Object> coluna(String tabela, String nome) {
        return jdbc.queryForList(
                "SELECT data_type, character_maximum_length, is_nullable "
                        + "FROM information_schema.columns "
                        + "WHERE table_name = ? AND column_name = ?", tabela, nome)
                .stream().findFirst().orElse(null);
    }

    // ----- CA-9: V9 aplicada + boot validate verde -----

    /** Flyway registrou a versao 9 com success=true (o boot com validate ja subiu — schema bate). */
    @Test
    void flywayAplicouVersao9ComSucesso() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT version, success FROM flyway_schema_history WHERE version = '9'");
        assertThat(row.get("version")).isEqualTo("9");
        assertThat(row.get("success")).isEqualTo(Boolean.TRUE);
    }

    // ----- CA-9: minimizacao — colunas de dado sensivel removidas -----

    /** cliente e fornecedor NAO tem mais tipo_pessoa/documento/endereco (minimizacao LGPD). */
    @Test
    void dropouColunasSensiveisDosDoisCadastros() {
        for (String tabela : List.of("cliente", "fornecedor")) {
            assertThat(coluna(tabela, "tipo_pessoa")).as(tabela + ".tipo_pessoa").isNull();
            assertThat(coluna(tabela, "documento")).as(tabela + ".documento").isNull();
            assertThat(coluna(tabela, "endereco")).as(tabela + ".endereco").isNull();
        }
    }

    /** observacoes existe como VARCHAR(500), opcional, nos dois cadastros. */
    @Test
    void observacoesExisteComoVarchar500() {
        for (String tabela : List.of("cliente", "fornecedor")) {
            Map<String, Object> obs = coluna(tabela, "observacoes");
            assertThat(obs).as(tabela + ".observacoes").isNotNull();
            assertThat(obs.get("data_type")).isEqualTo("character varying");
            assertThat(((Number) obs.get("character_maximum_length")).intValue()).isEqualTo(500);
            assertThat(obs.get("is_nullable")).isEqualTo("YES");
        }
    }

    /** telefone foi ampliado para VARCHAR(40) (string livre) nos dois cadastros. */
    @Test
    void telefoneAgoraEVarchar40() {
        for (String tabela : List.of("cliente", "fornecedor")) {
            Map<String, Object> tel = coluna(tabela, "telefone");
            assertThat(tel).as(tabela + ".telefone").isNotNull();
            assertThat(tel.get("data_type")).isEqualTo("character varying");
            assertThat(((Number) tel.get("character_maximum_length")).intValue()).isEqualTo(40);
        }
    }

    /** Os indices de nome (ordenacao + ILIKE) existem nos dois cadastros. */
    @Test
    void indicesDeNomeExistem() {
        List<String> ixCliente = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'cliente'", String.class);
        assertThat(ixCliente).contains("ix_cliente_nome");
        List<String> ixFornecedor = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'fornecedor'", String.class);
        assertThat(ixFornecedor).contains("ix_fornecedor_nome");
    }

    // ----- R-CA-9: vinculo virou derivado — as juncoes N:N NAO existem mais -----

    /** A V9 editada in-place (AD-SQ-65) nao cria mais cliente_produto/fornecedor_produto. */
    @Test
    void juncoesNaoExistemMais() {
        List<String> tabelas = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);
        assertThat(tabelas).doesNotContain("cliente_produto", "fornecedor_produto");
    }
}
