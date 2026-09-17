package com.floricultura.api.web.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.read.ListAppender;
import com.floricultura.api.domain.UsuarioFactory;
import com.floricultura.api.repository.UsuarioRepository;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Nivel 3 da T-M7-10 (AD-SQ-176 / LGPD): a regua mede o <b>ARQUIVO DE LOG INTEIRO</b>, nao o trecho
 * que o nosso codigo escreve.
 *
 * <p><b>Por que esta classe existe (a camada cega que ela fecha).</b> O {@code LogIntegridadeLgpdTest}
 * pluga o appender no logger do {@code GlobalExceptionHandler}. Um appender preso a UM logger e
 * estruturalmente incapaz de ver o que OUTRO logger escreve no mesmo arquivo — e quem vazava o e-mail
 * era o {@code org.hibernate.orm.jdbc.error}, de dentro de uma dependencia, uma linha ANTES de a
 * excecao subir para o nosso handler. Aqui o ponto de observacao e o <b>logger ROOT</b>, por onde
 * passa tudo, e o texto aferido e o <b>renderizado pelo pattern de producao</b> (mesma palavra de
 * conversao de excecao que o Boot injeta), nao o {@code getFormattedMessage()} cru — senao um
 * stacktrace com a mensagem do driver passaria batido.
 *
 * <p><b>Pergunta dual (R4).</b> Tres pernas, e nenhuma sozinha aprova:
 *
 * <ul>
 *   <li><b>ancora</b> — o cenario, com o logger do Hibernate religado, PRODUZ o e-mail de verdade;
 *       sem isso os outros casos passariam por nunca ter havido vazamento;
 *   <li><b>guarda</b> — com a configuracao entregue, o ROOT nao contem o e-mail <b>e</b> continua
 *       contendo {@code constraint=uq_usuario_email} + {@code sqlState=23505}. "Nao vaza" sozinho
 *       passaria com {@code logging.level.root: OFF}, que e transformar o log em "deu erro";
 *   <li><b>catch-all</b> — o 500 ainda imprime classes, {@code Caused by:} e frames, so sem a
 *       mensagem do servidor. "Nao vaza" sozinho passaria com um stacktrace apagado.
 * </ul>
 */
@SpringBootTest
@Testcontainers
class LogIntegridadeHibernateTest {

    /** Dado ficticio (LGPD) que simula o dado pessoal real de {@code cliente}/{@code usuario}. */
    private static final String EMAIL_PESSOAL = "maria.silva.lgpd@exemplo.com.br";
    private static final String NOME_PESSOAL = "Maria Silva Lgpd";
    private static final String ROTA = "/api/v1/usuarios";

