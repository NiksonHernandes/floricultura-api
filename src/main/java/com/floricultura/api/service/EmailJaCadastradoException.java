package com.floricultura.api.service;

/**
 * E-mail ja em uso na criacao de usuario (SPEC-M1 §3.2/§4, CA-7). O servico checa {@code findByEmail}
 * <b>antes</b> do insert para devolver a mensagem amigavel "E-mail ja cadastrado." → {@code 409
 * CONFLICT} (traduzida por handler local no {@code UsuarioController}). A constraint {@code
 * uq_usuario_email} (V1) e a rede de seguranca: uma corrida que escape da checagem vira
 * {@code DataIntegrityViolationException} → {@code 409} pelo {@code GlobalExceptionHandler} do M0.
 */
public class EmailJaCadastradoException extends RuntimeException {

    public EmailJaCadastradoException() {
        super("E-mail ja cadastrado.");
    }
}
