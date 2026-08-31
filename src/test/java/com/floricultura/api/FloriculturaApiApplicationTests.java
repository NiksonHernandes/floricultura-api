package com.floricultura.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test do M0 (AD-SQ-11): prova que o contexto Spring sobe.
 *
 * <p>Armadilha §12: as deps Data JPA / PostgreSQL / Flyway fariam o contexto exigir um banco
 * real na subida. Como em T-M0-1 ainda nao ha entidades, repositorios nem migracao (isso e
 * T-M0-2/T-M0-3), excluimos aqui as auto-configuracoes de datasource/JPA/Flyway para que o
 * {@code contextLoads} passe SEM banco e SEM Docker. A subida real contra PostgreSQL e validada
 * em T-M0-3 com a migracao baseline. O starter-security carrega o contexto normalmente (nao
 * exige datasource); o SecurityFilterChain permissivo entra em T-M0-2.
 *
 * <p>Nota Boot 4 (AD-SQ-13): as classes de auto-config sairam do jar monolitico para modulos
 * proprios com novos pacotes (ex.: {@code org.springframework.boot.jdbc.autoconfigure.*},
 * {@code ...hibernate.autoconfigure.*}, {@code ...flyway.autoconfigure.*}) — FQCNs abaixo ja
 * atualizados para o Spring Boot 4.1.1.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
class FloriculturaApiApplicationTests {

    @Test
    void contextLoads() {
        // O proprio boot do contexto e a asercao: falha se algum bean de config nao subir.
    }
}
