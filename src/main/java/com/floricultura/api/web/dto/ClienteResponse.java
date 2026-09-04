package com.floricultura.api.web.dto;

import com.floricultura.api.domain.Cliente;
import java.time.Instant;
import java.util.List;

/**
 * {@code data} das respostas de leitura/escrita de cliente (SPEC-M5 §3.3): item de
 * {@code PaginaResponse.conteudo} no {@code GET /clientes} (lista), corpo do {@code GET /clientes/{id}}
 * (detalhe) e retorno do {@code POST}/{@code PUT}.
 *
 * <p><b>{@code produtoIds} so aparece no detalhe</b> ({@code GET /{id}}) e no retorno de POST/PUT; na
 * <b>lista</b> vem {@code null} — a leitura de lista nunca materializa o vinculo N:N (§4.2/AD-SQ-38/44).
 * {@code telefone}/{@code email}/{@code observacoes} podem ser {@code null} (minimizacao LGPD, §4.1).
 *
 * @param id           id do cliente
 * @param nome         nome de exibicao
 * @param telefone     telefone livre (pode ser {@code null})
 * @param email        e-mail (pode ser {@code null})
 * @param observacoes  observacoes livres (pode ser {@code null})
 * @param produtoIds   ids dos produtos vinculados — {@code null} na LISTA, preenchido no DETALHE/escrita
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
     * Variante de <b>lista</b> (§3.3/CA-3): {@code produtoIds = null} — a colecao de vinculos nunca vem
     * na lista (evita N+1, nunca materializa o N:N).
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
     * Variante de <b>detalhe</b>/escrita (§3.3/CA-2): inclui os {@code produtoIds} vinculados (lidos por
     * query nativa dedicada). Cliente sem vinculos → {@code produtoIds = []} (§4.5).
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
