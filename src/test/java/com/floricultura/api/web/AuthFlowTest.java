package com.floricultura.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Fluxo de autenticacao do M1 (SPEC-M1 §3.1/§3.2, CA-1/CA-2/CA-3/CA-10) contra um PostgreSQL de
 * DESCARTE (Testcontainers postgres:16), com o {@code AuthController}/{@code AuthService},
 * {@code JwtService}, filtro e {@code SecurityConfig} reais no contexto. Complementa o
 * {@code AuthSecurityTest} (CA-4/5/6) — <b>nao</b> os duplica.
 *
 * <p>Nome {@code *Test} (Surefire), como as demais integracoes Testcontainers do repo (o
 * {@code verify} nao ativa failsafe). <b>Sem segredo versionado (§9):</b> a senha de teste e um
 * {@link UUID} aleatorio de runtime e o {@code senha_hash} e o BCrypt dela — nenhum literal de
 * credencial no repositorio.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthFlowTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    // Senha de teste efemera (runtime): nunca versionada. O hash gravado e o BCrypt dela.
    private static final String SENHA = UUID.randomUUID().toString();

    private static final String EMAIL_ATIVO = "flow-ativo@floricultura.local";
    private static final String EMAIL_INATIVO = "flow-inativo@floricultura.local";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Segredo test-only (NAO-segredo; >= 32 bytes) compartilhado pelo JwtService (emite/valida).
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-authflow-0123456789ab");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private Long ativoId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM usuario");
        ativoId = inserir(EMAIL_ATIVO, "USER", true, true);   // ativo, senha_provisoria=true (CA-10)
        inserir(EMAIL_INATIVO, "USER", false, false);         // inativo (CA-3)
    }

    private Long inserir(String email, String role, boolean ativo, boolean provisoria) {
        return jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class,
                "Nome " + role, email, ENCODER.encode(SENHA), role, ativo, provisoria);
    }

    private String loginBody(String email, String senha) {
        return "{\"email\":\"" + email + "\",\"senha\":\"" + senha + "\"}";
    }

    // ---- CA-1: login OK -----------------------------------------------------------------------

    @Test
    void loginComCredenciaisCorretas_devolve200ComTokenESemHash() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(EMAIL_ATIVO, SENHA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(3600))
                .andExpect(jsonPath("$.data.usuario.id").value(ativoId))
                .andExpect(jsonPath("$.data.usuario.email").value(EMAIL_ATIVO))
                .andExpect(jsonPath("$.data.usuario.role").value("USER"))
                .andExpect(jsonPath("$.data.usuario.senhaProvisoria").value(true))
                // §9/CA-1: nunca expor senha_hash na resposta.
                .andExpect(jsonPath("$.data.usuario.senhaHash").doesNotExist());
    }

    // ---- CA-2/CA-3: 401 generico byte-a-byte identico nos tres casos --------------------------

    @Test
    void loginInexistenteSenhaErradaEInativo_produzemCorpo401Identico() throws Exception {
        String inexistente = corpoDe(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("nao-existe@floricultura.local", SENHA)));

        String senhaErrada = corpoDe(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(EMAIL_ATIVO, "senha-errada-" + UUID.randomUUID())));

        String inativo = corpoDe(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(EMAIL_INATIVO, SENHA)));

        // Nucleo anti-enumeracao (CA-2/CA-3): corpos identicos a menos do "timestamp"/"path" comuns.
        assertThat(normalizar(inexistente)).isEqualTo(normalizar(senhaErrada));
        assertThat(normalizar(senhaErrada)).isEqualTo(normalizar(inativo));
    }

    @Test
    void loginInvalido_devolve401SemTokenComMensagemGenerica() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(EMAIL_ATIVO, "outra-senha-" + UUID.randomUUID())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error.message").value("Credenciais invalidas."))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    // ---- CA-4 (smoke do endpoint real /auth/me com token de verdade) --------------------------

    @Test
    void me_comTokenValido_devolvePerfilSemHash() throws Exception {
        String token = loginEObterToken();
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(ativoId))
                .andExpect(jsonPath("$.data.email").value(EMAIL_ATIVO))
                .andExpect(jsonPath("$.data.ativo").value(true))
                .andExpect(jsonPath("$.data.senhaProvisoria").value(true))
                .andExpect(jsonPath("$.data.senhaHash").doesNotExist());
    }

    // ---- CA-10: troca da propria senha --------------------------------------------------------

    @Test
    void trocarSenha_comSenhaAtualCorreta_devolve204EZeraProvisoria() throws Exception {
        String token = loginEObterToken();
        mockMvc.perform(patch("/api/v1/auth/senha")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"senhaAtual\":\"" + SENHA + "\",\"novaSenha\":\"novaSenha123\"}"))
                .andExpect(status().isNoContent());

        Boolean provisoria = jdbc.queryForObject(
                "SELECT senha_provisoria FROM usuario WHERE id = ?", Boolean.class, ativoId);
        assertThat(provisoria).isFalse();
        // A nova senha passa a autenticar (prova que o hash foi regravado).
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(EMAIL_ATIVO, "novaSenha123")))
                .andExpect(status().isOk());
    }

    @Test
    void trocarSenha_comSenhaAtualErrada_devolve400CampoSenhaAtualSemDeslogar() throws Exception {
        String token = loginEObterToken();
        mockMvc.perform(patch("/api/v1/auth/senha")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"senhaAtual\":\"errada-xyz\",\"novaSenha\":\"novaSenha123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("senhaAtual"))
                .andExpect(jsonPath("$.error.details[0].message").value("senha atual incorreta"));

        // Nao deslogou: o mesmo token segue valendo em rota autenticada.
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void trocarSenha_comNovaSenhaCurta_devolve400Validacao() throws Exception {
        String token = loginEObterToken();
        mockMvc.perform(patch("/api/v1/auth/senha")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"senhaAtual\":\"" + SENHA + "\",\"novaSenha\":\"curta\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    // ---- helpers -----------------------------------------------------------------------------

    private String loginEObterToken() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(EMAIL_ATIVO, SENHA)))
                .andExpect(status().isOk())
                .andReturn();
        String body = res.getResponse().getContentAsString();
        int i = body.indexOf("\"token\":\"") + 9;
        return body.substring(i, body.indexOf('"', i));
    }

    private String corpoDe(org.springframework.test.web.servlet.RequestBuilder rb) throws Exception {
        return mockMvc.perform(rb)
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
    }

    /** Remove os campos variaveis do envelope ({@code timestamp}) para comparar so o conteudo do erro. */
    private static String normalizar(String json) {
        return json.replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"<>\"");
    }
}
