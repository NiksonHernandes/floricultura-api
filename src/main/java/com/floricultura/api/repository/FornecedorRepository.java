package com.floricultura.api.repository;

import com.floricultura.api.domain.Fornecedor;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
