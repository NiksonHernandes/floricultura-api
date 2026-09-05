package com.floricultura.api.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corpo de {@code POST}/{@code PUT /api/v1/clientes} (SPEC-M5 §3.3, CA-4/CA-5) — criacao/edicao de cliente
 * pelo ADMIN.
 *
 * <p>Regras de validacao (Bean Validation → {@code 400 VALIDATION_ERROR} com {@code details} por campo pelo
 * {@code GlobalExceptionHandler} do M0): {@code nome} obrigatorio 1..150; {@code telefone} opcional, string
 * livre ≤40 (sem mascara — PA#3/§4.1); {@code email} opcional mas, <b>quando presente</b>, formato valido e
 * ≤180; {@code observacoes} opcional ≤500 (minimizacao LGPD).
 *
 * <p><b>Revisao 2026-09-04 (AD-SQ-65):</b> o {@code produtoIds} de <b>escrita</b> foi removido — o vinculo
 * cliente↔produto deixou de ser junção N:N editavel e passou a ser derivado da movimentacao. Nao ha mais
 * escrita de vinculo pelo cadastro.
 *
 * @param nome        nome de exibicao (obrigatorio, 1..150)
 * @param telefone    telefone livre (opcional, ≤40)
 * @param email       e-mail (opcional; formato valido quando presente, ≤180)
 * @param observacoes observacoes livres (opcional, ≤500)
 */
public record ClienteRequest(
        @NotBlank @Size(max = 150) String nome,
        @Size(max = 40) String telefone,
        @Email @Size(max = 180) String email,
        @Size(max = 500) String observacoes) {
}
