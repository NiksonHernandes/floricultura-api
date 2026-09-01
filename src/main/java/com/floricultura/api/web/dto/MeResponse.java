package com.floricultura.api.web.dto;

import com.floricultura.api.domain.Usuario;

/**
 * {@code data} de {@code GET /api/v1/auth/me} (SPEC-M1 §3.2, CA-4) — perfil do usuario autenticado,
 * recarregado do banco a cada chamada (role/{@code ativo} sempre frescos — §3.3). Nunca expoe
 * {@code senha_hash} (§9).
 *
 * @param id              id do usuario
 * @param nome            nome de exibicao
 * @param email           e-mail
 * @param role            papel ({@code ADMIN}/{@code USER})
 * @param ativo           estado da conta
 * @param senhaProvisoria flag de troca forcada
 */
public record MeResponse(
        Long id,
        String nome,
        String email,
        String role,
        boolean ativo,
        boolean senhaProvisoria) {

    /** Mapeia a entidade para o perfil, sem {@code senha_hash} (§9/CA-8). */
    public static MeResponse de(Usuario usuario) {
        return new MeResponse(
                usuario.getId(),
                usuario.getNome(),
                usuario.getEmail(),
                usuario.getRole(),
                usuario.isAtivo(),
                usuario.isSenhaProvisoria());
    }
}
