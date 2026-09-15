package com.floricultura.api.web.dto;

import com.floricultura.api.domain.Cor;
import com.floricultura.api.domain.CorComUso;
import java.time.Instant;

/**
 * {@code data} de toda resposta de cor (SPEC-M6 §3.2): item de {@code PaginaResponse.conteudo} na lista,
 * corpo do {@code GET /cores/{id}} e retorno do {@code POST}/{@code PUT}.
 *
 * <p>{@code nome} e <b>SEMPRE o canonico persistido</b> (AD-SQ-90) — nao existe nome de exibicao e a UI
 * nao re-embeleza (R1d). {@code produtosVinculados} vem da contagem agrupada do servico (1 query por
 * pagina, sem N+1 — CA-4) e deixa a UI honesta antes de o operador tentar excluir (P3).
 *
 * @param id                 id da cor
 * @param nome               nome CANONICO (ex.: {@code CINZA-ESCURO})
 * @param hex                amostra {@code #RRGGBB} em maiusculas, ou {@code null}
 * @param produtosVinculados quantos produtos usam a cor ({@code 0} quando nenhum)
 * @param criadoEm           instante de criacao (UTC ISO-8601), do {@code DEFAULT now()} do banco
 * @param atualizadoEm       instante da ultima atualizacao (UTC ISO-8601)
 */
public record CorResponse(
        Long id,
        String nome,
        String hex,
        long produtosVinculados,
        Instant criadoEm,
        Instant atualizadoEm) {

    /** Mapeia o par de dominio {@link CorComUso} devolvido pelo {@code CorService} para o contrato §3.2. */
    public static CorResponse de(CorComUso comUso) {
        Cor cor = comUso.cor();
        return new CorResponse(cor.getId(), cor.getNome(), cor.getHex(), comUso.produtosVinculados(),
                cor.getCriadoEm(), cor.getAtualizadoEm());
    }
}
