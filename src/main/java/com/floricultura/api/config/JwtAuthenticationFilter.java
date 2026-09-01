package com.floricultura.api.config;

import com.floricultura.api.domain.Usuario;
import com.floricultura.api.repository.UsuarioRepository;
import com.floricultura.api.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filtro de autenticacao JWT (SPEC-M1 §3.4, CA-4/CA-5/CA-6). Roda uma vez por requisicao, ANTES do
 * {@code UsernamePasswordAuthenticationFilter} (registrado no {@code SecurityConfig}).
 *
 * <p>Fluxo:
 * <ol>
 *   <li>Sem header {@code Authorization: Bearer <token>} → segue anonimo (o {@code EntryPoint}
 *       decide 401 se a rota exigir auth).</li>
 *   <li>Com token: valida assinatura + {@code exp} via {@link JwtService#decodificar}. Invalido/
 *       expirado/adulterado → nao autentica (segue anonimo → 401).</li>
 *   <li>Carrega o usuario por {@code sub}. <b>Inexistente ou {@code ativo=false} → nao autentica</b>
 *       (→ 401) — checagem a cada requisicao, <b>sem cache</b> (§12/L5): rebaixar/desativar tem
 *       efeito imediato.</li>
 *   <li>Autentica com {@code authorities = [ROLE_<role-do-banco>]} (a role vem do banco, nao do
 *       token — §3.3). {@code /usuarios/**} = {@code hasRole("ADMIN")} casa com {@code ROLE_ADMIN}.</li>
 * </ol>
 *
 * <p>{@code UsuarioRepository} injetado como {@link Lazy}: os smokes do M0 ({@code contextLoads},
 * {@code HealthCheckControllerTest}) sobem o {@code SecurityConfig} real mas excluem JPA — o repo so
 * e resolvido quando ha token (nunca naqueles testes). O token/segredo <b>nunca</b> e logado (§9/§12).
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UsuarioRepository usuarioRepository;

    public JwtAuthenticationFilter(
            JwtService jwtService, @Lazy UsuarioRepository usuarioRepository) {
        this.jwtService = jwtService;
        this.usuarioRepository = usuarioRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response); // anonimo
            return;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        autenticarSePossivel(token, request);
        filterChain.doFilter(request, response);
    }

    private void autenticarSePossivel(String token, HttpServletRequest request) {
        Long usuarioId;
        try {
            Jwt jwt = jwtService.decodificar(token); // valida assinatura + exp
            usuarioId = Long.valueOf(jwt.getSubject());
        } catch (JwtException | NumberFormatException e) {
            // Token invalido/expirado/adulterado ou sub nao numerico → nao autentica (→ 401).
            // NAO logar o token (§9): apenas o motivo, sem o valor.
            log.debug("Token JWT rejeitado: {}", e.getClass().getSimpleName());
            SecurityContextHolder.clearContext();
            return;
        }

        Optional<Usuario> encontrado = usuarioRepository.findById(usuarioId);
        if (encontrado.isEmpty() || !encontrado.get().isAtivo()) {
            // Usuario inexistente ou desativado em runtime (L5/CA-5) → nao autentica (→ 401).
            SecurityContextHolder.clearContext();
            return;
        }

        Usuario usuario = encontrado.get();
        var authority = new SimpleGrantedAuthority("ROLE_" + usuario.getRole());
        var authentication = new UsernamePasswordAuthenticationToken(
                usuario.getId(), null, List.of(authority));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
