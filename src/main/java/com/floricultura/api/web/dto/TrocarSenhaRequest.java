package com.floricultura.api.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corpo de {@code PATCH /api/v1/auth/senha} — troca da propria senha (SPEC-M1 §3.2, CA-10).
 *
 * <p>{@code novaSenha} &lt; 8 ou em branco → {@code 400 VALIDATION_ERROR} por Bean Validation
 * (politica minima de 8 — §9). {@code senhaAtual} incorreta e regra de negocio (comparada por
 * BCrypt no servico) → {@code 400} campo {@code senhaAtual}, tratada no {@code AuthController}.
 * Nenhum dos valores e logado (§9).
 *
 * @param senhaAtual senha atual (obrigatoria; validada por {@code PasswordEncoder.matches})
 * @param novaSenha  nova senha (obrigatoria, minimo 8 caracteres; gravada como BCrypt)
 */
public record TrocarSenhaRequest(
        @NotBlank String senhaAtual,
        @NotBlank @Size(min = 8) String novaSenha) {
}
