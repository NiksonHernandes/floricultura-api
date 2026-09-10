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
 * Integracao da migracao V10 (RB-2, R-CA-9/R-CA-6 — SPEC-M5 §R3.2) contra um PostgreSQL de DESCARTE
 * (Testcontainers postgres:16). Sobe o contexto real (datasource/JPA/Flyway com {@code
 * ddl-auto=validate}): o Flyway aplica V1..V10 na subida e as asercoes validam via JDBC.
 *
 * <p>Prova a contraparte da movimentacao (AD-SQ-64): 4 colunas novas ({@code fornecedor_id}/{@code
 * fornecedor_nome}/{@code cliente_id}/{@code cliente_nome}), 2 FKs {@code ON DELETE SET NULL}, 2 CHECK
 * por tipo, 2 indices e {@code head = V10}. E prova o endurecimento da trigger {@code
 * trg_movimentacao_imutavel} nos dois sentidos:
 * <ul>
 *   <li><b>Cascade tolerado (R-CA-6/LGPD):</b> o hard delete de um cliente/fornecedor <b>usado</b> numa
 *       movimentacao anula o {@code *_id} (cascade) e <b>preserva</b> o snapshot {@code *_nome} — a
 *       trigger NAO bloqueia.</li>
 *   <li><b>Imutabilidade preservada (AD-SQ-48):</b> um UPDATE de <b>conteudo</b> (ex.: {@code quantidade})
 *       continua BARRADO; e um {@code DELETE FROM usuario} direto continua BARRADO (o cascade {@code SET
 *       NULL} sobre {@code usuario_id} e rejeitado — remocao de usuario e desativacao, nao hard delete).</li>
 * </ul>
 * Dados ficticios (LGPD): "Sitio Verde" / "Maria Flores".
 */
