package com.floricultura.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Seguranca endurecida do M1 (SPEC-M1 §3.4 — substitui o {@code permitAll} do scaffold M0). A
 * autenticacao passa a ser exigida em runtime: o {@link JwtAuthenticationFilter} valida o Bearer JWT
 * e popula o {@code SecurityContext}; o RBAC ({@code hasRole("ADMIN")}) protege {@code /usuarios/**}.
 *
 * <p><b>Rotas publicas (§3.4):</b> {@code POST /api/v1/auth/login}, {@code GET /api/v1/health-check},
 * {@code /actuator/health}, {@code /actuator/info} e as rotas do Swagger. <b>Resto de {@code /api/v1}
 * autenticado</b> — inclui rota inexistente sem token → 401 (AD-SQ-21; o 404 com token valido e
 * coberto no {@code AuthSecurityTest}).
 *
 * <p><b>RBAC de produtos (M2/T-M2-3, FC-07):</b> {@code GET /api/v1/produtos/**} segue autenticado
 * (USER+ADMIN) via {@code anyRequest().authenticated()}; {@code POST}/{@code PUT}/{@code DELETE} de
 * {@code /api/v1/produtos/**} exigem {@code ROLE_ADMIN} (matchers por metodo, avaliados ANTES do
 * catch-all) — USER que escreve → 403. O matcher {@code POST /produtos/**} ja cobre o futuro
 * {@code POST .../movimentacoes} como ADMIN (T-M2-4). Nada muda em {@code /usuarios/**} nem nos
 * {@code permitAll} do M1.
 *
 * <p><b>RBAC de eventos (M4/T-M4-2, §3.6):</b> mesmo padrao — {@code GET /api/v1/eventos/**} (inclui
 * {@code /eventos/proximos}) segue autenticado (USER+ADMIN); {@code POST}/{@code PUT}/{@code DELETE} de
 * {@code /api/v1/eventos/**} exigem {@code ROLE_ADMIN}. {@code GET /api/v1/movimentacoes} (lista global,
 * T-M4-10) cai no catch-all autenticado, sem matcher novo.
 *
 * <p><b>RBAC de clientes/fornecedores (M5/T-M5-4/T-M5-5, §3.4):</b> mesmo padrao — {@code GET} de
 * {@code /api/v1/clientes/**} e {@code /api/v1/fornecedores/**} segue autenticado (USER+ADMIN);
 * {@code POST}/{@code PUT}/{@code DELETE} desses cadastros (dado pessoal, LGPD) exigem {@code ROLE_ADMIN}.
 *
 * <p>Mantem do M0: CSRF off, CORS on (o {@code CorsFilter} entra ANTES da autorizacao, entao o
 * preflight {@code OPTIONS} e respondido sem exigir auth — §12/CA-M0-5), sessao STATELESS,
 * {@code httpBasic}/{@code formLogin} off, {@code PasswordEncoder} (BCrypt). Os handlers de erro
 * ({@link RestAuthenticationEntryPoint} 401 / {@link RestAccessDeniedHandler} 403) escrevem o
 * envelope §3.1.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CorsConfigurationSource corsConfigurationSource,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/health-check").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers(
                                "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/api/v1/usuarios/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/produtos/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/produtos/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/produtos/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/eventos/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/eventos/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/eventos/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/clientes/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/clientes/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/clientes/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/fornecedores/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/fornecedores/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/fornecedores/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/cores/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/cores/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/cores/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable());
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
