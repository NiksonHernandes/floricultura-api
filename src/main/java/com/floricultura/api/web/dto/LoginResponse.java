package com.floricultura.api.web.dto;

/**
 * {@code data} de {@code 200} em {@code POST /api/v1/auth/login} (SPEC-M1 §3.2, CA-1). O {@code token}
 * (JWT HS256) so volta <b>aqui</b>, uma unica vez; nunca e logado (§9).
 *
 * @param tokenType tipo do token ({@code "Bearer"})
 * @param token     JWT compacto assinado (emitido pelo {@code JwtService})
 * @param expiresIn TTL em segundos (do {@code JwtService.getTtlSeconds()})
 * @param usuario   sessao minima do autenticado (sem {@code senha_hash})
 */
public record LoginResponse(
        String token,
        String tokenType,
        long expiresIn,
        UsuarioSessaoResponse usuario) {
}