@SpringBootTest
@Testcontainers
class V10MigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-v10-migration-0123456789abcdef");
    }

    @Autowired
    private JdbcTemplate jdbc;

    private Map<String, Object> coluna(String nome) {
        return jdbc.queryForList(
                "SELECT data_type, character_maximum_length "
                        + "FROM information_schema.columns "
                        + "WHERE table_name = 'movimentacao_estoque' AND column_name = ?", nome)
                .stream().findFirst().orElse(null);
    }

    private Long inserirUsuario() {
        return jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES ('Nome ADMIN', 'v10-admin@floricultura.local', 'x', 'ADMIN', true, false) "
                        + "RETURNING id", Long.class);
    }

    private Long inserirCliente(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO cliente (nome) VALUES (?) RETURNING id", Long.class, nome);
    }

    private Long inserirFornecedor(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO fornecedor (nome) VALUES (?) RETURNING id", Long.class, nome);
    }

    /** Insere uma linha do ledger (INSERT nao dispara a trigger — so UPDATE/DELETE). */
    private Long inserirMovimentacao(
            String tipo, Long fornecedorId, String fornecedorNome,
            Long clienteId, String clienteNome, Long usuarioId) {
        return jdbc.queryForObject(
                "INSERT INTO movimentacao_estoque "
                        + "(produto_nome, tipo, quantidade, quantidade_resultante, "
                        + " fornecedor_id, fornecedor_nome, cliente_id, cliente_nome, usuario_id) "
                        + "VALUES ('Rosa', ?, 10, 10, ?, ?, ?, ?, ?) RETURNING id",
                Long.class, tipo, fornecedorId, fornecedorNome, clienteId, clienteNome, usuarioId);
    }

    // ----- R-CA-9: V10 aplicada + head=V10 + boot validate verde -----

    /** Flyway registrou a versao 10 com success=true (o boot com validate ja subiu — schema bate). */
    @Test
    void flywayAplicouVersao10ComSucesso() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT version, success FROM flyway_schema_history WHERE version = '10'");
        assertThat(row.get("version")).isEqualTo("10");
        assertThat(row.get("success")).isEqualTo(Boolean.TRUE);
    }

    // Head-pin removido (autorizacao do dono no gate do M5.2, mesma categoria do episodio V7V8/M5):
    // fixar "head == 10" passou a mentir assim que a V11 (produto_imagem_variante) virou o novo head, e
    // quebraria a cada migracao futura. Este teste afere o EFEITO da V10 (ver
    // flywayAplicouVersao10ComSucesso acima, que permanece); o pin do head atual vive no V11MigrationTest
    // (head == "11"), que a proxima migracao atualizara.

    // ----- R-CA-9: 4 colunas de contraparte com os tipos certos -----

    @Test
    void colunasDeContraparteExistemComTiposCertos() {
        assertThat(coluna("fornecedor_id")).as("fornecedor_id").isNotNull()
                .containsEntry("data_type", "bigint");
        assertThat(coluna("cliente_id")).as("cliente_id").isNotNull()
                .containsEntry("data_type", "bigint");

        Map<String, Object> fornNome = coluna("fornecedor_nome");
        assertThat(fornNome).as("fornecedor_nome").isNotNull();
        assertThat(fornNome.get("data_type")).isEqualTo("character varying");
        assertThat(((Number) fornNome.get("character_maximum_length")).intValue()).isEqualTo(150);

        Map<String, Object> cliNome = coluna("cliente_nome");
        assertThat(cliNome).as("cliente_nome").isNotNull();
        assertThat(cliNome.get("data_type")).isEqualTo("character varying");
        assertThat(((Number) cliNome.get("character_maximum_length")).intValue()).isEqualTo(150);
    }

    // ----- R-CA-9: FKs ON DELETE SET NULL (confdeltype = 'n') -----

    @Test
    void fksDeContraparteSaoOnDeleteSetNull() {
        // pg_constraint.confdeltype: 'n' = SET NULL, 'c' = CASCADE, 'a' = NO ACTION, 'r' = RESTRICT.
        String fornDel = jdbc.queryForObject(
                "SELECT confdeltype FROM pg_constraint WHERE conname = 'fk_mov_fornecedor'",
                String.class);
        String cliDel = jdbc.queryForObject(
                "SELECT confdeltype FROM pg_constraint WHERE conname = 'fk_mov_cliente'",
                String.class);
        assertThat(fornDel).as("fk_mov_fornecedor ON DELETE SET NULL").isEqualTo("n");
        assertThat(cliDel).as("fk_mov_cliente ON DELETE SET NULL").isEqualTo("n");
    }

    // ----- R-CA-9: CHECKs por tipo + indices -----

    @Test
    void checksPorTipoEIndicesExistem() {
        List<String> checks = jdbc.queryForList(
                "SELECT conname FROM pg_constraint "
                        + "WHERE conrelid = 'movimentacao_estoque'::regclass AND contype = 'c'",
                String.class);
        assertThat(checks).contains("ck_mov_fornecedor_tipo", "ck_mov_cliente_tipo");

        List<String> indices = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'movimentacao_estoque'",
                String.class);
        assertThat(indices).contains("ix_mov_fornecedor_tipo", "ix_mov_cliente_tipo");
    }

    // ----- R-CA-6: cascade tolerado — hard delete anula o *_id e PRESERVA o *_nome -----

    /** Hard delete do FORNECEDOR usado numa ENTRADA: fornecedor_id -> NULL, fornecedor_nome preservado. */
    @Test
    void hardDeleteDeFornecedorAnulaIdEPreservaNome() {
        Long fornecedorId = inserirFornecedor("Sitio Verde");
        Long movId = inserirMovimentacao("ENTRADA", fornecedorId, "Sitio Verde", null, null, null);

        // A trigger NAO bloqueia o cascade de anulacao (R-CA-6).
        assertThatCode(() -> jdbc.update("DELETE FROM fornecedor WHERE id = ?", fornecedorId))
                .doesNotThrowAnyException();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT fornecedor_id, fornecedor_nome FROM movimentacao_estoque WHERE id = ?", movId);
        assertThat(row.get("fornecedor_id")).as("fornecedor_id anulado pelo cascade").isNull();
        assertThat(row.get("fornecedor_nome")).as("snapshot preservado (LGPD/AD-SQ-64)")
                .isEqualTo("Sitio Verde");
    }

    /** Hard delete do CLIENTE usado numa SAIDA: cliente_id -> NULL, cliente_nome preservado. */
    @Test
    void hardDeleteDeClienteAnulaIdEPreservaNome() {
        Long clienteId = inserirCliente("Maria Flores");
        Long movId = inserirMovimentacao("SAIDA", null, null, clienteId, "Maria Flores", null);

        assertThatCode(() -> jdbc.update("DELETE FROM cliente WHERE id = ?", clienteId))
                .doesNotThrowAnyException();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT cliente_id, cliente_nome FROM movimentacao_estoque WHERE id = ?", movId);
        assertThat(row.get("cliente_id")).as("cliente_id anulado pelo cascade").isNull();
        assertThat(row.get("cliente_nome")).as("snapshot preservado (LGPD/AD-SQ-64)")
                .isEqualTo("Maria Flores");
    }

    // ----- AD-SQ-48: imutabilidade preservada — UPDATE de conteudo e DELETE de usuario BARRADOS -----

    /** UPDATE de conteudo (mudar quantidade) continua rejeitado pela trigger (ledger imutavel). */
    @Test
    void updateDeConteudoContinuaBarrado() {
        Long movId = inserirMovimentacao("AJUSTE", null, null, null, null, null);

        assertThatThrownBy(() ->
                jdbc.update("UPDATE movimentacao_estoque SET quantidade = quantidade + 1 WHERE id = ?",
                        movId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("imutavel");
    }

    /**
     * DELETE FROM usuario direto continua barrado (AD-SQ-48): a FK {@code fk_mov_usuario} e {@code ON
     * DELETE SET NULL}, entao o delete dispara um UPDATE de {@code usuario_id -> NULL} no ledger — e a
     * trigger BLOQUEIA (usuario_id e guardado por igualdade estrita, fora da tolerancia de cascade).
     */
    @Test
    void deleteDeUsuarioContinuaBarrado() {
        Long usuarioId = inserirUsuario();
        inserirMovimentacao("AJUSTE", null, null, null, null, usuarioId);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM usuario WHERE id = ?", usuarioId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("imutavel");
    }
}
