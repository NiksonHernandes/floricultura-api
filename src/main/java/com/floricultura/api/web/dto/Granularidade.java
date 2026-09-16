package com.floricultura.api.web.dto;

/**
 * Corte temporal dos baldes do relatorio (SPEC-M7 §3.7): {@code SEMANA} ou {@code MES}.
 *
 * <p>{@code SEMANA} e <b>segunda a domingo</b> (ISO-8601, §3.7-c) — o mesmo corte do
 * {@code date_trunc('week', ...)} do PostgreSQL, aqui derivado em Java pelo {@code SerieDePeriodos}.
 * O default do contrato e {@code MES} (parametro ausente ou em branco).
 *
 * <p>Conjunto <b>fechado</b>: valor fora dele e 400 {@code field=granularidade} (mesma gramatica de
 * mensagem do {@code tipo} em {@link FiltroMovimentacao}).
 */
public enum Granularidade {
    SEMANA,
    MES
}
