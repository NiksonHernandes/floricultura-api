package com.floricultura.api.web.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Corpo de {@code PATCH /api/v1/usuarios/{id}/status} (SPEC-M1 §3.2, CA-9) — ativar/desativar um
 * usuario pelo ADMIN. Desativar e soft-state (FC-08/AD-SQ-18): {@code ativo=false} nao apaga a linha.
 *
 * <p>{@code ativo} e obrigatorio ({@link NotNull} → {@code 400 VALIDATION_ERROR} com {@code details}
 * pelo {@code GlobalExceptionHandler} do M0 se ausente/nulo). Tipo {@code Boolean} (nao primitivo)
 * para que a ausencia do campo seja capturada pela validacao em vez de assumir {@code false} silencioso.
 *
 * @param ativo novo estado da conta (obrigatorio): {@code true} ativa, {@code false} desativa
 */
public record AlterarStatusRequest(@NotNull Boolean ativo) {
}
