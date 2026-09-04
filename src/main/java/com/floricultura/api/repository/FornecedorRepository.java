package com.floricultura.api.repository;

import com.floricultura.api.domain.Fornecedor;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Acesso a persistencia de {@link Fornecedor} (SPEC-M5 §3.5) — irmao de {@code ClienteRepository}. O
 * {@code findAll(Pageable)} herdado cobre a listagem paginada/ordenada ({@code nome ASC}) e
 * {@code findByNomeContainingIgnoreCase} implementa o filtro {@code ILIKE '%nome%'} (case-insensitive,
 * substring) do contrato §3.4. O {@code existsById} herdado serve a validacao referencial no vinculo
 * N:N (T-M5-5).
 */
@Repository
public interface FornecedorRepository extends JpaRepository<Fornecedor, Long> {

    Page<Fornecedor> findByNomeContainingIgnoreCase(String nome, Pageable pageable);

    /**
     * Ids dos produtos vinculados ao fornecedor (detalhe {@code GET /{id}} — §3.5/CA-2), ordenados.
     * Query <b>nativa</b> dedicada sobre {@code fornecedor_produto}: o N:N nao e mapeado na @Entity
     * (AD-SQ-44), a lista nunca o materializa; so o detalhe le por aqui.
     */
    @Query(value = "SELECT produto_id FROM fornecedor_produto WHERE fornecedor_id = :id "
            + "ORDER BY produto_id", nativeQuery = true)
    List<Long> findProdutoIdsByFornecedorId(@Param("id") Long id);

    // ----- Vinculo N:N fornecedor<->produto (M5/AD-SQ-44): replace-set por queries nativas. ---------

    /**
     * Apaga todos os vinculos do fornecedor (1o passo do replace-set — §3.5/§4.2). Roda dentro da
     * transacao do {@code FornecedorProdutoVinculoService} (mesmo padrao de {@code removerVinculosDoProduto}).
     */
    @Modifying
    @Query(value = "DELETE FROM fornecedor_produto WHERE fornecedor_id = :id", nativeQuery = true)
    void removerVinculosDoFornecedor(@Param("id") Long id);

    /**
     * Insere um vinculo fornecedor<->produto (2o passo do replace-set — §3.5/§4.2). {@code ON CONFLICT DO
     * NOTHING} torna a insercao idempotente para ids repetidos ja deduplicados no servico.
     */
    @Modifying
    @Query(value = "INSERT INTO fornecedor_produto (fornecedor_id, produto_id) "
            + "VALUES (:fornecedorId, :produtoId) ON CONFLICT DO NOTHING", nativeQuery = true)
    void inserirVinculo(@Param("fornecedorId") Long fornecedorId, @Param("produtoId") Long produtoId);
}
