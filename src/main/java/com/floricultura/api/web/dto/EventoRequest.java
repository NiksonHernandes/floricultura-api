package com.floricultura.api.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Corpo de {@code POST}/{@code PUT /api/v1/eventos} (SPEC-M4 §3.2, CA-1/CA-2/CA-3) — criacao/edicao de
 * evento pelo ADMIN.
 *
 * <p>Regras de validacao (Bean Validation → {@code 400 VALIDATION_ERROR} com {@code details} pelo
 * {@code GlobalExceptionHandler} do M0): {@code nome} obrigatorio 1..150; {@code tipo} obrigatorio e ∈
 * {@code {COMEMORATIVA,FEIRA,BENEFICENTE,ENCOMENDA_CLIENTE}} (AD-SQ-31 — codigo ASCII, rotulos pt-BR sao
 * do front); {@code dataInicio} obrigatoria ({@code yyyy-MM-dd}); {@code dataFim} opcional (ausente ⇒
 * data unica); {@code repeteTodoAno} opcional (default {@code false}); {@code descricao} opcional. O
 * CHECK do enum/periodo vive tambem na V6 (defesa em profundidade); a regra cruzada {@code dataFim >=
 * dataInicio} e validada no servico (400 com {@code field=dataFim} — CA-2).
 *
 * @param nome          nome de exibicao (obrigatorio, 1..150)
 * @param tipo          codigo do tipo (obrigatorio, ∈ enum)
 * @param dataInicio    inicio do evento (obrigatorio)
 * @param dataFim       fim do periodo (opcional; ausente ⇒ data unica)
 * @param repeteTodoAno recorrencia anual (opcional; default {@code false})
 * @param descricao     descricao livre (opcional)
 */
public record EventoRequest(
        @NotBlank @Size(max = 150) String nome,
        @NotBlank @Pattern(regexp = "COMEMORATIVA|FEIRA|BENEFICENTE|ENCOMENDA_CLIENTE",
                message = "tipo deve ser um de: COMEMORATIVA, FEIRA, BENEFICENTE, ENCOMENDA_CLIENTE")
                String tipo,
        @NotNull LocalDate dataInicio,
        LocalDate dataFim,
        Boolean repeteTodoAno,
        String descricao) {
}
