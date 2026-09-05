package com.floricultura.api.web.dto;

import com.floricultura.api.domain.Cliente;
import java.time.Instant;
import java.util.List;

/**
 * {@code data} das respostas de leitura/escrita de cliente (SPEC-M5 §3.3): item de
 * {@code PaginaResponse.conteudo} no {@code GET /clientes} (lista), corpo do {@code GET /clientes/{id}}
 * (detalhe) e retorno do {@code POST}/{@code PUT}.
 *
 * <p><b>Revisao 2026-09-04 (AD-SQ-65):</b> {@code produtoIds} e <b>derivado da movimentacao</b>
 * (read-only, produtos das SAIDAS deste cliente) em vez de junção N:N. So aparece no <b>detalhe</b>
 * ({@code GET /{id}}) e no retorno de POST/PUT ({@link #comProdutos}); na <b>lista</b> vem {@code null}
 * ({@link #de}, nunca materializa o vinculo — AD-SQ-38). {@code telefone}/{@code email}/{@code observacoes}
 * podem ser {@code null} (minimizacao LGPD, §4.1).
 *
 * @param id           id do cliente
 * @param nome         nome de exibicao
 * @param telefone     telefone livre (pode ser {@code null})
 * @param email        e-mail (pode ser {@code null})
 * @param observacoes  observacoes livres (pode ser {@code null})
 * @param produtoIds   ids dos produtos derivados — {@code null} na LISTA; derivado do ledger no DETALHE/escrita
 * @param criadoEm     instante de criacao (UTC ISO-8601), do {@code DEFAULT now()} do banco
 * @param atualizadoEm instante da ultima atualizacao (UTC ISO-8601)
 */
public record ClienteResponse(
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
    public static ClienteResponse de(Cliente cliente) {
        return new ClienteResponse(
                cliente.getId(),
                cliente.getNome(),
                cliente.getTelefone(),
                cliente.getEmail(),
                cliente.getObservacoes(),
                null,
                cliente.getCriadoEm(),
                cliente.getAtualizadoEm());
    }

    /**
     * Variante de <b>detalhe</b>/escrita (§3.3/§R3.4, R-CA-7): inclui os {@code produtoIds} derivados do
     * ledger (produtos das SAIDAS deste cliente). Cliente sem SAIDAS → {@code produtoIds = []}.
     */
    public static ClienteResponse comProdutos(Cliente cliente, List<Long> produtoIds) {
        return new ClienteResponse(
                cliente.getId(),
                cliente.getNome(),
                cliente.getTelefone(),
                cliente.getEmail(),
                cliente.getObservacoes(),
                produtoIds,
                cliente.getCriadoEm(),
                cliente.getAtualizadoEm());
    }
}
