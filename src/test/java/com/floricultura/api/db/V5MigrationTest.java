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
 * Integracao da migracao V5 (T-M3-1, CA-1/CA-9) contra um PostgreSQL de DESCARTE (Testcontainers
 * postgres:16). Sobe o contexto real (datasource/JPA/Flyway ativos): o Flyway aplica V1..V5 na subida
 * e as asercoes validam via JDBC (dispensa {@code psql}).
 *
 * <p>Cobre <b>CA-1</b>: V5 registrada com {@code success}, colunas {@code imagem BYTEA}/{@code
 * imagem_content_type VARCHAR(100)}/{@code imagem_filename VARCHAR(255)} anulaveis, CHECKs {@code
 * ck_produto_imagem_coerente} e {@code ck_produto_imagem_tipo}. O proprio fato de {@code
 * @SpringBootTest} subir com {@code ddl-auto=validate} prova que a @Entity (que NAO mapeia {@code
 * imagem}) casa o schema — colunas de banco nao mapeadas nao derrubam o validate (AD-SQ-38/§12).
 */
@SpringBootTest
@Testcontainers
class V5MigrationTest {

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

    private Map<String, Object> coluna(String nome) {
        return jdbc.queryForMap(
                "SELECT data_type, character_maximum_length, is_nullable "
                        + "FROM information_schema.columns "
                        + "WHERE table_name = 'produto' AND column_name = ?", nome);
    }

    // ----- CA-1: V5 aplicada + colunas -----

    /** CA-1: Flyway registrou a versao 5 com success=true. */
    @Test
    void flywayAplicouVersao5ComSucesso() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT version, success FROM flyway_schema_history WHERE version = '5'");
        assertThat(row.get("version")).isEqualTo("5");
        assertThat(row.get("success")).isEqualTo(Boolean.TRUE);
    }

    /** CA-1: coluna imagem existe como bytea, anulavel. */
    @Test
    void colunaImagemExisteComoByteaAnulavel() {
        Map<String, Object> col = coluna("imagem");
        assertThat(col.get("data_type")).isEqualTo("bytea");
        assertThat(col.get("is_nullable")).isEqualTo("YES");
    }

    /** CA-1: imagem_content_type = varchar(100) anulavel; imagem_filename = varchar(255) anulavel. */
    @Test
    void colunasDeMetadadoExistemComoVarcharAnulaveis() {
        Map<String, Object> contentType = coluna("imagem_content_type");
        assertThat(contentType.get("data_type")).isEqualTo("character varying");
        assertThat(((Number) contentType.get("character_maximum_length")).intValue()).isEqualTo(100);
        assertThat(contentType.get("is_nullable")).isEqualTo("YES");

        Map<String, Object> filename = coluna("imagem_filename");
        assertThat(filename.get("data_type")).isEqualTo("character varying");
        assertThat(((Number) filename.get("character_maximum_length")).intValue()).isEqualTo(255);
        assertThat(filename.get("is_nullable")).isEqualTo("YES");
    }

    /** CA-1: os dois CHECKs da V5 existem na tabela produto. */
    @Test
    void checksDaV5EstaoPresentes() {
        List<String> nomes = jdbc.queryForList(
                "SELECT conname FROM pg_constraint "
                        + "WHERE conrelid = 'produto'::regclass AND contype = 'c'", String.class);
        assertThat(nomes)
                .contains("ck_produto_imagem_coerente", "ck_produto_imagem_tipo");
    }

    // ----- CA-1: CHECKs de comportamento -----

    /** CA-1: convivencia — produto SEM imagem (ambos NULL) e aceito (nao-destrutivo p/ o M2). */
    @Test
    void produtoSemImagemEAceito() {
        assertThatCode(() -> jdbc.update(
                "INSERT INTO produto (nome, unidade_medida) VALUES ('Rosa', 'un')"))
                .doesNotThrowAnyException();
    }

    /** CA-1: coerencia — imagem completa (bytea + content_type validos) e aceita. */
    @Test
    void imagemCompletaComTipoValidoEAceita() {
        assertThatCode(() -> jdbc.update(
                "INSERT INTO produto (nome, unidade_medida, imagem, imagem_content_type, imagem_filename) "
                        + "VALUES ('Lirio', 'un', decode('FFD8FF', 'hex'), 'image/jpeg', 'lirio.jpg')"))
                .doesNotThrowAnyException();
    }

    /** CA-1: ck_produto_imagem_coerente — bytea presente SEM content_type e rejeitado. */
    @Test
    void byteaSemContentTypeERejeitado() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO produto (nome, unidade_medida, imagem) "
                        + "VALUES ('Cravo', 'un', decode('FFD8FF', 'hex'))"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_produto_imagem_coerente");
    }

    /** CA-1: ck_produto_imagem_coerente — content_type presente SEM bytea e rejeitado. */
    @Test
    void contentTypeSemByteaERejeitado() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO produto (nome, unidade_medida, imagem_content_type) "
                        + "VALUES ('Tulipa', 'un', 'image/png')"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_produto_imagem_coerente");
    }

    /** CA-1: ck_produto_imagem_tipo — content_type fora da whitelist e rejeitado. */
    @Test
    void contentTypeForaDaWhitelistERejeitado() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO produto (nome, unidade_medida, imagem, imagem_content_type) "
                        + "VALUES ('Girassol', 'un', decode('4749', 'hex'), 'image/gif')"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_produto_imagem_tipo");
    }

    /** CA-1: a whitelist aceita os tres tipos suportados (jpeg/png/webp). */
    @Test
    void whitelistAceitaJpegPngWebp() {
        for (String tipo : List.of("image/jpeg", "image/png", "image/webp")) {
            assertThatCode(() -> jdbc.update(
                    "INSERT INTO produto (nome, unidade_medida, imagem, imagem_content_type) "
                            + "VALUES (?, 'un', decode('00', 'hex'), ?)", "P " + tipo, tipo))
                    .doesNotThrowAnyException();
        }
    }
}