    /** O logger da dependencia que duplicava a mensagem crua do driver (SqlExceptionHelper). */
    private static final String LOGGER_DO_HIBERNATE = "org.hibernate.orm.jdbc.error";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-loghibernate-0123456789");
    }

    @Autowired
    private GlobalExceptionHandler handler;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private Logger raiz;
    private OutputStreamAppender<ILoggingEvent> appenderDaRaiz;
    private ByteArrayOutputStream logRenderizado;

    @BeforeEach
    void preparar() {
        jdbc.update("DELETE FROM usuario WHERE email = ?", EMAIL_PESSOAL);
        jdbc.update(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, 'USER', true, false)",
                NOME_PESSOAL, EMAIL_PESSOAL, "$2a$10$naoimporta");

        raiz = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        LoggerContext contexto = raiz.getLoggerContext();

        // A palavra de conversao de excecao vem do MESMO lugar que producao usa: a propriedade que o
        // Spring Boot exporta a partir de `logging.exception-conversion-word`. Se o app parar de
        // configura-la, este teste nao "conserta" o pattern por conta propria — ele quebra.
        String palavraDeExcecao = System.getProperty("LOG_EXCEPTION_CONVERSION_WORD");
        assertThat(palavraDeExcecao)
                .as("o app tem de selecionar o renderizador seguro de stacktrace")
                .isEqualTo("%exLgpd");

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(contexto);
        encoder.setPattern("%-5level %logger : %m%n" + palavraDeExcecao);
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();

        logRenderizado = new ByteArrayOutputStream();
        appenderDaRaiz = new OutputStreamAppender<>();
        appenderDaRaiz.setContext(contexto);
        appenderDaRaiz.setEncoder(encoder);
        appenderDaRaiz.setOutputStream(logRenderizado);
        appenderDaRaiz.start();
        raiz.addAppender(appenderDaRaiz);
    }

    @AfterEach
    void limpar() {
        raiz.detachAppender(appenderDaRaiz);
        appenderDaRaiz.stop();
        jdbc.update("DELETE FROM usuario WHERE email = ?", EMAIL_PESSOAL);
    }

    /** Provoca a violacao no banco de verdade e devolve a excecao que o Spring traduziu. */
    private DataIntegrityViolationException violacaoReal() {
        return assertThrows(DataIntegrityViolationException.class, () -> usuarioRepository
                .saveAndFlush(UsuarioFactory.novo(
                        NOME_PESSOAL, EMAIL_PESSOAL, "$2a$10$naoimporta", "USER")));
    }

    private String logDaRaiz() {
        return logRenderizado.toString(StandardCharsets.UTF_8);
    }

    private static MockHttpServletRequest requisicao() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", ROTA);
        request.setRequestURI(ROTA);
        return request;
    }

    @Test
    @DisplayName("ancora: religado o logger do Hibernate, o e-mail REAPARECE — o vazamento existia")
    void comOLoggerDoHibernateLigadoOEmailVazaDeVerdade() {
        Logger doHibernate = (Logger) LoggerFactory.getLogger(LOGGER_DO_HIBERNATE);

        // O que a configuracao entregue faz: cala esse logger. Se um dia a linha sair do
        // application.yml, este assert cai antes de qualquer outro e diz exatamente o que faltou.
        assertThat(doHibernate.getLevel())
                .as("application.yml tem de manter %s em OFF", LOGGER_DO_HIBERNATE)
                .isEqualTo(Level.OFF);

        // Captura isolada (additive=false): reproduz o mundo ANTES do conserto SEM reimprimir o dado
        // pessoal no console — a evidencia fica em memoria, nao no relatorio da suite.
        ListAppender<ILoggingEvent> captura = new ListAppender<>();
        captura.start();
        boolean aditivoOriginal = doHibernate.isAdditive();
        doHibernate.addAppender(captura);
        doHibernate.setAdditive(false);
        doHibernate.setLevel(Level.WARN);
        try {
            violacaoReal();
        } finally {
            doHibernate.setLevel(Level.OFF);
            doHibernate.setAdditive(aditivoOriginal);
            doHibernate.detachAppender(captura);
            captura.stop();
        }

        assertThat(captura.list)
                .as("o Hibernate loga a mensagem crua do servidor, com o dado que falhou")
                .anyMatch(evento -> evento.getFormattedMessage().contains(EMAIL_PESSOAL));
        // E nada disso chegou ao ROOT: a barreira e o nivel OFF, nao a sorte.
        assertThat(logDaRaiz()).doesNotContain(EMAIL_PESSOAL);
    }

    @Test
    @DisplayName("guarda: o log INTEIRO perde o e-mail e mantem constraint + SQLState")
    void logDaRaizNaoLevaDadoPessoalMasContinuaDiagnosticando() {
        DataIntegrityViolationException ex = violacaoReal();

        handler.handleConflict(ex, requisicao());

        String arquivo = logDaRaiz();
        // Perna 1 — nenhum logger do processo, nosso ou de dependencia, poe o dado no arquivo.
        assertThat(arquivo)
                .doesNotContain(EMAIL_PESSOAL)
                .doesNotContain("maria.silva.lgpd")
                .doesNotContain(NOME_PESSOAL)
                .doesNotContain("Key (email)")
                .doesNotContain("Detalhe")
                .doesNotContain("Detail");
        // Perna 2 — o diagnostico ficou: da para achar a rota e a regra violada no banco.
        assertThat(arquivo)
                .contains(ROTA)
                .contains("constraint=uq_usuario_email")
                .contains("sqlState=23505");
    }

    @Test
    @DisplayName("catch-all: o 500 mantem classes, Caused by e frames — perde so a mensagem do banco")
    void stacktraceDoCatchAllPreservaOOndeESoPerdeOQue() {
        RuntimeException erro = new RuntimeException("falha ao salvar usuario", violacaoReal());

        handler.handleUnexpected(erro, requisicao());

        String arquivo = logDaRaiz();
        // Perna 1 — o stacktrace nao carrega mais a mensagem do servidor.
        assertThat(arquivo)
                .doesNotContain(EMAIL_PESSOAL)
                .doesNotContain("Key (email)")
                .doesNotContain("Detalhe")
                .doesNotContain("duplicate key");
        // Perna 2 — o ONDE continua todo la; um stacktrace apagado reprovaria aqui.
        assertThat(arquivo)
                .contains("Erro nao tratado em " + ROTA)
                .contains("java.lang.RuntimeException")
                .contains("org.springframework.dao.DataIntegrityViolationException")
                .contains("org.postgresql.util.PSQLException")
                .contains("Caused by:")
                .contains("at com.floricultura.api.web.error.LogIntegridadeHibernateTest");
    }
}
