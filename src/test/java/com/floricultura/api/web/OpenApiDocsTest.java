package com.floricultura.api.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integracao do Swagger/OpenAPI do M1 (SPEC-M1 §2/CA-15, item 18 do §10) contra um PostgreSQL de
 * DESCARTE (Testcontainers postgres:16), com o contexto completo — {@code SecurityConfig}
 * endurecido + {@link com.floricultura.api.config.OpenApiConfig} + os controllers reais de
 * {@code auth}/{@code usuarios}. Prova que:
 *
 * <ul>
 *   <li>{@code GET /v3/api-docs} responde <b>200</b> (rota <b>publica</b> pelo matcher do T-M1-2,
 *       sem token);</li>
 *   <li>o documento lista os paths de {@code auth} ({@code /api/v1/auth/login}, {@code /auth/me},
 *       {@code /auth/senha}) e de {@code usuarios} ({@code /api/v1/usuarios} e
 *       {@code /usuarios/{id}});</li>
 *   <li>contem o esquema de seguranca {@code bearerAuth} (HTTP, {@code scheme:bearer},
 *       {@code bearerFormat:JWT}) e o aplica nas rotas autenticadas, deixando o {@code login}
 *       publico (sem requisito de seguranca).</li>
 * </ul>
 *
 * <p>Nome {@code *Test} (Surefire), como as demais integracoes Testcontainers do repo. Segredo
 * test-only (§9): o {@code APP_JWT_SECRET} vem do Surefire e o {@code app.jwt.secret} das props de
 * teste — o contexto precisa do {@link com.floricultura.api.service.JwtService} para subir.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OpenApiDocsTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Segredo test-only (NAO-segredo; >= 32 bytes) — necessario so para o contexto subir.
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-openapi-0123456789abcd");
    }

    @Autowired
    private MockMvc mockMvc;

    /** CA-15 / item 18: doc publico, 200, lista auth+usuarios e o esquema bearerAuth. */
    @Test
    void apiDocs_publico_listaAuthEUsuariosComBearerAuth() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                // Documento OpenAPI 3.x.
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.info.title").value("Floricultura API"))
                // Paths de auth.
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.tags[0]").value("auth"))
                .andExpect(jsonPath("$.paths['/api/v1/auth/me'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/senha'].patch").exists())
                // Paths de usuarios.
                .andExpect(jsonPath("$.paths['/api/v1/usuarios'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/usuarios'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/usuarios'].get.tags[0]").value("usuarios"))
                .andExpect(jsonPath("$.paths['/api/v1/usuarios/{id}'].get").exists())
                // Esquema de seguranca Bearer JWT presente (CA-15).
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(
                        jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
                // Rotas autenticadas exigem bearerAuth...
                .andExpect(jsonPath("$.paths['/api/v1/auth/me'].get.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/usuarios'].get.security[0].bearerAuth").exists())
                // ...e o login e PUBLICO (sem requisito de seguranca).
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.security").doesNotExist());
    }
}
