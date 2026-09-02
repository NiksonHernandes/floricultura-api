package com.floricultura.api.repository;

import com.floricultura.api.domain.Produto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Acesso a persistencia de {@link Produto} (SPEC-M2 §8). O {@code findAll(Pageable)} herdado cobre a
 * listagem paginada/ordenada (AD-SQ-29); {@code findByNomeContainingIgnoreCase} implementa o filtro
 * {@code ILIKE '%nome%'} (case-insensitive, substring) do contrato de listagem (§3.3) consumido na
 * onda 2 (T-M2-2).
 */
@Repository
public interface ProdutoRepository extends JpaRepository<Produto, Long> {

    Page<Produto> findByNomeContainingIgnoreCase(String nome, Pageable pageable);
}
