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
 * Integracao da migracao V6 (T-M4-1, CA-1..CA-11 — camada de schema) contra um PostgreSQL de
 * DESCARTE (Testcontainers postgres:16). Sobe o contexto real (datasource/JPA/Flyway ativos): o
 * Flyway aplica V1..V8 na subida e as asercoes validam via JDBC (dispensa {@code psql}).
 *
 * <p>Cobre a evolucao do stub `evento` do M0 (AD-SQ-43): rename {@code data_evento -> data_inicio},
 * novas colunas {@code data_fim}/{@code tipo}/{@code repete_todo_ano}, DROP de {@code sazonal}/
 * {@code ativo}, CHECKs {@code ck_evento_tipo}/{@code ck_evento_periodo}, e o N:N {@code
 * evento_produto} (AD-SQ-44) com PK composta + FKs {@code ON DELETE CASCADE} nos dois lados.
 */
@SpringBootTest
@Testcontainers
class V6MigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Divida §12: teste de migracao auto-contido (nao depende do env do surefire).
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-v6-migration-0123456789abcdef");
    }

    @Autowired
    private JdbcTemplate jdbc;

    private Map<String, Object> colunaEvento(String nome) {
        return jdbc.queryForList(
                "SELECT data_type, is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'evento' AND column_name = ?", nome)
                .stream().findFirst().orElse(null);
    }

    private Long inserirProduto(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida) VALUES (?, 'un') RETURNING id",
                Long.class, nome);
    }

    private Long inserirEvento(String nome, String dataInicio, String dataFim, String tipo) {
        return jdbc.queryForObject(
                "INSERT INTO evento (nome, data_inicio, data_fim, tipo) VALUES (?, ?::date, ?::date, ?) "
                        + "RETURNING id",
                Long.class, nome, dataInicio, dataFim, tipo);
    }

    // ----- V6 aplicada + evolucao do schema `evento` -----

    /** Flyway registrou a versao 6 com success=true. */
    @Test
    void flywayAplicouVersao6ComSucesso() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT version, success FROM flyway_schema_history WHERE version = '6'");
        assertThat(row.get("version")).isEqualTo("6");
        assertThat(row.get("success")).isEqualTo(Boolean.TRUE);
    }

    /** `data_evento` foi renomeada para `data_inicio` (a antiga some, a nova existe NOT NULL). */
    @Test
    void renomeouDataEventoParaDataInicio() {
        assertThat(colunaEvento("data_evento")).isNull();
        Map<String, Object> dataInicio = colunaEvento("data_inicio");
        assertThat(dataInicio).isNotNull();
        assertThat(dataInicio.get("data_type")).isEqualTo("date");
        assertThat(dataInicio.get("is_nullable")).isEqualTo("NO");
    }

    /** Novas colunas do modelo M4 existem com o tipo/nulabilidade corretos. */
    @Test
    void colunasNovasDoModeloExistem() {
        Map<String, Object> dataFim = colunaEvento("data_fim");
        assertThat(dataFim).isNotNull();
        assertThat(dataFim.get("data_type")).isEqualTo("date");
        assertThat(dataFim.get("is_nullable")).isEqualTo("YES");     // NULL => data unica

        Map<String, Object> tipo = colunaEvento("tipo");
        assertThat(tipo).isNotNull();
        assertThat(tipo.get("data_type")).isEqualTo("character varying");
        assertThat(tipo.get("is_nullable")).isEqualTo("NO");

        Map<String, Object> repete = colunaEvento("repete_todo_ano");
        assertThat(repete).isNotNull();
        assertThat(repete.get("data_type")).isEqualTo("boolean");
        assertThat(repete.get("is_nullable")).isEqualTo("NO");
    }

    /** `sazonal` e `ativo` (do stub V1) foram dropadas na V6. */
    @Test
    void dropouSazonalEAtivo() {
        assertThat(colunaEvento("sazonal")).isNull();
        assertThat(colunaEvento("ativo")).isNull();
    }

    /** Os dois CHECKs da V6 existem na tabela evento. */
    @Test
    void checksDaV6EstaoPresentes() {
        List<String> nomes = jdbc.queryForList(
                "SELECT conname FROM pg_constraint "
                        + "WHERE conrelid = 'evento'::regclass AND contype = 'c'", String.class);
        assertThat(nomes).contains("ck_evento_tipo", "ck_evento_periodo");
    }

    // ----- CHECKs de comportamento -----

    /** ck_evento_tipo — aceita o enum, rejeita valor fora dele. */
    @Test
    void checkDeTipoAceitaEnumRejeitaForaDele() {
        for (String tipo : List.of("COMEMORATIVA", "FEIRA", "BENEFICENTE", "ENCOMENDA_CLIENTE")) {
            assertThatCode(() -> inserirEvento("Ev " + tipo, "2026-05-10", null, tipo))
                    .doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> inserirEvento("Invalido", "2026-05-10", null, "OUTRO"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_evento_tipo");
    }

    /** ck_evento_periodo — data unica (data_fim NULL) e periodo valido passam; data_fim < inicio falha. */
    @Test
    void checkDePeriodoAceitaDataUnicaEPeriodoValidoRejeitaInvertido() {
        assertThatCode(() -> inserirEvento("Unica", "2026-05-10", null, "COMEMORATIVA"))
                .doesNotThrowAnyException();
        assertThatCode(() -> inserirEvento("Periodo", "2026-09-04", "2026-09-13", "FEIRA"))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> inserirEvento("Invertido", "2026-09-13", "2026-09-04", "FEIRA"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_evento_periodo");
    }

    // ----- N:N evento_produto + cascade dos dois lados -----

    /** A tabela evento_produto existe com PK composta e o indice ix_ep_produto. */
    @Test
    void tabelaEventoProdutoExisteComPkEIndice() {
        List<String> tabelas = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);
        assertThat(tabelas).contains("evento_produto");

        List<String> constraints = jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'evento_produto'::regclass",
                String.class);
        assertThat(constraints).contains("pk_evento_produto", "fk_ep_evento", "fk_ep_produto");

        List<String> indices = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'evento_produto'", String.class);
        assertThat(indices).contains("ix_ep_produto");
    }

    /** Deletar o PRODUTO remove o vinculo (cascade) e o evento permanece. */
    @Test
    void hardDeleteDeProdutoRemoveVinculoEPreservaEvento() {
        Long eventoId = inserirEvento("Dia das Maes", "2026-05-10", null, "COMEMORATIVA");
        Long produtoId = inserirProduto("Rosa");
        jdbc.update("INSERT INTO evento_produto (evento_id, produto_id) VALUES (?, ?)",
                eventoId, produtoId);

        jdbc.update("DELETE FROM produto WHERE id = ?", produtoId);

        Integer links = jdbc.queryForObject(
                "SELECT count(*) FROM evento_produto WHERE evento_id = ?", Integer.class, eventoId);
        assertThat(links).isZero();
        Integer eventos = jdbc.queryForObject(
                "SELECT count(*) FROM evento WHERE id = ?", Integer.class, eventoId);
        assertThat(eventos).isEqualTo(1);
    }

    /** Deletar o EVENTO remove o vinculo (cascade) e o produto permanece. */
    @Test
    void hardDeleteDeEventoRemoveVinculoEPreservaProduto() {
        Long eventoId = inserirEvento("Finados", "2026-11-02", null, "COMEMORATIVA");
        Long produtoId = inserirProduto("Crisantemo");
        jdbc.update("INSERT INTO evento_produto (evento_id, produto_id) VALUES (?, ?)",
                eventoId, produtoId);

        jdbc.update("DELETE FROM evento WHERE id = ?", eventoId);

        Integer links = jdbc.queryForObject(
                "SELECT count(*) FROM evento_produto WHERE produto_id = ?", Integer.class, produtoId);
        assertThat(links).isZero();
        Integer produtos = jdbc.queryForObject(
                "SELECT count(*) FROM produto WHERE id = ?", Integer.class, produtoId);
        assertThat(produtos).isEqualTo(1);
    }
}
