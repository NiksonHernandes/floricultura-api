package com.floricultura.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Nivel 1 do renderizador de stacktrace (T-M7-10 / AD-SQ-176 / LGPD): o {@code %exLgpd} sobre cadeias
 * de excecao montadas a mao, sem Spring e sem banco.
 *
 * <p><b>Pergunta dual (R4) — "que implementacao ERRADA este teste deixa passar?".</b> Duas
 * implementacoes erradas rondam este conversor, e cada uma tem um caso dedicado:
 *
 * <ul>
 *   <li><b>"apaga tudo"</b> — devolver string vazia, ou so o nome da classe do topo, tiraria o
 *       vazamento e tambem a investigacao. O caso {@code cadeiaComBanco…} cobra, na mesma asserticao,
 *       que sumiu o dado pessoal <b>e</b> que ficaram as classes da cadeia, o {@code Caused by:} e os
 *       frames do nosso pacote;
 *   <li><b>"apaga demais"</b> — redigir a mensagem de qualquer excecao transformaria todo log de erro
 *       do app em "deu erro". O caso {@code cadeiaSemBanco…} cobra igualdade <b>byte a byte</b> com o
 *       renderizador padrao do Spring Boot: fora do caminho de banco, este conversor e um no-op.
 * </ul>
 */
class ThrowableSemMensagemDeBancoConverterTest {

    /** Dado ficticio (LGPD) no formato exato em que o PostgreSQL o anexa a mensagem. */
    private static final String EMAIL_PESSOAL = "maria.silva@exemplo.com.br";

    private static final String MSG_DO_SERVIDOR = """
            ERROR: duplicate key value violates unique constraint "uq_usuario_email"
              Detalhe: Key (email)=(maria.silva@exemplo.com.br) already exists.""";

    private final LoggerContext contexto = new LoggerContext();

    private String renderizar(Throwable erro) {
        return renderizar(new ThrowableSemMensagemDeBancoConverter(), erro);
    }

    private String renderizar(ExtendedWhitespaceThrowableProxyConverter conversor, Throwable erro) {
        conversor.setContext(contexto);
        conversor.start();
        LoggingEvent evento = new LoggingEvent(
                "com.floricultura.api.web.error.GlobalExceptionHandler",
                contexto.getLogger("com.floricultura.api.web.error.GlobalExceptionHandler"),
                Level.ERROR,
                "Erro nao tratado em /api/v1/usuarios",
                erro,
                null);
        return conversor.convert(evento);
    }

    /** Cadeia igual a que chega ao catch-all: wrapper do Spring por cima da excecao do driver. */
    private static RuntimeException cadeiaDeBanco() {
        SQLException driver = new SQLException(MSG_DO_SERVIDOR, "23505");
        DataIntegrityViolationException spring = new DataIntegrityViolationException(
                "could not execute statement [" + MSG_DO_SERVIDOR + "]", driver);
        return new RuntimeException("falha ao salvar usuario", spring);
    }

    @Test
    @DisplayName("cadeia com causa de banco: sai a mensagem do servidor, ficam classes e frames")
    void cadeiaComBancoPerdeSoAMensagemENaoODiagnostico() {
        RuntimeException erro = cadeiaDeBanco();

        // Guarda de sanidade: sem isto o caso passaria num mundo onde nunca houve vazamento.
        assertThat(renderizar(new ExtendedWhitespaceThrowableProxyConverter(), erro))
                .contains(EMAIL_PESSOAL)
                .contains("Detalhe:");

        String saida = renderizar(erro);

        // Perna 1 — o dado pessoal saiu, e saiu de TODOS os elos (o wrapper do Spring embute a
        // mensagem do driver dentro da propria mensagem dele, por isso a cadeia inteira e redigida).
        assertThat(saida)
                .doesNotContain(EMAIL_PESSOAL)
                .doesNotContain("Detalhe")
                .doesNotContain("Key (email)")
                .doesNotContain("duplicate key")
                .doesNotContain("could not execute statement");
        // Perna 2 — o ONDE ficou inteiro: as tres classes da cadeia, o encadeamento e os frames.
        assertThat(saida)
                .contains("java.lang.RuntimeException")
                .contains("org.springframework.dao.DataIntegrityViolationException")
                .contains("java.sql.SQLException")
                .contains("Caused by:")
                .contains("at com.floricultura.api.config."
                        + "ThrowableSemMensagemDeBancoConverterTest.cadeiaDeBanco");
        // E o corte e ANUNCIADO: redigir em silencio e pior do que vazar.
        assertThat(saida).contains(ThrowableSemMensagemDeBancoConverter.OMITIDA);
    }

    @Test
    @DisplayName("cadeia sem banco: saida IDENTICA a do renderizador padrao do Spring Boot")
    void cadeiaSemBancoNaoEToquePelaRedacao() {
        IllegalStateException erro = new IllegalStateException(
                "estoque insuficiente para o produto 42",
                new IllegalArgumentException("quantidade invalida: -3"));

        String padrao = renderizar(new ExtendedWhitespaceThrowableProxyConverter(), erro);
        String comRedacao = renderizar(erro);

        assertThat(comRedacao).isEqualTo(padrao);
        // Explicito, para o dia em que alguem "simplificar" a regra para "redige tudo":
        assertThat(comRedacao)
                .contains("estoque insuficiente para o produto 42")
                .contains("quantidade invalida: -3");
    }

    @Test
    @DisplayName("excecao SUPRIMIDA de banco tambem entra na regra — nao e porta dos fundos")
    void suprimidaDeBancoNaoEscapa() {
        IllegalStateException erro = new IllegalStateException("falha ao fechar a transacao");
        erro.addSuppressed(new SQLException(MSG_DO_SERVIDOR, "23505"));

        assertThat(renderizar(new ExtendedWhitespaceThrowableProxyConverter(), erro))
                .contains(EMAIL_PESSOAL);

        String saida = renderizar(erro);

        assertThat(saida).doesNotContain(EMAIL_PESSOAL).doesNotContain("Detalhe");
        assertThat(saida).contains("java.lang.IllegalStateException").contains("java.sql.SQLException");
    }

    @Test
    @DisplayName("evento sem throwable nao vira texto — o conversor nao inventa linha de log")
    void eventoSemThrowableSaiVazio() {
        assertThat(renderizar((Throwable) null)).isEmpty();
    }
}
