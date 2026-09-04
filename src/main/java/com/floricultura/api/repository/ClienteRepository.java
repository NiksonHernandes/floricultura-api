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
 * filtro {@code ILIKE '%nome%'} (case-insensitive, substring) do contrato §3.4 — mesmo padrao ja usado
 * em {@code EventoRepository}/{@code ProdutoRepository}. O {@code existsById} herdado serve a validacao
 * referencial no vinculo N:N (T-M5-4).
 */
@Repository
public interface ClienteRepository extends JpaRepository<Cliente, Long> {

    Page<Cliente> findByNomeContainingIgnoreCase(String nome, Pageable pageable);

    /**
     * Ids dos produtos vinculados ao cliente (detalhe {@code GET /{id}} — §3.5/CA-2), ordenados. Query
     * <b>nativa</b> dedicada sobre {@code cliente_produto}: o N:N nao e mapeado na @Entity (AD-SQ-44), a
     * lista nunca o materializa; so o detalhe le por aqui.
     */
    @Query(value = "SELECT produto_id FROM cliente_produto WHERE cliente_id = :id "
            + "ORDER BY produto_id", nativeQuery = true)
    List<Long> findProdutoIdsByClienteId(@Param("id") Long id);
}
