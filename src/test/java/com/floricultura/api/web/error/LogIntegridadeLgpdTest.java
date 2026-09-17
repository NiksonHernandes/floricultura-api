package com.floricultura.api.web.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.floricultura.api.domain.UsuarioFactory;
import com.floricultura.api.repository.UsuarioRepository;
import com.floricultura.api.web.response.ApiResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Nivel 2 da T-M7-10 (AD-SQ-176 / LGPD): a excecao aqui e <b>de verdade</b> — vem de um INSERT
 * duplicado num PostgreSQL 16 real (Testcontainers) batendo na constraint {@code uq_usuario_email}
 * da V1, traduzida pelo Spring. O handler e o <b>bean real</b> do contexto e o appender e plugado no
 * <b>mesmo logger</b> que o {@code GlobalExceptionHandler} usa em producao
 * ({@code LoggerFactory.getLogger(GlobalExceptionHandler.class)}), nao num logger de teste.
 *
 * <p><b>Honestidade sobre o alcance (nao e ponta a ponta por HTTP).</b> O handler e invocado
 * diretamente, sem passar pelo {@code DispatcherServlet}. Isso nao e preguica: todo caminho que
 * chega a este handler com dado pessoal e uma <b>rede de corrida</b> — {@code UsuarioService.criar}
 * pre-checa {@code findByEmail} e devolve 409 amigavel antes do banco, e {@code MovimentacaoService}
 * valida a contraparte antes do INSERT. Por HTTP a violacao so ocorre com duas requisicoes cruzando
 * entre a checagem e o commit, que nao da para forcar de forma deterministica. Preferimos exercitar
 * o par real (excecao real + handler real + logger real) a montar uma corrida instavel.
 *
 * <p><b>Pergunta dual (R4).</b> As tres asserticoes andam juntas de proposito: (1) a mensagem crua
 * REALMENTE traz o e-mail — sem isso o teste passaria porque nunca houve vazamento; (2) o log nao a
 * contem; (3) o log ainda diz rota/constraint/SQLState. Assim {@code log.warn("erro")} pelado
 * reprova, tanto quanto o codigo original reprova.
 */
@SpringBootTest
@Testcontainers
class LogIntegridadeLgpdTest {

    /** Dado ficticio (LGPD) que simula o dado pessoal real de {@code cliente}/{@code usuario}. */
    private static final String EMAIL_PESSOAL = "maria.silva.lgpd@exemplo.com.br";
    private static final String NOME_PESSOAL = "Maria Silva Lgpd";
    private static final String ROTA = "/api/v1/usuarios";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-logintegridade-0123456789");
    }

    @Autowired
    private GlobalExceptionHandler handler;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private Logger loggerDoHandler;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void preparar() {
        jdbc.update("DELETE FROM usuario WHERE email = ?", EMAIL_PESSOAL);
        jdbc.update(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, 'USER', true, false)",
                NOME_PESSOAL, EMAIL_PESSOAL, "$2a$10$naoimporta");

        loggerDoHandler = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        appender = new ListAppender<>();
        appender.start();
        loggerDoHandler.addAppender(appender);
        loggerDoHandler.setLevel(Level.WARN);
    }

    @AfterEach
    void limpar() {
        loggerDoHandler.detachAppender(appender);
        appender.stop();
        jdbc.update("DELETE FROM usuario WHERE email = ?", EMAIL_PESSOAL);
    }

    /** Provoca a violacao no banco de verdade e devolve a excecao que o Spring traduziu. */
    private DataIntegrityViolationException violacaoReal() {
        return assertThrows(DataIntegrityViolationException.class, () -> usuarioRepository
                .saveAndFlush(UsuarioFactory.novo(
                        NOME_PESSOAL, EMAIL_PESSOAL, "$2a$10$naoimporta", "USER")));
    }

    @Test
    @DisplayName("o PostgreSQL real de fato poe o e-mail na mensagem — o vazamento nao e hipotetico")
    void mensagemCruaDoDriverCarregaDadoPessoal() {
        DataIntegrityViolationException ex = violacaoReal();

        assertThatThrownBy(() -> {
            throw ex;
        }).hasStackTraceContaining("uq_usuario_email");
        assertThat(ex.getMostSpecificCause().getMessage()).contains(EMAIL_PESSOAL);
    }

    @Test
    @DisplayName("o log do handler perde o e-mail e mantem rota + constraint + SQLState")
    void logDoHandlerNaoLevaDadoPessoalMasDiagnostica() {
        DataIntegrityViolationException ex = violacaoReal();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", ROTA);
        request.setRequestURI(ROTA);

        handler.handleConflict(ex, request);

        List<ILoggingEvent> eventos = appender.list;
        assertThat(eventos).hasSize(1);
        String linha = eventos.get(0).getFormattedMessage();

        // Perna 1 — o dado pessoal saiu.
        assertThat(linha)
                .doesNotContain(EMAIL_PESSOAL)
                .doesNotContain("maria.silva.lgpd")
                // O driver LOCALIZA o rotulo: no ambiente pt-BR a linha vem como "Detalhe:", nao
                // "Detail:" (visto na mutacao M-A). Barramos os dois — filtrar por rotulo seria
                // fragil, e por isso o redator nao repassa trecho NENHUM da mensagem.
                .doesNotContain("Detail")
                .doesNotContain("Detalhe")
                .doesNotContain("Key (email)")
                .doesNotContain("insert into usuario");
        // Perna 2 — o diagnostico ficou: da para achar a rota e a regra violada no banco.
        assertThat(linha)
                .contains(ROTA)
                .contains("constraint=uq_usuario_email")
                .contains("sqlState=23505");
        assertThat(eventos.get(0).getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    @DisplayName("a resposta HTTP nao muda: 409 CONFLICT no envelope, como antes da redacao")
    void respostaHttpSegueIntacta() {
        DataIntegrityViolationException ex = violacaoReal();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", ROTA);
        request.setRequestURI(ROTA);

        ResponseEntity<ApiResponse<Object>> resposta = handler.handleConflict(ex, request);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resposta.getBody()).isNotNull();
        assertThat(resposta.getBody().success()).isFalse();
        assertThat(resposta.getBody().error().code()).isEqualTo(ErrorCode.CONFLICT.name());
        assertThat(resposta.getBody().path()).isEqualTo(ROTA);
        // E o corpo tambem nao pode virar canal de vazamento (§9 do M0).
        assertThat(resposta.getBody().error().message()).doesNotContain(EMAIL_PESSOAL);
        assertThat(resposta.getBody().error().details()).isEmpty();
    }
}
