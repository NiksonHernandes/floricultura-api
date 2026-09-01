package com.floricultura.api.web.dto;

import com.floricultura.api.domain.Usuario;
import java.time.Instant;

/**
 * {@code data} das respostas de gestao de usuarios (SPEC-M1 §3.2, CA-7/CA-8): corpo do
 * {@code POST /api/v1/usuarios} (201), item de {@code GET /api/v1/usuarios} (lista) e
 * {@code GET /api/v1/usuarios/{id}} (detalhe).
 *
 * <p><b>Nunca inclui {@code senha_hash} nem qualquer senha (§9/CA-8)</b> — a fabrica {@link #de}
 * mapeia so os campos publicos do perfil administrativo.
 *
 * @param id              id do usuario
 * @param nome            nome de exibicao
 * @param email           e-mail (identificador de login)
 * @param role            papel ({@code ADMIN}/{@code USER})
 * @param ativo           estado da conta (soft-state; desativar ≠ excluir — §4)
 * @param senhaProvisoria se {@code true}, o alvo sera forcado a trocar a senha no proximo login
 * @param criadoEm        instante de criacao (UTC ISO-8601), vindo do {@code DEFAULT now()} do banco
 */
public record UsuarioResponse(
        Long id,
        String nome,
        String email,
        String role,
        boolean ativo,
        boolean senhaProvisoria,
        Instant criadoEm) {

    /** Mapeia a entidade para o response administrativo, deixando {@code senha_hash} de fora (§9/CA-8). */
    public static UsuarioResponse de(Usuario usuario) {
        return new UsuarioResponse(
                usuario.getId(),
                usuario.getNome(),
                usuario.getEmail(),
                usuario.getRole(),
                usuario.isAtivo(),
                usuario.isSenhaProvisoria(),
                usuario.getCriadoEm());
    }
}
