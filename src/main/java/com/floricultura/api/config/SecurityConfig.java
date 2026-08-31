package com.floricultura.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Baseline de seguranca do M0 (AD-SQ-6, aprovada no gate). {@code SecurityFilterChain}
 * <b>permissivo</b> ({@code permitAll}) para o scaffold: sem o permitAll, o starter-security
 * bloquearia o health com a senha padrao gerada (401) e CA-2/CA-4 falhariam.
 *
 * <p>CSRF off (API stateless), CORS on (referenciando o {@code CorsConfigurationSource} dentro do
 * chain — §12), sessao STATELESS, {@code httpBasic}/{@code formLogin} desabilitados. O
 * {@code PasswordEncoder} (BCrypt) fica declarado para uso no M1 (login/JWT). Autorizacao por role
 * em runtime = M1.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/**").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().permitAll())
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable());
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
