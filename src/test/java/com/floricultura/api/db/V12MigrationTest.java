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
 * Integracao da migracao V12 (T-M6-01a, CA-14/CA-16/CA-39.7 — SPEC-M6 §3.1) contra um PostgreSQL de
 * DESCARTE (Testcontainers postgres:16). Sobe o contexto real (datasource/JPA/Flyway com {@code
 * ddl-auto=validate}): o Flyway aplica V1..V12 na subida e as asercoes validam via JDBC.
 *
 * <p>Prova o schema do M6: {@code cor} (com {@code uk_cor_nome} UNIQUE simples + {@code
 * ck_cor_nome_canonico} — e SEM {@code ux_cor_nome_lower}, AD-SQ-90), {@code produto_cor} (PK
 * composta, FK CASCADE p/ produto e RESTRICT p/ cor), as 3 colunas botanicas de {@code produto} com
 * os 4 CHECKs (incluindo o da altura NULL-SAFE — AD-SQ-89) e {@code produto_necessidade_luz}. O
 * proprio {@code @SpringBootTest} subir com {@code ddl-auto=validate} prova que as tabelas/colunas
 * <b>nao mapeadas</b> nesta task nao derrubam o validate (precedente {@code produto.imagem} na V5 e
 * {@code produto_imagem_variante} na V11) — o mapeamento e da T-M6-02a. <b>Sem head-pin</b>
 * (AD-SQ-88). Dados ficticios (LGPD): nomes de planta.
 */
