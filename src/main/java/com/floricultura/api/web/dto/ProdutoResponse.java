package com.floricultura.api.web.dto;

import com.floricultura.api.domain.Produto;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * {@code data} das respostas de leitura de produto (SPEC-M2 §3.2): item de
 * {@code PaginaResponse.conteudo} no {@code GET /produtos} (lista) e corpo de {@code GET
 * /produtos/{id}} (detalhe). Tambem serve o {@code POST}/{@code PUT} (T-M2-3).
 *
 * <p>{@code descricao}, {@code preco} e {@code imagemUrl} podem ser {@code null} (§3.2/AD-SQ-28/
 * AD-SQ-32). {@code estoqueBaixo} e <b>computado no back</b> a cada leitura (FC-13/CA-15):
 * {@code estoqueAtual <= estoqueMinimo} — inclui o caso {@code estoqueAtual = 0}. {@code
 * estoqueAtual} nunca vem em request (so muda via movimentacao — AD-SQ-30).
 *
 * @param id            id do produto
 * @param nome          nome de exibicao
 * @param descricao     descricao livre (pode ser {@code null})
 * @param unidadeMedida codigo da unidade em ASCII ({@code un,kg,saco,m3,l,g} — AD-SQ-31)
 * @param estoqueMinimo estoque minimo configurado (gatilho do alerta)
 * @param estoqueAtual  estoque atual (snapshot; so muda via movimentacao)
 * @param preco         preco unitario opcional (pode ser {@code null} — AD-SQ-28)
 * @param imagemUrl     URL da imagem (pode ser {@code null}; sem validacao de existencia — AD-SQ-32)
 * @param estoqueBaixo  {@code true} quando {@code estoqueAtual <= estoqueMinimo} (FC-13)
 * @param ativo         estado do produto
 * @param criadoEm      instante de criacao (UTC ISO-8601), do {@code DEFAULT now()} do banco
 * @param atualizadoEm  instante da ultima atualizacao (UTC ISO-8601)
 */
public record ProdutoResponse(
        Long id,
        String nome,
        String descricao,
        String unidadeMedida,
        BigDecimal estoqueMinimo,
        BigDecimal estoqueAtual,
        BigDecimal preco,
        String imagemUrl,
        boolean estoqueBaixo,
        boolean ativo,
        Instant criadoEm,
        Instant atualizadoEm) {

    /** Mapeia a entidade para o response, computando {@code estoqueBaixo} (FC-13/CA-15). */
    public static ProdutoResponse de(Produto produto) {
        boolean estoqueBaixo =
                produto.getEstoqueAtual().compareTo(produto.getEstoqueMinimo()) <= 0;
        return new ProdutoResponse(
                produto.getId(),
                produto.getNome(),
                produto.getDescricao(),
                produto.getUnidadeMedida(),
                produto.getEstoqueMinimo(),
                produto.getEstoqueAtual(),
                produto.getPreco(),
                produto.getImagemUrl(),
                estoqueBaixo,
                produto.isAtivo(),
                produto.getCriadoEm(),
                produto.getAtualizadoEm());
    }
}
