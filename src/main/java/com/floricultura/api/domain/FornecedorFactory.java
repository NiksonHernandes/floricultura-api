package com.floricultura.api.domain;

/**
 * Fabrica de {@link Fornecedor} para a criacao por ADMIN (SPEC-M5 §3.2/§3.3, CA-4) — irma de
 * {@link ClienteFactory}. Vive no MESMO pacote de {@link Fornecedor} porque o construtor da entidade e
 * {@code protected} (exigido pelo JPA) — este helper permite instancia-la a partir da camada de servico
 * <b>sem alterar a entity</b> (mesma convencao de {@code EventoFactory}/{@code ProdutoFactory}).
 *
 * <p>{@code criado_em}/{@code atualizado_em} <b>nao</b> sao setados aqui: vem do {@code DEFAULT now()}
 * do banco (colunas {@code insertable=false}). Os vinculos N:N {@code produtoIds} sao aplicados por
 * query nativa dedicada no {@code FornecedorProdutoVinculoService} (T-M5-5), nao pela fabrica.
 */
public final class FornecedorFactory {

    private FornecedorFactory() {
        // Utilitaria — sem instancia.
    }

    /**
     * Cria um fornecedor transiente pronto para {@code save}. Os campos ja chegam validados na forma
     * pelo {@code FornecedorRequest} (Bean Validation — {@code nome} obrigatorio; {@code email} formato
     * quando presente).
     *
     * @param nome        nome de exibicao (obrigatorio)
     * @param telefone    telefone livre (pode ser {@code null})
     * @param email       e-mail (pode ser {@code null}; formato validado quando presente)
     * @param observacoes observacoes livres ≤500 (pode ser {@code null})
     * @return entidade transiente pronta para persistir
     */
    public static Fornecedor novo(String nome, String telefone, String email, String observacoes) {
        Fornecedor fornecedor = new Fornecedor();
        fornecedor.setNome(nome);
        fornecedor.setTelefone(telefone);
        fornecedor.setEmail(email);
        fornecedor.setObservacoes(observacoes);
        return fornecedor;
    }
}
