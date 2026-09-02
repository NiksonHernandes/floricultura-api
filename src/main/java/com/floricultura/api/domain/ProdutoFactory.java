package com.floricultura.api.domain;

import java.math.BigDecimal;

/**
 * Fabrica de {@link Produto} para a criacao por ADMIN (SPEC-M2 §3.2/§4, CA-6). Vive no MESMO pacote de
 * {@link Produto} porque o construtor da entidade e {@code protected} (exigido pelo JPA, T-M2-1) —
 * este helper permite instancia-la a partir da camada de servico <b>sem alterar a entity</b> (mesma
 * convencao do {@code UsuarioFactory} do M1).
 *
 * <p>Centraliza os invariantes de "produto novo": nasce {@code ativo=true} e, sobretudo,
 * {@code estoqueAtual=0} (AD-SQ-30) — estoque so muda via movimentacao (T-M2-4); o CRUD nunca o toca.
 * As colunas {@code estoque_atual} e {@code ativo} sao {@code insertable} e NOT NULL, entao precisam
 * de valor explicito no insert (o {@code DEFAULT} do banco so valeria se a coluna fosse omitida, o que
 * o Hibernate nao faz). {@code criado_em}/{@code atualizado_em} <b>nao</b> sao setados aqui: vem do
 * {@code DEFAULT now()} do banco (colunas {@code insertable=false}).
 */
public final class ProdutoFactory {

    private ProdutoFactory() {
        // Utilitaria — sem instancia.
    }

    /**
     * Cria um produto ativo com {@code estoqueAtual=0} (AD-SQ-30). Os campos de cadastro ja chegam
     * validados pelo DTO ({@code CriarProdutoRequest}).
     *
     * @param nome          nome de exibicao (obrigatorio, ja validado)
     * @param descricao     descricao livre (pode ser {@code null})
     * @param unidadeMedida codigo da unidade em ASCII (∈ {@code un,kg,saco,m3,l,g})
     * @param estoqueMinimo estoque minimo configurado ({@code >= 0})
     * @param preco         preco unitario opcional (pode ser {@code null})
     * @param imagemUrl     URL da imagem (pode ser {@code null})
     * @return entidade transiente pronta para {@code save}
     */
    public static Produto novo(
            String nome,
            String descricao,
            String unidadeMedida,
            BigDecimal estoqueMinimo,
            BigDecimal preco,
            String imagemUrl) {
        Produto produto = new Produto();
        produto.setNome(nome);
        produto.setDescricao(descricao);
        produto.setUnidadeMedida(unidadeMedida);
        produto.setEstoqueMinimo(estoqueMinimo);
        produto.setEstoqueAtual(BigDecimal.ZERO); // AD-SQ-30: nasce zerado; estoque so muda via movimentacao
        produto.setPreco(preco);
        produto.setImagemUrl(imagemUrl);
        produto.setAtivo(true);
        return produto;
    }
}
