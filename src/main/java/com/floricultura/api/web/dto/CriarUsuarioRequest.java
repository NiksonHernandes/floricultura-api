package com.floricultura.api.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corpo de {@code POST /api/v1/usuarios} (SPEC-M1 §3.2, CA-7) — criacao de usuario pelo ADMIN.
 *
 * <p>Regras de validacao (Bean Validation → {@code 400 VALIDATION_ERROR} com {@code details} pelo
 * {@code GlobalExceptionHandler} do M0): {@code nome} 2..120 obrigatorio, {@code email} formato valido
 * e ≤180 (unicidade e checada no servico → 409), {@code senha} ≥8.
 *
 * <p><b>Sem campo {@code role} (decisao do dono, 2026-09-01 — altera §3.2/AD-SQ-19):</b> todo usuario
 * criado pela API nasce {@code role=USER}. O ADMIN <b>nao</b> cria outro ADMIN pela API; a promocao
 * fica fora do M1 (feita via banco por enquanto). Um {@code role} eventualmente enviado no JSON e
 * ignorado (propriedade desconhecida) — o servico sempre grava {@code USER}.
 *
 * <p>A {@code senha} <b>nunca</b> e logada nem persistida em texto (§9): so trafega em transito e vira
 * {@code PasswordEncoder.encode} (BCrypt).
 *
 * @param nome  nome de exibicao (obrigatorio, 2..120)
 * @param email e-mail unico (obrigatorio, formato valido, ≤180)
 * @param senha senha provisoria (obrigatoria, ≥8; BCrypt no servico, nunca logada)
 */
public record CriarUsuarioRequest(
        @NotBlank @Size(min = 2, max = 120) String nome,
        @NotBlank @Email @Size(max = 180) String email,
        @NotBlank @Size(min = 8) String senha) {
}
