package com.floricultura.api.web.response;

/**
 * Item de erro de validacao por campo (SPEC-M0 §3.1).
 *
 * @param field   nome do campo invalido
 * @param message motivo da invalidez
 */
public record FieldErrorItem(String field, String message) {
}
