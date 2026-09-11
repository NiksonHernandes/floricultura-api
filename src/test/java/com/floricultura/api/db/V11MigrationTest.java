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
 * Integracao da migracao V11 (T-M5.2-2, CA-C1/C5/C9/C10 — SPEC-M5.2 §3.4) contra um PostgreSQL de
 * DESCARTE (Testcontainers postgres:16). Sobe o contexto real (datasource/JPA/Flyway com {@code
 * ddl-auto=validate}): o Flyway aplica V1..V11 na subida e as asercoes validam via JDBC.
 *
 * <p>Prova o storage dos derivados da imagem (PA#3): a tabela {@code produto_imagem_variante} com PK
 * composta {@code (produto_id, tamanho)}, FK {@code ON DELETE CASCADE} p/ {@code produto}, CHECK de
 * {@code tamanho IN ('thumb','medio')} e CHECK de {@code imagem_content_type IN ('image/webp',
 * 'image/jpeg')}; e {@code head = V11}. O proprio {@code @SpringBootTest} subir com {@code
 * ddl-auto=validate} prova que a tabela <b>nao mapeada</b> (AD-SQ-38/CA-C10) nao derruba o validate —
 * nenhum bytea (original ou variante) e mapeado em @Entity. Dados ficticios (LGPD): "Rosa".
 */
@SpringBootTest
@Testcontainers
class V11MigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-v11-migration-0123456789abcdef");
    }

    @Autowired
    private JdbcTemplate jdbc;

    private Map<String, Object> coluna(String nome) {
        return jdbc.queryForList(
                "SELECT data_type, character_maximum_length, is_nullable "
                        + "FROM information_schema.columns "
                        + "WHERE table_name = 'produto_imagem_variante' AND column_name = ?", nome)
                .stream().findFirst().orElse(null);
    }

    private Long inserirProduto(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida) VALUES (?, 'un') RETURNING id",
                Long.class, nome);
    }

    private void inserirVariante(Long produtoId, String tamanho, String contentType) {
        jdbc.update(
                "INSERT INTO produto_imagem_variante "
                        + "(produto_id, tamanho, imagem, imagem_content_type, largura, altura, bytes) "
                        + "VALUES (?, ?, decode('00', 'hex'), ?, 200, 133, 1)",
                produtoId, tamanho, contentType);
    }

    // ----- CA-C1/C10: V11 aplicada + head=V11 + boot validate verde (tabela nao mapeada) -----

    /** Flyway registrou a versao 11 com success=true (o boot com validate ja subiu — schema bate). */
    @Test
    void flywayAplicouVersao11ComSucesso() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT version, success FROM flyway_schema_history WHERE version = '11'");
        assertThat(row.get("version")).isEqualTo("11");
        assertThat(row.get("success")).isEqualTo(Boolean.TRUE);
    }

    // O head-pin (`headDoSchemaEV11`) foi REMOVIDO em 2026-09-11, com autorizacao do dono (AD-SQ-88), porque a
    // V12 do M6 o quebraria por desenho. Ele aferia "nada veio depois da V11" — afirmacao com prazo de validade,
    // nao invariante. O que tem valor real e o metodo acima, que afere o EFEITO da V11. Terceiro episodio do
    // mesmo padrao (pin da V8 removido no M5, da V10 no M5.2): NAO recriar apontando para a V12.

    // ----- CA-C1: tabela + colunas com os tipos certos -----

    @Test
    void colunasDaVarianteExistemComTiposCertos() {
        Map<String, Object> imagem = coluna("imagem");
        assertThat(imagem).as("imagem").isNotNull();
        assertThat(imagem.get("data_type")).isEqualTo("bytea");
        assertThat(imagem.get("is_nullable")).isEqualTo("NO");

        Map<String, Object> tamanho = coluna("tamanho");
        assertThat(tamanho).as("tamanho").isNotNull();
        assertThat(tamanho.get("data_type")).isEqualTo("character varying");
        assertThat(((Number) tamanho.get("character_maximum_length")).intValue()).isEqualTo(10);

        Map<String, Object> contentType = coluna("imagem_content_type");
        assertThat(contentType).as("imagem_content_type").isNotNull();
        assertThat(contentType.get("data_type")).isEqualTo("character varying");
        assertThat(((Number) contentType.get("character_maximum_length")).intValue()).isEqualTo(30);

        assertThat(coluna("largura")).as("largura").isNotNull().containsEntry("data_type", "integer");
        assertThat(coluna("altura")).as("altura").isNotNull().containsEntry("data_type", "integer");
        assertThat(coluna("bytes")).as("bytes").isNotNull().containsEntry("data_type", "bigint");
        assertThat(coluna("criado_em")).as("criado_em").isNotNull()
                .containsEntry("data_type", "timestamp with time zone");
    }

    // ----- CA-C1: PK composta (produto_id, tamanho) -----

    @Test
    void pkComposta() {
        List<String> pkCols = jdbc.queryForList(
                "SELECT a.attname FROM pg_index i "
                        + "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) "
                        + "WHERE i.indrelid = 'produto_imagem_variante'::regclass AND i.indisprimary "
                        + "ORDER BY a.attname", String.class);
        assertThat(pkCols).containsExactlyInAnyOrder("produto_id", "tamanho");
    }

    // ----- CA-C9/C10: FK ON DELETE CASCADE p/ produto -----

    @Test
    void fkProdutoEOnDeleteCascade() {
        // pg_constraint.confdeltype: 'c' = CASCADE, 'n' = SET NULL, 'a' = NO ACTION.
        String del = jdbc.queryForObject(
                "SELECT confdeltype FROM pg_constraint WHERE conname = 'fk_piv_produto'", String.class);
        assertThat(del).as("fk_piv_produto ON DELETE CASCADE").isEqualTo("c");
    }

    // ----- CA-C1: os dois CHECKs existem -----

    @Test
    void checksDaV11EstaoPresentes() {
        List<String> nomes = jdbc.queryForList(
                "SELECT conname FROM pg_constraint "
                        + "WHERE conrelid = 'produto_imagem_variante'::regclass AND contype = 'c'",
                String.class);
        assertThat(nomes).contains("ck_piv_tamanho", "ck_piv_tipo");
    }

    // ----- CA-C1: CHECKs de comportamento -----

    /** thumb/medio com content-type valido (webp/jpeg) sao aceitos. */
    @Test
    void variantesValidasSaoAceitas() {
        Long produtoId = inserirProduto("Rosa");
        assertThatCode(() -> inserirVariante(produtoId, "thumb", "image/webp"))
                .doesNotThrowAnyException();
        assertThatCode(() -> inserirVariante(produtoId, "medio", "image/jpeg"))
                .doesNotThrowAnyException();
    }

    /** ck_piv_tamanho — tamanho fora de thumb/medio (ex.: 'original') e rejeitado. */
    @Test
    void tamanhoForaDoEnumERejeitado() {
        Long produtoId = inserirProduto("Lirio");
        assertThatThrownBy(() -> inserirVariante(produtoId, "original", "image/webp"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_piv_tamanho");
    }

    /** ck_piv_tipo — content-type fora de webp/jpeg (ex.: image/png) e rejeitado. */
    @Test
    void contentTypeForaDaWhitelistERejeitado() {
        Long produtoId = inserirProduto("Cravo");
        assertThatThrownBy(() -> inserirVariante(produtoId, "thumb", "image/png"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_piv_tipo");
    }

    /** PK composta — (produto_id, tamanho) duplicado e rejeitado (1 linha por variante). */
    @Test
    void pkCompostaRejeitaDuplicata() {
        Long produtoId = inserirProduto("Tulipa");
        inserirVariante(produtoId, "thumb", "image/webp");
        assertThatThrownBy(() -> inserirVariante(produtoId, "thumb", "image/jpeg"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("pk_produto_imagem_variante");
    }

    // ----- CA-C9/C10: FK CASCADE — hard delete do produto limpa as variantes -----

    @Test
    void hardDeleteDoProdutoLimpaVariantesPorCascade() {
        Long produtoId = inserirProduto("Girassol");
        inserirVariante(produtoId, "thumb", "image/webp");
        inserirVariante(produtoId, "medio", "image/webp");

        assertThatCode(() -> jdbc.update("DELETE FROM produto WHERE id = ?", produtoId))
                .doesNotThrowAnyException();

        Integer restantes = jdbc.queryForObject(
                "SELECT count(*) FROM produto_imagem_variante WHERE produto_id = ?",
                Integer.class, produtoId);
        assertThat(restantes).as("variantes limpas pelo cascade (FC-08)").isZero();
    }
}
