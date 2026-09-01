package com.floricultura.api.service;

/**
 * Senha atual incorreta na troca da propria senha (SPEC-M1 §3.2, CA-10). O {@code AuthController} a
 * mapeia para {@code 400 VALIDATION_ERROR} com {@code details:[{field:"senhaAtual",
 * message:"senha atual incorreta"}]} — <b>sem deslogar</b> (nao mexe no {@code SecurityContext}).
 */
public class SenhaAtualIncorretaException extends RuntimeException {

    public SenhaAtualIncorretaException() {
        super("senha atual incorreta");
    }
}
