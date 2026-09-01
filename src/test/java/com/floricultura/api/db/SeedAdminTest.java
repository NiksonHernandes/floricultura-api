package com.floricultura.api.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Prova do seed do 1o ADMIN da migracao V2 (SPEC-M1 §3.5, CA-12) contra um PostgreSQL de DESCARTE
 * (Testcontainers postgres:16). O Flyway aplica V1+V2 na subida; as env de seed sao injetadas como
 * placeholders via {@link DynamicPropertySource} (camada Spring -> Flyway).
 *
 * <p><b>Sem segredo versionado (FC-15/§9):</b> nenhum hash/senha literal no repositorio — o hash
 * BCrypt e gerado em <i>runtime</i> a partir de um {@link UUID} aleatorio. O teste prova o
 * <i>mecanismo</i> de seed (linha ADMIN ativa/provisoria existe), nao o login (isso e AuthFlow no
 * T-M1-3, que exige o JwtService do T-M1-2).
 *
 * <p><b>Nome {@code *Test} (Surefire), nao {@code *IT}:</b> este repo nao ativa o
 * {@code maven-failsafe-plugin} — o M0 ja segue esta convencao ({@code V1BaselineMigrationTest} e um
 * teste de integracao Testcontainers nomeado {@code *Test}). {@code *IT} nao rodaria no
 * {@code ./mvnw clean verify} (DoD §10 item 1).
 */
@SpringBootTest
@Testcontainers
class SeedAdminTest {

    private static final String SEED_EMAIL = "seed-admin-it@floricultura.local";

    // Hash BCrypt DESCARTAVEL, gerado em runtime de um valor aleatorio: prova o mecanismo de seed
    // sem versionar senha/hash (FC-15). Nao e literal de credencial nem casa o grep anti-segredo §10.
    private static final String SEED_HASH =
            new BCryptPasswordEncoder().encode(UUID.randomUUID().toString());

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Placeholders Flyway resolvidos por env em prod; aqui pelas props de teste (camada Spring).
        registry.add("spring.flyway.placeholders.seed_admin_email", () -> SEED_EMAIL);
        registry.add("spring.flyway.placeholders.seed_admin_senha_hash", () -> SEED_HASH);
        // Segredo JWT so para o contexto subir; consumidor real e o T-M1-2. Nao e segredo de verdade.
        registry.add("app.jwt.secret", () -> "test-only-not-a-secret-0123456789abcdef");
    }

    @Autowired
    private JdbcTemplate jdbc;

    /** CA-12: com env de seed presentes, a V2 cria EXATAMENTE 1 ADMIN ativo com senha_provisoria=true. */
    @Test
    void v2SemeiaExatamenteUmAdminAtivoProvisorio() {
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM usuario WHERE role = 'ADMIN' AND ativo AND senha_provisoria",
                Long.class);
        assertThat(total).isEqualTo(1L);
    }

    /** CA-12: o ADMIN semeado casa nome/e-mail/hash dos placeholders e nasce ativo e provisorio. */
    @Test
    void adminSemeadoRefleteOsPlaceholders() {
        var row = jdbc.queryForMap(
                "SELECT nome, email, senha_hash, role, ativo, senha_provisoria "
                        + "FROM usuario WHERE email = ?", SEED_EMAIL);
        assertThat(row.get("nome")).isEqualTo("Administrador");
        assertThat(row.get("role")).isEqualTo("ADMIN");
        assertThat(row.get("ativo")).isEqualTo(Boolean.TRUE);
        assertThat(row.get("senha_provisoria")).isEqualTo(Boolean.TRUE);
        assertThat(row.get("senha_hash")).isEqualTo(SEED_HASH);
    }

    /** V2 (§3.5): a coluna senha_provisoria existe — nasce pela Flyway, nunca pelo Hibernate (§12). */
    @Test
    void colunaSenhaProvisoriaExiste() {
        Long cols = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'usuario' AND column_name = 'senha_provisoria'",
                Long.class);
        assertThat(cols).isEqualTo(1L);
    }
}
