package com.floricultura.api.web.dto;

import com.floricultura.api.domain.Usuario;

/**
 * Objeto {@code data.usuario} da resposta de login (SPEC-M1 §3.2, CA-1). Espelha a sessao minima que
 * o front precisa (id/nome/email/role/senhaProvisoria) — <b>nunca</b> expoe {@code senha_hash} (§9).
 *
 * @param id              id do usuario
 * @param nome            nome de exibicao
 * @param email           e-mail (identificador de login)
 * @param role            papel ({@code ADMIN}/{@code USER}) — lido do banco
 * @param senhaProvisoria se {@code true}, o front forca a troca de senha no 1o login
 */
public record UsuarioSessaoResponse(
        Long id,
        String nome,
        String email,
        String role,
        boolean senhaProvisoria) {

    /** Mapeia a entidade para a sessao, deixando {@code senha_hash} de fora (§9/CA-1). */
    public static UsuarioSessaoResponse de(Usuario usuario) {
        return new UsuarioSessaoResponse(
                usuario.getId(),
                usuario.getNome(),
                usuario.getEmail(),
                usuario.getRole(),
                usuario.isSenhaProvisoria());
    }
}
