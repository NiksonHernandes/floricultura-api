package com.floricultura.api.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Prova do seed do ADMIN de DESENVOLVIMENTO via Flyway repeatable dev-only (SPEC-M1.1 §3.2,
 * CA-1/CA-2/CA-3/CA-6 · AD-SQ-23) contra um PostgreSQL de DESCARTE (Testcontainers postgres:16).
 *
 * <p>O contexto sobe no profile <b>default {@code dev}</b> (application.yml define
 * {@code spring.profiles.default: dev}, como as demais integracoes Testcontainers do repo), entao o
 * {@code application-dev.yml} vale e o {@code spring.flyway.locations} inclui {@code db/migration-dev}
 * — o Flyway aplica {@code V1+V2+R__seed_admin_dev.sql} na subida. <b>Nenhuma env de seed</b>
 * ({@code APP_SEED_ADMIN_*}) e injetada: a V2 fica no-op de seed e <b>quem cria o admin e o
 * {@code R__}</b> (CA-1: admin dev existe sem env).
 *
 * <p><b>Nome {@code *Test} (Surefire), nao {@code *IT}:</b> este repo <b>nao</b> ativa o
 * {@code maven-failsafe-plugin}, entao {@code *IT} nao rodaria no {@code ./mvnw clean verify} (DoD
 * §10 item 1 exige este teste verde no {@code verify}). Segue a convencao ja ratificada em M0/M1
 * ({@code SeedAdminTest}, {@code V1BaselineMigrationTest}). A senha {@code admin} e credencial
 * <b>DEV conhecida</b> (publica, dev-only — AD-SQ-23), a unica excecao sancionada ao "zero segredo".
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SeedAdminDevTest {

    private static final String EMAIL_ADMIN_DEV = "admin@floricultura.local";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Segredo test-only (NAO-segredo; >= 32 bytes) do JwtService que emite o token no login abaixo.
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-seeddev-0123456789ab");
        // NOTA: NENHUM APP_SEED_ADMIN_* — prova CA-1 (admin dev nasce so do R__, sem env de seed).
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    /**
     * CA-1: sem env de seed, existe EXATAMENTE 1 admin@floricultura.local com role ADMIN, ativo e
     * senha_provisoria=FALSE (o 1o acesso NAO forca troca — AD-SQ-24).
     */
    @Test
    void seedDevCriaExatamenteUmAdminAtivoNaoProvisorio() {
        var row = jdbc.queryForMap(
                "SELECT role, ativo, senha_provisoria FROM usuario WHERE email = ?", EMAIL_ADMIN_DEV);
        assertThat(row.get("role")).isEqualTo("ADMIN");
        assertThat(row.get("ativo")).isEqualTo(Boolean.TRUE);
        assertThat(row.get("senha_provisoria")).isEqualTo(Boolean.FALSE);

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM usuario WHERE email = ?", Long.class, EMAIL_ADMIN_DEV);
        assertThat(total).isEqualTo(1L);
    }

    /**
     * CA-1: login do admin dev com a senha DEV conhecida {@code admin} devolve 200, token e role
     * ADMIN; {@code senhaProvisoria=false} (informativa — nada e forcado, AD-SQ-24).
     */
    @Test
    void loginDoAdminDevComSenhaAdmin_devolve200TokenERoleAdmin() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL_ADMIN_DEV + "\",\"senha\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.usuario.role").value("ADMIN"))
                .andExpect(jsonPath("$.data.usuario.senhaProvisoria").value(false));
    }

    /**
     * CA-2: idempotencia sobre banco ja migrado. Re-executar o INSERT do {@code R__} (exercita
     * {@code ON CONFLICT (email) DO NOTHING}) e disparar um {@code flyway.migrate()} extra (subir a
     * API "duas vezes") mantem a contagem de admin@floricultura.local em 1 — sem duplicar.
     */
    @Test
    void reaplicarSeedEMigrarNovamente_mantemUmAdmin() {
        jdbc.update(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES ('Administrador (dev)', ?, 'hash-qualquer', 'ADMIN', TRUE, FALSE) "
                        + "ON CONFLICT (email) DO NOTHING",
                EMAIL_ADMIN_DEV);
        flyway.migrate();

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM usuario WHERE email = ?", Long.class, EMAIL_ADMIN_DEV);
        assertThat(total).isEqualTo(1L);
    }
}
