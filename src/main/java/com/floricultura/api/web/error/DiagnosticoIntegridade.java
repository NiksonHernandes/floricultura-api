package com.floricultura.api.web.error;

import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resumo <b>seguro</b> de uma violacao de integridade, para ir ao log no lugar da mensagem crua do
 * driver (AD-SQ-170 / LGPD).
 *
 * <p><b>O problema.</b> {@code DataIntegrityViolationException#getMessage()} carrega a mensagem do
 * PostgreSQL inteira, e o PostgreSQL anexa o <b>dado que falhou</b> na linha {@code Detail:} —
 * {@code Key (email)=(maria@exemplo.com.br) already exists} numa violacao de unicidade e
 * {@code Failing row contains (12, Maria Silva, maria@…, 11 98888-7777, …)} numa violacao de CHECK
 * ou NOT NULL. Em {@code cliente}/{@code fornecedor} (e na contraparte desnormalizada de
 * {@code movimentacao_estoque}) isso despeja nome, e-mail e telefone em arquivo de log, que nao tem
 * o controle de acesso das tabelas. Dado pessoal em log e retencao nao declarada.
 *
 * <p><b>A troca.</b> Nao basta calar o log: um log que so diz "deu erro" tira do dono a capacidade
 * de investigar. Sai o conteudo, fica o <b>metadado de esquema</b>, que descreve a regra violada e
 * nao a pessoa: nome da constraint, {@code SQLState} e a classe da causa. Com
 * {@code constraint=uq_usuario_email sqlState=23505 causa=PSQLException} da para achar a regra no
 * banco e o caminho no codigo sem saber quem tentou gravar o que.
 *
 * <p><b>Limite conhecido.</b> Violacao de NOT NULL ({@code SQLState 23502}) nao nomeia constraint na
 * mensagem do PostgreSQL ("violates not-null constraint", sem aspas) — nesses casos sai
 * {@code constraint=?} e o diagnostico fica por conta do {@code SQLState} + rota. Extrair o nome da
 * coluna/relacao exigiria mais regex sobre a mesma string que estamos justamente tratando como
 * contaminada; preferimos o log mais pobre ao risco novo.
 */
public final class DiagnosticoIntegridade {

    /** Valor quando o dado nao pode ser extraido com seguranca — nunca a mensagem crua. */
    static final String DESCONHECIDO = "?";

    /** Profundidade maxima na cadeia de causas (defesa contra cadeia ciclica). */
    private static final int MAX_CAUSAS = 10;

    /** O PostgreSQL sempre cita a constraint entre aspas: {@code ... constraint "uk_cor_nome"}. */
    private static final Pattern CONSTRAINT = Pattern.compile("constraint \"([^\"]{1,63})\"");

    /**
     * Cerca final: so sai do extrator o que tem forma de <b>identificador</b> de banco. Se um dia uma
     * mensagem de driver puser outra coisa entre as aspas, o resultado e {@code ?} e nao um vazamento.
     */
    private static final Pattern IDENTIFICADOR = Pattern.compile("[A-Za-z0-9_$]{1,63}");

    private DiagnosticoIntegridade() {
        // utilitario
    }

    /**
     * Resumo de uma linha, pronto para o log: {@code constraint=<nome> sqlState=<codigo>
     * causa=<ClasseDaCausa>}. Nunca inclui trecho da mensagem do driver — cada campo e extraido e
     * validado isoladamente, e o que nao passa na validacao vira {@code ?}.
     */
    public static String de(Throwable ex) {
        Throwable causa = causaMaisEspecifica(ex);
        return "constraint=" + constraintDe(causa)
                + " sqlState=" + sqlStateDe(ex)
                + " causa=" + (causa == null ? DESCONHECIDO : causa.getClass().getSimpleName());
    }

    /** Nome da constraint violada, ou {@code ?} quando a mensagem nao a nomeia (ex.: NOT NULL). */
    private static String constraintDe(Throwable causa) {
        String mensagem = causa == null ? null : causa.getMessage();
        if (mensagem == null) {
            return DESCONHECIDO;
        }
        Matcher m = CONSTRAINT.matcher(mensagem);
        if (!m.find()) {
            return DESCONHECIDO;
        }
        String nome = m.group(1);
        return IDENTIFICADOR.matcher(nome).matches() ? nome : DESCONHECIDO;
    }

    /** {@code SQLState} da primeira {@link SQLException} da cadeia (23505 unique, 23514 check, …). */
    private static String sqlStateDe(Throwable ex) {
        Throwable atual = ex;
        for (int i = 0; atual != null && i < MAX_CAUSAS; i++) {
            if (atual instanceof SQLException sql && sql.getSQLState() != null) {
                String estado = sql.getSQLState();
                return IDENTIFICADOR.matcher(estado).matches() ? estado : DESCONHECIDO;
            }
            atual = atual.getCause() == atual ? null : atual.getCause();
        }
        return DESCONHECIDO;
    }

    /** Ultima causa da cadeia — a do driver, que e quem nomeia a constraint. */
    private static Throwable causaMaisEspecifica(Throwable ex) {
        Throwable atual = ex;
        for (int i = 0; atual != null && i < MAX_CAUSAS; i++) {
            Throwable proxima = atual.getCause();
            if (proxima == null || proxima == atual) {
                return atual;
            }
            atual = proxima;
        }
        return atual;
    }
}
