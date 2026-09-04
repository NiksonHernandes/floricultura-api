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
     * Lista GLOBAL paginada do ledger (M4/T-M4-10, AD-SQ-46, CA-22): filtro {@code q} opcional casando
     * {@code produto_nome} <b>OU</b> {@code usuario_nome} por {@code ILIKE '%q%'}. Query <b>nativa</b> de
     * proposito: o {@code ILIKE} na coluna crua e o que o indice <b>GIN trigram</b> da V8 acelera (um
     * {@code lower(col) LIKE} nao usaria o indice). {@code q} nulo/vazio ⇒ {@code :q IS NULL} ⇒ sem
     * filtro. A ordem {@code criado_em DESC} e <b>fixa no SQL</b> (sustentada por {@code ix_mov_criado_em}
     * da V1); o {@link Pageable} entra apenas com {@code LIMIT/OFFSET} — passe-o <b>sem</b> {@code Sort}
     * (o servico usa {@code Sort.unsorted()}). Paginacao <b>obrigatoria</b>: nunca {@code findAll()} sem
     * {@code Pageable} (CA-23).
     */
    @Query(value = "SELECT * FROM movimentacao_estoque m "
            + "WHERE :q IS NULL "
            + "OR m.produto_nome ILIKE '%' || :q || '%' "
            + "OR m.usuario_nome ILIKE '%' || :q || '%' "
            + "ORDER BY m.criado_em DESC",
            countQuery = "SELECT count(*) FROM movimentacao_estoque m "
            + "WHERE :q IS NULL "
            + "OR m.produto_nome ILIKE '%' || :q || '%' "
            + "OR m.usuario_nome ILIKE '%' || :q || '%'",
            nativeQuery = true)
    Page<MovimentacaoEstoque> buscarGlobal(@Param("q") String q, Pageable pageable);

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
