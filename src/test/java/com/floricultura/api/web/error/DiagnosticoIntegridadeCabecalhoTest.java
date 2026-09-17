package com.floricultura.api.web.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Cerca do P2-01 da review da T-M7-10 (AD-SQ-176 / LGPD): o nome da constraint so pode ser procurado
 * no <b>cabecalho</b> da mensagem do servidor, nunca na mensagem inteira.
 *
 * <p><b>O buraco que estes casos fecham.</b> A cerca de identificador ({@code [A-Za-z0-9_$]{1,63}})
 * e rigorosa, mas ela so julga o que o {@code find()} ja escolheu — e o {@code find()} varria a
 * mensagem INTEIRA, {@code Detalhe:} incluso. Numa violacao <b>NOT NULL</b> o PostgreSQL nao cita
 * constraint entre aspas no cabecalho, entao o primeiro casamento de {@code constraint "…"} so pode
 * vir da linha de dados. Um valor com forma de identificador ali dentro passava pela cerca e ia
 * inteiro para o log.
 *
 * <p><b>Pergunta dual (R4).</b> Os dois primeiros casos cobram que o ataque vira {@code ?}; o
 * terceiro cobra o <b>lado oposto</b> — que o conserto e estritamente monotonico, isto e, que a
 * mensagem legitima continua entregando o nome da constraint. Um {@code return DESCONHECIDO} pelado
 * derruba o terceiro; a busca na mensagem inteira derruba os dois primeiros.
 *
 * <p>Arquivo separado do {@code DiagnosticoIntegridadeTest} de proposito: aquele ja e rastreado e a
 * liberacao vigente cobre apenas a linha de javadoc dele. Isto aqui e <b>adicao pura</b>.
 */
class DiagnosticoIntegridadeCabecalhoTest {

    private static DataIntegrityViolationException doPostgres(String mensagem, String sqlState) {
        return new DataIntegrityViolationException(
                "could not execute statement", new SQLException(mensagem, sqlState));
    }

    @Test
    @DisplayName("NOT NULL com valor id-shaped no Detalhe: o dado NAO vira o nome da constraint")
    void dadoComFormaDeIdentificadorNoDetalheNaoEscapa() {
        // Cabecalho de NOT NULL nao tem constraint entre aspas; a unica ocorrencia do literal esta
        // na linha de dados, forjada por quem gravou o valor.
        DataIntegrityViolationException ex = doPostgres(
                """
                ERROR: null value in column "nome" of relation "cliente" violates not-null constraint
                  Detalhe: Failing row contains (12, null, constraint "CPF12345678900", 11 98888-7777).""",
                "23502");

        // Guarda de sanidade: o payload de ataque esta MESMO na mensagem que o redator recebe.
        assertThat(ex.getMostSpecificCause().getMessage()).contains("CPF12345678900");

        String diagnostico = DiagnosticoIntegridade.de(ex);

        assertThat(diagnostico)
                .doesNotContain("CPF12345678900")
                .contains("constraint=?")
                .contains("sqlState=23502");
    }

    @Test
    @DisplayName("NOT NULL com nome de um token so no Detalhe: tambem vira '?'")
    void nomeDeTokenUnicoNoDetalheNaoEscapa() {
        DataIntegrityViolationException ex = doPostgres(
                """
                ERROR: null value in column "email" of relation "cliente" violates not-null constraint
                  Detalhe: Failing row contains (12, constraint "MARIA", null, 11 98888-7777).""",
                "23502");

        String diagnostico = DiagnosticoIntegridade.de(ex);

        assertThat(diagnostico).doesNotContain("MARIA").contains("constraint=?");
    }

    @Test
    @DisplayName("controle de monotonia: a constraint legitima do cabecalho continua saindo")
    void constraintLegitimaDoCabecalhoContinuaSaindo() {
        // Mesma mensagem multi-linha de uma violacao de unicidade real: o cabecalho nomeia, o
        // Detalhe contamina. O corte na 1a linha nao pode custar este diagnostico.
        DataIntegrityViolationException ex = doPostgres(
                """
                ERROR: duplicate key value violates unique constraint "uq_cliente_email"
                  Detalhe: Key (email)=(maria.silva@exemplo.com.br) already exists.""",
                "23505");

        String diagnostico = DiagnosticoIntegridade.de(ex);

        assertThat(diagnostico)
                .contains("constraint=uq_cliente_email")
                .contains("sqlState=23505")
                .doesNotContain("maria.silva@exemplo.com.br");
    }

    @Test
    @DisplayName("mensagem de uma linha so: comportamento identico ao de antes do corte")
    void mensagemSemQuebraDeLinhaNaoRegride() {
        DataIntegrityViolationException ex =
                doPostgres("ERROR: violates unique constraint \"uk_cor_nome\"", "23505");

        assertThat(DiagnosticoIntegridade.de(ex)).contains("constraint=uk_cor_nome");
    }
}
