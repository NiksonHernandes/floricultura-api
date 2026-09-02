package com.floricultura.api.repository;

import com.floricultura.api.domain.MovimentacaoEstoque;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Le o {@code criado_em} (do {@code DEFAULT now()} do banco) de uma linha recem-inserida, na mesma
     * transacao da movimentacao (T-M2-4). A coluna e {@code insertable=false}, entao a entidade em
     * memoria fica com {@code criadoEm=null} apos o INSERT; esta <b>projecao escalar</b> executa um
     * SELECT que <b>nao</b> e servido pelo cache de 1o nivel (ao contrario de {@code findById}),
     * trazendo o valor real gravado pelo banco — sem precisar de {@code EntityManager.refresh} (que
     * exigiria um EMF e quebraria os smokes do M0 que excluem JPA).
     */
    @Query("select m.criadoEm from MovimentacaoEstoque m where m.id = :id")
    Instant findCriadoEmById(@Param("id") Long id);
}
