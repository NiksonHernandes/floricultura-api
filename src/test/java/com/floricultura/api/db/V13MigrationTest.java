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
 * Integracao da migracao V13 (T-M7-01, CA-1/CA-2 — SPEC-M7 §3.1) contra um PostgreSQL de DESCARTE
 * (Testcontainers postgres:16). Sobe o contexto real (datasource/JPA/Flyway com {@code
 * ddl-auto=validate}): o Flyway aplica V1..V13 na subida e as asercoes validam via JDBC.
 *
 * <p>Prova, por EFEITO, o schema do dinheiro no ledger: as 6 colunas novas com tipo/escala, os
 * <b>11</b> objetos de {@code pg_constraint} (10 CHECK + a FK {@code fk_mov_estorno} — o indice unico
 * PARCIAL {@code ux_mov_estorno} nao aparece ali, por isso e conferido em {@code pg_indexes}) e os
 * <b>2</b> indices. <b>Sem head-pin</b> (AD-SQ-88 e a best-practice de migracao: 3 episodios ja
 * custaram bloqueio, o do M6 custou um dia) — nao ha nenhuma asercao sobre "qual e o head", nem
 * {@code installed_rank}: a prova e {@code WHERE version = '13'}, que sobrevive a V14, V15...
 * Dados ficticios (LGPD): nomes de planta.
 */
@SpringBootTest
@Testcontainers
class V13MigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-v13-migration-0123456789abcdef");
    }

    @Autowired
    private JdbcTemplate jdbc;

    private Map<String, Object> coluna(String nome) {
        return jdbc.queryForList(
                "SELECT data_type, numeric_precision, numeric_scale, character_maximum_length, is_nullable "
                        + "FROM information_schema.columns "
                        + "WHERE table_name = 'movimentacao_estoque' AND column_name = ?", nome)
                .stream().findFirst().orElse(null);
    }

    private int inteiro(Map<String, Object> coluna, String campo) {
        return ((Number) coluna.get(campo)).intValue();
    }

    // ----- CA-1: a V13 aplicou (por efeito, NUNCA por head-pin) -----

    /** Flyway registrou a versao 13 com success=true (o boot com ddl-auto=validate ja subiu). */
    @Test
    void flywayAplicouVersao13ComSucesso() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT version, success FROM flyway_schema_history WHERE version = '13'");
        assertThat(row.get("version")).isEqualTo("13");
        assertThat(row.get("success")).isEqualTo(Boolean.TRUE);
    }

    // ----- CA-1: as 6 colunas com tipo/escala do §3.1-a, todas anulaveis (zero backfill) -----

    /** 5 colunas de valor + o ponteiro de estorno: NUMERIC(14,2), VARCHAR(12) e BIGINT, todas NULL. */
    @Test
    void asSeisColunasNovasExistemComTipoEEscalaCertos() {
        for (String dinheiro : List.of("valor_unitario", "desconto_valor", "total_bruto", "total_final")) {
            Map<String, Object> col = coluna(dinheiro);
            assertThat(col).as("movimentacao_estoque.%s", dinheiro).isNotNull()
                    .containsEntry("data_type", "numeric")
                    .containsEntry("is_nullable", "YES");
            assertThat(inteiro(col, "numeric_precision")).as("%s precision", dinheiro).isEqualTo(14);
            assertThat(inteiro(col, "numeric_scale")).as("%s scale", dinheiro).isEqualTo(2);
        }

        Map<String, Object> descontoTipo = coluna("desconto_tipo");
        assertThat(descontoTipo).as("desconto_tipo").isNotNull()
                .containsEntry("data_type", "character varying")
                .containsEntry("is_nullable", "YES");
        assertThat(inteiro(descontoTipo, "character_maximum_length")).isEqualTo(12);

        assertThat(coluna("estorna_movimentacao_id")).as("estorna_movimentacao_id").isNotNull()
                .containsEntry("data_type", "bigint")
                .containsEntry("is_nullable", "YES");
    }

    // ----- CA-2: 11 objetos em pg_constraint (10 CHECK + 1 FK) -----

    /**
     * Os nomes sao LITERAIS na migracao (constraint anonima do PG nao e referenciavel em teste nem em
     * migracao futura). O indice unico parcial NAO entra nesta conta — ver o teste seguinte.
     */
    @Test
    void osOnzeObjetosDeConstraintDaV13Existem() {
        List<String> nomes = jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'movimentacao_estoque'::regclass",
                String.class);

        assertThat(nomes).as("10 CHECK + 1 FK da V13").contains(
                "ck_mov_valor_unitario",
                "ck_mov_desconto_tipo",
                "ck_mov_desconto_par",
                "ck_mov_desconto_valor",
                "ck_mov_desconto_pct",
                "ck_mov_desconto_exige_base",
                "ck_mov_totais_par",
                "ck_mov_total_final",
                "ck_mov_valor_tipo",
                "ck_mov_estorno_nao_self",
                "fk_mov_estorno");

        // A FK aponta para a PROPRIA tabela e NAO tem cascade ('a' = NO ACTION): linha de ledger nao
        // se apaga (o DELETE ja e barrado pela trigger).
        Map<String, Object> fk = jdbc.queryForMap(
                "SELECT confrelid::regclass::text AS destino, confdeltype::text AS ondelete "
                        + "FROM pg_constraint WHERE conname = 'fk_mov_estorno'");
        assertThat(fk.get("destino")).isEqualTo("movimentacao_estoque");
        assertThat(fk.get("ondelete")).as("fk_mov_estorno sem cascade").isEqualTo("a");
    }

    // ----- CA-2: os 2 indices, com o ux_mov_estorno comprovadamente PARCIAL e UNICO -----

    /**
     * {@code ux_mov_estorno} e o que garante no banco "cada lancamento e estornado no maximo uma vez"
     * (a corrida de dois cliques morre aqui, nao no servico); {@code ix_mov_tipo_criado_em} serve ao
     * recorte periodo x tipo do relatorio (§3.7).
     */
    @Test
    void osDoisIndicesDaV13ExistemEOEstornoEUnicoEParcial() {
        List<String> indices = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'movimentacao_estoque'", String.class);
        assertThat(indices).contains("ux_mov_estorno", "ix_mov_tipo_criado_em");

        Map<String, Object> ux = jdbc.queryForMap(
                "SELECT i.indisunique AS unico, (i.indpred IS NOT NULL) AS parcial "
                        + "FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid "
                        + "WHERE c.relname = 'ux_mov_estorno'");
        assertThat(ux.get("unico")).as("ux_mov_estorno e UNIQUE").isEqualTo(Boolean.TRUE);
        assertThat(ux.get("parcial")).as("ux_mov_estorno e PARCIAL (WHERE ... IS NOT NULL)")
                .isEqualTo(Boolean.TRUE);
    }
}
