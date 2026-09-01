package com.floricultura.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documentacao OpenAPI/Swagger do M1 (SPEC-M1 §2/CA-15, AD-SQ-20). Descreve os endpoints de
 * {@code auth} e {@code usuarios} e declara o esquema de seguranca <b>Bearer JWT</b> usado pelas
 * rotas autenticadas (o {@code POST /api/v1/auth/login} e publico, sem requisito de seguranca).
 *
 * <p>A UI ({@code /swagger-ui.html}) e o documento ({@code /v3/api-docs}) sao <b>publicos</b> pelos
 * matchers ja previstos no {@code SecurityConfig} do T-M1-2 (§3.4) — este bean nao mexe em
 * seguranca. O esquema {@code bearerAuth} e registrado nos {@code components} aqui (sempre presente
 * no documento, CA-15) e referenciado nas operacoes autenticadas via {@code @SecurityRequirement}.
 *
 * <p>Segredo (§9): a doc <b>nao</b> expoe token nem senha; os exemplos ilustrativos usam o dominio
 * ficticio {@code .local}, sem PII real.
 */
@Configuration
public class OpenApiConfig {

    /** Nome do esquema de seguranca referenciado por {@code @SecurityRequirement} nos controllers. */
    public static final String BEARER_AUTH = "bearerAuth";

    @Bean
    public OpenAPI floriculturaOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Floricultura API")
                        .version("v1")
                        .description("API de gestao interna da Floricultura — autenticacao (JWT "
                                + "stateless) e gestao de usuarios (M1). Rotas autenticadas exigem "
                                + "o header Authorization: Bearer <token> obtido em POST "
                                + "/api/v1/auth/login."))
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                                .name(BEARER_AUTH)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
