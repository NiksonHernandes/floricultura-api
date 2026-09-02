package com.floricultura.api.repository;

import com.floricultura.api.domain.Produto;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Carrega o produto com <b>lock pessimista de escrita</b> ({@code SELECT ... FOR UPDATE}) para a
     * movimentacao de estoque (T-M2-4, AD-SQ-30). A linha do produto fica travada ate o commit da
     * transacao: duas SAIDAS concorrentes serializam aqui, cada uma reavaliando o {@code estoque_atual}
     * ja atualizado pela anterior — impede que ambas passem na checagem e furem o estoque para negativo
     * (§4/§12). Deve ser chamado <b>dentro</b> de uma {@code @Transactional} (senao o lock nao se
     * sustenta). Vazio → produto inexistente (404).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Produto p where p.id = :id")
    Optional<Produto> findByIdForUpdate(@Param("id") Long id);
}
