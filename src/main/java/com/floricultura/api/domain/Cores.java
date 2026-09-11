package com.floricultura.api.domain;

import java.util.Locale;

/**
 * PONTO UNICO da normalizacao do catalogo de cores (SPEC-M6 §3.2.1 / AD-SQ-90). A regra do dono e
 * "tudo MAIUSCULO e sem espacos; espaco vira hifen" — e o valor PERSISTIDO ja e o canonico (nao existe
 * nome de exibicao separado). Este arquivo e o unico lugar onde a canonizacao mora: POST, PUT e o filtro
 * {@code ?nome} do GET chamam {@link #canonizar(String)}; <b>nunca</b> duplicar a logica em
 * controller/DTO/repositorio (o gate grep da §2151-item-4b verifica isso).
 *
 * <p>Acento CONTA (opcao (a) da §3.2.1): {@code LILAS} e {@code LILÁS} sao cores distintas — nenhuma
 * extensao do Postgres, exibicao pt-BR correta.
 */
public final class Cores {

    /** Tamanho minimo do nome CANONICO (espelha {@code ck_cor_nome_canonico} da V12). */
    public static final int NOME_MIN = 2;

    /** Tamanho maximo do nome CANONICO (espelha {@code cor.nome VARCHAR(40)} da V12). */
    public static final int NOME_MAX = 40;

    private Cores() {
        // Utilitaria — sem instancia.
    }

    /**
     * Aplica os 5 passos da §3.2.1, NESTA ordem: (1) {@code strip()} Unicode; (2) todo run de espaco
     * (inclusive NBSP) vira UM hifen; (3) run de hifens vira UM hifen; (4) remove hifen das pontas;
     * (5) MAIUSCULAS com {@link Locale#ROOT}. O canonico nunca cresce em relacao ao cru, entao a
     * validacao {@code 2..40} sobre o resultado garante o insert (sem {@code DataIntegrityViolation}
     * por comprimento). Entrada {@code null} ou so-separadores → string <b>vazia</b> (o chamador decide:
     * 400 na escrita, "sem filtro" na leitura).
     */
    public static String canonizar(String bruto) {
        if (bruto == null) {
            return "";
        }
        return bruto.strip()
                .replaceAll("[\\s\\u00A0]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-+|-+$", "")
                .toUpperCase(Locale.ROOT);
    }

    /**
     * Normaliza a amostra {@code #RRGGBB} para MAIUSCULAS (§3.2 — {@code #c4326b} → {@code #C4326B}),
     * para a amostra ser estavel. Ausente/em branco → {@code null} (hex e opcional). O formato ja e
     * garantido pelo {@code @Pattern} do DTO e pelo CHECK {@code ck_cor_hex}.
     */
    public static String normalizarHex(String bruto) {
        if (bruto == null || bruto.isBlank()) {
            return null;
        }
        return bruto.strip().toUpperCase(Locale.ROOT);
    }
}
