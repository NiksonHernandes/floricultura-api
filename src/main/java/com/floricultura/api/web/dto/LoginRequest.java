package com.floricultura.api.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Corpo de {@code POST /api/v1/auth/login} (SPEC-M1 §3.2). Login por e-mail (AD-SQ-14).
 *
 * <p>Campos ausentes/mal-formados → {@code 400 VALIDATION_ERROR} pelo {@code GlobalExceptionHandler}
 * do M0 (Bean Validation). A {@code senha} <b>nunca</b> e logada (§9): este record so trafega em
 * transito e vira {@code PasswordEncoder.matches}.
 *
 * @param email e-mail do usuario (obrigatorio, formato valido)
 * @param senha senha em texto (obrigatoria; comparada por BCrypt, nunca persistida/logada)
 */
public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String senha) {
}
