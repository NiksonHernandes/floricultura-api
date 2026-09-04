package com.floricultura.api.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Corpo de {@code POST}/{@code PUT /api/v1/fornecedores} (SPEC-M5 §3.3, CA-4/CA-5) — irmao de
 * {@link ClienteRequest}: criacao/edicao de fornecedor pelo ADMIN.
 *
 * <p>Regras de validacao (Bean Validation → {@code 400 VALIDATION_ERROR} com {@code details} por campo pelo
 * {@code GlobalExceptionHandler} do M0): {@code nome} obrigatorio 1..150; {@code telefone} opcional, string
 * livre ≤40 (sem mascara — PA#3/§4.1); {@code email} opcional mas, <b>quando presente</b>, formato valido e
 * ≤180; {@code observacoes} opcional ≤500 (minimizacao LGPD). {@code produtoIds} opcional (replace-set do
 * vinculo N:N — §4.2): <b>ausente/{@code null}</b> ⇒ nao altera os vinculos (update parcial); <b>presente
 * (inclusive {@code []})</b> ⇒ substitui o conjunto. Cada id deve existir em {@code produto}, senao
 * {@code 400 field=produtoIds} <b>antes</b> de qualquer escrita (CA-8) — validado no {@code
 * FornecedorService}.
 *
 * @param nome        nome de exibicao (obrigatorio, 1..150)
 * @param telefone    telefone livre (opcional, ≤40)
 * @param email       e-mail (opcional; formato valido quando presente, ≤180)
 * @param observacoes observacoes livres (opcional, ≤500)
 * @param produtoIds  ids dos produtos a vincular (opcional; replace-set — §4.2)
 */
public record FornecedorRequest(
        @NotBlank @Size(max = 150) String nome,
        @Size(max = 40) String telefone,
        @Email @Size(max = 180) String email,
        @Size(max = 500) String observacoes,
        List<Long> produtoIds) {
}
