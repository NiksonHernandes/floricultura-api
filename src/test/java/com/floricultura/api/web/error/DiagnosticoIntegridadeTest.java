package com.floricultura.api.web.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Nivel 1 da T-M7-10 (AD-SQ-176 / LGPD): o redator {@link DiagnosticoIntegridade} sobre mensagens no
 * formato REAL do PostgreSQL. As strings abaixo foram escritas no mesmo formato que o driver produz
 * ({@code ERROR: … constraint "…"} + linha {@code Detail:}) com dados ficticios.
 *
 * <p><b>Pergunta dual (R4) — "que implementacao ERRADA este teste deixa passar?".</b> Um teste que so
 * afirmasse "o log nao contem o e-mail" passaria com {@code return ""} — trocar vazamento por log
 * cego, que e outra entrega ruim. Por isso <b>todo</b> caso aqui cobra as duas pernas na mesma
 * asserticao: o dado pessoal <b>saiu</b> E o metadado de diagnostico (constraint/SQLState/classe)
 * <b>ficou</b>. Um {@code return ""} derruba a segunda perna; o codigo original derruba a primeira.
 */
class DiagnosticoIntegridadeTest {

    /** Unicidade: o PostgreSQL poe o VALOR duplicado no {@code Detail:} — aqui, um e-mail pessoal. */
    private static final String MSG_UNIQUE = """
            ERROR: duplicate key value violates unique constraint "uq_usuario_email"
              Detail: Key (email)=(maria.silva@exemplo.com.br) already exists.""";

    /** CHECK: o PostgreSQL poe a LINHA INTEIRA no {@code Detail:} — nome, e-mail e telefone. */
    private static final String MSG_CHECK = """
            ERROR: new row for relation "movimentacao_estoque" violates check constraint \
            "ck_mov_cliente_tipo"
              Detail: Failing row contains (12, 3, ENTRADA, 5, Maria Silva, \
            maria.silva@exemplo.com.br, 11 98888-7777, 2026-09-16).""";

    /** NOT NULL: nao ha nome de constraint na mensagem, mas o {@code Detail:} vaza igual. */
    private static final String MSG_NOT_NULL = """
            ERROR: null value in column "nome" of relation "cliente" violates not-null constraint
              Detail: Failing row contains (12, null, maria.silva@exemplo.com.br, 11 98888-7777).""";

    private static DataIntegrityViolationException doPostgres(String mensagem, String sqlState) {
        SQLException driver = new SQLException(mensagem, sqlState);
        return new DataIntegrityViolationException("could not execute statement", driver);
    }

    @Test
    @DisplayName("unicidade: sai o e-mail duplicado, fica constraint + SQLState + classe da causa")
    void unicidadeRedigeDadoEPreservaDiagnostico() {
        DataIntegrityViolationException ex = doPostgres(MSG_UNIQUE, "23505");

        // Guarda de sanidade: sem isto, o teste poderia passar por a mensagem nunca ter vazado nada.
        assertThat(ex.getMostSpecificCause().getMessage()).contains("maria.silva@exemplo.com.br");

        String diagnostico = DiagnosticoIntegridade.de(ex);

        assertThat(diagnostico)
                .doesNotContain("maria.silva@exemplo.com.br")
                .doesNotContain("Detail")
                .doesNotContain("Key (email)")
                .contains("constraint=uq_usuario_email")
                .contains("sqlState=23505")
                .contains("causa=SQLException");
    }

    @Test
    @DisplayName("CHECK: sai o 'Failing row contains' inteiro, fica o nome da constraint")
    void checkRedigeLinhaInteiraEPreservaConstraint() {
        DataIntegrityViolationException ex = doPostgres(MSG_CHECK, "23514");

        assertThat(ex.getMostSpecificCause().getMessage())
                .contains("Maria Silva")
                .contains("11 98888-7777");

        String diagnostico = DiagnosticoIntegridade.de(ex);

        assertThat(diagnostico)
                .doesNotContain("Maria Silva")
                .doesNotContain("maria.silva@exemplo.com.br")
                .doesNotContain("11 98888-7777")
                .doesNotContain("Failing row")
                .contains("constraint=ck_mov_cliente_tipo")
                .contains("sqlState=23514");
    }

    @Test
    @DisplayName("NOT NULL: sem constraint nomeada vira '?', e o SQLState ainda diagnostica")
    void notNullSemConstraintNomeadaNaoVazaEAindaDiagnostica() {
        DataIntegrityViolationException ex = doPostgres(MSG_NOT_NULL, "23502");

        String diagnostico = DiagnosticoIntegridade.de(ex);

        // A perna do dado: nada da linha que falhou sobrevive.
        assertThat(diagnostico)
                .doesNotContain("maria.silva@exemplo.com.br")
                .doesNotContain("11 98888-7777")
                .doesNotContain("cliente"); // nem o nome da relacao, que vem da mesma string suja
        // A perna do diagnostico: mesmo sem nome de constraint, o SQLState diz "violou NOT NULL".
        assertThat(diagnostico)
                .contains("constraint=?")
                .contains("sqlState=23502")
                .contains("causa=SQLException");
    }

    @Test
    @DisplayName("aspas com conteudo que nao e identificador: vira '?' em vez de vazar")
    void conteudoEstranhoEntreAspasNaoEscapa() {
        // Cerca final: se um driver puser texto livre onde deveria haver um identificador, o redator
        // recusa em vez de repassar. 'Maria Silva' tem espaco, logo nao casa com [A-Za-z0-9_$].
        DataIntegrityViolationException ex =
                doPostgres("ERROR: violates constraint \"Maria Silva <maria@exemplo.com.br>\"", "23505");

        String diagnostico = DiagnosticoIntegridade.de(ex);

        assertThat(diagnostico)
                .doesNotContain("Maria Silva")
                .doesNotContain("maria@exemplo.com.br")
                .contains("constraint=?")
                .contains("sqlState=23505");
    }

    @Test
    @DisplayName("mensagem nula nao quebra o handler e ainda identifica a causa")
    void mensagemNulaNaoQuebra() {
        DataIntegrityViolationException ex =
                new DataIntegrityViolationException("conflito", new SQLException((String) null, "23505"));

        String diagnostico = DiagnosticoIntegridade.de(ex);

        assertThat(diagnostico)
                .isEqualTo("constraint=? sqlState=23505 causa=SQLException");
    }

    @Test
    @DisplayName("excecao sem causa SQL: SQLState desconhecido, sem estourar")
    void semCausaSqlDegradaSemQuebrar() {
        String diagnostico = DiagnosticoIntegridade.de(
                new DataIntegrityViolationException("violates unique constraint \"uk_cor_nome\""));

        assertThat(diagnostico)
                .contains("constraint=uk_cor_nome")
                .contains("sqlState=?");
    }
}
