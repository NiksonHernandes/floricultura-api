package com.floricultura.api.web.dto;

/**
 * Referencia enxuta {@code (id, nome)} de uma entidade relacionada — item das listas de
 * {@link ProdutoRelacionamentosResponse} (SPEC-M5 §R3.5, AD-SQ-66). Leitura derivada, read-only, por
 * <b>nome</b> (nunca arrasta bytea/campos pesados — honra AD-SQ-38). Usado no "visualizar produto"
 * (RF-4) para listar eventos/fornecedores/clientes do produto.
 *
 * @param id   id da entidade referenciada
 * @param nome nome de exibicao (nome atual do cadastro vivo — §R3.5)
 */
public record ReferenciaSimples(Long id, String nome) {
}
