package com.floricultura.api.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corpo de {@code POST /api/v1/usuarios} (SPEC-M1 §3.2, CA-7) — criacao de usuario pelo ADMIN.
 *
 * <p>Regras de validacao (Bean Validation → {@code 400 VALIDATION_ERROR} com {@code details} pelo
 * {@code GlobalExceptionHandler} do M0): {@code nome} 2..120 obrigatorio, {@code email} formato valido
 * e ≤180 (unicidade e checada no servico → 409), {@code senha} ≥8. O {@code role} e opcional
 * ({@code null}/ausente → default {@code USER} no servico); se presente, so aceita {@code ADMIN} ou
 * {@code USER} (o {@code @Pattern} ignora {@code null}, preservando o opcional).
 *
 * <p>A {@code senha} <b>nunca</b> e logada nem persistida em texto (§9): so trafega em transito e vira
 * {@code PasswordEncoder.encode} (BCrypt).
 *
 * @param nome  nome de exibicao (obrigatorio, 2..120)
 * @param email e-mail unico (obrigatorio, formato valido, ≤180)
 * @param senha senha provisoria (obrigatoria, ≥8; BCrypt no servico, nunca logada)
 * @param role  papel opcional {@code ADMIN}/{@code USER} (default {@code USER})
 */
public record CriarUsuarioRequest(
        @NotBlank @Size(min = 2, max = 120) String nome,
        @NotBlank @Email @Size(max = 180) String email,
        @NotBlank @Size(min = 8) String senha,
        @Pattern(regexp = "ADMIN|USER", message = "role deve ser ADMIN ou USER") String role) {
}
