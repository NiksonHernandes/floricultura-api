package com.floricultura.api.service;

/**
 * Falha de autenticacao <b>generica</b> (SPEC-M1 §3.2/§4, CA-2/CA-3). Lancada de forma <b>identica</b>
 * para e-mail inexistente, senha incorreta <b>e</b> conta inativa — <b>sem</b> carga distintiva — para
 * nao permitir enumeracao de contas (anti-enumeracao). O {@code AuthController} a mapeia para
 * {@code 401 UNAUTHORIZED} com mensagem fixa "Credenciais invalidas." e {@code details:[]}, produzindo
 * um corpo byte-a-byte igual nos tres casos.
 */
public class CredenciaisInvalidasException extends RuntimeException {

    public CredenciaisInvalidasException() {
        super("Credenciais invalidas.");
    }
}
