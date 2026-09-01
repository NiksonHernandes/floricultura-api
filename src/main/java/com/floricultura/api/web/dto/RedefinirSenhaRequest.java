package com.floricultura.api.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corpo de {@code PATCH /api/v1/usuarios/{id}/senha} (SPEC-M1 §3.2, CA-11) — reset de senha pelo ADMIN.
 * O ADMIN define a nova senha do alvo; o servico grava o {@code senha_hash} BCrypt e marca
 * {@code senha_provisoria=true}, forcando a troca no proximo login do alvo (§4).
 *
 * <p>Validacao (Bean Validation → {@code 400 VALIDATION_ERROR} com {@code details}): {@code novaSenha}
 * obrigatoria e ≥8 (politica minima do M1 — §9). A {@code novaSenha} <b>nunca</b> e logada nem
 * persistida em texto (§9): so trafega em transito e vira {@code PasswordEncoder.encode} (BCrypt).
 *
 * @param novaSenha nova senha provisoria do alvo (obrigatoria, ≥8; BCrypt no servico, nunca logada)
 */
public record RedefinirSenhaRequest(@NotBlank @Size(min = 8) String novaSenha) {
}
