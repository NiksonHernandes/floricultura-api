package com.floricultura.api.service;

/**
 * Os dois conflitos de estado do catalogo de cores (SPEC-M6 §3.2.1) — ambos {@code 409 CONFLICT} com a
 * mensagem <b>exata</b> do contrato (o front a exibe), entao vivem numa unica excecao com duas fabricas:
 *
 * <ul>
 *   <li>{@link #duplicada()} — POST/PUT cujo CANONICO ja existe (CA-2/CA-5). Detectado por
 *       {@code existsByNome} <b>antes</b> do save; {@code uk_cor_nome} fecha a corrida.</li>
 *   <li>{@link #emUso(long)} — DELETE de cor vinculada a produto (CA-6/P3). Contado <b>antes</b> de
 *       deletar; o {@code ON DELETE RESTRICT} e a 2a linha de defesa.</li>
 * </ul>
 */
public class CorConflitoException extends RuntimeException {

    private CorConflitoException(String message) {
        super(message);
    }

    public static CorConflitoException duplicada() {
        return new CorConflitoException("Já existe uma cor com esse nome.");
    }

    public static CorConflitoException emUso(long produtosVinculados) {
        return new CorConflitoException("Cor em uso por " + produtosVinculados
                + " produto(s) — desvincule dos produtos antes de excluir.");
    }
}
