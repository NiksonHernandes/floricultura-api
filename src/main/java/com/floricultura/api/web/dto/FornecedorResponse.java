package com.floricultura.api.web.dto;

import com.floricultura.api.domain.Fornecedor;
import java.time.Instant;
import java.util.List;

/**
 * {@code data} das respostas de leitura/escrita de fornecedor (SPEC-M5 §3.3) — irma de
 * {@link ClienteResponse}: item de {@code PaginaResponse.conteudo} no {@code GET /fornecedores} (lista),
 * corpo do {@code GET /fornecedores/{id}} (detalhe) e retorno do {@code POST}/{@code PUT}.
 *
 * <p><b>Revisao 2026-09-04 (AD-SQ-65):</b> {@code produtoIds} e <b>derivado da movimentacao</b>
 * (read-only, produtos das ENTRADAS deste fornecedor) em vez de junção N:N. So aparece no <b>detalhe</b>
 * ({@code GET /{id}}) e no retorno de POST/PUT ({@link #comProdutos}); na <b>lista</b> vem {@code null}
 * ({@link #de}, nunca materializa o vinculo — AD-SQ-38). {@code telefone}/{@code email}/{@code observacoes}
 * podem ser {@code null} (minimizacao LGPD, §4.1).
 *
 * @param id           id do fornecedor
 * @param nome         nome de exibicao
 * @param telefone     telefone livre (pode ser {@code null})
 * @param email        e-mail (pode ser {@code null})
 * @param observacoes  observacoes livres (pode ser {@code null})
 * @param produtoIds   ids dos produtos derivados — {@code null} na LISTA; derivado do ledger no DETALHE/escrita
 * @param criadoEm     instante de criacao (UTC ISO-8601), do {@code DEFAULT now()} do banco
 * @param atualizadoEm instante da ultima atualizacao (UTC ISO-8601)
 */
public record FornecedorResponse(
        Long id,
        String nome,
        String telefone,
        String email,
        String observacoes,
        List<Long> produtoIds,
        Instant criadoEm,
        Instant atualizadoEm) {

    /**
     * Variante de <b>lista</b> (§3.3/CA-3): {@code produtoIds = null} — a lista nunca materializa o
     * vinculo derivado (AD-SQ-38), evita varredura por linha do ledger.
     */
    public static FornecedorResponse de(Fornecedor fornecedor) {
        return new FornecedorResponse(
                fornecedor.getId(),
                fornecedor.getNome(),
                fornecedor.getTelefone(),
                fornecedor.getEmail(),
                fornecedor.getObservacoes(),
                null,
                fornecedor.getCriadoEm(),
                fornecedor.getAtualizadoEm());
    }

    /**
     * Variante de <b>detalhe</b>/escrita (§3.3/§R3.4, R-CA-7): inclui os {@code produtoIds} derivados do
     * ledger (produtos das ENTRADAS deste fornecedor). Fornecedor sem ENTRADAS → {@code produtoIds = []}.
     */
    public static FornecedorResponse comProdutos(Fornecedor fornecedor, List<Long> produtoIds) {
        return new FornecedorResponse(
                fornecedor.getId(),
                fornecedor.getNome(),
                fornecedor.getTelefone(),
                fornecedor.getEmail(),
                fornecedor.getObservacoes(),
                produtoIds,
                fornecedor.getCriadoEm(),
                fornecedor.getAtualizadoEm());
    }
}
