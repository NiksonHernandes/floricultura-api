package com.floricultura.api.service;

import com.floricultura.api.web.error.ErrorCode;

/**
 * As recusas de {@code POST /api/v1/movimentacoes/{id}/estorno} (SPEC-M7 §3.4-d, CA-12/CA-13 /
 * AD-SQ-156) — <b>uma</b> excecao com fabricas nomeadas, no molde de {@code CorConflitoException}, cada
 * uma carregando o {@link ErrorCode} do contrato para o handler nao precisar adivinhar o status.
 *
 * <p>Todas sao lancadas pelo {@code MovimentacaoService} <b>antes de qualquer escrita</b> (e antes do
 * lock pessimista do produto): nem o {@code estoque_atual} nem o ledger sao tocados.
 *
 * <ul>
 *   <li>{@link #naoEncontrada()} — {@code 404}: o id nao existe.</li>
 *   <li>{@link #jaEstornada()} — {@code 409}: cada lancamento e estornado <b>no maximo uma vez</b>.
 *       Esta e a <b>porta da frente</b>; o indice unico parcial {@code ux_mov_estorno} da V13 e que
 *       faz a exclusao mutua de verdade na corrida de dois cliques (a violacao e traduzida para
 *       <b>esta mesma mensagem</b> pelo handler do controller).</li>
 *   <li>{@link #deEstorno()} — {@code 409}: estorno de estorno esta fora de escopo (§2). A linha
 *       inversa de uma inversa seria a original de novo, sem ganho de auditoria.</li>
 *   <li>{@link #deAjuste()} — {@code 409}: o alvo anterior de um {@code AJUSTE} (alvo <b>absoluto</b>)
 *       nao e derivavel da linha — a correcao de um AJUSTE e outro AJUSTE, que ja funciona hoje.</li>
 *   <li>{@link #produtoExcluido()} — {@code 409} (PA#4, decidida — AD-SQ-160 #4): {@code produto_id IS
 *       NULL} (FC-08) significa que <b>nao ha estoque para devolver</b>. A alternativa (linha sem
 *       efeito de estoque) e anulacao contabil pura, outro conceito, deliberadamente fora deste marco
 *       (SPEC-M7 §12 #19) — e <b>nao</b> se transforma este 409 em 200 "porque parecia inofensivo".</li>
 * </ul>
 */
public class EstornoInvalidoException extends RuntimeException {

    private final transient ErrorCode codigo;

    private EstornoInvalidoException(ErrorCode codigo, String message) {
        super(message);
        this.codigo = codigo;
    }

    public static EstornoInvalidoException naoEncontrada() {
        return new EstornoInvalidoException(ErrorCode.NOT_FOUND, "Movimentação não encontrada.");
    }

    public static EstornoInvalidoException jaEstornada() {
        return new EstornoInvalidoException(
                ErrorCode.CONFLICT, "Este lançamento já foi estornado.");
    }

    public static EstornoInvalidoException deEstorno() {
        return new EstornoInvalidoException(
                ErrorCode.CONFLICT, "Um estorno não pode ser estornado.");
    }

    public static EstornoInvalidoException deAjuste() {
        return new EstornoInvalidoException(ErrorCode.CONFLICT,
                "Lançamento de AJUSTE não é estornável; registre um novo AJUSTE.");
    }

    public static EstornoInvalidoException produtoExcluido() {
        return new EstornoInvalidoException(ErrorCode.CONFLICT,
                "Produto excluído: não é possível estornar este lançamento.");
    }

    /** Status do contrato (§3.4-d): {@code NOT_FOUND} na primeira, {@code CONFLICT} nas demais. */
    public ErrorCode getCodigo() {
        return codigo;
    }
}
