package com.floricultura.api.repository;

import com.floricultura.api.domain.MovimentacaoEstoque;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Acesso a persistencia do ledger {@link MovimentacaoEstoque} (SPEC-M2 §8). Insert-only na pratica
 * (AD-SQ-8): {@code save} grava novas linhas; qualquer UPDATE/DELETE e barrado pela trigger de banco
 * {@code trg_movimentacao_imutavel}. {@code findByProdutoId} serve ao historico paginado
 * {@code criadoEm DESC} (§3.3) consumido na T-M2-4.
 */
@Repository
public interface MovimentacaoRepository extends JpaRepository<MovimentacaoEstoque, Long> {

    Page<MovimentacaoEstoque> findByProdutoId(Long produtoId, Pageable pageable);
}