@SpringBootTest
@Testcontainers
class V12MigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-v12-migration-0123456789abcdef");
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

    private List<String> constraints(String tabela) {
        return jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = to_regclass(?)", String.class, tabela);
    }

    private Long inserirCor(String nome) {
        return jdbc.queryForObject("INSERT INTO cor (nome) VALUES (?) RETURNING id", Long.class, nome);
    }

    private Long inserirProduto(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida) VALUES (?, 'un') RETURNING id", Long.class, nome);
    }

    private void inserirBotanico(String nome, String caracteristica, Integer alturaCm) {
        jdbc.update("INSERT INTO produto (nome, unidade_medida, caracteristica, altura_cm) "
                + "VALUES (?, 'un', ?, ?)", nome, caracteristica, alturaCm);
    }

    // ----- CA-14: V12 aplicada + boot validate verde (schema nao mapeado nao derruba o validate) -----
    /** Flyway registrou a versao 12 com success=true (o boot com validate ja subiu — schema bate). */
    @Test
    void flywayAplicouVersao12ComSucesso() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT version, success FROM flyway_schema_history WHERE version = '12'");
        assertThat(row.get("version")).isEqualTo("12");
        assertThat(row.get("success")).isEqualTo(Boolean.TRUE);
    }

    // ----- CA-14: tabela `cor` -----
    @Test
    void colunasDaCorExistemComTiposCertos() {
        Map<String, Object> nome = coluna("cor", "nome");
        assertThat(nome).as("cor.nome").isNotNull().containsEntry("is_nullable", "NO");
        assertThat(((Number) nome.get("character_maximum_length")).intValue()).isEqualTo(40);
        Map<String, Object> hex = coluna("cor", "hex");
        assertThat(hex).as("cor.hex").isNotNull().containsEntry("is_nullable", "YES");
        assertThat(((Number) hex.get("character_maximum_length")).intValue()).isEqualTo(7);
        assertThat(coluna("cor", "criado_em")).as("cor.criado_em").isNotNull()
                .containsEntry("data_type", "timestamp with time zone");
        assertThat(coluna("cor", "atualizado_em")).as("cor.atualizado_em").isNotNull()
                .containsEntry("data_type", "timestamp with time zone");
    }

    /** AD-SQ-90: UNIQUE simples + CHECK de forma canonica; o indice funcional lower(nome) NAO existe. */
    @Test
    void unicidadeDaCorEUniqueSimplesSemIndiceFuncional() {
        assertThat(constraints("cor")).contains("uk_cor_nome", "ck_cor_nome_canonico", "ck_cor_hex");

        String tipo = jdbc.queryForObject(
                "SELECT contype::text FROM pg_constraint WHERE conname = 'uk_cor_nome'", String.class);
        assertThat(tipo).as("uk_cor_nome e UNIQUE ('u')").isEqualTo("u");

        List<String> indices = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'cor'", String.class);
        assertThat(indices).as("ux_cor_nome_lower nao existe (AD-SQ-90)").doesNotContain("ux_cor_nome_lower");
    }

    /** CA-39.7: INSERT direto de nome fora da forma canonica e rejeitado por ck_cor_nome_canonico. */
    @Test
    void nomeDeCorForaDoCanonicoERejeitado() {
        for (String cru : List.of("cinza escuro", "azul", "-AZUL", "AZUL-", "CINZA--ESCURO", "A")) {
            assertThatThrownBy(() -> inserirCor(cru)).as("nome cru: %s", cru)
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("ck_cor_nome_canonico");
        }
    }

    /** Contraprova: os canonicos passam — inclusive com acento, que CONTA como cor distinta (R1c). */
    @Test
    void nomeDeCorCanonicoEAceito() {
        assertThatCode(() -> {
            inserirCor("VERMELHO");
            inserirCor("CINZA-ESCURO");
            inserirCor("LILAS");
            inserirCor("LILÁS");
        }).doesNotThrowAnyException();
    }

    /** CA-39.7: uk_cor_nome fecha a corrida — canonico duplicado e rejeitado pelo banco. */
    @Test
    void ukCorNomeRejeitaDuplicata() {
        inserirCor("MARROM");
        assertThatThrownBy(() -> inserirCor("MARROM"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_cor_nome");
    }

    /** ck_cor_hex — so '#RRGGBB'; NULL e permitido (amostra opcional). */
    @Test
    void hexDaCorSegueOFormatoOuENulo() {
        assertThatCode(() -> jdbc.update(
                "INSERT INTO cor (nome, hex) VALUES ('OCRE', '#C4326B')")).doesNotThrowAnyException();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO cor (nome, hex) VALUES ('BEGE', '#FFF')"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_cor_hex");
    }

    // ----- CA-14/CA-16: produto_cor -----
    @Test
    void produtoCorTemPkCompostaEFksCascadeERestrict() {
        List<String> pkCols = jdbc.queryForList(
                "SELECT a.attname FROM pg_index i "
                        + "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) "
                        + "WHERE i.indrelid = 'produto_cor'::regclass AND i.indisprimary", String.class);
        assertThat(pkCols).containsExactlyInAnyOrder("produto_id", "cor_id");

        // pg_constraint.confdeltype: 'c' = CASCADE, 'r' = RESTRICT, 'a' = NO ACTION.
        assertThat(jdbc.queryForObject(
                "SELECT confdeltype FROM pg_constraint WHERE conname = 'fk_pc_produto'", String.class))
                .as("fk_pc_produto ON DELETE CASCADE").isEqualTo("c");
        assertThat(jdbc.queryForObject(
                "SELECT confdeltype FROM pg_constraint WHERE conname = 'fk_pc_cor'", String.class))
                .as("fk_pc_cor ON DELETE RESTRICT").isEqualTo("r");
    }

    /** CA-16: hard delete do produto (FC-08) limpa vinculos e luzes; as cores do catalogo PERMANECEM. */
    @Test
    void hardDeleteDoProdutoLimpaVinculosECatalogoPermanece() {
        Long corA = inserirCor("ROSA");
        Long corB = inserirCor("AZUL");
        Long produtoId = inserirProduto("Orquidea");
        jdbc.update("INSERT INTO produto_cor (produto_id, cor_id) VALUES (?, ?), (?, ?)",
                produtoId, corA, produtoId, corB);
        jdbc.update("INSERT INTO produto_necessidade_luz (produto_id, luz) VALUES (?, 'SOMBRA'), "
                + "(?, 'MEIA_SOMBRA')", produtoId, produtoId);

        assertThatCode(() -> jdbc.update("DELETE FROM produto WHERE id = ?", produtoId))
                .doesNotThrowAnyException();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM produto_cor WHERE produto_id = ?",
                Integer.class, produtoId)).as("vinculos de cor limpos").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM produto_necessidade_luz WHERE produto_id = ?",
                Integer.class, produtoId)).as("luzes limpas").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cor WHERE id IN (?, ?)",
                Integer.class, corA, corB)).as("cores do catalogo permanecem").isEqualTo(2);
    }

    /** P3 (defesa em profundidade): excluir cor em uso e barrado pelo ON DELETE RESTRICT. */
    @Test
    void excluirCorEmUsoERejeitadoPeloRestrict() {
        Long corId = inserirCor("AMARELO");
        Long produtoId = inserirProduto("Girassol");
        jdbc.update("INSERT INTO produto_cor (produto_id, cor_id) VALUES (?, ?)", produtoId, corId);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM cor WHERE id = ?", corId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_pc_cor");
    }

    // ----- CA-14: colunas botanicas em `produto` + os 4 CHECKs -----
    @Test
    void colunasBotanicasDoProdutoExistemComTiposCertos() {
        Map<String, Object> caracteristica = coluna("produto", "caracteristica");
        assertThat(caracteristica).as("produto.caracteristica").isNotNull()
                .containsEntry("is_nullable", "YES");
        assertThat(((Number) caracteristica.get("character_maximum_length")).intValue()).isEqualTo(10);

        Map<String, Object> toxicidade = coluna("produto", "toxicidade");
        assertThat(toxicidade).as("produto.toxicidade").isNotNull().containsEntry("is_nullable", "YES");
        assertThat(((Number) toxicidade.get("character_maximum_length")).intValue()).isEqualTo(12);

        assertThat(coluna("produto", "altura_cm")).as("produto.altura_cm").isNotNull()
                .containsEntry("data_type", "integer").containsEntry("is_nullable", "YES");

        assertThat(constraints("produto")).contains("ck_produto_caracteristica", "ck_produto_toxicidade",
                "ck_produto_altura_cm", "ck_produto_altura_exige_porte");
    }

    /**
     * AD-SQ-89 (regressao explicita): altura SEM caracteristica e REJEITADA, no INSERT e no UPDATE.
     * E o caso que a variante ingenua do CHECK deixava entrar (FALSE OR NULL = NULL nao viola CHECK).
     */
    @Test
    void alturaSemCaracteristicaERejeitadaNoInsertENoUpdate() {
        assertThatThrownBy(() -> inserirBotanico("Samambaia", null, 30))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_produto_altura_exige_porte");

        Long produtoId = inserirProduto("Jiboia");
        assertThatThrownBy(() -> jdbc.update("UPDATE produto SET altura_cm = 30 WHERE id = ?", produtoId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_produto_altura_exige_porte");
    }

    /** ck_produto_altura_exige_porte — MUDA nao declara porte; JOVEM/ADULTA sim; (NULL, NULL) passa. */
    @Test
    void alturaSoValeParaJovemOuAdulta() {
        assertThatThrownBy(() -> inserirBotanico("Muda de Manjericao", "MUDA", 15))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_produto_altura_exige_porte");

        assertThatCode(() -> {
            inserirBotanico("Ficus adulto", "ADULTA", 120);
            inserirBotanico("Palmeira jovem", "JOVEM", 40);
            inserirBotanico("Cacto sem porte", null, null);
            inserirBotanico("Muda de Alecrim", "MUDA", null);
        }).doesNotThrowAnyException();
    }

    /** ck_produto_altura_cm — faixa 1..10000 cm. */
    @Test
    void alturaForaDaFaixaERejeitada() {
        for (Integer fora : List.of(0, 10001)) {
            assertThatThrownBy(() -> inserirBotanico("Bonsai " + fora, "ADULTA", fora))
                    .as("altura_cm = %s", fora)
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("ck_produto_altura_cm");
        }
    }

    /** ck_produto_caracteristica e ck_produto_toxicidade — enums ASCII fechados (AD-SQ-31). */
    @Test
    void caracteristicaEToxicidadeForaDoEnumSaoRejeitadas() {
        assertThatThrownBy(() -> inserirBotanico("Broto qualquer", "BROTO", null))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_produto_caracteristica");

        assertThatThrownBy(() -> jdbc.update("INSERT INTO produto (nome, unidade_medida, toxicidade) "
                + "VALUES ('Comigo-ninguem-pode', 'un', 'TALVEZ')"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_produto_toxicidade");

        assertThatCode(() -> jdbc.update("INSERT INTO produto (nome, unidade_medida, toxicidade) "
                + "VALUES ('Espada-de-sao-jorge', 'un', 'TOXICA')")).doesNotThrowAnyException();
    }

    // ----- CA-14: produto_necessidade_luz -----
    @Test
    void necessidadeDeLuzTemPkCompostaCheckDoEnumEFkCascade() {
        assertThat(constraints("produto_necessidade_luz"))
                .contains("pk_produto_luz", "fk_pnl_produto", "ck_pnl_luz");
        assertThat(jdbc.queryForObject(
                "SELECT confdeltype FROM pg_constraint WHERE conname = 'fk_pnl_produto'", String.class))
                .as("fk_pnl_produto ON DELETE CASCADE").isEqualTo("c");

        Long produtoId = inserirProduto("Anturio");
        jdbc.update("INSERT INTO produto_necessidade_luz (produto_id, luz) VALUES (?, 'SOL_PLENO')",
                produtoId);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO produto_necessidade_luz (produto_id, luz) VALUES (?, 'SOL_PLENO')", produtoId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("pk_produto_luz");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO produto_necessidade_luz (produto_id, luz) VALUES (?, 'LUA')", produtoId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_pnl_luz");
    }

    // ----- CA-14: indices dos filtros server-side -----
    @Test
    void indicesDeFiltroForamCriados() {
        List<String> indices = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename IN "
                        + "('produto', 'produto_cor', 'produto_necessidade_luz')", String.class);
        assertThat(indices).contains("ix_produto_caracteristica", "ix_produto_toxicidade",
                "ix_produto_preco", "ix_pc_cor", "ix_pnl_luz");
    }
}
