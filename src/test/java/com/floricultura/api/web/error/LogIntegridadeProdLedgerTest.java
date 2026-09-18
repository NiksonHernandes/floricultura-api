package com.floricultura.api.web.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import com.floricultura.api.domain.MovimentacaoEstoque;
import com.floricultura.api.domain.MovimentacaoFactory;
import com.floricultura.api.domain.ValoresMovimentacao;
import com.floricultura.api.repository.MovimentacaoRepository;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * As duas frestas que o QA do M7 declarou <b>sem carimbar como verificadas</b> — e ele fez certo em
 * nao carimbar.
 *
 * <ol>
 *   <li><b>Nenhum teste ativava o profile {@code prod}.</b> A sobrevivencia da trava de LGPD sob
 *       {@code prod} repousava na <b>fusao de chaves do Binder</b> ({@code application-prod.yml} traz
 *       {@code logging.level.root: INFO}, o base traz
 *       {@code logging.level.org.hibernate.orm.jdbc.error: OFF}) — raciocinio correto, mas
 *       <b>nao executado</b>. Aqui o contexto sobe <b>com o profile prod</b> e as duas chaves sao
 *       lidas do {@code LoggerContext} de verdade.</li>
 *   <li><b>A regua do ROOT so exercitava {@code uq_usuario_email}.</b> Uma violacao no <b>ledger</b> e
 *       materialmente diferente desde o M7: a linha que o PostgreSQL imprime no {@code Failing row
 *       contains} agora carrega <b>nome de cliente/fornecedor</b> (V10) <b>e dinheiro</b> (V13). Passa
 *       pelos mesmos 3 handlers, mas com carga pessoal maior — e e isso que este arquivo fecha.</li>
 * </ol>
 *
 * <p><b>Pergunta dual:</b> um caso que so afirmasse "o log nao tem o nome" ficaria verde tambem com o
 * logging inteiro desligado. Por isso as assercoes andam <b>em par</b>: o rendered do ROOT <b>perde</b>
 * o dado pessoal <b>e continua</b> trazendo {@code constraint=} + {@code sqlState=} — diagnostico vivo,
 * dado pessoal fora.
 */
@SpringBootTest
@ActiveProfiles("prod")
@Testcontainers
class LogIntegridadeProdLedgerTest {

    private static final String CLIENTE_PESSOAL = "Maria Silva Cliente";

    private static final String DINHEIRO_DA_LINHA = "1234.56";

    private static final String ROTA = "/api/v1/produtos/1/movimentacoes";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-prod-ledger-0123456");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GlobalExceptionHandler handler;

    @Autowired
    private MovimentacaoRepository movimentacaoRepository;

    @Autowired
    private Environment environment;

    private Logger raiz;
    private OutputStreamAppender<ILoggingEvent> appenderDaRaiz;
    private ByteArrayOutputStream logRenderizado;
    private Long produto;
    private Long cliente;

    @BeforeEach
    void preparar() {
        jdbc.update("TRUNCATE TABLE movimentacao_estoque");
        jdbc.update("DELETE FROM produto");
        jdbc.update("DELETE FROM cliente");
        produto = jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida, estoque_minimo, estoque_atual) "
                        + "VALUES ('Rosa Vermelha', 'un', 1, 0) RETURNING id", Long.class);
        cliente = jdbc.queryForObject(
                "INSERT INTO cliente (nome) VALUES (?) RETURNING id", Long.class, CLIENTE_PESSOAL);

        raiz = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        LoggerContext contexto = raiz.getLoggerContext();
        String palavraDeExcecao = System.getProperty("LOG_EXCEPTION_CONVERSION_WORD");
        assertThat(palavraDeExcecao)
                .as("o app tem de selecionar o renderizador seguro de stacktrace tambem em prod")
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
    }

    /**
     * Fresta 1: <b>sob {@code prod}</b>, as duas chaves de {@code logging.level} convivem. O
     * {@code application-prod.yml} define {@code root: INFO} e <b>nao</b> apaga a trava do base — a
     * fusao do Binder deixa de ser raciocinio e vira execucao.
     */
    @Test
    @DisplayName("com o profile prod ATIVO, a trava do Hibernate sobrevive ao root: INFO")
    void sobProdAsDuasChavesDeLoggingConvivem() {
        assertThat(environment.getActiveProfiles()).contains("prod");

        Logger hibernateErro = (Logger) LoggerFactory.getLogger("org.hibernate.orm.jdbc.error");
        assertThat(hibernateErro.getLevel())
                .as("a trava da AD-SQ-176 tem de valer em prod, que e onde o vazamento doeria")
                .isEqualTo(Level.OFF);
        assertThat(raiz.getLevel())
                .as("e o root de prod continua INFO — uma chave nao pode ter comido a outra")
                .isEqualTo(Level.INFO);
    }

    /**
     * Fresta 2: violacao no <b>ledger</b>, cuja linha carrega nome de cliente <b>e</b> dinheiro. Mesma
     * regua do ROOT, carga pessoal maior.
     */
    @Test
    @DisplayName("violacao no LEDGER: o ROOT perde nome de cliente e valor, e mantem o diagnostico")
    void violacaoNoLedgerNaoVazaNomeNemDinheiroNoRoot() {
        DataIntegrityViolationException ex = violacaoNoLedger();

        // O vazamento nao e hipotetico: o driver POE o nome e o dinheiro na mensagem crua.
        assertThat(ex.getMostSpecificCause().getMessage())
                .contains(CLIENTE_PESSOAL)
                .contains(DINHEIRO_DA_LINHA);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", ROTA);
        request.setRequestURI(ROTA);
        handler.handleConflict(ex, request);

        String rendered = logRenderizado.toString(StandardCharsets.UTF_8);
        assertThat(rendered)
                .doesNotContain(CLIENTE_PESSOAL)
                .doesNotContain(DINHEIRO_DA_LINHA)
                .doesNotContain("Failing row contains");
        assertThat(rendered)
                .as("perder o dado pessoal nao pode custar o diagnostico — senao bastaria desligar tudo")
                .contains("constraint=")
                .contains("sqlState=");
    }

    /**
     * ENTRADA com cliente viola {@code ck_mov_cliente_tipo} (V10) — e a linha recusada leva o nome do
     * cliente e o {@code total_final} para dentro da mensagem do PostgreSQL.
     *
     * <p><b>Pelo repositorio JPA, e nao por {@code JdbcTemplate}, de proposito:</b> e o caminho real de
     * escrita do ledger, e e o unico que passa pelo {@code SqlExceptionHelper} do Hibernate — a trava
     * {@code org.hibernate.orm.jdbc.error: OFF} so e exercitada assim. Com {@code JdbcTemplate} o caso
     * ficaria verde sem nunca tocar metade da defesa que ele diz cobrir.
     */
    private DataIntegrityViolationException violacaoNoLedger() {
        MovimentacaoEstoque linha = MovimentacaoFactory.nova(
                produto, "Rosa Vermelha", "ENTRADA", BigDecimal.ONE, BigDecimal.ONE,
                "ajuste de inventario", null, "Ana Admin", null, null, cliente, CLIENTE_PESSOAL,
                new ValoresMovimentacao(
                        new BigDecimal(DINHEIRO_DA_LINHA), null, null,
                        new BigDecimal(DINHEIRO_DA_LINHA), new BigDecimal(DINHEIRO_DA_LINHA), null));
        return assertThrows(DataIntegrityViolationException.class,
                () -> movimentacaoRepository.saveAndFlush(linha));
    }
}
