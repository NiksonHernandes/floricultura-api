package com.floricultura.api.config;

import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;
import org.springframework.dao.DataAccessException;

/**
 * Renderizador de stacktrace que preserva o <b>onde</b> (classe + frames) e corta o <b>que</b>
 * (mensagem) quando a cadeia tem causa de banco (AD-SQ-176 / LGPD).
 *
 * <p><b>O problema.</b> O cabecalho de cada linha de um stacktrace e {@code FQCN: getMessage()}. Numa
 * excecao de banco esse {@code getMessage()} e a mensagem do PostgreSQL, que anexa o dado que falhou
 * ({@code Detalhe: Key (email)=(…)} / {@code Failing row contains (…)}). Logo, todo
 * {@code log.error("…", ex)} de um caminho de persistencia despeja dado pessoal no arquivo de log —
 * inclusive o catch-all do {@code GlobalExceptionHandler}, que e o unico lugar do app onde o
 * stacktrace inteiro e impresso de proposito.
 *
 * <p><b>Por que aqui e nao no nosso codigo.</b> Um {@code conversionRule} vale para <b>todos</b> os
 * loggers do processo, inclusive os de dependencia, que a gente nao escreve e nao pode revisar.
 *
 * <p><b>Por que nao um filtro de PII por regex.</b> Filtro de regex <i>adivinha onde o dado esta</i>:
 * o driver <b>localiza</b> o rotulo ({@code Detalhe:} em pt-BR, {@code Detail:} em en), formato de
 * e-mail nao pega nome nem endereco, e um filtro que erra <b>redige em silencio</b>. Aqui nao ha
 * adivinhacao: o criterio e a <b>identidade da classe</b> na cadeia, e o corte e anunciado no texto.
 *
 * <p><b>O criterio, e por que ele e da cadeia inteira.</b> A contaminacao sobe: o
 * {@code getMessage()} de {@code DataIntegrityViolationException}/{@code JpaSystemException} embute a
 * mensagem do driver. Entao, se <b>algum</b> elo da cadeia (causas + suprimidas) for
 * {@link SQLException} ou {@link DataAccessException}, a mensagem de <b>todos</b> os elos e
 * substituida. Quando nao ha elo de banco, este conversor devolve <b>byte a byte</b> o que o
 * renderizador padrao do Spring Boot devolveria — nenhuma outra excecao do app perde mensagem.
 *
 * <p><b>O que sobra para investigar.</b> O nome da classe de cada elo, a cadeia {@code Caused by:}
 * inteira, todos os frames e os dados de empacotamento. Some apenas o texto do servidor — e o
 * diagnostico de integridade continua saindo estruturado pelo {@link
 * com.floricultura.api.web.error.DiagnosticoIntegridade} (constraint + {@code SQLState}).
 */
public class ThrowableSemMensagemDeBancoConverter extends ExtendedWhitespaceThrowableProxyConverter {

    /** Marca visivel do corte: redigir em silencio e pior do que vazar — o operador tem de saber. */
    static final String OMITIDA = ": <mensagem do banco omitida (LGPD/AD-SQ-176)>";

    /** Profundidade maxima na cadeia (defesa contra cadeia ciclica), igual a do DiagnosticoIntegridade. */
    private static final int MAX_CAUSAS = 10;

    /** Teto do cache de classificacao — o conversor roda em caminho de log, nao pode crescer sem fim. */
    private static final int MAX_CACHE = 512;

    private static final Map<String, Boolean> EH_DE_BANCO = new ConcurrentHashMap<>();

    @Override
    protected String throwableProxyToString(IThrowableProxy tp) {
        return super.throwableProxyToString(cadeiaTemCausaDeBanco(tp) ? redigir(tp, 0) : tp);
    }

