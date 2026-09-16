package com.floricultura.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.floricultura.api.service.JwtService;
import com.floricultura.api.web.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integracao da camada de seguranca do M1 (SPEC-M1 §3.4, CA-4/CA-5/CA-6) contra um PostgreSQL de
 * DESCARTE (Testcontainers postgres:16), com o {@code SecurityConfig} endurecido + o
 * {@link com.floricultura.api.config.JwtAuthenticationFilter} reais no contexto. Os JWT sao emitidos
 * pelo {@link JwtService} (mesmo bean/segredo do filtro), garantindo round-trip fiel.
 *
 * <p>Os controllers de negocio ({@code /auth/me}, {@code /usuarios}) chegam em T-M1-3/T-M1-4, entao
 * este teste exercita a <b>regra de seguranca</b> via um <i>probe-controller</i> de teste em rotas
 * distintas que caem nas MESMAS regras dos matchers (§3.4): {@code GET /api/v1/_probe} (autenticado,
 * como {@code /auth/me}) e {@code GET /api/v1/usuarios/_probe} (ROLE_ADMIN, como {@code /usuarios}).
 * Rotas distintas evitam colisao de mapeamento quando os controllers reais surgirem.
 *
 * <p>Nome {@code *Test} (Surefire), como as demais integracoes Testcontainers do repo (o
 * {@code verify} nao ativa failsafe). Sem segredo versionado: os hashes de seed sao BCrypt de valores
 * aleatorios gerados em runtime (§9).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthSecurityTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Segredo test-only (NAO-segredo; >= 32 bytes) compartilhado entre o JwtService injetado
        // (emite os tokens do teste) e o filtro (valida) — mesmo bean, mesma chave.
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-authsec-0123456789abcd");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbc;

    private Long adminId;
    private Long userId;

    @BeforeEach
    void seed() {
        // V2 e no-op de seed aqui (placeholders vazios); estado conhecido inserido direto via JDBC.
        jdbc.update("DELETE FROM usuario");
        adminId = inserirUsuario("admin-sec@floricultura.local", "ADMIN", true);
        userId = inserirUsuario("user-sec@floricultura.local", "USER", true);
    }

    private Long inserirUsuario(String email, String role, boolean ativo) {
        return jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, ?, ?, false) RETURNING id",
                Long.class,
                "Nome " + role,
                email,
                ENCODER.encode(UUID.randomUUID().toString()),
                role,
                ativo);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    // ---- CA-4: rota autenticada sem/with token ----------------------------------------------

    @Test
    void semToken_rotaAutenticada_devolve401() throws Exception {
        mockMvc.perform(get("/api/v1/_probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void comTokenValido_rotaAutenticada_devolve200() throws Exception {
        String token = jwtService.gerarToken(userId);
        mockMvc.perform(get("/api/v1/_probe").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ok").value(true));
    }

    @Test
    void tokenAdulterado_rotaAutenticada_devolve401() throws Exception {
        String token = jwtService.gerarToken(userId);
        int corte = token.lastIndexOf('.') + 1; // 1o caractere da ASSINATURA
        char original = token.charAt(corte);
        String adulterado = token.substring(0, corte)
                + (original == 'A' ? 'B' : 'A') // troca deterministica
                + token.substring(corte + 1);
        // Guarda: o 1o caractere da assinatura carrega 6 bits SIGNIFICATIVOS, entao os bytes
        // decodificados MUDAM de fato. (No ULTIMO caractere isso nao vale: 2 bits sao padding e
        // some na decodificacao - era o que tornava o caso instavel ~1/1024.)
        assertThat(Base64.getUrlDecoder().decode(adulterado.substring(corte)))
                .isNotEqualTo(Base64.getUrlDecoder().decode(token.substring(corte)));
        mockMvc.perform(get("/api/v1/_probe").header(HttpHeaders.AUTHORIZATION, bearer(adulterado)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // ---- CA-5: token de usuario desativado em runtime ---------------------------------------

    @Test
    void tokenDeUsuarioDesativadoAposEmissao_devolve401() throws Exception {
        String token = jwtService.gerarToken(userId);
        // Com o usuario ativo, o token funciona.
        mockMvc.perform(get("/api/v1/_probe").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());

        // Desativa em runtime; o filtro recarrega `ativo` do banco a cada requisicao (L5, sem cache).
        jdbc.update("UPDATE usuario SET ativo = false WHERE id = ?", userId);

        mockMvc.perform(get("/api/v1/_probe").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // ---- CA-6: RBAC em /usuarios/** ----------------------------------------------------------

    @Test
    void user_acessaRotaAdmin_devolve403() throws Exception {
        String token = jwtService.gerarToken(userId);
        mockMvc.perform(get("/api/v1/usuarios/_probe").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void admin_acessaRotaAdmin_devolve200() throws Exception {
        String token = jwtService.gerarToken(adminId);
        mockMvc.perform(get("/api/v1/usuarios/_probe").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true));
    }

    // ---- AD-SQ-21: 404 preservado quando AUTENTICADO ----------------------------------------

    @Test
    void rotaInexistente_comTokenValido_devolve404() throws Exception {
        String token = jwtService.gerarToken(adminId);
        mockMvc.perform(get("/api/v1/inexistente").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    /** Endpoints de sondagem (test-only) sob os mesmos matchers do §3.4 — nao vao para producao. */
    @TestConfiguration
    static class ProbeConfig {

        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {

        @GetMapping("/api/v1/_probe")
        ApiResponse<Map<String, Boolean>> autenticado(HttpServletRequest request) {
            return ApiResponse.ok(Map.of("ok", true), request.getRequestURI());
        }

        @GetMapping("/api/v1/usuarios/_probe")
        ApiResponse<Map<String, Boolean>> somenteAdmin(HttpServletRequest request) {
            return ApiResponse.ok(Map.of("ok", true), request.getRequestURI());
        }
    }
}
