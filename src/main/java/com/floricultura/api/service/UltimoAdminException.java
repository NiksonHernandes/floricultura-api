package com.floricultura.api.service;

/**
 * Tentativa de desativar o unico ADMIN ativo (SPEC-M1 §3.2/§4, CA-9). Ao processar
 * {@code PATCH /api/v1/usuarios/{id}/status} com {@code ativo=false} sobre um alvo {@code role='ADMIN'}
 * quando ha apenas 1 ADMIN ativo no sistema ({@code countByRoleAndAtivoTrue("ADMIN") == 1}), o servico
 * nega <b>antes</b> de persistir (inclui o caso do proprio ADMIN tentando se autodesativar) →
 * {@code 409 CONFLICT} (traduzida por handler local no {@code UsuarioController}). Protege o primeiro
 * acesso: nunca deixar o sistema sem administrador ativo (AD-SQ-19/FC-08).
 */
public class UltimoAdminException extends RuntimeException {

    public UltimoAdminException() {
        super("Nao e possivel desativar o unico administrador ativo.");
    }
}
