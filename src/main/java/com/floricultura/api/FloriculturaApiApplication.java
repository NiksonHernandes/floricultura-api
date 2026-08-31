package com.floricultura.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Ponto de entrada da API da Floricultura.
 *
 * <p>M0 (fundacao): apenas o esqueleto. Contrato REST, security scaffold e migracao
 * Flyway entram em T-M0-2 / T-M0-3. Ver {@code docs/ARQUITETURA.md} e SPEC-M0.
 */
@SpringBootApplication
public class FloriculturaApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(FloriculturaApiApplication.class, args);
    }
}
