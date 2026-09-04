package com.floricultura.api.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
 * Integracao da migracao V9 (T-M5-1, CA-9 — camada de schema) contra um PostgreSQL de DESCARTE
 * (Testcontainers postgres:16). Sobe o contexto real (datasource/JPA/Flyway ativos com {@code
 * ddl-auto=validate}): o Flyway aplica V1..V9 na subida e as asercoes validam via JDBC (dispensa
 * {@code psql}).
 *
 * <p>Cobre a evolucao dos stubs `cliente`/`fornecedor` do M0 (AD-SQ-58) sob minimizacao LGPD: DROP
 * de {@code tipo_pessoa}/{@code documento}/{@code endereco}, {@code telefone -> VARCHAR(40)}, nova
 * coluna {@code observacoes VARCHAR(500)}, indices {@code ix_cliente_nome}/{@code ix_fornecedor_nome},
 * e as duas juncoes N:N {@code cliente_produto}/{@code fornecedor_produto} (AD-SQ-44) com PK composta
 * + FKs {@code ON DELETE CASCADE} nos dois lados. O boot com {@code validate} verde comprova que o
 * schema V9 nao quebra as entidades ja mapeadas (M0..M4).
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

    private Long inserirProduto(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida) VALUES (?, 'un') RETURNING id",
                Long.class, nome);
    }

    private Long inserirCadastro(String tabela, String nome) {
        return jdbc.queryForObject(
                "INSERT INTO " + tabela + " (nome) VALUES (?) RETURNING id", Long.class, nome);
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

    // ----- CA-9: junções N:N com PK composta, FKs e indices -----

    /** cliente_produto e fornecedor_produto existem com PK composta, FKs e indice por produto. */
    @Test
    void tabelasDeJuncaoExistemComPkFksEIndice() {
        List<String> tabelas = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);
        assertThat(tabelas).contains("cliente_produto", "fornecedor_produto");

        List<String> cpConstraints = jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'cliente_produto'::regclass",
                String.class);
        assertThat(cpConstraints).contains("pk_cliente_produto", "fk_cp_cliente", "fk_cp_produto");

        List<String> fpConstraints = jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'fornecedor_produto'::regclass",
                String.class);
        assertThat(fpConstraints).contains(
                "pk_fornecedor_produto", "fk_fp_fornecedor", "fk_fp_produto");

        assertThat(jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'cliente_produto'", String.class))
                .contains("ix_cp_produto");
        assertThat(jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'fornecedor_produto'", String.class))
                .contains("ix_fp_produto");
    }

    /** Hard delete do CADASTRO (FC-08) remove os vinculos por cascade e preserva o produto. */
    @Test
    void hardDeleteDoCadastroRemoveVinculoEPreservaProduto() {
        for (String cadastro : List.of("cliente", "fornecedor")) {
            String juncao = cadastro + "_produto";
            Long cadastroId = inserirCadastro(cadastro, "Maria Flores");
            Long produtoId = inserirProduto("Rosa " + cadastro);
            jdbc.update("INSERT INTO " + juncao + " (" + cadastro + "_id, produto_id) VALUES (?, ?)",
                    cadastroId, produtoId);

            jdbc.update("DELETE FROM " + cadastro + " WHERE id = ?", cadastroId);

            Integer links = jdbc.queryForObject(
                    "SELECT count(*) FROM " + juncao + " WHERE produto_id = ?", Integer.class, produtoId);
            assertThat(links).as(juncao + " apos delete do " + cadastro).isZero();
            Integer produtos = jdbc.queryForObject(
                    "SELECT count(*) FROM produto WHERE id = ?", Integer.class, produtoId);
            assertThat(produtos).as("produto preservado apos delete do " + cadastro).isEqualTo(1);
        }
    }

    /** Hard delete do PRODUTO remove os vinculos por cascade e preserva o cadastro. */
    @Test
    void hardDeleteDoProdutoRemoveVinculoEPreservaCadastro() {
        for (String cadastro : List.of("cliente", "fornecedor")) {
            String juncao = cadastro + "_produto";
            Long cadastroId = inserirCadastro(cadastro, "Contato Fornecimento");
            Long produtoId = inserirProduto("Lirio " + cadastro);
            jdbc.update("INSERT INTO " + juncao + " (" + cadastro + "_id, produto_id) VALUES (?, ?)",
                    cadastroId, produtoId);

            assertThatCode(() -> jdbc.update("DELETE FROM produto WHERE id = ?", produtoId))
                    .doesNotThrowAnyException();

            Integer links = jdbc.queryForObject(
                    "SELECT count(*) FROM " + juncao + " WHERE " + cadastro + "_id = ?",
                    Integer.class, cadastroId);
            assertThat(links).as(juncao + " apos delete do produto").isZero();
            Integer cadastros = jdbc.queryForObject(
                    "SELECT count(*) FROM " + cadastro + " WHERE id = ?", Integer.class, cadastroId);
            assertThat(cadastros).as(cadastro + " preservado apos delete do produto").isEqualTo(1);
        }
    }
}