    /** {@code true} se algum elo da cadeia (causas + suprimidas) for excecao de banco. */
    static boolean cadeiaTemCausaDeBanco(IThrowableProxy tp) {
        IThrowableProxy atual = tp;
        for (int i = 0; atual != null && i < MAX_CAUSAS; i++) {
            if (deBanco(atual.getClassName())) {
                return true;
            }
            for (IThrowableProxy suprimida : suprimidas(atual)) {
                if (suprimida != null && deBanco(suprimida.getClassName())) {
                    return true;
                }
            }
            IThrowableProxy proxima = atual.getCause();
            atual = proxima == atual ? null : proxima;
        }
        return false;
    }

    /**
     * Classificacao pela <b>identidade da classe</b>, nao pelo texto: {@link SQLException} cobre o
     * driver ({@code PSQLException}, {@code BatchUpdateException}) e {@link DataAccessException} cobre
     * a traducao do Spring ({@code DataIntegrityViolationException}, {@code JpaSystemException}).
     * Classe que nao carrega (logger de outro classloader) e tratada como "nao e de banco" — este
     * conversor nunca pode derrubar uma linha de log.
     */
    private static boolean deBanco(String nomeDaClasse) {
        if (nomeDaClasse == null) {
            return false;
        }
        Boolean conhecido = EH_DE_BANCO.get(nomeDaClasse);
        if (conhecido != null) {
            return conhecido;
        }
        boolean resultado = resolver(nomeDaClasse);
        if (EH_DE_BANCO.size() < MAX_CACHE) {
            EH_DE_BANCO.put(nomeDaClasse, resultado);
        }
        return resultado;
    }

    private static boolean resolver(String nomeDaClasse) {
        try {
            Class<?> classe = Class.forName(
                    nomeDaClasse, false, ThrowableSemMensagemDeBancoConverter.class.getClassLoader());
            return SQLException.class.isAssignableFrom(classe)
                    || DataAccessException.class.isAssignableFrom(classe);
        } catch (Throwable erro) {
            return false;
        }
    }

    private static IThrowableProxy[] suprimidas(IThrowableProxy tp) {
        IThrowableProxy[] lista = tp.getSuppressed();
        return lista == null ? new IThrowableProxy[0] : lista;
    }

    private static IThrowableProxy redigir(IThrowableProxy origem, int profundidade) {
        if (origem == null || profundidade >= MAX_CAUSAS) {
            return origem;
        }
        return new ProxyRedigido(origem, profundidade);
    }

    /**
     * Fachada sobre o proxy real: so troca o <b>cabecalho</b> da linha, via
     * {@code getOverridingMessage()} — o gancho oficial do Logback para substituir
     * {@code "FQCN: mensagem"}. Frames, frames comuns, causas e suprimidas sao delegados intactos.
     */
    private record ProxyRedigido(IThrowableProxy alvo, int profundidade) implements IThrowableProxy {

        @Override
        public String getOverridingMessage() {
            return alvo.getClassName() + OMITIDA;
        }

        @Override
        public String getMessage() {
            return null;
        }

        @Override
        public String getClassName() {
            return alvo.getClassName();
        }

        @Override
        public StackTraceElementProxy[] getStackTraceElementProxyArray() {
            return alvo.getStackTraceElementProxyArray();
        }

        @Override
        public int getCommonFrames() {
            return alvo.getCommonFrames();
        }

        @Override
        public IThrowableProxy getCause() {
            IThrowableProxy causa = alvo.getCause();
            return causa == alvo ? null : redigir(causa, profundidade + 1);
        }

        @Override
        public IThrowableProxy[] getSuppressed() {
            IThrowableProxy[] originais = suprimidas(alvo);
            IThrowableProxy[] redigidas = new IThrowableProxy[originais.length];
            for (int i = 0; i < originais.length; i++) {
                redigidas[i] = redigir(originais[i], profundidade + 1);
            }
            return redigidas;
        }

        @Override
        public boolean isCyclic() {
            return alvo.isCyclic();
        }
    }
}
