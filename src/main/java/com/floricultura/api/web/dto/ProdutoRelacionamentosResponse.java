package com.floricultura.api.web.dto;

import java.util.List;

/**
 * {@code data} do {@code GET /api/v1/produtos/{id}/relacionamentos} (SPEC-M5 §R3.5, AD-SQ-66) — os
 * relacionados <b>derivados</b> do produto para o "visualizar produto" (RF-4), cada grupo como lista de
 * {@link ReferenciaSimples} {@code (id, nome)}:
 *
 * <ul>
 *   <li><b>eventos</b>: eventos vinculados via {@code evento_produto} (N:N informativo do M4/AD-SQ-44).</li>
 *   <li><b>fornecedores</b>: {@code DISTINCT} dos fornecedores das <b>ENTRADAS</b> deste produto
 *       (derivado do ledger, §R3.4/R3.5).</li>
 *   <li><b>clientes</b>: {@code DISTINCT} dos clientes das <b>SAIDAS</b> deste produto.</li>
 * </ul>
 *
 * <p>Leitura dedicada, colunas enumeradas, <b>sem bytea</b> (AD-SQ-38/AD-SQ-50). §R3.5 junta a
 * <b>tabela viva</b> (nome atual): um cadastro hard-deletado sai do resultado (o {@code *_id} do ledger
 * fica {@code NULL} apos o cascade, e o INNER JOIN nao casa) — por isso o {@code id} de cada
 * {@link ReferenciaSimples} e sempre nao-nulo.
 *
 * @param eventos      eventos vinculados (ordem {@code nome ASC})
 * @param fornecedores fornecedores das ENTRADAS (dedup, ordem {@code nome ASC})
 * @param clientes     clientes das SAIDAS (dedup, ordem {@code nome ASC})
 */
public record ProdutoRelacionamentosResponse(
        List<ReferenciaSimples> eventos,
        List<ReferenciaSimples> fornecedores,
        List<ReferenciaSimples> clientes) {
}
