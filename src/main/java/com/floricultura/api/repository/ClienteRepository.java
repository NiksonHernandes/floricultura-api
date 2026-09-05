package com.floricultura.api.repository;

import com.floricultura.api.domain.Cliente;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Acesso a persistencia de {@link Cliente} (SPEC-M5 §3.5). O {@code findAll(Pageable)} herdado cobre a
 * listagem paginada/ordenada ({@code nome ASC}) e {@code findByNomeContainingIgnoreCase} implementa o
 * filtro {@code ILIKE '%nome%'} (case-insensitive, substring) do contrato §3.4. O {@code existsById}
 * herdado serve ao hard delete.
 *
 * <p><b>Revisao 2026-09-04 (AD-SQ-65):</b> o vinculo cliente↔produto e <b>derivado da movimentacao</b>
 * (SAIDAS deste cliente), nao mais junção N:N editavel. {@code findNomeById} resolve o snapshot do nome
 * para a contraparte da movimentacao (padrao {@code UsuarioRepository.findNomeById}/AD-SQ-45);
 * {@code findProdutoIdsByClienteId} deriva os produtos do <b>detalhe</b> (R3.4/R-CA-7) — nunca na LISTA
 * (AD-SQ-38).
 */
@Repository
public interface ClienteRepository extends JpaRepository<Cliente, Long> {

    Page<Cliente> findByNomeContainingIgnoreCase(String nome, Pageable pageable);

    /**
     * Projecao escalar do nome do cliente para o snapshot da contraparte no ledger (RB-3, §R3.3): resolve
     * {@code cliente_nome} no INSERT da movimentacao sem carregar a entidade. {@code null} se o id nao
     * existir — o servico transforma isso em {@code 400 field=clienteId} (R-CA-5).
     */
    @Query("select c.nome from Cliente c where c.id = :id")
    String findNomeById(@Param("id") Long id);

    /**
     * {@code produtoIds} <b>derivados do ledger</b> (detalhe {@code GET /{id}} — §R3.4/R-CA-7,
     * AD-SQ-65): os produtos das <b>SAIDAS</b> deste cliente, deduplicados e ordenados. Query nativa que
     * enumera a coluna (nunca {@code SELECT *}, nunca {@code bytea}); a LISTA nunca materializa o vinculo.
     */
    @Query(value = "SELECT DISTINCT produto_id FROM movimentacao_estoque "
            + "WHERE cliente_id = :id AND tipo = 'SAIDA' AND produto_id IS NOT NULL "
            + "ORDER BY produto_id", nativeQuery = true)
    List<Long> findProdutoIdsByClienteId(@Param("id") Long id);
}
